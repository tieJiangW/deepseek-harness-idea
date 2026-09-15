# 里程碑评审（Step 6）— DeepSeek Harness IDEA 插件

> **⚠️ 历史快照**：本文是 Step 6 里程碑评审（2026-08-20）时的记录，反映 Step 0–5 收官状态。
> 其后的 **v0.1.1 / v0.1.2 / v0.1.3-dev**（每项目隔离 DSH_HOME、dsh 0.1.1-rc.2、运行日志一键解释、
> 旧 session 迁移、API key 全局化等）与 **v0.2.0 / v0.2.1**（瘦身 + 按平台下载运行时模型、下载可靠性
> 与 UX——当前插件版本 0.2.1，2026-09-02）均已远超本快照。**最新实现与状态请以 [README.md](./README.md)、
> [DESIGN.md](./DESIGN.md)、[ACCEPTANCE.md](./ACCEPTANCE.md) 为准**；本文件仅作历史评审背景留存。

| 项目 | 内容 |
|---|---|
| 评审时间 | 2026-08-20 |
| 评审范围 | Step 0（文档）→ Step 5（加固与发布）全部交付物 |
| 依据 | PRD.md、DESIGN.md、ACCEPTANCE.md、PROJECT_NOTES.md、构建产物与测试结果 |
| 结论 | **MVP 目标达成（自动化/逻辑层）**；放行条件 = 5 项手工验收在真实 IDE 会话中闭环 |

---

## 1. 评审总结（Step 0–5）

| 步骤 | 目标 | 交付物 | 验证状态 |
|---|---|---|---|
| Step 0 文档 | 需求/设计/索引先行 | docs/PRD.md、docs/DESIGN.md、docs/README.md | ✅ 覆盖 §2–§7 全部条目；实现期活文档持续同步 |
| Step 1 骨架 | 可构建的插件壳 | Gradle 工程（Kotlin DSL + intellij 1.17.4 / ideaIC 2024.1.7）、plugin.xml（工具窗口/动作/服务/i18n）、设置页骨架、DshBundle 双语资源 | ✅ buildPlugin 成功；zip ≈1.6MB（当时）；PasswordSafe 241 API 实测锁定 |
| Step 2 运行时自举 | 开箱即用内嵌 DSH | scripts/build-runtime.ps1（Node 22.23.2 + `@deepseek-ai/dsh@0.1.0-rc.7`）、DshHomeManager（首次解压自举/DSH_HOME/凭据）、DshProcessManager（端口发现/健康检查/重启）、JCEF 工具窗口、默认工作区预注册（v0.5.1） | ✅ DshBootstrapSmokeTest 真实 dsh 启动 + workspace.json 断言；runIde 曾干净加载（Step 1 记录） |
| Step 3 MCP 桥接 | 智能体可调 IDE 能力 | IdeBridgeServer（HTTP+token）、mcp-ide-server.mjs（6 个 `ide_*` 工具）、McpPatchGenerator（`insert:` patch 实测语法）、DshBridgeManager、DSH_HOME 顶层 node_modules junction | ✅ DshMcpBridgeSmokeTest：tools/list 6 工具 + tools/call 桥接返回 + failOnStartupError 严格启动 |
| Step 4 IDE 集成 | 上下文发送 + 改动审查 | SendSelectionAction（紧凑引用 `@路径#L1-14` + sent-selection 队列 + JCEF 注入/剪贴板降级）、SnapshotManager（200MB LRU + 字节落盘）、ReviewManager/ReviewChangesAction（diff/还原/忽略/重新基线）、PathFilters | ✅ 逻辑层测试 5+5+5 全绿；UI 交互留手工 |
| Step 5 加固与发布 | 生命周期/崩溃/日志/打包 | DshLifecycleManager + DshAppLifecycleListener（项目关闭/IDE 退出终止进程树）、DshRuntimeRegistry（并发≤3）、崩溃通知、DshLogPanel、logLevel 设置、runtime-bundle.zip 打入插件 + 首次解压自举、PRD §7 走查（ACCEPTANCE.md） | ✅ 测试 36/36（见 §4）；产物 98.1MB |

**手工测试迭代（v0.5.1–v0.5.8，用户反馈闭环）**：默认选中主界面、默认工作区预注册、紧凑文件引用（去提示语/光标落位）、四个标题动作区分图标、右键动作图标统一、插件 Overview/What's New 中英双语、一键打包脚本 build-plugin.bat、项目知识库 PROJECT_NOTES.md。

**评审后兼容修复（v0.1.1）**：`until-build` 251.* → 262.*（用户 IDEA 2026.2/build 262 安装报错；`-PplatformVersion=2026.2` 前向编译验证通过）；Gson → 自研 `JsonCodec`（移除平台 Gson 依赖）；新增 JsonCodecTest 9 例（测试 36→45）。详见 docs/README.md 变更记录与 DESIGN §3.1/§3.5。

## 2. 需求覆盖矩阵

### 2.1 功能需求（PRD §5）

| 编号 | 条目 | 状态 | 说明 |
|---|---|---|---|
| FR-01.1 | 工具窗口 JCEF 加载 Web UI | ✅ | placeholder/loading/browser/error 四卡片 + 状态文案 |
| FR-01.2 | 工具栏动作 | ✅ | 设置/外部浏览器/审查改动/重启（图标区分）+ 日志 tab；"附加项目工作区"以启动自动预注册实现（v0.5.1） |
| FR-01.3 | JCEF 不可用降级 | ✅ | 错误卡 + 外部浏览器打开 |
| FR-01.4 | 加载状态反馈 | ✅ | 占位/加载/错误卡片 + statusLabel 四态（FR-08.2 同源实现）；转圈动画细节留手工核验 |
| FR-02.1 | 内置运行时首次解压（幂等） | ✅ | runtime-bundle.zip ≈106.9MB；实测解压 ≈62s |
| FR-02.2 | 每项目实例 + 懒启动 + 关闭终止 | ✅ | ProjectManagerListener + AppLifecycleListener 兜底 |
| FR-02.3 | 启动命令 | ✅ | `--patch` 置于 web 选项之前（实测必需） |
| FR-02.4 | 端口发现 + 健康检查 | ✅ | stdout 正则 + HTTP 探活 |
| FR-02.5 | 崩溃通知 + 退避重启 ≤3 | ✅ | 500ms/2s/5s |
| FR-02.6 | 并发上限 3 | ✅ | DshRuntimeRegistry + 提示 |
| FR-03.1–3.5 | 设置页（Key/模型/BaseURL/导入/高级） | ✅ | PasswordSafe + 插件全局凭据文件 + "重启会话生效"提示 |
| FR-04.1 | cwd = 项目根目录 | ✅ | 智能体 fs 以项目为工作区 |
| FR-04.2 | 附加项目工作区 | ✅ | workspace.create RPC 预注册（幂等；UI 打开即选中） |
| FR-05.1–5.3 | 发送选中代码 | ✅ | 64KB 截断 → Bridge 队列 → 注入/剪贴板降级；`ide_get_sent_selection` 兜底 |
| FR-05.4 | 发送当前文件（P1） | ⏳ 未实现 | 列入遗留问题 A-2 |
| FR-06.1–6.5 | 审查与还原 | ✅ | 忽略规则 + 1MB 上限 + 200MB LRU；diff/还原/忽略/重新基线；VFS 刷新 |
| FR-07.1–7.3 | MCP 桥接 | ✅ | streamable-http + insert patch；6 工具；127.0.0.1 + 随机 token |
| FR-08.1–8.2 | 日志页 + 状态指示 | ✅ | 可复制、5000 行上限；statusLabel 四态 |
| FR-09.1–9.2 | 国际化 | ✅ | DshBundle 双资源包；代码/日志英文 |
| FR-10.1–10.2 | 外部浏览器兜底 | ✅ | Desktop.browse + 错误卡引导 |

**P0 全部落地；P1 仅 FR-05.4 未实现（明确推迟）。**

### 2.2 用户故事（PRD §4）

US-01～US-10 均有对应实现；其中 US-01/02/04/06/07/10 的**最终体验验证依赖真实 IDE 会话**（见 §3 遗留问题 A 类）。

### 2.3 非目标（PRD §2.2）核验

| 非目标 | 状态 |
|---|---|
| 独立原生聊天 UI | ✅ 未做（嵌入 Web UI） |
| 内联补全/生成式重构 | ✅ 未做 |
| 远程开发/Gateway 支持 | ⚠️ 功能未做（符合）；但"检测远程环境并提示"也未实现（PRD §2.2 提及）→ 遗留 A-3 |
| macOS/Linux | ✅ v0.2.0 已做（瘦身通用插件 + 首次运行按平台下载运行时，见 docs/release-runtime.md） |
| 自动接受提交 | ✅ 未做（接受 = 丢弃快照，符合设计） |
| Marketplace 上架 | ⏳ 进行中（快照时点：✅ 未做，本地 zip 安装；**v0.2.1 已上传 JetBrains Marketplace——update id 1159301，待审核**） |

## 3. 遗留问题清单

### A. 验收遗留（需真实 IDE 会话 + 真实 API Key，手工）

| # | 事项 | 对应验收项/故事 | 自动化已覆盖部分 |
|---|---|---|---|
| A-1 | zip 安装到独立 IDEA 2024.1+（Windows）无报错，首次打开自动解压运行时（≈62s） | PRD §7-1 / US-01 | zip 结构已验证（plugin.jar + kotlin-stdlib + annotations + runtime-bundle.zip）；解压幂等逻辑有单测 |
| A-2 | 真实 API Key 对话；智能体读/建/改项目文件 | PRD §7-2/3 / US-02/04 | 凭据链路自动化；cwd/工作区预注册冒烟验证 |
| A-3 | 审查面板 DiffManager UI 交互与还原 | PRD §7-4 / US-07 | SnapshotDiffTest 5 例 + ReviewManager 三类还原逻辑 |
| A-4 | 发送选中代码的 JCEF 注入效果（含失败剪贴板降级） | PRD §7-5 / US-06 | 队列/工具链路自动化；注入脚本按 0.1.0-rc.7 DOM 编写 |
| A-5 | 关闭项目/退出 IDE 无残留 node 进程（端到端） | PRD §7-7 / US-10 | killTree（taskkill /T /F）+ 生命周期监听器逻辑层验证 |

### B. 功能缺口

| # | 事项 | 优先级 | 备注 |
|---|---|---|---|
| B-1 | FR-05.4 "发送当前文件"动作 | P1 | 文件路径 + 内容摘要，实现成本低 |
| B-2 | 远程开发/Gateway 环境检测提示 | P1 | PRD §2.2 提及；DESIGN §6 有处理描述但代码未实现 |
| B-3 | `ide_reveal_file` 仅"打开"文件，未做项目树定位高亮 | P2 | 工具已存在，可增强 reveal 语义 |
| B-4 | 输入框原生"文件引用 chip"（文件名+行号+X 删除） | P2 | **上游 dsh 0.1.0-rc.7 无此能力**（实测边界）；现用紧凑引用方案 + sent-selection 兜底；随 dsh 升级跟踪 |

### C. 技术债

| # | 事项 | 说明 |
|---|---|---|
| C-1 | `org.jetbrains.intellij` 1.17.4 → 2.x（platform 线） | 2.x 未在本网络插件门户解析到且 DSL 不兼容（build.gradle 注释）；升级需回归全量构建 |
| C-2 | dsh 版本锁定 0.1.0-rc.7 | 升级 = 改 `DSH_VERSION` + 重建运行时 + 回归 Step 3 patch 语法（`insert:`/`failOnStartupError`） |
| C-3 | 并发上限 3 → 单实例多工作区 | 多项目内存/端口优化方向；当前上限策略满足 MVP |
| C-4 | `failOnStartupError` 仅测试形态 | 生产可用 `reconnect` 语义（patch 已含 reconnect maxAttempts 3） |
| C-5 | 版本号口径不一致 | 插件产物版本 0.1.0（build.gradle.kts）vs 文档变更记录 v0.5.x；建议下一次发版前对齐（如 0.2.0）。**→ ✅ 已解决（v0.2.0 起按 0.2.x 发版对齐；当前插件版本 0.2.1，2026-09-02）** |
| C-6 | 杀软白名单说明未成文 | PRD §9 提及"文档说明加白名单"；用户文档层缺失，可并入插件 description 或随 A-1 安装文档补充 |

### D. 文档债（本次评审发现，随本评审已修复）

| # | 事项 | 修复 |
|---|---|---|
| D-1 | DESIGN.md §3.1 写 `org.jetbrains.intellij 2.x`，与实际 1.17.4 不符 | ✅ 修正为 1.17.4 + 升级说明 |
| D-2 | DESIGN.md 变更记录缺 v0.5.2–v0.5.8 | ✅ 补齐 |
| D-3 | ACCEPTANCE.md 测试合计 32 漏 WorkspaceInitializerTest（4 例） | ✅ 修正为 36 |
| D-4 | ACCEPTANCE.md 状态"走查中"未随 Step 6 收尾 | ✅ 标注评审结论 |

## 4. 质量数据（评审时点 vs 当前）

> 评审时点（2026-08-20，Step 6）与各后续版本的数据如下；**截至 v0.2.1（2026-09-02），自动化测试已达 122/122、全部通过（0 失败）**（见下方"当前"）。

- **评审时点（2026-08-20，Step 6）**：自动化测试 **36/36**（Step 0–5）；构建产物 `deepseek-harness-idea-0.1.0.zip`（102,889,616 B ≈98.1MB）；含 runtime-bundle.zip（≈106.9MB 压缩，首次解压 ≈62s）。
- **v0.1.1**：Gson→JsonCodec 变更，新增 JsonCodecTest 9 例，测试 36→**45**；`until-build` 251.*→262.*。
- **v0.1.2**：2026.2 JCEF 兼容修复（plugin.xml 可选依赖 `com.intellij.modules.jcef`）；测试 45/45 复跑通过。
- **v0.1.3-dev**：每项目独立 DSH_HOME/工作区根治、dsh 0.1.1-rc.2 升级回归、运行日志一键解释（FR-11）、旧 session/投影缓存升级迁移、API Key 脱敏回显 + Web UI 全局生效（方案B）；测试 **90/90**（86 单元 + 4 集成冒烟）；构建产物 `deepseek-harness-idea-0.1.2.zip`。
- **v0.2.0**：运行时供应模型重构——瘦身默认不捆绑 ~93MB 运行时（插件 ≈1.8MB），首次使用按平台下载（`runtime-assets.json` → `runtime-<os>-<arch>.zip` + `.sha256` 校验 → 解压 `<config>/dsh-idea/runtime/<DSH_VERSION>`，离线/升级复用）；`DSH_IDEA_RUNTIME` / 设置页 runtime-directory 跳过下载；fat（`-Pthin=false`）仍 bundle（不下载）；macOS/Linux 支持（跨平台 `scripts/build-runtime.mjs`）；测试 **113**（109 单元 + 4 集成冒烟）。
- **v0.2.1（2026-09-02，当前）**：修复首次使用下载失败（临时文件父目录缺失 → `NoSuchFileException`，先建目录再写）；连接池化 HTTP/2 `java.net.http.HttpClient` + 浏览器 UA + 60s 连接超时 + 可配置读超时 + 退避重试（慢速/不稳定网络可成功）；工具窗口下载进度条（可取消）；设置页精确 URL 回显 + 一键复制 + 可配置下载超时 + 本地 zip 离线导入（SHA-256 vs 侧车）；错误卡失败 URL + 根因 + Restart；测试 **122/122 全部通过（0 失败）**；构建产物 `deepseek-harness-idea-0.2.1.zip`；JetBrains Marketplace 已上传（update id 1159301），待审核。
- **构建链**：JBR 21 必须；Gradle 8.14 `--no-daemon`（防缓存锁）；一键脚本 `scripts/build-plugin.bat`。
- 运行时目录（`tooling/runtime-dev` 与 `build/runtime`）均完整（node + dsh 齐全，可离线构建）。

## 5. 风险回顾（PRD §9 逐项核验）

| 风险 | 评审结论 |
|---|---|
| 插件包体积 150–300MB | ✅ 实测 98.1MB，低于预期下限；缓解有效 |
| Web UI DOM 变化破坏 JS 注入 | ✅ 剪贴板降级 + `ide_get_sent_selection` 兜底已落地 |
| cordis patch 语法演进 | ✅ 锁定版本 + DSH_HOME 版本化目录；升级回归点已记录（C-2） |
| JCEF 兼容性（WebSocket/IME） | ✅ 外部浏览器兜底已实现 |
| 多项目并发资源占用 | ✅ 并发上限 3 + 懒启动；后续单实例优化（C-3） |
| 杀软拦截 node.exe | ⚠️ 日志/失败提示已实现；白名单文档未成文（C-6） |
| dsh web UI 依赖最新前端构建 | ✅ 固定 0.1.0-rc.7 并与当前 GUI 对齐 |

## 6. 后续规划

### 短期（v0.6：验收闭环）

1. 真实 IDE 会话执行 PRD §7 手工验收 A-1～A-5（安装 zip → 真实 API Key 对话 → 建/改文件 → 审查还原 → 发送选中 → 进程清理）。
2. 验收中发现的问题按"先更新文档再改代码"流程闭环。
3. B-1 "发送当前文件"动作；C-6 杀软白名单用户文档；~~C-5 版本号对齐（0.2.0）~~ **✅ 已完成**（v0.2.0/v0.2.1 已发版，当前插件版本 0.2.1）。

### 中期（v0.7：架构与体验升级）

1. C-1 intellij 2.x（platform 线）升级与回归。
2. C-2 dsh 版本升级评估（含 Step 3 patch 语法回归、B-4 上游能力跟踪）。
3. B-2 远程开发环境检测提示；B-3 `ide_reveal_file` 项目树定位增强。
4. C-3 单实例多工作区（多项目共享一个 dsh，降低内存/端口占用）。

### 长期（v1.x）

- Marketplace 上架评估：**✅ 已进入上架流程**（v0.2.1 上传 JetBrains Marketplace，update id 1159301，待审核）；体积上限问题经 v0.2.0 瘦身 + "首次启动下载运行时"（该备选方案已实现为默认 thin 模型）解除；插件签名仍待评估。
- 平台扩展：**✅ 打包已参数化 macOS/Linux**（v0.2.0 起 `scripts/build-runtime.mjs`，任意主机按目标 os/arch 构建）；注意 **macos-x64（Intel Mac）无法在 GitHub-hosted runner 构建（Intel macOS 已退役）→ 发布资产缺席，需其他主机构建后补传**。
- 体验扩展：JetBrains AI 风格内联补全/生成式重构（PRD §2.2 非目标，可重新评估）。

## 7. 评审结论

- **MVP 目标达成（自动化/逻辑层）**：PRD §2.1 六项目标全部实现；P0 功能需求全部落地；NFR-01～06 均有实现与测试佐证；风险缓解措施按设计生效。
- **放行条件**：A-1～A-5 五项手工验收在真实 IDE 会话中闭环（需真实 API Key），并修复验收中暴露的问题。
- **遗留问题已分级归档**（A 验收 / B 功能 / C 技术债 / D 文档债），可作为 v0.6 起迭代的 backlog 直接使用。
