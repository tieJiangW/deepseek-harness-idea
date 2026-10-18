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

当前版本 / Current version：**0.2.5**

---

## 功能特性 / Features

**核心 / Core**

- **内嵌 Web UI / Embedded Web UI** — 完整 DeepSeek Harness 界面（对话、会话、目标、工作流）通过 JCEF 在 IDE 工具窗口运行，体验与浏览器版一致。
- **以项目为工作区 / Workspace-bound agent** — 智能体以当前项目目录为工作区（启动时自动注册），可读取、新建、修改项目文件。
- **自动供给的运行时 / Self-provisioned runtime** — 插件默认**不捆绑**运行时（瘦身包约 2MB）：首次使用按当前平台自动下载 Node.js 22.x + DeepSeek Harness 运行时（SHA-256 校验，缓存后离线复用）；也可在设置页用「运行时目录」或「选择本地运行时压缩包…」完全离线指定，或用 `-Pthin=false` 构建内置运行时的 fat 包。

**IDE 集成 / IDE integration**

- **MCP 桥接 IDE 上下文 / IDE context via MCP** — 智能体可通过 `mcp__ide__*` 工具读取当前选中代码、打开的文件、项目树，并打开/定位文件。
- **发送选中代码 / Send selection to DSH** — 右键选中代码发送紧凑文件引用（`@路径#L1-14`）；完整代码可随时被智能体拉取（剪贴板兜底）。
- **运行日志一键解释 / One-click log explanation** — 在项目运行控制台选中一段日志，右键「DSH 一键解释」，自动把解释请求 + 日志提交给 DSH（无需手动粘贴/回车）。
- **审查与还原 / Review & restore** — 基线快照 + 原生 diff（修改/新增/删除），支持还原单个/全部、忽略、重新基线。

**跨项目共享 / Cross-project sharing**

- **配置共享 / Shared config** — 模型、供应商、API Key、Agent 预设、个人技能在共享配置目录里配置一次，即对所有项目生效。
- **插件共享 / Shared dsh plugins** — **dsh 插件包装一次、所有项目加载**：每项目的 `profiles/` 链接到统一共享 profile，装一次即全局生效；会话、工作区与附件仍按项目隔离。
- **每项目独立数据面 / Per-project data** — 每个项目独立 DSH_HOME（会话、工作区、附件），切换项目不串台。

**插件管理 / dsh plugin management**

- **「DSH 插件」设置页** — 列出已装插件与纯依赖，按包名/版本范围或本地目录安装、选中卸载；也提供工具窗标题栏入口。
- **无需全局 pnpm** — 首次使用时在共享配置目录自备一份私有 pnpm（用运行时自带 npm 安装），不污染用户环境。
- **过程可见** — 阶段文案 + 进度条 + 每秒用时 + 实时输出 + 静默提示 + 可取消；结束给出绿色 ✓ / 红色 ✗（含用时）并弹 IDE 通知。
- **装完一键生效** — 成功后直接给「立即重启 Harness」按钮（通知里也有同一动作），不必去标题栏找按钮。
- **健壮兜底** — profile manifest 操作前备份、失败回滚；卸载遇到文件被占用时降级为 manifest 级移除；`node_modules` 与 pnpm 配置不一致时自动对齐或隔离重建。

**可靠性 / Reliability**

- **生命周期管理 / Lifecycle** — 每项目一个实例（并发上限 3）；项目关闭 / IDE 退出自动终止进程；崩溃自动退避重启。
- **日志与诊断 / Logs & diagnostics** — DSH Log 标签页 + 崩溃通知 + 可配置日志级别。
- **中英双语界面 / Bilingual UI** — 插件 UI 跟随 IDE 语言（English / 简体中文）。

## 环境要求 / Requirements

- IntelliJ IDEA Community / Ultimate **2024.1 – 2026.2**（build 241 – 262；Windows 10/11 x64、macOS arm64/x64、Linux x64）
- 持有 DeepSeek API Key（`deepseek-chat` / `deepseek-reasoner`）
- 构建机器需要网络（构建时下载 Node.js 22.23.2 与 `@deepseek-ai/dsh@0.1.5-rc.2`）；**运行时按平台解析**：首次使用需联网一次下载运行时（SHA-256 校验，缓存到配置目录后离线可用；离线可用设置页「运行时目录」/ `DSH_IDEA_RUNTIME` / 内网镜像 / fat zip）；**五个平台**（Windows x64、macOS arm64/x64、Linux x64/arm64）的运行时资产随 Release 提供
- 安装 dsh 插件包时需要网络（访问 npm registry；可使用镜像）

## 安装 / Install

1. 构建插件 zip（见下）或下载 [Releases](../../releases) 中的 `deepseek-harness-idea-0.2.5.zip`（瘦身约 2MB，跨平台单个 zip）与对应平台的 `runtime-<os>-<arch>.zip`（附 `.sha256`；五个平台资产齐全）。
2. IDEA 中 `Settings → Plugins → ⚙ → Install Plugin from Disk…` 选择该 zip，重启 IDE。
3. 打开右侧 **DeepSeek Harness** 工具窗口（首次打开自动下载并解压当前平台运行时，约 1 分钟；之后复用本地缓存不再下载）。
4. `Settings → Tools → DeepSeek Harness` 填入 DeepSeek API Key（或从本机已有 DeepSeek 配置一键导入）并应用，开始对话。
5. 需要扩展能力时，进入 `Settings → Tools → DeepSeek Harness → DSH 插件` 安装 dsh 插件包（见下一节）。

## DSH 插件管理 / dsh plugins

dsh 的插件是**装进 profile 的 npm 包**（profile = `$DSH_HOME/profiles/web`）。本插件把每个项目的 `profiles/`
链接到**同一个共享 profile**，因此：

- 在项目 A 装一次，项目 B/C… 都能加载；
- 会话、工作区、附件仍在各自 DSH_HOME，数据隔离不变；
- 开关：`Settings → Tools → DeepSeek Harness` 的「跨项目共享 dsh 插件包」（默认开启）。关闭只断链，共享根与已装插件保留不删。

安装方式（三选一）：

| 方式 | 说明 |
|---|---|
| 设置页 | `Settings → Tools → DeepSeek Harness → DSH 插件`，填 `包名` 或 `@scope/包名@^1.2.0`，点「安装」 |
| 本地目录 | 同一页的「Local folder…」→ 填 `link:<绝对路径>`（开发态联调） |
| 命令行 | 在 IDE 里用同样的 profile：`dsh plugin --profile web add <pkg>`（需自备 pnpm） |

安装/卸载后 **需要重启 Harness 才生效**（dsh 只在启动时解析 profile）。设置页在成功后会给出
「立即重启 Harness」按钮，通知里也带同一动作，因此不必手动去找工具窗标题栏的 Restart。

底层行为（便于排障）：

- 安装转发给 dsh 自己的 `dsh plugin --profile web add|remove`（复用其 profile 初始化、相对路径锚定与 `bundles` 对账）；
- 私有 pnpm 装在 `<共享配置根>/plugin-tools/`，首次需要联网；
- 每次操作前把 `package.json` / `pnpm-lock.yaml` / `cordis.patch.yml` / `pnpm-workspace.yaml` 备份到
  `<共享配置根>/plugin-backups/<时间戳>/`，命令失败自动回滚；
- 卸载时若 pnpm 因文件被运行中的 Harness 占用而失败，会改为**从 manifest 移除声明**（dsh 按 `dsh.profile.bundles`
  加载，因此插件确实被卸载、重启后不再加载；残留文件会在下次 pnpm 操作时对账）；
- `node_modules` 与当前 pnpm 配置不一致（`ERR_PNPM_VIRTUAL_STORE_DIR_MAX_LENGTH_DIFF`）时，先按
  `.modules.yaml` 的 `virtualStoreDirMaxLength` 对齐，仍失败则把旧目录改名为 `node_modules.broken-<时间戳>`
  并自动重试一次。

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

## 常见问题 / FAQ

**装完 dsh 插件，列表里没有 / 没生效？**
dsh 只在启动时解析 profile。装/卸成功后点设置页的「立即重启 Harness」（或通知里的同名按钮），插件才会加载。

**卸载时提示"文件被占用"或直接失败？**
运行中的 Harness 会占用 `node_modules`，Windows 不允许删除被占用的文件。插件会降级为 manifest 级移除并提示
"重启后不再加载"；若想彻底删掉文件，重启 Harness（或关闭工具窗口）后再卸载一次即可。

**安装时报 `ERR_PNPM_VIRTUAL_STORE_DIR_MAX_LENGTH_DIFF`？**
说明 profile 里的 `node_modules` 是别的 pnpm 配置建的。插件会自动对齐该配置；对不齐时会把旧目录改名隔离并重建
（见上文）。无需手动处理。

**刷新/安装一直转圈？**
v0.2.5 已修复：进度更新现在会穿透模态设置窗口（`ModalityState.any()`），命令也不再依赖 IDE 公共线程池。
如果仍遇到，请把 `Help → Show Log` 中含 `dsh-plugins-worker` / `install(` / `ensureProfileHome` 的行反馈给我们。

**多个项目共享插件，会不会串数据？**
不会。共享的只有 `profiles/`（插件代码）；会话、工作区、附件都在各自项目的 DSH_HOME 下。

**升级后磁盘上多了 `profiles-migrated/`、`node_modules.broken-*`？**
前者是首次启用共享时对旧项目 profile 的**整目录备份**（不合并、不删除），确认新方案稳定后可手动删除；
后者是 pnpm 目录不一致时被隔离的旧 `node_modules`，同样可在确认正常后删除。

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
├─ 「DSH 插件」设置页（安装/卸载 + 一键重启）──► DshPluginInstaller（私有 pnpm）
└─ DshProcessManager（Node 子进程：端口发现、健康检查、崩溃退避重启）
        │  node <dsh>/bin.js --profile web --patch ide.yml --host 127.0.0.1 --port 0 --no-open
        ▼
Node 子进程（DSH）
├─ dsh web server（仅 loopback）
└─ mcp-client(ide) ──streamable-http──► MCP Server（mcp-ide-server.mjs，6 个 ide_* 工具）
```

**目录布局（v0.2.5）**：`<配置目录>/dsh-idea/dsh-home/` 是共享配置根（settings / 凭据 / 预设 / 技能 /
`profiles/` 共享插件 / `plugin-tools/` 私有 pnpm / `plugin-backups/` 操作备份 / `profiles-migrated/` 迁移备份），
其下每个 `<md5(项目路径)>/` 是该项目的数据面（sessions / storages / attachments / `ide.yml`），其中
`profiles` 是指向共享根的链接。详见 [docs/DESIGN.md §4.4](docs/DESIGN.md)。

## 文档 / Docs

- [docs/README.md](docs/README.md) — 文档索引与完整变更记录
- [docs/PRD.md](docs/PRD.md) — 需求文档（中）
- [docs/DESIGN.md](docs/DESIGN.md) — 详设文档（中；含插件管理与目录布局）
- [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) — PRD §7 验收走查（中）
- [docs/MILESTONE_REVIEW.md](docs/MILESTONE_REVIEW.md) — 里程碑评审（中）
- [docs/PROJECT_NOTES.md](docs/PROJECT_NOTES.md) — 项目知识库（中；含本机实测与踩坑记录）
- [docs/releases/](docs/releases/) — 各版本发布说明（中英）

## 测试 / Tests

- **260 个测试**：256 单元 + 4 集成冒烟（真实 dsh 启动 / MCP 桥接 7 工具 / 切换项目工作区顺序 / 旧会话升级迁移）。
- 冒烟测试需要 `DSH_IDEA_RUNTIME` 指向预构建运行时目录，否则自动跳过。

## 许可 / License

[MIT](LICENSE)
