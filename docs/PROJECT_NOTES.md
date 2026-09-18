# 项目知识库 / 开发备忘（跨会话参考）

> 本文汇总 DeepSeek Harness IDEA 插件开发过程中的**实测环境事实、踩坑记录、dsh 行为结论**，
> 供后续任务（Step 6 评审及之后的维护/升级）直接参考，避免重复调查。
> 最后更新：2026-09-18（**v0.2.4 已发布**，插件版本 **0.2.4**：GitHub Release `v0.2.4`（id 391244240，11 资产）
> + JetBrains Marketplace update **1174317**（待审）+ **macOS（Apple Silicon）端到端实测通过**。
> 发布前凭据自检通过：`scripts/secret-audit.mjs` 扫描仓库 91 个文本文件 / 24 条提交信息 / Release 正文 / 本地工作区，均无凭据。
> 背景：① 删除 `copyGlobalConfigTo`（启动覆盖项目配置 = 新模型/语言丢失根因）；patch 改
> `- id: settings/credentials/agent-presets/skill-filesystem`（旧的 `$settings` 形态被 dsh 拒绝）；
> ② MCP 脚本全局唯一、落在 dsh 树内（Node ESM **不越过 junction**），删除每项目 `node_modules` junction；
> ③ 一次性配置迁移（文本级 namespace / refs+records 合并 + `migrated/` 备份）；
> ④ **右键"发送选中代码 / 一键解释"没反应修复**：dsh 0.1.5 的 composer 是 Lexical contenteditable，
> 写入走 `execCommand('insertText')`、回读走 `[data-lexical-text]` + 轮询（真实页面 CDP 实测）；
> ⑤ `tooling/runtime-dev` 原为 dsh 0.1.1-rc.2，已对齐 0.1.5-rc.2（否则 2 个 workspace 冒烟必失败））
> 上次更新：2026-09-15（dsh 0.1.5-rc.2 升级，插件版本 0.2.3：启动 URL 浏览器鉴权 token（`?token=` → cookie）+
> RPC 命名空间/信封/参数三处变更（`workspace/list` 已移除））
> 上次更新：2026-09-02（**v0.2.1**：运行时供应 UX + 下载可靠性——首次使用下载失败修复、
> 连接池化 HTTP/2 HttpClient + 浏览器 UA + 超时配置 + 退避重试、工具窗口下载进度条（可取消）、
> 设置页精确下载 URL 回显/一键复制/可配置超时/本地 zip 离线导入、错误卡失败 URL + 根因 + Restart）
> 上次更新：2026-08-23（v0.1.3-dev：切换项目工作区根治/每项目隔离 DSH_HOME、dsh 0.1.1-rc.2 升级与回归、
> 运行日志一键解释、方案C回退方案A、旧 session/投影缓存升级迁移、API Key 脱敏回显 + 全局生效同步）

---

## 1. 本机构建 / 运行环境（实测）

| 项 | 结论 |
|---|---|
| 目标 IDE | IntelliJ IDEA Community/Ultimate **2024.1+**（`intellij.version = 2024.1.7`，since-build 241，until **262.\***；可用 `-PplatformVersion=2026.2` 做前向编译检查） |
| 构建 JDK | **必须 JBR 21**：`D:\develop\IntelliJ IDEA 2024.3.4.1\jbr`（`instrumentCode` 需要 JBR 布局；jdk-17 会报 `D:\develop\Java\jdk-17\Packages does not exist`） |
| Gradle | `tooling/gradle-8.14/bin/gradle.bat`（自带发行版）；**勿用系统 gradle-7.2**（native 库初始化失败且过旧） |
| Gradle 用户目录 | `GRADLE_USER_HOME=D:\develop\gradle-7.2\.gradle\repository`（缓存已就位，含 ideaIC 2024.1.7 约 1GB） |
| 运行时开发目录 | `tooling/runtime-dev`（`DSH_IDEA_RUNTIME` 指向它）；`build/runtime` 是构建产物（含 bundle） |
| 自动化沙箱 | pwsh 沙箱拦截工作区外读写与部分出站网络 → **gradle/npm 命令需完整沙箱权限**（仅自动化环境；用户本机无此限制） |
| 一键打包 | `scripts/build-plugin.bat`（双击；自动探测 JBR/Gradle 缓存，`--no-daemon`，输出产物路径） |
| 版本号 | 插件版本 = `build.gradle.kts` 第 13 行 `version`；`DshHomeManager.DSH_VERSION`（= dsh 运行时版本 `0.1.5-rc.2`，决定生产运行时目录名；勿随意改，升级=重建运行时） |
| 前向编译检查 | `tooling\gradle-8.14\bin\gradle.bat compileKotlin --no-daemon -PplatformVersion=2026.2`（下载 ideaIC 2026.2 约 1.5GB 到 Gradle 缓存；新平台自带 Kotlin 模块 metadata 高于 2.0.21，已加 `-Xskip-metadata-version-check`；JCEF 自 2026.2 起拆分为内置插件 `com.intellij.modules.jcef`，检查时需列入 `plugins`） |

### 常用命令（自动化环境需完整权限）

```powershell
# 环境
$env:JAVA_HOME = "D:\develop\IntelliJ IDEA 2024.3.4.1\jbr"
$env:GRADLE_USER_HOME = "D:\develop\gradle-7.2\.gradle\repository"
$env:DSH_IDEA_RUNTIME = "D:\develop\deepSeekWorkSpace\code\deepSeekForIdea\tooling\runtime-dev"

# 全量测试（含真实 dsh 冒烟；无 DSH_IDEA_RUNTIME 时冒烟自动跳过）
tooling\gradle-8.14\bin\gradle.bat test
# 打包
tooling\gradle-8.14\bin\gradle.bat buildPlugin
# 构建并打包运行时（Bundle 产物 → 插件资源）
tooling\gradle-8.14\bin\gradle.bat bundleRuntime
```

### Gradle 缓存踩坑（重要）

- **`Failed to create Jar file ...\caches\jars-9\<hash>\xxx.jar`** = 有**残留 Gradle daemon**（常为 jdk-11 老 daemon）锁着缓存。
  解法：`gradle --stop` → 杀残留 java 进程（确认路径不是 IDE 的 JBR）→ 删 `jars-9` 对应 hash 目录 → 重试。
- 一键脚本用 `--no-daemon` 正是为避免此类锁冲突（每次构建单次 JVM，隔离干净）。
- `gradlew` wrapper 在本机不可用：wrapper 的 `GRADLE_USER_HOME` 指向不可写目录且未预下载 8.14 发行版。

---

## 2. 插件结构速览

```
src/main/kotlin/com/deepseek/harness/idea/
├── runtime/   DshHomeManager(运行时/DSH_HOME/解压自举/全局配置+每项目副本) · DshProcessManager(进程+端口发现+重启)
│             PortParser · DshCredentials(PasswordSafe + mask + 凭据文件兜底读取) · WorkspaceInitializer(默认工作区)
│             LegacySessionMigrator(旧全局 session/投影缓存→隔离目录迁移) · DshCredentialsSync(Web UI 改 key→全局回写)
│             DshRuntimeRegistry(并发≤3) · DshLifecycleManager / DshAppLifecycleListener(生命周期)
├── bridge/    IdeBridgeServer(HTTP+token) · DshBridgeManager(编排) · SentSelectionQueue(环形队列)
│             IdeBridgeResources(读 mcp-ide-server.mjs)
├── mcp/       McpPatchGenerator(ide.yml patch)
├── review/    SnapshotManager(基线快照) · SnapshotDiff · ReviewManager
├── ui/        DshToolWindowFactory(工具窗口+JCEF+注入) · SendSelectionAction · ReviewChangesAction
│             SendLogExplanationAction(运行日志一键解释) · ExplainLogComposer · DshLogPanel(日志页)
├── settings/  DshSettingsState · DshSettingsConfigurable · CredentialImporter
└── i18n/      DshBundle
src/main/resources/
├── mcp-ide-server.mjs        # MCP server（随插件部署到 DSH_HOME）
├── runtime-bundle.zip        # 构建期打入（buildRuntime -Bundle 产物）
├── icons/dsh-toolwindow.svg  # 插件图标（工具窗口/右键动作共用）
├── messages/DshBundle*.properties
└── META-INF/plugin.xml       # 工具窗口/动作/服务/监听器/Overview/What's New
```

---

## 3. dsh 行为事实（0.1.1-rc.2 实测结论；启动/patch/RPC/鉴权已按 0.1.5-rc.2 复验并适配，其余条目待复验；
> 早期 0.1.0-rc.7 结论经 0.1.1-rc.2 复验兼容）

### 3.1 启动与 patch

- 启动命令（**`--patch` 必须在 web 应用选项之前**，否则 `unknown option '--patch'`）：
  `node <dsh>/lib/bin.js --profile web --patch <ide.yml> --host 127.0.0.1 --port 0`
- stdout 打 `dsh web: http://127.0.0.1:<port>`；`--port 0` 随机端口。
- **patch 语法（关键）**：`--patch` 是覆盖层，**只能改已有条目或用 `insert:` 新增**；新增 mcp-client 必须：
  ```yaml
  - insert:
      - id: mcp.ide
        name: '@deepseek-ai/dsh-mcp-client'   # name 字段必须显式
        config: { serverName: ide, transport: streamable-http, url: http://127.0.0.1:<port>/mcp, ... }
  ```
- `failOnStartupError: true` 时 MCP 连接/同步失败即拒绝启动（冒烟测试用它验证链路）。
- DSH_HOME 首次启动会**自愈创建** `profiles/node_modules` junction → 运行时 dsh 树；
  但 **ESM 向上查找不会命中 `profiles/node_modules`** → 插件需在 DSH_HOME **顶层**另建 `node_modules` junction 供 mcp-ide-server.mjs 解析 SDK。

### 3.2 默认工作区（Workspace）

- workspace 是**显式注册制**：`storages/workspace.json` 无记录时 UI 显示"选择一个工作区开始"，**不会自动用 cwd**。
- 插件解法：健康检查后调内部 RPC `POST /api/workspace.create`，body
  `{"type":"client-request","rpcId":"<uuid>","method":"workspace.create","payload":{"path":"D:/proj"}}`；
  **127.0.0.1 loopback 信任围栏放行，无需鉴权头**；幂等（同路径返回既有 workspace）。
- 其他 RPC 同构：`POST /api/<method>`，`session.create` 接受 `cwd` 或 `workspaceId`。

### 3.3 输入框 / 文件引用（重要边界）

- **dsh 0.1.1-rc.2 输入框不支持"文件引用 chip（文件名+行号+X 删除）"**——`@`/`/` 输入触发菜单
  仅注册了 `/`（command）等源，**无文件源**；`@` 前缀无源时菜单不弹（`roster.length===0` → close），可安全作引用前缀。
- `fileMentions` 渲染（消息里反引号路径 → 可点击文件 chip）**只匹配"本轮工具产出文件"**（`producedFileMentions`），
  对用户手动发送的代码路径不生效。
- 因此"发送选中代码"采用**紧凑引用文本**：注入 `@绝对路径#L起始-结束\n`（无代码本体、无提示语），
  光标 `setSelectionRange` 移到末尾下一行；完整代码存 Bridge `sent-selection` 队列（`ide_get_sent_selection` 兜底）。

### 3.4 其他

- composer 是标准 React 受控 `<textarea>`：外部注入需原生 setter + `input` 事件（`dispatchEvent(new Event('input',{bubbles:true}))`）。
- MCP SDK：`@modelcontextprotocol/sdk@1.30.0`（ESM；`StreamableHTTPServerTransport` + `createMcpExpressApp`，stateless 模式 `sessionIdGenerator: undefined`）。
- 网络：本机 npm 走 `registry.npmmirror.com`（`npm_config_registry`）；curl/Invoke-WebRequest 常失败，**用 node fetch 最稳**（`scripts/download-node.mjs` 即如此）。

---

## 4. 踩坑记录（含修复）

| 坑 | 现象 | 根因 / 修复 |
|---|---|---|
| runtime 树被清空 | `@deepseek-ai/dsh` 等包目录全空、boot 报 MODULE_NOT_FOUND | **junction 陷阱**：递归删除含 junction 的目录会跟随删掉目标（`Remove-Item -Recurse` 与 JUnit `@TempDir` 清理均如此）。修复：删除前先断链（Windows junction 需 `LinkOption.NOFOLLOW_LINKS` 检测 `isOther`）；测试 tearDown 先 `unlinkJunctions` |
| bat 中文乱码 | `'A' is not recognized` / 命令被拆 | write 工具产出 UTF-8 无 BOM 的 bat，cmd/GBK 解析中文错乱。修复：**bat 全英文纯 ASCII**（见 build-plugin.bat） |
| **.ps1 中文 + 无 BOM** | `ParserError: 命令字符串中包含未终止的标记 ')'` | Windows PowerShell 5.1 把无 BOM UTF-8 的 .ps1 按 GBK 解析，中文注释变乱码报 `ParserError`。修复：中文 .ps1 须 **UTF-8 BOM**（edit 工具写的是无 BOM，需用 `[System.IO.File]::WriteAllText(p, c, (New-Object System.Text.UTF8Encoding $true))` 写回带 BOM；build-runtime.ps1 踩过，v0.1.3-dev 已加 BOM） |
| PowerShell 变量 | `$home` 赋值报"read-only" | `$HOME` 是只读变量，测试/脚本变量名避开 `home`（用 `$dshHome`） |
| UTF-8 BOM | dsh 读 `package.json` 报 JSON 解析失败 | PowerShell `Set-Content -Encoding UTF8` 会写 BOM；用 `[System.IO.File]::WriteAllText(..., UTF8Encoding($false))` |
| 2024.1 API 勘误 | 编译失败 | 见下表 |
| 沙箱 spawn EPERM | npm/子进程 `spawn EPERM` | 沙箱禁管道 stdio；npm 用 `--ignore-scripts`（原生依赖预编译无需 postinstall），Node 子进程用 `stdio:'ignore'`+轮询端口 |
| Gradle 缓存锁 | 见 §1 | `--no-daemon` + 杀残留 daemon |

### v0.2.1（2026-09-02）首次使用运行时下载：失败修复 + 可靠性 + UX（发布条目）

- 修复首次使用下载失败：临时文件父目录缺失 → `NoSuchFileException`；现**先创建父目录再写入**。
- 下载更可靠：连接池化、HTTP/2 的 `java.net.http.HttpClient` + 浏览器 User-Agent + **60s 连接超时 +
  可配置读超时 + 退避重试**；慢速/不稳定网络（如大陆访问 GitHub）也能成功。
- 工具窗口下载进度条（connecting/verifying/downloading 三态）+ **取消**。
- 设置页回显**当前平台精确下载 URL（到文件）** + 一键复制 + 可配置下载超时 +
  **"Choose local runtime zip…" 本地 zip 离线导入**（内容校验 + SHA-256 对照 `.sha256` 侧车）。
- 错误卡显示**失败的确切 URL + 底层原因 + Restart**。

### 2024.1 API 勘误（编译期验证）

- 无 `com.intellij.util.json.JsonUtil` → 用 Gson（`com.google.gson.Gson`，平台自带）。**v0.1.1 起改为自研 `JsonCodec`**
  （Gson 正被 JetBrains 逐步移出平台，2026.2 前向编译验证通过；见 §3/§6 与 DESIGN §3.1）。
- **dsh 凭证读取优先级（v0.1.3-dev 关键结论）**：`dsh-credentials-local.resolve()` 为
  `inherited env > 插件凭据文件 > .env`（源码 `lib/index.js:473`）。**不能给 dsh 进程注入
  `DEEPSEEK_API_KEY` env**——否则 dsh 永远读 env 旧值，且 Web UI 改 key 会被 `assertUnshadowed()` 拒绝
  （源码 `lib/index.js:636`，报"supplied read-only by the launching environment"）。所以 key 靠文件
  （PasswordSafe + 插件全局凭据文件同步），Web UI 改 key 经 `DshCredentialsSync`（WatchService）回写全局。
- **凭据文件格式**：插件 `syncCredentials()` 写**平铺** `DEEPSEEK_API_KEY: <key>`；dsh
  credentials-local 会通过 `renderFlatLayoutMigration()` 自动迁移到 `version:1 + refs.DEEPSEEK_API_KEY`；
  dsh Web UI（Models page）写入的是 **version:1 + refs** 格式。读取需兼容两种（`DshCredentials.readApiKeyFromCredentialFile` 行级解析）。

### 2026.2 JCEF 拆分（v0.1.1 实测/编译期验证）

- **JCEF 不再是平台核心的一部分**：2026.2（build 262）起 `com.intellij.ui.jcef.*` 移到**独立内置插件
  `com.intellij.modules.jcef`（"Web Browser (JCEF)"）**，其模块声明 `visibility="public"`（其他插件无需
  声明依赖即可访问类，运行时亦然）。该插件默认启用（bundledPlugins 有、disabledPlugins 空；
  `ide.browser.jcef.enabled` registry 默认 true）。
- **运行时不可用排查**（用户 2026.2 实测：安装成功但工具窗口显示 "JCEF is unavailable…"）：
  1. **IDE 运行时必须是带 JCEF 的 JBR**（`JBCefApp.isJcefFromJbr()` 检查 `JCefAppConfig` 是否来自 jrt 模块；
     用户如用自定义 JDK 或 "nomod" JBR 则 JCEF 不可用，trae 社区同因）；
  2. **"Web Browser (JCEF)" 插件需启用**（Settings | Plugins）；
  3. 修改后**重启 IDE**。
  - v0.1.1 起工具窗口 JCEF 失败提示会附带异常信息与上述排查建议（`error.jcef.hint`），便于用户在真实会话自诊。
- 前向编译检查：`-PplatformVersion=2026.2` 时把 `plugins/jcef-plugin/lib/**/*.jar` 加入 compile classpath
  （`build.gradle.kts` 条件依赖），2024.1 默认构建不受影响（JCEF 在 app-client.jar）。
- `LanguageUtil.getLanguageForFile(vf)` 不存在 → `getLanguageForPsi(project, vf)`。
- `Document` 无 `isModified` → `FileDocumentManager.isDocumentUnsaved(doc)`；Document 无 `selectionModel` → 用 `(FileEditorManager.selectedEditor as? TextEditor)?.editor`。
- `VfsUtil.visitChildrenRecursively` 不存在 → `VfsUtilCore.visitChildrenRecursively` + `VirtualFileVisitor`（**`visitFile` 返回 `Boolean`**，false=跳过 children；不是 Result）。
- `VfsUtil.markDirtyAndRefresh` 是 **4 参** `(async, recursive, sync, vararg files)`。
- `Notification` 内容版是 **4 参** `(groupId, title, content, type)`（3 参无内容）。
- `AppLifecycleListener.appClosing()`（`applicationListeners` 注册）；`ProjectManagerListener.projectClosed(project)`。
- PasswordSafe 241：`setPassword(CredentialAttributes, String?)` / `getPassword(...)`；旧三参不可用。

### 2024.x "invalid plugin descriptor"（v0.2.1 实测定根因）

- **现象**：IntelliJ **2024.3**（build 243）启动报
  `File '...\plugins\deepseek-harness-idea\lib\instrumented-deepseek-harness-idea-0.2.1.jar' contains invalid plugin descriptor`，
  插件侧边栏不显示；但**动态加载/"Loaded without restart" 完全正常**（dsh 进程、JCEF、工具窗口都跑了）。
  `0.1.3` 在 2024.3 正常，`0.2.0/0.2.1` 均失败。
- **根因（实锤，来自 idea.log 第 893–907 行堆栈）**：`<idea-plugin>` 里写了
  `<icon>/icons/dsh-logo-512.png</icon>`。IntelliJ 2024.x（build 241–252）启动期的
  `PluginDescriptorLoader` 用 `XmlReader.readRootElementChild` 解析 plugin.xml 时**只识别固定的根子元素集合**
  （`id/name/category/version/description/change-notes/resource-bundle/product-descriptor/module/idea-version/vendor/…/depends/actions/include/…`），
  **不认识 `<icon>`** → 落入 `else` 分支触发 `LOG.error("Unknown element: icon")`，而**启动期 `Logger.error` 会抛异常**，
  被 `loadDescriptorFromJar` 的 `catch` 捕获 → `reportCannotLoad` → 判 "contains invalid plugin descriptor"。
  - 2026.2（build 262+）的 XmlReader 识别 `<icon>`，故只在 2024.x 崩。
- **修复**：**移除 plugin.xml 中的 `<icon>` 元素**。工具窗口/动作的 `icon="/icons/dsh-toolwindow.svg"`
  是作用于子元素的属性（`extensions/toolWindow`、`actions/action`），不经过根子元素读取，**不受影响**。
- 其余排查无果/排除项：jcef `<depends>`（移除与否均失败，非因）；description/change-notes 内容（非因）；
  `<vendor url>`（2024.3 的 vendor case 支持 `url` 属性，非因）；jar 内容与 src 一致（非污染）；
  `<version>`（gradle 注入，2024.3 支持该 case，非因）。

### dsh 0.1.5-rc.2 升级（浏览器鉴权 + RPC 命名空间变更，实测定根因）

- **现象**：升级后 4 个真实启动冒烟全部失败——`dsh did not reach RUNNING`，日志
  `dsh health check timed out on port <p>`（dsh 已打印端口，但 HTTP 健康检查始终不通过）。
- **根因 A：启动 URL 带浏览器鉴权 token（0.1.5 新增）**。启动行变为
  `dsh web: http://127.0.0.1:<port>/?token=<t>`；实测 `GET /`（无 token）→ **401**，
  `GET /?token=<t>` → **303 + `Set-Cookie: dsh-auth-<authority-hash>=v1.…`**，之后所有 `/api/*` 必须携带该 cookie
  （`Authorization: Bearer <t>` 与 query token 均**不被接受**，实测 401）。
  → 修复：`PortParser.parseUrl()` 解析完整 URL；`DshProcessManager` 保存 `launchUrl` 并让 JCEF / 健康检查 / `onUrlReady` 都用它；
  健康检查禁止跟随重定向（303 视为就绪）并容忍 401；`WorkspaceInitializer` 先 `GET /?token=` 换取 cookie 再调 API。
- **根因 B：RPC 契约三处变化（0.1.5）**：
  - **命名空间**：方法名点号 → 斜杠（`workspace.create` → `workspace/create`；点号形式 RPC 层 404）。
  - **信封**：`payload` 必须"恰好包含一个 plain-object `args`"（`{"payload":{"args":{…}}}`），否则报
    `gateway/internal: Remote payload must contain exactly one plain-object args field`。
  - **参数**：`args` 内还需按 descriptor 再包一层 `request`：`{"args":{"request":{"path":"…"}}}`，否则报
    `gateway/arguments-invalid: args fields do not match the descriptor: missing "request"; unexpected "path"`。
  - **方法集**：**`workspace/list` 已移除**（RPC 层 404）；工作区列表仅经流式 `workspace/follow` 下发。
    实测 `workspace/create` 对**新建** workspace 会自动置顶，但**幂等 create（已存在）不改变顺序**，故"切回旧项目置顶"仍需 `insertBefore`。
    顺序来源改为读 `DSH_HOME/storages/workspace.json`（**v2 结构**：`global.workspaceIds` + `tables.workspaces`，非旧版顶层 `workspaceIds`）。
  → 修复：`WorkspaceInitializer`（`workspace/create`、`workspace/insertBefore` + 读文件定序）、`DshProcessManager` 传入 `homeDir`。
- **前端 composer 变更（本次未适配，功能降级不崩溃）**：0.1.5 的 InputBar 不再渲染 `<textarea>`
  （0.1.1 为 `jsx("textarea", { ref: inputRef, className: InputBar_module_css… })`），改为 Lexical
  `contenteditable`（`data-lexical-editor="true"`）。插件注入仍按 `document.querySelector('textarea')` 定位，
  故「发送选中代码」「运行日志一键解释」会走剪贴板兜底路径（有提示，需手动粘贴）。
  适配方向：改走 contenteditable（`document.execCommand('insertText')` + Enter 派发），**须在真实 JCEF 页面验证后**再改。
- 其余未变（实测）：`--profile web` / `--patch` / `--host` / `--port` / `--no-open` 启动参数、端口行前缀 `dsh web: `、
  `lib/bin.js` 入口、profile bundle（`dsh-base` + `dsh-web-app`）、`welcomeNoticeVersion` 常量值 `2026-08-13.1`（与插件常量一致，无需改）。

### v0.2.4 dsh 配置共享化（用户实测驱动的根因修复，全链路实测）

**现象**：在某个项目的 Web「Settings → Models」新增自定义模型（或切换语言），关闭 IDEA 再打开，配置**消失**。

**根因（三重，全部实测）**：

1. **启动覆盖**：v0.1.3-dev ~ v0.2.3 的 `DshHomeManager.ensureHome` 每次启动执行
   `copyGlobalConfigTo(home)`——把共享根的 `settings.yaml` / `.credentials.yaml`
   **`REPLACE_EXISTING` 覆盖**到每项目 DSH_HOME。dsh 的配置真源是 `$DSH_HOME`
   （`dsh-home-paths/lib/index.js:73-76`：显式配置 > `$DSH_HOME` > `~/.dsh`；
   `dsh-settings-file/lib/index.js:30-40` 默认 `<harness home>/settings.yaml`），
   而 Web Models 页写的 namespace（`llm-pi-ai` / `llm-deepseek`，
   `dsh-client-ui-settings-models/lib/client.js:1076`）落在项目子目录 → 下次启动被覆盖清空。
   同理吃掉 `locale.preference`。
2. **patch 语法无效**：全局化的实现（`McpPatchGenerator` 的 `$settings` / `$credentials` 分支）
   用的是 `- $settings:` / `- $credentials:`，dsh **不接受**：
   ```
   dsh: [<ide.yml>] patch: id is required for non-insert patches
   ```
   整条 patch 被丢弃。**正确写法**是 `- id: settings` / `- id: credentials`（`--dump-config` 实测通过）。
3. **调用方没传参**：`DshBridgeManager.writePatch()` 调 `McpPatchGenerator.generate(port)`，
   `globalConfigDir` 走默认空值 → 即使语法正确，分支也不会执行。

**为什么不能用目录链接（junction/symlink）把 `settings.yaml` 指向共享文件**：dsh 写文档走
`@deepseek-ai/dsh-atomic-write` 的 `writeFileAtomic`（随机名临时文件 + rename 覆盖，`wx` 标志拒绝
跟随符号链接）。首次写入就会把链接**替换成普通文件**，共享随即失效。→ 只能走配置（`--patch`）重定向。

**dsh patch 语法（0.1.5-rc.2，`--dump-config` 实测）**：`--patch` 是叠加在 bundle 层之上的覆盖层，
顶层是 `PatchOptions` 数组，两种形态：
- `insert:` 列表 → 新增条目（新增 mcp-client 必须显式 `name`）；
- `- id: <rowId>` → **整份替换**该条目的 `config`（未改字段必须重述，不做深合并，
  见 `dsh-app-boot/README.md:43,60`）。
校验命令（离线组合，不启动服务、不联网）：
```powershell
$env:DSH_HOME="<临时目录>"; node <runtime>\dsh\node_modules\@deepseek-ai\dsh\lib\bin.js `
  --profile web --patch <ide.yml> --dump-config
```
输出里 `# == <ide.yml>` 注释标出该文件贡献的行；无 `patch:` 报错即语法通过。

**全局化范围与各自依据**：

| 行 id | 配置 | 依据 |
|---|---|---|
| `settings` | `path: <共享根>/settings.yaml` | `dsh-settings-file` `Config{path,dshHome,watch,debounceMs}`，`path` 优先 |
| `credentials` | `path: <共享根>/.credentials.yaml` | `dsh-credentials-local` 同构 `Config`（`lib/index.js:58,393-398`） |
| `agent-presets` | `roots=[<共享根>/.agent-presets]` + `includeUserRoot:false` + `default: standard` | 用户根默认是 `dshHomePath('.agent-presets')`（按项目）；`default` 是 **required**，整份替换必须重述；`copy()`/`remove()` 只认**第一个 user trust 根** |
| `skill-filesystem` | `dshHome: <共享根>` | 用户技能根 = `<dshHome>/skills`；项目根 `<项目>/.dsh/skills` 不受影响 |

**数据面保持隔离（无回归）**：dsh 全库只有两处 `dshHomePath(...)`——
`dsh-base/cordis.patch.yml:101` 的 `sessions` 与 `dsh-web-app/cordis.patch.yml:57` 的 `storages`，
都继续落在每项目 DSH_HOME，因此"切项目后工作区不残留"（v0.1.3-dev 修复）保持成立。

**MCP 脚本零链接（v0.2.4）**：旧实现在每个项目 DSH_HOME 顶层建 `node_modules` junction，
只为让 `mcp-ide-server.mjs`（当时部署在该目录）能解析 `@modelcontextprotocol/sdk` 与 `zod`。
现改为把脚本部署到 `<运行时根>/.dsh-ide-bridge/`——该位置向上查找 `node_modules` 会命中
`<运行时根>/dsh/node_modules/`（dsh 自身依赖树），**无需任何链接**，全局只有一份脚本。
运行时根不可写时降级为 `<共享根>/.dsh-ide-bridge/` + 那里唯一一个链接。

**junction 体积的实测方法（避免误判）**：`Get-ChildItem -Recurse` **会跟随** junction，把
被指向的运行时树（约 201.7MB）重复计入每个项目。正确做法是按文件属性判断或排除该目录：
```powershell
(Get-Item <项目DSH_HOME>\node_modules -Force) | Select-Object Length, Attributes, Target
# Length=1, Attributes=Directory, ReparsePoint  → 目录链接本身不占空间
```
实测（本机 5 个项目）：排除 junction 跟随后的项目数据仅 0.01 / 0.01 / 0.01 / 0.14 / 1.80 MB。

**磁盘增长的真正来源（后续独立任务）**：每项目 DSH_HOME 下的 `sessions/`（`.jsonl.zstd`）、
`storages/`、`attachments/v1/objects/<sha256>`（`dsh-attachment-local`，README 明确
"Objects are retained indefinitely; reference-aware garbage collection is deferred"）。
这些是数据面，按项目独立、无法共享，需要单独做清理入口。

**迁移（`SharedConfigMigrator`）**：一次性（标记 `<共享根>/.plugin-layout-version` = 插件版本）把各项目
DSH_HOME 里的 `settings.yaml` / `.credentials.yaml` 与共享文档**文本级合并**（共享侧优先，只补缺：
缺失的顶层 namespace、缺失的 `refs` 键与 `records` 条目；扁平凭据先内联升级为 `version:1`），原文件
**移入** `<共享根>/migrated/<hash>/` 备份。失败逐项目降级且**不写标记**（下次启动重试）。
插件不再参与配置同步：`syncCredentials()` 改为 `YamlText.upsertRef` 合并写入（只替换
`refs.DEEPSEEK_API_KEY`，保留其它 `refs` 与整个 `records`）——旧实现是扁平整份覆盖，会抹掉
dsh 自己的 `records.client-connection/browser-session` 与其它 provider 密钥。

### v0.2.4 输入框注入修复（"选中代码右键发送 / 日志一键解释 没反应"，真实页面 CDP 实测）

**现象**：编辑器右键"发送选中代码到 DSH"、运行控制台右键"DSH 一键解释"点击后没有任何可感知反馈。

**根因（真实 dsh 0.1.5 页面实测）**：

1. **输入框不再是 `<textarea>`**。0.1.5 的 composer 是 Lexical：
   `<div data-lexical-editor="true" role="textbox" contenteditable="true">`。实测 `textarea` 选择器命中 **0**，
   旧脚本 `document.querySelector('textarea')` 永远找不到元素 → 8s 后超时，什么都不做。
2. **回读判定读错了地方**。Lexical 把文本放在 `[data-lexical-text="true"]` 节点里；写入**当拍**
   根元素的 `innerText`/`textContent` 可能为空（异步渲染）。实测：注入其实成功了，但"写后立即回读"
   得到空串 → 被判成失败 → 降级剪贴板 + 通知。这是"看起来没反应"的直接来源。
3. **输入框只在进入会话后才渲染**。实测：页面停在内测声明 / "选择工作区"时，`[contenteditable]` 命中 **0**；
   必须选中工作区（进入会话）后 composer 才存在。若注入发生在页面切换期，必须重试等待。

**验证方法（可复用）**：启动临时 dsh web（真实 0.1.5 运行时）→ 用 Edge headless + CDP
（`--remote-debugging-port`，Node 原生 `WebSocket` + `Runtime.evaluate`）打开带 `?token=` 的启动 URL →
页面内 `POST /api/workspace/create`（path 必须是**真实存在**的目录，dsh 会 `realpath` 校验）→ reload →
点击工作区行进入会话 → 在页面内执行候选注入脚本并回读判定。实测结论：

| 写入方式 | 结果 |
|---|---|
| `document.execCommand('insertText', false, text)` | ✅ 生效（且派发 `beforeinput`，Lexical 据此同步内部状态） |
| 合成 `ClipboardEvent('paste')` | ⚠️ 事件可见，但作为主路径不可靠（仅作兜底） |
| 合成 `InputEvent('beforeinput')` | ❌ 不触发 Lexical 写入 |
| 直接改 DOM + `input` 事件 | ❌ Lexical 状态不同步 |
| 写后**当拍**读 `innerText`/`textContent` | ❌ 读不到（误判失败） |
| 读 `[data-lexical-text="true"]` + 轮询 200ms | ✅ 立即命中 |
| 派发 `KeyboardEvent('keydown', key/code/keyCode/which=Enter)` | ✅ 501ms 内提交、输入框清空 |

**修复**：新增 `ComposerScripts`（纯函数、可单测）统一构造注入脚本——选择器顺序
`textarea` → `[data-lexical-editor="true"]` → `[contenteditable="true"][role="textbox"]` → `div[contenteditable="true"]`；
contenteditable 用 `execCommand('insertText')` 写入（失败退回合成 `paste`）；回读走 `[data-lexical-text]` 节点
并轮询 ≤2s；提交用带 `keyCode/which` 的 Enter，再轮询编辑器清空判 `submitted`，否则兜底点发送按钮
（绝不用 class 通配）；结果经 JBCefJSQuery 回传 `injected` / `submitted` / `blocked` / `notfound` / `failed`，
失败才降级剪贴板 + 通知（不再"脚本已下发"即乐观提示）。

**教训**：涉及前端 DOM 的注入，**必须在真实页面验证**（CDP 是最省事的办法）；"写成功"不等于"读得到"，
验证逻辑本身也要实测。

**后续（用户截图报告）：引用重复**。现象 `@…application.yml#L5-15@…application.yml#L5-15`。
**真实原因（CDP 实测）**：不是"偶尔重复"，而是**每次都追加一份**——`insertText` 前没有任何判重，
所以每触发一次就多一个引用 chip（用户清空后"不重复"只是因为清空后只剩一份）。
两个被我最初实现掩盖的细节：

1. **dsh 把 `@路径` 渲染成文件引用 chip**：`<span class="…textRef" data-composer-text-ref=""
   data-lexical-text="true">`。实测 chip 的 `textContent` 只覆盖路径的一部分（如 `@E:/code/proj/`），
   文件名与 `#L5-15` 落在相邻的普通文本节点里，且**节点之间不含空白**。
   → 回读/判重若按"空白分词"或"两侧带空白边界匹配"，就会把**已存在**的引用判成不存在而重复注入。
   正确做法：**去掉全部空白**再比较（`squash`）。
2. **空串包含会误判成功**：空编辑器回读为 `''`，`''` 被任何串包含 → 旧实现的"双向包含"判定
   会把"什么都没写进去"当成成功并返回 `injected`。
3. **Lexical 空编辑器首次 `insertText` 可能整段不生效**（实测：读回仍为空）→ 必须"轮询确认 +
   8s 窗口内重试 + 内容长度增长兜底"，不能一次写不进去就报 failed / 就认为已注入。

**修复（三层）**：① 编辑器侧判重（忽略空白比较 + 两边非空 + 写入后轮询/重试）；
② 面板侧 `SendSelectionRefs` 1200ms 同引用时间窗判重（`sendSelection` 此前**没有在途守卫**，
`sendQuestion` 有）；③ 引用仍照常进 Bridge 队列。附带把引用构造与判重抽为纯对象 `SendSelectionRefs` 便于单测。
**验证**：真实 dsh 0.1.5 页面 + CDP，连续注入同一引用 3 次 → 第 1 次 `injected`、第 2/3 次
`skipped-existing`，**引用 chip 数恒为 1**。

**附：文件引用 chip 是 dsh 原生能力，但对"文件路径"不可用（重要，别再踩）**。
dsh 的输入框把文本 reference 渲染成 chip，规则在
`dsh-client-ui-conversation/lib/client.js:12167-12201`：

```js
const TEXT_REF_RE   = /(^|\s)([/@])([\w-]+)/g;                  // @/ + 单词字符 + 名字命中词表
const FOLDER_REF_RE = /(^|\s)(@(?:"[^"\n]*\/|[^\s"]+\/))/g;      // 只认"以 / 结尾"的 token
```

实测（CDP）：`@E:/code/proj/`（目录，结尾 `/`）**会**被渲染成 chip；
`@E:/code/proj/application.yml#L5-15`（文件 + 行号）**不会**——`FOLDER_REF_RE` 要求结尾是 `/`。
所以插件注入的"文件 + 行号"引用**在机制上无法 chip 化**；用户看到的"文件名 chip + 行号"来自
dsh 对**目录**引用的渲染。插件保持注入 `@绝对路径#L起始-结束` 即可：上游将来支持文件 chip 时无需改契约。

**判重实测（用户真实长路径格式，CDP 三次连续注入）**：
```
#1 outcome=injected          refs=1   ← 首次写入
#2 outcome=skipped-existing  refs=1   ← 正确跳过
#3 outcome=skipped-existing  refs=1   ← 正确跳过
```
判据（缺一不可）：① 去掉**全部空白**再比较（chip 的 textContent 只覆盖片段且节点间无空白）；
② 两边**都非空**（空串包含会把"没写进去"误判成成功）；③ 回读内容**长度量级相近**
（`got.length >= want.length * 0.8`，防止只读到 `@…/resources/` 这类片段就误判"已存在"而漏写）。

**真正的重复根因（v0.2.4 第二轮实测，用用户短路径复现）**：**"双写"**——`write()` 曾在 `insertText`
之后用"立即回读是否出现目标文本"来决定**是否再补一次 `paste`**，而
`document.execCommand('insertText')` 是**同步生效、但 Lexical 异步更新 DOM** 的：回读当拍仍为空
→ 判定"没写进去" → 又派发 `paste` → **同一份写了两遍**。实测 trace：

```
first : cmdRet=true → after='@E:/code/cfca/cfcaSDKDemo/pom.xml#L7-11@E:/code/cfca/cfcaSDKDemo/pom.xml#L7-11'
second: outcome=skipped-existing   ← 第二次点击被正确跳过（说明面板/判重本身没问题）
```

即"一次点击就出现两份"。**修复：写入路径只保留 `insertText` 一条**，失效一律交给外层
"轮询 ≤1.2s + 8s 窗口内重试"兜底（绝不能在同一轮里叠加第二种写入机制）。修复后实测：
`first: injected` → `second: skipped-existing` → **finalText 中引用出现次数 = 1**。

**教训**：对 Lexical 这类"异步渲染的受控编辑器"，**不能在写入后立即回读做补救决策**；
"写入路径唯一 + 异步重试"才是安全形态。

### FileChooser 隐藏 .zip（v0.2.3 实测：设置页"Choose local runtime zip…"看不到文件）

- **现象**：设置页选本地运行时 zip 时，文件选择器**只显示文件夹、看不到任何 .zip**（用户截图）。
- **根因（IDEA 2024.1 `app-client.jar` 字节码核实）**：`FileChooserDescriptor.isFileVisible` 对
  `FileElement.isArchive(file)` 为真的文件（**`.zip` 属于归档**）有特判——
  `if (isArchive && !myChooseJars && !myChooseJarContents) return false;`
  即 **`chooseJars=false` 时归档文件直接不可见，与 `chooseFiles`、`withFileFilter` 都无关**
  （filter 只在通过可见性后生效；`isFileSelectable` 才依赖 fileFilter）。
- **修复**：构造 descriptor 时 `chooseJars = true`（`FileChooserDescriptor(true, false, true, false, false, false)`）；
  并按用户要求**不做扩展名过滤**，显示所有类型文件（选中内容由 `provisionFromLocalZip` 做结构 + SHA-256 校验）。
  两处导入入口都已改：设置页 `DshSettingsConfigurable` 与工具窗口错误卡 `DshToolWindowFactory.provisionLocalZip`。
- 通用教训：任何"选 zip/jar 文件"的 FileChooser 都必须开 `chooseJars`，否则文件根本列不出来。
- **后续实测（错误卡给了 `Cause: path=…; isRegularFile=false; exists=false`）**：文件选择器能列出并选中该 zip
  （VFS 可见），但同一路径交给 NIO 时 `Files.exists=false`（该环境 NIO 看不到文件；远程/虚拟文件系统或安全软件拦截皆可能），
  于是被误判为"不是有效的运行时 zip"。→ 新增 `RuntimeZipStaging.resolve(VirtualFile)`：**以 VFS 为准**——
  优先用 `VfsUtilCore.virtualToIoFile` 的 io 路径，仍不可见时经 `file.inputStream` 复制到
  `<config>/dsh-idea/imported-runtime.zip` 再导入；两处入口（工具窗口/设置页）统一走它。
- 诊断增强：`provisionFromLocal` 的两条 LOCAL_INVALID 分支记录完整路径、`exists`/`size`，并作为 detail 显示在错误卡
  （此前只有一句笼统提示）；本地导入失败不再显示 `Failed URL: <文件名>!`。
- **设置页补齐「运行时目录」字段**（此前文档写了、代码没有）：`DshSettingsState.runtimeDirectory` +
  `DshSettingsConfigurable` 的 `TextFieldWithBrowseButton`（目录选择器）；
  `DshHomeManager.runtimeRoot()` 优先级 = **环境变量 `DSH_IDEA_RUNTIME` > 设置页运行时目录 > 默认下载目录**
  （解析逻辑抽成纯函数 `resolveRuntimeRoot` 并有单测）；两者都属"显式指定"，内容缺失时报错、不回退下载
  （`hasExplicitRuntimeRoot()`）。
- 设置页地址/目录两行的**反显 + 「默认」按钮**（用户要求）：`Runtime download URL` 输入框反显**当前生效地址**
  （未覆盖时 = 当前版本/平台的完整默认 URL），右侧「默认」按钮一键恢复；取值支持"目录级 base（可含 `{version}`）"与
  "到文件的完整 URL（`.zip` 结尾）"两种形式（`RuntimeAssetSpec.urlFor` 对后者原样返回、不再拼接资产名）；
  与默认等价的取值**不落盘**（`DshHomeManager.isDefaultRuntimeDownloadUrl` 判定，保存 null，避免把版本/平台钉死）。
  `Runtime directory` 同样带「默认」按钮（清空 = 走默认下载/缓存目录）。原"单独一行只读 URL 回显 + Copy"已移除。
- 续（用户要求）：`Runtime directory` 的「默认」按钮改为**填入完整的默认目录路径**（`<config>/dsh-idea/runtime/<DSH_VERSION>`），
  与输入框初值/revert 保持一致；同时把"填入默认目录"定义为**与未指定等价**——
  `DshHomeManager.isDefaultRuntimeDirectory()`（`samePath` 规范化比较，忽略分隔符/大小写/尾点）+ `hasExplicitRuntimeRoot()`
  排除默认值 + `apply` 不落盘默认值，从而不会掉进"显式指定但目录为空 → 不下载"的死角。
- 旁证：同一个 `build/runtime-win-x64.zip` 在 JDK 17 与 JBR 21 下均能正常打开（31311 条目、`node/node.exe` 与
  `dsh/…/bin.js` 齐备），即 zip 内容无问题——问题只在"插件进程如何拿到这个文件"。
- 同屏附带修复：工具窗口错误卡文案里的 `<br>` 被 `showError` 的 `escapeHtml` 转义成字面文本（用户截图中可见
  `Runtime download cancelled.<br><br>Failed URL: …`）——现改为把转义后的 `&lt;br&gt;` 还原为 `<br>` 并同时渲染 `\n`。

### 运行控制台一键解释（v0.1.3-dev，FR-11 实测/源码验证）

- **右键组 id**：Run 控制台右键菜单组是 **`ConsoleView.PopupMenu`**（不是 `ConsoleEditorPopupMenu`）。
  两版本源码核实：2024.1.7 `ConsoleViewImpl.java:93`（`CONSOLE_VIEW_POPUP_MENU = "ConsoleView.PopupMenu"`）
  与 2026.2 `ConsoleViewImpl.kt:1668` 同值；弹窗经 `ContextMenuPopupHandler` 挂在控制台 editor 上，
  `CommonDataKeys.EDITOR`/`PROJECT` 可用，选中文本读 `editor.selectionModel.selectedText`。
- **dsh composer 提交机制**（`dsh-client-ui-conversation/lib/client.js`，dsh 0.1.0-rc.7 实测 / 0.1.1-rc.2 复验；
  **0.1.5-rc.2 已变更**：composer 改为 Lexical contenteditable，`<textarea>` 定位失效——见上文 0.1.5 升级章节）：
  - composer 文本区即页面 `<textarea>`（`document.querySelector('textarea')`），React 受控，原生 setter + `input` 事件可驱动（现有注入已验证）；
  - `onKeyDown`：非 shift 的 Enter → `keyboard.arbitrate("enter") === "pass"` → `keyboard.submit(resolveSubmitMode(...))`；
    智能体忙时默认 `busyEnter=queue` → **消息入队仍送达**；`machineBusy` 时不会静默丢弃（提交后 composer 清空）；
  - 发送按钮 `aria-label` 实际为 **"Send message"**（en）/ **"发送消息"**（zh）（`t("input.send")`）；
    运行中时主按钮变"停止"（`primaryStops`），**回退点击发送按钮绝不能用 class 通配**（避免误点"停止"）。
- **JBCefJSQuery**：`com.intellij.ui.jcef`（2024.1 平台核心 / 2026.2 jcef 插件，API 一致）：
  `create(JBCefBrowserBase)` + `addHandler(Function<String, Response>)` + `getFuncName()`；
  JS 侧 `window.<funcName>({request, onSuccess, onFailure})` 回传字符串。
  **必须在 `loadURL` 之前创建**（CEF message router 在页面加载时注入 `window.<funcName>`，之后创建函数不存在）；
  实现 `JBCefDisposable`，随 browser dispose。
- 发送判定：注入后轮询 ≤3s 判 textarea 值清空 = 提交成功（**不要用 `disabled` 判成功**——composer 锁定态
  也可能 disabled 但草稿仍在）；未清空再回退点击发送按钮复核。

### 切换项目后工作区为旧项目（v0.1.3-dev 实测修复）

- **现象**：同窗口切换项目 A→B 后，工具窗口仍显示 A 项目的工作区（用户实测复现，截图见
  顶部出现**两个 "DeepSeek Harness" 标签**）。
- **根因（最终确认，用户实测反例驱动）**：
  - dsh 的 workspace 注册表（`workspace.json`）与会话数据**全局共享**（同一 DSH_HOME），
    `workspace.create` 对**新路径**才 prepend 到最前、对**已存在路径幂等返回既有实体、不重置其
    状态**；dsh 记住了已打开项目的 workspace/会话状态。
  - **决定性反例**：用户切到**从未打开过的新项目**无问题（workspace 全新、无历史状态）；切到
    **之前打开过的旧项目**则复现（workspace 已存在、dsh 恢复其历史会话状态 → 工作区框显示旧项目）。
  - 曾误判：①"UI 默认落点=列表第一个"（错——`workspace.json` 顺序已正确但 UI 仍显示旧项目）；
    ②"工具窗口 content 残留"（用户澄清顶部第二个"DeepSeek Harness"是标题/logo，实际单面板无残留）；
    ③"localStorage `dsh.sessions.current` 恢复当前会话"（测得最新 zip 清空后仍复现，排除）。
- **修复（最终，用户确认方案）**：**每个项目使用独立 DSH_HOME**（`DshHomeManager.homeDir(projectPath)`
  = `configDir/dsh-idea/dsh-home/<MD5(projectPath)前16位>`）——dsh 工作区注册表与会话数据按项目
  隔离，切到任何项目（新旧都一样）工作区都从当前项目"白纸"开始，**从机制上杜绝残留**。
  - 补充保留：`WorkspaceInitializer.ensureWorkspace` 的 `workspace.insertBefore`（当前项目置顶）、
    `DshToolWindowPanel.onUrlReady` 清 localStorage `dsh.sessions.current` + reload（防御）、
    `createToolWindowContent` 旧 content 去重 + `dispose()` 幂等（防御）。
  - API Key：`syncCredentials(projectPath)` 项目启动时从 PasswordSafe 写入各项目 DSH_HOME；
    设置页 apply 用 `syncCredentialsAll()` 同步到当前所有打开项目。

**dsh workspace RPC 备忘（实测 dsh 0.1.0-rc.7 / 0.1.1-rc.2 复验）**：
- 响应格式：`{"type":"server-response","rpcId":"...","result":{"ok":true,"value":{...} | "error":"..."}}`
  —— `ok/value/error` 都在 **`result`** 里（**不在顶层**）；首次用顶层 `ok` 解析导致 create 误判失败。
- `workspace.create`：幂等（已存在路径返回既有实体，`value.workspace` + `created:bool`）；新路径才 prepend 到 `workspaceIds`。
- `workspace.list`：`value.items[].workspaceId`；显示顺序 = `workspaceIds` 数组顺序。
- `workspace.insertBefore`：payload `{workspaceId, beforeWorkspaceId?}`（anchor 省略 = 追加末尾），
  响应 `value.workspaceIds`（完整新顺序）。
- `workspace.json` 落盘路径为 **Windows 反斜杠**（如 `D:\proj\MyApp`），断言/比较注意分隔符。

---

## 5. 打包 / 运行时（Step 5 实测）

- 链路（v0.2.0 起跨平台，`scripts/build-runtime.mjs`，任意主机，默认取当前主机 os/arch）：下载 Node 22.23.2（按平台选 `win-*.zip`/`darwin-*.tar.gz`/`linux-*.tar.gz`，SHA-256 从同版本 `SHASUMS256.txt` 校验）→ 归一化 `node/<nodeBin>` → npm 装 `@deepseek-ai/dsh@0.1.5-rc.2` 到 `dsh/` → 冒烟 → `--bundle` 产出 `build/runtime-<os>-<arch>.zip`（**zip 根直接 `node/`+`dsh/`**，排除源包与 npm 缓存）+ 同名 `.sha256` 侧车。
- 分发：瘦身默认（thin）不把运行时打入插件 jar；`-Pthin=false` 时 `bundleRuntime` 把当前平台 zip 复制为 `build/plugin-runtime/runtime-bundle.zip` 作为插件资源（fat / 离线备选）。
- 运行期自举（v0.2.0 引入）：`DshHomeManager.hasRuntime()` → 本地缺失且无 `DSH_IDEA_RUNTIME` 时，fat 安装从插件资源解压；**瘦身默认不捆绑 ~93MB 运行时**，经 `RuntimeProvisioner` 按平台从 `runtime-assets.json` 资产地图下载 `runtime-<os>-<arch>.zip` + 同名 `.sha256`，**SHA-256 校验**后安全解压到 `<config>/dsh-idea/runtime/<DSH_VERSION>`（幂等；`unzip` 兼容顶层单目录前缀剥离 + zip-slip 防护），离线/升级复用。`DSH_IDEA_RUNTIME` env 或设置页 runtime-directory 可跳过下载；fat（`-Pthin=false`）直接 bundle、不下载。下载 URL 与超时可配置。
- 下载可靠性（**v0.2.1 加固**）：连接池化、HTTP/2 的 `java.net.http.HttpClient` + 浏览器 User-Agent + **60s 连接超时 + 可配置读超时 + 退避重试**——慢速/不稳定网络（如大陆访问 GitHub）也能成功；首次使用下载失败（临时文件父目录缺失 → `NoSuchFileException`）已修复：**先建父目录再写文件**（v0.2.1）。
- 下载 UX（**v0.2.1**）：工具窗口下载进度条（connecting/verifying/downloading）+ **取消**；错误卡显示**失败的确切 URL + 底层原因 + Restart**；设置页回显**当前平台精确下载 URL（到文件）** + 一键复制 + 可配置下载超时 + **"Choose local runtime zip…" 本地 zip 离线导入**（内容校验 + SHA-256 对照 `.sha256` 侧车）。
- 发布现状注意：**macos-x64（Intel Mac）运行时无法在 GitHub-hosted runner 构建（Intel macOS 已退役）**——
  v0.2.3 起改为**本地交叉构建后手动上传**（`release-assets/runtime-macos-x64.zip`），该资产不再缺失；`linux-arm64` 同理。
- **v0.2.3 发布记录（2026-09-16）**：GitHub Release `v0.2.3` 共 **11 个资产**（插件 zip + 5 平台运行时及其 `.sha256`）——
  CI 用各平台原生 runner 覆盖了 win-x64 / macos-arm64 / linux-x64，**macos-x64 与 linux-arm64 由本地交叉构建上传**；
  JetBrains Marketplace update **id=1171727**（`approve=false` 待审，`since/until = 241.0 — 262.*`）。
  本次 `github.com:443` 不通，代码推送、tag、Release 与资产上传**全程走 REST API**（见 `RELEASE_PROCESS.md` §8）。
- **跨 OS 原生依赖**：dsh 树含平台专属原生依赖（`@img/sharp-*`、`@koromix/koffi-*`、`node-addon-require-builtin-*`，被 dsh-subprocess-local/dsh-attachment-local/cordis-plugin-loader import），**不能跨平台共享一个 dsh 树**；运行时必须按目标 OS 生成。推荐 CI 矩阵在各目标 OS runner 构建；用 npm `--os/--cpu` 交叉仅作捷径（有变体不全风险）。
- 插件包：瘦身约 1.8MB（不含运行时）；fat 约 93–98MB（含压缩运行时）。
- 跨平台运行时（v0.2.3）：**五个平台资产已全部重建为 dsh 0.1.5-rc.2**（win-x64 103.8MB / macos-arm64 118.7MB /
  macos-x64 120.7MB / linux-x64 126.0MB / linux-arm64 125.7MB，均含 `.sha256` 侧车，落在 `release-assets/`），
  补齐了此前缺失的 **macos-x64**（Intel Mac，CI 无 runner）与 **linux-arm64**。
  交叉构建（Windows 主机产出 Unix 运行时）由 `scripts/build-runtime.mjs` 支持，踩到并修掉三个坑：
  ① Node Unix 包内 `bin/npm|npx|corepack` 是符号链接，Windows 创建需特权 → tar 失败时若 `bin/node` 已就位则继续；
  ② 探测/npm/冒烟不能用目标平台 node（本机无法执行）→ 交叉时改用**主机** node + `--os/--cpu`；
  ③ Linux 变体带 libc 后缀，交叉安装必须显式 `--libc glibc`，否则静默跳过 `*-linux-*-gnu`（实测包数 519→522、zip 118→126MB）。
  打包命令按**主机**能力选择（Windows `tar -a` / Unix `zip`）。
- IDE 版本边界验证（v0.2.3）：`since-build=241` / `until-build=262.*`；在 **2024.1.7（默认，含全部测试）**、
  **2024.3.2**、**2026.2** 三处分别跑 `compileKotlin` + `compileTestKotlin` 均成功（`-PplatformVersion=X`
  注意在 PowerShell 中需加引号，否则 `2026.2` 会被拆成两个参数报 `Task '.2' not found`）。
- Node 版本：**v22.23.2**（npmmirror/官方二进制镜像，SHA-256 校验）；dsh 固定 `@deepseek-ai/dsh@0.1.5-rc.2`。

---

## 6. 测试体系

| 层 | 类 | 说明 |
|---|---|---|
| 单元 | PortParserTest(4) / McpPatchGeneratorTest(6) / SnapshotDiffTest(5) / PathFiltersTest(5) / SentSelectionQueueTest(5) / WorkspaceInitializerTest(12) / DshRuntimeRegistryTest(3) / CredentialImporterTest(4) / **JsonCodecTest(v0.1.1,9)** / **ExplainLogComposerTest(v0.1.3-dev,4)** / **LegacySessionMigratorTest(v0.1.3-dev,14)** / **DshCredentialsMaskTest(v0.1.3-dev,10)** / **DshCredentialsSyncTest(v0.1.3-dev,5)** / **PlatformTest(v0.2.0,10)** / **RuntimeAssetsTest(v0.2.0,6)** / **RuntimeProvisionerTest(v0.2.0,7)** | 纯 JUnit，无 IDE 依赖 |
| 集成冒烟 | DshBootstrapSmokeTest(真实 dsh 启动 + workspace 注册断言) / DshMcpBridgeSmokeTest(mock bridge + MCP tools/list 6 工具 + failOnStartupError 严格启动) / WorkspaceInitializerSmokeTest(切换项目工作区顺序) / **LegacySessionMigratorSmokeTest(v0.1.3-dev：zstd session 迁移后 workspace.json 自动挂接)** | 需 `DSH_IDEA_RUNTIME`，否则跳过 |

- 冒烟测试注意：临时 DSH_HOME 内 dsh 自愈 junction 指向 runtime → **tearDown 必须先 unlinkJunctions 再让 `@TempDir` 清理**，否则清空 runtime（见 §4）。
- 测试计数：**122 个**（截至 v0.2.1，**全部通过、0 失败**；沙箱下需完整权限，否则 Gradle native 服务初始化失败）。上表括号内数字为该测试类引入/记录版本的用例数（记录值，如 v0.2.0 的 10/6/7），v0.2.1 新增用例未逐类复列——类覆盖以上表为准，**当前总数以 122 为准**。

---

## 7. 后续任务参考（Step 6 之后）

1. ~~**Step 6 里程碑评审**~~ ✅ 已完成（2026-08-20）：见 docs/MILESTONE_REVIEW.md —— Step 0–5 总结、FR/US/非目标覆盖矩阵、
   遗留问题 A（手工验收 5 项）/B（功能缺口）/C（技术债）/D（文档债）分级、PRD §9 风险回顾、v0.6/v0.7/v1.x 规划。
2. **v0.6 验收闭环**（next）：PRD §7 手工验收项（见 docs/ACCEPTANCE.md 与 MILESTONE_REVIEW §3-A）——
   安装 zip、真实 API Key 对话、DiffManager UI、JCEF 注入效果、进程清理端到端——需真实 IDE 会话人工确认；
   另含 B-1"发送当前文件"动作、C-6 杀软白名单文档（**C-5 版本号对齐已于 v0.2.0/v0.2.1 发版完成，当前插件版本 0.2.1**）。
3. **已知改进项**（MILESTONE_REVIEW §3-B/C 明细）：
   - `org.jetbrains.intellij` 1.17.4 → 2.x（`org.jetbrains.intellij.platform`）升级（C-1）。
   - dsh 版本升级：改 `DshHomeManager.DSH_VERSION` + 重建运行时（重建脚本现已改用 `scripts/build-runtime.mjs` 参数化，见 §5；build-runtime.ps1 已弃用）+ 回归 Step 3 patch 语法（C-2）。
   - 多项目并发 3 上限后续可优化为单实例多工作区（C-3）。
   - `ide_open_file`/`ide_reveal_file` 目前实现为"打开"，项目树定位（reveal）可再增强（B-3）。
   - MCP patch 的 `failOnStartupError` 仅测试形态；生产可用 `reconnect` 语义（C-4）。
   - 远程开发/Gateway 环境检测提示未实现（B-2）；输入框文件引用 chip 为上游 dsh 能力，随版本升级跟踪（B-4）。
4. **文档约定**：改代码前先更新 PRD/DESIGN（活文档）；每个实现步骤后在 README 变更记录追加。
