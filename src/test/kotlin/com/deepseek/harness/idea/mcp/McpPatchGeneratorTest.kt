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
        assertFalse(patch.contains("credentials"))
    }

    /**
     * dsh 0.2.1：`settings` 行改为 `@deepseek-ai/dsh-settings`（无 Config，`path` 被静默忽略），
     * `agent-presets` 行已不存在（改了名字/Config）。两者再 patch 只会"看起来生效实则无效"
     * （0.2.1 实测：`patch: entry "agent-presets" not found` 被 warn 跳过），故必须彻底不生成。
     */
    @Test
    fun `never patches rows that dsh 0_2_1 dropped or ignores`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertFalse(patch.contains("- id: settings"), "dsh 0.2.1 ignores the settings row config: $patch")
        assertFalse(patch.contains("settings.yaml"), "settings.yaml is no longer a live settings document: $patch")
        assertFalse(patch.contains("- id: agent-presets"), "the agent-presets row no longer exists: $patch")
        assertFalse(patch.contains("includeUserRoot"), "agent-preset-registry has no includeUserRoot option: $patch")
        assertFalse(patch.contains("trust: user"))
    }

    @Test
    fun `redirects credentials to the shared config root`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertTrue(patch.contains("- id: credentials"), "must patch the credentials row: $patch")
        assertTrue(
            patch.contains("path: 'C:/Users/me/AppData/Roaming/JetBrains/IdeaIC2024.1/dsh-idea/dsh-home/.credentials.yaml'"),
            "credentials path must be the forward-slash shared root: $patch",
        )
    }

    /**
     * 用户技能根 = `<dshHome>/skills`；项目根 `<项目>/.dsh/skills` 仍按项目（不在此 patch 中改动）。
     *
     * dsh 0.2.1 的 web 层把 `skill-filesystem` 行 `disabled: true`（预设接管技能发现），
     * 只替换 `config` 会保留禁用 → 必须显式 `disabled: false` 才能让它作为部署级技能提供者注册。
     */
    @Test
    fun `shares the personal skills root and re-enables the disabled row`() {
        val patch = McpPatchGenerator.generate(8080, shared)
        assertTrue(patch.contains("- id: skill-filesystem"))
        assertTrue(patch.contains("disabled: false"), "0.2.1 disables this row in the web layer: $patch")
        assertTrue(patch.contains("dshHome: 'C:/Users/me/AppData/Roaming/JetBrains/IdeaIC2024.1/dsh-idea/dsh-home'"))
    }

    @Test
    fun `escapes single quotes in the shared root`() {
        val patch = McpPatchGenerator.generate(8080, "/home/o'brien/.config/dsh-idea/dsh-home")
        assertTrue(patch.contains("path: '/home/o''brien/.config/dsh-idea/dsh-home/.credentials.yaml'"), patch)
        assertTrue(patch.contains("dshHome: '/home/o''brien/.config/dsh-idea/dsh-home'"), patch)
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
        assertTrue(patch.contains("- id: credentials"))
        assertTrue(patch.contains("- id: skill-filesystem"))
    }
}
