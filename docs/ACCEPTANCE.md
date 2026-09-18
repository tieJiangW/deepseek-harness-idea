# PRD §7 验收清单走查（Step 5 起，随版本持续跟踪——当前 v0.2.4）

执行日期：2026-08-19 ｜ 最近复核：2026-09-17（v0.2.4：自动化测试 194/194 全部通过）｜ 依据：docs/PRD.md §7 成功标准

| # | 验收项 | 状态 | 验证方式 | 备注 |
|---|---|---|---|---|
| 1 | 从磁盘安装构建出的 zip 到独立 IDEA 2024.1–2026.2 实例，无报错 | ⏳ 手工 | `gradle buildPlugin` → `deepseek-harness-idea-0.2.4.zip`：**瘦身默认、不含运行时**（首启按平台下载并 SHA-256 校验，或经设置页「运行时目录」/「选择本地运行时 zip…」离线指定）；fat 备选 `-Pthin=false` ≈93–98MB | 本环境无法启动独立 IDE 会话；zip 结构已验证（plugin.jar + kotlin-stdlib + annotations）；2024.x 描述符问题已修（v0.2.2）；v0.2.3 已上传 JetBrains Marketplace（update id 1171727） |
| 2 | 设置页填入 API Key 后工具窗口内可对话；智能体可读项目文件 | ⏳ 手工 | 需真实 API Key + IDE 会话 | 凭据链路已自动化（PasswordSafe → **共享**凭据文件，v0.2.4 起 dsh 与插件写同一份）；脱敏回显 + 合并式写入（保留 `records` 与其它 provider `refs`）。dsh 0.1.5 起启动 URL 带鉴权 token，鉴权/cookie 链路已有冒烟覆盖 |
| 3 | 指示智能体"新建 `src/Hello.java`"，文件出现在项目树并可打开 | ⏳ 手工 | 真实对话 | 底层已就绪：dsh cwd=项目根 + fs 工具 |
| 4 | 指示智能体修改某文件，审查面板出现 diff，还原后恢复 | ✅ 自动（逻辑层） | `SnapshotDiffTest`、`ReviewManager` 三类还原（MODIFIED 覆盖 / NEW 删除 / DELETED 重建） | UI 交互（DiffManager 面板）需 IDE 会话手工确认 |
| 5 | 选中代码右键发送，会话中出现提示，智能体可获取 | ✅ 自动（链路层）+ ✅ 真实页面实测 | `SentSelectionQueueTest` + `DshMcpBridgeSmokeTest`（`ide_get_sent_selection` 读回）+ `ComposerScriptsTest`（注入脚本契约） | **v0.2.4 已适配 Lexical composer**：headless Chromium + CDP 在真实 dsh 0.1.5 页面上实测 `execCommand('insertText')` 生效、`[data-lexical-text]` 回读命中；真实 JCEF 内仍建议手工复验一次 |
| 6 | 提问"当前打开的文件是什么"，智能体经 MCP 工具正确回答 | ✅ 自动 | `DshMcpBridgeSmokeTest`：`tools/list` 7 个 `ide_*` 工具 + `tools/call` 桥接返回（真实 dsh 0.1.5） | v0.2.4 起脚本部署在 dsh 树内（零链接），本项同时是该解析链的回归门 |
| 7 | 关闭项目/IDE 后无残留 node 进程 | ✅ 自动（逻辑层） | `DshLifecycleManager`（ProjectManagerListener）+ `DshAppLifecycleListener` + `DshRuntimeRegistry.release` | 进程树终止：Windows `taskkill /T /F`，Unix `ProcessHandle` 后代遍历；IDE 会话级确认需手工 |
| 8 | 切换 IDE 语言（中/英）后插件文案跟随 | ✅ 自动（结构） | `DshBundle`（DynamicBundle）+ 中英双份资源 | — |
| 9 | 运行控制台选中日志右键"DSH 一键解释"，自动提交到 DSH 对话 | ⏳ 手工 | 真实 IDE 会话 + 运行中的 dsh | 链路已就绪（`SendLogExplanationAction` + `DshToolWindowPanel.sendQuestion` + JBCefJSQuery 回传；失败降级剪贴板）。**dsh 0.1.5 起 composer 为 Lexical，自动填写同样降级**，适配后复验 |
| 10 | 设置页「运行时下载地址」反显当前生效值 + 「默认」按钮；「运行时目录」可指定且「默认」按钮填入插件默认目录 | ✅ 自动（逻辑层） | `DshHomeManagerRuntimeRootTest`（优先级与路径等价、共享根解析）、`RuntimeAssetsTest`（含直连文件 URL 用例） | UI 交互需 IDE 会话确认 |
| 11 | **在一个项目新增自定义模型/provider 后重启 IDEA，配置仍在；其它项目同样可见** | ✅ 自动（迁移与 patch 层）+ ⏳ 手工 | `McpPatchGeneratorTest`（四段 patch 指向共享根）、`SharedConfigMigratorTest`（按 namespace 合并与备份）、`DshMcpBridgeSmokeTest`（真实 dsh 不写项目级配置文件） | **v0.2.4 核心修复**；端到端"Web UI 加模型 → 重启 → 仍在"仍需真实 IDE 会话确认（见「需人工确认」第 10 项） |
| 12 | Agent 预设与个人技能跨项目共享 | ✅ 自动（patch 层）+ ⏳ 手工 | `McpPatchGeneratorTest`（`agent-presets` 重述 `default` + `includeUserRoot: false`、`skill-filesystem.dshHome`） | 需真实会话确认创建/删除预设可用（`copy()`/`remove()` 只认第一个 user 根） |

## 自动化测试覆盖合计

- **合计 194 项全部通过（0 失败 / 0 跳过）**；其中集成冒烟 4 项（真实 dsh 0.1.5-rc.2 运行时）：
  `DshBootstrapSmokeTest`（启动 + 端口 + workspace 注册）、`DshMcpBridgeSmokeTest`（7 工具 + 严格 patch 启动 + 共享配置落盘）、
  `WorkspaceInitializerSmokeTest`（切换项目置顶）、`LegacySessionMigratorSmokeTest`（zstd session 迁移 + workspace 挂接）
- 版本演进：36（Step 0–5）→ 45（v0.1.1）→ 90（v0.1.3-dev）→ 113（v0.2.0）→ 122（v0.2.1）→ 130（v0.2.3）→ **194（v0.2.4）**
- v0.2.4 新增/扩充：`SharedConfigMigratorTest`（23 例：`YamlText` 顶层键/段落切分、按 namespace 合并、扁平凭据升级、
  `refs`/`records` 补缺、端到端迁移与备份、幂等标记、种子选择、失败隔离、`upsertRef`/`hasRef`）、
  `ComposerScriptsTest`（11 例：注入脚本契约——Lexical 选择器、`insertText` 写入、`[data-lexical-text]` + 轮询回读、
  Enter 自动提交、发送按钮选择器不误点"停止"、无回传通道形态）、
  `SendSelectionRefsTest`（12 例：紧凑引用构造 + 同一引用短时间窗判重——含窗口边界、时间回拨、空引用）、
  `McpPatchGeneratorTest`（重写为 11 例，含"绝不出现 `$settings` 形态"的回归断言）、
  `DshHomeManagerRuntimeRootTest`（新增 `resolveSharedConfigRoot` 与项目目录名模式）、
  `DshCredentialsSyncTest`（新增"`register` 为空操作""不再写共享凭据"）、
  `DshMcpBridgeSmokeTest`（新增 ESM 解析探针 + 项目 DSH_HOME 无 `node_modules`/配置文件断言）

## 需人工 IDE 会话确认的项

1. 安装 zip 到独立 IDE 实例无报错（v0.2.4 瘦身默认：首启按平台下载运行时 + SHA-256 校验 + 解压自举；也可用「运行时目录」指向已解压目录）。
2. 真实 API Key 对话；智能体建/改文件（含 dsh 0.1.5 的鉴权 token/cookie 链路）。
3. 审查面板 DiffManager UI 交互与还原。
4. **「发送选中代码」与「日志一键解释」的 JCEF 注入**——v0.2.4 已按 Lexical contenteditable 适配并在
   **真实 dsh 0.1.5 页面（headless Chromium + CDP）实测通过**（写入生效、回读命中、Enter 501ms 内提交）；
   仍建议在真实 JCEF 工具窗口里复验一次（JCEF 的 Chromium 版本与 Edge 可能略有差异）。
   若出现 `notfound` 提示，通常是**尚未进入会话**（composer 只在选中工作区后渲染）或页面仍在加载。
5. 关闭项目/退出 IDE 的进程清理（自动化覆盖逻辑层；Unix 走 `ProcessHandle` 后代遍历）。
6. 同窗口切换项目后，DSH 新建会话的工作区为当前项目根目录（v0.1.3-dev 起每项目独立 DSH_HOME）。
7. 升级迁移后旧会话可见且标题正确（`LegacySessionMigrator`，真实 dsh 已验证）。
8. ~~dsh Web UI 改 API key 后其它项目下次启动全局一致（`DshCredentialsSync`）~~ → **v0.2.4 起不再需要**：
   dsh 与插件读写同一份共享凭据文件，改动即时互相可见。
9. 设置页两项新交互：「运行时下载地址」反显与默认按钮；「运行时目录」默认按钮（填入插件默认目录，**等价于未设置**）。
10. **v0.2.4 配置共享化端到端**（最高优先）：① 项目 A 的 Web UI「Settings → Models」新增自定义 provider/模型 →
    关闭 IDEA → 重开 A，模型仍在；② 打开项目 B，同一模型可见；③ 切换语言（General → Language）后重开仍保持，B 中一致；
    ④ 创建一个自定义 Agent 预设，A/B 均可见可选中，且能在 Web UI 中删除；⑤ 在 `<项目>/.dsh/skills`（项目级）与
    `<共享根>/skills`（全局）各放一个技能，A/B 呈现差异符合预期；⑥ 检查项目 DSH_HOME 与共享根下**没有任何 junction**
    （除 dsh 自愈的 `profiles/node_modules`），且 `mcp__ide__*` 工具可调用。
11. 首次升级到 v0.2.4 时的迁移体验：日志应出现 `shared-config migration: …`，`<共享根>/migrated/<hash>/` 下能看到被移走的
    旧项目级 `settings.yaml` / `.credentials.yaml`；若迁移失败则不写 `.plugin-layout-version` 并在下次启动重试。

## 结论（更新至 v0.2.4）

- 手工验收项（安装 / 真实对话 / diff UI / 注入效果 / 进程清理 / **配置共享化端到端**）仍需真实 IDE 会话闭环，
  已归档为 [MILESTONE_REVIEW.md](./MILESTONE_REVIEW.md) 的遗留问题 A 类。
- 自动化（逻辑层）验收项 4/6/8、第 5 项链路（含真实页面实测）、第 11/12 项的 patch 与迁移层，加上 v0.1.3-dev 起的迁移/全局 key 与
  v0.2.x 的运行时供应/下载/鉴权/配置共享/Lexical 注入适配，在 **194/194（v0.2.4，全部通过）** 测试中持续守护。
- 插件 v0.2.3 已发布：GitHub Release（11 个资产：插件 zip + 5 平台运行时及 `.sha256`）+
  JetBrains Marketplace update **1171727**（待审核）。v0.2.4 待发布。
