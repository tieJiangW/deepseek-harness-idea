package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `<项目 DSH_HOME>/profiles` 与共享 profile 根的链接判定（v0.2.5）。
 *
 * 这一层是"插件装一次、多项目生效"的**判据**：判断错了会走错分支（重复迁移/删除真实目录/
 * 链接指向错误目标却不修），所以每个 [SharedProfilesLink.State] 都要覆盖，且必须用真实链接
 * （Windows junction / Unix 符号链接）验证，而不是构造出来的假对象。
 */
class SharedProfilesLinkTest {

    @TempDir
    lateinit var tmp: Path

    /** 建目录链接：Windows 用 junction（`mklink /J`，无需管理员），其它平台用符号链接。 */
    private fun linkDir(link: Path, target: Path) {
        Files.createDirectories(link.parent)
        Files.createDirectories(target)
        if (Platform.current().os == Platform.Os.WINDOWS) {
            val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "mklink /J failed: $link -> $target")
        } else {
            Files.createSymbolicLink(link, target)
        }
    }

    private fun shared(): Path = Files.createDirectories(tmp.resolve("shared/profiles"))

    private fun project(): Path = tmp.resolve("home-a/profiles")

    // ---- 状态判定 ----

    @Test
    fun `absent when the project has no profiles directory`() {
        assertEquals(SharedProfilesLink.State.ABSENT, SharedProfilesLink.state(project(), shared()))
    }

    @Test
    fun `real dir when the project keeps its own profile`() {
        Files.createDirectories(project().resolve("web"))
        assertEquals(SharedProfilesLink.State.REAL_DIR, SharedProfilesLink.state(project(), shared()))
        assertFalse(SharedProfilesLink.isLink(project()), "a plain directory must not be detected as a link")
    }

    @Test
    fun `correct link when the project profiles point at the shared root`() {
        val shared = shared()
        linkDir(project(), shared)
        assertEquals(SharedProfilesLink.State.CORRECT_LINK, SharedProfilesLink.state(project(), shared))
        assertTrue(SharedProfilesLink.isLink(project()))
        assertTrue(SharedProfilesLink.pointsTo(project(), shared))
    }

    @Test
    fun `wrong link when the project profiles point somewhere else`() {
        val shared = shared()
        val elsewhere = Files.createDirectories(tmp.resolve("other/profiles"))
        linkDir(project(), elsewhere)
        assertEquals(SharedProfilesLink.State.WRONG_LINK, SharedProfilesLink.state(project(), shared))
        assertFalse(SharedProfilesLink.pointsTo(project(), shared))
    }

    @Test
    fun `wrong link when the shared target does not exist yet`() {
        // 共享根还没建（首次启用）时，已存在的链接不能算"正确"——否则会跳过建共享目录
        linkDir(project(), tmp.resolve("gone/profiles"))
        assertEquals(SharedProfilesLink.State.WRONG_LINK, SharedProfilesLink.state(project(), shared()))
    }

    // ---- 播种 / 回填 ----

    @Test
    fun `filesToSeed only returns files missing in the shared profile`() {
        val projectWeb = Files.createDirectories(project().resolve("web"))
        val sharedWeb = Files.createDirectories(shared().resolve("web"))
        Files.writeString(projectWeb.resolve("package.json"), "{}")
        Files.writeString(projectWeb.resolve("cordis.yml"), "[]\n")
        Files.writeString(projectWeb.resolve("cordis.patch.yml"), "[]\n")
        // 共享已有 package.json → 不得被旧项目的副本覆盖
        Files.writeString(sharedWeb.resolve("package.json"), """{"name":"shared"}""")

        assertEquals(
            listOf("cordis.yml", "cordis.patch.yml"),
            SharedProfilesLink.filesToSeed(projectWeb, sharedWeb),
        )
    }

    @Test
    fun `filesToRestore only returns files missing in the project profile`() {
        val sharedWeb = Files.createDirectories(shared().resolve("web"))
        val projectWeb = Files.createDirectories(project().resolve("web"))
        Files.writeString(sharedWeb.resolve("package.json"), "{}")
        Files.writeString(sharedWeb.resolve("cordis.yml"), "[]\n")
        Files.writeString(projectWeb.resolve("cordis.patch.yml"), "[]\n")

        assertEquals(
            listOf("package.json", "cordis.yml"),
            SharedProfilesLink.filesToRestore(sharedWeb, projectWeb),
        )
    }

    // ---- 是否值得留档 ----

    @Test
    fun `hasUserContent is false for a freshly seeded profile`() {
        val web = Files.createDirectories(project().resolve("web"))
        Files.writeString(web.resolve("package.json"), "{}")
        Files.writeString(web.resolve("cordis.patch.yml"), "# comment\n[]\n")
        assertFalse(SharedProfilesLink.hasUserContent(web))
    }

    @Test
    fun `hasUserContent is true when packages were installed`() {
        val web = Files.createDirectories(project().resolve("web"))
        Files.createDirectories(web.resolve("node_modules"))
        assertTrue(SharedProfilesLink.hasUserContent(web))
    }

    @Test
    fun `hasUserContent is true for user patch layers`() {
        val web = Files.createDirectories(project().resolve("web"))
        Files.writeString(web.resolve("cordis.patch.yml"), "- insert:\n    - id: my-plugin\n")
        assertTrue(SharedProfilesLink.hasUserContent(web))
    }

    @Test
    fun `hasUserContent is false for a missing directory`() {
        assertFalse(SharedProfilesLink.hasUserContent(project().resolve("web")))
    }
}
