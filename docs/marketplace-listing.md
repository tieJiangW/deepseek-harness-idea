# JetBrains Marketplace 上架填写（当前版本 v0.2.5）

> 上传 `build/distributions/deepseek-harness-idea-0.2.5.zip` 时按此填写。
> Name / Description / Change-notes 会自动从 jar 内 `plugin.xml` 带入；此处为**额外字段**的建议值。
> 历史：v0.2.3 → update 1171727；v0.2.4 → update 1174317（均 `approve=false` 待审）。

## 基本信息

| 字段 | 值 |
| --- | --- |
| **Plugin ID** | `com.deepseek.harness.idea`（自动带入） |
| **Name** | `DeepSeek Harness`（自动带入） |
| **Version** | `0.2.5`（自动带入） |
| **Vendor** | `DeepSeek Harness Dev`（自动带入；`url=https://github.com/tieJiangW/deepseek-harness-idea`） |
| **Source code URL** | `https://github.com/tieJiangW/deepseek-harness-idea` |
| **License** | MIT（粘贴 `LICENSE` 文本或填上面的 LICENSE 链接） |
| **Category** | `AI`（无法选 AI 时选 `Coding Assistance`） |
| **Tags** | `AI`、`LLM`、`DeepSeek`、`Agent`、`Chat`、`Code Review`、`MCP`、`Coding Assistant` |
| **Compatible IDEs** | IntelliJ IDEA（Community / Ultimate），since 241 / until 262.\*（已验证 2024.1 → 2026.2） |

## 一句话简介（Marketplace "Short description"）

- EN：`DeepSeek Harness AI agent workbench inside IntelliJ IDEA — embedded chat, MCP IDE context, shared config and one-click dsh plugin installs across projects.`
- ZH：`把 DeepSeek Harness 智能体工作台嵌入 IntelliJ IDEA：内嵌对话、MCP 取用 IDE 上下文、配置与 dsh 插件跨项目共享（装一次全项目生效）。`

## 本版重点（v0.2.5）

- **dsh 插件跨项目共享**：每项目 `profiles` 链接到统一共享 profile，装一次所有项目加载；数据面（会话/工作区/附件）仍按项目隔离。
- **「DSH 插件」设置页**：列出已装插件与纯依赖，按包名/版本范围或本地目录安装、选中卸载；无需全局 pnpm（自备私有 pnpm）。
- **过程可见 + 一键生效**：阶段文案、进度条、每秒用时、实时输出、静默提示、可取消；结束绿 ✓ / 红 ✗ + IDE 通知；成功后直接「立即重启 Harness」。
- **健壮兜底**：manifest 备份/回滚；卸载遇文件占用时降级为 manifest 级移除；`node_modules` 与 pnpm 配置不一致时自动对齐或隔离重建。

## 数据收集声明（AI 插件审核重点）

插件**不做任何遥测/统计**，不收集作者信息、不回传数据给插件作者。但请注意：

- 用户把 **DEEPSEEK_API_KEY** 填入插件设置（存本地 IDE 密码库 + 共享配置目录的凭据文件，不随插件上传）。
- 与智能体对话 / 发送选中代码时，**相关代码与对话内容会发送到 DeepSeek 服务**（api.deepseek.com）用于推理。
- 默认**瘦身包不捆绑运行时**：首次使用按平台从 GitHub Releases **下载** `runtime-<os>-<arch>.zip` + `.sha256`（校验后解压到本地、之后离线复用），下载地址与超时可调；五个平台（Windows x64 / macOS arm64·x64 / Linux x64·arm64）均在 Release 提供；也可用「运行时目录」或「选择本地运行时压缩包…」完全离线指定；另提供捆绑运行时的 fat 构建（`-Pthin=false`）。
- **安装 dsh 插件包**时会访问 npm registry（可用镜像），下载的包由用户自行选择；插件本身不上传任何内容。

### 建议表单文案（英文，供粘贴）

> This plugin does not collect any telemetry or usage data. It reads your local
> API key (stored locally, never uploaded) and, when you use the agent, sends the
> relevant code/context to the DeepSeek API (api.deepseek.com) for inference.
> The thin build downloads the per-platform runtime from GitHub Releases on first
> use (SHA-256 verified, then cached for offline reuse); an optional fat bundle
> pre-bundles the runtime for fully offline installs. Installing dsh plugin
> packages uses the public npm registry (configurable mirror) for the packages you
> choose. Nothing is uploaded to the plugin author.

## 截图建议（≥2 张，v0.2.5 建议新增第 5 张）

1. 工具窗口聊天界面（对话中）
2. 「审查改动」Diff / 还原视图
3. 设置页（API Key 输入）
4. 右键「发送选中代码到 DSH」
5. **「DSH 插件」设置页**：插件列表 + 安装进度/结果（本版卖点）

## 上传与审核

- 可先以 **Private** 保存并用 zip 本地实测，确认无误再 **Submit for review**。
- 公开审核一般 1–5 个工作日；AI 类插件可能更长。
- 审核反馈按提示调整后重新提交。

## 后续自动发布（可选）

配置 Gradle `publishPlugin` + Permanent Token（`https://plugins.jetbrains.com/plugin/33820/permanent-token`），
把 token 写入 `~/.gradle/gradle.properties` 的 `intellijPublishToken`。**切勿把 token 提交进 git。**
