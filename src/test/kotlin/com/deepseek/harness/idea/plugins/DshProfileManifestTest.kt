package com.deepseek.harness.idea.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * dsh profile manifest 解析与插件规格校验（v0.2.5）。
 *
 * 解析器是**手写的极简 JSON 读取器**（为不引入第三方依赖，见 [DshProfileManifest] 注释），
 * 因此这里既覆盖真实 pnpm/dsh 生成的文件形状，也覆盖转义、损坏输入与空输入 —— 展示层
 * 绝不能因为一个坏文件而抛异常。
 */
class DshProfileManifestTest {

    /** dsh 0.1.5 实际生成的形状（pnpm 2 空格缩进，bundles 顺序即层叠顺序）。 */
    private val realManifest = """
        {
          "name": "dsh-profile-web",
          "private": true,
          "dependencies": {
            "@scope/my-plugin": "^1.2.0",
            "plain-lib": "2.0.1"
          },
          "dsh": {
            "profile": {
              "bundles": [
                "@deepseek-ai/dsh-base",
                "@deepseek-ai/dsh-web-app",
                "@scope/my-plugin"
              ],
              "patchReload": "live"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses a manifest produced by dsh`() {
        val profile = DshProfileManifest.parse(realManifest)
        assertEquals("dsh-profile-web", profile.name)
        assertEquals(
            listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "@scope/my-plugin"),
            profile.bundles,
        )
        assertEquals(mapOf("@scope/my-plugin" to "^1.2.0", "plain-lib" to "2.0.1"), profile.dependencies)
        assertEquals("live", profile.patchReload)
    }

    @Test
    fun `user plugins exclude the built-in bundles`() {
        val profile = DshProfileManifest.parse(realManifest)
        assertEquals(listOf("@scope/my-plugin"), profile.plugins)
    }

    @Test
    fun `entries list bundles first then plain dependencies`() {
        val entries = DshProfileManifest.parse(realManifest).entries()
        assertEquals(
            listOf(
                DshProfileManifest.Entry("@scope/my-plugin", "^1.2.0", bundle = true),
                DshProfileManifest.Entry("plain-lib", "2.0.1", bundle = false),
            ),
            entries,
        )
    }

    @Test
    fun `a dependency that is also a bundle is listed once as a plugin`() {
        val text = """
            {"dependencies":{"p":"1"},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","p"]}}}
        """.trimIndent()
        val entries = DshProfileManifest.parse(text).entries()
        assertEquals(1, entries.size)
        assertEquals(DshProfileManifest.Entry("p", "1", bundle = true), entries.single())
    }

    @Test
    fun `handles escapes and unicode escapes`() {
        val text = """{"name":"a\"b\\c\u4e2d","dependencies":{"x/y":"1.0.0"},"dsh":{"profile":{"bundles":["x/y"]}}}"""
        val profile = DshProfileManifest.parse(text)
        assertEquals("a\"b\\c中", profile.name)
        assertEquals(listOf("x/y"), profile.bundles)
    }

    @Test
    fun `empty or broken input degrades to an empty profile`() {
        listOf(null, "", "   ", "not json", "{", """{"dsh": {"profile": {}}""", "[]").forEach { text ->
            val profile = DshProfileManifest.parse(text)
            assertEquals(DshProfileManifest.Profile.EMPTY, profile, "input: $text")
            assertTrue(profile.entries().isEmpty())
        }
    }

    @Test
    fun `non-string and unknown fields are ignored`() {
        val text = """{"dependencies":{"p":123},"dsh":{"profile":{"bundles":["p",7]}},"other":true}"""
        val profile = DshProfileManifest.parse(text)
        assertEquals(listOf("p"), profile.bundles)
        assertEquals("123", profile.dependencies["p"])
        assertNull(profile.patchReload)
    }

    // ---- 规格校验 ----

    @Test
    fun `valid specs`() {
        listOf(
            "my-dsh-plugin",
            "@scope/plugin",
            "@scope/plugin@^1.2.0",
            "plugin@1.0.0-rc.1",
            "plugin@~1.2.0",
            "link:/opt/plugins/mine",
            "file:C:\\plugins\\mine",
            "github:user/repo",
            "插件",
        ).forEach { spec ->
            assertNull(DshProfileManifest.validateSpec(spec), "expected valid: $spec")
        }
    }

    @Test
    fun `relative path specs are rejected because dsh anchors them to the profile dir`() {
        listOf("./relative", "../plugin", ".", "..", "link:../plugin", "file:./x", "link:.\\x").forEach { spec ->
            assertEquals(
                DshProfileManifest.SpecError.RELATIVE_PATH,
                DshProfileManifest.validateSpec(spec),
                "expected relative: $spec",
            )
        }
    }

    @Test
    fun `caret ranges need quoting only on Windows`() {
        // 实测：cmd 会把 ^ 当转义符吃掉（pkg@^1.2.0 → pkg@1.2.0），加引号后可原样传入
        assertTrue(DshProfileManifest.needsWindowsQuote("pkg@^1.2.0"))
        assertTrue(!DshProfileManifest.needsWindowsQuote("pkg@1.2.0"))
        assertTrue(!DshProfileManifest.needsWindowsQuote("file:C:\\plugins\\mine"))
    }

    @Test
    fun `empty spec is rejected`() {
        assertEquals(DshProfileManifest.SpecError.EMPTY, DshProfileManifest.validateSpec(null))
        assertEquals(DshProfileManifest.SpecError.EMPTY, DshProfileManifest.validateSpec("   "))
    }

    @Test
    fun `leading dash is rejected`() {
        assertEquals(DshProfileManifest.SpecError.LEADING_DASH, DshProfileManifest.validateSpec("--global"))
    }

    @Test
    fun `whitespace and shell metacharacters are rejected`() {
        // dsh 在 Windows 上用 spawnSync(..., shell: true) 转发参数，Node 不会为它们加引号
        listOf("my plugin", "a&b", "a|b", "a;rm", "a%PATH%", "a\"b", "a'b", "a\nb", ">=1.0.0").forEach { spec ->
            assertEquals(
                DshProfileManifest.SpecError.ILLEGAL_CHARS,
                DshProfileManifest.validateSpec(spec),
                "expected rejected: $spec",
            )
        }
    }

    @Test
    fun `normalizeSpec only trims`() {
        assertEquals("pkg@1", DshProfileManifest.normalizeSpec("  pkg@1  "))
        assertEquals("", DshProfileManifest.normalizeSpec(null))
    }
}
