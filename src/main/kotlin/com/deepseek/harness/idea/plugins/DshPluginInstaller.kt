package com.deepseek.harness.idea.plugins

import com.deepseek.harness.idea.runtime.Platform
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * dsh 插件包的安装/卸载（v0.2.5）。
 *
 * **实现方式：转发给 dsh 自己的 `plugin` 子命令**，而不是直接调 pnpm ——
 * `dsh plugin --profile web add <spec>`（`@deepseek-ai/dsh/lib/plugin-*.js`）做了三件我们不想
 * 重复实现的事：
 * 1. profile 首次使用时按模板初始化（`dsh.profile.bundles` + `patchReload`）；
 * 2. 把相对路径规格（`.`、`../p`、`file:./p`）**锚定到调用者的当前目录**（否则 pnpm 以 profile 目录
 *    为 cwd，`add .` 会把 profile 自己链接进去）；
 * 3. 安装完成后 `reconcilePlugins`：把"声明了 `dsh.bundle` 的依赖"追加进 `dsh.profile.bundles`、
 *    把不再是 bundle 的依赖移除。**包名以实际装出来的名字为准**（`link:`/git/tarball 规格也一样）。
 *
 * **唯一的前置条件**：`runPlugin` 用 `spawnSync("pnpm", …)` 转发，PATH 里必须有 `pnpm`。插件用户的
 * 机器上通常没有 pnpm，因此这里在共享根下准备一份**私有 pnpm**（`<共享根>/plugin-tools`，
 * 用运行时自带的 npm 装一次，之后离线复用），并把它的 shim 目录前置到子进程 PATH ——
 * 不改用户的全局环境，也不碰运行时树（运行时树可能只读）。
 *
 * **可回滚**：安装前把 profile 的 `package.json` / `pnpm-lock.yaml` / `cordis.patch.yml` /
 * `pnpm-workspace.yaml` 备份到 `<共享根>/plugin-backups/<时间戳>/`；命令失败则回写这些文件
 * （插件包本身留在 `node_modules` 里无害：manifest 没声明就不会被加载）。
 */
internal object DshPluginInstaller {

    private val LOG = Logger.getInstance(DshPluginInstaller::class.java)

    /** 私有 pnpm 版本（与实测可用的 `npx pnpm@9` 一致）。 */
    const val PNPM_VERSION = "9.15.9"

    /** 共享根下存放私有 pnpm 的目录名。 */
    const val TOOLS_DIR = "plugin-tools"

    /** 共享根下存放安装前备份的目录名。 */
    const val BACKUPS_DIR = "plugin-backups"

    /** 私有 pnpm 的 shim 目录名（相对 [TOOLS_DIR]）。 */
    const val SHIM_DIR = "bin"

    /** 单次安装/卸载的超时（含依赖下载；大包也可能超过 2 分钟）。 */
    const val TIMEOUT_SECONDS = 900L

    /** 安装前备份的 profile 文件（存在才备份）。 */
    val BACKUP_FILES = listOf(
        DshProfileManifest.FILE_NAME,
        "pnpm-lock.yaml",
        "cordis.patch.yml",
        "pnpm-workspace.yaml",
    )

    /** 失败原因（供 UI 映射文案）。 */
    enum class Failure {
        /** 运行时缺失（node 或 dsh 编排文件不存在）。 */
        NO_RUNTIME,

        /** 私有 pnpm 准备失败（无网络 / npm 不可用）。 */
        PNPM_SETUP_FAILED,

        /** 安装/卸载命令非零退出。 */
        COMMAND_FAILED,

        /** 超时被杀。 */
        TIMEOUT,

        /** 其它异常（IO 等）。 */
        ERROR,
    }

    /** 一次安装/卸载的结果。 */
    data class Outcome(
        val ok: Boolean,
        /** 命令输出（stdout+stderr 合并，供 UI 展示/排错）。 */
        val output: String,
        /** 操作后的 profile 视图（失败时为备份前的状态）。 */
        val profile: DshProfileManifest.Profile = DshProfileManifest.Profile.EMPTY,
        val failure: Failure? = null,
        /** 失败摘要（已足够人类可读，不含 i18n）。 */
        val detail: String? = null,
    )

    /** 串行化：两个 IDE 窗口同时装插件会把同一个 profile 的 lockfile 写坏。 */
    private val lock = ReentrantLock()

    // ---- 纯逻辑（可单测） ----

    /** 私有 pnpm 的安装目录：`<共享根>/plugin-tools`。 */
    fun toolsDir(sharedRoot: Path): Path = sharedRoot.resolve(TOOLS_DIR)

    /** 私有 pnpm 的 shim 目录：`<共享根>/plugin-tools/bin`。 */
    fun shimDir(sharedRoot: Path): Path = toolsDir(sharedRoot).resolve(SHIM_DIR)

    /** 备份根：`<共享根>/plugin-backups`。 */
    fun backupsRoot(sharedRoot: Path): Path = sharedRoot.resolve(BACKUPS_DIR)

    /** 某次安装的备份目录：`<共享根>/plugin-backups/<stamp>`。 */
    fun backupDir(sharedRoot: Path, stamp: String): Path = backupsRoot(sharedRoot).resolve(stamp)

    /** shim 文件名（Windows 需要 `.cmd` 扩展名才能被 cmd 解析）。 */
    fun shimName(os: Platform.Os): String = if (os == Platform.Os.WINDOWS) "pnpm.cmd" else "pnpm"

    /** 私有 pnpm 的可执行入口（npm 布局：`node_modules/pnpm/bin/pnpm.cjs`）。 */
    fun pnpmCli(toolsDir: Path): Path = toolsDir.resolve("node_modules/pnpm/bin/pnpm.cjs")

    /**
     * `dsh plugin` 的子命令参数（`plugin` 是 dsh bin 的子命令，不是 pnpm 的）。
     *
     * [quoteSpec] 为真时把包规格包进双引号：Windows 上 dsh 用 `shell: true` 转发，`cmd` 会把
     * `^`（semver 的"兼容版本"前缀）当转义符吃掉，加引号后实测能原样传进 pnpm。
     */
    fun dshArgs(action: String, spec: String?, quoteSpec: Boolean = false): List<String> = buildList {
        add("plugin")
        add("--profile")
        add(DshProfileManifest.PROFILE_NAME)
        add(action)
        if (!spec.isNullOrEmpty()) add(if (quoteSpec) "\"$spec\"" else spec)
    }

    /**
     * 把 shim 目录前置进 PATH。
     *
     * Windows 的环境变量名是不区分大小写的（`System.getenv()` 里常见的是 `Path`），而
     * `ProcessBuilder.environment()` 只有**一份**键 —— 因此这里统一成 `PATH` 并去掉其它大小写变体，
     * 避免两份键并存导致"到底哪份生效"不确定。
     */
    fun withShimPath(env: Map<String, String>, shimDir: Path): Map<String, String> {
        val existing = env.entries.firstOrNull { it.key.equals("PATH", ignoreCase = true) }?.value.orEmpty()
        val merged = if (existing.isEmpty()) shimDir.toString() else shimDir.toString() + File.pathSeparator + existing
        return env.filterKeys { !it.equals("PATH", ignoreCase = true) } + ("PATH" to merged)
    }

    /** 备份目录名：`yyyyMMdd-HHmmss-SSS`（毫秒后缀避免同一秒内两次操作互相覆盖）。 */
    fun stamp(now: Long): String {
        val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
        return java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(t)
    }

    /** 读取某项目的 profile manifest（经 `<项目 DSH_HOME>/profiles/web`，共享开启时即共享目录）。 */
    fun readProfile(projectHome: Path): DshProfileManifest.Profile {
        val file = projectHome.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME).resolve(DshProfileManifest.FILE_NAME)
        if (!Files.isRegularFile(file)) return DshProfileManifest.Profile.EMPTY
        return DshProfileManifest.parse(runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrNull())
    }

    /** 用某项目的 profile manifest 文本解析（供 UI 直接喂文本，避免重复读盘）。 */
    fun parseProfile(text: String?): DshProfileManifest.Profile = DshProfileManifest.parse(text)

    // ---- 安装 / 卸载 ----

    /**
     * 安装（[action] = `add`）或卸载（`remove`）一个插件包。
     *
     * @param projectHome 项目的 DSH_HOME（`DSH_HOME` 环境变量指向它，dsh 据此解析 profile 目录）。
     * @param sharedRoot 共享配置根（私有 pnpm 与备份都放这里；与项目无关）。
     * @param nodeExe 运行时 node。
     * @param dshBin 运行时的 `@deepseek-ai/dsh/lib/bin.js`。
     */
    fun run(
        projectHome: Path,
        sharedRoot: Path,
        nodeExe: Path,
        dshBin: Path,
        action: String,
        spec: String?,
    ): Outcome {
        val profileDir = projectHome.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
        val before = readProfile(projectHome)
        if (!Files.isRegularFile(nodeExe) || !Files.isRegularFile(dshBin)) {
            return Outcome(false, "", before, Failure.NO_RUNTIME, "missing runtime: node=$nodeExe dsh=$dshBin")
        }
        if (!lock.tryLock(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return Outcome(false, "", before, Failure.COMMAND_FAILED, "another plugin operation is still running")
        }
        try {
            Files.createDirectories(profileDir)
            val pnpm = try {
                ensurePnpm(sharedRoot, nodeExe)
            } catch (e: Exception) {
                LOG.warn("failed to prepare the private pnpm under $sharedRoot", e)
                return Outcome(false, "", before, Failure.PNPM_SETUP_FAILED, e.message)
            }

            val backup = backupProfile(profileDir, sharedRoot)
            val quoteSpec = Platform.current().os == Platform.Os.WINDOWS &&
                spec != null && DshProfileManifest.needsWindowsQuote(spec)
            val cmd = listOf(nodeExe.toString(), dshBin.toString()) + dshArgs(action, spec, quoteSpec)
            val env = withShimPath(System.getenv(), pnpm).toMutableMap().apply {
                put("DSH_HOME", projectHome.toString())
                // node 自身也在 PATH 里：pnpm 执行生命周期脚本时会用到
                put("PATH", nodeExe.parent.toString() + File.pathSeparator + getValue("PATH"))
            }
            val (code, output) = exec(cmd, profileDir, env)
            if (code != 0) {
                restoreProfile(backup, profileDir)
                val timedOut = code == TIMEOUT_EXIT
                LOG.warn("dsh plugin $action failed (exit=$code) for $profileDir; profile manifest restored")
                return Outcome(
                    ok = false,
                    output = output,
                    profile = readProfile(projectHome),
                    failure = if (timedOut) Failure.TIMEOUT else Failure.COMMAND_FAILED,
                    detail = "exit=$code",
                )
            }
            val after = readProfile(projectHome)
            LOG.info(
                "dsh plugin $action ok for $profileDir: bundles=${after.bundles.size} " +
                    "plugins=${after.plugins} deps=${after.dependencies.size} backup=$backup",
            )
            return Outcome(true, output, after)
        } catch (e: Exception) {
            LOG.warn("dsh plugin $action crashed for $profileDir", e)
            return Outcome(false, "", readProfile(projectHome), Failure.ERROR, e.message ?: e.toString())
        } finally {
            lock.unlock()
        }
    }

    /** 子进程被超时杀掉时使用的退出码哨兵（真实进程不会返回它）。 */
    private const val TIMEOUT_EXIT = -99

    /**
     * 准备私有 pnpm（首次会联网 `npm install pnpm@<ver>`，之后直接复用），返回 shim 目录。
     *
     * 用运行时**自带的 npm**而不是系统 npm：插件保证运行时内有 node+npm，且版本可控。
     */
    private fun ensurePnpm(sharedRoot: Path, nodeExe: Path): Path {
        val tools = toolsDir(sharedRoot)
        val shim = shimDir(sharedRoot)
        val cli = pnpmCli(tools)
        val os = Platform.current().os
        val shimFile = shim.resolve(shimName(os))
        if (Files.isRegularFile(cli) && Files.isRegularFile(shimFile)) return shim

        val npmCli = nodeExe.parent.resolve("node_modules/npm/bin/npm-cli.js")
        if (!Files.isRegularFile(npmCli)) throw java.io.IOException("npm-cli.js not found next to $nodeExe (looked at $npmCli)")

        Files.createDirectories(tools)
        val (code, output) = exec(
            listOf(
                nodeExe.toString(), npmCli.toString(), "install",
                "--prefix", tools.toString(),
                "pnpm@$PNPM_VERSION",
                "--no-audit", "--no-fund", "--loglevel=error",
            ),
            tools,
            System.getenv(),
        )
        if (code != 0 || !Files.isRegularFile(cli)) {
            throw java.io.IOException("npm install pnpm@$PNPM_VERSION failed (exit=$code): ${output.takeLast(600)}")
        }
        writeShim(shimFile, nodeExe, cli, os)
        LOG.info("prepared a private pnpm $PNPM_VERSION at $tools")
        return shim
    }

    /** 写 `pnpm` shim（Windows=`pnpm.cmd`，其它平台=`pnpm` 可执行脚本）。 */
    private fun writeShim(shimFile: Path, nodeExe: Path, cli: Path, os: Platform.Os) {
        Files.createDirectories(shimFile.parent)
        val body = if (os == Platform.Os.WINDOWS) {
            "@echo off\r\n\"${nodeExe}\" \"$cli\" %*\r\n"
        } else {
            "#!/bin/sh\nexec \"$nodeExe\" \"$cli\" \"\$@\"\n"
        }
        Files.writeString(shimFile, body, StandardCharsets.UTF_8)
        if (os != Platform.Os.WINDOWS) {
            runCatching {
                Files.setPosixFilePermissions(
                    shimFile,
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
                    ),
                )
            }
        }
    }

    /** 备份 profile 的声明文件；返回备份目录（无文件可备份时仍返回，便于日志定位）。 */
    private fun backupProfile(profileDir: Path, sharedRoot: Path): Path {
        val dir = backupDir(sharedRoot, stamp(System.currentTimeMillis()))
        Files.createDirectories(dir)
        BACKUP_FILES.forEach { name ->
            val src = profileDir.resolve(name)
            if (Files.isRegularFile(src)) {
                runCatching { Files.copy(src, dir.resolve(name), StandardCopyOption.COPY_ATTRIBUTES) }
                    .onFailure { LOG.warn("failed to back up $src", it) }
            }
        }
        return dir
    }

    /** 失败回滚：把备份里的文件写回 profile（`node_modules` 不回滚，未被 manifest 声明即不会加载）。 */
    private fun restoreProfile(backup: Path, profileDir: Path) {
        BACKUP_FILES.forEach { name ->
            val saved = backup.resolve(name)
            if (Files.isRegularFile(saved)) {
                runCatching { Files.copy(saved, profileDir.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
                    .onFailure { LOG.warn("failed to restore $name from $backup", it) }
            }
        }
    }

    /** 执行命令并合并输出；超时则杀进程树并返回 [TIMEOUT_EXIT]。 */
    private fun exec(cmd: List<String>, cwd: Path, env: Map<String, String>): Pair<Int, String> {
        val pb = ProcessBuilder(cmd)
        pb.directory(cwd.toFile())
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) { output.append(line).append('\n') }
                    }
                }
            }
        }.apply { isDaemon = true; name = "dsh-plugin-io"; start() }
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(10, TimeUnit.SECONDS) }
            runCatching { reader.join(2000) }
            return TIMEOUT_EXIT to synchronized(output) { output.toString() }
        }
        runCatching { reader.join(5000) }
        return process.exitValue() to synchronized(output) { output.toString() }
    }
}
