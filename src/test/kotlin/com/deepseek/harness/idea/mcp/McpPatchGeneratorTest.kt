package com.deepseek.harness.idea.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpPatchGeneratorTest {

    private val shared = "C:\\Users\\me\\AppData\\Roaming\\JetBrains\\IdeaIC2024.1\\dsh-idea\\dsh-home"

    @Test
    fun `generates insert syntax with dynamic port`() {
        val patch = McpPatchGenerator.generate(3187)
        assertTrue(patch.contains("- insert:"), "must use insert syntax: $patch")
        assertTrue(patch.contains("id: mcp.ide"), "must declare mcp.ide id")
        assertTrue(patch.contains("name: '@deepseek-ai/dsh-mcp-client'"), "must reference the mcp-client plugin")
        assertTrue(patch.contains("serverName: ide"))
        assertTrue(patch.contains("transport: streamable-http"))
        assertTrue(patch.contains("url: http://127.0.0.1:3187/mcp"), "must contain dynamic mcp port")
        assertTrue(patch.contains("toolCallTimeoutMs: 60000"))
        assertTrue(patch.contains("reconnect:"))
        assertTrue(patch.contains("maxAttempts: 3"))
    }

    @Test
    fun `port appears exactly once in url`() {
        val patch = McpPatchGenerator.generate(9999)
        val urlMatches = Regex("""http://127\.0\.0\.1:9999/mcp""").findAll(patch).count()
        assertEquals(1, urlMatches, "url with port should appear exactly once: $patch")
    }

    @Test
    fun `strict variant enables failOnStartupError`() {
        val patch = McpPatchGenerator.generateStrict(1234)
        assertTrue(patch.contains("failOnStartupError: true"))
    }

    @Test
    fun `generate and strict share base shape`() {
        val base = McpPatchGenerator.generate(5000)
        val strict = McpPatchGenerator.generateStrict(5000)
        assertTrue(base.contains("url: http://127.0.0.1:5000/mcp"))
        assertTrue(strict.contains("url: http://127.0.0.1:5000/mcp"))
        assertFalse(base.contains("failOnStartupError"))
    }

    /**
     * v0.2.4 关键回归：共享配置化必须用 `- id: <rowId>`（整份 config 覆盖）形态。
     * 历史实现用 `- $settings:` / `- $credentials:`，被 dsh 以
     * `patch: id is required for non-insert patches` 拒绝，全局化从未生效。
     */
    @Test
    fun `never emits the rejected dollar-prefixed patch syntax`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertFalse(patch.contains("\$settings"), "dollar-prefixed form is rejected by dsh: $patch")
        assertFalse(patch.contains("\$credentials"), "dollar-prefixed form is rejected by dsh: $patch")
        assertFalse(patch.contains("\$agent-presets"), "dollar-prefixed form is rejected by dsh: $patch")
        assertFalse(patch.contains("\$skill-filesystem"), "dollar-prefixed form is rejected by dsh: $patch")
    }

    @Test
    fun `blank shared root keeps patch minimal`() {
        val patch = McpPatchGenerator.generate(8080)
        assertFalse(patch.contains("id: settings"))
        assertFalse(patch.contains("settings.yaml"))
        assertFalse(patch.contains("agent-presets"))
        assertFalse(patch.contains("skill-filesystem"))
    }

    @Test
    fun `redirects settings and credentials to the shared config root`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertTrue(patch.contains("- id: settings"), "must patch the settings row: $patch")
        assertTrue(patch.contains("- id: credentials"), "must patch the credentials row: $patch")
        assertTrue(
            patch.contains("path: 'C:/Users/me/AppData/Roaming/JetBrains/IdeaIC2024.1/dsh-idea/dsh-home/settings.yaml'"),
            "settings path must be the forward-slash shared root: $patch",
        )
        assertTrue(
            patch.contains("path: 'C:/Users/me/AppData/Roaming/JetBrains/IdeaIC2024.1/dsh-idea/dsh-home/.credentials.yaml'"),
            "credentials path must be the forward-slash shared root: $patch",
        )
    }

    /**
     * `dsh-agent-presets` 的 `Config.default` 是必填项，且 patch 是整份 config 替换 —— 未改字段必须重述。
     * `includeUserRoot: false` 防止再叠加 `$DSH_HOME/.agent-presets`（那是按项目隔离的旧根）。
     */
    @Test
    fun `shares agent presets and restates required default`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertTrue(patch.contains("- id: agent-presets"))
        assertTrue(patch.contains("default: standard"), "Config.default is required and must be restated: $patch")
        assertTrue(patch.contains("includeUserRoot: false"), "must not keep the per-project user root: $patch")
        assertTrue(patch.contains("path: 'C:/Users/me/AppData/Roaming/JetBrains/IdeaIC2024.1/dsh-idea/dsh-home/.agent-presets'"))
        assertTrue(patch.contains("trust: user"), "writable root must carry user trust")
    }

    /** 用户技能根 = `<dshHome>/skills`；项目根 `<项目>/.dsh/skills` 仍按项目（不在此 patch 中改动）。 */
    @Test
    fun `shares the personal skills root`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertTrue(patch.contains("- id: skill-filesystem"))
        assertTrue(patch.contains("dshHome: 'C:/Users/me/AppData/Roaming/JetBrains/IdeaIC2024.1/dsh-idea/dsh-home'"))
    }

    @Test
    fun `escapes single quotes in the shared root`() {
        val patch = McpPatchGenerator.generate(8080, "/home/o'brien/.config/dsh-idea/dsh-home")
        assertTrue(patch.contains("path: '/home/o''brien/.config/dsh-idea/dsh-home/settings.yaml'"), patch)
    }

    @Test
    fun `yamlPath normalizes separators and quotes`() {
        assertEquals("C:/a/b", McpPatchGenerator.yamlPath("C:\\a\\b"))
        assertEquals("/home/o''x/y", McpPatchGenerator.yamlPath("/home/o'x/y"))
    }

    @Test
    fun `strict variant keeps the shared config sections`() {
        val patch = McpPatchGenerator.generateStrict(4321, shared)
        assertTrue(patch.contains("failOnStartupError: true"))
        assertTrue(patch.contains("- id: settings"))
        assertTrue(patch.contains("- id: credentials"))
        assertTrue(patch.contains("- id: agent-presets"))
        assertTrue(patch.contains("- id: skill-filesystem"))
    }
}
