package com.deepseek.harness.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * "发送选中代码"的引用构造与**重复引用防护**（v0.2.4）。
 *
 * 背景（用户截图报告）：dsh 的输入框是**累积**内容（注入 = 追加，不是替换），而右键菜单/快捷键
 * 可能在极短时间内触发两次（双击），于是同一条引用被追加两遍：
 * `@…application.yml#L5-15@…application.yml#L5-15`。
 *
 * 防护分两层：
 * 1. 面板侧"同一引用 + 短时间窗"去重（[SendSelectionRefs.isDuplicate]）；
 * 2. 编辑器侧"已包含该引用则不重复写入"（见 `ComposerScriptsTest` 与 `ComposerScripts.build`）。
 */
class SendSelectionRefsTest {

    private val ref = "@E:/code/my-spring-ai-mcp/client/src/main/resources/application.yml#L5-15\n"

    // ---- compactReference ----

    @Test
    fun `builds the compact reference with forward slashes and a trailing newline`() {
        assertEquals(
            "@E:/code/proj/src/main/resources/application.yml#L5-15\n",
            SendSelectionRefs.compactReference("E:\\code\\proj\\src\\main\\resources\\application.yml", 5, 15),
        )
    }

    @Test
    fun `single line selection omits the end line`() {
        assertEquals("@E:/code/proj/Main.kt#L7\n", SendSelectionRefs.compactReference("E:\\code\\proj\\Main.kt", 7, 7))
    }

    @Test
    fun `whole file selection omits the line suffix entirely`() {
        assertEquals("@E:/code/proj/Main.kt\n", SendSelectionRefs.compactReference("E:\\code\\proj\\Main.kt", 0, 0))
    }

    @Test
    fun `blank path yields an empty reference`() {
        assertEquals("", SendSelectionRefs.compactReference(null, 1, 2))
        assertEquals("", SendSelectionRefs.compactReference("", 1, 2))
        assertEquals("", SendSelectionRefs.compactReference("   ", 1, 2))
    }

    @Test
    fun `the same selection always yields the identical reference`() {
        // 去重依赖引用文本稳定：同样输入必须产生完全一致的字符串（否则去重会失效）
        val a = SendSelectionRefs.compactReference("E:\\p\\A.kt", 1, 3)
        val b = SendSelectionRefs.compactReference("E:\\p\\A.kt", 1, 3)
        assertEquals(a, b)
        assertTrue(SendSelectionRefs.isDuplicate(b, a, 1_000L, 1_100L))
    }

    // ---- isDuplicate ----

    @Test
    fun `same ref inside the window is a duplicate`() {
        assertTrue(SendSelectionRefs.isDuplicate(ref, ref, 1_000L, 1_000L), "同一时刻重复到达")
        assertTrue(SendSelectionRefs.isDuplicate(ref, ref, 1_000L, 2_199L), "窗口内")
    }

    @Test
    fun `same ref at the window edge is a duplicate then released`() {
        val window = SendSelectionRefs.DEDUPE_WINDOW_MS
        assertTrue(SendSelectionRefs.isDuplicate(ref, ref, 1_000L, 1_000L + window), "恰好等于窗口应视为重复")
        assertFalse(SendSelectionRefs.isDuplicate(ref, ref, 1_000L, 1_001L + window), "超过窗口应允许再次发送")
    }

    @Test
    fun `different ref is never a duplicate`() {
        val other = "@E:/code/other/Main.kt#L1-5\n"
        assertFalse(SendSelectionRefs.isDuplicate(other, ref, 1_000L, 1_100L))
    }

    @Test
    fun `first send never counts as duplicate`() {
        assertFalse(SendSelectionRefs.isDuplicate(ref, null, 0L, 1_000L), "无历史引用")
        assertFalse(SendSelectionRefs.isDuplicate(ref, "", 0L, 1_000L), "历史为空串")
    }

    @Test
    fun `blank ref is never treated as a duplicate`() {
        assertFalse(SendSelectionRefs.isDuplicate("", "", 1_000L, 1_000L))
        assertFalse(SendSelectionRefs.isDuplicate("   ", "   ", 1_000L, 1_000L))
    }

    @Test
    fun `a backwards clock does not count as duplicate`() {
        assertFalse(SendSelectionRefs.isDuplicate(ref, ref, 5_000L, 4_000L), "时间回拨应放行")
    }

    @Test
    fun `custom window is honored`() {
        assertTrue(SendSelectionRefs.isDuplicate(ref, ref, 0L, 5_000L, windowMs = 6_000L))
        assertFalse(SendSelectionRefs.isDuplicate(ref, ref, 0L, 5_000L, windowMs = 4_000L))
    }
}
