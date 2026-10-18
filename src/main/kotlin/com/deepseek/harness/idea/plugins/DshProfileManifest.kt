package com.deepseek.harness.idea.plugins

/**
 * dsh profile 的 `package.json`（profile manifest）读取 + 插件规格校验（纯逻辑，无 IDE 依赖，可单测）。
 *
 * **背景（v0.2.5）**：dsh 用 profile 目录下的 `package.json` 声明"这个 profile 由哪些 bundle 层组成"：
 * ```json
 * {
 *   "name": "dsh-profile-web",
 *   "private": true,
 *   "dependencies": { "my-dsh-plugin": "^1.2.0" },
 *   "dsh": { "profile": { "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "my-dsh-plugin"],
 *                          "patchReload": "live" } }
 * }
 * ```
 * `dsh plugin --profile web add <pkg>`（= pnpm 转发器）在安装完成后**自行**把"声明了 `dsh.bundle`
 * 的新依赖"追加进 `bundles`、把不再是 bundle 的依赖移除（`reconcilePlugins`），因此插件**不写**
 * 这个文件 —— 只读它来展示"已装了哪些插件"。
 *
 * 解析用**内置极简 JSON 读取器**而不是引入第三方库：只需要读一个已知形状的小文件，
 * 而插件运行时不应为一个字段引入额外依赖（平台自带 gson 的可用性随 IDE 版本变化）。
 * 解析失败（文件损坏/正在被并发写入）时返回空 profile —— 展示层降级，绝不抛异常。
 */
internal object DshProfileManifest {

    /** profile 名（插件只启动 `--profile web`，见 `DshProcessManager`）。 */
    const val PROFILE_NAME = "web"

    /**
     * profile 模板自带的**内置 bundle**（dsh `PROFILE_TEMPLATES.web.bundles`）。
     *
     * 它们不是 `dependencies` 里的依赖（由安装树的 `profiles/node_modules` 供给），
     * 因此"用户装的插件" = `bundles` 去掉这两项。
     */
    val BASE_BUNDLES = listOf("@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app")

    /** manifest 文件名（profile 目录下）。 */
    const val FILE_NAME = "package.json"

    /** 插件规格校验结果；`null` 表示合法。 */
    enum class SpecError {
        /** 空输入。 */
        EMPTY,

        /** 以 `-` 开头（会被 pnpm 当成选项而不是包名）。 */
        LEADING_DASH,

        /** 含空白或 shell 元字符 —— dsh 在 Windows 上以 `shell: true` 转发参数，会被解释/截断。 */
        ILLEGAL_CHARS,

        /** 相对路径规格（`.` / `..` / `link:./x`）：dsh 以 profile 目录为 cwd 锚定，会解析到错误位置。 */
        RELATIVE_PATH,
    }

    /** 表格一行：已安装的项。 */
    data class Entry(
        /** 包名（`dependencies` 的键，或内置 bundle 名）。 */
        val name: String,
        /** 声明版本（内置 bundle 为 null）。 */
        val version: String?,
        /** 是否为 profile bundle（参与 patch 层叠）；false = 纯依赖（装上了但不会被加载为插件）。 */
        val bundle: Boolean,
    )

    /** profile manifest 的结构化视图。 */
    data class Profile(
        val name: String?,
        val bundles: List<String>,
        val dependencies: Map<String, String>,
        val patchReload: String?,
    ) {
        /** 用户安装的插件（bundle 层里除内置 bundle 之外的项，保持声明顺序）。 */
        val plugins: List<String> get() = bundles.filterNot { it in BASE_BUNDLES }

        /** 表格行：先插件（bundle），后纯依赖；各自按名字排序，便于查找。 */
        fun entries(): List<Entry> {
            val bundleSet = bundles.toSet()
            val plugins = bundles.filterNot { it in BASE_BUNDLES }
                .map { Entry(it, dependencies[it], true) }
                .sortedBy { it.name.lowercase() }
            val plain = dependencies.entries
                .filterNot { it.key in bundleSet }
                .map { Entry(it.key, it.value, false) }
                .sortedBy { it.name.lowercase() }
            return plugins + plain
        }

        companion object {
            val EMPTY = Profile(null, emptyList(), emptyMap(), null)
        }
    }

    /** 解析 manifest 文本（null/空/损坏 → [Profile.EMPTY]）。 */
    fun parse(text: String?): Profile {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return Profile.EMPTY
        val root = runCatching { JsonCursor(raw).value() }.getOrNull() as? Map<*, *> ?: return Profile.EMPTY
        val dsh = root["dsh"] as? Map<*, *>
        val profile = dsh?.get("profile") as? Map<*, *>
        val bundles = (profile?.get("bundles") as? List<*>).orEmpty().filterIsInstance<String>()
        val dependencies = LinkedHashMap<String, String>()
        (root["dependencies"] as? Map<*, *>)?.forEach { (k, v) ->
            if (k is String) dependencies[k] = scalarText(v)
        }
        return Profile(
            name = root["name"] as? String,
            bundles = bundles,
            dependencies = dependencies,
            patchReload = profile?.get("patchReload") as? String,
        )
    }

    /** 规整用户输入的插件规格（仅去首尾空白；不改大小写、不补版本）。 */
    fun normalizeSpec(raw: String?): String = raw?.trim().orEmpty()

    /** 标量转文本：整数形态的数字不要显示成 `1.0`（版本号被写成裸数字时才会出现）。 */
    private fun scalarText(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v % 1.0 == 0.0 && v < 1e15 && v > -1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    /**
     * 校验插件规格。
     *
     * 允许：registry 包名与版本范围（`@scope/pkg`、`pkg@^1.2.0`、`pkg@~1.2.0`）、git 规格
     * （`github:user/repo`）、**绝对**路径规格（`link:/opt/plugin`、`file:C:/plugins/x`）。
     *
     * 拒绝（都是实测过的踩坑点，见 docs/PROJECT_NOTES.md）：
     * - 空白与 `"` `%` `!` `&` `|` `<` `>` `;` `$` 等：dsh 的 `runPlugin` 在 Windows 上用
     *   `spawnSync(..., shell: true)` 转发参数且 Node **不自动加引号**，`a&b` 实测只传进 `a`
     *   并返回非零、`pkg@^1.2.0` 的 `^` 会被 cmd 当转义符吃掉；
     * - 相对路径规格：dsh 以**调用进程的 cwd**（= profile 目录）为锚点解析，结果会落到
     *   `<profile>/../…` 这种意想不到的地方，因此只接受绝对路径。
     */
    fun validateSpec(raw: String?): SpecError? {
        val s = normalizeSpec(raw)
        if (s.isEmpty()) return SpecError.EMPTY
        if (s.startsWith("-")) return SpecError.LEADING_DASH
        if (RELATIVE_SPEC.matches(s)) return SpecError.RELATIVE_PATH
        // 允许字母数字（含非 ASCII：包名/路径可能出现中文）与包名/路径分隔符
        val ok = s.all { c -> c.isLetterOrDigit() || c in "@._/+~:#-\\^" }
        return if (ok) null else SpecError.ILLEGAL_CHARS
    }

    /** 相对路径规格：`.`、`..`、`./x`、`../x`，可带 `file:` / `link:` 前缀。 */
    private val RELATIVE_SPEC = Regex("""^(?:file:|link:)?\.{1,2}(?:[/\\].*)?$""")

    /**
     * 是否需要用双引号包住参数再交给 dsh。
     *
     * 只有 Windows 需要：`cmd` 会把 `^` 当转义符（实测 `pkg@^1.2.0` 变成 `pkg@1.2.0`，语义从
     * "范围"变成"精确版本"），而 `"pkg@^1.2.0"` 实测能原样传进 pnpm。Unix 上 dsh 用
     * `shell: false`，加引号反而会把引号变成参数内容。
     */
    fun needsWindowsQuote(spec: String): Boolean = spec.contains('^')
}

/**
 * 极简 JSON 读取器（对象/数组/字符串/数字/布尔/null）。
 *
 * 只服务 [DshProfileManifest] 的展示需求：不做流式解析、不做路径查询，遇到语法错误直接抛异常，
 * 由调用方 `runCatching` 降级。数字统一按 [Double] 返回（本文件只读字符串字段，不影响使用）。
 */
private class JsonCursor(private val text: String) {
    private var i = 0

    fun value(): Any? {
        skipWs()
        if (i >= text.length) throw IllegalStateException("unexpected end of JSON")
        return when (text[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> num()
        }
    }

    private fun skipWs() {
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF') i++ else return
        }
    }

    private fun obj(): Map<String, Any?> {
        i++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < text.length && text[i] == '}') { i++; return map }
        while (true) {
            skipWs()
            val key = str()
            skipWs()
            if (i >= text.length || text[i] != ':') throw IllegalStateException("expected ':' at $i")
            i++
            map[key] = value()
            skipWs()
            if (i >= text.length) throw IllegalStateException("unterminated object")
            when (text[i]) {
                ',' -> i++
                '}' -> { i++; return map }
                else -> throw IllegalStateException("expected ',' or '}' at $i")
            }
        }
    }

    private fun arr(): List<Any?> {
        i++ // '['
        val list = ArrayList<Any?>()
        skipWs()
        if (i < text.length && text[i] == ']') { i++; return list }
        while (true) {
            list.add(value())
            skipWs()
            if (i >= text.length) throw IllegalStateException("unterminated array")
            when (text[i]) {
                ',' -> i++
                ']' -> { i++; return list }
                else -> throw IllegalStateException("expected ',' or ']' at $i")
            }
        }
    }

    private fun str(): String {
        if (i >= text.length || text[i] != '"') throw IllegalStateException("expected string at $i")
        i++
        val sb = StringBuilder()
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> { i++; return sb.toString() }
                '\\' -> {
                    i++
                    if (i >= text.length) throw IllegalStateException("unterminated escape")
                    when (val e = text[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= text.length) throw IllegalStateException("bad \\u escape")
                            sb.append(text.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalStateException("bad escape \\$e")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        throw IllegalStateException("unterminated string")
    }

    private fun num(): Double {
        val start = i
        while (i < text.length && (text[i].isDigit() || text[i] in "-+.eE")) i++
        if (start == i) throw IllegalStateException("unexpected character at $start")
        return text.substring(start, i).toDoubleOrNull() ?: throw IllegalStateException("bad number at $start")
    }

    private fun expect(literal: String) {
        if (!text.startsWith(literal, i)) throw IllegalStateException("expected $literal at $i")
        i += literal.length
    }
}
