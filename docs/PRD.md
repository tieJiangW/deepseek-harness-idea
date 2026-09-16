# DeepSeek Harness IntelliJ IDEA 插件 — 规划需求文档（PRD）

| 项目 | 内容 |
|---|---|
| 文档版本 | v0.1 |
| 日期 | 2026-02-11 |
| 状态 | 草稿（随实现迭代更新） |
| 目标 IDE | IntelliJ IDEA Community / Ultimate，2024.1 – 2026.2（since 241 / until 262.*，v0.1.1 起） |
| 目标平台 | Windows 10/11 x64（MVP）；macOS（arm64/x64）与 Linux x64 自 v0.2.0 起支持（运行时按平台下载） |

---

## 1. 背景与问题陈述

DeepSeek Harness（DSH）是一个以本地 Web UI（`dsh web`，默认 `http://127.0.0.1:3080`）为主要交互界面的智能体工作台：用户通过浏览器与智能体对话，智能体可读写工作区文件、执行命令、管理目标/工作流等。但其浏览器形态与 IDE 工作流割裂：

- 用户需要在 IDE 与浏览器之间反复切换；
- 智能体看不到 IDE 上下文（当前选中代码、打开的文件、项目结构）；
- 智能体对项目文件的改动没有 IDE 原生审查/还原入口；
- 缺少"把 IDE 里的代码/文件交给智能体"的顺滑通道。

本插件把 DSH 完整嵌入 IntelliJ IDEA：在 IDE 内直接使用 DSH Web UI，并通过 MCP 桥接 + 原生集成把 IDE 能力交给智能体，形成类 Qoder 的 AI 编程体验。

## 2. 目标与非目标

### 2.1 目标

1. 在 IDEA 工具窗口内直接使用 DSH 全部 Web UI 功能（对话、会话管理、目标/工作流等），体验与浏览器版一致。
2. 智能体以**当前打开的项目目录**为工作区：可读取、修改、新建项目文件。
3. 通过 MCP 桥接，智能体可调用 IDE 能力：读取当前选中代码、打开的文件、项目结构、定位/打开文件。
4. 提供原生 IDE 集成：右键"发送选中代码到 DSH"、AI 改动 diff 审查与还原。
5. 插件开箱即用：运行时按平台解析（Windows 首次下载一次，macOS/Linux 同样按需下载并 SHA-256 校验，缓存后复用），用户无需手动安装 Node / DSH。
6. UI 中英双语。

### 2.2 非目标（MVP 明确不做）

- 不实现独立的原生聊天 UI（聊天界面 = 嵌入的 DSH Web UI）。
- 不实现 JetBrains AI 风格的内联代码补全 / 生成式重构动作（后续版本可考虑）。
- 不支持 Remote Development / JetBrains Gateway（MVP 检测到远程环境时提示不可用）。
- 运行时在 macOS / Linux 上通过「首次运行按平台下载」提供（v0.2.0 起支持）；不保证离线环境的首次自举，可用 `DSH_IDEA_RUNTIME` / 内网镜像 / fat zip 覆盖。
- 不做智能体修改的自动接受提交（审查面板默认"接受=丢弃快照"，不写回，因为 dsh 已直接写盘）。
- 发布到 JetBrains Marketplace（"不发布"为 MVP 初期非目标，自 v0.1.3 上架后撤销；v0.2.0 起为瘦身跨平台单包，符合 Marketplace 单版本单 zip 模型；**v0.2.3 已上传 update id 1171727，待审核**）。

## 3. 用户画像与核心场景

**用户**：使用 IntelliJ IDEA 的开发者（中国用户为主，中英双语界面），持有 DeepSeek API Key，希望在 IDE 内直接使用 DSH 智能体完成编码任务。

核心场景：

1. **开箱即用**：安装插件 → 打开项目 → 工具窗口出现 DSH 界面 → 填入 API Key → 开始对话。
2. **让智能体改代码**：对话中要求"给 `UserService` 加缓存"，智能体读取/修改项目文件，用户随后在审查面板看 diff 并决定保留/还原。
3. **把 IDE 上下文交给智能体**：编辑器里选中一段代码 → 右键"发送选中代码到 DSH" → 智能体基于该代码继续工作；智能体也可随时主动读取"当前选中/打开文件/项目结构"。
4. **多项目并行**：同时打开多个项目，每个项目有独立的 DSH 会话（独立工作区）。

## 4. 核心用户故事

| 编号 | 角色 | 故事 | 验收标准 |
|---|---|---|---|
| US-01 | 开发者 | 作为 IDEA 用户，我安装插件并打开项目后，希望工具窗口直接显示可用的 DSH 界面 | 工具窗口可打开；无 Node/dsh 安装步骤；首次运行自动下载、校验并解压运行时（thin 默认按平台下载一次，缓存后离线可用；v0.2.1 下载有进度/取消） |
| US-02 | 开发者 | 作为 IDEA 用户，我希望在设置页配置 DeepSeek API Key 后即可使用 | 填写 Key 并应用后，新会话可正常发起对话；Key 缺失时界面给出明确提示并引导到设置页 |
| US-03 | 开发者 | 作为 IDEA 用户，我希望把本机已有的 DeepSeek 凭据一键导入，避免重复输入 | 设置页"从本机导入"可读取 `DEEPSEEK_API_KEY` 并填入 |
| US-04 | 开发者 | 作为 IDEA 用户，我希望智能体以当前项目目录为工作区读写文件 | 智能体可读取项目文件内容；新建文件出现在项目树；修改内容可从磁盘读到 |
| US-05 | 开发者 | 作为 IDEA 用户，我希望智能体能看到"我选中的代码/打开的文件" | 智能体调用 `mcp__ide__*` 工具可获取选中文本、打开文件列表、项目树 |
| US-06 | 开发者 | 作为 IDEA 用户，我希望右键选中代码可一键发送给智能体 | 动作后工具窗口聚焦并出现对应提示；智能体可获取该代码（即使注入失败也可通过工具拉取） |
| US-07 | 开发者 | 作为 IDEA 用户，我希望智能体的改动可以 diff 审查并还原 | 审查面板列出改动文件；每文件可看 diff；可还原单个或全部文件；可忽略 |
| US-08 | 开发者 | 作为 IDEA 用户，我希望 Node 崩溃/异常时得到明确反馈而非静默失败 | 崩溃通知 + 一键重启；日志页可查看 dsh 输出 |
| US-09 | 开发者 | 作为 IDEA 用户，我希望界面语言跟随 IDEA（中/英） | 切换 IDEA 语言后插件文案跟随（资源包机制） |
| US-10 | 开发者 | 作为 IDEA 用户，我希望关闭项目/退出 IDE 后没有残留进程 | 项目关闭/应用退出时 Node 进程被终止 |

## 5. 功能需求（FR）

优先级：P0 = MVP 必须；P1 = MVP 应有（缺失可降级但不影响主链路）；P2 = 增强。

### FR-01 工具窗口（P0）
- FR-01.1 提供 "DeepSeek Harness" 工具窗口，内容为 JCEF 浏览器加载 DSH Web UI（`http://127.0.0.1:<port>`）。
- FR-01.2 工具栏动作：附加项目工作区、在外部浏览器打开、重启 dsh、审查改动、打开设置、查看日志。
- FR-01.3 JCEF 不可用时降级：提示 + "在外部浏览器打开"按钮。
- FR-01.4 加载状态反馈：启动中（转圈 + 阶段文案）、失败（原因 + 引导）。

### FR-02 运行时自举与生命周期（P0）
- FR-02.1 运行时按平台下载（v0.2.0 起 thin 默认）：插件**不内置**约 93MB 的运行时（含 Node.js 22.x 与 dsh-home），首次使用按当前平台经 `runtime-assets.json` 下载 `runtime-<os>-<arch>.zip`（+ `.sha256`），SHA-256 校验后解压到 `PathManager.getConfigDir()/dsh-idea/runtime/<version>/`（幂等、带版本目录），缓存后离线/升级复用；fat 构建（`-Pthin=false`）才把运行时打进插件资源（免下载）。下载 URL 与超时可在设置页配置；`DSH_IDEA_RUNTIME` 或设置页「运行时目录」可跳过下载。v0.2.1 下载 UX：工具窗口下载进度条（connecting/verifying/downloading）+ 取消；设置页回显精确到文件的当前平台 URL + 一键复制 + "选择本地运行时 zip…"离线导入（校验 zip 与 `.sha256`）；失败错误卡显示失败 URL + 底层原因 + Restart。**v0.2.3**：下载地址输入框**反显当前生效值**（支持目录级 base 与"到文件的完整 URL"直连）+「默认」按钮；新增**「运行时目录」设置项**（环境变量的 GUI 版；默认按钮填入插件默认目录，且**与未设置等价**——仍会自动下载供给）；本地 zip 选择器修复（不再隐藏 `.zip`），文件无法经常规路径访问时经 **VFS 暂存**导入；运行时资产补齐**五个平台**（Windows x64、macOS arm64/x64、Linux x64/arm64）。
- FR-02.2 每项目一个 Node 实例：工具窗口首次打开时懒启动，项目关闭时终止，IDE 退出时兜底终止。
- FR-02.3 启动命令：`node <dsh>/lib/bin.js --profile web --host 127.0.0.1 --port 0 --patch <ide.yml>`，cwd=项目根目录，env 注入 `DSH_HOME`。
- FR-02.4 端口发现：解析 stdout 中 `dsh web: http://127.0.0.1:<port>`，随后 HTTP 健康检查，通过后通知 UI 加载。
- FR-02.5 崩溃处理：通知 + 指数退避自动重启（≤3 次）+ 手动重启；日志捕获。
- FR-02.6 并发上限：同时运行的实例 ≤ 3，超出给出提示。

### FR-03 设置页（P0）
- FR-03.1 API Key：存入 `PasswordSafe`（应用级），应用时写入插件全局凭据文件（键 `DEEPSEEK_API_KEY`）并同步到各项目 DSH_HOME；**设置页脱敏回显**（前 6 位 + ****** + 后 6 位）；**dsh Web UI 改 key 也经 `DshCredentialsSync` 同步回全局**，保证所有子项目一致；提示"重启会话生效"。
- FR-03.2 模型选择：`deepseek-chat` / `deepseek-reasoner`，默认 `deepseek-chat`。
- FR-03.3 Base URL（可选，默认 `https://api.deepseek.com`），用于代理/自定义网关。
- FR-03.4 "从本机导入凭据"：读取用户本机已有 DeepSeek 凭据文件的 `DEEPSEEK_API_KEY` 填入。
- FR-03.5 高级项：DSH_HOME 位置（默认插件配置目录）、日志级别。

### FR-04 项目绑定（P0）
- FR-04.1 启动 Node 时 cwd=项目根目录，使默认工作区 = 项目目录；**每个项目使用独立 DSH_HOME**（工作区/会话数据按项目隔离，切换项目后从当前项目白纸开始）。
- FR-04.2 "附加项目工作区"：启动后自动把项目根注册为默认工作区（`WorkspaceInitializer` 调 dsh RPC `workspace.create` + `workspace.insertBefore` 置顶；无需手动动作）。

### FR-05 代码上下文发送（P0）
- FR-05.1 编辑器右键菜单："发送选中代码到 DSH"（读取选中文本+文件路径+语言，容量上限 64KB）。
- FR-05.2 动作流程：推送至 IDE Bridge `/sent-selection` → 聚焦工具窗口 → 尝试 JCEF JS 注入填充输入框（失败降级：复制到剪贴板 + 界面提示）。
- FR-05.3 智能体侧工具 `ide_get_sent_selection` 可随时拉取最近发送的代码（兜底通道）。
- FR-05.4 "发送当前文件"动作（P1）：文件路径 + 内容摘要。

### FR-06 改动审查与还原（P0）
- FR-06.1 快照基线：工具窗口首次打开时对项目文件快照（跳过 build/.git/node_modules/out；单文件 ≤1MB；总容量 LRU ≤200MB）。
- FR-06.2 审查面板：列出修改/新增/删除文件。
- FR-06.3 每文件 diff（`DiffManager`，快照 vs 当前）；动作：还原该文件 / 还原全部 / 忽略（丢弃快照）。
- FR-06.4 打开审查时对受影响目录执行 VFS 刷新。
- FR-06.5 "重新基线"：用户可手动重置快照（例如接受全部改动后）。

### FR-07 MCP 桥接（P0）
- FR-07.1 插件侧提供 MCP server（streamable-http，127.0.0.1 随机端口），经 `ide.yml` patch 注入 dsh 的 mcp-client（serverName=`ide`）。
- FR-07.2 工具集（模型侧名 `mcp__ide__<tool>`）：
  - `ide_get_selection`：当前编辑器选中文本、文件路径、语言；
  - `ide_get_open_files`：当前打开的文件列表（路径、语言、是否修改）；
  - `ide_get_project_tree`：项目树（深度限制、忽略规则）；
  - `ide_get_sent_selection`：最近经"发送到 DSH"推送的代码；
  - `ide_open_file`：按路径在 IDE 中打开文件（P1）；
  - `ide_reveal_file`：在项目树中定位文件（P1）。
- FR-07.3 鉴权：IDE Bridge 与 MCP server 均绑定 127.0.0.1；MCP→Bridge 用随机 token（每次启动生成）。

### FR-08 日志与健康状态（P1）
- FR-08.1 工具窗口"DSH 日志"页：Node 进程 stdout/stderr + 插件桥接日志，可复制。
- FR-08.2 状态指示：工具窗口显示运行状态（启动中/运行中/已停止/异常）。

### FR-09 国际化（P0）
- FR-09.1 全部文案走 `DshBundle` 资源包（`DshBundle.properties` + `DshBundle_zh_CN.properties`）。
- FR-09.2 代码与日志使用英文。

### FR-10 外部浏览器兜底（P1）
- FR-10.1 "在外部浏览器打开"：`Desktop.browse(http://127.0.0.1:<port>)`。
- FR-10.2 用于 JCEF 异常、中文输入法异常、Web UI 与 JCEF 不兼容等场景。

### FR-11 运行日志一键解释（P1，v0.1.3-dev 已实现）
- FR-11.1 运行控制台（Run 控制台，ConsoleView）选中日志后右键出现"DSH 一键解释"；无选中文本时不显示。
- FR-11.2 点击后**不等待用户确认**：本地化解释指令 + 选中日志作为用户问题自动提交给 DSH 对话（JCEF 填 composer + 派发回车）。
- FR-11.3 结果反馈：成功 / 阻塞 / 失败均有通知；失败降级剪贴板；DSH 未运行明确提示。
- FR-11.4 超长日志（>64KB）截断并注明。

## 6. 非功能需求（NFR）

| 编号 | 类别 | 要求 |
|---|---|---|
| NFR-01 | 性能 | 运行时就绪后（首次按平台下载并解压完成后的后续使用），工具窗口从打开到可对话 ≤ 15s（本地磁盘、正常机器）；首次下载约 93MB 运行时为一次性成本（v0.2.1 带进度/取消）；Node 实例空闲内存可接受（dsh 正常水平）；UI 操作不阻塞 EDT |
| NFR-02 | 安全 | 所有服务仅绑定 127.0.0.1；MCP/Bridge 间使用随机 token；API Key 不落日志、不落插件源代码；不使用 `--host 0.0.0.0`（dsh 本身拒绝） |
| NFR-03 | 可靠性 | 安装/解压幂等；崩溃可自动重启（退避）；项目关闭/IDE 退出无残留进程；磁盘写失败有明确报错 |
| NFR-04 | 可维护性 | dsh 版本固定（`@deepseek-ai/dsh@0.1.5-rc.2`），升级=重建运行时；DSH_HOME 版本化目录便于升级；模块边界清晰（runtime/bridge/mcp/review/ui/settings 分离） |
| NFR-05 | 兼容性 | IntelliJ IDEA 2024.1 – 2026.2（Community/Ultimate，until 262.*）；Windows 10/11 x64、macOS（arm64/x64）、Linux x64；路径含空格/中文可用；JCEF 不可用有降级；运行时按平台解析（首次下载，缓存后离线可用，v0.2.0 起） |
| NFR-06 | 可用性 | 关键失败（缺 Key、Node 缺失、端口异常、崩溃）均有中文+英文明确提示与恢复路径；不静默失败 |

## 7. 成功标准与验收清单

MVP 成功标准：全新 IDEA 实例中，安装插件 zip → 配置 API Key → 工具窗口内完成一次真实对话，智能体能够读取项目文件、创建新文件、修改文件，且改动可审查/还原，代码上下文可发送。逐条验收清单（Step 5 执行）：

1. 从磁盘安装构建出的 zip 到独立 IDEA 2024.1 – 2026.2（Windows）实例，无报错。
2. 设置页填入 DeepSeek API Key 后，工具窗口内可对话；智能体可读取项目文件内容。
3. 指示智能体"新建 `src/Hello.java`"，文件出现在项目树并可打开。
4. 指示智能体修改某文件，审查面板出现该文件 diff，还原后内容恢复。
5. 选中代码右键发送，会话中出现对应提示，智能体可获取该代码。
6. 提问"当前打开的文件是什么"，智能体通过 MCP 工具正确回答。
7. 关闭项目/IDE 后无残留 node 进程。
8. 切换 IDE 语言（中/英）后插件文案跟随。
9. 运行控制台选中一段日志，右键"DSH 一键解释"，DSH 对话自动出现解释请求并可正常回复。

## 8. 假设与依赖

- Windows 10/11 x64、macOS（arm64/x64）、Linux x64；IntelliJ IDEA Community/Ultimate 2024.1 – 2026.2（until 262.*）。
- 用户持有 DeepSeek API Key（`deepseek-chat` / `deepseek-reasoner` 可用）。
- 构建机有网络（构建时下载 Node 与 dsh 依赖）；**运行时按平台解析**：首次使用需联网一次下载运行时（SHA-256 校验，缓存到配置目录后离线可用；离线可用 `DSH_IDEA_RUNTIME` / 内网镜像 / fat zip）。
- dsh 版本固定 0.1.5-rc.2（与当前环境一致），其 Web UI 与 `--patch`/mcp-client 行为以该版本为准。
- 插件不修改用户本机已有的 DeepSeek 配置（独立 DSH_HOME），避免与浏览器版 dsh 互相干扰。

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 插件包体积 150-300MB（fat 内置运行时） | 分发/安装体验 | v0.2.0 起缓解：thin 默认不打包运行时（插件 zip ≈1.83MB），首次使用按平台下载运行时（进度/取消/可配置 URL 与超时/本地 zip 离线导入/「运行时目录」指定）；已上架 Marketplace（**v0.2.3 已上传 update 1171727，待审核**） |
| Intel Mac（macos-x64）运行时资产缺失 | 原：GitHub-hosted runner 无 Intel macOS（已退役），`runtime-macos-x64.zip` 无法在 CI 构建 → Intel Mac 首次下载 404 | **✅ 已解决（v0.2.3）**：改为在 Windows 主机**交叉构建**后随 Release 上传（`build-runtime.mjs` 已支持），`runtime-macos-x64.zip` 已提供；`linux-arm64` 同样补齐，五平台资产齐备。完全离线仍可经 `DSH_IDEA_RUNTIME` / 设置页「运行时目录」/「选择本地运行时压缩包…」 |
| Web UI DOM 变化破坏 JS 注入 | "发送选中代码"体验降级 | **⚠️ 已实际发生（dsh 0.1.5）**：composer 由 `<textarea>` 改为 Lexical contenteditable → 注入失效，按设计**降级剪贴板**（有通知），`ide_get_sent_selection` 兜底；待按 Lexical 重写注入脚本（当前最高优先项） |
| cordis patch 语法随 dsh 版本演进 | MCP 注入失效 | 锁定 dsh 版本；DSH_HOME 版本化；升级时回归验证 Step 3 |
| JCEF 兼容性（WebSocket/IME） | 聊天/中文输入异常 | "外部浏览器打开"按钮兜底；JCEF 版本随 IDE 更新 |
| 多项目并发资源占用 | 内存/端口 | 每项目实例 + 并发上限 3 + 懒启动；后续可优化为单实例多工作区 |
| 杀软拦截 node.exe | 启动失败 | 文档说明加白名单；日志可诊断；失败提示明确 |
| dsh web UI 依赖最新前端构建 | 功能缺失 | 固定 dsh 版本并与当前 GUI 版本对齐（0.1.5-rc.2） |

## 10. 迭代规划与范围管理

| 步骤 | 内容 | 对应本 PRD |
|---|---|---|
| Step 0 | 文档（本文 + DESIGN） | — |
| Step 1 | 项目骨架：Gradle、plugin.xml、工具窗口壳、设置页骨架、i18n | FR-01（部分）、FR-03（部分）、FR-09 |
| Step 2 | 运行时自举 + 端到端对话 | FR-01、FR-02、FR-03、FR-04 |
| Step 3 | MCP 桥接 | FR-07、FR-05.3 |
| Step 4 | IDE 集成：发送选中代码 + 审查面板 | FR-05、FR-06 |
| Step 5 | 加固与发布：生命周期、崩溃 UX、日志页、打包、验收 | FR-02.5/2.6、FR-08、§7 验收清单 |

范围变更控制：任何需求变更先更新本文档相应条目并标注版本，再进入对应实现步骤。
