# 发布流程参考（GitHub Release + JetBrains Marketplace）

> 面向自动化 agent 的可执行流程与接口说明。所有命令均在 **Windows / Windows PowerShell 5.1** 下实测通过。
> 仓库：`https://github.com/tieJiangW/deepseek-harness-idea.git`（owner=`tieJiangW`，repo=`deepseek-harness-idea`）
> 插件 XML ID（`pluginXmlId`）：`com.deepseek.harness.idea` ｜ Marketplace 数字 `pluginId`：`33820`

---

## 0. 前置条件与凭据

| 用途 | 变量/位置 | 获取方式 |
| --- | --- | --- |
| GitHub 推送与 Release API | `GITHUB_PAT`（`ghp_...` / 细粒度 PAT） | GitHub → Settings → Developer settings → Personal access tokens |
| Marketplace 上传 | `MARKETPLACE_TOKEN`（`perm-...`，永久令牌） | Marketplace 个人面板 → **My Tokens** |

- PAT 需要 `repo`（或对目标仓库 `Contents: read/write`）权限。
- **凭据绝不写入仓库、日志或文档**。用环境变量或当轮注入；泄露后立即轮换。
- **版本号变更需先获得用户确认**（本仓库约定）。

**构建环境**：Gradle Wrapper `8.14`，命令用 `.\gradlew.bat`；插件线 `org.jetbrains.intellij 1.17.4`；`version` 唯一定义在 `build.gradle.kts` 的 `version = "x.y.z"`。

---

## 1. 发版步骤总览

1. 提升版本号（`build.gradle.kts`）
2. 在 `src/main/resources/META-INF/plugin.xml` 的 `<change-notes>` 顶部追加本次版本条目（中英双语）
3. `.\gradlew.bat buildPlugin` → 产出 `build/distributions/deepseek-harness-idea-<ver>.zip`
4. 校验产物（版本、描述符合法性）
5. 提交并推送 GitHub
6. 创建 GitHub Release 并上传资产
7. 上传 JetBrains Marketplace

---

## 2. 版本号与构建

```powershell
# 1) 版本号（唯一来源）
#    build.gradle.kts:  version = "0.2.2"

# 2) changelog：写入 plugin.xml 的 <change-notes>（Marketplace 与 IDE 都读这里，见 §4.4）

# 3) 构建
.\gradlew.bat buildPlugin
# 产物: build\distributions\deepseek-harness-idea-0.2.2.zip
# -PplatformVersion=2026.2 可做前向编译检查；默认平台 2024.1.7
```

**产物结构**：`deepseek-harness-idea/lib/{instrumented-<ver>.jar, kotlin-stdlib-*.jar, annotations-*.jar}`。
构建会把 `<version>` 注入 jar 内 `META-INF/plugin.xml`。

---

## 3. GitHub 推送流程

### 3.1 提交

PowerShell 下**不要**用 `git commit -m "多行/含引号"`（会被拆坏）。改用消息文件：

```powershell
git add build.gradle.kts src/main/resources/META-INF/plugin.xml docs/PROJECT_NOTES.md
# 只暂存本次相关文件；release-assets/ 已在 .gitignore，勿提交
git commit -F .commitmsg.txt
```

### 3.2 推送

用 token 内嵌 URL，避免凭据弹窗在无 TTY 环境下失败：

```powershell
$pat = $env:GITHUB_PAT
git push "https://x-access-token:$pat@github.com/tieJiangW/deepseek-harness-idea.git" main
```

- `main` 有分支保护（要求走 PR）。持有管理员 PAT 时会直接推送成功，输出含
  `remote: Bypassed rule violations for refs/heads/main` —— 属正常，非报错。
- 若无 TTY，`git push origin main` 会因凭据对话框取消而失败（`could not read Username`），必须用上面的内嵌 URL 形式。

### 3.3 创建 Release（REST API）

```
POST https://api.github.com/repos/{owner}/{repo}/releases
Headers:
  Authorization: Bearer <PAT>
  Accept: application/vnd.github+json
  User-Agent: <任意非空，GitHub 强制要求>
Body (JSON): { "tag_name": "v0.2.2", "name": "v0.2.2", "body": "<发布说明>", "draft": false, "prerelease": false }
```

- 若 `tag_name` 尚不存在，GitHub 会**自动创建**该 tag 并指向仓库默认分支 HEAD。
- 返回 JSON 中的 `id` 即 `release_id`，用于上传资产；`upload_url` 亦会返回。

### 3.4 上传资产

```
POST https://uploads.github.com/repos/{owner}/{repo}/releases/{release_id}/assets?name={filename}
Headers:
  Authorization: Bearer <PAT>
  Content-Type: application/zip   # .sha256 用 text/plain
Body: 文件二进制
```

- 资产命名须与 `runtime-assets.json` 约定的文件名一致（`runtime-<os>-<arch>.zip` + `.sha256`），否则插件运行时下载 404。
- 大文件（100MB+）单次可能数分钟，**建议后台任务**逐个上传，避免超时。
- 本仓库典型资产集：插件 zip + `runtime-win-x64` / `runtime-linux-x64` / `runtime-linux-arm64` / `runtime-macos-arm64` / `runtime-macos-x64` 的 zip 与 `.sha256`。

### 3.5 tag 说明

Release API 已自动建远端 tag。如需本地 tag 与远端一致：

```powershell
git tag v0.2.2
git push "https://x-access-token:$pat@github.com/tieJiangW/deepseek-harness-idea.git" tag v0.2.2
```

---

## 4. JetBrains Marketplace 推送流程

### 4.1 上传接口

```
POST https://plugins.jetbrains.com/api/updates/upload
Headers:
  Authorization: Bearer <MARKETPLACE_TOKEN>
Content-Type: multipart/form-data
Fields:
  xmlId   = com.deepseek.harness.idea      # 或用 pluginId = 33820（二选一）
  file    = @build/distributions/deepseek-harness-idea-0.2.2.zip
  channel = ""                             # 留空 = Stable；可填 nightly/eap 等
可选:
  isHidden=true                            # 通过审核后先不公开
  containsAds=true                         # 含广告时必须声明
```

> 文档：<https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html>
> 单包上限 **400 MB**。上传成功返回 **HTTP 201**。

### 4.2 返回与状态

```json
{ "id": 1160462, "version": "0.2.2", "approve": false, "channel": "",
  "link": "/plugin/33820-deepseek-harness/versions/stable/1160462", "pluginId": 33820 }
```

- `approve: false` = **已进入官方审核队列**（非失败）。
- `since/until` 由 jar 内 `plugin.xml` 的 `idea-version` 决定。

### 4.3 复用脚本（PowerShell 5.1，含 UTF-8 安全写法）

```powershell
Add-Type -AssemblyName System.Net.Http
$tok = $env:MARKETPLACE_TOKEN
$zip = 'build\distributions\deepseek-harness-idea-0.2.2.zip'

$client = [System.Net.Http.HttpClient]::new()
$client.DefaultRequestHeaders.Authorization =
    [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $tok)

$form = [System.Net.Http.MultipartFormDataContent]::new()
$form.Add([System.Net.Http.StringContent]::new('com.deepseek.harness.idea'), 'xmlId')
$file = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($zip))
$file.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/zip')
$form.Add($file, 'file', 'deepseek-harness-idea-0.2.2.zip')

$resp = $client.PostAsync('https://plugins.jetbrains.com/api/updates/upload', $form).GetAwaiter().GetResult()
Write-Output ("HTTP " + [int]$resp.StatusCode)
Write-Output $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
$form.Dispose(); $client.Dispose()
```

### 4.4 changelog 来源（重要）

上传 API **没有 changelog 字段**。Marketplace 的“更新说明”直接读取上传包内
`META-INF/plugin.xml` 的 `<change-notes>`。因此：

- 发版前必须先把本次条目写进 `<change-notes>`，再构建上传。
- 该内容是 HTML（`<h4>/<ul>/<li>` 可用）；出现字面 `<icon>` 之类尖括号请转义为 `&lt;icon&gt;`。

---

## 5. ⚠️ 编码与工具陷阱（PowerShell 5.1 实测，务必遵守）

| 陷阱 | 现象 | 正确做法 |
| --- | --- | --- |
| `Invoke-WebRequest -Body <string>` | 中文按系统 ANSI/GBK 编码，GitHub 按 UTF-8 解码 → 中文变 `?????` | 用 `HttpClient` + `[System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')` |
| 含中文的 here-string `@'...'@` | 从非 UTF-8 代码页加载 .ps1 时解析失败（`string is missing the terminator`） | 正文写入**独立 UTF-8 文件**，用 `[System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)` 读取 |
| `Invoke-WebRequest -Form` | PS 5.1 无此参数 | 用 `[System.Net.Http.MultipartFormDataContent]`（见 §4.3） |
| `HttpClient.PatchAsync` | .NET Framework 无此方法 | 用 `SendAsync` + `[System.Net.Http.HttpMethod]::new('PATCH')` |
| 已发布 Release 正文修正 | 需改 body | `PATCH https://api.github.com/repos/{o}/{r}/releases/{id}`，JSON `{"body": "..."}`（同样走 UTF-8 StringContent） |
| `git commit -m "多行"` | 引号/换行被 PowerShell 拆坏 | 用 `git commit -F <utf8-message-file>` |

**纯 ASCII 内容不受影响**；仅含中日韩等非 ASCII 时需要上述写法。

---

## 6. 验证清单

```powershell
# GitHub：Release 与资产
GET https://api.github.com/repos/tieJiangW/deepseek-harness-idea/releases/tags/v0.2.2
#   检查 tag_name / assets[].name / assets[].size / body 中文是否正常

# GitHub：main 是否在目标提交
GET https://api.github.com/repos/tieJiangW/deepseek-harness-idea/commits/main

# 产物：确认版本与描述符
#   解出 jar 内 META-INF/plugin.xml，校验 <version> 与根子元素合法
```

- Marketplace 上传成功以 **HTTP 201** 且返回 `id`（update id）为准；`approve:false` 属正常待审。
- 插件页：<https://plugins.jetbrains.com/plugin/33820-deepseek-harness>

---

## 7. 已知注意事项

- **`release-assets/` 已在 `.gitignore`**：存放跨平台 runtime zip（体积大），只作为 GitHub Release 的资产来源，**不入库**。
- 资产文件名必须与插件内 `runtime-assets.json` 一致，否则首次使用下载运行时 404。
- `main` 受分支保护；管理员 PAT 可直推（会提示 `Bypassed rule violations`）。
- Marketplace 审核通过后版本才会公开；上传后可在插件面板查看/管理该 update。

---

## 8. 备用通道：github.com:443 不通时全程走 API 发布（v0.2.3 实测）

部分网络环境 `github.com:443`（HTTPS/Web）会超时，但 `api.github.com` 与 `uploads.github.com` 可达。
此时 `git push` / `git fetch` 均不可用，可**全程用 REST API** 完成发版（v0.2.3 即按此方式发布）：

| 步骤 | 接口 |
| --- | --- |
| 推送提交 | Git Data API：`POST /git/blobs` → `POST /git/trees`（带 `base_tree`）→ `POST /git/commits` → `PATCH /git/refs/heads/main` |
| 创建 tag | `POST /releases`（`tag_name` 不存在时自动建）或 `POST /git/refs`；**都会触发 `on: push: tags` 的 workflow** |
| Release 与资产 | `POST /releases`；`POST https://uploads.github.com/repos/{o}/{r}/releases/{id}/assets?name=<文件名>` |

要点与坑：

- **先判定连通性**：`curl -m 15 https://github.com` 返回 `000`/超时 = 主站不通；而 `api.github.com` 返回 200、
  `uploads.github.com` 返回 302 说明 API 通道可用。`ssh.github.com:443`、`github.com:22` 可能仍通（但需要 SSH key）。
- **用 Node（`fetch`）发 API 请求，不要用 PowerShell**：Node 天然 UTF-8，可完全绕开 §5 的
  `Invoke-WebRequest` / here-string 编码陷阱；大文件直接 `fs.readFileSync` 作为 body（实测 126 MB 约 30 s）。
  请求头必须带 `User-Agent`（GitHub 强制）。
- **上传保持幂等**：同名资产先 `DELETE /releases/assets/{id}` 再上传，脚本可重复执行而不产生重名资产。
- **tag 触发的 CI 会覆盖 Release 正文**：`build-release.yml` 的 release job 使用 `generate_release_notes: true`，
  会在 CI 跑完后重写 body、并覆盖同名资产。因此正确顺序是：
  1) 先上传本地全部资产；2) 等 CI 结束；3) **最后 `PATCH /releases/{id}` 把正文改回中英文**。
- CI 只构建 win-x64 / macos-arm64 / linux-x64；**`macos-x64` 与 `linux-arm64` 必须本地交叉构建后手动上传**
  （见 `release-runtime.md` §3.1）。
- API 推送不会更新本地 `origin/main` 引用（本地对象库里没有 API 生成的 commit 对象）；下次在有网络的环境
  `git fetch` 即可对齐，内容与本地提交一致。
