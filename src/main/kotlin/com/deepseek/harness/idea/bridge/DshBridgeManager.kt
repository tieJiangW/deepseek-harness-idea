package com.deepseek.harness.idea.bridge

import com.deepseek.harness.idea.mcp.McpPatchGenerator
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP 桥接编排（Step 3）：
 * 1. 启动 [IdeBridgeServer]（随机端口 + token）；
 * 2. 把 mcp-ide-server.mjs 部署到 DSH_HOME 并启动其 Node 子进程（随机 MCP 端口）；
 * 3. 用 [McpPatchGenerator] 生成 ide.yml（--patch 覆盖层），供 [DshProcessManager] 启动 dsh 时注入；
 * 4. 环境变量 DSH_IDE_BRIDGE_URL / DSH_IDE_TOKEN 由 [DshProcessManager] 透传给 dsh 与 mcp server。
 *
 * 生命周期：与 DshProcessManager 同生命周期（工具窗口项目级 Disposable）。
 */
class DshBridgeManager(
    private val project: Project,
    private val nodeExe: File,
    private val workDir: File,
    private val homeDir: Path,
) : Disposable {

    private val bridge: IdeBridgeServer = IdeBridgeServer(project, IdeBridgeServer.randomToken())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-mcp-server").apply { isDaemon = true } }
    private val disposed = AtomicBoolean(false)

    @Volatile private var mcpProcess: Process? = null
    @Volatile private var mcpPort: Int = 0

    init {
        executor.execute { startMcpServer() }
    }

    /** Bridge 基础地址（127.0.0.1:随机端口）。 */
    fun bridgeUrl(): String = bridge.baseUrl()

    fun bridgeToken(): String = bridge.token()

    /** 推送选中代码到 sent-selection 队列（Step 4 发送动作；无需等待 MCP 就绪）。 */
    fun pushSentSelection(filePath: String?, language: String?, selection: String, lineStart: Int = 0, lineEnd: Int = 0): String =
        bridge.pushSentSelection(filePath, language, selection, lineStart, lineEnd)

    /** MCP server 端口（0 = 尚未就绪/失败）。 */
    fun mcpPort(): Int = mcpPort

    /** 生成并写入 ide.yml（幂等；每次启动重新生成以反映最新 mcpPort）。 */
    fun writePatch(): Path {
        val port = mcpPort
        require(port > 0) { "MCP server not ready" }
        // 共享配置根（v0.2.4）：patch 把 dsh 的 settings / credentials / agent-presets / 用户技能
        // 全部指向该目录（`- id: <rowId>` 整份覆盖形态），实现"配置共享 + 数据隔离"。
        val sharedRoot = com.deepseek.harness.idea.runtime.DshHomeManager.getInstance().sharedConfigRoot().toString()
        val patch = McpPatchGenerator.generate(port, sharedRoot)
        val file = homeDir.resolve("ide.yml")
        Files.createDirectories(file.parent)
        Files.writeString(file, patch, StandardCharsets.UTF_8)
        LOG.info("wrote ide.yml (mcpPort=$port, sharedConfigRoot=$sharedRoot) -> $file")
        return file
    }

    private fun startMcpServer() {
        try {
            // v0.2.4：脚本全局唯一一份，部署在运行时根（依赖由运行时树的 node_modules 解析，
            // 不再需要项目级 node_modules junction）。见 DshHomeManager.ensureMcpServerScript。
            val script = com.deepseek.harness.idea.runtime.DshHomeManager.getInstance().mcpServerScript()
            if (!Files.isRegularFile(script)) {
                LOG.error("mcp-ide-server.mjs not deployed: $script")
                return
            }
            val cmd = listOf(
                nodeExe.absolutePath,
                script.toString(),
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(workDir)
            pb.environment()["DSH_IDE_BRIDGE_URL"] = bridge.baseUrl()
            pb.environment()["DSH_IDE_TOKEN"] = bridge.token()
            pb.environment()["DSH_MCP_PORT"] = "0"
            pb.environment()["DSH_MCP_HOST"] = "127.0.0.1"
            pb.redirectErrorStream(true)
            val p = pb.start()
            mcpProcess = p
            // 解析 "listening on http://127.0.0.1:<port>/mcp"
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val m = Regex("""listening on http://127\.0\.0\.1:(\d+)/mcp""").find(line)
                    if (m != null) {
                        mcpPort = m.groupValues[1].toInt()
                        LOG.info("mcp-ide-server ready on port ${mcpPort}")
                    } else {
                        LOG.debug("[mcp-server] $line")
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("failed to start mcp-ide-server", e)
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        bridge.dispose()
        mcpProcess?.destroy()
        mcpProcess = null
        executor.shutdownNow()
    }

    companion object {
        private val LOG = Logger.getInstance(DshBridgeManager::class.java)
    }
}
