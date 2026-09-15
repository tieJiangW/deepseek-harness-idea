package com.deepseek.harness.idea.runtime

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

class WorkspaceInitializerTest {

    private var server: HttpServer? = null

    @TempDir
    lateinit var tempHome: Path

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    // ---- 纯逻辑：computeBringToFront ----

    @Test
    fun `computeBringToFront empty order no move`() {
        assertNull(WorkspaceInitializer.computeBringToFront(emptyList(), "ws-b"))
    }

    @Test
    fun `computeBringToFront already first no move`() {
        assertNull(WorkspaceInitializer.computeBringToFront(listOf("ws-b", "ws-a"), "ws-b"))
    }

    @Test
    fun `computeBringToFront target in middle moves before first`() {
        assertEquals("ws-b" to "ws-a", WorkspaceInitializer.computeBringToFront(listOf("ws-a", "ws-b", "ws-c"), "ws-b"))
    }

    @Test
    fun `computeBringToFront target at end moves before first`() {
        assertEquals("ws-c" to "ws-a", WorkspaceInitializer.computeBringToFront(listOf("ws-a", "ws-b", "ws-c"), "ws-c"))
    }

    @Test
    fun `computeBringToFront single element no move`() {
        assertNull(WorkspaceInitializer.computeBringToFront(listOf("ws-a"), "ws-a"))
    }

    // ---- 链路：create → 读取 workspace.json 顺序 → insertBefore ----

    @Test
    fun `ensureWorkspace posts workspace create rpc`() {
        var receivedPath: String? = null
        var receivedMethod: String? = null
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/workspace/create") { ex ->
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            receivedPath = Regex(""""path":"([^"]+)"""").find(body)?.groupValues?.get(1)
            receivedMethod = Regex(""""method":"([^"]+)"""").find(body)?.groupValues?.get(1)
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspace":{"path":"$receivedPath"},"created":true}}}""")
        }
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv

        val ok = WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:${srv.address.port}", "D:/proj/MyApp")
        assertTrue(ok, "ensureWorkspace should return true on ok response")
        assertTrue(receivedMethod == "workspace/create", "should call workspace/create, got $receivedMethod")
        assertTrue(receivedPath == "D:/proj/MyApp", "should send project path, got $receivedPath")
    }

    @Test
    fun `ensureWorkspace calls insertBefore to move current workspace to front`() {
        var insertPayload: String? = null
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/workspace/create") { ex ->
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspace":{"workspaceId":"ws-b","path":"D:/proj/MyApp"},"created":false}}}""")
        }
        srv.createContext("/api/workspace/insertBefore") { ex ->
            insertPayload = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspaceIds":["ws-b","ws-a"]}}}""")
        }
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv
        writeWorkspaceOrder(listOf("ws-a", "ws-b"))

        val ok = WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:${srv.address.port}", "D:/proj/MyApp", tempHome)
        assertTrue(ok)
        assertTrue(insertPayload != null, "workspace/insertBefore should be called")
        assertTrue(insertPayload!!.contains("\"workspaceId\":\"ws-b\""), "insertBefore should target ws-b, got $insertPayload")
        assertTrue(insertPayload!!.contains("\"beforeWorkspaceId\":\"ws-a\""), "insertBefore should anchor at ws-a, got $insertPayload")
    }

    @Test
    fun `ensureWorkspace skips insertBefore when already first`() {
        var insertCalls = 0
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/workspace/create") { ex ->
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspace":{"workspaceId":"ws-b","path":"D:/proj/MyApp"},"created":false}}}""")
        }
        srv.createContext("/api/workspace/insertBefore") { ex ->
            insertCalls++
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspaceIds":["ws-b","ws-a"]}}}""")
        }
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv
        writeWorkspaceOrder(listOf("ws-b", "ws-a"))

        val ok = WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:${srv.address.port}", "D:/proj/MyApp", tempHome)
        assertTrue(ok)
        assertEquals(0, insertCalls, "no insertBefore needed when already first")
    }

    @Test
    fun `ensureWorkspace tolerates insertBefore failure`() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/workspace/create") { ex ->
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":true,"value":{"workspace":{"workspaceId":"ws-b","path":"D:/proj/MyApp"},"created":false}}}""")
        }
        srv.createContext("/api/workspace/insertBefore") { ex ->
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":"boom"}}""")
        }
        srv.executor = Executors.newCachedThreadPool()
        srv.start()
        server = srv
        writeWorkspaceOrder(listOf("ws-a"))

        // create 成功即视为成功；insertBefore 失败仅降级（不阻塞、不抛）
        val ok = WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:${srv.address.port}", "D:/proj/MyApp", tempHome)
        assertTrue(ok)
    }

    @Test
    fun `ensureWorkspace returns false on http error`() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/workspace/create") { ex ->
            respond(ex, """{"type":"server-response","rpcId":"x","result":{"ok":false,"error":"boom"}}""")
        }
        srv.start()
        server = srv
        val ok = WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:${srv.address.port}", "D:/proj")
        assertTrue(!ok, "should return false on non-ok response")
    }

    @Test
    fun `ensureWorkspace returns false on blank path`() {
        assertTrue(!WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:1", ""))
    }

    @Test
    fun `ensureWorkspace returns false when unreachable`() {
        // 端口 1 几乎必然拒绝连接；不抛异常，返回 false
        assertTrue(!WorkspaceInitializer.ensureWorkspace("http://127.0.0.1:1", "D:/proj"))
    }

    /** 写 DSH_HOME/storages/workspace.json（0.1.5 v2 结构：global.workspaceIds）。 */
    private fun writeWorkspaceOrder(ids: List<String>) {
        val dir = tempHome.resolve("storages")
        Files.createDirectories(dir)
        val idsJson = ids.joinToString(",") { "\"$it\"" }
        Files.writeString(
            dir.resolve("workspace.json"),
            """{"unit":{"name":"workspace","version":2},"global":{"initialized":true,"workspaceIds":[$idsJson],"archivedSessionIds":[]}}""",
            StandardCharsets.UTF_8
        )
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, resp: String) {
        val bytes = resp.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.set("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
