package com.deepseek.harness.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * composer 注入脚本的构造契约（v0.2.4 修复"发送选中代码 / 一键解释没反应"）。
 *
 * 背景（真实 dsh 0.1.5 页面 CDP 实测）：输入框是 **Lexical `contenteditable`**
 * （`<div data-lexical-editor="true" role="textbox" contenteditable="true">`），
 * 旧的 `document.querySelector('textarea')` 永远找不到元素；并且写入后**当拍**
 * `innerText`/`textContent` 可能读不到内容（文本在 `[data-lexical-text="true"]` 节点下），
 * "写后立即回读"会把成功误判为失败、导致降级到剪贴板（用户感知为"没反应"）。
 *
 * 本测试锁定已验证有效的脚本契约，防止回归。
 */
class ComposerScriptsTest {

    private fun build(text: String, submit: Boolean, funcName: String? = null): String =
        ComposerScripts.build(text, submit, funcName)

    @Test
    fun `outcome constants match the report payloads`() {
        val script = build("hi", submit = true, funcName = "cb")
        listOf(
            ComposerScripts.OUTCOME_NOT_FOUND,
            ComposerScripts.OUTCOME_FAILED,
            ComposerScripts.OUTCOME_SUBMITTED,
            ComposerScripts.OUTCOME_BLOCKED,
        ).forEach { assertTrue(script.contains("report('$it')"), "script must be able to report '$it'") }
    }

    @Test
    fun `targets textarea first and falls back to the Lexical editor`() {
        val script = build("@C:/p/Main.kt#L1-5\n", submit = false)
        assertTrue(script.contains("document.querySelector('textarea')"), "must still support the legacy textarea")
        assertTrue(script.contains("[data-lexical-editor=\"true\"]"), "must target dsh 0.1.5's Lexical editor")
        assertTrue(script.contains("[contenteditable=\"true\"][role=\"textbox\"]"), "must have a role=textbox fallback")
    }

    @Test
    fun `writes through insertText exactly once and never stacks a second mechanism`() {
        val script = build("hello", submit = false)
        assertTrue(script.contains("document.execCommand('insertText'"), "Lexical only syncs via beforeinput → insertText")
        assertTrue(script.contains("new Event('input', { bubbles: true })"), "legacy textarea path must still fire input")
        // 实测 bug：insertText 同步生效但 Lexical 异步更新 DOM → 旧实现在"立即回读为空"时又补一次 paste，
        // 同一份被写两遍（用户看到 @a#L7-11@a#L7-11）。写入路径必须只有一条。
        assertFalse(
            script.contains("ClipboardEvent('paste'"),
            "must not stack a paste fallback on top of insertText (that double-wrote the reference)",
        )
        assertEquals(1, Regex("execCommand\\('insertText'").findAll(script).count(), "exactly one insertText call site")
    }

    @Test
    fun `reads back through lexical text nodes and squashes whitespace`() {
        val script = build("hello", submit = false)
        assertTrue(
            script.contains("[data-lexical-text=\"true\"]"),
            "same-tick innerText/textContent can be empty; lexical text nodes carry the real content",
        )
        // dsh 会把 @路径 渲染成文件引用 chip，其 textContent 与文本节点之间不带空白 →
        // 判重/回读都必须去掉全部空白再比较，否则同一个引用会被判成"不存在"而重复注入
        assertTrue(script.contains("const squash = (s) => String(s).replace(/\\s+/g, '')"), "must squash whitespace")
        assertTrue(script.contains(".join('\\n')"), "text nodes must be joined with a separator")
    }

    @Test
    fun `waits for the composer to render before reporting failure`() {
        val script = build("hello", submit = false)
        assertTrue(script.contains("const deadline = Date.now() + 8000;"), "composer may not be rendered yet")
        assertTrue(script.contains("setTimeout(() => attempt(tryNo), 300)"), "must retry until the editor appears")
    }

    /**
     * 用户报告的重复问题（`@…application.yml#L5-15@…application.yml#L5-15`）：
     * 必须"已包含同一引用就不再写入"。
     */
    @Test
    fun `never injects the same reference twice`() {
        val script = build("@E:/code/proj/application.yml#L5-15\n", submit = false)
        assertTrue(script.contains("const hasTarget = (el) => {"), "need a presence check")
        assertTrue(script.contains("if (hasTarget(el)) {"), "must skip writing when the reference is already present")
        // 空串包含会误判成功（实测踩过）→ 判定必须要求两边都非空
        assertTrue(script.contains("want.length === 0 || got.length === 0"), "empty-string containment must not count as a hit")
        // 只读到片段（如 `@…/resources/`）时不得误判"已存在"，否则会漏写真正的引用
        assertTrue(
            script.contains("got.length >= want.length * 0.8"),
            "a partial readback must not be mistaken for the full reference",
        )
    }

    /**
     * 实测：Lexical 空编辑器上**首次** `insertText` 可能整段不生效（读回仍为空），
     * 因此必须"轮询 + 在 8s 窗口内重试"，不能一次写不进去就报 failed。
     */
    @Test
    fun `retries the write when the first attempt has no effect`() {
        val script = build("hello", submit = false)
        assertTrue(script.contains("setTimeout(() => attempt(tryNo + 1), 250)"), "must retry a no-op write")
        assertTrue(script.contains("const grew = now.length > before.length && now !== before;"))
        assertTrue(script.contains("const attempt = (tryNo) =>"))
    }

    @Test
    fun `reports a distinguishable outcome for each failure mode`() {
        val script = build("hello", submit = false)
        assertTrue(script.contains("report('notfound')"), "no editor rendered must be distinguishable from a write failure")
        assertTrue(script.contains("report('failed')"), "a write that never lands must be reported")
        assertTrue(script.contains("report('injected')"), "selection mode reports a successful prefill")
    }

    @Test
    fun `question mode presses enter and falls back to the send button`() {
        val submitScript = build("explain this", submit = true)
        assertTrue(submitScript.contains("const shouldSubmit = true;"))
        assertTrue(submitScript.contains("new KeyboardEvent('keydown'"), "Enter submits the composer")
        assertTrue(submitScript.contains("keyCode: 13"), "dsh's composer reads the legacy key fields too")

        val prefillScript = build("ref only", submit = false)
        assertTrue(prefillScript.contains("const shouldSubmit = false;"))
        assertTrue(prefillScript.contains("report('injected')"))
    }

    @Test
    fun `send button selector never matches unrelated buttons`() {
        val script = build("hello", submit = true)
        assertTrue(script.contains("button[aria-label=\"Send message\"]"))
        assertTrue(script.contains("button[aria-label=\"发送消息\"]"))
        // 绝不能用 class 通配（运行中主按钮会变成"停止"，误点会打断智能体）
        assertFalse(script.contains("querySelectorAll('button')"), "must not blind-click buttons")
        assertTrue(script.contains("!btn.disabled"))
    }

    @Test
    fun `reports through the injected js query function when available`() {
        val withChannel = build("hi", submit = false, funcName = "dshBridge42")
        assertTrue(withChannel.contains("window.dshBridge42("), "must call back into JBCefJSQuery")

        val withoutChannel = build("hi", submit = false, funcName = null)
        // 注意：脚本里仍有 window.getSelection()，因此断言"无回调符号/无回传信封"而非"不含 window."
        assertFalse(withoutChannel.contains("window.dshBridge"), "no channel → no callback symbol")
        assertFalse(withoutChannel.contains("onSuccess"), "no channel → no JBCefJSQuery envelope")
        assertTrue(withoutChannel.contains("const report = () => {};"))
    }

    @Test
    fun `embeds the payload as a json string literal`() {
        val script = build("line1\nline2 \"quoted\"", submit = false)
        assertTrue(script.contains("const text = \"line1\\nline2 \\\"quoted\\\"\";"), script)
    }

    @Test
    fun `keeps paths intact and normalizes whitespace when matching`() {
        val script = build("@D:/a b/Main.kt#L1-5\n", submit = false)
        assertTrue(script.contains("@D:/a b/Main.kt#L1-5\\n"))
        assertTrue(script.contains("replace(/\\s+/g, ' ')"))
    }

    @Test
    fun `escapeJs escapes quotes backslashes and control characters`() {
        assertEquals("\"a\\\"b\"", ComposerScripts.escapeJs("a\"b"))
        assertEquals("\"a\\\\b\"", ComposerScripts.escapeJs("a\\b"))
        assertEquals("\"a\\nb\\r\\t\"", ComposerScripts.escapeJs("a\nb\r\t"))
        assertEquals("\"\\u0001\"", ComposerScripts.escapeJs("\u0001"))
        assertEquals("\"\"", ComposerScripts.escapeJs(""))
    }
}
