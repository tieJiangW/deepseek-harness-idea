# 运行时发布与资产契约（macOS / Linux 兼容）

> 本文档描述瘦身通用插件的**按平台运行时**如何构建、发布、被插件下载校验。适用 v0.2.0 起（当前 v0.2.1）。

## 1. 为什么按平台分发

`@deepseek-ai/dsh` 的 npm 树含**平台专属原生依赖**（`@img/sharp-*`、`@koromix/koffi-*`、
`node-addon-require-builtin-*`，被 `dsh-attachment-local` / `dsh-subprocess-local` /
`cordis-plugin-loader` 运行时 import）。因此运行时**不能跨平台共享一个 dsh 树**，必须按目标 OS 生成。

JetBrains Marketplace 单一版本只能传一份 zip（≤200MB），而自包含运行时约 100MB/平台。为覆盖
macOS / Linux 且保持单一 Marketplace 产物，采用**瘦身通用插件 + 首次运行按平台下载运行时**。

## 2. 目标平台矩阵与资产名

| 目标 | os.name | os.arch | Node 发行包 | 资产名 | Node 可执行 |
|---|---|---|---|---|---|
| Windows x64 | windows | amd64 | win-x64.zip | `runtime-win-x64.zip` | `node/node.exe` |
| macOS arm64 | mac | aarch64 | darwin-arm64.tar.gz | `runtime-macos-arm64.zip` | `node/node` |
| macOS x64 | mac | x86_64 | darwin-x64.tar.gz | `runtime-macos-x64.zip` | `node/node` |
| Linux x64 | linux | amd64 | linux-x64.tar.gz | `runtime-linux-x64.zip` | `node/node` |
| Linux arm64（可选） | linux | aarch64 | linux-arm64.tar.gz | `runtime-linux-arm64.zip` | `node/node` |

- **资产内容**：zip 根为 `node/` + `dsh/`（无额外前缀；`node/<nodeBin>` 为归一化后的可执行文件）。
- **随资产发布同名 `.sha256` 侧车**（`<资产>.sha256`，大写 hex），插件下载后校验比对。

## 3. 构建命令

用当前仓库的 Node 运行跨平台脚本（可跑在任意主机，默认取当前主机 os/arch）：

```bash
# 当前主机平台（本地开发）
node scripts/build-runtime.mjs --bundle

# 显式指定目标平台（交叉，建议 CI 在各目标 OS runner 上构建最稳）
node scripts/build-runtime.mjs --os darwin --arch arm64 --bundle
node scripts/build-runtime.mjs --os linux  --arch x64   --bundle
```

产出：`build/runtime-<os>-<arch>.zip` + `build/runtime-<os>-<arch>.zip.sha256`。
（`docs/DESIGN.md §3.2` 的 `build-runtime.ps1` 为 Windows 版历史脚本，仍可用于 win-x64；Gradle
`buildRuntime` 已改调本 `.mjs`。）

跨 OS 交叉时 npm 用 `--os/--cpu` 解析 optionalDependencies；为稳妥，**推荐用 CI 矩阵在各目标 OS
runner 上构建**（见 `.github/workflows/build-release.yml`）。

### 3.1 在 Windows 上交叉构建 macOS / Linux 运行时

Windows 主机也能产出 Unix 运行时，脚本已处理两个平台差异（v0.2.3 实测修复）：

- **解压 Node 发行包**：Unix 包的 `bin/npm`、`bin/npx`、`bin/corepack` 是符号链接，Windows 创建它们
  需要 `SeCreateSymbolicLinkPrivilege`（管理员或开发者模式），否则 `tar` 报错退出。脚本改为**容错**：
  tar 失败时只要目标目录里 `bin/node` 已解压到位就继续（这几个链接对运行时并非必需 ——
  npm 由 `node lib/node_modules/npm/bin/npm-cli.js` 直接执行）。
- **打包 zip**：按**主机**能力选择命令（Windows 用 `tar -a`，Unix 用 `zip`），而不是按目标平台判断 ——
  否则在 Windows 上交叉构建 Unix 时会去找不存在的 `zip` 命令。

交叉构建的校验要点（构建后请核对）：

1. `dsh/node_modules/@deepseek-ai/dsh/package.json` 的 `version` 与 `DSH_VERSION` 一致；
2. 目标平台的原生依赖变体齐备：`@img/sharp-<os>-<arch>`、`@koromix/koffi-<os>-<arch>`、
   `node-addon-require-builtin-<os>-<arch>*`；
3. `node/<nodeBinName>` 与 `dsh/.../lib/bin.js` 均存在（`RuntimeArchive.validateRuntimeZip` 同款检查）。

> 交叉构建拿的是 npm 上**预编译**的目标平台包；若某个原生依赖只发布了主机平台变体，交叉产物会缺件。
> 因此正式发布仍以 CI 矩阵（各平台原生 runner）为准；交叉产物适合本地/内网先行验证。

#### Linux 目标必须指定 libc（否则静默缺件）

Linux 的原生依赖变体带 libc 后缀：`node-addon-require-builtin-linux-x64-gnu`、
`@img/sharp-linux-x64`（glibc）/ `@img/sharp-linuxmusl-x64`（musl）。交叉安装时 npm **无法推断目标 libc**，
会直接跳过这些包 —— 实测表现为 `@img/` 下只剩 `colour`、`sharp-wasm32`，`node-addon-require-builtin-*`
为空（包数 519、zip 118MB），运行时缺原生依赖。脚本现已对 `t.os === 'linux'` 追加 `--libc glibc`，
补齐后包数 522、zip 约 126MB。

**各平台交叉构建实测（v0.2.3，Node 22.23.2 + dsh 0.1.5-rc.2）**：

| 目标 | 交叉构建 | zip | 关键原生变体 |
|---|---|---|---|
| win-x64 | 主机原生 | 103.8 MB | `sharp-win32-x64` / `koffi-win32-x64` / `…-win32-x64-msvc` |
| macos-arm64 | Windows 交叉 | 118.7 MB | `sharp-darwin-arm64`（+libvips）/ `koffi-darwin-arm64` / `…-darwin-arm64` |
| macos-x64 | Windows 交叉 | 120.7 MB | `sharp-darwin-x64`（+libvips）/ `koffi-darwin-x64` / `…-darwin-x64` |
| linux-x64 | Windows 交叉（`--libc glibc`） | 126.0 MB | `sharp-linux-x64` / `koffi-linux-x64` / `…-linux-x64-gnu` |
| linux-arm64 | Windows 交叉（`--libc glibc`） | 125.7 MB | `sharp-linux-arm64` / `koffi-linux-arm64` / `…-linux-arm64-gnu` |

> 备注：交叉构建**只能**产出上述平台资产；`windows-arm64` 未在 `runtime-assets.json` 资产表中，故不在矩阵内。


## 4. 插件如何下载

- 插件资源 `src/main/resources/runtime-assets.json` 提供 `baseUrl`（GitHub Releases，含 `{version}`
  占位符）与 `assets` 映射（target → 资产名）。
- 运行时：`DshHomeManager` → `RuntimeProvisioner`：本地已有运行时短路（存量无感）→ 无则
  拉取 `<资产>.sha256` → 下载资产 → SHA-256 比对 → 安全解压到
  `<config>/dsh-idea/runtime/<DSH_VERSION>/`。
- 下载地址可在 设置 → Tools → DeepSeek Harness 的「运行时下载地址」覆盖（自建镜像/内网源）；
  下载读取超时可在 设置 → Tools → DeepSeek Harness 配置。
- **v0.2.1 起（体验增强）**：下载用带连接池、支持 HTTP/2 的 `java.net.http.HttpClient`（浏览器 UA、
  60s 连接超时 + 退避重试），工具窗口显示实时进度条（字节/MB/速率、可取消），并新增
  「Choose local runtime zip…」从本地已下载的运行时 zip 导入（校验 + SHA-256 侧车），实现完全离线启动。

## 5. 发布 checklist（打 tag 触发 `.github/workflows/build-release.yml`）

1. 升版本号：`build.gradle.kts` `version`（如 `0.2.1`）+ `plugin.xml` change-notes + 本文档一致。
2. 本地：`./gradlew buildPlugin`（默认瘦身）确认插件 zip <2MB 且 jar 含 `runtime-assets.json`、无
   `runtime-bundle.zip`。
3. 推 tag：`git tag v0.2.1 && git push origin v0.2.1` → workflow 矩阵在每个目标 OS 生成
   `runtime-<os>-<arch>.zip` + `.sha256`，并构建瘦身插件 zip，附着到该 tag 的 Release。
4. **手动上传瘦身插件 zip 到 JetBrains Marketplace**（单版本单 zip；跨平台）。
5. 校验：各平台 IDE 安装后首次打开工具窗应出现「下载运行时」；之后复用本地运行时不再下载。

### 5.1 v0.2.4 实测状态（可作下一步基线）

- **五平台资产齐备并已发布**：GitHub Release `v0.2.4`（id 391244240）共 11 个资产（插件 zip + 5 平台运行时
  及 `.sha256`），全部 `state=uploaded`。
- **`macos-arm64` 已由用户端到端实测通过**（安装 → 启动 dsh → 对话 → MCP 工具链）；`macos-x64`、
  `linux-arm64` 仍由本地交叉构建后手动上传（CI 无对应 runner）。
- **给测试者发包建议**：① 通用瘦身插件 zip（首启自动下载运行时）；或 ② 目标平台 **fat 包**
  （用 `scripts/build-mac-fat.ps1` 注入对应 `runtime-<os>-<arch>.zip`，离线可用）；或 ③ 只发现用插件 +
  单独发 `runtime-macos-arm64.zip`，让对方在设置页「Choose local runtime zip…」离线导入。
- **macOS 上的可执行位**由 `RuntimeArchive.unzip` 兜底（见 `PROJECT_NOTES.md`「macOS 适配与 fat 包构造」）。

## 6. 运行时配置：下载源 vs 本地运行目录（离线说明）

运行时目标位置由 `DshHomeManager.runtimeRoot()` 决定，**优先级**：

1. 环境变量 `DSH_IDEA_RUNTIME`（若指向存在的目录）→ 直接用它（离线/手动，**绕过下载**）；
2. 否则设置页 设置 → Tools → DeepSeek Harness → **「运行时目录」**（若填了且是存在的目录）→ 直接使用
   （等价于环境变量的 GUI 版本；环境变量优先级更高）。该字段的「默认」按钮会填入插件的默认目录，
   **填入默认目录与"未指定"完全等价**（见下）；
3. 否则默认 `PathManager.getConfigDir()/dsh-idea/runtime/<DSH_VERSION>/`（如
   `…/config/dsh-idea/runtime/0.1.5-rc.2/`）——这是插件**自动下载/解压**的路径，命中即复用（存量无感）。

> "**用户显式指定**"（`hasExplicitRuntimeRoot()`）＝ 环境变量非空，或设置页填了**默认目录以外**的路径：
> 此时若目录缺 `node/` 或 `dsh/`，`hasRuntime()` 直接返回 false 并报错，**不会**回退到下载
> （避免误以为走的是指定目录）。
> 例外：填的正是插件默认目录（`isDefaultRuntimeDirectory`，规范化路径比较）→ 视为**未指定**，
> 因为该目录本就由插件下载/解压管理，仍会正常自动供给；`apply` 时也不会把默认值写进配置。

> 两个不同机制，别混：
> - **「Runtime download URL」（设置页）** = **下载源**（网络 or `file://` zip），插件仍走"下载→校验→解压"。
> - **`DSH_IDEA_RUNTIME` / 手动「运行时目录」** = **本地已解压的运行目录**，插件**直接使用，完全绕过下载**。

### 6.1 目录格式要求（`DSH_IDEA_RUNTIME` / 手动运行时目录都必须满足）

`DSH_IDEA_RUNTIME` 指向的目录必须是插件解压运行时的标准结构：

```
<运行时目录>/
├── node/                        # Node.js 运行时
│   └── node[.exe]              # Windows=node.exe；macOS/Linux=node（构建期已归一化到 node/ 顶层）
└── dsh/
    └── node_modules/
        └── @deepseek-ai/dsh/lib/bin.js
```

- 这就是插件从 `runtime-<os>-<arch>.zip`（根为 `node/` + `dsh/`）或 fat bundle 解压后的布局；
- 可直接指向插件**之前自动下载/解压好的目录**（`…/dsh-idea/runtime/<DSH_VERSION>/`），或你自己按上述结构放好的目录；
- **不能**直接指向一个随意的 dsh 安装树（如 `node_modules/@deepseek-ai/dsh` 本身，它缺少独立 `node/`）；
- `DSH_IDEA_RUNTIME` 一旦设置且该目录缺 `node` 或 `dsh` 中任一项，`hasRuntime()` 返回 false 并**直接报错**（不会触发下载）——所以必须指向完整有效的运行目录。

### 6.2 如何手动指定

- **枚举变量（Windows）**
  ```powershell
  $env:DSH_IDEA_RUNTIME = "D:\path\to\runtime"   # 目录内含 node/ 与 dsh/
  ```
- **环境变量（macOS / Linux）**
  ```bash
  export DSH_IDEA_RUNTIME=/path/to/runtime
  ```
  设置后启动 IDEA 即可。也可在 设置 → Tools → DeepSeek Harness 的「运行时目录」手动路径中填入同一目录（GUI 等价物）。

### 6.3 「Runtime download URL」设置

设置页 设置 → Tools → DeepSeek Harness → **Runtime download URL (optional)**：输入框**反显当前生效的下载地址**
（未覆盖时即默认的完整资产 URL，如
`https://github.com/tieJiangW/deepseek-harness-idea/releases/download/v0.2.3/runtime-win-x64.zip`）；
输入框右侧的「默认」按钮一键恢复为默认地址（不再单独占一行回显）。

- 支持的取值形式：
  1. **目录级 base**（默认形态，可含 `{version}` 占位符）→ 运行期拼接当前平台资产名，如 `https://…/releases/download/v{version}`；
  2. **到文件的完整 URL**（以 `.zip` 结尾）→ 直接使用、不再拼接（便于复制/粘贴，或指向 `file://…/runtime-<os>-<arch>.zip`）。
- 与默认地址等价（留空、默认 base 模板、或当前版本/平台的默认完整 URL）的取值**不落盘**（保存为空），
  避免把插件版本/平台钉死在配置里；插件升级后自动跟随默认。
- 注意：它改的是**下载地址**，不影响运行时落盘目录；离线更推荐用 `DSH_IDEA_RUNTIME` 或「运行时目录」设置项。

### 6.4 常见离线场景

- **有已解压的运行时**：设 `DSH_IDEA_RUNTIME`（或「运行时目录」手动路径）指向它 → 无需下载；
- **有内网镜像**：把「Runtime download URL」填成内网地址（含同名 `.sha256` 侧车）→ 从内网下载；
- **想完全离线、零设置**：用 `./gradlew buildPlugin -Pthin=false` 构建含运行时的 fat zip，装完即用（当前平台）。

## 7. 后续改进计划（未实施）

### 7.1 运行时下载地址按 DSH 版本解耦（避免随插件版本重复上传 / 重新下载）

**现状**：插件的运行时下载地址用 `{version}` = **插件版本**，即默认
`https://github.com/tieJiangW/deepseek-harness-idea/releases/download/v{version}/runtime-<os>-<arch>.zip`。
运行时缓存目录按 `DSH_VERSION`（`DshHomeManager.DSH_VERSION`，如 `0.1.5-rc.2`）命名，
`RuntimeProvisioner.isPresent(runtimeRoot())` 短路保证**已装好运行时的老用户在插件版本升级后不重新下载**。

**改进方向**：把下载 URL 的 `{version}` 从「插件版本」改为「**DSH 运行时版本**」，`runtime-assets.json` 的
`baseUrl` 改用 `v{dshVersion}` 占位，并把各平台运行时 zip 挂到一个**按 DSH 版本命名的稳定 tag**（如
`v0.1.5-rc.2`）。这样无论插件版本怎么变，只要 `DSH_VERSION` 常量不变，新装用户与老用户都指向
**同一个运行时下载地址** —— 不再需要随每个插件版本重复上传运行时，也不需要重新下载。

**前置（发布动作，未执行）**：需新建 `v0.1.5-rc.2`（或对应 DSH 版本）Release 并挂载各平台
`runtime-<os>-<arch>.zip` + `.sha256`（文件已在 `release-assets/` 可复现）。当前 v0.2.1 仍以插件版本为下载源，**未改动**。

### 7.2 其它预留

- **完整跟随 IntelliJ HTTP 代理**：`HttpFetcher` 已用 `java.net.http.HttpClient`，代理回退到 JVM 默认
  `ProxySelector`（即直连 / 跟随系统代理属性）。进一步让下载**读取 IDE「Settings → HTTP Proxy」配置**
  （`com.intellij.util.net.HttpConfigurable`）并据此构造 `ProxySelector`，作为后续改进。
- **下载失败归因与重试调参**：当前校验和与下载各最多 3 次退避重试（1s/2s/4s），可做成可配置项。
