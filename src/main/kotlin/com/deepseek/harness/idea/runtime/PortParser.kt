package com.deepseek.harness.idea.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * 解析 dsh web 启动日志中的端口与启动 URL：
 * - 0.1.1：`dsh web: http://127.0.0.1:<port>`
 * - 0.1.5+：`dsh web: http://127.0.0.1:<port>/?token=<token>`（浏览器鉴权 token）
 */
object PortParser {
    private val RE = Regex("""dsh web: http://127\.0\.0\.1:(\d+)""")
    private val URL_RE = Regex("""dsh web: (http://127\.0\.0\.1:\d+\S*)""")

    fun parsePort(line: String): Int? =
        RE.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** 完整启动 URL（含 0.1.5 的 `?token=`）；无标记行返回 null。 */
    fun parseUrl(line: String): String? =
        URL_RE.find(line)?.groupValues?.get(1)?.trimEnd('.', ',', ';', ')')
}
