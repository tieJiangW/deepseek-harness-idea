package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.util.JsonCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 集成冒烟（真实 dsh）：验证切换项目场景下工作区顺序修复——
 * `ensureWorkspace` 的 create 幂等不改变顺序，必须经 workspace.insertBefore
 * 把当前项目挪到显示顺序最前（UI 默认落点 = 列表第一个）。
 *
 * 未设置 DSH_IDEA_RUNTIME 时自动跳过。
 */
class WorkspaceInitializerSmokeTest {

    @TempDir
    lateinit var tempDir: Path

    private var manager: DshProcessManager? = null

    @BeforeEach
    fun setUp() {
        assumeTrue(runtimeRoot() != null, "DSH_IDEA_RUNTIME not set; skipping real-boot smoke test")
    }

    @AfterEach
    fun tearDown() {
        manager?.dispose()
        manager = null
        unlinkJunctions(tempDir)
    }

    @Test
    fun `ensureWorkspace moves current project workspace to front on project switch`() {
        val root = runtimeRoot()!!
        val nodeExe = root.resolve("node/node.exe").toFile()
        val dshBin = root.resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").toFile()
        assertTrue(nodeExe.isFile, "node.exe missing in runtime: $nodeExe")
        assertTrue(dshBin.isFile, "dsh bin missing in runtime: $dshBin")

        val home = tempDir.resolve("dsh-home")
        bootstrapHome(home)
        val workDir = File(System.getProperty("user.dir"))

        var url: String? = null
        manager = DshProcessManager(
            nodeExe = nodeExe,
            dshBin = dshBin,
            workDir = workDir,
            homeDir = home.toFile(),
            patchFile = home.resolve("ide.yml").toFile(),
            // 手动控制注册流程，避免异步注册干扰断言
            projectPath = "",
        ).also { m ->
            m.addListener(object : DshProcessManager.Listener {
                override fun onUrlReady(u: String) {
                    url = u
                }
            })
            m.start()
        }
        val webUrl = waitRunning(url)

        // 模拟项目 A 打开：注册 A
        val dirA = Files.createDirectory(tempDir.resolve("projA")).toFile().absolutePath
        val dirB = Files.createDirectory(tempDir.resolve("projB")).toFile().absolutePath
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, dirA, home), "register workspace A")

        // 模拟同窗口切换项目 B：注册 B 后 B 应挪到显示顺序最前
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, dirB, home), "register workspace B")
        awaitFirstWorkspace(home, dirB)
        assertEquals(canonical(dirB), firstWorkspacePath(home), "B should be first after switching to B")

        // 再切回 A：A 应回到最前（create 幂等 + insertBefore 重新排序）
        assertTrue(WorkspaceInitializer.ensureWorkspace(webUrl, dirA, home), "re-register workspace A")
        awaitFirstWorkspace(home, dirA)
        assertEquals(canonical(dirA), firstWorkspacePath(home), "A should be first after switching back to A")
    }

    // ---- helpers ----

    private fun waitRunning(urlRef: String?): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        var url = urlRef
        while (System.nanoTime() < deadline) {
            if (manager?.currentState() == DshProcessManager.State.RUNNING) {
                url = url ?: manager?.webUrl()
                if (url != null) return url
            }
            Thread.sleep(500)
        }
        throw AssertionError("dsh did not reach RUNNING with url (state=${manager?.currentState()})")
    }

    private fun bootstrapHome(home: Path) {
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)
        Files.writeString(
            web.resolve("package.json"),
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}""",
            StandardCharsets.UTF_8
        )
        Files.writeString(web.resolve("cordis.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(web.resolve("cordis.patch.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(home.resolve("ide.yml"), "[]\n", StandardCharsets.UTF_8)
        Files.writeString(home.resolve(".credentials.yaml"), "DEEPSEEK_API_KEY: sk-dummy-for-test\n", StandardCharsets.UTF_8)
    }

    /** 等待 workspace.json 出现且第一个 workspace 路径为 [expected]。 */
    private fun awaitFirstWorkspace(home: Path, expected: String) {
        val wsFile = home.resolve("storages/workspace.json")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (Files.exists(wsFile)) {
                val path = firstWorkspacePath(home)
                if (path == canonical(expected)) return
            }
            Thread.sleep(300)
        }
        throw AssertionError("workspace order not updated; file=${Files.exists(wsFile)} expected=$expected")
    }

    /** 解析 workspace.json：显示顺序第一个 workspace 的 path。 */
    private fun firstWorkspacePath(home: Path): String? {
        val wsFile = home.resolve("storages/workspace.json")
        if (!Files.exists(wsFile)) return null
        val root = JsonCodec.decodeObject(Files.readString(wsFile))
        val ids = (root["global"] as? Map<*, *>)?.get("workspaceIds") as? List<*> ?: return null
        val firstId = ids.firstOrNull() as? String ?: return null
        val tables = root["tables"] as? Map<*, *>
        val workspaces = tables?.get("workspaces") as? Map<*, *>
        val first = workspaces?.get(firstId) as? Map<*, *>
        return first?.get("path") as? String
    }

    /** 与 dsh 落盘格式一致：realpath 规范化，保留系统分隔符（dsh 存反斜杠，实测）。 */
    private fun canonical(p: String): String = Path.of(p).toRealPath().toString()

    private fun runtimeRoot(): Path? =
        System.getenv(DshHomeManager.RUNTIME_OVERRIDE_ENV)
            ?.let { Path.of(it) }
            ?.takeIf { Files.isDirectory(it) }

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
        Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther
    } catch (e: Exception) {
        false
    }
}
