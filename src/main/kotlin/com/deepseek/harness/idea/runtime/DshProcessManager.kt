package com.deepseek.harness.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh Node 子进程生命周期管理。
 *
 * 启动命令（见 docs/DESIGN.md §4.3）：
 * `node <dsh>/lib/bin.js --profile web --patch <ide.yml> --host 127.0.0.1 --port 0`
 * cwd = 项目根目录；env: DSH_HOME=<home>。
 * 端口发现：逐行解析 stdout 的 `dsh web: http://127.0.0.1:<port>`，随后 HTTP 健康检查。
 * 崩溃：指数退避自动重启（≤3 次）；dispose() 时终止进程树。
 */
class DshProcessManager(
    private val nodeExe: File,
    private val dshBin: File,
    private val workDir: File,
    private val homeDir: File,
    private val patchFile: File,
    private val extraEnv: Map<String, String> = emptyMap(),
    /** 项目根目录绝对路径（用于启动后注册默认工作区，FR-04.2）。 */
    private val projectPath: String = "",
) : Disposable {

    enum class State { STOPPED, STARTING, RUNNING, CRASHED }

    interface Listener {
        fun onStateChanged(oldState: State, newState: State) = Unit
        fun onUrlReady(url: String) = Unit
        fun onLogLine(line: String) = Unit
    }

    companion object {
        private val LOG = Logger.getInstance(DshProcessManager::class.java)
        private const val MAX_RESTART_ATTEMPTS = 3
        private val RESTART_DELAYS_MS = longArrayOf(500, 2000, 5000)
        private const val HEALTH_MAX_TRIES = 20
        private const val HEALTH_INTERVAL_MS = 500L
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { r ->
        Thread(r, "dsh-process").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile private var currentState: State = State.STOPPED
    @Volatile private var webPort: Int? = null

    /** dsh 0.1.5+ 打印的完整启动 URL（含 `?token=` 浏览器鉴权参数）；旧版为不含 token 的 base。 */
    @Volatile private var launchUrl: String? = null
    private var restartAttempts = 0

    fun currentState(): State = currentState
    fun webPort(): Int? = webPort
    fun webUrl(): String? = launchUrl ?: webPort()?.let { "http://127.0.0.1:$it" }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(State.STOPPED, currentState)
    }

    /** 从 STOPPED/CRASHED 启动。 */
    fun start() {
        synchronized(this) {
            if (currentState == State.RUNNING || currentState == State.STARTING) return
            setState(State.STARTING)
        }
        spawn()
    }

    /** 手动重启：无论当前状态，先停旧进程再拉起。 */
    fun restart() {
        synchronized(this) {
            stopProcessQuietly()
            restartAttempts = 0
            setState(State.STARTING)
        }
        spawn()
    }

    override fun dispose() {
        stopRequested.set(true)
        stopProcessQuietly()
        executor.shutdownNow()
    }

    // ---- 内部实现 ----

    private fun spawn() {
        // 注意：--patch 是启动器（launcher）选项，必须位于 web 应用自己的
        // --host/--port 之前；放在后面会被 web 应用当成未知选项拒绝。
        val cmd = listOf(
            nodeExe.absolutePath,
            dshBin.absolutePath,
            "--profile", "web",
            "--patch", patchFile.absolutePath,
            "--host", "127.0.0.1",
            "--port", "0",
            // dsh web 默认会把 Web UI 打开到系统默认浏览器；内嵌于 IDE 工具窗内，
            // 无需（也不应）弹外部浏览器 —— 显式禁用。
            "--no-open",
        )
        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)
        pb.environment()["DSH_HOME"] = homeDir.absolutePath
        extraEnv.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)

        val p = try {
            pb.start()
        } catch (e: Exception) {
            LOG.error("failed to start dsh process", e)
            synchronized(this) { setState(State.CRASHED) }
            scheduleRestart()
            return
        }
        process = p
        webPort = null
        launchUrl = null
        LOG.info("dsh process started pid=${p.pid()} cwd=${workDir.absolutePath} home=$homeDir")
        readAsync(p.inputStream)
        p.onExit().whenComplete { _, err -> onProcessExit(p, err) }
    }

    private fun readAsync(stream: InputStream) {
        executor.execute {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    if (line.contains("dsh web:")) {
                        LOG.info("[dsh] $line")
                    } else {
                        LOG.debug("[dsh] $line")
                    }
                    listeners.forEach { it.onLogLine(line) }
                    PortParser.parsePort(line)?.let { port -> onPortFound(port, PortParser.parseUrl(line)) }
                }
            }
        }
    }

    private fun onPortFound(port: Int, url: String? = null) {
        if (webPort != null || stopRequested.get()) return
        webPort = port
        launchUrl = url
        executor.execute { waitHealthy(port) }
    }

    private fun waitHealthy(port: Int) {
        // 0.1.5+：健康检查必须带启动 URL 的 token（不带 token 的 `/` 返回 401）；旧版无 token 时回退到 `/`。
        val url = launchUrl?.takeIf { it.contains("token=") } ?: "http://127.0.0.1:$port/"
        repeat(HEALTH_MAX_TRIES) {
            if (stopRequested.get()) return
            if (isHealthy(url)) {
                synchronized(this) {
                    restartScheduled.set(false)
                    restartAttempts = 0
                    setState(State.RUNNING)
                }
                val webUrl = launchUrl ?: "http://127.0.0.1:$port"
                LOG.info("dsh web ready: $webUrl")
                listeners.forEach { it.onUrlReady(webUrl) }
                // FR-04.2：把项目根注册为默认工作区（幂等；失败仅降级，不阻塞 UI）
                if (projectPath.isNotBlank()) {
                    WorkspaceInitializer.ensureWorkspace(webUrl, projectPath, homeDir.toPath())
                }
                return
            }
            try { Thread.sleep(HEALTH_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
        LOG.warn("dsh health check timed out on port $port")
        if (!stopRequested.get()) {
            synchronized(this) { setState(State.CRASHED) }
            scheduleRestart()
        }
    }

    private fun isHealthy(url: String): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        // 0.1.5 的 `/?token=` 返回 303；禁止跟随重定向即可把 3xx 视为"服务已就绪"。
        // 无 token（或 token 失效）时服务端返回 401，同样说明 HTTP 已在监听，不应判定启动失败。
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399 || code == 401
    } catch (_: Exception) {
        false
    }

    private fun onProcessExit(p: Process, err: Throwable?) {
        if (process !== p) return
        process = null
        if (stopRequested.get()) {
            synchronized(this) { setState(State.STOPPED) }
        } else {
            LOG.warn("dsh process exited unexpectedly pid=${p.pid()}", err)
            synchronized(this) { setState(State.CRASHED) }
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (stopRequested.get()) return
        synchronized(this) {
            if (restartScheduled.get()) return
            if (restartAttempts >= MAX_RESTART_ATTEMPTS) return // 停在 CRASHED，等用户手动重启
            restartAttempts++
            restartScheduled.set(true)
        }
        val delay = RESTART_DELAYS_MS[(restartAttempts - 1).coerceAtMost(RESTART_DELAYS_MS.size - 1)]
        LOG.info("scheduling dsh restart attempt $restartAttempts in ${delay}ms")
        executor.schedule({
            restartScheduled.set(false)
            if (stopRequested.get()) return@schedule
            synchronized(this) { setState(State.STARTING) }
            spawn()
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun stopProcessQuietly() {
        val p = process ?: run { synchronized(this) { setState(State.STOPPED) }; return }
        process = null
        try {
            p.destroy()
            p.waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        if (p.isAlive) killTree(p.pid())
        synchronized(this) { setState(State.STOPPED) }
    }

    private fun killTree(pid: Long) {
        try {
            if (Platform.current().os == Platform.Os.WINDOWS) {
                // taskkill /T /F 终止整棵进程树（Windows 兜底，防残留）
                ProcessBuilder(listOf("taskkill", "/PID", pid.toString(), "/T", "/F"))
                    .start().waitFor(3, TimeUnit.SECONDS)
            } else {
                killProcessTree(pid)
            }
        } catch (e: Exception) {
            LOG.warn("failed to kill process tree $pid", e)
        }
    }

    /** Unix：用 [ProcessHandle] 终止进程树（父 + 全部后代），避免 `kill -9 <pid>` 只杀父进程残留 worker。 */
    private fun killProcessTree(pid: Long) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        handle.descendants().forEach { runCatching { it.destroyForcibly() } }
        runCatching { handle.destroyForcibly() }
    }

    private fun setState(newState: State) {
        val old = currentState
        if (old == newState) return
        currentState = newState
        listeners.forEach { it.onStateChanged(old, newState) }
    }
}
