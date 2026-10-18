package com.deepseek.harness.idea.plugins

/**
 * profile manifest 的**文本级编辑**（只用于"卸载"的兜底路径，纯函数、可单测）。
 *
 * **为什么需要它**：`dsh plugin remove` 是 pnpm 转发，pnpm 必须**删除** `node_modules` 里的文件；
 * 而运行中的 dsh 进程正持有这些文件（Windows 上删除被占用的文件会失败），于是卸载稳定返回
 * `exit=1`（实测：同一 profile 在 dsh 运行时 remove 必失败，停掉进程后成功）。
 *
 * dsh 判定"加载哪些插件"的**唯一依据**是 manifest 的 `dsh.profile.bundles`，所以从 manifest 里
 * 移除声明 = 真正卸载（重启 Harness 后不再加载）；`node_modules` 里的残留文件无害，下次
 * `dsh plugin add/remove` 时 pnpm 会按 manifest 自动对账。
 *
 * 编辑方式刻意选**文本级**而不是"解析成对象再序列化"：manifest 里可能有 `packageManager`、
 * `pnpm.overrides` 等我们不认识的字段，重新序列化会有丢失与格式漂移风险。
 *
 * 实现是**按顶层逗号切分容器、丢弃目标项后原样拼回**（不是正则替换）：正则很容易误伤
 * `"a"` 与 `"a-b"`、或在删除最后一项时留下非法尾逗号（这两种错误都会让 dsh 直接起不来）。
 */
internal object DshPluginManifestEditor {

    /**
     * 从 manifest 文本中移除某个包：`dependencies` 里的依赖 + `dsh.profile.bundles` 里的层项。
     *
     * 容错：包不存在时原样返回；结构异常（找不到容器/括号不配对）时也原样返回。
     *
     * @return 移除后的文本（未改变时与输入相同）
     */
    fun removePackage(text: String, pkg: String): String {
        if (pkg.isBlank() || text.isBlank()) return text
        var out = editContainer(text, "dependencies") { segment -> isDependencySegment(segment, pkg) }
        out = editContainer(out, "bundles") { segment -> isArrayItemSegment(segment, pkg) }
        return out
    }

    /** 移除结果里是否仍然声明了该包（`bundles` 或 `dependencies` 任一处残留 → 视为未移除）。 */
    fun stillDeclares(text: String, pkg: String): Boolean {
        val profile = DshProfileManifest.parse(text)
        return pkg in profile.bundles || pkg in profile.dependencies.keys
    }

    // ---- 内部实现 ----

    /** 该段是否为 `"pkg": "<range>"`（精确键名：`"a"` 不能命中 `"a-b"`）。 */
    private fun isDependencySegment(segment: String, pkg: String): Boolean {
        val t = segment.trim()
        val quoted = "\"$pkg\""
        if (!t.startsWith(quoted)) return false
        return t.removePrefix(quoted).trimStart().startsWith(":")
    }

    /** 该段是否为数组里的精确字符串项 `"pkg"`。 */
    private fun isArrayItemSegment(segment: String, pkg: String): Boolean =
        segment.trim() == "\"$pkg\""

    /**
     * 在 `"<key>": {...}` / `"<key>": [...]` 里按顶层逗号切分，丢掉命中的段，再原样拼回。
     * 每段保留自己的前导空白/换行，因此格式基本不变；全部被丢掉时容器变空（`{}` / `[]`）。
     */
    private fun editContainer(text: String, key: String, drop: (String) -> Boolean): String {
        val keyIdx = text.indexOf("\"$key\"")
        if (keyIdx < 0) return text
        var open = -1
        for (j in keyIdx + key.length + 2 until text.length) {
            val c = text[j]
            if (c == '{' || c == '[') {
                open = j
                break
            }
            if (c == '}' || c == ']') return text
        }
        if (open < 0) return text
        val close = matchingClose(text, open) ?: return text
        val inner = text.substring(open + 1, close)
        val parts = splitTopLevel(inner)
        val kept = parts.filterNot(drop)
        if (kept.size == parts.size) return text
        return text.substring(0, open + 1) + kept.joinToString(",") + text.substring(close)
    }

    /** 从 `openIdx` 处的 `{`/`[` 找到与之配对的闭合下标（跳过字符串字面量）。 */
    private fun matchingClose(text: String, openIdx: Int): Int? {
        val openChar = text[openIdx]
        val closeChar = if (openChar == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIdx until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 按**顶层**逗号切分（忽略字符串与嵌套容器里的逗号）。 */
    private fun splitTopLevel(inner: String): List<String> {
        if (inner.isBlank()) return emptyList()
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (c in inner) {
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    sb.append(c)
                }
                '{', '[' -> {
                    depth++
                    sb.append(c)
                }
                '}', ']' -> {
                    depth--
                    sb.append(c)
                }
                ',' -> if (depth == 0) {
                    parts.add(sb.toString())
                    sb.clear()
                } else {
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        parts.add(sb.toString())
        return parts
    }
}
