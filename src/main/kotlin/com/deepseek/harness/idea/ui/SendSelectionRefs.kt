package com.deepseek.harness.idea.ui

/**
 * "发送选中代码"的**纯函数**：紧凑引用构造与重复引用判定（无平台依赖，可单测）。
 *
 * 见 docs/DESIGN.md §3.7：
 * - 注入内容仅 `@绝对路径#L起始-结束` + 尾随换行（无提示语、无代码本体），光标落在下一行等用户输入；
 * - dsh 的输入框是**累积**内容（注入是追加而非替换），而右键菜单/快捷键可能在极短时间内触发两次
 *   （双击），导致同一条引用被追加两遍（用户截图实测：
 *   `@…application.yml#L5-15@…application.yml#L5-15`）。故提供"同一引用 + 短时间窗"去重判定。
 */
object SendSelectionRefs {

    /** "发送选中代码"去重时间窗（毫秒）。 */
    const val DEDUPE_WINDOW_MS = 1200L

    /**
     * 构造紧凑引用：`@绝对路径#L起始-结束` + 尾随换行。
     *
     * - 路径统一正斜杠（dsh 侧与 `@` 引用解析都按正斜杠处理）；
     * - 有选区（`lineEnd > 0`）时带行号；单行选区省略结束行；整文件（无选区）只留路径；
     * - 路径为空 → 空串（调用方应跳过注入）。
     */
    fun compactReference(filePath: String?, lineStart: Int, lineEnd: Int): String {
        if (filePath.isNullOrBlank()) return ""
        val sb = StringBuilder()
        sb.append('@').append(filePath.replace('\\', '/'))
        if (lineEnd > 0) {
            sb.append("#L").append(lineStart)
            if (lineEnd > lineStart) sb.append('-').append(lineEnd)
        }
        sb.append('\n')
        return sb.toString()
    }

    /**
     * [ref] 与"上一次的引用 + 时间"是否构成重复触发。
     * 仅当引用完全相同**且**间隔落在 `[0, windowMs]` 内才算重复（时间回拨视为不重复）。
     */
    fun isDuplicate(ref: String, lastRef: String?, lastAt: Long, now: Long, windowMs: Long = DEDUPE_WINDOW_MS): Boolean {
        if (ref.isBlank() || lastRef != ref) return false
        val delta = now - lastAt
        return delta in 0..windowMs
    }
}
