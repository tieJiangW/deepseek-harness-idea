# 项目知识库 / 开发备忘（跨会话参考）

> 本文汇总 DeepSeek Harness IDEA 插件开发过程中的**实测环境事实、踩坑记录、dsh 行为结论**，
> 供后续任务（Step 6 评审及之后的维护/升级）直接参考，避免重复调查。
> 最后更新：2026-09-02（**v0.2.1**：运行时供应 UX + 下载可靠性——首次使用下载失败修复、
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
| 版本号 | 插件版本 = `build.gradle.kts` 第 13 行 `version`；`DshHomeManager.DSH_VERSION`（= dsh 运行时版本 `0.1.1-rc.2`，决定生产运行时目录名；勿随意改，升级=重建运行时） |
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

## 3. dsh 行为事实（0.1.1-rc.2 实测结论；早期 0.1.0-rc.7 结论经 0.1.1-rc.2 复验兼容）

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

### 运行控制台一键解释（v0.1.3-dev，FR-11 实测/源码验证）

- **右键组 id**：Run 控制台右键菜单组是 **`ConsoleView.PopupMenu`**（不是 `ConsoleEditorPopupMenu`）。
  两版本源码核实：2024.1.7 `ConsoleViewImpl.java:93`（`CONSOLE_VIEW_POPUP_MENU = "ConsoleView.PopupMenu"`）
  与 2026.2 `ConsoleViewImpl.kt:1668` 同值；弹窗经 `ContextMenuPopupHandler` 挂在控制台 editor 上，
  `CommonDataKeys.EDITOR`/`PROJECT` 可用，选中文本读 `editor.selectionModel.selectedText`。
- **dsh composer 提交机制**（`dsh-client-ui-conversation/lib/client.js`，dsh 0.1.0-rc.7 实测 / 0.1.1-rc.2 复验）：
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

- 链路（v0.2.0 起跨平台，`scripts/build-runtime.mjs`，任意主机，默认取当前主机 os/arch）：下载 Node 22.23.2（按平台选 `win-*.zip`/`darwin-*.tar.gz`/`linux-*.tar.gz`，SHA-256 从同版本 `SHASUMS256.txt` 校验）→ 归一化 `node/<nodeBin>` → npm 装 `@deepseek-ai/dsh@0.1.1-rc.2` 到 `dsh/` → 冒烟 → `--bundle` 产出 `build/runtime-<os>-<arch>.zip`（**zip 根直接 `node/`+`dsh/`**，排除源包与 npm 缓存）+ 同名 `.sha256` 侧车。
- 分发：瘦身默认（thin）不把运行时打入插件 jar；`-Pthin=false` 时 `bundleRuntime` 把当前平台 zip 复制为 `build/plugin-runtime/runtime-bundle.zip` 作为插件资源（fat / 离线备选）。
- 运行期自举（v0.2.0 引入）：`DshHomeManager.hasRuntime()` → 本地缺失且无 `DSH_IDEA_RUNTIME` 时，fat 安装从插件资源解压；**瘦身默认不捆绑 ~93MB 运行时**，经 `RuntimeProvisioner` 按平台从 `runtime-assets.json` 资产地图下载 `runtime-<os>-<arch>.zip` + 同名 `.sha256`，**SHA-256 校验**后安全解压到 `<config>/dsh-idea/runtime/<DSH_VERSION>`（幂等；`unzip` 兼容顶层单目录前缀剥离 + zip-slip 防护），离线/升级复用。`DSH_IDEA_RUNTIME` env 或设置页 runtime-directory 可跳过下载；fat（`-Pthin=false`）直接 bundle、不下载。下载 URL 与超时可配置。
- 下载可靠性（**v0.2.1 加固**）：连接池化、HTTP/2 的 `java.net.http.HttpClient` + 浏览器 User-Agent + **60s 连接超时 + 可配置读超时 + 退避重试**——慢速/不稳定网络（如大陆访问 GitHub）也能成功；首次使用下载失败（临时文件父目录缺失 → `NoSuchFileException`）已修复：**先建父目录再写文件**（v0.2.1）。
- 下载 UX（**v0.2.1**）：工具窗口下载进度条（connecting/verifying/downloading）+ **取消**；错误卡显示**失败的确切 URL + 底层原因 + Restart**；设置页回显**当前平台精确下载 URL（到文件）** + 一键复制 + 可配置下载超时 + **"Choose local runtime zip…" 本地 zip 离线导入**（内容校验 + SHA-256 对照 `.sha256` 侧车）。
- 发布现状注意：**macos-x64（Intel Mac）运行时无法在 GitHub-hosted runner 构建（Intel macOS 已退役）→ 不在发布资产中，该平台下载 URL 会 404，需在其他主机构建后补传**。
- **跨 OS 原生依赖**：dsh 树含平台专属原生依赖（`@img/sharp-*`、`@koromix/koffi-*`、`node-addon-require-builtin-*`，被 dsh-subprocess-local/dsh-attachment-local/cordis-plugin-loader import），**不能跨平台共享一个 dsh 树**；运行时必须按目标 OS 生成。推荐 CI 矩阵在各目标 OS runner 构建；用 npm `--os/--cpu` 交叉仅作捷径（有变体不全风险）。
- 插件包：瘦身约 1.8MB（不含运行时）；fat 约 93–98MB（含压缩运行时）。
- Node 版本：**v22.23.2**（npmmirror/官方二进制镜像，SHA-256 校验）；dsh 固定 `@deepseek-ai/dsh@0.1.1-rc.2`。

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
