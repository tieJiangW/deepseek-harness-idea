# DeepSeek Harness for IntelliJ IDEA

把 DeepSeek Harness 智能体工作台完整嵌入 IntelliJ IDEA，提供类 Qoder 的 AI 编程体验：
在 IDE 内嵌的 Web 界面中与智能体对话，让它读写你的项目文件，通过 MCP 桥接获取 IDE 上下文，
并用原生 diff 工具审查/还原它的改动。

Embed the [DeepSeek Harness](https://github.com/deepseek-ai) AI agent workbench directly inside
IntelliJ IDEA for a Qoder-like AI coding experience: chat with the agent in an embedded web UI,
let it read and modify your project files, bridge IDE context via MCP, and review/restore its
changes with native diff tooling.

> 声明 / Disclaimer：本插件非 DeepSeek 官方产品；DeepSeek Harness 与 DeepSeek 商标归其各自所有者。
> This plugin is not an official DeepSeek product; DeepSeek Harness and the DeepSeek trademark belong
> to their respective owners.

---

## 功能特性 / Features

- **内嵌 Web UI / Embedded Web UI** — 完整 DeepSeek Harness 界面（对话、会话、目标、工作流）通过 JCEF 在 IDE 工具窗口运行，体验与浏览器版一致。
- **以项目为工作区 / Workspace-bound agent** — 智能体以当前项目目录为工作区（启动时自动注册），可读取、新建、修改项目文件。
- **MCP 桥接 IDE 上下文 / IDE context via MCP** — 智能体可通过 `mcp__ide__*` 工具读取当前选中代码、打开的文件、项目树，并打开/定位文件。
- **发送选中代码 / Send selection to DSH** — 右键选中代码发送紧凑文件引用（`@路径#L1-14`）；完整代码可随时被智能体拉取（剪贴板兜底）。
- **运行日志一键解释 / One-click log explanation** — 在项目运行控制台选中一段日志，右键"DSH 一键解释"，自动把解释请求 + 日志提交给 DSH（无需手动粘贴/回车）。
- **每项目独立工作区 / Per-project workspace** — 每个项目使用独立的 DSH_HOME，工作区按项目隔离；切换项目后 DSH 自动以当前项目为工作空间，不会残留其他项目的工作区。
- **审查与还原 / Review & restore** — 基线快照 + 原生 diff（修改/新增/删除），支持还原单个/全部、忽略、重新基线。
- **自动供给的运行时 / Self-provisioned runtime** — 插件默认**不捆绑**运行时（瘦身包约 1.8MB）：首次使用按当前平台自动下载 Node.js 22.x + DeepSeek Harness 运行时（SHA-256 校验，缓存后离线复用）；也可在设置页用「运行时目录」或「选择本地运行时压缩包…」完全离线指定，或用 `-Pthin=false` 构建内置运行时的 fat 包。
- **生命周期管理 / Lifecycle** — 每项目一个实例（并发上限 3）；项目关闭 / IDE 退出自动终止进程；崩溃自动退避重启。
- **日志与诊断 / Logs & diagnostics** — DSH Log 标签页 + 崩溃通知 + 可配置日志级别。
- **中英双语界面 / Bilingual UI** — 插件 UI 跟随 IDE 语言（English / 简体中文）。

## 环境要求 / Requirements

- IntelliJ IDEA Community / Ultimate **2024.1 – 2026.2**（build 241 – 262；Windows 10/11 x64、macOS arm64/x64、Linux x64）
- 持有 DeepSeek API Key（`deepseek-chat` / `deepseek-reasoner`）
- 构建机器需要网络（构建时下载 Node.js 22.23.2 与 `@deepseek-ai/dsh@0.1.5-rc.2`）；**运行时按平台解析**：首次使用需联网一次下载运行时（SHA-256 校验，缓存到配置目录后离线可用；离线可用设置页「运行时目录」/ `DSH_IDEA_RUNTIME` / 内网镜像 / fat zip）；**五个平台**（Windows x64、macOS arm64/x64、Linux x64/arm64）的运行时资产随 Release 提供

## 安装 / Install

1. 构建插件 zip（见下）或下载 [Releases](../../releases) 中的 `deepseek-harness-idea-<version>.zip`（瘦身约 1.8MB，跨平台单个 zip）与对应平台的 `runtime-<os>-<arch>.zip`（附 `.sha256`；五个平台资产齐全）。
2. IDEA 中 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择该 zip，重启 IDE。
3. 打开右侧 **DeepSeek Harness** 工具窗口（首次打开自动下载并解压当前平台运行时，约 1 分钟；之后复用本地缓存不再下载）。
4. `Settings → Tools → DeepSeek Harness` 填入 DeepSeek API Key（或从本机已有 DeepSeek 配置一键导入）并应用，开始对话。

## 运行时与离线 / Runtime & offline

> **两个不同机制，别混**：
> - **「Runtime download URL」（设置项）** = **下载源**（网络或 `file://` zip），插件仍走"下载→校验→解压"。
> - **`DSH_IDEA_RUNTIME` / 手动「运行时目录」** = **本地已解压的运行目录**，插件**直接使用，完全绕过下载**。

- **默认运行时位置**：`<配置目录>/dsh-idea/runtime/<版本>/`（如 Windows
  `%APPDATA%\JetBrains\IntelliJIdea2024.3\...`、macOS `~/Library/Application Support/...`）。插件首次使用
  按平台下载（SHA-256 校验）并解压到该目录，之后复用；存量 Windows 安装复用已解压的运行时，无需再下载。

- **Runtime download URL（设置 → Tools → DeepSeek Harness）**：覆盖默认下载源（
  官方 GitHub Releases `…/releases/download/v{version}`，`{version}` 运行时替换为插件版本）。用于自建镜像 /
  内网源，或本机 `file://…/runtime-<os>-<arch>.zip` 离线下载。留空 = 官方源。**只改变下载地址，不改变运行时落盘目录。**

- **手动指定运行时目录（离线）**：设置 `DSH_IDEA_RUNTIME` 环境变量（Windows
  `set DSH_IDEA_RUNTIME=D:\path\to\runtime`，macOS/Linux `export DSH_IDEA_RUNTIME=/path/to/runtime`），或在
  设置页「运行时目录」填入同一路径。指向的目录必须是下面的标准结构，插件将**直接使用**它、不再联网：

  ```text
  <运行时目录>/
  ├── node/                 # Windows=node.exe；macOS/Linux=node
  └── dsh/node_modules/@deepseek-ai/dsh/lib/bin.js
  ```

  通常可直接指向插件已解压的 `…/dsh-idea/runtime/<版本>/`，或你自己按此结构放好的目录。**不能**指向一个随意的
  dsh 安装树（缺少独立 `node/`）；一旦该目录缺 `node` 或 `dsh` 中任一项，插件会直接报错而不触发下载。

- **想完全离线、零设置**：用 `./gradlew buildPlugin -Pthin=false` 构建含运行时（当前平台）的 fat zip，装完即用。

## 构建 / Build

```bash
# 要求：JBR 21（或 JDK 21）作为 JAVA_HOME；JDK 17 无法通过 instrumentCode 步骤
# 推荐直接用 IDE 自带的 JBR：<IDEA 安装目录>/jbr

# 全量测试（含真实 dsh 冒烟；设置 DSH_IDEA_RUNTIME 指向预构建运行时可跳过下载）
gradlew test

# 打包插件 zip（默认瘦身，输出到 build/distributions/；不含运行时）
gradlew buildPlugin

# 生成当前平台运行时 bundle（产出 build/runtime-<os>-<arch>.zip + .sha256）
node scripts/build-runtime.mjs --bundle

# 可选：构建含运行时（当前平台）的 fat zip（离线/内网安装）
gradlew buildPlugin -Pthin=false
```

> 首次构建会下载 ideaIC 2024.1.7 平台（约 1GB 缓存）与运行时依赖；本机若无法联网解析
> `org.jetbrains.intellij` 2.x，请使用 1.17.4（见 `build.gradle.kts` 注释）。
>
> 无 gradlew 环境（Windows）也可用 `tooling/gradle-8.14/bin/gradle.bat`（本仓库工具目录不入库，需自行准备）。

## 架构 / Architecture

```
IntelliJ IDEA (JVM/EDT)
├─ Tool Window: JBCefBrowser ──loads──► http://127.0.0.1:<port>（DSH Web UI）
├─ IDE Bridge Server（127.0.0.1，随机 token 鉴权）
│    /health /selection /open-files /project-tree /sent-selection /open-file /reveal
├─ Snapshot & Review Manager（基线快照 → diff → 还原/忽略）
└─ DshProcessManager（Node 子进程：端口发现、健康检查、崩溃退避重启）
        │  node <dsh>/bin.js --profile web --patch ide.yml --host 127.0.0.1 --port 0 --no-open
        ▼
Node 子进程（DSH）
├─ dsh web server（仅 loopback）
└─ mcp-client(ide) ──streamable-http──► MCP Server（mcp-ide-server.mjs，6 个 ide_* 工具）
```

## 文档 / Docs

- [docs/README.md](docs/README.md) — 文档索引与变更记录
- [docs/PRD.md](docs/PRD.md) — 需求文档（中）
- [docs/DESIGN.md](docs/DESIGN.md) — 详设文档（中）
- [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) — PRD §7 验收走查（中）
- [docs/MILESTONE_REVIEW.md](docs/MILESTONE_REVIEW.md) — 里程碑评审（中）
- [docs/PROJECT_NOTES.md](docs/PROJECT_NOTES.md) — 项目知识库（中；含本机开发环境实测记录）

## 测试 / Tests

- 90 个测试：86 单元 + 4 集成冒烟（真实 dsh 启动 / MCP 桥接 6 工具 / 切换项目工作区顺序 / 旧会话升级迁移）。
- 冒烟测试需要 `DSH_IDEA_RUNTIME` 指向预构建运行时目录，否则自动跳过。

## 许可 / License

[MIT](LICENSE)
