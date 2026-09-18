package com.deepseek.harness.idea.mcp

/**
 * 生成 `ide.yml` patch 内容（Step 3；v0.2.4 起同时承载"全局配置共享化"，见 docs/DESIGN.md §3.6/§4.5）。
 *
 * **语法（dsh 0.1.5-rc.2 实测）**：`--patch` 是叠加在 bundle 层之上的覆盖层，顶层是
 * `PatchOptions` 数组，两种形态：
 * - `insert:` 列表 → 新增条目（新增 mcp-client 必须显式写 `name`）；
 * - `- id: <rowId>` → 按 id **整份替换**该条目的 `config`（未改字段必须重述，不做深合并）。
 *
 * ⚠️ 历史坑（v0.1.3-dev ~ v0.2.3）：曾用 `- $settings:` / `- $credentials:` 指向全局配置，
 * 该语法**不被 dsh 接受**——`dsh --profile web --patch <ide.yml> --dump-config` 报
 * `patch: id is required for non-insert patches`，整条 patch 被丢弃（全局化机制从未生效）。
 * 正确写法是 `- id: settings` / `- id: credentials`（见 [generate]）。
 *
 * 生成示例（YAML，`<mcpPort>` / `sharedConfigRoot` 动态填入）：
 * ```yaml
 * - insert:
 *     - id: mcp.ide
 *       name: '@deepseek-ai/dsh-mcp-client'
 *       config:
 *         serverName: ide
 *         transport: streamable-http
 *         url: http://127.0.0.1:<mcpPort>/mcp
 *         toolCallTimeoutMs: 60000
 *         reconnect:
 *           enabled: true
 *           maxAttempts: 3
 * - id: settings
 *   config:
 *     path: '<shared>/settings.yaml'
 * ```
 */
object McpPatchGenerator {

    const val SERVER_NAME = "ide"
    private const val PLUGIN = "@deepseek-ai/dsh-mcp-client"
    private const val TOOL_TIMEOUT_MS = 60000
    private const val RECONNECT_MAX_ATTEMPTS = 3

    /** `dsh-agent-presets` 的 `Config.default` 是必填项：patch 替换整份 config 时必须重述。 */
    private const val DEFAULT_AGENT_PRESET = "standard"

    /**
     * 生成 patch 文本。
     *
     * [sharedConfigRoot] 是插件的共享配置根（`DshHomeManager.sharedConfigRoot()`），非空时追加四段
     * 按 id 覆盖的 patch，把 dsh 的用户级配置面全部指向共享目录 —— 实现"配置共享 + 数据隔离"：
     *
     * | 行 id | 作用 | 依据 |
     * |---|---|---|
     * | `settings` | 设置文档 → `<共享根>/settings.yaml` | `dsh-settings-file` `Config{path,dshHome}`，`path` 优先 |
     * | `credentials` | 凭据 → `<共享根>/.credentials.yaml` | `dsh-credentials-local` 同构 `Config` |
     * | `agent-presets` | 用户预设根 → `<共享根>/.agent-presets` | `roots` + `includeUserRoot:false`（否则仍扫 `$DSH_HOME/.agent-presets`） |
     * | `skill-filesystem` | 用户技能根 → `<共享根>/skills` | `dshHome` 决定用户根；项目根 `<项目>/.dsh/skills` 不变 |
     *
     * 空 [sharedConfigRoot] 只生成 mcp-client 段（单测/降级用；生产始终传共享根）。
     */
    fun generate(mcpPort: Int, sharedConfigRoot: String = "", host: String = "127.0.0.1"): String {
        val url = "http://$host:$mcpPort/mcp"
        return buildString {
            appendLine("- insert:")
            appendLine("    - id: mcp.$SERVER_NAME")
            appendLine("      name: '$PLUGIN'")
            appendLine("      config:")
            appendLine("        serverName: $SERVER_NAME")
            appendLine("        transport: streamable-http")
            appendLine("        url: $url")
            appendLine("        toolCallTimeoutMs: $TOOL_TIMEOUT_MS")
            appendLine("        reconnect:")
            appendLine("          enabled: true")
            appendLine("          maxAttempts: $RECONNECT_MAX_ATTEMPTS")
            if (sharedConfigRoot.isNotBlank()) {
                val dir = yamlPath(sharedConfigRoot)
                // 设置文档（dsh-settings-file）：path 优先于 dshHome
                appendLine("- id: settings")
                appendLine("  config:")
                appendLine("    path: '$dir/settings.yaml'")
                // 凭据（dsh-credentials-local）：同上
                appendLine("- id: credentials")
                appendLine("  config:")
                appendLine("    path: '$dir/.credentials.yaml'")
                // Agent 预设（dsh-agent-presets）：Config.default 必填，必须重述；
                // includeUserRoot:false 避免再叠加 $DSH_HOME/.agent-presets（那是按项目隔离的旧根）
                appendLine("- id: agent-presets")
                appendLine("  config:")
                appendLine("    default: $DEFAULT_AGENT_PRESET")
                appendLine("    includeUserRoot: false")
                appendLine("    roots:")
                appendLine("      - path: '$dir/.agent-presets'")
                appendLine("        trust: user")
                // 本地用户技能（dsh-skill-filesystem）：dshHome 决定 <dshHome>/skills 用户根
                appendLine("- id: skill-filesystem")
                appendLine("  config:")
                appendLine("    dshHome: '$dir'")
            }
        }
    }

    /** 生成带 failOnStartupError 的严格形态（测试/诊断用）：连接或工具同步失败即拒绝启动。 */
    fun generateStrict(mcpPort: Int, sharedConfigRoot: String = "", host: String = "127.0.0.1"): String {
        val base = generate(mcpPort, sharedConfigRoot, host)
        return base.replace(
            "        toolCallTimeoutMs: $TOOL_TIMEOUT_MS\n",
            "        toolCallTimeoutMs: $TOOL_TIMEOUT_MS\n        failOnStartupError: true\n"
        )
    }

    /**
     * 纯函数（可单测）：把本地绝对路径转成 YAML 单引号标量安全的正斜杠形式。
     * - 反斜杠 → 正斜杠（避免 Windows 路径被 YAML 当作转义）；
     * - 单引号 → 双写（YAML 单引号标量的唯一转义规则）。
     */
    internal fun yamlPath(path: String): String =
        path.trim().replace('\\', '/').replace("'", "''")
}
