# JetBrains Marketplace 上架填写草案

> 上传 `build/distributions/deepseek-harness-idea-0.2.1.zip` 时按此填写。
> Name / Description / Change-notes 会自动从 plugin.xml 带入，此处为「额外字段」的建议值。

## 基本信息
- **Plugin ID**: `com.deepseek.harness.idea` （自动带入）
- **Name**: `DeepSeek Harness` （自动带入）
- **Version**: `0.2.1` （自动带入）
- **Vendor**: `DeepSeek Harness Dev` （自动带入；已挂 `url=https://github.com/tieJiangW/deepseek-harness-idea`）

## 分类与标签
- **Category**: `AI` （若无法选 AI 则选 `Coding Assistance`）
- **Tags**: `AI`、`LLM`、`DeepSeek`、`Agent`、`Chat`、`Code Review`、`MCP`、`Coding Assistant`

## 兼容的 IDE
- **IntelliJ IDEA**（Community / Ultimate），since 241 / until 262.\*，已验证 2024.1 → 2026.2。

## 许可证
- **License**: 选 **MIT**，粘贴 `LICENSE` 文件文本，或填许可证链接 `https://github.com/tieJiangW/deepseek-harness-idea/blob/main/LICENSE`。

## Source code URL
- `https://github.com/tieJiangW/deepseek-harness-idea`

## 数据收集声明（Data collection，AI 插件审核重点）
情况：插件**不做任何遥测/统计**，不收集作者信息、不回传任何数据到插件作者。但：
- 用户把 **DEEPSEEK_API_KEY** 填入插件设置（存本地 `%APPDATA%/../.dsh` 私有目录，不随插件上传）。
- 走 DeepSeek API 时需要**联网**：用户在 IDE 里与智能体对话 / 发送选中代码时，**相关代码与对话内容会发送到 DeepSeek 的服务**（api.deepseek.com）用于推理。
- 默认**精简包（thin build）不捆绑运行时**：首次使用按当前平台（os/arch）从 GitHub Releases **下载** DeepSeek Harness 运行时（`runtime-<os>-<arch>.zip` + `.sha256`，SHA-256 校验通过后解压到本地、之后离线复用），下载 URL 与超时可在设置中查看/调整；另提供**捆绑运行时的 fat build**，供完全离线环境安装使用（不触发任何运行时下载）。

### 建议表单文案（英文，供粘贴）
> This plugin does not collect any telemetry or usage data. It only reads your local
> API key (stored locally, never uploaded) and, when you use the agent, sends the
> relevant code/context to the DeepSeek API (api.deepseek.com) for inference. The
> thin build downloads the per-platform runtime from GitHub Releases on first use
> (SHA-256 verified, then cached locally for offline reuse); an optional fat bundle
> pre-bundles the runtime for fully offline installs. Nothing is uploaded to the
> plugin author.

## 截图建议（Screenshots，强烈建议 ≥2 张）
1. 工具窗口聊天界面（对话中）
2. 「审查改动」Diff / 还原视图
3. 设置页（API Key 输入）
4. 右键「发送选中代码到 DSH」

## 上传与审核
- 先以 **Private** 保存，用 zip 本地实测；确认无误再 **Submit for review**。
- 公开审核周期一般 1–5 个工作日；AI 类插件可能更长。
- 若收到审核反馈，按提示调整后重新提交。

## 后续自动发布（可选）
配置 Gradle `publishPlugin` + **Permanent Token**（`https://plugins.jetbrains.com/plugin/<id>/permanent-token`），
将 token 写入 `~/.gradle/gradle.properties` 的 `intellijPublishToken`。**切勿把 token 提交进 git。**
