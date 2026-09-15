package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.util.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * 工作区预注册（Step 5 手工测试反馈：dsh 启动后 UI 默认工作区不是当前项目）。
 *
 * dsh 的 workspace 是显式注册制：`storages/workspace.json` 没有记录时，UI 显示
 * "选择一个工作区开始"。插件在 dsh 健康检查通过后调用内部 RPC
 * `POST /api/workspace/create`（payload `{path}`）把项目根注册为工作区（幂等：
 * 同路径重复调用返回既有实体），使 UI 一打开即默认选中当前项目。
 *
 * **切换项目修复（v0.1.3-dev 实测）**：`workspace.create` 幂等、**不改变**注册表
 * 显示顺序（workspace.json `workspaceIds`）；UI 侧边栏/新建会话选择器按该顺序显示，
 * 默认落点 = 列表第一个 workspace。因此切换项目后新项目仍是既有实体时，UI 默认仍
 * 落在旧项目 → 新建会话绑定旧项目工作区。修复：create 成功后调用
 * `POST /api/workspace/insertBefore`（payload `{workspaceId, beforeWorkspaceId}`，
 * dsh 0.1.0-rc.7 已暴露该 RPC）把当前项目挪到列表最前。
 *
 * 实测（dsh 0.1.0-rc.7）：127.0.0.1 loopback 信任围栏放行，无需鉴权头。
 */
object WorkspaceInitializer {

    private val LOG = Logger.getInstance(WorkspaceInitializer::class.java)

    /**
     * 调用 workspace.create + 把当前项目挪到显示顺序最前；成功返回 true。
     * 任一步失败不抛出（日志降级，UI 仍可用；最坏回退到旧行为）。
     */
    fun ensureWorkspace(webUrl: String, projectPath: String, homeDir: Path? = null): Boolean {
        if (projectPath.isBlank()) return false
        return try {
            // dsh 0.1.5 起：启动 URL 形如 `http://127.0.0.1:<port>/?token=<t>`，且所有 /api 请求必须携带
            // 浏览器鉴权 cookie（仅 loopback 信任不足以放行，实测无 cookie → 401）；旧版无 token → cookie 为 null。
            val base = originOf(webUrl)
            val cookie = bootstrapSessionCookie(webUrl)
            val path = projectPath.replace('\\', '/')
            // 1. 注册/复用当前项目 workspace（幂等）
            // 0.1.5：remote 参数需包在 `args.request` 中（gateway 按 descriptor 校验）
            val created = rpc(base, "workspace/create", mapOf("request" to mapOf("path" to path)), cookie)
            if (!created.ok) {
                LOG.warn("workspace/create failed: ${created.errorText}")
                return false
            }
            // 2. 挪到最前：UI 默认落点 = 列表第一个 workspace
            val workspaceId = extractWorkspaceId(created.value)
            if (workspaceId != null) bringToFront(base, workspaceId, cookie, homeDir)
            LOG.info("workspace.ensureWorkspace ok for $projectPath")
            true
        } catch (e: Exception) {
            LOG.warn("workspace.ensureWorkspace error for $projectPath", e)
            false
        }
    }

    /**
     * 纯逻辑（可单测）：给定当前 workspace 显示顺序与目标 id，
     * 返回 `(workspaceId, beforeWorkspaceId)` 使目标插到最前；已在最前/列表为空 → null。
     */
    fun computeBringToFront(currentOrder: List<String>, targetId: String): Pair<String, String>? {
        val first = currentOrder.firstOrNull() ?: return null
        return if (first == targetId) null else targetId to first
    }

    // ---- 内部实现 ----

    /** 等待 `storages/workspace.json` 中登记 [workspaceId]（create 落盘可能异步），返回其显示顺序。 */
    private fun waitWorkspaceOrder(homeDir: Path, workspaceId: String, timeoutMs: Long = 3000): List<String> {
        val end = System.currentTimeMillis() + timeoutMs
        var order = readWorkspaceOrder(homeDir)
        while (!order.contains(workspaceId) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return order
            }
            order = readWorkspaceOrder(homeDir)
        }
        return order
    }

    /** 读取工作区显示顺序：0.1.5 为 `global.workspaceIds`（unit version 2），兼容 0.1.1 的顶层 `workspaceIds`。 */
    private fun readWorkspaceOrder(homeDir: Path): List<String> = try {
        val file = homeDir.resolve("storages/workspace.json")
        if (!Files.isRegularFile(file)) emptyList() else {
            val root = JsonCodec.decodeObject(Files.readString(file))
            val global = root["global"] as? Map<*, *>
            val ids = (global?.get("workspaceIds") ?: root["workspaceIds"]) as? List<*>
            ids?.mapNotNull { it as? String }.orEmpty()
        }
    } catch (e: Exception) {
        LOG.warn("failed to read workspace order: ${e.message}")
        emptyList()
    }

    /** workspace.create 响应 → workspaceId。 */
    private fun extractWorkspaceId(value: Map<String, Any?>): String? =
        (value["workspace"] as? Map<*, *>)?.get("workspaceId") as? String

    /**
     * 把 [workspaceId] 挪到显示顺序最前。
     * 0.1.5 起 `workspace/list` RPC 已移除（列表仅经流式 `workspace/follow` 下发），
     * 故顺序改读 DSH_HOME 的 `storages/workspace.json`（v2：`global.workspaceIds`），再用 insertBefore 调整。
     */
    private fun bringToFront(base: String, workspaceId: String, cookie: String?, homeDir: Path?) {
        val order = homeDir?.let { waitWorkspaceOrder(it, workspaceId) }.orEmpty()
        val move = computeBringToFront(order, workspaceId) ?: return // 空列表或已在最前
        val moved = rpc(
            base,
            "workspace/insertBefore",
            mapOf(
                "request" to mapOf(
                    "workspaceId" to move.first,
                    "beforeWorkspaceId" to move.second,
                )
            ),
            cookie,
        )
        if (moved.ok) {
            LOG.info("workspace $workspaceId moved to front (order=${moved.value["workspaceIds"]})")
        } else {
            LOG.warn("workspace/insertBefore failed: ${moved.errorText}")
        }
    }

    private data class RpcResult(
        val ok: Boolean,
        val value: Map<String, Any?> = emptyMap(),
        val errorText: String = "",
    )

    /** 取 `http://host:port` origin（剥离 0.1.5 启动 URL 的 `?token=` 查询参数与尾斜杠）。 */
    private fun originOf(webUrl: String): String = webUrl.substringBefore('?').trimEnd('/')

    /**
     * dsh 0.1.5+ 浏览器鉴权引导：`GET /?token=<t>` 返回 303 + `Set-Cookie: dsh-auth-…`；
     * 之后所有 api 请求必须携带该 cookie（实测无 cookie → 401）。
     * 旧版启动 URL 不含 token → 返回 null，调用方按无鉴权处理（保持 0.1.1 行为）。
     */
    private fun bootstrapSessionCookie(webUrl: String): String? {
        if (!webUrl.contains("token=")) return null
        return try {
            val conn = URL(webUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            try {
                val code = conn.responseCode
                if (code in 200..399) {
                    // 注意：headerFields 的 key 保留服务端原始大小写（Node 发 `set-cookie`），必须忽略大小写查找。
                    val setCookies = conn.headerFields.entries
                        .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                        ?.value
                        .orEmpty()
                    setCookies.firstOrNull { it.startsWith("dsh-") }?.substringBefore(';')
                } else {
                    LOG.warn("browser auth bootstrap http $code")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            LOG.warn("browser auth bootstrap failed: ${e.message}")
            null
        }
    }

    /** 调用 dsh 内部 RPC（client-request 封装）；解析 `{ok, value?, error?}`。 */
    private fun rpc(base: String, method: String, payload: Map<String, Any?>, cookie: String? = null): RpcResult {
        val rpcId = "dsh-idea-" + UUID.randomUUID().toString()
        val body = gson(
            mapOf(
                "type" to "client-request",
                "rpcId" to rpcId,
                "method" to method,
                // dsh 0.1.5：remote payload 必须"恰好包含一个 plain-object `args` 字段"
                // （dsh-api-gateway: "Remote payload must contain exactly one plain-object args field"）
                "payload" to mapOf("args" to payload),
            )
        )
        val conn = URL("$base/api/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val resp = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            if (code !in 200..299) return RpcResult(false, errorText = "http $code: ${resp.take(200)}")
            val parsed = runCatching { JsonCodec.decodeObject(resp) }.getOrNull()
                ?: return RpcResult(false, errorText = "unparseable response: ${resp.take(200)}")
            // dsh RPC 响应实测结构：{"type":"server-response","rpcId":"...","result":{"ok":...,"value":...|"error":...}}
            val result = parsed["result"] as? Map<*, *>
                ?: return RpcResult(false, errorText = "unexpected response: ${resp.take(200)}")
            return if (result["ok"] == true) {
                RpcResult(true, (result["value"] as? Map<*, *>)?.let { cast(it) } ?: emptyMap())
            } else {
                RpcResult(false, errorText = (result["error"] as? String) ?: resp.take(200))
            }
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cast(m: Map<*, *>): Map<String, Any?> = m as Map<String, Any?>

    private fun gson(payload: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        payload.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(k).append("\":")
            when (v) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    sb.append(gson(v as Map<String, Any?>))
                }
                is String -> sb.append('"').append(escape(v)).append('"')
                else -> sb.append(v)
            }
        }
        return sb.append('}').toString()
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
