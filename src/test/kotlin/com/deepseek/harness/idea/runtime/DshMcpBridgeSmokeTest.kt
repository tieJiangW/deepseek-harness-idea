package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.bridge.IdeBridgeResources
import com.deepseek.harness.idea.mcp.McpPatchGenerator
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Step 3 集成冒烟：mock IDE Bridge + mcp-ide-server.mjs + dsh web（failOnStartupError patch）。
 *
 * 验证链路（见 docs/DESIGN.md §7.2）：
 * 1. MCP server 的 tools/list 返回 7 个 ide_* 工具；
 * 2. tools/call 经 mock bridge 返回结构化结果；
 * 3. dsh web 带 --patch ide.yml 启动（failOnStartupError=true：连接/同步失败即拒绝启动）。
 *
 * 未设置 DSH_IDEA_RUNTIME 时跳过（与 DshBootstrapSmokeTest 一致）。
 */
class DshMcpBridgeSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    private var mockBridge: HttpServer? = null
    private val procs = mutableListOf<Process>()

    @BeforeEach
    fun setUp() {
        assumeTrue(runtimeRoot() != null, "DSH_IDEA_RUNTIME not set; skipping MCP smoke test")
    }

    @AfterEach
    fun tearDown() {
        procs.forEach { runCatching { it.destroy() } }
        procs.forEach { runCatching { it.waitFor(3, TimeUnit.SECONDS) } }
        mockBridge?.stop(0)
        // 重要：测试创建的顶层 node_modules junction 与 dsh 自愈的 profiles/node_modules
        // junction 都指向运行时树；必须先断链再让 @TempDir 清理，否则递归删除会清空
        // runtime 的 node_modules（Step 2/3 实测踩坑）。
        unlinkJunctions(tempDir)
    }

    private fun unlinkJunctions(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { p ->
                runCatching {
                    if (Files.isSymbolicLink(p) || isJunction(p)) Files.delete(p)
                }
            }
        }
    }

    private fun isJunction(p: Path): Boolean = try {
        // Windows junction 在默认（FOLLOW）读取时 isOther=false、isDirectory=true，
        // 必须用 NOFOLLOW_LINKS 才能识别（实测 dsh 自愈 junction）。
        Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther
    } catch (e: Exception) {
        false
    }

    @Test
    fun `mcp server exposes six ide tools and dsh boots with strict patch`() {
        val root = runtimeRoot()!!
        val nodeExe = root.resolve("node/node.exe").toFile()
        val dshBin = root.resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").toFile()
        assertTrue(nodeExe.isFile, "node.exe missing: $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing: $dshBin")

        // 1) mock IDE Bridge（JDK HttpServer，带 token 校验）
        val token = "smoke-token"
        val hits = AtomicInteger()
        val bridge = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        bridge.createContext("/") { ex ->
            hits.incrementAndGet()
            val ok = ex.requestHeaders.getFirst("X-DSH-IDE-Token") == token
            if (!ok) {
                val body = """{"error":"unauthorized","code":"unauthorized"}""".toByteArray(StandardCharsets.UTF_8)
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(401, body.size.toLong())
                ex.responseBody.use { it.write(body) }
                return@createContext
            }
            val resp = when (ex.requestURI.path) {
                "/health" -> """{"ok":true,"project":"smoke","pid":1}"""
                "/selection" -> """{"filePath":"C:/smoke/Main.kt","language":"kotlin","selection":"val x = 1","lineStart":1,"lineEnd":2,"projectName":"smoke"}"""
                "/open-files" -> """{"files":[{"path":"C:/smoke/Main.kt","language":"kotlin","modified":false}]}"""
                "/project-tree" -> """{"roots":[{"path":"C:/smoke","name":"smoke","type":"dir","children":[]}]}"""
                "/sent-selection" -> """{"id":"s1","filePath":"C:/smoke/Main.kt","selection":"val x = 1","ts":123}"""
                "/open-file" -> """{"ok":true}"""
                "/reveal" -> """{"ok":true}"""
                "/refresh" -> """{"ok":true,"refreshed":["C:/smoke"],"missing":[]}"""
                else -> """{"error":"nf","code":"not_found"}"""
            }
            val body = resp.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        bridge.executor = Executors.newCachedThreadPool()
        bridge.start()
        mockBridge = bridge

        // 2) 构造最小 DSH_HOME + 顶层 node_modules junction（MCP server 需解析 SDK）
        val home = tempDir.resolve("dsh-home")
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)
        Files.writeString(
            web.resolve("package.json"),
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}""",
            StandardCharsets.UTF_8
        )
        Files.writeString(web.resolve("cordis.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(web.resolve("cordis.patch.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(home.resolve(".credentials.yaml"), "DEEPSEEK_API_KEY: sk-dummy-for-test\n", StandardCharsets.UTF_8)
        createJunction(home.resolve("node_modules"), root.resolve("dsh/node_modules"))

        // 3) 部署并启动 mcp-ide-server.mjs
        val script = home.resolve("mcp-ide-server.mjs")
        Files.writeString(script, IdeBridgeResources.mcpServerScript()!!, StandardCharsets.UTF_8)
        val mcpEnv = mutableMapOf(
            "DSH_IDE_BRIDGE_URL" to "http://127.0.0.1:${bridge.address.port}",
            "DSH_IDE_TOKEN" to token,
            "DSH_MCP_PORT" to "0",
            "DSH_MCP_HOST" to "127.0.0.1",
        )
        val mcpProc = spawn(nodeExe, listOf(script.toString()), home.toFile(), mcpEnv)
        procs.add(mcpProc)

        val mcpPort = waitForPortLine(mcpProc, home)
        assertTrue(mcpPort > 0, "mcp-ide-server did not report a port")

        // 4) initialize + tools/list：7 个 ide_* 工具
        val toolsText = rpc(mcpPort, "tools/list", emptyMap())
        val toolNames = Regex(""""name":"(ide_[a-z_]+)"""").findAll(toolsText).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("ide_get_selection", "ide_get_open_files", "ide_get_project_tree",
                "ide_get_sent_selection", "ide_open_file", "ide_reveal_file", "ide_refresh_files"),
            toolNames,
            "tools/list should expose exactly the 7 ide_* tools"
        )

        // 5) tools/call 经 bridge 返回结构化结果（selection 无选中时为空字符串）
        val selText = rpc(mcpPort, "tools/call", mapOf("name" to "ide_get_selection", "arguments" to emptyMap<String, Any>()))
        assertTrue(selText.contains("Main.kt"), "tools/call ide_get_selection should return bridge data; got: $selText")
        assertTrue(hits.get() >= 1, "bridge should have been hit by tools/call")
        val refreshText = rpc(mcpPort, "tools/call", mapOf("name" to "ide_refresh_files", "arguments" to emptyMap<String, Any>()))
        assertTrue(refreshText.contains("refreshed"), "tools/call ide_refresh_files should return bridge data; got: $refreshText")

        // 6) dsh web 带 failOnStartupError patch 启动：连接失败会拒绝启动，故能起来即证明 MCP 链路通
        val patch = McpPatchGenerator.generateStrict(mcpPort)
        val patchFile = home.resolve("ide.yml")
        Files.writeString(patchFile, patch, StandardCharsets.UTF_8)

        val dshEnv = mapOf(
            "DSH_HOME" to home.toString(),
            "DSH_IDE_BRIDGE_URL" to "http://127.0.0.1:${bridge.address.port}",
            "DSH_IDE_TOKEN" to token,
        )
        val dshProc = spawn(
            nodeExe,
            listOf(dshBin.absolutePath, "--profile", "web", "--patch", patchFile.toString(), "--host", "127.0.0.1", "--port", "0"),
            home.toFile(),
            dshEnv,
        )
        procs.add(dshProc)

        val webUrl = waitForDshWeb(dshProc, home)
        assertTrue(webUrl != null, "dsh web should boot with strict mcp patch (failOnStartupError)")
        // 0.1.5：启动 URL 带 ?token=，首个 GET 返回 303（换取鉴权 cookie 的重定向）→ 接受 2xx/3xx
        assertTrue(httpStatus(webUrl!!) in 200..399, "web ui should answer 2xx/3xx")
    }

    // ---- 辅助 ----

    private fun createJunction(link: Path, target: Path) {
        val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        p.waitFor(10, TimeUnit.SECONDS)
        assertTrue(Files.exists(link), "junction creation failed: $link -> $target")
    }

    private fun spawn(nodeExe: File, args: List<String>, cwd: File, extraEnv: Map<String, String>): Process {
        val pb = ProcessBuilder(listOf(nodeExe.absolutePath) + args)
        pb.directory(cwd)
        extraEnv.forEach { (k, v) -> pb.environment()[k] = v }
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /** 从 mcp-ide-server stdout 解析 "listening on http://127.0.0.1:<port>/mcp"。 */
    private fun waitForPortLine(proc: Process, home: Path): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        val re = Regex("""listening on http://127\.0\.0\.1:(\d+)/mcp""")
        val buffer = StringBuilder()
        val reader = proc.inputStream.bufferedReader()
        while (System.nanoTime() < deadline) {
            while (reader.ready()) {
                val line = reader.readLine() ?: return -1
                buffer.appendLine(line)
                re.find(line)?.let { return it.groupValues[1].toInt() }
            }
            Thread.sleep(200)
        }
        throw AssertionError("mcp-ide-server no port line; log:\n$buffer")
    }

    /** 等待 dsh web 启动行（最多 60s），返回完整启动 URL（0.1.5 起含 `?token=`）或 null。 */
    private fun waitForDshWeb(proc: Process, home: Path): String? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        val re = Regex("""dsh web: (http://127\.0\.0\.1:\d+\S*)""")
        val reader = proc.inputStream.bufferedReader()
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) return null
            while (reader.ready()) {
                val line = reader.readLine() ?: return null
                re.find(line)?.let { return it.groupValues[1].trimEnd('.', ',', ';', ')') }
            }
            Thread.sleep(300)
        }
        return null
    }

    private fun rpc(port: Int, method: String, params: Map<String, Any?>): String {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":${gson(params)}}"""
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return text
    }

    private fun gson(params: Map<String, Any?>): String {
        val sb = StringBuilder("{")
        params.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(k).append("\":")
            appendJsonValue(sb, v)
        }
        return sb.append('}').toString()
    }

    private fun appendJsonValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Number, is Boolean -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                @Suppress("UNCHECKED_CAST")
                val m = v as Map<String, Any?>
                m.entries.forEachIndexed { i, (k, value) ->
                    if (i > 0) sb.append(',')
                    sb.append('"').append(k).append("\":")
                    appendJsonValue(sb, value)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    appendJsonValue(sb, item)
                }
                sb.append(']')
            }
            else -> sb.append('"').append(v).append('"')
        }
    }

    private fun httpStatus(url: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = false
        try {
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun runtimeRoot(): Path? =
        System.getenv(DshHomeManager.RUNTIME_OVERRIDE_ENV)
            ?.let { Path.of(it) }
            ?.takeIf { Files.isDirectory(it) }
}
