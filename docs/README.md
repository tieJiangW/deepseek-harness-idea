 DeepSeek Harness IDEA 插件 — 项目文档

本仓库开发一个 IntelliJ IDEA 插件（类 Qoder）：在 IDE 内嵌入 DeepSeek Harness（DSH）Web UI，让智能体能读写当前项目文件、通过 MCP 调用 IDE 能力，并提供代码上下文发送与 diff 审查/还原等原生集成。

## 文档索引

| 文档 | 说明 | 状态 |
|---|---|---|
| [PRD.md](./PRD.md) | 规划需求文档：目标、用户故事、功能/非功能需求、验收标准、风险 | 草稿（随实现迭代更新） |
| [DESIGN.md](./DESIGN.md) | 详设文档：架构、模块设计、接口契约、数据流、测试策略 | 草稿（随实现迭代更新） |
| [ACCEPTANCE.md](./ACCEPTANCE.md) | PRD §7 验收清单走查（Step 5 执行，自动化 vs 手工项） | 维护中（v0.2.1：122 项测试全部通过；手工项待真实 IDE 会话） |
| [MILESTONE_REVIEW.md](./MILESTONE_REVIEW.md) | 里程碑评审（Step 6）：Step 0–5 总结、需求覆盖矩阵、遗留问题、风险回顾、后续规划 | ✅ 完成（2026-08-20 评审时点快照，最新状态见上方文档） |
| [PROJECT_NOTES.md](./PROJECT_NOTES.md) | 项目知识库：本机构建环境、dsh 行为实测、踩坑记录、2024.1 API 勘误、后续任务参考 | 维护中（v0.2.1，122 项测试全部通过） |

## 文档约定

- 文档使用中文编写，代码与技术术语保留英文。
- PRD 与 DESIGN 为**活文档**：实现过程中如有决策变化，先更新文档再改代码。
- 每个实现步骤结束后，在下方变更记录追加一条，并同步更新对应章节。

## 变更记录

| 日期 | 版本 | 变更内容 |
|---|---|---|
| 2026-02-11 | v0.1 | 初稿：基于已确认的产品与技术决策生成 PRD 与 DESIGN（Step 0） |
| 2026-08-19 | v0.2 | Step 2 运行时自举实现：DshHomeManager/DshProcessManager/PortParser、JCEF 工具窗口、凭据同步、build-runtime.ps1 + buildRuntime 任务、冒烟测试（含真实 dsh web 启动） |
| 2026-08-19 | v0.3 | Step 3 MCP 桥接实现：IdeBridgeServer、mcp-ide-server.mjs（6 个 ide_* 工具）、McpPatchGenerator、DshBridgeManager、DSH_HOME 顶层 node_modules junction、MCP 冒烟测试；spike 实测 patch `insert:` 语法与 failOnStartupError 严格验证 |
| 2026-08-19 | v0.4 | Step 4 IDE 集成实现：SendSelectionAction（读选中 → sent-selection 队列 → JCEF 注入预填，失败降级剪贴板）、SnapshotManager/SnapshotDiff/ReviewManager/ReviewChangesAction（基线快照 + diff + 还原/忽略/重新基线）、PathFilters；新增 PathFiltersTest/SnapshotDiffTest/SentSelectionQueueTest |
| 2026-08-19 | v0.5 | Step 5 加固与发布：DshLifecycleManager/DshAppLifecycleListener（项目关闭 + IDE 退出终止进程树）、DshRuntimeRegistry（并发上限 3）、崩溃通知、日志页（DshLogPanel）、设置页 logLevel、运行时打包（runtime-bundle.zip 打入插件 + 首次解压自举）、DshRuntimeRegistryTest；PRD §7 验收走查 |
| 2026-08-20 | v0.5.1 | 手工测试修复：①工具窗口默认选中主界面（日志 tab 不再抢焦点）；②默认工作区预注册（WorkspaceInitializer 调 dsh RPC workspace.create，UI 打开即选中当前项目；真实启动冒烟断言） |
| 2026-08-20 | v0.5.2 | 发送选中代码增强为结构化引用文本（`文件路径`(行 a-b) + 代码块 + 提示语）；实测确认 dsh 输入框无原生文件引用 chip，采用结构化文本方案 |
| 2026-08-20 | v0.5.3 | 紧凑文件引用：输入框改为 `@路径#L起始-结束` + 提示语（不填充代码本体，避免内容过多）；完整代码仍入 sent-selection 队列 |
| 2026-08-20 | v0.5.4 | 输入框仅显示 `@路径#L起始-结束` + 换行（去掉提示语），光标自动落到下一行等待输入 |
| 2026-08-20 | v0.5.5 | 工具窗口标题动作区分图标：Settings(齿轮)/Open in browser(Web)/Review changes(Diff)/Restart(Restart) |
| 2026-08-20 | v0.5.6 | 右键"Send Selection to DSH"动作图标与插件一致（plugin.xml icon 指向 dsh-toolwindow.svg） |
| 2026-08-20 | v0.5.7 | 插件描述（Overview）与更新说明（What's New）中英双语完善（plugin.xml description/change-notes） |
| 2026-08-20 | v0.5.8 | 一键打包脚本 scripts/build-plugin.bat（自动探测 JBR/Gradle 缓存，--no-daemon）；新增 docs/PROJECT_NOTES.md 项目知识库（环境/踩坑/API 勘误/dsh 行为） |
| 2026-08-20 | v0.5.9 | Step 6 里程碑评审：新增 docs/MILESTONE_REVIEW.md（Step 0–5 总结、FR/US/非目标覆盖矩阵、遗留问题 A–D 分级、PRD §9 风险回顾、v0.6/v0.7/v1.x 规划）；同步修正 DESIGN.md（§3.1 intellij 1.17.4、补 v0.5.2–0.5.8 变更记录）与 ACCEPTANCE.md（测试合计 32→36）；测试 36/36 复跑通过 |
| 2026-08-20 | v1.0.0 开源 | 项目开源发布至 GitHub（MIT）：仓库 tieJiangW/deepseek-harness-idea（main + v0.1.0 tag）；新增根 README.md（中英）、LICENSE、.gitattributes；首次提交 47 文件；Release v0.1.0 含插件 zip 附件（98MB，含内嵌运行时） |
| 2026-08-20 | v0.1.1 | 兼容修复：`until-build` 251.* → 262.*（用户 IDEA 2026.2/build 262 安装报错，前向编译验证通过）；Gson → 自研 `JsonCodec`（移除平台 Gson 依赖）；新增 JsonCodecTest 9 例；测试 36→45 |
| 2026-08-21 | v0.1.2 | 2026.2 JCEF 兼容修复：plugin.xml 新增（可选）依赖 `com.intellij.modules.jcef`（2026.2 起 JCEF 拆分内置插件，使用 JBCefBrowser 须声明依赖否则运行时 NoClassDefFoundError；别名自 2025.3.1 引入，optional 保证 241–252 兼容）；JCEF 失败提示附带异常 + 排查建议；PROJECT_NOTES 新增"2026.2 JCEF 拆分" |
| 2026-08-22 | v0.1.3-dev | 运行控制台"DSH 一键解释"（FR-11）：`SendLogExplanationAction` 注册于 `ConsoleView.PopupMenu`（Run 控制台右键组，组 id 2024.1/2026.2 源码核实）；点击后不等待确认，JCEF 自动填 composer + 派发回车提交"解释指令 + 选中日志"；JBCefJSQuery 回传结果（submitted/blocked），失败降级剪贴板；新增 `ExplainLogComposer` 纯函数 + 4 例单测 |
| 2026-08-22 | v0.1.3-dev | 切换项目工作区修复：`WorkspaceInitializer.ensureWorkspace` 在 create 后追加 workspace.list + workspace.insertBefore 把当前项目挪到显示顺序最前（create 幂等不改变顺序、UI 默认落点=列表第一个，用户实测同窗口切换项目后新会话仍绑旧项目根目录）；新增 WorkspaceInitializerTest 链路 8 例 + WorkspaceInitializerSmokeTest（真实 dsh 切换场景） |
| 2026-08-22 | v0.1.3-dev | 切换项目工作区根治（用户确认方案）：**每个项目独立 DSH_HOME**（`DshHomeManager.homeDir(projectPath)` = `MD5(projectPath)` 前 16 位目录），dsh 工作区注册表/会话数据按项目隔离——切到任何项目工作区都从当前项目白纸开始，从机制上杜绝"显示其他项目工作区"；`syncCredentials(projectPath)` 项目启动写凭据、设置页 `syncCredentialsAll()` 同步所有打开项目 |
| 2026-08-23 | v0.1.3-dev | dsh 运行时升级 **0.1.0-rc.7 → 0.1.1-rc.2**（用户要求）：改 `DshHomeManager.DSH_VERSION` + `build-runtime.ps1` 默认 DshVersion，重建 runtime-dev 与 build/runtime bundle；真实 dsh 冒烟全过（启动/端口/工作区 RPC/MCP 6 工具），行为兼容 |
| 2026-08-23 | v0.1.3-dev | dsh 0.1.1-rc.2 回归修复（用户截图）：① API Key 不生效 → `DshProcessManager` 透传 `DEEPSEEK_API_KEY` 环境变量（dsh 优先读继承环境）；② 每次新项目弹"内测声明" → `ensureHome` 预写 `settings.yaml` 的 `ui-onboarding.welcomeNoticeVersion` + JCEF 自动点 Continue 兜底 |
| 2026-08-23 | v0.1.3-dev | dsh "Add an API key" onboarding 捕获（用户要求）：JCEF 检测弹窗，用户输入 Key 点 Save 时经 JBCefJSQuery 回传 → 写插件 PasswordSafe，设置页脱敏显示 + 下次透传；注入改为注册 CefLoadHandler（onLoadEnd 主 frame 加载完成后注入，可靠；onUrlReady 时页面未加载 + 移除冗余的 resetSessionPersistence/reload） |
| 2026-08-23 | v0.1.3-dev | **方案 C：dsh 配置真·同一文件全局共享**（POC 验证后实现）：`DshHomeManager.globalConfigHome()` 全局配置根（凭据/设置文件唯一真源），每项目子目录 DSH_HOME 只存数据（storages/sessions）；`McpPatchGenerator` 用 cordis patch `$settings`/`$credentials`（修改已有单元；`- insert` 会 duplicate、`$id` 才对，POC 确证）把 `settings-file.path`/`credentials-local.path` 指向全局——配置共享 + 数据按项目隔离 + 无多窗口冲突；`syncCredentials()` 写全局、`ensureHome` 预写全局 settings.yaml |
| 2026-08-23 | v0.1.3-dev | **回退方案 A**（用户实机判定 dsh 未读全局 path 后定）：`McpPatchGenerator` 恢复只生成 `mcp.ide` insert（无 `$settings`）；`DshHomeManager.ensureHome` 把全局唯一配置复制到每项目子目录（`copyGlobalConfigTo`）；`start()` 先 `syncCredentials()` 再 `ensureHome`——配置全局管理、子目录为同步副本（dsh 读子目录；dsh 内改动下次启动被全局覆盖）；API key 以环境变量透传为主 |
| 2026-08-23 | v0.1.3-dev | **升级迁移：旧全局 session → 每项目隔离目录**（用户要求，解决"升级后旧 session 找不到"）：新增 `LegacySessionMigrator`——复刻 dsh `projectKey(cwd)` 编码定位旧全局 `sessions/<projectKey(cwd)>/`，把各 session 目录**原样复制**（保留 `.jsonl.zstd` 压缩格式，逐目录合并/已存在跳过，幂等）到隔离目录；`ensureHome` 调 `migrateLegacySessions`。workspace 注册表由 dsh 启动时 bootstrap 从 session header 自动重建（无需手工迁移）。新增 `LegacySessionMigratorTest` + `LegacySessionMigratorSmokeTest`（真实 dsh 验证） |
| 2026-08-23 | v0.1.3-dev | **投影缓存迁移（`session_projcache.json`）**（用户实测修复"历史会话标题全显示成项目名"）：dsh 的 `session.list` 用零 I/O 投影缓存读每行会话标题，缓存缺记录时 `session.title=undefined` → UI 回退显示 `basename(cwd)`（项目目录名）。`LegacySessionMigrator.migrateProjectionCache` 从全局缓存筛选当前项目（identity.cwd 规范化匹配）的会话条目合并写入隔离目录；`ensureHome` 一并调用。真实迁移后 `session.list` 立即返回正确标题（你是谁？/解释Spring AI MCP配置文件/SSE 405错误分析/...）。LegacySessionMigratorTest 增至 14 例 |
| 2026-08-23 | v0.1.3-dev | **设置页 API Key 脱敏回显**（用户要求"前 6 位 + 中间脱敏 + 后 6 位"）：`DshCredentials.maskApiKey(key)` 前 6 位 + `******` + 后 6 位（≤12 位整段脱敏）；`DshSettingsConfigurable` 回显脱敏串（改用 `JBTextField`，否则 `JBPasswordField` 渲染成掩码点看不到），`isModified`/`apply` 以"字段内容 ≠ 脱敏串"判定是否真改了 key，避免把脱敏串写回密码库。新增 DshCredentialsMaskTest 6 例 |
| 2026-08-23 | v0.1.3-dev | **设置页回显兜底：凭据文件读取**（用户实测"改后仍为空"）：PasswordSafe 读不到 key 时回显为空。`DshCredentials.readApiKeyFromCredentialFile`（行级解析）+ `readApiKeyWithFallback`（先 PasswordSafe，无则回退插件全局凭据文件）；设置页 `readStoredApiKey()` 用它。DshCredentialsMaskTest 增至 10 例 |
| 2026-08-23 | v0.1.3-dev | **dsh Web UI 改 API key 全局生效**（用户要求+选B）：去掉 `DshProcessManager` 注入的 `DEEPSEEK_API_KEY` 环境变量（dsh-credentials-local `inherited env wins` 遮蔽 Web UI 写入，且 `assertUnshadowed` 拒改）；新增 `DshCredentialsSync`（WatchService 监听各项目凭据文件，dsh Web UI 写 `version:1 + refs.DEEPSEEK_API_KEY` → 捕获 → 回写 PasswordSafe + 插件全局凭据文件）。方案B：当前 dsh 进程立即生效，其它项目下次启动/重启一致。新增 DshCredentialsSyncTest 6 例 |
| 2026-09-02 | v0.2.1 | 首次使用运行时下载修复 + 下载可靠性/UX（v0.2.0+ 运行时供给模型：thin 构建不再捆绑 ~93MB 运行时，按平台从 GitHub Releases 下载 `runtime-<os>-<arch>.zip` + `.sha256`，SHA-256 校验后解压到 `<config>/dsh-idea/runtime/<DSH_VERSION>/` 复用；`DSH_IDEA_RUNTIME` 环境变量或设置页运行时目录字段可指向已解压运行时跳过下载；fat 构建 `-Pthin=false` 捆绑运行时、无需下载）：①修复全新安装首启下载失败——临时下载文件父目录不存在抛 NoSuchFileException（"download failed"/0 进度），下载前先创建目录再写入；②下载更可靠——基于 java.net.http.HttpClient 的连接池 + HTTP/2、浏览器 User-Agent、60s 连接超时、可配置读超时与退避重试（慢/不稳定网络如大陆访问 GitHub 首启下载也能成功）；③工具窗口内下载进度条 + 取消（connecting/verifying/downloading 状态、bytes/MB/s 速度）；④设置页回显当前平台运行时下载 URL（精确到文件）+ 一键复制、可配置下载超时、"Choose local runtime zip…" 导入已下载的运行时 zip（校验 + 对照 sidecar SHA-256）实现完全离线启动；⑤错误卡片显示失败 URL + 底层原因 + "Restart harness"。测试 122 项全部通过 |
| 2026-09-15 | v0.2.3 | dsh 运行时升级 0.1.1-rc.2 → **0.1.5-rc.2**（用户要求）：改 `DshHomeManager.DSH_VERSION` + `build.gradle.kts` dshVersion + `build-runtime.mjs`/`build-runtime.ps1` 默认值；重建 win-x64 运行时（Node 22.23.2 + dsh 0.1.5-rc.2，103.8MB zip + SHA-256 侧车）。**0.1.5 契约变更与适配**：① 启动 URL 新增浏览器鉴权 token（`dsh web: http://127.0.0.1:<port>/?token=<t>`）——`GET /` 无 token 返回 401，`GET /?token=` 返回 303 + `Set-Cookie: dsh-auth-…`，此后所有 `/api` 请求必须携带该 cookie（`Authorization: Bearer` 与 query token 均不被接受）；修复：`PortParser.parseUrl` 解析完整 URL、`DshProcessManager` 保存 `launchUrl`（JCEF/onUrlReady 用带 token URL；健康检查禁跟随重定向、303/401 均视为已就绪）、`WorkspaceInitializer` 先换 cookie；② RPC 契约三变：命名空间点号→斜杠、信封 `payload.args`、参数再包 `request`（`{"payload":{"args":{"request":{…}}}}`）；③ `workspace/list` RPC 移除 → 置顶顺序改读 `storages/workspace.json`（v2 `global.workspaceIds`）。测试 **124 项全部通过（0 失败 / 0 跳过，含 4 个真实 dsh 冒烟）** |
| 2026-09-15 | v0.2.3 | **跨平台运行时 + IDE 版本边界适配**（用户要求）：五个平台资产全部重建为 dsh 0.1.5-rc.2 —— win-x64 103.8MB / macos-arm64 118.7MB / **macos-x64 120.7MB（新增，Intel Mac，CI 无 runner）** / linux-x64 126.0MB / **linux-arm64 125.7MB（新增）**，均带 `.sha256` 侧车并同步到 `release-assets/`。`scripts/build-runtime.mjs` 现支持在 Windows 上交叉构建 Unix 运行时，过程中修掉三个坑：① Unix Node 包内 `bin/npm|npx|corepack` 是符号链接，Windows 创建需特权 → tar 失败时只要 `bin/node` 已解压则继续；② 探测/npm/冒烟不能用目标平台 node（本机无法执行）→ 交叉时改用主机 node + `--os/--cpu`；③ Linux 交叉安装必须 `--libc glibc`（原生构建交给 npm 自行判定，兼容 musl），否则静默跳过 `*-linux-*-gnu` 变体（包数 519→522、zip 118→126MB）；打包命令按主机能力选择（Windows `tar -a` / Unix `zip`）。**IDE 版本边界**：`since-build=241` / `until-build=262.*`，在 **2024.1.7**（含全部测试）、**2024.3.2**、**2026.2** 分别编译主代码 + 测试代码全部通过 |

## 实施进度

| 步骤 | 内容 | 状态 |
|---|---|---|
| Step 0 | 文档（PRD + DESIGN + 索引） | ✅ 完成 |
| Step 1 | 项目骨架（Gradle / plugin.xml / 工具窗口壳 / 设置页骨架 / i18n） | ✅ 完成 |
| Step 2 | 运行时自举（打包 Node+dsh / 进程管理 / JCEF 加载 Web UI / 端到端对话） | ✅ 完成 |
| Step 3 | MCP 桥接（IDE Bridge Server / MCP server / patch 注入） | ✅ 完成 |
| Step 4 | IDE 集成（发送选中代码 / 审查面板 diff+还原） | ✅ 完成 |
| Step 5 | 加固与发布（生命周期 / 崩溃 UX / 日志页 / 打包 / 验收） | ✅ 完成 |
| Step 6 | 里程碑评审（总结 / 遗留问题 / 后续规划） | ✅ 完成 |
| v0.1.2 | 2026.2 JCEF 兼容修复 | ✅ 完成 |
| v0.1.3-dev | 切换项目工作区根治（每项目独立 DSH_HOME）+ dsh 0.1.1-rc.2 升级回归 + 运行日志一键解释 + 旧 session/投影缓存升级迁移 + API Key 脱敏回显与 Web UI 全局生效 | ✅ 完成（90/90 测试） |
| v0.2.1 | 运行时供给 UX + 下载可靠性（thin 构建首启按平台下载运行时 + SHA-256 校验、工具窗口进度条/取消、设置页下载 URL/超时/本地 zip 导入、错误卡片；详见变更记录） | ✅ 完成（122 项测试全部通过） |
