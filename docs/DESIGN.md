# DeepSeek Harness IntelliJ IDEA 插件 — 详设文档（DESIGN）

| 项目 | 内容 |
|---|---|
| 文档版本 | v0.1 |
| 日期 | 2026-02-11 |
| 状态 | 草稿（随实现迭代更新） |
| 关联文档 | [PRD.md](./PRD.md) |

---

## 1. 术语与参考

| 术语 | 说明 |
|---|---|
| DSH | DeepSeek Harness，智能体工作台 CLI/服务（`@deepseek-ai/dsh`） |
| dsh web | DSH 的浏览器 UI 服务：`dsh --profile web`，默认 `http://127.0.0.1:3080` |
| DSH_HOME | dsh 的配置/数据目录（profiles、sessions、credentials），插件使用独立目录 |
| JCEF | JetBrains 内置 Chromium Embedded Framework（`com.intellij.ui.jcef.JBCefBrowser`） |
| IDE Bridge | 插件内的 Kotlin 本地 HTTP 服务，向 MCP server 暴露 IDE 能力 |
| MCP | Model Context Protocol；dsh 作为 MCP 客户端连接插件提供的 MCP server |

参考源码（本机 `tooling/runtime-dev` 与 dsh profile 目录中的 `@deepseek-ai/dsh@0.1.5-rc.2`）：

- `dsh-web-app/lib/startup.js`：web 命令行 `--host/--port/--trusted-host`；`--port 0` 由 OS 分配
- `dsh-web-app/lib/index.js:107`：启动成功打印 `dsh web: http://127.0.0.1:<port>`（loopback）
- `dsh-client-connection/lib/index.js`：`/api` 浏览器信任围栏，loopback hostname 默认受信任；`--host 0.0.0.0` 被拒绝
- `dsh-mcp-client/lib/index.js:738-756`：mcp-client Config schema（`transport: streamable-http` 分支）
- 凭据文件（插件以 `DEEPSEEK_API_KEY` 为键管理）；profiles/web/：profile 结构（`cordis.yml` = bundle 层 + `cordis.patch.yml` 用户层 + `--patch` 覆盖层；`package.json` 的 `dsh.profile.bundles` 声明 bundle）

## 2. 总体架构

### 2.1 架构图

```
┌───────────────────────────── IntelliJ IDEA 进程（JVM/EDT）────────────────────────────┐
│  Plugin (Kotlin)                                                                       │
│  ├─ Tool Window: JBCefBrowser ──loads──► http://127.0.0.1:<webPort>（DSH Web UI）      │
│  ├─ IDE Bridge Server（JDK HttpServer，127.0.0.1:<bridgePort>，X-DSH-IDE-Token 鉴权）  │
│  │    /health /selection /open-files /project-tree /sent-selection                     │
│  │    /open-file /reveal                                                               │
│  ├─ Snapshot & Review Manager（基线快照 → DiffManager diff → 还原/忽略）               │
│  ├─ DshProcessManager（Node 子进程生命周期、stdout 端口解析、日志、崩溃重启）           │
│  └─ RuntimeProvisioner（v0.2.0+ thin：首次按平台下载+校验+解压；v0.2.1 进度/取消/重试） │
└───────────────────────────────────┬───────────────────────────────────────────────────┘
                                    │ ProcessBuilder：node.exe dsh/bin.js
                                    │   --profile web --patch ide.yml --host 127.0.0.1 --port 0 --no-open
                                    │   cwd=<项目根目录>；env: DSH_HOME、DSH_IDE_BRIDGE_URL、DSH_IDE_TOKEN
┌───────────────────────────────────▼───────────────────────────────────────────────────┐
│  Node 子进程（DSH）                                                                     │
│  ├─ dsh web server（webPort，仅 loopback）                                              │
│  ├─ cordis 插件树：… + mcp-client(ide)                                                  │
│  │      └─ StreamableHTTPClientTransport ──http://127.0.0.1:<mcpPort>/mcp──► MCP Server│
│  └─ MCP Server（mcp-ide-server.mjs，复用 profile node_modules 的 @modelcontextprotocol/ │
│        sdk）：注册 ide_* 工具 ──fetch + token──► IDE Bridge Server                      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 进程模型

- **每项目一个 Node 实例**：工具窗口首次打开时懒启动；项目关闭（`ProjectManagerListener` = `DshLifecycleManager` + `Disposer.register(project, panel)`）与 IDE 退出（`AppLifecycleListener` = `DshAppLifecycleListener`）时终止进程树（Step 5 落地）。
- 并发上限 3（`DshRuntimeRegistry.tryAcquire`，超出工具窗口提示），后续可优化为单实例多工作区。
- 插件侧与 Node 侧所有端口随机（`--port 0` / HttpServer 随机端口），无固定端口冲突。

### 2.3 关键技术依据（已通过本机源码/环境验证）

1. **端口发现**：`dsh web` 支持 `--port 0`，由 OS 分配；启动后 stdout 打印 `dsh web: http://127.0.0.1:<port>`
   （**dsh 0.1.5 起为 `http://127.0.0.1:<port>/?token=<t>`**，浏览器鉴权 token）。插件逐行读取 stdout，
   用 `PortParser.parsePort` 取端口、`PortParser.parseUrl` 取**完整启动 URL（含 token）**，随后健康检查（见 §3.4）。
2. **信任围栏**：`/api` 请求的浏览器信任围栏接受 loopback hostname（`dsh-client-connection` `isLoopbackHostname`），故 JCEF 从 `http://127.0.0.1:<webPort>` 加载可正常调用 API；无需 `--trusted-host`。`--host 0.0.0.0` 被 dsh 主动拒绝，天然防外网暴露。
3. **凭据**：以 `DEEPSEEK_API_KEY` 为键管理密钥（真源 = PasswordSafe + **共享**凭据文件
   `<共享根>/.credentials.yaml`，v0.2.4 起 dsh 与插件写同一份文件）。设置页写入 PasswordSafe +
   合并写入共享文件；**不向 dsh 进程注入 `DEEPSEEK_API_KEY` 环境变量**
   （`dsh-credentials-local.resolve()` 为 `inherited env wins`，注入会遮蔽 Web UI 写入并使 `assertUnshadowed`
   拒绝 Web UI 的 set；见 §3.9 与 PROJECT_NOTES §4）。
4. **Profile 合成**：`profiles/<name>/cordis.yml` 初始为 `[]`，由 bundle 层（`package.json` 的 `dsh.profile.bundles`）+ `cordis.patch.yml` 用户层 + `--patch` 覆盖层合成。插件以 `--patch <ide.yml>` 注入 mcp-client（`insert:`）与四个共享化行（`- id: <rowId>` 整份覆盖，见 §4.5），不污染用户层。
5. **MCP 客户端**：`@deepseek-ai/dsh-mcp-client` 支持 `transport: streamable-http`；每实例一个 serverName；模型侧工具名为 `mcp__<serverName>__<rawName>`（serverName 须匹配 `^[A-Za-z0-9_-]{1,32}$`）。其依赖 `@modelcontextprotocol/sdk` 存在于 dsh 安装树的 `node_modules`，由插件附带的 MCP server 脚本按 node 向上查找解析——脚本部署在 `<运行时根>/.dsh-ide-bridge/`，因此**无需任何 junction**（v0.2.4）。
6. **运行时（v0.2.0+ thin 默认）**：固定 `@deepseek-ai/dsh@0.1.5-rc.2` + Node.js 22.x；插件**不打包**约 93MB 的运行时，Windows/macOS/Linux 一律在**首次使用按平台下载**（`runtime-assets.json` 资产地图 → `runtime-<os>-<arch>.zip` + `.sha256`），SHA-256 校验后解压缓存到 `<config>/dsh-idea/runtime/<ver>/`（离线/升级复用；`DSH_IDEA_RUNTIME` 或设置页 runtime-directory 跳过下载；fat 构建 `-Pthin=false` 才内置、免下载）。v0.2.1 起下载走池化 HTTP/2 的 `java.net.http.HttpClient` + 浏览器 UA + 超时/退避重试，带进度/取消（见 §3.2）。

## 3. 模块设计

### 3.1 项目骨架与构建

- Gradle（Kotlin DSL），`org.jetbrains.intellij` **1.17.4**（2.x platform 线未在本网络插件门户解析到且 DSL 不兼容，升级列入技术债 C-1，见 build.gradle.kts 注释与 MILESTONE_REVIEW.md），platformVersion `2024.1`（编译目标，`-PplatformVersion` 可覆盖做前向编译检查），`until-build` 262.*（支持至 IDEA 2026.2），Kotlin 2.0.x，JVM 17（toolchain）。
- 包根 `com.deepseek.harness.idea`，子包：
  - `runtime`：DshProcessManager、DshHomeManager、Bootstrap、PortParser、ProcessLog
  - `bridge`：IdeBridgeServer、BridgeApi（请求/响应模型）、BridgeAuth（token）
  - `mcp`：McpPatchGenerator、McpConfig（端口/工具清单）
  - `review`：SnapshotManager、SnapshotDiff、ReviewPanel（tool window 页）
  - `ui`：DshToolWindow、ToolbarActions、StatusIndicator、LogPanel
  - `settings`：DshSettingsState（`PersistentStateComponent`）、SettingsPage、CredentialImporter
  - `i18n`：DshBundle
  - `util`：PathFilters、IoUtil、VfsActions（EDT 封装）
- `plugin.xml`：toolWindow（id `dsh.toolWindow`）、actions（editor popup 等）、`projectService`/`applicationService` 声明、`ProjectManagerListener`、`AppLifecycleListener`。

#### 本机构建环境（实测，持续更新；详见 docs/PROJECT_NOTES.md §1）

| 项 | 结论 |
|---|---|
| JDK | **必须 JBR 21**：`D:\develop\IntelliJ IDEA 2024.3.4.1\jbr`（jdk-17 会让 `instrumentCode` 报 `Packages does not exist`） |
| Gradle | 用 `tooling/gradle-8.14/bin/gradle.bat`（自带发行版）；勿用系统 gradle-7.2 |
| Gradle 用户目录 | `GRADLE_USER_HOME=D:\develop\gradle-7.2\.gradle\repository`（缓存含 ideaIC 2024.1.7） |
| 运行时开发目录 | `tooling/runtime-dev`（`DSH_IDEA_RUNTIME`）；`build/runtime` 为构建产物 |
| 网络/沙箱 | 自动化环境 pwsh 沙箱拦截工作区外读写 → gradle/npm 需完整沙箱权限；npm 走 npmmirror，下载用 node fetch |
| 一键打包 | `scripts/build-plugin.bat`（双击；自动探测 JBR/Gradle 缓存，`--no-daemon` 防缓存锁） |
| Gradle 缓存坑 | `Failed to create Jar file ...jars-9\...` = 残留 daemon 锁缓存 → `gradle --stop` + 杀残留 java + 删 hash 目录 |

##### Step 1 实测结论（2026-02-11）

- 构建链：`gradlew buildPlugin test verifyPlugin` 全部通过；插件 zip 约 1.6MB；`CredentialImporterTest` 4/4。
- **PasswordSafe（241 版 API，实测）**：经 `Application.getService(PasswordSafe::class.java)` 解析出的接口为
  `setPassword(CredentialAttributes, String?)` / `getPassword(CredentialAttributes): String?`；
  `PasswordSafe.getInstance()` 与旧三参 `setPassword(Project, attrs, String)` 均不可用（Kotlin 编译期验证）。
- **runIde**：沙箱 IDE 加载插件成功（日志 `Loaded custom plugins: DeepSeek Harness (0.1.0)`），平台完整启动、无异常；
  IDE 约 20s 后自行干净退出（自动化环境会话限制，非插件问题）。工具窗口/设置页的交互验证需用户手动 `gradlew runIde` 或安装 zip。

### 3.2 运行时打包与 DSH_HOME 管理

**构建期**（`scripts/build-runtime.mjs`，Gradle task `buildRuntime` 调用；跨平台，可跑在任意主机，默认取当前主机 os/arch 为目标）：

1. 下载 Node.js 22.x（按目标平台选 `win-*.zip` / `darwin-*.tar.gz` / `linux-*.tar.gz`，SHA-256 从同版本官方 `SHASUMS256.txt` 校验）→ 归一化到 `<OutputDir>/node/`（Windows=`node.exe` 顶层；Unix 将 `bin/node` 上移为 `node/node` 并加可执行位）。
2. 以目标 node 的 npm 安装 `@deepseek-ai/dsh@0.1.5-rc.2` 及其依赖到 `<OutputDir>/dsh/`（`--ignore-scripts`；npm 依 `--os/--cpu` 解析目标平台原生 optionalDependencies，如 sharp/koffi/node-addon-require-builtin）。
3. 冒烟验证：读取 `dsh` 版本；`--bundle` 时打包 `runtime-<os>-<arch>.zip`（**zip 根直接为 `node/` + `dsh/`**，排除源包与 npm 缓存），并产出同名 `.sha256` 侧车。
4. 下载/安装均为幂等（存在且校验通过则跳过；`--force` 重建）。

**打包**：`buildRuntime`（`--bundle`）→ `build/runtime-<os>-<arch>.zip` + `.sha256`。瘦身默认（thin）不把约 93MB 的运行时打进插件 jar；`-Pthin=false` 时 `bundleRuntime` 把当前平台 zip 复制为 `build/plugin-runtime/runtime-bundle.zip` 作为插件资源（fat / 离线备选，装上后免下载）。

**运行期**（`DshHomeManager`）：

- 运行时根（`node/` + `dsh/`）：`PathManager.getConfigDir()/dsh-idea/runtime/<version>/`
  （thin 默认本地缺失时按平台下载后解压到此；可用环境变量 `DSH_IDEA_RUNTIME` 覆盖（如 `tooling/runtime-dev`），
  或设置页 runtime-directory 指定已解压的本地运行时——两者都跳过下载）。
- **首次使用自举**（FR-02.1；v0.2.0 起按平台解析）：`hasRuntime()` 在本地缺失且无 override 时：
  a) fat 安装（`-Pthin=false`）→ 从插件资源 `/runtime-bundle.zip` 解压（免下载）；b) 瘦身安装（thin 默认）
  → 经 `RuntimeProvisioner` 按当前平台从 `runtime-assets.json` 资产地图下载 `runtime-<os>-<arch>.zip`，
  SHA-256 比对（`.sha256` 侧车）后安全解压（幂等；zip 兼容顶层单目录前缀剥离 + zip-slip 防护；
  解压目标 `runtime/<version>/`，升级换版本目录复用/重下）。下载 URL 与超时可在设置页覆盖。
- **下载实现与加固（v0.2.1）**：修复首次下载失败的 `NoSuchFileException`（临时文件父目录缺失 → 写盘前先创建
  目录）；下载走**池化、HTTP/2 能力的 `java.net.http.HttpClient`**＋浏览器 User-Agent＋60s 连接超时＋
  可配置读超时＋**指数退避重试**——慢/不稳定网络（如中国大陆访问 GitHub）下首次下载可成功。
- **下载 UX（v0.2.1）**：工具窗口下载**进度条（connecting / verifying / downloading 三态）＋取消**；
  设置页回显**当前平台精确到文件的下载 URL**（一键复制）＋可配置下载超时＋「选择本地运行时 zip…」**离线导入**
  （校验 zip 与 `.sha256` 侧车一致后采用）；失败错误卡显示**失败 URL＋底层原因＋Restart**。
- **平台资产注意（v0.2.1）**：`macos-x64`（Intel Mac）运行时**无法在 GitHub-hosted runner 构建**（Intel macOS
  已退役）→ `runtime-macos-x64.zip` 未随 release 发布，Intel Mac 的下载路径将 404，需在其它主机另行构建并发布；
  macOS arm64 不受影响（Intel Mac 用户可先用 `DSH_IDEA_RUNTIME` / 本地 zip 离线导入）。
- 目录布局（v0.2.4，详见 §4.4）：**共享配置根** `<config>/dsh-idea/dsh-home/`（dsh 用户级配置面唯一真源）
  + **每项目 DSH_HOME** `<共享配置根>/<md5(项目根)前16位>/`（只放 `sessions`/`storages`/`profiles`/`ide.yml`）。
  插件幂等生成每项目的 `profiles/web/`（package.json + cordis.yml）与 `ide.yml`；dsh 首次启动时
  自愈创建 `profiles/node_modules` junction 指向运行时 dsh 树（实测验证，这是 dsh 自己维护的、与插件无关）。
- 生成运行期文件：共享凭据文件（PasswordSafe 合并镜像）、共享 `settings.yaml` 占位（内测声明 acknowledge）、
  `ide.yml`（patch：mcp-client + settings/credentials/agent-presets/skill-filesystem 四段指向共享根）。
- 初始化顺序：校验/下载运行时（首次必要时下载+解压；v0.2.1 工具窗口进度条/可取消）→ 部署全局 MCP 脚本
  → 一次性共享配置迁移与遗留清理 → 生成每项目 DSH_HOME 骨架 → 合并写入共享凭据 → 写 patch → 启动进程 → 健康检查。

### 3.3 DshProcessManager

- `ProcessBuilder`：`[<node>, <dsh>/lib/bin.js, --profile, web, --patch, <ide.yml>, --host, 127.0.0.1, --port, 0, --no-open]`（`<node>` = `node/node.exe` 或 `node/node`，见 `Platform.nodeBinName`）；`directory = 项目根目录`；env：`DSH_HOME=<dsh-home>`、`DSH_IDE_BRIDGE_URL=http://127.0.0.1:<bridgePort>`、`DSH_IDE_TOKEN=<random>`；**不注入 `DEEPSEEK_API_KEY`**（见 §2.3/§3.9）；`redirectErrorStream=true` 或分别捕获。
- **参数列表直传，不走 shell**（兼容路径含空格/中文）。
- stdout 逐行读取：匹配 `dsh web: http://127.0.0.1:(\d+)` → 记录 webPort 与 launchUrl → HTTP 健康检查
  （超时 10s，重试 ≤10 次间隔 500ms）→ 回调通知工具窗口加载。
  **dsh 0.1.5 起**：健康检查须携带启动 URL 的 `?token=`（否则 `/` 返回 **401**），并**禁止跟随重定向**
  （带 token 的 `/` 正常返回 **303**）；`200..399 || 401` 均视为"服务已就绪"，
  JCEF 加载与"外部浏览器打开"都使用带 token 的 URL（`DshProcessManager.launchUrl` / `webUrl()`）。
- 崩溃/退出监听：非预期退出（无 `stop` 标记）→ 通知 + 指数退避自动重启（500ms/2s/5s，≤3 次）→ 手动"重启"按钮；日志写入插件日志 + 工具窗口日志页。
- 停止：`stop(reason)` → `process.destroy()` + 平台无关的进程树终止（Windows 用 `taskkill /PID <pid> /T /F` 兜底）→ 清理状态。
- 状态机：`STOPPED → STARTING → RUNNING → STOPPED | CRASHED`；`CRASHED` 可 `RESTARTING`。

### 3.4 JCEF 工具窗口

- `JBCefBrowser` 放入 tool window content；加载 URL = 运行中实例的 webPort（未启动先显示启动页/进度，就绪后 loadURL）。
- 工具栏动作：附加项目工作区（FR-04.2，MVP：启动后自动把项目根注册为默认工作区，见 §5 启动链路）、外部浏览器打开、重启 dsh、审查改动（打开 review 页）、设置、日志。
- JCEF 不可用（`JBCefBrowser` 初始化异常）：占位页 + "外部浏览器打开"。
- 关闭工具窗口不终止 Node（保留会话）；项目关闭才终止。

### 3.4.1 默认工作区预注册（FR-04.2 实测落地）

dsh 的 workspace 是**显式注册制**：`storages/workspace.json` 无记录时，web UI 顶部显示
"选择一个工作区开始"，不会自动把进程 cwd 设为工作区（实测 dsh 0.1.1-rc.2，`workspaceIds: []`）。

插件在 dsh 健康检查通过后调用内部 RPC `POST /api/workspace/create`：

```json
{"type":"client-request","rpcId":"<uuid>","method":"workspace/create",
 "payload":{"args":{"request":{"path":"D:/proj/MyApp"}}}}
```

> **dsh 0.1.5-rc.2 契约变更（v0.2.3 适配，均实测）**：
> ① 方法名点号 → 斜杠（`workspace/create`；旧的 `workspace.create` 返回 **404**）；
> ② `payload` 必须"恰好含一个 plain-object `args`"（否则 `gateway/internal`）；
> ③ `args` 内再包一层 `request`（否则 `gateway/arguments-invalid: missing "request"; unexpected "path"`）；
> ④ **`workspace/list` 已移除** → 显示顺序改读 `storages/workspace.json`（**v2 结构**：`global.workspaceIds` + `tables.workspaces`）。
> 完整实测见 `docs/PROJECT_NOTES.md`「dsh 0.1.5-rc.2 升级」。

- **鉴权**：0.1.1 时 loopback 信任围栏放行、无需鉴权头；**0.1.5 起 `/api` 一律需要浏览器鉴权 cookie**
  （`GET /?token=` → 303 + `Set-Cookie: dsh-auth-…`，`Authorization: Bearer` 与 query token 均不被接受），
  `WorkspaceInitializer.bootstrapSessionCookie` 先换 cookie 再调 API；
- **幂等**：同路径重复调用返回既有 workspace（`created:false`），不重复创建；
- 实现：`WorkspaceInitializer.ensureWorkspace(webUrl, projectPath, homeDir)`，在
  `DshProcessManager.waitHealthy` 置 RUNNING 后调用；失败仅日志降级，不阻塞 UI；
- 验证：`WorkspaceInitializerTest` + `WorkspaceInitializerSmokeTest`（真实 dsh，切换项目场景）
  + `DshBootstrapSmokeTest`（真实 dsh 启动后断言 `workspace.json` 出现项目路径）。

**切换项目修复（v0.1.3-dev 实测）**：两处修正——

1. **工具窗口 content 残留（根因）**：同窗口切换项目时 IDEA 复用工具窗口，旧项目 content
   （面板 + DSH 日志页）未清理，新项目 createToolWindowContent 叠加 → 出现**两个主面板**，
   用户选中旧面板即显示旧项目工作区。修复：`createToolWindowContent` 开头先
   `contentManager.removeContent(old, true)` 清空全部旧 content（触发旧面板 dispose → 杀其
   dsh 进程）；`DshToolWindowPanel.dispose()` 加 AtomicBoolean 幂等位。
2. **幂等 create 不改变顺序**：显示顺序中，新项目若已是既有 workspace 则保持原位置；为让当前项目稳定显示在
   列表最前，`ensureWorkspace` 在 create 成功后调用 `workspace/insertBefore` 把当前项目挪到最前。
   **dsh 0.1.5 起**：新建 workspace 会被 dsh **自动置顶**，但**幂等 create（已存在）仍不改变顺序**，
   故该步骤仍然必要；顺序来源由已移除的 `workspace.list` 改为读 `storages/workspace.json`
   （`waitWorkspaceOrder` / `readWorkspaceOrder`，`samePath` 规范化路径比较，兼容 v1 顶层 / v2 `global` 结构）。

真实 dsh 冒烟验证：A→B 切换后 B 在 `workspaceIds[0]`，切回 A 后 A 回到最前。

### 3.5 IDE Bridge Server（Kotlin）

- JDK `com.sun.net.httpserver.HttpServer`，绑定 `127.0.0.1` 随机端口；`X-DSH-IDE-Token` 校验（常量时间比较，SHA-256 摘要后 `MessageDigest.isEqual`）。
- JSON 序列化用自研 `JsonCodec`（`com.deepseek.harness.idea.util`，无第三方/平台依赖）。**背景**：2024.1 无
  `com.intellij.util.json.JsonUtil`，原用平台自带 Gson（`com.google.gson.Gson`，util-8.jar）；v0.1.1 起改为自研
  实现（Gson 正被 JetBrains 逐步移出平台，且 `until-build` 放宽到 262.* 后需避免平台库差异，见 PROJECT_NOTES §3/§4）。
- 线程模型：`Executors.newCachedThreadPool`；VFS/PSI 操作经 `ReadAction.compute` 切后台线程安全读取；文件打开经 `ApplicationManager.getApplication().invokeAndWait` 切 EDT。
- 端点（均为 JSON，见 §4.1）；`selection`/`open-files`/`project-tree` 基于活动编辑器与 VFS：
  - 活动编辑器取 `FileEditorManager.selectedEditor as? TextEditor` 的 `Editor`（`Document` 无 selectionModel，选中状态在 Editor）；
  - 语言取 `LanguageUtil.getLanguageForPsi(project, vf)`（2024.1 无 `getLanguageForFile`）；
  - 文档修改态用 `FileDocumentManager.isDocumentUnsaved(doc)`（2024.1 `Document` 无 `isModified`）。
- 生命周期：与 Node 实例同生命周期（项目维度）；token 每次启动随机。

### 3.6 MCP 桥接

**MCP Server（`mcp-ide-server.mjs`）**：

- **全局唯一一份**，部署在 `<运行时根>/.dsh-ide-bridge/mcp-ide-server.mjs`（v0.2.4）：脚本在该目录下
  `import '@modelcontextprotocol/sdk/...'` / `'zod/v4'` 时，Node 的 ESM 解析会向上命中
  `<运行时根>/dsh/node_modules/`（dsh 自身安装树），因此**不需要任何 `node_modules` 链接**。
  运行时根不可写时降级为 `<共享根>/.dsh-ide-bridge/` + 那里唯一一个链接。
- 用 SDK `StreamableHTTPServerTransport` 起 `127.0.0.1:<mcpPort>`；mcpPort 随机（`--port 0` 或 HttpServer 自选）。
- 注册工具（raw name → 参数 → 调 IDE Bridge，带 token）：
  - `ide_get_selection` → `GET /selection`
  - `ide_get_open_files` → `GET /open-files`
  - `ide_get_project_tree`（参数 `depth?`）→ `GET /project-tree`
  - `ide_get_sent_selection` → `GET /sent-selection?latest=1`
  - `ide_open_file`（参数 `path`）→ `POST /open-file`
  - `ide_reveal_file`（参数 `path`）→ `POST /reveal`
- 错误语义：Bridge 不可达 → 工具返回结构化错误（`{error: "ide bridge unreachable"}`），不抛未捕获异常导致 MCP 连接中断。

**patch 注入（`ide.yml`）**：

- 由插件生成（`McpPatchGenerator`），内容为 cordis loader patch 条目数组。**实测语法**（dsh 0.1.1-rc.2；**0.1.5-rc.2 冒烟复验仍适用**——strict patch 启动 + 6 工具注册通过）：`--patch` 覆盖层只能修改已有条目或 `insert` 新增；新增 mcp-client 实例须用 `insert` 列表，且 `name` 字段必须显式声明插件包名：

```yaml
# ide.yml（McpPatchGenerator 生成，mcpPort 动态填入）
- insert:
    - id: mcp.ide
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: ide
        transport: streamable-http
        url: http://127.0.0.1:<mcpPort>/mcp
        toolCallTimeoutMs: 60000
        reconnect:
          enabled: true
          maxAttempts: 3
```

- 模型侧工具名：`mcp__ide__ide_get_selection` 等（raw name 前缀 `mcp__<serverName>__`）。
- MCP server（`mcp-ide-server.mjs`）**全局部署一份**于 `<运行时根>/.dsh-ide-bridge/`，依赖由
  `<运行时根>/dsh/node_modules` 天然向上解析（v0.2.4 起不再建任何 junction，见 §3.6/§4.4）。
- `failOnStartupError: true`（测试/诊断形态）：MCP 连接或工具同步失败即拒绝启动，用于冒烟验证。

### 3.7 代码上下文发送

- 编辑器右键动作"发送选中代码到 DSH"（`SendSelectionAction`，注册于 `EditorPopupMenu`，见 plugin.xml `<actions>`）：
  1. `ReadAction` 读选中文本/文件/语言（≤64KB，超出截断并注明 `…(已截断)`）；
  2. **直接写入 Bridge 的 sent-selection 队列**（`SentSelectionQueue`：容量 ≤10 条、单条 ≤64KB，环形淘汰）——智能体可随时经 `ide_get_sent_selection` 取回，**必达**；
  3. 聚焦工具窗口 + 经 `ComposerScripts.build(...)` 注入预填 composer（**v0.2.4 起兼容 dsh 0.1.5 的 Lexical
     contenteditable**，见 §3.7.1），结果经 JBCefJSQuery 回传 `injected` / `notfound` / `failed`；
  4. 注入失败（未运行/找不到输入框/写入未生效）→ 系统剪贴板 + 通知"请粘贴到输入框（代码已就绪）"。

#### 3.7.1 composer 注入（v0.2.4 修复，真实页面 CDP 实测）

dsh 0.1.5 的输入框是 **Lexical `contenteditable`**：
`<div data-lexical-editor="true" role="textbox" contenteditable="true">`。旧的
`document.querySelector('textarea')` 命中 0，两个动作（发送选中代码、一键解释）因此"没反应"。

`ComposerScripts`（纯函数，`ComposerScriptsTest` 锁定契约）实现：

| 环节 | 做法 | 实测结论 |
|---|---|---|
| 定位 | `textarea` → `[data-lexical-editor="true"]` → `[contenteditable="true"][role="textbox"]` → `div[contenteditable="true"]`，8s 内每 300ms 重试 | 输入框**只在进入会话后**才渲染；页面停在"选择工作区"时命中 0 |
| 写入 | contenteditable 用 `document.execCommand('insertText')`（派发 `beforeinput`，Lexical 据此同步内部状态）；`textarea` 用原生 setter + `input` 事件；前者失败退回合成 `paste` | insertText ✅；合成 `beforeinput` ❌；纯改 DOM ❌ |
| 回读验证 | 读 `[data-lexical-text="true"]` 节点文本，**轮询 ≤2s** | 写入**当拍**根元素 `innerText`/`textContent` 可能为空 → 立即回读会误判失败 |
| 自动提交 | 派发 `KeyboardEvent('keydown', {key/code/keyCode/which: Enter})`，轮询编辑器清空判 `submitted`；否则点 `button[aria-label="Send message"/"发送消息"]`（绝不用 class 通配） | Enter 路径 501ms 内提交成功 |

结果语义：`injected`（已填入）/ `submitted` / `blocked`（已填入但需手动回车）/ `notfound` / `failed`；
仅后两者降级剪贴板 + 通知（不再"脚本已下发"即乐观提示）。

#### 3.7.2 重复引用防护（v0.2.4，用户截图实测）

dsh 的输入框是**累积**内容：注入是"追加"而非"替换"。右键菜单双击（或快捷键连按）会让同一条引用
被追加两遍——实测现象 `@…application.yml#L5-15@…application.yml#L5-15`。四层防护：

1. **编辑器侧（主）**：`ComposerScripts` 写入前先判断输入框**是否已包含**同一引用，已包含则直接
   `injected` 返回、不再写入。判定要点（均为真实页面实测得出）：
   - 判重必须**忽略全部空白**再比较：dsh 会把 `@路径` 渲染成引用 chip
     （`<span data-composer-text-ref data-lexical-text="true">`），chip 的 `textContent` 与相邻文本节点
     之间**不带空白**，按"空白分词 / 带边界匹配"会判成"不存在"从而重复注入；
   - 判重必须要求**两边都非空**：空编辑器回读为空串，`""` 被任何串包含 → 会把"什么都没写进去"
     误判为成功；
   - 回读内容**长度量级相近**：防止只读到 `@…/resources/` 这类片段就误判"已存在"而漏写。
2. **写入路径唯一（关键）**：只走 `document.execCommand('insertText')` **一条路径**。
   ⚠️ 反面教训：曾在其后按"立即回读是否出现目标文本"决定**是否再补一次 `paste`**，而 `insertText`
   是**同步生效、Lexical 异步更新 DOM** 的——回读当拍为空 → 判定"没写进去" → 又派发 `paste`
   → **同一份写了两遍**（用户看到 `@a#L7-11@a#L7-11`，且表现为"一次点击就出现两份"）。
3. **写入重试**：Lexical **空编辑器上首次 `insertText` 可能整段不生效**（实测：读回仍为空），因此
   写入后"轮询 ≤1.2s 确认 + 8s 窗口内重试"，并额外用"内容长度是否增长"兜底判定。
4. **面板侧**（`SendSelectionRefs`）：同一引用在 `SendSelectionRefs.DEDUPE_WINDOW_MS`（1200ms）内
   再次到达即跳过注入；引用本身仍写入 Bridge 的 sent-selection 队列（智能体侧不丢上下文）。

> 实测验证（真实 dsh 0.1.5 页面 + headless Chromium/CDP，用户短路径
> `@E:/code/cfca/cfcaSDKDemo/pom.xml#L7-11`）：
> - 修复前（双写）：**一次点击即出现两份**；
> - 修复后：`first: injected` → `second: skipped-existing` → **finalText 中引用出现次数 = 1**。

#### 3.7.3 关于"文件引用 chip"（dsh 原生能力，当前**无法**用于文件路径）

dsh 0.1.5 的输入框会把 **文本形态** 的引用渲染成 chip，规则见
`dsh-client-ui-conversation/lib/client.js:12167-12201`：

```js
const TEXT_REF_RE   = /(^|\s)([/@])([\w-]+)/g;                    // @/ + 单词字符，且名字必须在触发词的词表里
const FOLDER_REF_RE = /(^|\s)(@(?:"[^"\n]*\/|[^\s"]+\/))/g;        // 只认"以 / 结尾"的 token（@dir/ 目录引用）
```

实测（真实 dsh 0.1.5 页面 + CDP）：

| 注入文本 | 是否渲染 chip |
|---|---|
| `@E:/code/proj/`（目录，以 `/` 结尾） | ✅ 渲染 |
| `@E:/code/proj/application.yml#L5-15`（文件 + 行号） | ❌ 不渲染（`FOLDER_REF_RE` 要求结尾是 `/`） |
| `@name`（配合前端词表命中） | 视词表而定，插件无法为此注入词表条目 |

**结论**：插件注入的"文件 + 行号"引用**在机制上无法变成 chip**，只能保持 `@绝对路径#L起始-结束` 的
紧凑文本形态（用户看到的"文件名 chip + 行号"来自 dsh 自身 UI 对**目录**引用的渲染，不是文件引用）。
如果上游 dsh 将来支持文件引用 chip，插件只需继续注入同一文本即可自动受益（无需改契约）。

- **紧凑文件引用**（v0.5.4，用户反馈迭代）：注入内容仅 `@绝对路径#L起始-结束` + 尾随换行
  （`SendSelectionRefs.compactReference`），**无提示语、无代码本体**；注入后光标停在末尾，
  可直接输入问题。完整选中代码仍写入 Bridge sent-selection 队列
  （智能体可经 `ide_get_sent_selection` 取回，或经 fs 工具读文件对应行）。实测确认：dsh 输入
  触发菜单仅注册了 `/` 源，`@` 前缀（`roster.length===0`）不会弹菜单，可安全作为引用前缀。
- **技术边界（实测 dsh 0.1.1-rc.2）**：dsh 输入框**不支持**输入态"文件引用 chip（文件名+行号+X 删除）"——
  `@`/`/` 菜单仅注册了 workspace/command/skill/subagent 等源，无文件源；`fileMentions` 渲染仅匹配
  "本轮工具产出文件"（`producedFileMentions`），对用户选中发送的代码不生效。故采用紧凑引用文本方案。
- 智能体侧兜底：`ide_get_sent_selection` 随时可取最近推送的代码（即使注入失败也不丢上下文）。

### 3.8 审查面板（Review）

- **基线快照**（`SnapshotManager`）：工具窗口首次打开时执行；遍历项目根（`VfsUtilCore.visitChildrenRecursively` + `VirtualFileVisitor`，`visitFile` 返回 **Boolean**——false 跳过目录 children）：
  - 忽略：`.git`、`node_modules`、`build`、`out`、`.idea`、`target`、`dist`、`.gradle`、隐藏文件（`.` 前缀）及 >1MB 文件（`PathFilters`）；
  - 记录 `path → content(MD5 + 原始字节)`，内存字节 LRU ≤200MB（超限最旧条目字节落盘 `<md5>.bin`，md5/元数据恒在内存，diff/还原按需回读）；
  - 落盘：插件临时目录（`FileUtil.getTempDirectory()/dsh-idea/snapshots/<project>/`，不污染项目）；`index.txt` 存元数据加速重建。
- **审查**（`ReviewChangesAction` 打开 `ReviewDialog`）：对比当前盘面与基线 → 三类（modified/new/deleted）→ 按钮"查看 Diff"（`DiffManager.showDiff` + `SimpleDiffRequest`，基线文本 vs 当前文件，NEW/DELETED 用空侧）；动作：还原该文件（基线覆盖当前，NEW=删除、DELETED=重建）、还原全部、接受（忽略，丢弃基线）、重新基线（全量重扫）。
- **刷新**：打开审查前对项目根 `VfsUtil.markDirtyAndRefresh(false, false, true, root)`（4 参签名，2024.1）。
- **注意**：dsh 直接写盘，"接受改动"= 丢弃快照（无需回写）；还原 = 用快照覆盖当前文件（VFS 写）。

### 3.9 设置页

- `DshSettingsState`（`PersistentStateComponent`，application 级，跨项目共享）：
  - `model`（`deepseek-chat` 默认 / `deepseek-reasoner`）与 `baseUrl`（默认 `https://api.deepseek.com`）：
    **插件侧记录项**，当前不写入 dsh 配置；模型/供应商的真实配置在 DSH Web UI「Settings → Models」
    （写入共享 `settings.yaml` 的 `llm-pi-ai` / `llm-deepseek` 段）。设置页文案已注明，避免 UI 撒谎。
  - `dshHomeOverride`（高级，默认 null → 用 `PathManager.getConfigDir()/dsh-idea/dsh-home`，即
    `DshHomeManager.sharedConfigRoot()`；字段保留以兼容既有 XML，目前未接线到 UI）
  - `logLevel`
  - 运行时相关（v0.2.0+，行为见 §3.2）：runtime-directory（指向本地已解压运行时，配置后跳过下载，等价 `DSH_IDEA_RUNTIME`）；运行时下载 URL（v0.2.1 起设置页回显当前平台**精确到文件的 URL** + 一键复制）与下载超时（可配置）；「选择本地运行时 zip…」离线导入（校验 zip 与 `.sha256` 侧车）。
  - **共享配置目录（v0.2.4）**：只读展示 `DshHomeManager.sharedConfigRoot()` +「打开」按钮，便于用户
    直接查看/编辑 dsh 的共享配置（dsh 对 `settings.yaml` 有热重载，外部编辑会即时生效）。
- **API Key（`DshCredentials`，PasswordSafe 应用级）**：
  - 读写 `PasswordSafe`（应用级凭据条目）。
  - **脱敏回显**（用户要求"前 6 位 + 中间脱敏 + 后 6 位"）：`DshCredentials.maskApiKey(key)` 前 6 位 + `******` + 后
    6 位（≤12 位整段脱敏）；设置页用 `JBTextField` 回显脱敏串（不能用 `JBPasswordField`，其把文本渲染成掩码点，
    看不到脱敏串）；`isModified`/`apply` 以"字段内容 ≠ 当前脱敏串"判定用户是否真的改了 key，避免把脱敏串写回密码库。
  - **回显兜底**：`readApiKey() ?: readApiKeyFromSharedDocument(共享凭据文件)`——PasswordSafe 读不到
    （如 IDE 密码库未解锁）时回退到共享 `.credentials.yaml`（按 `refs:` 段定位，避免误命中 `records` 内的同名键）。
- 应用行为：写 PasswordSafe + **合并写入**共享凭据文件（`syncCredentialsAll` → `syncCredentials` →
  `YamlText.upsertRef`，只替换 `refs.DEEPSEEK_API_KEY`，保留其它 `refs` 与整个 `records`）；
  **不向 dsh 进程注入 `DEEPSEEK_API_KEY` 环境变量**（见 §2.3）；提示"重启会话生效"；"重启 dsh"按钮。
- **Web UI 改 key 无需同步（v0.2.4）**：dsh 与插件读写**同一份**共享凭据文件
  （`ide.yml` 的 `- id: credentials` patch 指向共享根），Web UI 的改动立即对插件可见，反之亦然。
  旧实现（`DshCredentialsSync` + WatchService 跨目录回写）已废弃：其扁平 layout 整份覆盖会抹掉
  `records` 与其它 provider 的 `refs`。该类保留为空操作以免破坏外部调用点，文档见 §4.4。
- `CredentialImporter`：读用户本机 source 凭据文件的 `DEEPSEEK_API_KEY`（解析仅取该键），不存在/无键 → 提示。

### 3.10 国际化

- `messages/DshBundle.properties`（英文默认）+ `DshBundle_zh_CN.properties`（中文）；`DshBundle.message("key", args...)` 封装 `ResourceBundle`（UTF-8，`ResourceBundle.Control` 处理）。
- 覆盖：工具窗口标题/动作/状态、设置页、通知、审查面板、错误提示。Web UI 文案由 dsh 自带（不本地化）。

### 3.11 运行日志一键解释（FR-11）

- 运行控制台右键动作"DSH 一键解释"（`SendLogExplanationAction`，注册于 `ConsoleView.PopupMenu`）：
  - 组 id 两版本源码核实：2024.1.7 `ConsoleViewImpl.java:93` 与 2026.2 `ConsoleViewImpl.kt:1668`
    均为 `CONSOLE_VIEW_POPUP_MENU = "ConsoleView.PopupMenu"`（弹窗挂在控制台 editor 上，
    `CommonDataKeys.EDITOR`/`PROJECT` 可用，选中文本在 `selectionModel`）；
  - `update()`（BGT）仅在有选中文本时显示；`actionPerformed` 用 `ReadAction` 读选中文本，
    `ExplainLogComposer.buildMessage(prefix, log)` 组装消息（本地化指令 + 空行 + 日志，
    >64KB 截断并注明，纯函数可单测），经 `DshToolWindowPanel.find(project)` 取面板后调用 `sendQuestion`；
  - 面板为 null（工具窗口从未打开）→ 剪贴板 + 通知。
- `DshToolWindowPanel.sendQuestion`（自动提交，**不等待用户确认**）：
  1. 在途守卫（`AtomicBoolean` 防双击）+ token 化回调（`AtomicLong` 防旧回调串台）；
  2. 激活工具窗口并 `setSelectedContent(content 0)` 切到对话页（避免停在日志 tab）；
  3. JCEF 注入（`ComposerScripts.build(text, submit = true, ...)`，见 §3.7.1）：写入 composer →
     派发 `keydown Enter`（带 `keyCode/which`；dsh composer 实测：非 shift 的 Enter → `keyboard.submit`，
     智能体忙时默认入队仍送达）→ 轮询编辑器清空 = `submitted`；未清空则回退点击
     `button[aria-label="Send message"/"发送消息"]`（**不用 class 通配**，避免误点运行中的"停止"按钮）；
  4. 结果经 **JBCefJSQuery** 回传 `submitted / blocked / notfound / failed`：`submitted` → 通知已发送；
     `blocked` → 消息留在输入框 + 提示手动回车；`notfound`/`failed` → 剪贴板兜底 + 通知；
     `setupJsQuery` 必须在 `loadURL` **之前**创建（CEF message router 在页面加载时注入
     `window.<funcName>`；创建失败降级为无验证乐观提示）。
- 技术边界：发送按钮 aria-label 为 "Send message" / "发送消息"（`t("input.send")`）。
  **0.1.5 起 composer 为 Lexical contenteditable**——v0.2.4 已完成适配并**在真实页面（CDP）验证**，
  见 §3.7.1（历史状态：0.1.1 为 React 受控 `<textarea>`，用原生 setter + `input` 事件）。

## 4. 接口契约

### 4.1 IDE Bridge HTTP API

| 方法 | 路径 | 鉴权 | 请求 | 响应 |
|---|---|---|---|---|
| GET | /health | token | — | `{ok, project, pid}` |
| GET | /selection | token | — | `{filePath, language, selection, lineStart, lineEnd}` |
| GET | /open-files | token | — | `{files:[{path, language, modified}]}` |
| GET | /project-tree | token | `?depth=4` | `{roots:[{path,name,type,children}]}` |
| POST | /sent-selection | token | `{id,filePath,language,selection}` | `{id}` |
| GET | /sent-selection | token | `?latest=1` | `{id,filePath,language,selection,ts}` 或 `{error:"empty"}` |
| POST | /open-file | token | `{path}` | `{ok}` |
| POST | /reveal | token | `{path}` | `{ok}` |
| POST | /refresh | token | `{paths?: string[]}` | `{ok, refreshed, missing}`；省略 paths 时刷新项目根 |

统一错误：`{error: string, code: string}`；未带/错 token → 401。

### 4.2 MCP 工具清单（模型侧名 `mcp__ide__*`）

| raw name | 参数 | 对应 Bridge | 说明 |
|---|---|---|---|
| ide_get_selection | — | GET /selection | 当前编辑器选中 |
| ide_get_open_files | — | GET /open-files | 打开文件列表 |
| ide_get_project_tree | `depth?` | GET /project-tree | 项目结构 |
| ide_get_sent_selection | — | GET /sent-selection?latest=1 | 最近发送的代码 |
| ide_open_file | `path` | POST /open-file | 在 IDE 打开（P1） |
| ide_reveal_file | `path` | POST /reveal | 项目树定位（P1，打开前同步刷新） |
| ide_refresh_files | `paths?` | POST /refresh | 同步刷新指定文件/目录；省略时刷新项目根 |

### 4.3 进程启动与环境

```
node.exe <dshBin>/lib/bin.js \
  --profile web --patch <dshHome>/ide.yml --host 127.0.0.1 --port 0 --no-open
cwd      = <项目根目录>
env      = DSH_HOME=<dshHome>
           DSH_IDE_BRIDGE_URL=http://127.0.0.1:<bridgePort>
           DSH_IDE_TOKEN=<randomToken>
           # 注意：不注入 DEEPSEEK_API_KEY（dsh-credentials-local inherited env wins 会遮蔽 Web UI 写入）
stdout   = 逐行读取；含 "dsh web: http://127.0.0.1:<webPort>"
```

> 注意：`--patch` 是启动器选项，必须位于 web 应用选项 `--host`/`--port` 之前；
> 放在后面会被 web 应用当作未知选项拒绝（实测 dsh 0.1.1-rc.2）。
> `--no-open`：dsh web 默认会把 Web UI 打开到系统默认浏览器；内嵌于 IDE 工具窗，显式禁用（用户要求，v0.1.3-dev）。

### 4.4 运行时与目录布局（共享配置根 + 每项目 DSH_HOME，v0.2.4）

```
<PathManager.getConfigDir()>/dsh-idea/
├── runtime/<version>/                     # 运行时（v0.2.0+ thin：首次按平台下载+SHA-256 校验；全局共享，不按项目）
│   ├── node/                              # Node.js 运行时（按平台：node.exe / node，归一化布局）
│   ├── dsh/                               # npm 安装的 @deepseek-ai/dsh 树（含全部依赖）
│   ├── dsh/node_modules/                  #   MCP 脚本依赖的解析落点
│   └── .dsh-ide-bridge/mcp-ide-server.mjs #   ★ MCP server 脚本（全局唯一一份；v0.2.4）
└── dsh-home/                              # ★ 共享配置根（所有项目共享；v0.2.4 起 = DshHomeManager.sharedConfigRoot()）
    ├── settings.yaml                      #   dsh 设置文档唯一真源（模型 provider/自定义模型、语言、内测声明）
    ├── .credentials.yaml                  #   dsh 凭据唯一真源（version:1 + refs/records）
    ├── .agent-presets/                    #   Agent 预设（用户可作者化根）
    ├── skills/                            #   个人技能（项目技能仍在 <项目>/.dsh/skills）
    ├── .plugin-layout-version             #   插件写入：一次性布局迁移完成标记
    ├── migrated/<md5(项目路径)前16位>/     #   迁移备份（旧的项目级 settings.yaml / .credentials.yaml）
    ├── sessions/ storages/ profiles/      #   仅历史残留（v0.1.2 全局 DSH_HOME；sessions 由迁移器读取）
    └── <md5(项目根目录)前16位>/            # ★ 每项目 DSH_HOME（**只承载数据面**）
        ├── ide.yml                        #   patch（每项目；含动态 mcpPort）
        ├── profiles/web/                  #   物化 web profile（package.json + cordis.yml）
        ├── profiles/node_modules/         #   dsh 首次启动自愈创建的 junction → runtime/dsh/node_modules
        ├── sessions/ storages/            #   会话 + 工作区注册表 + 投影缓存（dsh 自动创建；每项目独立）
        └── attachments/                   #   图片附件对象存储（<DSH_HOME>/attachments/v1/objects/<sha256>）
```

> **为什么这样切分（v0.2.4，用户实测驱动）**：dsh 的**用户级配置面**天然只有一份（Web「Settings → Models」
> 写的 `llm-pi-ai` / `llm-deepseek` 段、语言 `locale`、API Key、Agent 预设、个人技能），而**数据面**
> （`sessions` / `storages` / 附件）必须按项目隔离才能保证"切项目后工作区不残留"（v0.1.3-dev 修复）。
> 因此：配置面经 `ide.yml` 的 `- id: settings` / `- id: credentials` / `- id: agent-presets` /
> `- id: skill-filesystem` patch 指向共享配置根，**dsh 直接读写共享文档**；数据面留在每项目 DSH_HOME。

> **共享配置根（`DshHomeManager.sharedConfigRoot()`，= `dsh-home/`）**：`settings.yaml`、
> `.credentials.yaml`、`.agent-presets/`、`skills/` 的唯一真源。路径沿用 v0.1.3-dev 以来的全局根，
> 以免丢失用户既有的语言偏好与内测声明接受状态。插件不再参与配置同步：`syncCredentials()` 只做
> **合并写入**（替换 `refs.DEEPSEEK_API_KEY`，保留其它 `refs` 与整个 `records` 段）。

> **v0.2.3 的致命缺陷（已修复）**：旧实现把共享根配置 `copyGlobalConfigTo` **覆盖**到每项目子目录，
> 于是 dsh 写进子目录的用户配置（自定义模型、语言）在下次启动被覆盖而**消失**；同时 `- $settings:` /
> `- $credentials:` 这种 patch 语法被 dsh 拒绝（`patch: id is required for non-insert patches`），
> 全局化机制从未生效。详见 `docs/PROJECT_NOTES.md`「v0.2.4 dsh 配置共享化」。

> **DSH_HOME 按项目隔离（v0.1.3-dev，用户实测驱动）**：`DshHomeManager.homeDir(projectPath)` 用
> `MD5(projectPath)` 前 16 位派生目录。dsh 的工作区注册表（`workspace.json`）与会话数据因此按项目
> 隔离：切换项目后，新 dsh 进程的工作区只含当前项目，从机制上杜绝"显示其他项目工作区"
> （实测：此前共享 DSH_HOME 时，仅"已打开过的旧项目"复现——dsh 记住了其历史会话状态；全新项目无
> 此问题）。API Key：**不向 dsh 进程注入 `DEEPSEEK_API_KEY` 环境变量**（dsh-credentials-local
> 的 `inherited env wins` 会遮蔽 Web UI 写入，且 `assertUnshadowed` 拒绝 Web UI 的 set）。
> key 真源 = PasswordSafe + 共享 `.credentials.yaml`（v0.2.4 起由 dsh 与插件写同一份文件，无需监听同步）。

> **MCP 脚本与 node_modules 链接（v0.2.4 精简）**：`mcp-ide-server.mjs` 只部署一份，位于
> `<运行时根>/.dsh-ide-bridge/`。该位置向上查找 `node_modules` 会命中 `<运行时根>/dsh/node_modules/`
> （dsh 自身依赖树，含 `@modelcontextprotocol/sdk` 与 `zod`），因此**每项目不再需要 `node_modules`
> junction**（旧实现在每个项目 DSH_HOME 顶层建链接，只为让脚本解析 SDK）。运行时根不可写时降级为
> `<共享配置根>/.dsh-ide-bridge/` + 那里**唯一一个**链接。

> **升级迁移（v0.1.3-dev，用户要求）**：旧版（v0.1.2）把 session 存在全局 `dsh-home/sessions/`；
> 升级到按项目隔离后，旧 session 目录仍在全局根但不再被读取。`DshHomeManager.ensureHome` 通过
> `LegacySessionMigrator` 把当前项目的旧 session 目录**原样复制**（保留 `.jsonl.zstd` 压缩格式）到
> 隔离目录 `sessions/<projectKey(cwd)>/`，并**迁移投影缓存** `storages/session_projcache.json`
> （筛选当前项目 identity.cwd 匹配的会话条目，合并写入隔离目录）——dsh 的 `session.list` 用**零 I/O
> 投影缓存**读会话标题，缺缓存时 UI 回退显示 `basename(cwd)`（即项目目录名），迁移缓存后标题立即可见
> （用户实测：历史会话标题全部显示成项目名）。workspace 注册表由 dsh 启动时自动 bootstrap 从 session
> header 重建，无需手工迁移。详见 `LegacySessionMigrator` / `LegacySessionMigratorTest` /
> `LegacySessionMigratorSmokeTest`。

> **共享配置迁移（v0.2.4）**：`SharedConfigMigrator.migrateIfNeeded` 一次性（标记
> `<共享根>/.plugin-layout-version`）把各项目 DSH_HOME 里的 `settings.yaml` / `.credentials.yaml` 与共享
> 文档**合并**（共享侧优先，只补缺：缺失的顶层 namespace、缺失的 `refs` 键与 `records` 条目），原文件
> **移入** `<共享根>/migrated/<hash>/` 备份。合并是**文本级**的（不反序列化 YAML），以保留用户注释、
> 锚点与 `!!js` 表达式；失败时逐项目降级并**不写标记**，下次启动重试。

### 4.5 patch 模板（`ide.yml`）

见 3.6；由 `McpPatchGenerator.generate(mcpPort, sharedConfigRoot)` 生成。**语法要点（dsh 0.1.5-rc.2 实测）**：
`--patch` 是叠加在 bundle 之上的覆盖层，两种形态——`insert:`（新增条目，新增 mcp-client 必须显式写
`name`）与 `- id: <rowId>`（**整份替换**该条目的 `config`，未改字段必须重述）。共享配置化的四行即用后者：

```yaml
- id: settings
  config:
    path: '<共享根>/settings.yaml'
- id: credentials
  config:
    path: '<共享根>/.credentials.yaml'
- id: agent-presets
  config:
    default: standard          # Config.default 必填，整份替换时必须重述
    includeUserRoot: false     # 否则仍会扫描 $DSH_HOME/.agent-presets（按项目隔离的旧根）
    roots:
      - path: '<共享根>/.agent-presets'
        trust: user            # 必须是第一个 user 根：copy()/remove() 只认它
- id: skill-filesystem
  config:
    dshHome: '<共享根>'         # 用户技能根 = <共享根>/skills；项目根 <项目>/.dsh/skills 不变
```

> ⚠️ **不要用 `- $settings:` / `- $credentials:`**：dsh 会报
> `patch: id is required for non-insert patches` 并丢弃整条 patch（v0.1.3-dev ~ v0.2.3 的实际故障）。
> 校验方法：`dsh --profile web --patch <ide.yml> --dump-config`（离线组合，不启动服务）。

## 5. 数据流

1. **启动链路**：工具窗口打开 → `DshProcessManager.start()` → 校验/下载/解压运行时（thin 首次按平台下载，v0.2.1 工具窗口进度条/可取消）→ `syncCredentials()`（PasswordSafe→插件全局凭据文件）+ `ensureHome`（全局配置复制到子目录 + 旧 session/投影缓存迁移）→ spawn node（cwd=项目，`--no-open`）→ 逐行读 stdout 解析 webPort → 健康检查 → `toolWindow.loadUrl(webPort)` → JCEF 加载 Web UI；同时 `DshCredentialsSync` 启动监听子项目凭据文件（dsh Web UI 改 key 时回写全局）。用户对话 → dsh 智能体（fs 工具以 cwd=项目目录读写文件）。
2. **MCP 链路**：智能体调用 `mcp__ide__ide_get_selection` → dsh mcp-client → streamable-http → mcp-ide-server.mjs → fetch+bridge token → IDE Bridge（EDT 读 VFS/PSI）→ JSON 原路返回 → 智能体。
3. **发送代码链路**：编辑器动作 → 读选中 → POST /sent-selection（Bridge 队列）→ 聚焦工具窗口 + JS 注入（失败→剪贴板）→ 智能体经 `ide_get_sent_selection` 或提示文本获取。
4. **审查链路**：打开工具窗口 → 基线快照 → 用户点"审查改动" → VFS 刷新 → 对比 → DiffManager diff → 还原（VFS 写回快照）/忽略/重新基线。

## 6. 边界情况与失败模式

| 场景 | 处理 |
|---|---|
| 端口冲突 | 全随机端口（`--port 0` / HttpServer 随机），无固定端口 |
| Node 崩溃 | 通知（Notifications，Step 5）+ 指数退避自动重启（≤3 次）+ 手动重启；状态机 CRASHED；日志页可查输出 |
| 运行时缺失 | override（`DSH_IDEA_RUNTIME` / 设置页 runtime-directory）指向的本地运行时不存在 → 报错并引导配置；无 override 且本地无缓存 → thin（默认）自动按平台下载（v0.2.1：进度条/取消；失败显示失败 URL+底层原因+Restart），fat（`-Pthin=false`）从插件资源 `/runtime-bundle.zip` 解压 |
| Intel Mac 运行时资产缺失 | GitHub-hosted runner 无 Intel macOS，`runtime-macos-x64.zip` 未随 release 发布 → 首次下载 404（v0.2.1） | 另行构建并发布该资产前，Intel Mac 用户经 `DSH_IDEA_RUNTIME` / 设置页 runtime-directory / 「选择本地运行时 zip…」离线导入规避；arm64 不受影响 |
| API Key 缺失/无效 | 健康检查后会话创建失败 → 工具窗口横幅"请配置 API Key"→ 跳设置；设置应用后提示重启会话 |
| dsh 启动超时（>60s） | 终止并报错，附日志片段；建议检查网络/杀软 |
| 多项目 | 每项目实例（`DshRuntimeRegistry`），并发 ≤3，超出提示；项目关闭即终止（`DshLifecycleManager`） |
| IDE 退出 | `DshAppLifecycleListener.appClosing` 兜底终止全部存活面板（进程树） |
| 项目关闭时任务运行中 | 终止进程并提示"任务可能未完成" |
| JCEF 初始化失败 | 占位页 + 外部浏览器打开 |
| Web UI DOM 变化（注入失效） | 降级剪贴板；`ide_get_sent_selection` 兜底 |
| 大项目 | 快照忽略规则 + 1MB 上限 + 200MB LRU |
| 路径含空格/中文 | ProcessBuilder 参数列表直传 |
| Remote Dev / Gateway | 检测到远程开发环境 → 工具窗口提示不支持 |
| 杀软拦截 node.exe | 启动失败提示 + 日志 + 文档（白名单说明） |
| JCEF 中文输入法异常 | "外部浏览器打开"按钮 |

## 7. 测试策略

### 7.1 单元测试（JUnit，Gradle `test`）

- `PortParserTest`(4)：stdout 行解析（含多行/前缀/异常格式）
- `SnapshotDiffTest`(5)：modified/new/deleted 判定、忽略规则、容量上限
- `McpPatchGeneratorTest`(6)：patch yaml 生成与占位符替换
- `CredentialImporterTest`(4)：用户 source 凭据文件解析 `DEEPSEEK_API_KEY`（临时文件）
- `PathFiltersTest`(5)：忽略规则
- `SentSelectionQueueTest`(5)：环形容量、64KB 截断、id 序（Step 4）
- `DshRuntimeRegistryTest`(3)：并发上限 3、释放名额、幂等（Step 5）
- `JsonCodecTest`(9)：自研 JsonCodec 编解码（v0.1.1）
- `ExplainLogComposerTest`(4)：运行日志一键解释的消息组装（v0.1.3-dev）
- `WorkspaceInitializerTest`：workspace 注册链路（v0.1.3-dev 引入；**v0.2.3 改为** `workspace/create` + 读 `storages/workspace.json` 定序 + `workspace/insertBefore`）
- `DshHomeManagerRuntimeRootTest`(5)：运行时目录优先级（env > 设置页 > 默认）与路径等价 `samePath`（v0.2.3）
- `LegacySessionMigratorTest`(14)：`projectKey` 编码 + 旧 session/投影缓存迁移 + 幂等（v0.1.3-dev）
- `DshCredentialsMaskTest`(10)：API key 脱敏 + 凭据文件解析（v0.1.3-dev）
- `DshCredentialsSyncTest`(6)：Web UI 改 key 回写全局的比对逻辑（v0.1.3-dev）
- `PlatformTest` / `RuntimeAssetsTest` / `RuntimeProvisionerTest`（v0.2.0 引入：平台解析 / `runtime-assets.json` 资产地图 / 下载 + `.sha256` 校验 + 安全解压；v0.2.1 加固：首次下载失败修复、HTTP/2 池化客户端、超时与退避重试、进度/取消状态机，见 §3.2）

> 注：以上各测试括号内数字为其**引入时**的用例口径，非当前值；截至 v0.2.1，`gradle test` 全量（§7.1 + §7.2）累计 **122 个测试、0 失败**。

### 7.2 集成冒烟（Gradle `test` + `DSH_IDEA_RUNTIME`）

- `DshBootstrapSmokeTest`(1)：临时 DSH_HOME + 假凭据，spawn `dsh web --port 0`，断言 stdout 端口 + HTTP 200；
  设置了 `DSH_IDEA_RUNTIME` 时自动执行，否则跳过（CI 无运行时环境）。
- `DshMcpBridgeSmokeTest`(1)（Step 3）：JDK mock bridge + mcp-ide-server.mjs（部署到临时 DSH_HOME，顶层 junction 解析 SDK）+
  `tools/list` 断言 6 个 `ide_*` 工具 + `tools/call` 桥接返回 + dsh web 带 `failOnStartupError: true` patch 启动（连接失败即拒绝启动，能起来即证明 MCP 链路通）。
- `WorkspaceInitializerSmokeTest`(1)（v0.1.3-dev）：真实 dsh 切换项目场景，断言当前项目工作区置顶。
- `LegacySessionMigratorSmokeTest`(1)（v0.1.3-dev）：zstd session 迁移到隔离目录后真实 dsh 工作区自动挂接该 session。
- 本地执行：`$env:DSH_IDEA_RUNTIME="<runtime 目录>"; gradle test`（实测通过；截至 v0.2.1 累计 **122 个测试、0 失败**）。

### 7.3 手工验收（`runIde`，Step 5 执行）

PRD §7 验收清单 9 条（含 v0.1.3-dev 新增"DSH 一键解释"）。

## 8. 实现步骤分解与验收

| 步骤 | 交付物 | 验收 |
|---|---|---|
| Step 0 文档 | docs/PRD.md、docs/DESIGN.md、docs/README.md | 覆盖 §2-§7 全部条目，无未决设计决策 |
| Step 1 骨架 | Gradle 工程、plugin.xml、工具窗口壳、设置页骨架、i18n | `buildPlugin` 成功；`runIde` 可打开工具窗口/设置 |
| Step 2 运行时 | build-runtime.ps1、DshHomeManager、DshProcessManager、JCEF 加载 | 全新实例端到端对话；API Key 生效；进程随项目关闭终止 |
| Step 3 MCP | IdeBridgeServer、mcp-ide-server.mjs、McpPatchGenerator、spike 验证 patch | "当前打开的文件是什么"可答；`tools/list` 6 工具 |
| Step 4 集成 | 发送选中代码动作、审查面板 | 上下文可送达；diff/还原可用 |
| Step 5 加固 | 生命周期、崩溃 UX、日志页、打包、验收清单 | PRD §7 八条全过 |
| Step 6 评审 | 总结、遗留问题、后续规划 | 文档收尾更新 |

## 9. 变更记录

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-09-02 | v0.2.1 | **首次下载可靠性 + 下载 UX + 设置页离线导入（发布版 0.2.0 → 0.2.1）**：① 修复首次使用运行时下载失败——临时文件父目录缺失导致 `NoSuchFileException`，写盘前先创建目录；② 下载改走**池化、HTTP/2 能力的 `java.net.http.HttpClient`**＋浏览器 User-Agent＋60s 连接超时＋可配置读超时＋**指数退避重试**（慢/不稳定网络如中国大陆访问 GitHub 下首次下载可成功）；③ 工具窗口**下载进度条（connecting / verifying / downloading）＋取消**；④ 设置页**回显当前平台精确到文件的下载 URL（一键复制）＋可配置下载超时＋「选择本地运行时 zip…」离线导入**（校验 zip 与 `.sha256` 侧车）；⑤ 失败错误卡显示**失败 URL＋底层原因＋Restart**。`macos-x64` 运行时无法在 GitHub-hosted runner 构建（Intel macOS 退役），`runtime-macos-x64.zip` 未随 release 发布（Intel Mac 下载 404，需另行构建）。测试累计 **122 个、0 失败**；Marketplace v0.2.1 已上传（update id 1159301），待审核 |
| 2026-02-11 | v0.1 | 初稿：依据已确认决策（JCEF 嵌入、内嵌运行时、独立 DSH_HOME、MCP 桥接、中英双语、Windows 优先）编写 |
| 2026-08-19 | v0.2 | Step 2 实现落地：运行时布局改为 runtime/node + runtime/dsh（npm 安装）与 DSH_HOME 分离（junction 自愈）；`--patch` 必须位于 web 应用选项之前（实测 dsh 0.1.0-rc.7）；新增 scripts/build-runtime.ps1 与 buildRuntime 任务 |
| 2026-08-19 | v0.3 | Step 3 MCP 桥接落地：patch 语法修正为 `insert` + 显式 `name` 字段（实测）；新增 IdeBridgeServer/mcp-ide-server.mjs/McpPatchGenerator/DshBridgeManager；DSH_HOME 顶层 node_modules junction 供 ESM 解析 SDK；2024.1 API 勘误（Gson、getLanguageForPsi、isDocumentUnsaved、TextEditor.editor） |
| 2026-08-19 | v0.4 | Step 4 IDE 集成落地：SendSelectionAction（sent-selection 队列直写 + JCEF textarea 注入预填 + 剪贴板降级）、SnapshotManager（200MB LRU + 字节落盘）、ReviewManager/ReviewChangesAction（diff/还原/忽略/重新基线）；API 勘误（VirtualFileVisitor.visitFile 返回 Boolean、markDirtyAndRefresh 4 参、Notification 4 参） |
| 2026-08-19 | v0.5 | Step 5 加固与发布落地：DshLifecycleManager/DshAppLifecycleListener（项目/IDE 关闭终止进程树）、DshRuntimeRegistry（并发 3）、崩溃通知、DshLogPanel 日志页、logLevel 设置、运行时打包自举（runtime-bundle.zip → 插件资源 → 首次解压；实测 106.9MB/解压 62s/可启动） |
| 2026-08-20 | v0.5.1 | 手工测试修复：①工具窗口默认选中主界面（日志 tab 不再抢焦点）；②默认工作区预注册（WorkspaceInitializer 调 dsh RPC workspace.create，UI 打开即选中当前项目；含 DshBootstrapSmokeTest 真实启动断言） |
| 2026-08-20 | v0.5.2 | 发送选中代码增强：输入框注入结构化引用文本（路径 + 行号 + 代码块 + 提示语） |
| 2026-08-20 | v0.5.3 | 紧凑文件引用：注入 `@路径#L起始-结束` + 提示语（不填代码本体） |
| 2026-08-20 | v0.5.4 | 输入框仅 `@路径#L起始-结束` + 换行（去提示语），光标自动落下一行；§3.7 同步更新 |
| 2026-08-20 | v0.5.5 | 工具窗口标题动作区分图标（Settings/Web/Diff/Restart） |
| 2026-08-20 | v0.5.6 | 右键动作图标与插件一致（plugin.xml icon → dsh-toolwindow.svg） |
| 2026-08-20 | v0.5.7 | 插件 Overview / What's New 中英双语（plugin.xml description/change-notes） |
| 2026-08-20 | v0.5.8 | 一键打包脚本 scripts/build-plugin.bat；新增 docs/PROJECT_NOTES.md 知识库 |
| 2026-08-20 | v0.5.9 | Step 6 里程碑评审：新增 docs/MILESTONE_REVIEW.md；§3.1 修正 intellij 版本为 1.17.4（2.x 为技术债）；补 v0.5.2–v0.5.8 变更记录；测试 36/36 复跑通过 |
| 2026-08-20 | v0.1.1 | 兼容修复：`until-build` 251.* → 262.*（IDEA 2026.2 实测安装报错）；`-PplatformVersion` 支持前向编译检查（2026.2 SDK 编译验证）；§3.5 JSON 序列化 Gson → 自研 JsonCodec（移除平台 Gson 依赖）；新增 JsonCodecTest 9 例；测试 36→45 |
| 2026-08-21 | v0.1.2 | 2026.2 JCEF 兼容修复：plugin.xml 新增可选依赖 `com.intellij.modules.jcef`（JCEF 拆分内置插件，见 PROJECT_NOTES "2026.2 JCEF 拆分"）；JCEF 失败提示增强（异常 + 排查建议）；测试 45/45 复跑通过 |
| 2026-08-22 | v0.1.3-dev | 运行控制台"DSH 一键解释"（FR-11）：`SendLogExplanationAction`（注册于 `ConsoleView.PopupMenu`，组 id 2024.1/2026.2 源码核实）、`ExplainLogComposer`（指令 + 日志，>64KB 截断，纯函数）、`DshToolWindowPanel.sendQuestion`（JCEF 填 composer + 派发回车自动提交；JBCefJSQuery 结果回传 submitted/blocked；在途守卫 + token 防串台；降级剪贴板/手动回车）；新增 ExplainLogComposerTest 4 例 |
| 2026-08-22 | v0.1.3-dev | 切换项目工作区修复：①工具窗口 content 残留（同窗口切换项目复用工具窗口，旧项目 content 未清理 → 两个主面板/旧工作区）——`createToolWindowContent` 先清空旧 content + `DshToolWindowPanel.dispose()` 幂等；②`WorkspaceInitializer.ensureWorkspace` 在 create 后追加 workspace.list + workspace.insertBefore 把当前项目挪到最前（create 幂等不改变顺序）；新增 WorkspaceInitializerTest 链路 8 例 + WorkspaceInitializerSmokeTest（真实 dsh 切换场景，见 §3.4.1） |
| 2026-08-22 | v0.1.3-dev | 切换项目工作区**根治**（用户确认）：每一项目独立 DSH_HOME（`DshHomeManager.homeDir(projectPath)`=MD5(projectPath) 前 16 位目录，见 §4.4）——dsh 工作区注册表/会话按项目隔离，切到任何项目工作区都从当前项目白纸开始；`syncCredentials(projectPath)` 项目启动写凭据、设置页 `syncCredentialsAll()` 同步所有打开项目 |
| 2026-08-23 | v0.1.3-dev | dsh 运行时升级 0.1.0-rc.7 → **0.1.1-rc.2**（用户要求）：改 `DshHomeManager.DSH_VERSION` + `build-runtime.ps1` 默认 DshVersion；重建 runtime-dev 与 build/runtime bundle；`build-runtime.ps1` 中文 PowerShell 脚本须 **UTF-8 BOM**（edit 写成无 BOM 后 Windows PowerShell 5.1 按 GBK 解析中文注释报 `ParserError 未终止标记 ')'`，用 `UTF8Encoding($true)` 写回）；真实 dsh 冒烟（启动/端口/工作区 RPC/MCP 6 工具）对 0.1.1-rc.2 全部通过，行为兼容 |
| 2026-08-23 | v0.1.3-dev | dsh 0.1.1-rc.2 回归修复（用户截图）：① **API Key 不生效**——0.1.1-rc.2 的 deepseek LLM provider 用 `credentialRef("DEEPSEEK_API_KEY")`，解析优先继承**环境变量**（`inherited env wins`）；插件在 `DshProcessManager` extraEnv 透传 `DEEPSEEK_API_KEY`（从 PasswordSafe），旧平铺凭据文件格式会被 dsh 自动迁移；② **每次新项目弹"内测声明"**——acknowledge 存每项目独立 `settings.yaml` 的 `ui-onboarding.welcomeNoticeVersion`；`ensureHome` 预写该字段 + JCEF 注入自动点 Continue 兜底 |
| 2026-08-23 | v0.1.3-dev | dsh "Add an API key" onboarding 捕获（用户要求）：JCEF 注入检测弹窗，用户输入 Key 点 "Save and continue" 时经 JBCefJSQuery 回传 `__apikey__<key>` → 写插件 PasswordSafe（`DshCredentials.writeApiKey`），设置页 JBPasswordField 脱敏显示 + 下次启动透传。**注入口径修正**：onUrlReady 时页面未加载、脚本不执行，且旧 `resetSessionPersistence` 的 `location.reload()` 会销毁注入脚本（用户实测不同步、内测声明需手动点）；改为注册 **CefLoadHandler**（`org.cef.handler.CefLoadHandlerAdapter.onLoadEnd`，主 frame 加载完成后注入）并**移除 resetSessionPersistence**（独立 DSH_HOME 下 localStorage 按端口隔离、本就冗余） |
| 2026-08-23 | v0.1.3-dev | **方案 C：dsh 配置真·同一文件全局共享**（POC 验证后实现）：`DshHomeManager.globalConfigHome()` 为全局配置根（凭据/设置文件唯一真源），每项目子目录 DSH_HOME 只存数据（storages/sessions）；`McpPatchGenerator` 生成 `ide.yml` 时用 cordis patch 的 **`$settings`/`$credentials`**（修改已有单元，POC 确证 `- insert` 会 duplicate、`$id` 才对）把 `settings-file.path`/`credentials-local.path` 指向全局——**配置共享 + 数据按项目隔离 + 无多窗口冲突**；`syncCredentials()` 写全局、`ensureHome` 预写全局 settings.yaml（内测声明全局一次）；新增 McpPatchGeneratorTest 2 例（含 $settings 省略/呈现） |
| 2026-08-23 | v0.1.3-dev | **升级迁移：旧全局 session → 每项目隔离目录**（用户要求，解决"升级后旧 session 找不到"）：新增 `LegacySessionMigrator`——复刻 dsh `session-persistence-jsonl` 的 `projectKey(cwd)` 编码定位旧全局 `sessions/<projectKey(cwd)>/`，按项目把各 session 目录**原样复制**到隔离目录（保留压缩格式 `.jsonl.zstd`，逐目录合并、已存在跳过，幂等）；`ensureHome` 调 `migrateLegacySessions`。**关键机制**：workspace 注册表（`workspace.json`）无需手工迁移——dsh 启动时若 `initialized=false`（新隔离目录首个实例）会触发 workspace **bootstrap**，从已迁移 session 的 header（`cwd`）自动重建 workspace 并挂接 session 归属（真实 dsh 冒烟验证）。新增 LegacySessionMigratorTest 8 例 + LegacySessionMigratorSmokeTest（真实 dsh：zstd session 迁移后 workspace.json 自动挂接该 sessionId） |
| 2026-08-23 | v0.1.3-dev | **升级迁移补齐：投影缓存迁移（`session_projcache.json`）**（用户实测修复"历史会话标题全显示成项目名"）：dsh 的 `session.list` 用**零 I/O 投影缓存**（`cachedSnapshot`）读每行会话标题；缓存缺某会话记录时 `session.title=undefined`，UI 回退显示 `basename(cwd)`（项目目录名）。旧全局 `storages/session_projcache.json` 保存各会话 `title` 投影。`LegacySessionMigrator.migrateProjectionCache` 从全局缓存**筛选当前项目**（identity.cwd 规范化匹配，兼容 `/` 与 `\`）会话条目，合并写入隔离目录 `storages/session_projcache.json`（保留目标已有条目，不覆盖）；`ensureHome` 一并调用。真实迁移后 `session.list` 立即返回正确标题（"你是谁？/解释Spring AI MCP配置文件/SSE 405错误分析/..."）。LegacySessionMigratorTest 增至 14 例（含 projcache 4 例） |
| 2026-08-23 | v0.1.3-dev | **设置页 API Key 脱敏回显**（用户要求"显示前 6 位 + 中间脱敏 + 后 6 位"）：`DshCredentials.maskApiKey(key)` —— 前 6 位 + `******` + 后 6 位（≤12 位整段脱敏）；`DshSettingsConfigurable` 回显脱敏串（改用 `JBTextField`，否则 `JBPasswordField` 把文本渲染成掩码点，用户看不到脱敏串），`isModified`/`apply` 用"字段内容 ≠ 当前脱敏串"判定用户是否真的改了 key，避免把脱敏串当真实 key 写回密码库。新增 DshCredentialsMaskTest 6 例 |
| 2026-08-23 | v0.1.3-dev | **设置页回显兜底：凭据文件读取**（用户实测"改后仍为空"）：PasswordSafe 读不到 key 时设置页回显为空。`DshCredentials.readApiKeyFromCredentialFile`（行级解析）＋ `readApiKeyWithFallback`（先 PasswordSafe，无则回退插件全局凭据文件，方案A真源）；设置页 `readStoredApiKey()` 用它。DshCredentialsMaskTest 增至 10 例 |
| 2026-08-23 | v0.1.3-dev | **dsh Web UI 改 API key 也要全局生效**（用户要求+选B方案）：① 去掉 `DshProcessManager` 启动时注入的 `DEEPSEEK_API_KEY` 环境变量——dsh-credentials-local 的 `resolve()` 是 `inherited env wins`，注入 env 会使 dsh 永远读旧值，且 Web UI 改 key 被 `assertUnshadowed` 直接拒绝（源码 `dsh-credentials-local lib/index.js:636`）；② 新增 `DshCredentialsSync`（`WatchService` 监听各项目 DSH_HOME 凭据文件，dsh Web UI/Models page 以 `version:1 + refs.DEEPSEEK_API_KEY` 写入该文件 → 捕获 → 回写 PasswordSafe + 插件全局凭据文件）。**方案B 语义**：改 key 的那个 dsh 进程（去 env 后读文件层，该进程立即生效），其它项目**下次启动/重启**时 `syncCredentials()`/`ensureHome()` 从全局复制+透传 → 全局一致。监听器随项目 Disposable 释放（`DshCredentialsSync.release(projectName)`）。`onFileChanged` 仅当子项目 key 与全局不同才回写（无自激循环）。新增 DshCredentialsSyncTest 6 例 |
| 2026-08-30 | v0.2.0 | **macOS / Linux 主机兼容（瘦身通用插件 + 按平台下载运行时）**：① `Platform`（os/arch→target/nodeBinName/assetName，前缀匹配防 `darwin`/`win` 冲突）；`DshHomeManager.nodeExe()` 平台化，`DshProcessManager.killTree` 跨平台进程树，symlink 兜底仅 Windows；② `RuntimeProvisioner`+`RuntimeArchive`+`RuntimeAssets`（`runtime-assets.json` 资产地图；下载+`.sha256` 校验+安全解压；`DSH_IDEA_RUNTIME`/手动路径离线逃生）；设置页「运行时下载地址」；③ `build-runtime.mjs`（跨平台，任意主机产出 `runtime-<os>-<arch>.zip`+`.sha256`）；Gradle `buildRuntime` 改调 mjs、瘦身默认（`-Pthin=false` 保留 fat）、新增 Gradle wrapper；④ `.github/workflows/build-release.yml` 矩阵 + `docs/release-runtime.md`；测试 100/100（PlatformTest/RuntimeAssetsTest/RuntimeProvisionerTest） |
| 2026-09-15 | v0.2.3 | dsh 运行时 0.1.1-rc.2 → **0.1.5-rc.2**：① 常量与脚本同步（`DshHomeManager.DSH_VERSION`、`build.gradle.kts` `dshVersion`、`scripts/build-runtime.mjs`/`.ps1` 默认值）；重建 win-x64 运行时并更新 `release-assets/`。② **浏览器鉴权（0.1.5 新增）**：启动行 `dsh web: http://127.0.0.1:<port>/?token=<t>`；`GET /`（无 token）401，`GET /?token=` 303 + `Set-Cookie: dsh-auth-<authority-hash>=v1.…`；`/api` 一律要该 cookie。`PortParser.parseUrl` 保留完整 URL；`DshProcessManager.launchUrl` 供 JCEF/健康检查/`onUrlReady`（健康检查 `instanceFollowRedirects=false`，`200..399 || 401` 视为就绪）；`WorkspaceInitializer.bootstrapSessionCookie` 换取 cookie。③ **RPC 契约**：`workspace/<method>` 斜杠命名空间；信封 `{"type":"client-request","rpcId","method","payload":{"args":{"request":{…}}}}`；`workspace/list` 移除，置顶顺序改读 `storages/workspace.json` v2 `global.workspaceIds`（新增 `waitWorkspaceOrder`/`readWorkspaceOrder`）。④ 未适配（已知降级）：0.1.5 composer 由 `<textarea>` 改 Lexical contenteditable，JS 注入走剪贴板兜底。**五平台运行时**（新增 macOS x64 / Linux arm64）由本地交叉构建补齐（`build-runtime.mjs`：符号链接容错 / 主机 node 执行 npm / `--libc glibc` / 按主机能力选 zip 命令）。**设置页**：「运行时下载地址」反显当前生效值 + 默认按钮、「运行时目录」新设置项（默认按钮填入插件默认目录，**等价于未设置**）、本地 zip 选择器修复（`chooseJars`）与 VFS 暂存导入、错误卡换行与诊断。**IDE 边界**：2024.1.7 / 2024.3.2 / 2026.2 编译（含测试代码）全部通过。测试 **130 项全部通过** |
| 2026-09-17 | v0.2.4 | **修复"发送选中代码 / 日志一键解释 没反应"**（用户报告，真实页面 CDP 实测定位）。根因三项：① dsh 0.1.5 的 composer 是 **Lexical `contenteditable`**（`<div data-lexical-editor="true" role="textbox" contenteditable="true">`），旧的 `document.querySelector('textarea')` 命中 0 → 注入静默失败；② 回读判定读错位置——Lexical 文本在 `[data-lexical-text="true"]` 节点里，写入**当拍**根元素 `innerText`/`textContent` 可能为空，导致"已成功注入"被误判为失败并降级剪贴板；③ 输入框**仅在进入会话后**渲染（停在内测声明/"选择工作区"时命中 0），需重试等待。**修复**：新增纯对象 `ComposerScripts`（可单测）统一构造注入脚本——选择器四级回退、contenteditable 走 `execCommand('insertText')`（派发 `beforeinput`，Lexical 据此同步内部状态；失败退回合成 `paste`）、回读走 `[data-lexical-text]` 并轮询 ≤2s、提交用带 `keyCode/which` 的 Enter 后轮询清空判 `submitted`、兜底点发送按钮（不用 class 通配）；结果语义扩为 `injected/submitted/blocked/notfound/failed`，仅后两者降级剪贴板+通知（不再"脚本已下发"即乐观提示）。`sendSelection` 与 `sendQuestion` 共用该脚本，`PendingSend` 增加 `kind` 区分回传处理。**验证**：headless Edge + CDP 在真实 dsh 0.1.5 页面上对照实测——`insertText` ✅（`execReturn=true`，立即回读命中）、合成 `beforeinput` ❌、纯改 DOM ❌、Enter 提交 501ms 内清空 ✅；新增 `ComposerScriptsTest`（11 例）锁定契约。测试 **194 项全部通过** |
| 2026-09-17 | v0.2.4 | **dsh 配置共享化：修复"新模型 / API Key / Agent 预设重启后消失"**（用户实测驱动）。**根因三重（实测）**：① `DshHomeManager.ensureHome` 的 `copyGlobalConfigTo` 每次启动把共享根 `settings.yaml`/`.credentials.yaml` **`REPLACE_EXISTING` 覆盖**到每项目 DSH_HOME，而 dsh 的配置真源是 `$DSH_HOME`（`dsh-home-paths` 解析顺序：显式配置 > `$DSH_HOME` > `~/.dsh`；Web「Models」页写的 `llm-pi-ai`/`llm-deepseek`、语言 `locale` 都落项目子目录）→ 下次启动被清空；② 全局化用的 `- $settings:` / `- $credentials:` patch 语法**被 dsh 拒绝**：`dsh --profile web --patch <ide.yml> --dump-config` 报 `patch: id is required for non-insert patches`，整条 patch 被丢弃；③ `DshBridgeManager.writePatch()` 从未把共享根传给 `McpPatchGenerator`（`globalConfigDir` 走默认空值）。**方案（§4.4/§4.5）**：删除 `copyGlobalConfigTo`；patch 改 `- id: settings` / `- id: credentials` / `- id: agent-presets`（整份覆盖须重述必填 `default: standard`，并置 `includeUserRoot: false` 以免再扫 `$DSH_HOME/.agent-presets`）/ `- id: skill-filesystem`（`dshHome`）四段——共享面 = 设置文档 + 凭据 + Agent 预设 + 个人技能；数据面（`sessions`/`storages`/附件）仍按项目隔离（dsh 全库仅 `dshHomePath('sessions'\|'storages')` 两处）。**配套**：① 新增 `SharedConfigMigrator`（标记 `<共享根>/.plugin-layout-version`）——按顶层 namespace 与 `refs`/`records` 做**文本级**合并（共享侧优先、只补缺，保留注释/`!!js`；扁平凭据内联升级为 `version:1`），原文件移入 `<共享根>/migrated/<hash>/` 备份，逐项目失败降级且不写标记；② `syncCredentials()` 改 `YamlText.upsertRef` 合并写入（只替换 `refs.DEEPSEEK_API_KEY`，不再用扁平 layout 整份覆盖、不再抹掉 dsh 的 `records.client-connection/browser-session`）；③ `DshCredentialsSync` 废弃为空操作（dsh 与插件写同一份共享凭据）；④ **MCP 脚本零链接**：部署在 `<运行时根>/dsh/node_modules/@deepseek-ai/dsh-ide-bridge/`——Node 的 ESM 解析按**真实路径**查找、**不越过 junction**（实测放 `<运行时根>/.dsh-ide-bridge/` 会 `ERR_MODULE_NOT_FOUND`），故**删除每项目 `node_modules` junction**，`cleanLegacySharedRoot`/`cleanLegacyProjectHome` 清理历史残留；⑤ 设置页新增「共享配置目录」只读展示 + 打开按钮，API Key 回显改读共享文档的 `refs` 段。新增 `SharedConfigMigratorTest`（23 例）、重写 `McpPatchGeneratorTest`（11 例，含"绝不出现 `$id` 形态"回归断言）。**测试环境对齐**：`tooling/runtime-dev` 原为 dsh **0.1.1-rc.2**（与 `DSH_VERSION` 不一致，导致 2 个 workspace 冒烟长期失败：旧契约点号 RPC、启动 URL 无 token），已用 `build/runtime-win-x64.zip` 对齐到 **0.1.5-rc.2**（Node 22.23.2），旧树留作 `tooling/runtime-dev/{node,dsh}-0.1.1-backup`。测试 **194 项全部通过**（含 4 个真实 dsh 冒烟） |
