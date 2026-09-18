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
 * 共享配置迁移与 YAML 文本合并（v0.2.4）。
 *
 * 覆盖两类行为：
 * 1. [YamlText] 的文本级合并（**不做 YAML 序列化**，以保留注释/格式）与 dsh 扁平凭据升级；
 * 2. [SharedConfigMigrator] 的端到端迁移（合并 + 备份 + 幂等标记 + 种子）。
 */
class SharedConfigMigratorTest {

    @TempDir
    lateinit var tmp: Path

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
        return path
    }

    private fun read(path: Path): String = Files.readString(path, StandardCharsets.UTF_8)

    // ---- YamlText：顶层键与段落切分 ----

    @Test
    fun `topLevelKeys ignores nested keys comments and document markers`() {
        val text = """
            ---
            # a comment
            locale:
              preference: zh
            llm-pi-ai:
              providers:
                - id: custom
            ui-onboarding:
              welcomeNoticeVersion: "2026-08-13.1"
        """.trimIndent()
        assertEquals(listOf("locale", "llm-pi-ai", "ui-onboarding"), YamlText.topLevelKeys(text))
    }

    @Test
    fun `blocksOf keeps raw text including comments and nested structure`() {
        val text = """
            locale:
              # keep me
              preference: zh
            agent-presets:
              default: minimal
        """.trimIndent()
        val blocks = YamlText.blocksOf(text)
        assertEquals(listOf("locale", "agent-presets"), blocks.keys.toList())
        assertTrue(blocks.getValue("locale").contains("# keep me"), blocks.toString())
        assertTrue(blocks.getValue("agent-presets").contains("default: minimal"))
    }

    @Test
    fun `blocksOf tolerates CRLF`() {
        val text = "locale:\r\n  preference: en\r\nllm-deepseek:\r\n  baseURL: https://x\r\n"
        val blocks = YamlText.blocksOf(text)
        assertEquals(setOf("locale", "llm-deepseek"), blocks.keys)
    }

    @Test
    fun `hasContent ignores blank and comment-only documents`() {
        assertFalse(YamlText.hasContent(""))
        assertFalse(YamlText.hasContent("\n\n# only a comment\n"))
        assertFalse(YamlText.hasContent("---\n"))
        assertTrue(YamlText.hasContent("locale:\n  preference: zh\n"))
    }

    // ---- YamlText：按 namespace 合并（共享侧优先） ----

    @Test
    fun `mergeMissingNamespaces appends only namespaces absent from the shared document`() {
        val shared = "locale:\n  preference: zh\n"
        val incoming = hashMapOf(
            "locale" to "locale:\n  preference: en\n",
            "llm-pi-ai" to "llm-pi-ai:\n  providers:\n    - id: custom\n      models:\n        - id: my-model\n",
        )
        val (merged, added) = YamlText.mergeMissingNamespaces(shared, incoming) { "# merged from project $it" }
        assertEquals(listOf("llm-pi-ai"), added, "shared value must win for an existing namespace")
        assertTrue(merged.contains("preference: zh"), "shared section must be untouched: $merged")
        assertTrue(merged.contains("# merged from project llm-pi-ai"))
        assertTrue(merged.contains("id: my-model"), "the recovered custom model must be carried over: $merged")
        assertEquals(listOf("locale", "llm-pi-ai"), YamlText.topLevelKeys(merged))
    }

    @Test
    fun `mergeMissingNamespaces is a no-op when every namespace already exists`() {
        val shared = "locale:\n  preference: zh\nllm-pi-ai:\n  providers: []\n"
        val (merged, added) = YamlText.mergeMissingNamespaces(shared, YamlText.blocksOf(shared)) { "# x" }
        assertTrue(added.isEmpty())
        assertEquals(shared, merged)
    }

    // ---- YamlText：凭据合并与扁平 layout 升级 ----

    @Test
    fun `flat credential layout is detected and upgraded inline`() {
        val flat = "DEEPSEEK_API_KEY: sk-abc\n"
        assertTrue(YamlText.isFlatCredentials(flat))
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-abc\n", YamlText.upgradeFlatCredentials(flat))
    }

    @Test
    fun `versioned credential layout is not treated as flat`() {
        val versioned = "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-abc\n"
        assertFalse(YamlText.isFlatCredentials(versioned))
        assertEquals(versioned, YamlText.upgradeFlatCredentials(versioned))
    }

    @Test
    fun `mergeCredentials keeps shared values and appends missing refs and records`() {
        val shared = """
            version: 1
            refs:
              # do not lose this comment
              DEEPSEEK_API_KEY: sk-shared
        """.trimIndent() + "\n"
        val incoming = """
            version: 1
            refs:
              DEEPSEEK_API_KEY: sk-project
              OPENAI_API_KEY: sk-openai
            records:
              client-connection/browser-session:
                kind: grant
                payload:
                  version: 1
                  secret: s3cret
        """.trimIndent() + "\n"

        val (merged, added) = YamlText.mergeCredentials(shared, incoming)
        assertTrue(merged.contains("DEEPSEEK_API_KEY: sk-shared"), "shared key wins: $merged")
        assertFalse(merged.contains("sk-project"), "must not overwrite the shared value: $merged")
        assertTrue(merged.contains("OPENAI_API_KEY: sk-openai"), "other provider ref must be kept: $merged")
        assertTrue(merged.contains("client-connection/browser-session"), "records must be kept: $merged")
        assertTrue(merged.contains("secret: s3cret"))
        assertTrue(merged.contains("# do not lose this comment"), "comments must be preserved: $merged")
        assertTrue(added.contains("OPENAI_API_KEY"))
        assertTrue(added.contains("records"))
    }

    @Test
    fun `mergeCredentials merges a flat project document into a versioned shared document`() {
        val shared = "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-shared\n"
        val incomingFlat = "DEEPSEEK_API_KEY: sk-project\nOPENAI_API_KEY: sk-openai\n"
        val (merged, _) = YamlText.mergeCredentials(shared, incomingFlat)
        assertTrue(merged.contains("DEEPSEEK_API_KEY: sk-shared"))
        assertTrue(merged.contains("OPENAI_API_KEY: sk-openai"))
    }

    @Test
    fun `mergeCredentials does not duplicate an existing record`() {
        val shared = """
            version: 1
            refs:
              DEEPSEEK_API_KEY: sk-shared
            records:
              client-connection/browser-session:
                kind: grant
                payload:
                  version: 1
                  secret: same
        """.trimIndent() + "\n"
        val (merged, added) = YamlText.mergeCredentials(shared, shared)
        assertEquals(shared, merged)
        assertTrue(added.isEmpty())
    }

    // ---- 端到端迁移 ----

    private fun projectHome(name: String): Path = Files.createDirectories(tmp.resolve("dsh-home").resolve(name))

    @Test
    fun `migration merges project namespaces and backs the files up`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        val home = projectHome("0123456789abcdef")
        write(home.resolve("settings.yaml"), "llm-pi-ai:\n  providers:\n    - id: custom\n      models:\n        - id: my-model\n")
        write(home.resolve(".credentials.yaml"), "version: 1\nrefs:\n  OPENAI_API_KEY: sk-openai\n")

        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, listOf(home), "0.2.4")

        assertTrue(outcome.ran)
        assertEquals(listOf("0123456789abcdef"), outcome.migratedProjects)
        assertTrue(outcome.mergedNamespaces.contains("llm-pi-ai"))
        // 共享侧原本没有凭据文档 → 采用项目文件作为种子（`*` 表示整份采用）
        assertEquals(listOf("*"), outcome.mergedCredentials)

        val mergedSettings = read(shared.resolve("settings.yaml"))
        assertTrue(mergedSettings.contains("preference: zh"), "shared content must survive: $mergedSettings")
        assertTrue(mergedSettings.contains("id: my-model"), "recovered model must land in the shared document: $mergedSettings")

        val mergedCredentials = read(shared.resolve(".credentials.yaml"))
        assertTrue(mergedCredentials.contains("OPENAI_API_KEY: sk-openai"))

        // 原文件搬到备份目录，项目目录里不再有配置文件（共享化后 dsh 不读它）
        assertFalse(Files.exists(home.resolve("settings.yaml")))
        assertFalse(Files.exists(home.resolve(".credentials.yaml")))
        assertTrue(Files.isRegularFile(shared.resolve("migrated/0123456789abcdef/settings.yaml")))
        assertTrue(Files.isRegularFile(shared.resolve("migrated/0123456789abcdef/.credentials.yaml")))
        assertTrue(Files.isRegularFile(shared.resolve(SharedConfigMigrator.MARKER_FILE)))
    }

    @Test
    fun `migration merges missing credential refs into an existing shared document`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        write(shared.resolve(".credentials.yaml"), "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-shared\n")
        val home = projectHome("0123456789abcdef")
        write(home.resolve(".credentials.yaml"), "version: 1\nrefs:\n  OPENAI_API_KEY: sk-openai\n")

        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, listOf(home), "0.2.4")

        assertEquals(listOf("OPENAI_API_KEY"), outcome.mergedCredentials)
        val merged = read(shared.resolve(".credentials.yaml"))
        assertTrue(merged.contains("DEEPSEEK_API_KEY: sk-shared"), "shared value must win: $merged")
        assertTrue(merged.contains("OPENAI_API_KEY: sk-openai"), "other provider ref must be recovered: $merged")
        assertFalse(Files.exists(home.resolve(".credentials.yaml")))
    }

    @Test
    fun `migration is skipped when the marker already matches the plugin version`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        write(shared.resolve(SharedConfigMigrator.MARKER_FILE), "0.2.4\n")
        val home = projectHome("0123456789abcdef")
        write(home.resolve("settings.yaml"), "llm-pi-ai:\n  providers: []\n")

        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, listOf(home), "0.2.4")

        assertFalse(outcome.ran)
        assertTrue(Files.isRegularFile(home.resolve("settings.yaml")), "a completed migration must not touch project files again")
    }

    @Test
    fun `migration is idempotent across two runs`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        val home = projectHome("0123456789abcdef")
        write(home.resolve("settings.yaml"), "llm-pi-ai:\n  providers: []\n")

        SharedConfigMigrator.migrateIfNeeded(shared, listOf(home), "0.2.4")
        val afterFirst = read(shared.resolve("settings.yaml"))
        val second = SharedConfigMigrator.migrateIfNeeded(shared, listOf(home), "0.2.4")

        assertFalse(second.ran)
        assertEquals(afterFirst, read(shared.resolve("settings.yaml")), "second run must not change the shared document")
    }

    @Test
    fun `migration seeds a missing shared document from the richest project copy`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        val poor = projectHome("aaaaaaaaaaaaaaaa")
        val rich = projectHome("bbbbbbbbbbbbbbbb")
        write(poor.resolve("settings.yaml"), "locale:\n  preference: en\n")
        write(rich.resolve("settings.yaml"), "locale:\n  preference: zh\nllm-pi-ai:\n  providers:\n    - id: custom\n")

        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, listOf(poor, rich), "0.2.4")

        assertTrue(outcome.ran)
        assertTrue(outcome.seededSettings)
        val seeded = read(shared.resolve("settings.yaml"))
        assertTrue(seeded.contains("id: custom"), "seed must come from the richest copy: $seeded")
        assertFalse(Files.exists(poor.resolve("settings.yaml")))
        assertFalse(Files.exists(rich.resolve("settings.yaml")))
    }

    @Test
    fun `migration with no project config files only writes the marker`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        val home = projectHome("0123456789abcdef") // 无 settings.yaml / .credentials.yaml

        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, listOf(home), "0.2.4")

        assertFalse(outcome.ran)
        assertTrue(Files.isRegularFile(shared.resolve(SharedConfigMigrator.MARKER_FILE)))
        assertFalse(Files.exists(shared.resolve("settings.yaml")))
    }

    @Test
    fun `one broken project does not stop the others`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        val ok = projectHome("aaaaaaaaaaaaaaaa")
        write(ok.resolve("settings.yaml"), "llm-pi-ai:\n  providers: []\n")
        // 用普通文件占住备份目录路径 → 该项目的迁移必然失败（Files.createDirectories 抛错），
        // 且不得影响其它项目。
        val brokenName = "cccccccccccccccc"
        val broken = Files.createDirectories(shared.resolve(brokenName))
        write(broken.resolve("settings.yaml"), "llm-pi-ai:\n  providers: []\n")
        write(shared.resolve("migrated").resolve(brokenName), "backup path occupied by a plain file")

        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, listOf(ok, broken), "0.2.4")

        assertTrue(outcome.migratedProjects.contains("aaaaaaaaaaaaaaaa"))
        assertTrue(outcome.failures.contains(brokenName), "outcome=$outcome")
        assertFalse(Files.exists(shared.resolve(SharedConfigMigrator.MARKER_FILE)), "failures must keep the migration retryable")
    }

    @Test
    fun `cleanLegacySharedRoot removes stale per-project style entries but keeps sessions`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        write(shared.resolve("ide.yml"), "[]\n")
        write(shared.resolve("mcp-ide-server.mjs"), "// legacy\n")
        write(shared.resolve("profiles/web/package.json"), "{}\n")
        write(shared.resolve("sessions/--proj--/x/header.json"), "{}\n")
        write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")

        SharedConfigMigrator.cleanLegacySharedRoot(shared)

        assertFalse(Files.exists(shared.resolve("ide.yml")))
        assertFalse(Files.exists(shared.resolve("mcp-ide-server.mjs")))
        assertFalse(Files.exists(shared.resolve("profiles")))
        assertTrue(Files.exists(shared.resolve("sessions/--proj--/x/header.json")), "sessions are still read by LegacySessionMigrator")
        assertTrue(Files.exists(shared.resolve("settings.yaml")), "the shared config document must never be cleaned")
    }

    // ---- YamlText.upsertRef（纯函数：合并写入共享凭据，保留其它条目） ----

    @Test
    fun `upsertRef replaces the deepseek ref and keeps the rest of the document`() {
        val doc = """
            version: 1
            refs:
              DEEPSEEK_API_KEY: sk-old
              OPENAI_API_KEY: sk-openai
            records:
              client-connection/browser-session:
                kind: grant
                payload:
                  version: 1
                  secret: s3cret
        """.trimIndent() + "\n"

        val updated = YamlText.upsertRef(doc, "DEEPSEEK_API_KEY", "sk-new")

        assertTrue(updated.contains("DEEPSEEK_API_KEY: sk-new"), updated)
        assertFalse(updated.contains("sk-old"), updated)
        assertTrue(updated.contains("OPENAI_API_KEY: sk-openai"), "other refs must survive: $updated")
        assertTrue(updated.contains("client-connection/browser-session"), "records must survive: $updated")
        assertTrue(updated.contains("secret: s3cret"))
    }

    @Test
    fun `upsertRef adds a missing ref into the existing refs section`() {
        val doc = "version: 1\nrefs:\n  OPENAI_API_KEY: sk-openai\n"
        val updated = YamlText.upsertRef(doc, "DEEPSEEK_API_KEY", "sk-new")
        assertTrue(updated.contains("DEEPSEEK_API_KEY: sk-new"), updated)
        assertTrue(updated.contains("OPENAI_API_KEY: sk-openai"))
        // 插入位置在 refs 段内（records 段之前），不能落到文档末尾形成新的顶层键
        assertEquals(listOf("version", "refs"), YamlText.topLevelKeys(updated))
    }

    @Test
    fun `upsertRef builds a versioned document from nothing`() {
        assertEquals(
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-new\n",
            YamlText.upsertRef(null, "DEEPSEEK_API_KEY", "sk-new"),
        )
        assertEquals(
            "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-new\n",
            YamlText.upsertRef("   ", "DEEPSEEK_API_KEY", "sk-new"),
        )
    }

    @Test
    fun `upsertRef upgrades a flat document inline`() {
        val updated = YamlText.upsertRef("DEEPSEEK_API_KEY: sk-old\n", "DEEPSEEK_API_KEY", "sk-new")
        assertEquals("version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-new\n", updated)
    }

    @Test
    fun `hasRef detects only the exact stored value`() {
        assertTrue(YamlText.hasRef("version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-a\n", "DEEPSEEK_API_KEY", "sk-a"))
        assertTrue(YamlText.hasRef("DEEPSEEK_API_KEY: sk-a\n", "DEEPSEEK_API_KEY", "sk-a"))
        assertFalse(YamlText.hasRef("version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-a\n", "DEEPSEEK_API_KEY", "sk-b"))
        assertFalse(YamlText.hasRef(null, "DEEPSEEK_API_KEY", "sk-a"))
    }
}
