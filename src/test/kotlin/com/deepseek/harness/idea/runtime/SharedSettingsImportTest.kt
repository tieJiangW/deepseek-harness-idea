package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * dsh 0.2.1 的"设置导入"落地（v0.2.6）。
 *
 * 背景：`dsh-settings-file` 在 0.2.1 被移除，`settings.yaml` 不再是长期配置文档。
 * `dsh-settings.importLegacyDocument()` 把 `<DSH_HOME>/settings.yaml` **一次性**导入进
 * profile 的 `cordis.patch.yml`，随后改名 `settings.yaml.imported`。
 * 插件只负责把旧共享文档搬成"待导入文档"，并用 `.imported` 保证**绝不重复导入**。
 */
class SharedSettingsImportTest {

    @TempDir
    lateinit var tmp: Path

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
        return path
    }

    private fun read(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    private val welcomeVersion = "2026-09-28.1"

    @Test
    fun `stages the legacy shared document once`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("0123456789abcdef")
        write(
            shared.resolve("settings.yaml"),
            """
            locale:
              preference: zh
            llm-pi-ai:
              providers:
                - id: custom
            """.trimIndent() + "\n",
        )

        val action = SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion)

        assertEquals("imported-legacy", action)
        val staged = read(home.resolve("settings.yaml"))
        assertTrue(staged.contains("preference: zh"), "legacy namespaces must be carried over: $staged")
        assertTrue(staged.contains("id: custom"), staged)
        assertTrue(staged.contains("ui-onboarding:"), "welcome prefill must be merged in: $staged")
        assertTrue(staged.contains("welcomeNoticeVersion: \"$welcomeVersion\""), staged)
        // 源文件保留（作为备份/回滚依据），只复制不移动
        assertTrue(Files.isRegularFile(shared.resolve("settings.yaml")), "the shared source must stay in place")
    }

    @Test
    fun `seeds only the welcome notice on a fresh install`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("fedcba9876543210")

        val action = SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion)

        assertEquals("seeded-welcome", action)
        val staged = read(home.resolve("settings.yaml"))
        assertEquals(
            "ui-onboarding:\n  welcomeNoticeVersion: \"$welcomeVersion\"\n",
            staged,
            "a fresh install stages exactly the onboarding acknowledgement",
        )
        assertFalse(Files.exists(shared.resolve("settings.yaml")), "the plugin must not recreate a shared settings document")
    }

    @Test
    fun `skips entirely once dsh renamed the document to imported`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("0123456789abcdef")
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        write(home.resolve("settings.yaml.imported"), "locale:\n  preference: en\n")

        val action = SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion)

        assertEquals("already-imported", action)
        assertFalse(Files.exists(home.resolve("settings.yaml")), "a completed import must never be re-staged")
    }

    @Test
    fun `leaves a pending import with the welcome section untouched`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("0123456789abcdef")
        val pending = "ui-onboarding:\n  welcomeNoticeVersion: \"2026-01-01.1\"\nlocale:\n  preference: zh\n"
        write(home.resolve("settings.yaml"), pending)

        val action = SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion)

        assertEquals("pending", action)
        assertEquals(pending, read(home.resolve("settings.yaml")), "a user-acknowledged version must not be overwritten")
    }

    @Test
    fun `adds the welcome section to a pending import that lacks it`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("0123456789abcdef")
        write(home.resolve("settings.yaml"), "locale:\n  preference: zh\n")

        val action = SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion)

        assertEquals("pending-merged-welcome", action)
        val staged = read(home.resolve("settings.yaml"))
        assertTrue(staged.contains("preference: zh"), "existing content must be preserved: $staged")
        assertTrue(staged.contains("welcomeNoticeVersion: \"$welcomeVersion\""), staged)
    }

    @Test
    fun `is idempotent across boots`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("0123456789abcdef")
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")

        assertEquals("imported-legacy", SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion))
        val first = read(home.resolve("settings.yaml"))

        // 同一轮内重复调用：目标已存在且带声明段 → 不再改写
        assertEquals("pending", SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion))
        assertEquals(first, read(home.resolve("settings.yaml")))

        // 模拟 dsh 导入完成后改名
        Files.move(home.resolve("settings.yaml"), home.resolve("settings.yaml.imported"))
        assertEquals("already-imported", SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion))
        assertFalse(Files.exists(home.resolve("settings.yaml")))
    }

    @Test
    fun `ignores an empty legacy document`() {
        val shared = tmp.resolve("shared")
        val home = shared.resolve("0123456789abcdef")
        write(shared.resolve("settings.yaml"), "# only a comment\n")

        val action = SharedConfigMigrator.importLegacySettingsInto(home, shared, welcomeVersion)

        assertEquals("seeded-welcome", action)
        assertTrue(read(home.resolve("settings.yaml")).contains("welcomeNoticeVersion"))
    }
}
