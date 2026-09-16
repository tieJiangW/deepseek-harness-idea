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
3. **凭据**：插件在 DSH_HOME 下以 `DEEPSEEK_API_KEY` 为键管理密钥（key 真源 = PasswordSafe + 插件自己管理的
   全局凭据文件）。设置页写入 PasswordSafe + 全局文件；**不向 dsh 进程注入 `DEEPSEEK_API_KEY` 环境变量**
   （`dsh-credentials-local.resolve()` 为 `inherited env wins`，注入会遮蔽 Web UI 写入并使 `assertUnshadowed`
   拒绝 Web UI 的 set；见 §3.9 与 PROJECT_NOTES §4）。
4. **Profile 合成**：`profiles/<name>/cordis.yml` 初始为 `[]`，由 bundle 层（`package.json` 的 `dsh.profile.bundles`）+ `cordis.patch.yml` 用户层 + `--patch` 覆盖层合成。插件以 `--patch <ide.yml>` 注入 mcp-client，不污染用户层。
5. **MCP 客户端**：`@deepseek-ai/dsh-mcp-client` 支持 `transport: streamable-http`；每实例一个 serverName；模型侧工具名为 `mcp__<serverName>__<rawName>`（serverName 须匹配 `^[A-Za-z0-9_-]{1,32}$`）。其依赖 `@modelcontextprotocol/sdk` 存在于 profile 的 hoisted `node_modules`，可被插件附带的 MCP server 脚本 import（脚本置于 DSH_HOME 下按 node 向上查找规则解析）。
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
- DSH_HOME：`PathManager.getConfigDir()/dsh-idea/dsh-home/`（与运行时分离，不随版本变化）。
  插件幂等生成 `profiles/web/`（package.json + cordis.yml）与 `ide.yml`；dsh 首次启动时
  自愈创建 `profiles/node_modules` junction 指向运行时 dsh 树（实测验证）。
- 生成运行期文件：全局凭据文件（从设置页，PasswordSafe 镜像）、`ide.yml`（patch，含 mcp-client 配置与 bridge 地址/token）。
- 初始化顺序：校验/下载运行时（首次必要时下载+解压；v0.2.1 工具窗口进度条/可取消）→ 生成 DSH_HOME → 写凭据 → 写 patch → 启动进程 → 健康检查。

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

- 复用 profile hoisted `node_modules` 的 `@modelcontextprotocol/sdk`（脚本置于 `$DSH_HOME` 下，node 向上查找命中 `profiles/node_modules`；若构建期已验证失败，则改为将 sdk 一并打包进脚本目录）。
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
- MCP server（`mcp-ide-server.mjs`）部署在 DSH_HOME 顶层；插件在 DSH_HOME 顶层创建 `node_modules` junction → runtime dsh 树，使 ESM 能向上解析 `@modelcontextprotocol/sdk`（dsh 自愈的 `profiles/node_modules` 不在 ESM 向上查找路径上，实测必需）。
- `failOnStartupError: true`（测试/诊断形态）：MCP 连接或工具同步失败即拒绝启动，用于冒烟验证。

### 3.7 代码上下文发送

- 编辑器右键动作"发送选中代码到 DSH"（`SendSelectionAction`，注册于 `EditorPopupMenu`，见 plugin.xml `<actions>`）：
  1. `ReadAction` 读选中文本/文件/语言（≤64KB，超出截断并注明 `…(已截断)`）；
  2. **直接写入 Bridge 的 sent-selection 队列**（`SentSelectionQueue`：容量 ≤10 条、单条 ≤64KB，环形淘汰）——智能体可随时经 `ide_get_sent_selection` 取回，**必达**；
  3. 聚焦工具窗口 + JCEF 注入预填 composer：轮询等待 dsh web 的 `<textarea>`（**0.1.1 实测**为 React 受控组件），
     原生 setter 设置 value + 派发 `input` 事件（触发 React onChange）。
     **⚠️ dsh 0.1.5 起 composer 已改为 Lexical contenteditable**（`data-lexical-editor="true"`），
     该注入当前**必然失败** → 走第 4 步剪贴板降级（有通知）；适配需改写为 contenteditable 路径并在真实 JCEF 页面验证；
  4. 注入失败/未运行 → 系统剪贴板 + 通知"请粘贴到输入框（代码已就绪）"。
- **紧凑文件引用**（v0.5.4，用户反馈迭代）：注入内容仅 `@绝对路径#L起始-结束` + 尾随换行
  （`buildCompactReference`），**无提示语、无代码本体**；注入后光标 `setSelectionRange` 移到
  文本末尾（引用行下一行），可直接输入问题。完整选中代码仍写入 Bridge sent-selection 队列
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
  - `model`（`deepseek-chat` 默认 / `deepseek-reasoner`）
  - `baseUrl`（默认 `https://api.deepseek.com`，可空）
  - `dshHomeOverride`（高级，默认 null → 用 `PathManager.getConfigDir()/dsh-idea/dsh-home`）
  - `logLevel`
  - 运行时相关（v0.2.0+，行为见 §3.2）：runtime-directory（指向本地已解压运行时，配置后跳过下载，等价 `DSH_IDEA_RUNTIME`）；运行时下载 URL（v0.2.1 起设置页回显当前平台**精确到文件的 URL** + 一键复制）与下载超时（可配置）；「选择本地运行时 zip…」离线导入（校验 zip 与 `.sha256` 侧车）。
- **API Key（`DshCredentials`，PasswordSafe 应用级）**：
  - 读写 `PasswordSafe`（应用级凭据条目）。
  - **脱敏回显**（用户要求"前 6 位 + 中间脱敏 + 后 6 位"）：`DshCredentials.maskApiKey(key)` 前 6 位 + `******` + 后
    6 位（≤12 位整段脱敏）；设置页用 `JBTextField` 回显脱敏串（不能用 `JBPasswordField`，其把文本渲染成掩码点，
    看不到脱敏串）；`isModified`/`apply` 以"字段内容 ≠ 当前脱敏串"判定用户是否真的改了 key，避免把脱敏串写回密码库。
  - **回显兜底**：`readApiKey() ?: readApiKeyFromCredentialFile(插件全局凭据文件)`——PasswordSafe 读不到
    （如 IDE 密码库未解锁）时回退到插件自管的全局凭据文件（方案A真源）。
- 应用行为：写 PasswordSafe + 同步插件全局凭据文件（`syncCredentialsAll` → `syncCredentials`）；
  **不再向 dsh 进程注入 `DEEPSEEK_API_KEY` 环境变量**（见 §2.3/§3.9 说明）；提示"重启会话生效"；"重启 dsh"按钮。
- **Web UI 改 key 全局生效（`DshCredentialsSync`，方案B）**：dsh Web UI（Models page）改 key 写当前项目
  DSH_HOME 下凭据文件（version:1 + refs）；`DshCredentialsSync` 用 `WatchService` 监听该文件，
  与全局不一致时回写 PasswordSafe + 插件全局凭据文件——改 key 的那个 dsh 进程立即生效，其它项目
  **下次启动/重启**时 `syncCredentials()`/`ensureHome()` 从全局复制 + 透传，全局一致。
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
  3. JCEF 注入脚本：原生 setter 填 composer → `input` 事件 → 派发 `keydown Enter`
     （dsh composer 实测：非 shift 的 Enter → `keyboard.submit`，智能体忙时默认入队仍送达）→
     轮询 ≤3s 判 textarea 清空 = `submitted`；未清空则回退点击
     `button[aria-label="Send message"/"发送消息"]`（**不用 class 通配**，避免误点运行中的"停止"按钮）；
  4. 结果经 **JBCefJSQuery** 回传 `submitted / blocked / no-composer`：`submitted` → 通知已发送；
     `blocked` → 消息留在输入框 + 提示手动回车；其他 → 剪贴板兜底 + 失败通知；
     `setupJsQuery` 必须在 `loadURL` **之前**创建（CEF message router 在页面加载时注入
     `window.<funcName>`；创建失败降级为无验证乐观提示）。
- 技术边界：**0.1.1 实测** composer 文本区即页面 `<textarea>`（`document.querySelector('textarea')`）；
  发送按钮 aria-label 为 "Send message" / "发送消息"（`t("input.send")`）。
  **⚠️ 0.1.5 起 composer 改为 Lexical contenteditable**（`data-lexical-editor="true"`；0.1.1 的 `jsx("textarea")` 已不存在）→
  现有注入失效、按设计降级剪贴板；适配需改用 contenteditable 路径（`document.execCommand('insertText')` + Enter 派发）
  并**在真实 JCEF 页面验证**（当前最高优先项）。

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

### 4.4 运行时与 DSH_HOME 目录布局（插件独立）

```
<PathManager.getConfigDir()>/dsh-idea/
├── runtime/<version>/            # 运行时（v0.2.0+ thin：首次按平台下载+SHA-256 校验；DSH_IDEA_RUNTIME / 设置页 runtime-directory 覆盖；全局共享，不按项目）
│   ├── node/                     # Node.js 运行时（按平台：node.exe / node，归一化布局）
│   └── dsh/                      # npm 安装的 @deepseek-ai/dsh 树（含全部依赖）
├── dsh-home/                     # DSH_HOME 根（v0.1.3-dev：全局配置 + 每项目隔离数据）
│   ├── 凭据文件                   #   全局凭据真源（PasswordSafe 镜像；方案A）
│   ├── settings.yaml             #   全局设置（内测声明 acknowledge；同步到各子目录）
│   └── <md5(项目根目录)前16位>/   # 每个项目一个独立 DSH_HOME——工作区注册表/会话数据按项目隔离，
│       ├── 凭据文件               #   切换项目后 dsh 工作区从当前项目"白纸"开始（彻底解决工作区残留）
│       ├── settings.yaml         #   启动时从全局复制（内测声明等；dsh 读子目录副本）
│       ├── ide.yml               #   运行期生成的 patch（mcp-client，McpPatchGenerator）
│       ├── mcp-ide-server.mjs    #   MCP server 脚本（插件资源部署）
│       ├── node_modules/         #   顶层 junction → runtime/dsh/node_modules（MCP 脚本 ESM 解析 SDK）
│       ├── profiles/web/         #   物化的 web profile（package.json + cordis.yml）
│       ├── profiles/node_modules/#   dsh 首次启动自愈创建的 junction → runtime/dsh/node_modules
│       └── sessions/ storages/   #   会话数据 + 工作区/投影缓存（dsh 自动创建；每个项目独立）
```

> **全局配置根（`DshHomeManager.globalConfigHome()`，= `dsh-home/`）**：插件自管的全局凭据文件与
> `settings.yaml` 的**唯一真源**，所有项目共享；`ensureHome` 启动时用 `copyGlobalConfigTo` 把它们
> **复制到每项目子目录**（dsh 读子目录副本；dsh 内改动下次启动被全局覆盖——方案A的 tradeoff）。API Key 经
> `DshCredentialsSync`（WatchService）在 dsh Web UI 改 key 时回写全局 PasswordSafe + 插件全局凭据文件
> （见 §3.9），使所有子项目下次启动/重启时一致（方案B）。

> **DSH_HOME 按项目隔离（v0.1.3-dev，用户实测驱动）**：`DshHomeManager.homeDir(projectPath)` 用
> `MD5(projectPath)` 前 16 位派生目录。dsh 的工作区注册表（`workspace.json`）与会话数据因此按项目
> 隔离：切换项目后，新 dsh 进程的工作区只含当前项目，从机制上杜绝"显示其他项目工作区"
> （实测：此前共享 DSH_HOME 时，仅"已打开过的旧项目"复现——dsh 记住了其历史会话状态；全新项目无
> 此问题）。API Key：**不再向 dsh 进程注入 `DEEPSEEK_API_KEY` 环境变量**（dsh-credentials-local
> 的 `inherited env wins` 会遮蔽 Web UI 写入，且 `assertUnshadowed` 拒绝 Web UI 的 set）。key 真源 =
> PasswordSafe + 插件全局凭据文件；`syncCredentials(projectPath)` 在项目启动时把它们
> 写入各项目 DSH_HOME 凭据文件（从全局复制）。dsh Web UI 改 key 写当前项目文件，由
> `DshCredentialsSync`（WatchService）监听并在与全局不同时回写全局——使所有子项目**下次启动/重启**时一致
> （方案B）。设置页 apply 用 `syncCredentialsAll()` 同步全局。
>
> **升级迁移（v0.1.3-dev，用户要求）**：旧版（v0.1.2）把 session 存在全局 `dsh-home/sessions/`；
> 升级到按项目隔离后，旧 session 目录仍在全局根但不再被读取。`DshHomeManager.ensureHome` 通过
> `LegacySessionMigrator` 把当前项目的旧 session 目录**原样复制**（保留 `.jsonl.zstd` 压缩格式）到
> 隔离目录 `sessions/<projectKey(cwd)>/`，并**迁移投影缓存** `storages/session_projcache.json`
> （筛选当前项目 identity.cwd 匹配的会话条目，合并写入隔离目录）——dsh 的 `session.list` 用**零 I/O
> 投影缓存**读会话标题，缺缓存时 UI 回退显示 `basename(cwd)`（即项目目录名），迁移缓存后标题立即可见
> （用户实测：历史会话标题全部显示成项目名）。workspace 注册表由 dsh 启动时自动 bootstrap 从 session
> header 重建，无需手工迁移。详见 `LegacySessionMigrator` / `LegacySessionMigratorTest` /
> `LegacySessionMigratorSmokeTest`。

### 4.5 patch 模板（`ide.yml`）

见 3.6；由 `McpPatchGenerator` 以实际 cordis loader 语法生成，mcpPort/token 动态填入。

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
