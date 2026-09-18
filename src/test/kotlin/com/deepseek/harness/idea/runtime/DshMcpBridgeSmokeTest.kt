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

        // 2) v0.2.4 生产布局：MCP 脚本全局唯一一份，部署在**真实运行时树的**
        //    `<运行时根>/dsh/node_modules/@deepseek-ai/dsh-ide-bridge/`。该位置向上查找 node_modules
        //    会命中 `<运行时根>/dsh/node_modules`（dsh 自身依赖树）——这是"不再需要任何 junction"的关键：
        //    Node 的 ESM 解析按**真实路径**查找、**不越过 junction**（实测），所以脚本必须真正落在
        //    dsh 树内部。本测试直接用真实运行时树，parse 失败即断言失败（回归门）。
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
        // 断言：项目 DSH_HOME 里**没有** node_modules junction（v0.2.4 起不再需要）
        assertTrue(
            !Files.exists(home.resolve("node_modules"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
            "v0.2.4 must not create a per-project node_modules junction",
        )

        val bridgeDir = Files.createDirectories(
            root.resolve("dsh/node_modules/@deepseek-ai/${DshHomeManager.MCP_BRIDGE_PACKAGE}")
        )
        val script = bridgeDir.resolve(DshHomeManager.MCP_SERVER_SCRIPT)
        Files.writeString(script, IdeBridgeResources.mcpServerScript()!!, StandardCharsets.UTF_8)
        // 该目录的 ESM 解析必须命中运行时依赖树（不越过任何链接）
        assertEsmResolves(nodeExe, bridgeDir)

        val sharedHome = Files.createDirectories(tempDir.resolve("shared-config"))
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

        // 6) dsh web 带 failOnStartupError + 共享配置 patch 启动：连接失败会拒绝启动，故能起来即证明
        //    MCP 链路与 patch 语法（`- id: settings` / `- id: credentials` / `- id: agent-presets` /
        //    `- id: skill-filesystem`）都正确。
        val patch = McpPatchGenerator.generateStrict(mcpPort, sharedHome.toString())
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

        // 7) 共享配置化生效：凭证/设置由 dsh 写在**共享配置根**，项目 DSH_HOME 里不产生这两个文件。
        assertTrue(
            !Files.exists(home.resolve("settings.yaml")),
            "settings must live in the shared config root, not in the per-project DSH_HOME",
        )
        assertTrue(
            !Files.exists(home.resolve(".credentials.yaml")),
            "credentials must live in the shared config root, not in the per-project DSH_HOME",
        )
        val sharedCredentials = waitForFile(sharedHome.resolve(".credentials.yaml"), 30)
        assertTrue(sharedCredentials, "dsh must create the web-session record in the SHARED credentials file")
        assertTrue(
            Files.readString(sharedHome.resolve(".credentials.yaml"), StandardCharsets.UTF_8)
                .contains("client-connection/browser-session"),
            "the shared credentials file must carry dsh's own records section",
        )
    }

    // ---- 辅助 ----

    /**
     * 断言 [dir] 下的 ESM 裸包解析能命中 dsh 依赖树。
     *
     * 这是 v0.2.4「MCP 脚本零链接」的核心回归门：脚本部署在
     * `<运行时根>/dsh/node_modules/@deepseek-ai/dsh-ide-bridge/`，靠向上查找 `dsh/node_modules` 解析
     * `@modelcontextprotocol/sdk` 与 `zod/v4`。Node **不越过 junction**（实测），放错位置会
     * `ERR_MODULE_NOT_FOUND`，故此处显式验证解析结果。
     */
    private fun assertEsmResolves(nodeExe: File, dir: Path) {
        val probe = dir.resolve("resolve-probe.mjs")
        Files.writeString(
            probe,
            """
            const sdk = import.meta.resolve('@modelcontextprotocol/sdk/server/mcp.js');
            const zod = import.meta.resolve('zod/v4');
            console.log('SDK_RESOLVED=' + sdk);
            console.log('ZOD_RESOLVED=' + zod);
            """.trimIndent(),
            StandardCharsets.UTF_8
        )
        try {
            val p = ProcessBuilder(nodeExe.absolutePath, probe.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            assertTrue(
                out.contains("SDK_RESOLVED=") && out.contains("ZOD_RESOLVED="),
                "ESM resolution must reach the dsh dependency tree from $dir; output:\n$out",
            )
        } finally {
            runCatching { Files.deleteIfExists(probe) }
        }
    }

    private fun createJunction(link: Path, target: Path) {
        linkDir(link, target)
    }

    /** 建目录链接（Windows 用 junction `mklink /J`，无需管理员；其它平台用符号链接）。 */
    private fun linkDir(link: Path, target: Path) {
        Files.createDirectories(link.parent)
        if (Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        val created = if (com.deepseek.harness.idea.runtime.Platform.current().os ==
            com.deepseek.harness.idea.runtime.Platform.Os.WINDOWS
        ) {
            val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            p.waitFor(10, TimeUnit.SECONDS)
            Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        } else {
            runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        }
        assertTrue(created, "directory link creation failed: $link -> $target")
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

    /** 等待文件出现（异步落盘），返回是否在超时内出现。 */
    private fun waitForFile(path: Path, timeoutSeconds: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) return true
            Thread.sleep(300)
        }
        return Files.isRegularFile(path)
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
