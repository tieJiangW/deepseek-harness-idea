package com.deepseek.harness.idea.plugins

import com.deepseek.harness.idea.runtime.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * dsh 插件安装器的纯逻辑部分（v0.2.5）：参数拼装、私有 pnpm 落点、PATH 注入、备份目录与
 * profile 读取。真实的 `pnpm add` 需要联网，由 `DshPluginInstallSmokeTest` 在真实运行时上覆盖。
 */
class DshPluginInstallerTest {

    @TempDir
    lateinit var tmp: Path

    // ---- 命令行拼装 ----

    @Test
    fun `plugin subcommand args keep the dsh contract`() {
        // `plugin` 是 dsh bin 的子命令（不是 pnpm 的）；profile 名固定为 web
        assertEquals(
            listOf("plugin", "--profile", "web", "add", "@scope/pkg@1.2.0"),
            DshPluginInstaller.dshArgs("add", "@scope/pkg@1.2.0"),
        )
        assertEquals(
            listOf("plugin", "--profile", "web", "remove", "pkg"),
            DshPluginInstaller.dshArgs("remove", "pkg"),
        )
    }

    @Test
    fun `an action without a spec omits the trailing argument`() {
        assertEquals(listOf("plugin", "--profile", "web", "list"), DshPluginInstaller.dshArgs("list", null))
        assertEquals(listOf("plugin", "--profile", "web", "list"), DshPluginInstaller.dshArgs("list", ""))
    }

    @Test
    fun `a spec can be quoted for the Windows shell`() {
        assertEquals(
            listOf("plugin", "--profile", "web", "add", "\"pkg@^1.2.0\""),
            DshPluginInstaller.dshArgs("add", "pkg@^1.2.0", quoteSpec = true),
        )
    }

    @Test
    fun `profile name matches the profile the plugin launches`() {
        // 与 DshProcessManager 的 `--profile web` 必须一致，否则装到别的 profile 里不会被加载
        assertEquals("web", DshProfileManifest.PROFILE_NAME)
    }

    // ---- 私有 pnpm 落点 ----

    @Test
    fun `private pnpm lives under the shared root`() {
        val shared = tmp.resolve("dsh-home")
        assertEquals(shared.resolve("plugin-tools"), DshPluginInstaller.toolsDir(shared))
        assertEquals(shared.resolve("plugin-tools/bin"), DshPluginInstaller.shimDir(shared))
        assertEquals(
            shared.resolve("plugin-tools/node_modules/pnpm/bin/pnpm.cjs"),
            DshPluginInstaller.pnpmCli(DshPluginInstaller.toolsDir(shared)),
        )
        // 备份放在共享根、与具体项目无关：便于按时间轴回找
        assertEquals(shared.resolve("plugin-backups"), DshPluginInstaller.backupsRoot(shared))
        assertEquals(shared.resolve("plugin-backups/20250101-120000-000"), DshPluginInstaller.backupDir(shared, "20250101-120000-000"))
    }

    @Test
    fun `shim name depends on the platform`() {
        assertEquals("pnpm.cmd", DshPluginInstaller.shimName(Platform.Os.WINDOWS))
        assertEquals("pnpm", DshPluginInstaller.shimName(Platform.Os.MACOS))
        assertEquals("pnpm", DshPluginInstaller.shimName(Platform.Os.LINUX))
    }

    // ---- PATH 注入 ----

    @Test
    fun `shim dir is prepended to PATH`() {
        val shim = tmp.resolve("tools/bin")
        val env = DshPluginInstaller.withShimPath(mapOf("PATH" to "/usr/bin"), shim)
        assertEquals(shim.toString() + File.pathSeparator + "/usr/bin", env["PATH"])
    }

    @Test
    fun `Windows-style Path key still yields a single PATH entry`() {
        val shim = tmp.resolve("tools/bin")
        val env = DshPluginInstaller.withShimPath(mapOf("Path" to "C:\\Windows"), shim)
        assertEquals(shim.toString() + File.pathSeparator + "C:\\Windows", env["PATH"])
    }

    @Test
    fun `empty PATH yields just the shim dir`() {
        val shim = tmp.resolve("tools/bin")
        assertEquals(shim.toString(), DshPluginInstaller.withShimPath(emptyMap(), shim)["PATH"])
    }

    // ---- 备份 / 时间戳 ----

    @Test
    fun `backup covers the profile manifest and lockfiles`() {
        assertTrue("package.json" in DshPluginInstaller.BACKUP_FILES)
        assertTrue("pnpm-lock.yaml" in DshPluginInstaller.BACKUP_FILES)
        assertTrue("cordis.patch.yml" in DshPluginInstaller.BACKUP_FILES)
    }

    @Test
    fun `stamp is a sortable timestamp with milliseconds`() {
        val stamp = DshPluginInstaller.stamp(1735732800000L)
        assertTrue(Regex("""^\d{8}-\d{6}-\d{3}$""").matches(stamp), "unexpected stamp: $stamp")
        // 同一毫秒内两次操作也必须可区分（毫秒在名字里，靠时间推进而非覆盖）
        assertTrue(DshPluginInstaller.stamp(1735732800001L) != stamp)
    }

    // ---- profile 读取 ----

    @Test
    fun `readProfile reads the project profile through its home`() {
        val home = tmp.resolve("home-a")
        val web = Files.createDirectories(home.resolve("profiles/web"))
        Files.writeString(
            web.resolve("package.json"),
            """{"name":"dsh-profile-web","dependencies":{"p":"1.0.0"},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","p"]}}}""",
            StandardCharsets.UTF_8,
        )
        val profile = DshPluginInstaller.readProfile(home)
        assertEquals(listOf("@deepseek-ai/dsh-base", "p"), profile.bundles)
        assertEquals(listOf("p"), profile.plugins)
    }

    @Test
    fun `readProfile is empty when no profile exists yet`() {
        assertEquals(DshProfileManifest.Profile.EMPTY, DshPluginInstaller.readProfile(tmp.resolve("nothing")))
        // 目录存在但没有 manifest（dsh 尚未初始化 profile）同样降级为空
        Files.createDirectories(tmp.resolve("home-b/profiles/web"))
        assertEquals(DshProfileManifest.Profile.EMPTY, DshPluginInstaller.readProfile(tmp.resolve("home-b")))
    }
}
