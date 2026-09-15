# PRD §7 验收清单走查（Step 5 起，随版本持续跟踪——当前 v0.2.1）

执行日期：2026-08-19 ｜ 最近复核：2026-09-02（v0.2.1：自动化测试 122/122 全部通过）｜ 依据：docs/PRD.md §7 成功标准

| # | 验收项 | 状态 | 验证方式 | 备注 |
|---|---|---|---|---|
| 1 | 从磁盘安装构建出的 zip 到独立 IDEA 2024.1–2026.2（Windows）实例，无报错 | ⏳ 手工 | `gradle buildPlugin` → `deepseek-harness-idea-0.2.1.zip`：**瘦身默认 ≈1.8MB、不含运行时**（首次使用经 `RuntimeProvisioner` 按平台下载 ~93MB 运行时并 SHA-256 校验）；fat 备选 `-Pthin=false` ≈93–98MB（含运行时，无需下载） | 本环境无法启动独立 IDE 会话；zip 结构已验证（plugin.jar + kotlin-stdlib + annotations）；2026.2 安装兼容已修复（v0.1.1，until-build 262.*）；**v0.2.1 已上传 JetBrains Marketplace（update id 1159301），待审核** |
| 2 | 设置页填入 DeepSeek API Key 后工具窗口内可对话；智能体可读项目文件 | ⏳ 手工 | 需真实 API Key + IDE 会话 | 凭据链路已自动化：设置页写 PasswordSafe → 插件全局凭据文件 → `copyGlobalConfigTo` 到各子目录（v0.1.3-dev）；API Key 脱敏回显 + Web UI 改 key 经 `DshCredentialsSync` 同步全局（方案B） |
| 3 | 指示智能体"新建 `src/Hello.java`"，文件出现在项目树并可打开 | ⏳ 手工 | 真实对话 | 底层已就绪：dsh cwd=项目根（Step 2）+ fs 工具 |
| 4 | 指示智能体修改某文件，审查面板出现 diff，还原后恢复 | ✅ 自动（逻辑层） | `SnapshotDiffTest`（5/5）、`ReviewManager` 三类还原（MODIFIED 覆盖/NEW 删除/DELETED 重建） | UI 交互（DiffManager 面板）需 IDE 会话手工确认 |
| 5 | 选中代码右键发送，会话中出现提示，智能体可获取 | ✅ 自动（链路层） | `SentSelectionQueueTest`（5/5）+ `DshMcpBridgeSmokeTest`（`ide_get_sent_selection` 读回） | JCEF 注入部分（Step 4）需 IDE 会话确认；剪贴板降级已实现 |
| 6 | 提问"当前打开的文件是什么"，智能体经 MCP 工具正确回答 | ✅ 自动 | `DshMcpBridgeSmokeTest`：`tools/list` 6 个 `ide_*` 工具 + `tools/call` 桥接返回（22s，真实 dsh） | — |
| 7 | 关闭项目/IDE 后无残留 node 进程 | ✅ 自动（逻辑层） | `DshLifecycleManager`（ProjectManagerListener）+ `DshAppLifecycleListener`（AppLifecycleListener.appClosing 终止全部面板）；`DshRuntimeRegistry.release` | 进程树终止实测：`killTree` 用 `taskkill /T /F`（Step 2）；IDE 会话级确认需手工 |
| 8 | 切换 IDE 语言（中/英）后插件文案跟随 | ✅ 自动（结构） | `DshBundle`（DynamicBundle）+ `DshBundle.properties` + `DshBundle_zh_CN.properties` 双份齐全（Step 1 起） | — |
| 9 | 运行控制台选中日志右键"DSH 一键解释"，自动提交到 DSH 对话 | ⏳ 手工 | 真实 IDE 会话 + 运行中的 dsh（功能自 v0.1.3-dev 引入；当前插件 v0.2.1） | 链路已就绪：`SendLogExplanationAction`（ConsoleView.PopupMenu）+ `DshToolWindowPanel.sendQuestion`（JCEF 填 composer + 派发回车 + JBCefJSQuery 结果回传）；降级路径（未运行/失败→剪贴板、阻塞→留在输入框）均有通知；JCEF 注入效果需 IDE 会话确认 |

## 自动化测试覆盖合计

- 单元（下列清单为 v0.1.3-dev 快照，合计 86）：SentSelectionQueue 5、McpPatchGenerator 6、SnapshotDiff 5、DshRuntimeRegistry 3、PortParser 4、CredentialImporter 4、PathFilters 5、JsonCodec 9、**ExplainLogComposer 4**、**WorkspaceInitializer 12**、**LegacySessionMigrator 14**、**DshCredentialsMask 10**、**DshCredentialsSync 6**；v0.2.0 起新增 **PlatformTest / RuntimeAssetsTest / RuntimeProvisionerTest**（平台/资产/供应），v0.2.1 继续扩展下载相关用例（逐类最新数未复列）
- 集成冒烟（真实 dsh 运行时）：DshBootstrapSmokeTest 1、DshMcpBridgeSmokeTest 1、**WorkspaceInitializerSmokeTest 1**、**LegacySessionMigratorSmokeTest 1** = **4**
- **合计：90（v0.1.3-dev）→ 113（v0.2.0）→ 122（v0.2.1）；v0.2.1 构建时 122/122 全部通过、0 失败**

## 需人工 IDE 会话确认的项

1. 安装 zip 到独立 IDE 实例无报错（当前 v0.2.1 瘦身默认：首次使用按平台下载运行时 + SHA-256 校验 + 解压自举；fat 包则为 zip 内解压）。
2. 真实 API Key 对话；智能体建/改文件。
3. 审查面板 DiffManager UI 交互与还原。
4. 发送选中代码的 JCEF 注入效果（注入失败剪贴板降级已就绪）。
5. 关闭项目/退出 IDE 的进程清理（自动化覆盖逻辑层）。
6. 运行控制台"DSH 一键解释"的自动提交效果（v0.1.3-dev，FR-11）。
7. 同窗口切换项目后，DSH 新建会话工作区自动为当前项目根目录（v0.1.3-dev 根治：每项目独立 DSH_HOME，机制上隔离；UI 视觉确认需 IDE 会话）。
8. 升级迁移后旧会话可见且标题正确（v0.1.3-dev：LegacySessionMigrator 迁移 session + 投影缓存，真实 dsh 已验证；视觉确认需 IDE 会话）。
9. dsh Web UI 改 API key 后其它项目下次启动全局一致（v0.1.3-dev：DshCredentialsSync，真实 dsh RPC 验证改 key 落子项目文件、去 env 后 read 文件层；项目间一致性需 IDE 会话）。

## Step 6 结论

以上 5 项已作为**遗留问题 A-1～A-5** 归档至 [MILESTONE_REVIEW.md](./MILESTONE_REVIEW.md)（验收闭环 backlog）；
自动化（逻辑层）验收项 4/6/8 及第 5 项链路及后续 v0.1.3-dev 新增迁移/全局 key、v0.2.x 运行时供应/下载等在 **122/122（v0.2.1，全部通过）** 测试中持续守护。
插件版本已发至 **v0.2.1**（自 v0.2.0 起版本号与文档对齐）；JetBrains Marketplace 上传完成（update id 1159301），待审核。
