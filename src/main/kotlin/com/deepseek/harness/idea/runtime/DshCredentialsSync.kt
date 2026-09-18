package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh Web UI 改 API Key 的**文件监听同步器**（v0.1.3-dev ~ v0.2.3 的"方案 B"）。
 *
 * **v0.2.4 起已被共享配置取代，不再需要跨目录同步**：
 * dsh 与插件现在读写**同一个**共享凭据文档（`<共享根>/.credentials.yaml`，由 `ide.yml` 的
 * `- id: credentials` patch 指向）—— Web UI 改 key 立即落在该文件，插件的设置页与
 * [DshHomeManager.syncCredentials] 也写同一个文件，因此不存在"项目副本与全局真源不一致"的问题。
 *
 * 保留本类只为两件事：
 * 1. [register] 作为**空操作**保留（幂等），避免旧调用点/第三方代码因方法缺失而编译失败；
 * 2. [onFileChanged] 若被显式调用（历史遗留路径），只把读到的 key 写回 PasswordSafe
 *    （应用级真源），**绝不写共享文件**——v0.1.3-dev 的旧实现用扁平 layout 整份覆写，
 *    会丢掉其它 provider 的 `refs` 与 `records`（见 docs/PROJECT_NOTES.md）。
 *
 * @deprecated v0.2.4：配置已全局共享，无需同步器。
 */
@Deprecated("v0.2.4 起配置面（含凭据）由共享配置根统一承载，不再需要跨目录同步")
class DshCredentialsSync(private val projectCredFile: Path) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private var watchService: java.nio.file.WatchService? = null
    private var executor: java.util.concurrent.ExecutorService? = null

    /**
     * 启动监听（已废弃：共享配置下不再需要）。
     * 保留实现以便显式调用时仍可工作，但 [register] 已不再调用它。
     */
    fun start() {
        if (closed.get()) return
        if (watchService != null) return
        val dir = projectCredFile.parent ?: return
        try {
            val ws = FileSystems.getDefault().newWatchService()
            dir.register(ws, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE)
            watchService = ws
            val ex = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-cred-sync").apply { isDaemon = true } }
            executor = ex
            ex.execute { loop(ws) }
            LOG.info("watching project credentials: $projectCredFile")
        } catch (e: Exception) {
            LOG.warn("failed to watch project credentials $projectCredFile", e)
        }
    }

    private fun loop(ws: java.nio.file.WatchService) {
        while (!closed.get()) {
            try {
                val key: WatchKey = ws.take()
                if (closed.get()) break
                val fileName = projectCredFile.fileName.toString()
                var relevant = false
                for (event in key.pollEvents()) {
                    val ctx = event.context() as? Path
                    if (ctx != null && ctx.fileName.toString() == fileName) relevant = true
                }
                key.reset()
                if (relevant && Files.isRegularFile(projectCredFile)) {
                    runCatching { onFileChanged() }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (!closed.get()) LOG.warn("credential watch loop error", e)
            }
        }
    }

    /**
     * 文件变化：只把 key 写回 PasswordSafe（应用级真源）。
     * **不写共享凭据文件**——[DshHomeManager.syncCredentials] 才是唯一写入路径（合并式，保留其它条目）。
     */
    internal fun onFileChanged() {
        val key = DshCredentials.readApiKeyFromCredentialFile(projectCredFile) ?: return
        if (key == DshCredentials.readApiKey()) return
        DshCredentials.writeApiKey(key)
        LOG.info("imported API key from $projectCredFile into PasswordSafe (shared-config mode)")
    }

    /** 纯逻辑（可单测）：比较两个 key，不同则返回应回写，否则返回 null。 */
    internal fun resolveSync(projectKey: String?, globalKey: String?): String? =
        if (projectKey.isNullOrEmpty() || projectKey == globalKey) null else projectKey

    /** 停止监听（幂等，可多次调用）。 */
    fun closeProject() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { watchService?.close() }
        watchService = null
        runCatching { executor?.shutdownNow() }
        executor = null
    }

    override fun close() = closeProject()

    companion object {
        private val LOG = Logger.getInstance(DshCredentialsSync::class.java)

        /** 项目名 → 曾登记的同步器（v0.2.4 起为空，保留以兼容旧调用点）。 */
        private val active = ConcurrentHashMap<String, DshCredentialsSync>()

        /**
         * v0.2.4 起为**空操作**：配置面已在共享配置根，dsh 与插件读写同一份文档，无需监听同步。
         * 保留签名以免旧调用点编译失败。
         */
        fun register(projectName: String, credFile: Path): DshCredentialsSync? {
            LOG.info("DshCredentialsSync is deprecated since v0.2.4 (shared config); ignoring register for $projectName")
            return active.remove(projectName)
        }

        /** 释放某项目监听（幂等；v0.2.4 起通常无登记）。 */
        fun release(projectName: String) {
            active.remove(projectName)?.closeProject()
        }
    }
}
