# PRD §7 验收清单走查（Step 5 起，随版本持续跟踪——当前 v0.2.3）

执行日期：2026-08-19 ｜ 最近复核：2026-09-16（v0.2.3：自动化测试 130/130 全部通过）｜ 依据：docs/PRD.md §7 成功标准

| # | 验收项 | 状态 | 验证方式 | 备注 |
|---|---|---|---|---|
| 1 | 从磁盘安装构建出的 zip 到独立 IDEA 2024.1–2026.2 实例，无报错 | ⏳ 手工 | `gradle buildPlugin` → `deepseek-harness-idea-0.2.3.zip`：**瘦身默认 ≈1.83MB、不含运行时**（首启按平台下载并 SHA-256 校验，或经设置页「运行时目录」/「选择本地运行时 zip…」离线指定）；fat 备选 `-Pthin=false` ≈93–98MB | 本环境无法启动独立 IDE 会话；zip 结构已验证（plugin.jar + kotlin-stdlib + annotations）；2024.x 描述符问题已修（v0.2.2）；**v0.2.3 已上传 JetBrains Marketplace（update id 1171727），待审核** |
| 2 | 设置页填入 API Key 后工具窗口内可对话；智能体可读项目文件 | ⏳ 手工 | 需真实 API Key + IDE 会话 | 凭据链路已自动化（PasswordSafe → 全局凭据文件 → 各项目子目录）；脱敏回显 + Web UI 改 key 同步（方案B）。dsh 0.1.5 起启动 URL 带鉴权 token，鉴权/cookie 链路已有冒烟覆盖 |
| 3 | 指示智能体"新建 `src/Hello.java`"，文件出现在项目树并可打开 | ⏳ 手工 | 真实对话 | 底层已就绪：dsh cwd=项目根 + fs 工具 |
| 4 | 指示智能体修改某文件，审查面板出现 diff，还原后恢复 | ✅ 自动（逻辑层） | `SnapshotDiffTest`、`ReviewManager` 三类还原（MODIFIED 覆盖 / NEW 删除 / DELETED 重建） | UI 交互（DiffManager 面板）需 IDE 会话手工确认 |
| 5 | 选中代码右键发送，会话中出现提示，智能体可获取 | ✅ 自动（链路层） | `SentSelectionQueueTest` + `DshMcpBridgeSmokeTest`（`ide_get_sent_selection` 读回） | **JCEF 注入在 dsh 0.1.5 上降级为剪贴板**（composer 改为 Lexical，见「需人工确认」第 4 项） |
| 6 | 提问"当前打开的文件是什么"，智能体经 MCP 工具正确回答 | ✅ 自动 | `DshMcpBridgeSmokeTest`：`tools/list` 6 个 `ide_*` 工具 + `tools/call` 桥接返回（真实 dsh） | — |
| 7 | 关闭项目/IDE 后无残留 node 进程 | ✅ 自动（逻辑层） | `DshLifecycleManager`（ProjectManagerListener）+ `DshAppLifecycleListener` + `DshRuntimeRegistry.release` | 进程树终止：Windows `taskkill /T /F`，Unix `ProcessHandle` 后代遍历；IDE 会话级确认需手工 |
| 8 | 切换 IDE 语言（中/英）后插件文案跟随 | ✅ 自动（结构） | `DshBundle`（DynamicBundle）+ 中英双份资源 | — |
| 9 | 运行控制台选中日志右键"DSH 一键解释"，自动提交到 DSH 对话 | ⏳ 手工 | 真实 IDE 会话 + 运行中的 dsh | 链路已就绪（`SendLogExplanationAction` + `DshToolWindowPanel.sendQuestion` + JBCefJSQuery 回传；失败降级剪贴板）。**dsh 0.1.5 起 composer 为 Lexical，自动填写同样降级**，适配后复验 |
| 10 | 设置页「运行时下载地址」反显当前生效值 + 「默认」按钮；「运行时目录」可指定且「默认」按钮填入插件默认目录 | ✅ 自动（逻辑层） | `DshHomeManagerRuntimeRootTest`（优先级与路径等价 5 例）、`RuntimeAssetsTest`（含直连文件 URL 用例） | UI 交互需 IDE 会话确认 |

## 自动化测试覆盖合计

- **合计 130 项全部通过（0 失败 / 0 跳过）**；其中集成冒烟 4 项（真实 dsh 运行时）：
  `DshBootstrapSmokeTest`（启动 + 端口 + workspace 注册）、`DshMcpBridgeSmokeTest`（6 工具 + 严格 patch 启动）、
  `WorkspaceInitializerSmokeTest`（切换项目置顶）、`LegacySessionMigratorSmokeTest`（zstd session 迁移 + workspace 挂接）
- 版本演进：36（Step 0–5）→ 45（v0.1.1）→ 90（v0.1.3-dev）→ 113（v0.2.0）→ 122（v0.2.1）→ **130（v0.2.3）**
- v0.2.3 新增/扩充：`PortParserTest`（launch URL 与 `?token=`）、`RuntimeAssetsTest`（直连文件 URL 原样使用）、
  `DshHomeManagerRuntimeRootTest`（运行时目录优先级 + 路径等价 `samePath`）、`WorkspaceInitializerTest`（`workspace/create` + 读 `workspace.json` 定序 + `insertBefore`）

## 需人工 IDE 会话确认的项

1. 安装 zip 到独立 IDE 实例无报错（v0.2.3 瘦身默认：首启按平台下载运行时 + SHA-256 校验 + 解压自举；也可用「运行时目录」指向已解压目录）。
2. 真实 API Key 对话；智能体建/改文件（含 dsh 0.1.5 的鉴权 token/cookie 链路）。
3. 审查面板 DiffManager UI 交互与还原。
4. **「发送选中代码」与「日志一键解释」的 JCEF 注入**——dsh 0.1.5 的 composer 已由 `<textarea>` 改为
   Lexical contenteditable（`data-lexical-editor`），当前按 textarea 定位不到 → 走剪贴板降级；适配后需真实页面复验。
5. 关闭项目/退出 IDE 的进程清理（自动化覆盖逻辑层；Unix 走 `ProcessHandle` 后代遍历）。
6. 同窗口切换项目后，DSH 新建会话的工作区为当前项目根目录（v0.1.3-dev 起每项目独立 DSH_HOME）。
7. 升级迁移后旧会话可见且标题正确（`LegacySessionMigrator`，真实 dsh 已验证）。
8. dsh Web UI 改 API key 后其它项目下次启动全局一致（`DshCredentialsSync`）。
9. 设置页两项新交互：「运行时下载地址」反显与默认按钮；「运行时目录」默认按钮（填入插件默认目录，**等价于未设置**）。

## 结论（更新至 v0.2.3）

- 手工验收项（安装 / 真实对话 / diff UI / 注入效果 / 进程清理）仍需真实 IDE 会话闭环，已归档为
  [MILESTONE_REVIEW.md](./MILESTONE_REVIEW.md) 的遗留问题 A 类。
- 自动化（逻辑层）验收项 4/6/8、第 5 项链路，加上 v0.1.3-dev 起的迁移/全局 key 与 v0.2.x 的运行时供应/下载/鉴权适配，
  在 **130/130（v0.2.3，全部通过）** 测试中持续守护。
- 插件已发布 **v0.2.3**：GitHub Release（11 个资产：插件 zip + 5 平台运行时及 `.sha256`）+
  JetBrains Marketplace update **1171727**（待审核）。
