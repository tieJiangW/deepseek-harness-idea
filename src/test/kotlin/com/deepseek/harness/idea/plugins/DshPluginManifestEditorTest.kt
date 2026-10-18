package com.deepseek.harness.idea.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 卸载兜底的 manifest 文本编辑（v0.2.5）。
 *
 * 运行中的 dsh 会占用 `node_modules`，pnpm 删不掉（实测稳定 `exit=1`），因此卸载在失败时会改为
 * 从 manifest 移除声明 —— 但这是**手写 JSON 编辑**，必须把逗号/空对象/唯一项/内联写法/同名前缀
 * 这些坑全部锁在测试里，否则会把用户 profile 写坏（dsh 对 manifest 解析失败是 fail-loud）。
 *
 * 注意：这里的输入必须是**合法 JSON**（真实 manifest 由 dsh/pnpm 生成，不会有尾逗号）——
 * 第一版测试模板自己带尾逗号，结果把"解析失败"误当成"编辑写坏"，白折腾一轮。
 */
class DshPluginManifestEditorTest {

    @Test
    fun `removes a middle dependency and its bundle entry`() {
        val text = """
            {
              "name": "dsh-profile-web",
              "private": true,
              "dependencies": {
                "a": "^1.0.0",
                "dshmarket": "^1.66.37",
                "z": "link:D:/plugins/z"
              },
              "dsh": {
                "profile": {
                  "bundles": [
                    "@deepseek-ai/dsh-base",
                    "@deepseek-ai/dsh-web-app",
                    "a",
                    "dshmarket",
                    "z"
                  ],
                  "patchReload": "live"
                }
              }
            }
        """.trimIndent()

        val after = DshPluginManifestEditor.removePackage(text, "dshmarket")
        val profile = DshProfileManifest.parse(after)

        assertFalse(DshPluginManifestEditor.stillDeclares(after, "dshmarket"))
        assertEquals(listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "a", "z"), profile.bundles)
        assertEquals(listOf("a", "z"), profile.dependencies.keys.toList())
        assertEquals("dsh-profile-web", profile.name, "the manifest must stay parseable:\n$after")
        assertEquals("live", profile.patchReload)
    }

    @Test
    fun `removes the last entries without leaving a trailing comma`() {
        val text = """
            {
              "dependencies": {
                "a": "^1.0.0",
                "dshmarket": "^1.66.37"
              },
              "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "dshmarket"] } }
            }
        """.trimIndent()

        val after = DshPluginManifestEditor.removePackage(text, "dshmarket")
        val profile = DshProfileManifest.parse(after)

        assertFalse(DshPluginManifestEditor.stillDeclares(after, "dshmarket"))
        assertEquals(listOf("@deepseek-ai/dsh-base"), profile.bundles)
        assertEquals(listOf("a"), profile.dependencies.keys.toList())
        assertFalse(Regex(",\\s*[}\\]]").containsMatchIn(after), "no trailing comma allowed:\n$after")
    }

    @Test
    fun `removing the only entries leaves empty but valid containers`() {
        val text = """
            {
              "dependencies": { "dshmarket": "^1.66.37" },
              "dsh": { "profile": { "bundles": ["dshmarket"] } }
            }
        """.trimIndent()

        val after = DshPluginManifestEditor.removePackage(text, "dshmarket")
        val profile = DshProfileManifest.parse(after)

        assertTrue(profile.dependencies.isEmpty(), "dependencies should be empty:\n$after")
        assertTrue(profile.bundles.isEmpty(), "bundles should be empty:\n$after")
        assertFalse(DshPluginManifestEditor.stillDeclares(after, "dshmarket"))
    }

    @Test
    fun `a package that is not declared is left untouched`() {
        val text = """
            {
              "dependencies": { "a": "^1.0.0" },
              "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "a"] } }
            }
        """.trimIndent()
        assertEquals(text, DshPluginManifestEditor.removePackage(text, "dshmarket"))
    }

    @Test
    fun `a similarly named package is preserved`() {
        val text = """
            {
              "dependencies": { "dshmarket-tools": "^1.0.0", "dshmarket": "^1.66.37" },
              "dsh": { "profile": { "bundles": ["dshmarket-tools", "dshmarket"] } }
            }
        """.trimIndent()

        val after = DshPluginManifestEditor.removePackage(text, "dshmarket")
        val profile = DshProfileManifest.parse(after)

        assertEquals(listOf("dshmarket-tools"), profile.bundles, after)
        assertEquals(listOf("dshmarket-tools"), profile.dependencies.keys.toList(), after)
        assertFalse(DshPluginManifestEditor.stillDeclares(after, "dshmarket"))
    }

    @Test
    fun `unknown fields survive the edit`() {
        val text = """
            {
              "name": "dsh-profile-web",
              "packageManager": "pnpm@9.15.9",
              "pnpm": { "overrides": { "left-pad": "1.3.0" } },
              "dependencies": { "dshmarket": "^1.66.37" },
              "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "dshmarket"], "patchReload": "live" } }
            }
        """.trimIndent()
        val after = DshPluginManifestEditor.removePackage(text, "dshmarket")
        assertTrue(after.contains("\"packageManager\": \"pnpm@9.15.9\""), after)
        assertTrue(after.contains("\"overrides\""), after)
        assertTrue(after.contains("\"patchReload\": \"live\""), after)
        assertFalse(DshPluginManifestEditor.stillDeclares(after, "dshmarket"))
    }

    @Test
    fun `a scoped package is removed cleanly`() {
        val text = """
            {
              "dependencies": { "@scope/plugin": "^2.0.0", "keep": "1.0.0" },
              "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "@scope/plugin", "keep"] } }
            }
        """.trimIndent()
        val after = DshPluginManifestEditor.removePackage(text, "@scope/plugin")
        val profile = DshProfileManifest.parse(after)
        assertEquals(listOf("@deepseek-ai/dsh-base", "keep"), profile.bundles, after)
        assertEquals(listOf("keep"), profile.dependencies.keys.toList(), after)
    }
}
