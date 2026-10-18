package com.deepseek.harness.idea.mcp

/**
 * 生成 `ide.yml` patch 内容（Step 3；v0.2.4 起同时承载"配置共享化"，v0.2.6 起按 dsh 0.2.1 契约重排）。
 *
 * **语法（dsh 0.2.1-rc.2 实测）**：`--patch` 是叠加在 bundle 层之上的覆盖层，顶层是
 * `PatchOptions` 数组，两种形态：
 * - `insert:` 列表 → 新增条目（新增 mcp-client 必须显式写 `name`）；
 * - `- id: <rowId>` → **整份替换**该条目的 `config`（未改字段必须重述，不做深合并）；
 *   同级还可以写 `disabled:` 等其他字段（未被 patch 的字段保持原值）。
 *   目标 id 不存在时 dsh 只 warn 并跳过（实测 `patch: entry "agent-presets" not found`，非致命）。
 *
 * ⚠️ 历史坑（v0.1.3-dev ~ v0.2.3）：曾用 `- $settings:` / `- $credentials:` 指向全局配置，
 * 该语法**不被 dsh 接受**——`dsh --profile web --patch <ide.yml> --dump-config` 报
 * `patch: id is required for non-insert patches`，整条 patch 被丢弃。正确写法是 `- id: <rowId>`。
 *
 * ⚠️ **dsh 0.2.1 契约变更（v0.2.6 实测，三处都会"静默失效"而非报错）**：
 * 1. `settings` 行改为 `@deepseek-ai/dsh-settings`（抽象 seam，**没有 Config schema**），
 *    0.1.5 的 `dsh-settings-file`（`Config{path,dshHome}`）已移除；`settings.yaml` 也被废弃，
 *    配置真源改为 **profile 的 `cordis.patch.yml`**（`dsh-config-editor.documentPath`）。
 *    因此指向共享 `settings.yaml` 的 `path` 会被静默忽略 → 本类**不再生成** `- id: settings`；
 *    配置共享改由"共享 profile 目录"承担（见 [com.deepseek.harness.idea.runtime.DshHomeManager.sharedProfilesRoot]），
 *    旧 `settings.yaml` 由 `DshHomeManager` 一次性搬成 dsh 的待导入文档。
 * 2. `agent-presets` 行**已不存在**（改为 `agent-preset-registry`，`Config` 只有
 *    `default`/`selectedDefault`，没有 `roots`/`includeUserRoot`）→ 不再生成该段
 *    （0.2.1 的 Agent 预设是 profile 里的声明式行，随共享 profile 一起共享）。
 * 3. `skill-filesystem` 行仍存在，但 web 层把它 `disabled: true`（预设接管技能发现）。
 *    只替换 `config` 不会解除禁用 → 必须**同时**写 `disabled: false`，
 *    才能让它作为"部署级技能提供者"注册进全局技能层（项目级 `<项目>/.dsh/skills` 不受影响）。
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
 * - id: credentials
 *   config:
 *     path: '<shared>/.credentials.yaml'
 * - id: skill-filesystem
 *   disabled: false
 *   config:
 *     dshHome: '<shared>'
 * ```
 */
object McpPatchGenerator {

    const val SERVER_NAME = "ide"
    private const val PLUGIN = "@deepseek-ai/dsh-mcp-client"
    private const val TOOL_TIMEOUT_MS = 60000
    private const val RECONNECT_MAX_ATTEMPTS = 3

    /**
     * 生成 patch 文本。
     *
     * [sharedConfigRoot] 是插件的共享配置根（`DshHomeManager.sharedConfigRoot()`），非空时追加两段
     * 按 id 覆盖的 patch，把 dsh **仍然可重定向**的用户级配置面指向共享目录：
     *
     * | 行 id | 作用 | 依据（dsh 0.2.1） |
     * |---|---|---|
     * | `credentials` | 凭据 → `<共享根>/.credentials.yaml` | `dsh-credentials-local` `Config{path,dshHome}`，`path` 优先 |
     * | `skill-filesystem` | 用户技能根 → `<共享根>/skills` | `Config.dshHome`；**必须**配 `disabled: false` 才能抵消 web 层的禁用 |
     *
     * 设置（模型/语言/onboarding）与 Agent 预设**不再经 patch 重定向**：0.2.1 把两者写进
     * profile 层（`<共享根>/profiles/web/cordis.patch.yml`），由 v0.2.5 起就存在的
     * `profiles` 共享链接天然跨项目共享。
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
                // 凭据（dsh-credentials-local）：0.2.1 仍接受 path，文档布局仍是 version:1 + refs/records
                appendLine("- id: credentials")
                appendLine("  config:")
                appendLine("    path: '$dir/.credentials.yaml'")
                // 本地用户技能（dsh-skill-filesystem）：dshHome 决定 <dshHome>/skills 用户根。
                // 0.2.1 的 web 层把该行 disabled: true（交给 agent preset 接管），只换 config 不生效，
                // 必须显式 disabled: false 才能恢复"部署级技能提供者"的全局注册。
                appendLine("- id: skill-filesystem")
                appendLine("  disabled: false")
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
