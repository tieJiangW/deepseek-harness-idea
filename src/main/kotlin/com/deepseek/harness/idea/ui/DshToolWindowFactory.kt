package com.deepseek.harness.idea.ui

import com.deepseek.harness.idea.bridge.DshBridgeManager
import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.deepseek.harness.idea.runtime.DshProcessManager
import com.deepseek.harness.idea.runtime.RuntimeProvisioner
import com.deepseek.harness.idea.settings.DshSettingsConfigurable
import com.deepseek.harness.idea.settings.DshSettingsState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Desktop
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * 工具窗口：启动内嵌 dsh → 状态流转 → JCEF 加载 Web UI。
 * 卡片：占位 / 启动中 / 浏览器 / 错误。
 */
class DshToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // 防御：同窗口切换项目时 IDEA 可能复用同一个工具窗口实例，旧项目 content
        // （上一项目的面板 + DSH 日志页）会残留，出现重复面板 + 旧工作区/旧进程。
        // 先把全部旧 content 移除并 dispose（触发旧面板 dispose → 杀其 dsh 进程），再建当前项目的。
        toolWindow.contentManager.contents.forEach { old ->
            toolWindow.contentManager.removeContent(old, true)
        }

        val panel = DshToolWindowPanel(project)
        // 主界面必须是第一个 content 且默认选中（否则日志 tab 抢焦点）
        val content = ContentFactory.getInstance().createContent(panel, DshBundle.message("toolwindow.title"), false)
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content, true)

        // 日志 tab 在主 content 之后添加（Step 5 FR-08.1）
        panel.installLogTab(toolWindow)

        toolWindow.setTitleActions(
            listOf(
                OpenSettingsAction(),
                OpenBrowserAction(panel),
                ReviewChangesAction(),
                RestartAction(panel)
            )
        )
    }
}

class DshToolWindowPanel(private val project: Project) : JPanel(CardLayout()), Disposable, DshProcessManager.Listener {

    companion object {
        private val LOG = Logger.getInstance(DshToolWindowPanel::class.java)
        private const val CARD_PLACEHOLDER = "placeholder"
        private const val CARD_LOADING = "loading"
        private const val CARD_BROWSER = "browser"
        private const val CARD_ERROR = "error"
        private const val CARD_PROVISION = "provision"
        const val TOOL_WINDOW_ID = "DeepSeek Harness"

        /** JBCefJSQuery 回传里标记"来自 dsh 弹窗的 API Key"的前缀（与一键发送结果区分）。 */
        const val APIKEY_PREFIX = "__apikey__"

        /** 通过工具窗口主 content（index 0）查找当前项目的面板（SendSelectionAction/SendLogExplanationAction 共用）。 */
        fun find(project: Project): DshToolWindowPanel? {
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                ?: return null
            if (tw.contentManager.contentCount == 0) return null
            return tw.contentManager.getContent(0)?.component as? DshToolWindowPanel
        }
    }

    private val cards = layout as CardLayout

    @Volatile
    private var processManager: DshProcessManager? = null
    private var bridgeManager: DshBridgeManager? = null
    private var browser: JBCefBrowser? = null
    private val statusLabel = JBLabel(DshBundle.message("status.stopped"), SwingConstants.CENTER)
    private val errorLabel = JBLabel(" ", SwingConstants.CENTER)
    private val retryLabel = JBLabel("<html><a href='#'>${DshBundle.message("action.restart")}</a></html>", SwingConstants.CENTER)
    private val provisionProgress = JProgressBar(0, 100).apply {
        isStringPainted = true
        value = 0
        isIndeterminate = true
    }
    private val provisionStatusLabel = JBLabel(DshBundle.message("provision.status.connecting"), SwingConstants.CENTER)

    /** 日志面板（Step 5 FR-08.1），null = 未打开。 */
    private var logPanel: DshLogPanel? = null

    /** JCEF JS 回传通道（一键发送的结果验证；须早于 loadURL 创建，null = 创建失败走乐观降级）。 */
    @Volatile
    private var jsQuery: JBCefJSQuery? = null

    /** 当前发送的等待回调（token 防旧回调串台，见 [sendQuestion]）。 */
    @Volatile
    private var pendingSend: PendingSend? = null

    /** 自动发送在途守卫（防双击/连点重复提交）。 */
    private val sending = AtomicBoolean(false)

    /** dispose 幂等位（项目切换 content 先移除 + 项目关闭 Disposer 双路径）。 */
    private val disposed = AtomicBoolean(false)

    /** 每次发送的单调 token，用于丢弃过期回调。 */
    private val sendToken = AtomicLong(0)

    init {
        add(buildPlaceholderCard(), CARD_PLACEHOLDER)
        add(buildLoadingCard(), CARD_LOADING)
        add(buildErrorCard(), CARD_ERROR)
        add(buildProvisionCard(), CARD_PROVISION)
        Disposer.register(project, this)
        com.deepseek.harness.idea.runtime.DshLifecycleManager.getInstance().registerPanel(project.name, this)
        start()
    }

    /** 由工厂在添加主 content 之后调用（日志 tab 不抢默认焦点）。 */
    fun installLogTab(toolWindow: ToolWindow) {
        val logPanel = DshLogPanel()
        this.logPanel = logPanel
        toolWindow.contentManager.addContent(
            com.intellij.ui.content.ContentFactory.getInstance().createContent(logPanel, DshBundle.message("log.tabTitle"), false)
        )
    }

    // ---- 生命周期 ----

    private fun start() {
        val homeManager = DshHomeManager.getInstance()
        // 快速路径：运行时已就绪 → 直接启动
        if (RuntimeProvisioner.isPresent(homeManager.runtimeRoot())) {
            bootstrap()
            return
        }
        // DSH_IDEA_RUNTIME 显式指向但运行时缺失 → 报错（不联网下载）
        if (System.getenv(DshHomeManager.RUNTIME_OVERRIDE_ENV) != null) {
            showError(DshBundle.message("error.runtimeMissing", DshHomeManager.RUNTIME_OVERRIDE_ENV))
            return
        }
        // 慢速路径：后台下载运行时（进度条 + 可取消），不在 EDT 上阻塞
        runProvisionTask(homeManager)
    }

    /** 后台任务：下载运行时并推进度；成功后继续启动流程，失败显示含完整 URL 的错误卡。 */
    private fun runProvisionTask(homeManager: DshHomeManager) {
        showCard(CARD_PROVISION)
        val readSeconds = DshSettingsState.getInstance().runtimeDownloadTimeoutSeconds.coerceAtLeast(30)
        val task = object : Task.Backgroundable(project, DshBundle.message("provision.progress.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                val options = RuntimeProvisioner.DownloadOptions(
                    connectTimeoutMs = RuntimeProvisioner.DownloadOptions().connectTimeoutMs,
                    readTimeoutMs = readSeconds * 1000,
                    progress = { done, total, assetName -> updateProvision(indicator, done, total, assetName) },
                    cancelled = { indicator.isCanceled() },
                )
                val result = homeManager.ensureRuntimeProvisioned(options)
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is RuntimeProvisioner.ProvisionResult.Ready -> {
                            showCard(CARD_LOADING)
                            bootstrap()
                        }
                        is RuntimeProvisioner.ProvisionResult.Failed -> showProvisionError(result)
                    }
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    provisionStatusLabel.text = DshBundle.message("provision.status.cancelled")
                    provisionProgress.isIndeterminate = true
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    /** 进度回调：更新 IDE 底栏 indicator + 面板内进度条（口径一致；总量未知则走 indeterminate）。 */
    private fun updateProvision(indicator: ProgressIndicator, done: Long, total: Long, assetName: String) {
        val pct = if (total > 0) (done.toDouble() / total * 100).toInt().coerceIn(0, 100) else 0
        val mbText = if (total > 0) "%.1f / %.1f MB".format(done / 1e6, total / 1e6) else "%.1f MB".format(done / 1e6)
        indicator.isIndeterminate = total <= 0
        if (total > 0) indicator.fraction = done.toDouble() / total
        indicator.text = DshBundle.message("provision.status.downloading", assetName, mbText)
        ApplicationManager.getApplication().invokeLater {
            provisionStatusLabel.text = DshBundle.message("provision.status.downloading", assetName, mbText)
            if (total > 0) {
                provisionProgress.isIndeterminate = false
                provisionProgress.value = pct
            } else {
                provisionProgress.isIndeterminate = true
            }
        }
    }

    /** 下载失败：把失败原因 + 尝试的完整文件 URL 一并展示，并提供"选择本地 zip"入口。 */
    private fun showProvisionError(result: RuntimeProvisioner.ProvisionResult.Failed) {
        val url = result.assetUrl ?: DshHomeManager.getInstance().effectiveRuntimeDownloadUrl() ?: ""
        val reason = when (result.reason) {
            RuntimeProvisioner.ProvisionReason.BASE_EMPTY -> DshBundle.message("provision.error.baseEmpty")
            RuntimeProvisioner.ProvisionReason.NO_ASSET -> DshBundle.message("provision.error.noAsset")
            RuntimeProvisioner.ProvisionReason.CHECKSUM_UNREACHABLE -> DshBundle.message("provision.error.checksum")
            RuntimeProvisioner.ProvisionReason.DOWNLOAD_FAILED -> DshBundle.message("provision.error.downloadFailed")
            RuntimeProvisioner.ProvisionReason.SHA_MISMATCH -> DshBundle.message("provision.error.shaMismatch")
            RuntimeProvisioner.ProvisionReason.EXTRACT_FAILED -> DshBundle.message("provision.error.extractFailed")
            RuntimeProvisioner.ProvisionReason.INCOMPLETE -> DshBundle.message("provision.error.incomplete")
            RuntimeProvisioner.ProvisionReason.CANCELLED -> DshBundle.message("provision.error.cancelled")
            RuntimeProvisioner.ProvisionReason.LOCAL_INVALID -> DshBundle.message("provision.error.localInvalid")
        }
        val body = buildString {
            append(reason)
            if (url.isNotBlank()) append("<br><br>").append(DshBundle.message("provision.error.url", url))
            val detail = result.detail
            if (!detail.isNullOrBlank()) {
                append("<br><br>").append(DshBundle.message("provision.error.detail", detail))
            }
            append("<br><br>").append(DshBundle.message("provision.error.hint"))
        }
        showError(body)
    }

    /** 打开文件选择器，导入本地运行时 zip（离线）；成功则继续启动流程。 */
    private fun provisionLocalZip() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(DshBundle.message("settings.runtimeDownload.chooseLocal"))
            .withFileFilter { it.extension?.equals("zip", ignoreCase = true) == true }
        val file = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createFileChooser(descriptor, project, null)
            .choose(project, null).firstOrNull() ?: return
        val zipPath = java.nio.file.Paths.get(file.path)
        val task = object : Task.Backgroundable(project, DshBundle.message("provision.progress.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = DshBundle.message("provision.status.localImport")
                val result = DshHomeManager.getInstance().provisionFromLocalZip(zipPath)
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is RuntimeProvisioner.ProvisionResult.Ready -> {
                            showCard(CARD_LOADING)
                            bootstrap()
                        }
                        is RuntimeProvisioner.ProvisionResult.Failed -> showProvisionError(result)
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun bootstrap() {
        val homeManager = DshHomeManager.getInstance()
        // Step 5 FR-02.6：并发上限 3
        if (!com.deepseek.harness.idea.runtime.DshRuntimeRegistry.getInstance()
                .tryAcquire(project.name, this)
        ) {
            showError(DshBundle.message("error.concurrencyLimit", com.deepseek.harness.idea.runtime.DshRuntimeRegistry.MAX_INSTANCES))
            return
        }
        showCard(CARD_LOADING)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 项目根目录：作为工作空间（dsh 注册）与 DSH_HOME 隔离标识（v0.1.3-dev 切换项目修复）
                val projectRoot = project.basePath ?: ""
                // 方案 A：先更新全局 .credentials.yaml，再 ensureHome 把全局配置同步到子目录
                // （key 真源 = PasswordSafe + 全局 .credentials.yaml；不再向 dsh 进程注入
                //   DEEPSEEK_API_KEY 环境变量 —— dsh-credentials-local 的 inherited env wins 会遮蔽
                //   Web UI 写入，并使 Web UI 改 key 被 assertUnshadowed 拒绝）。
                homeManager.syncCredentials()
                homeManager.ensureHome(projectRoot)
                val homePath = homeManager.homeDir(projectRoot)
                val home = homePath.toFile()

                // dsh Web UI 改 key 监听：dsh 写当前项目 DSH_HOME/.credentials.yaml → 回写
                // PasswordSafe + 全局，使其它项目下次启动/重启全局一致（方案 B）。
                com.deepseek.harness.idea.runtime.DshCredentialsSync.register(
                    project.name, homePath.resolve(".credentials.yaml")
                )

                // Step 3：MCP 桥接编排（bridge + mcp-ide-server + ide.yml patch）
                val bridge = DshBridgeManager(
                    project = project,
                    nodeExe = homeManager.nodeExe().toFile(),
                    workDir = File(projectRoot.ifEmpty { System.getProperty("user.home") }),
                    homeDir = homePath,
                )
                bridgeManager = bridge
                Disposer.register(this, bridge)

                val patchFile = waitForMcpPatch(bridge, homePath)
                val manager = DshProcessManager(
                    nodeExe = homeManager.nodeExe().toFile(),
                    dshBin = homeManager.dshBin().toFile(),
                    workDir = File(projectRoot.ifEmpty { System.getProperty("user.home") }),
                    homeDir = home,
                    patchFile = patchFile.toFile(),
                    projectPath = projectRoot,
                    extraEnv = buildMap {
                        put("DSH_IDE_BRIDGE_URL", bridge.bridgeUrl())
                        put("DSH_IDE_TOKEN", bridge.bridgeToken())
                        put("DSH_LOG_LEVEL", com.deepseek.harness.idea.settings.DshSettingsState.getInstance().logLevel)
                        // 注意：不再注入 DEEPSEEK_API_KEY 环境变量。dsh-credentials-local 的
                        // resolve() 是 inherited env wins；一旦注入，dsh 永远读 env 旧值，且 Web UI
                        // 改 key 会被 assertUnshadowed 拒绝。key 真源为 PasswordSafe + 全局
                        // .credentials.yaml，由 DshCredentialsSync 在 Web UI 改动时回写全局。
                    },
                )
                processManager = manager
                manager.addListener(this)
                Disposer.register(this, manager)
                manager.start()
            } catch (e: Exception) {
                LOG.error("failed to bootstrap dsh", e)
                showError(e.message ?: e.toString())
            }
        }
    }

    /** 等待 MCP server 就绪（≤15s）并生成 ide.yml patch。 */
    private fun waitForMcpPatch(bridge: DshBridgeManager, homePath: java.nio.file.Path): java.nio.file.Path {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (bridge.mcpPort() > 0) return bridge.writePatch()
            Thread.sleep(200)
        }
        LOG.warn("mcp-ide-server not ready within 15s; proceeding without patch")
        // 兜底：MCP server 未就绪也继续启动 dsh（patch 保持占位，IDE 工具缺失但 web UI 可用）
        val fallback = homePath.resolve("ide.yml")
        java.nio.file.Files.writeString(fallback, "[]\n")
        return fallback
    }

    override fun dispose() {
        // 幂等：项目切换时旧 content 会经 removeContent 先 dispose，随后项目关闭的
        // Disposer 链可能再次调用；用原子位防重复销毁（重复杀进程/释放名额）。
        if (!disposed.compareAndSet(false, true)) return
        // processManager/bridgeManager 已注册到 Disposer(this)，此处显式停止保证顺序
        processManager?.dispose()
        processManager = null
        bridgeManager?.dispose()
        bridgeManager = null
        jsQuery?.dispose()
        jsQuery = null
        pendingSend = null
        browser?.dispose()
        browser = null
        com.deepseek.harness.idea.runtime.DshLifecycleManager.getInstance().unregisterPanel(project.name)
        com.deepseek.harness.idea.runtime.DshRuntimeRegistry.getInstance().release(project.name)
        com.deepseek.harness.idea.runtime.DshCredentialsSync.release(project.name)
    }

    fun restart() {
        val manager = processManager ?: run {
            // 尚未启动成功（如运行时下发失败）→ 重新走启动/下发流程
            start()
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread { manager.restart() }
    }

    fun webUrl(): String? = processManager?.webUrl()

    fun isRunning(): Boolean = processManager?.currentState() == DshProcessManager.State.RUNNING

    /**
     * 发送选中代码到 DSH（Step 4 + 紧凑引用）：
     * 1. 直接写入 Bridge 的 sent-selection 队列（智能体可随时经 ide_get_sent_selection 取回，必达；
     *    队列存完整代码，供智能体按需读取）；
     * 2. 聚焦工具窗口并尝试 JCEF 注入：输入框填入**紧凑文件引用** `@路径#L起始-结束` + 换行，
     *    光标自动落到下一行等待输入问题（无提示语、无代码本体）；
     * 3. 注入失败/未运行 → 剪贴板（同样紧凑引用）+ 通知降级。
     */
    fun sendSelection(filePath: String?, language: String?, selection: String, lineStart: Int, lineEnd: Int) {
        val bridge = bridgeManager
        if (bridge != null) {
            bridge.pushSentSelection(filePath, language, selection, lineStart, lineEnd)
        }
        val panel = this
        val ref = buildCompactReference(filePath, lineStart, lineEnd)
        ApplicationManager.getApplication().invokeLater {
            // 聚焦工具窗口
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow(TOOL_WINDOW_ID)?.activate(null)
            val injected = injectToBrowser(ref)
            if (!injected) {
                copyToClipboard(ref)
                showNotification(DshBundle.message("sendSelection.clipboard"))
            } else {
                showNotification(DshBundle.message("sendSelection.done"))
            }
        }
    }

    /** 构造紧凑引用：`@绝对路径#L起始-结束` + 尾随换行（光标落下一行，无提示语）。 */
    private fun buildCompactReference(filePath: String?, lineStart: Int, lineEnd: Int): String {
        if (filePath.isNullOrBlank()) return ""
        val sb = StringBuilder()
        sb.append('@').append(filePath.replace('\\', '/'))
        if (lineEnd > 0) {
            sb.append("#L").append(lineStart)
            if (lineEnd > lineStart) sb.append('-').append(lineEnd)
        }
        sb.append('\n')
        return sb.toString()
    }

    /** JCEF 注入：轮询 dsh web 的 composer textarea，设置值、触发 React input 事件、光标移到末尾（下一行）。 */
    private fun injectToBrowser(selection: String): Boolean {
        val json = escapeJs(selection)
        val script = """
            (() => {
              const deadline = Date.now() + 8000;
              const text = $json;
              const tryInject = () => {
                const ta = document.querySelector('textarea');
                if (!ta) { if (Date.now() < deadline) setTimeout(tryInject, 300); return; }
                const proto = window.HTMLTextAreaElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(ta, text);
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                // 光标移到文本末尾（引用行之后的新行），等待直接输入问题
                const pos = ta.value.length;
                ta.setSelectionRange(pos, pos);
                ta.focus();
              };
              tryInject();
            })();
        """.trimIndent()
        return executeInPage(script)
    }

    /**
     * 一键发送问题到 DSH（自动提交，不等待用户确认）：
     * 1. 守卫：在途防重；DSH 未运行 → 剪贴板 + 通知；浏览器缺失 → 剪贴板 + 通知；
     * 2. 激活工具窗口并切到对话页（主 content 是第一个，避免停在日志 tab）；
     * 3. JCEF 注入：composer 填入完整问题 + 派发回车自动提交；JBCefJSQuery 回传
     *    `submitted` / `blocked` / `no-composer` 结果（无通道时乐观提示）；
     * 4. 成功 → 通知已发送；blocked → 消息留在输入框 + 提示手动回车；失败 → 剪贴板兜底。
     */
    fun sendQuestion(text: String) {
        if (!sending.compareAndSet(false, true)) {
            LOG.debug("sendQuestion already in flight; ignore duplicate click")
            return
        }
        val token = sendToken.incrementAndGet()
        pendingSend = PendingSend(token, text)
        ApplicationManager.getApplication().invokeLater {
            try {
                if (!isRunning()) {
                    copyToClipboard(text)
                    showNotification(DshBundle.message("sendLogExplanation.notRunning"))
                    return@invokeLater
                }
                if (browser == null) {
                    copyToClipboard(text)
                    showNotification(DshBundle.message("sendLogExplanation.failed"))
                    return@invokeLater
                }
                // 聚焦工具窗口并确保对话页（content 0）被选中
                val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                tw?.activate(null)
                tw?.contentManager?.let { cm ->
                    val main = cm.getContent(0)
                    if (main != null) cm.setSelectedContent(main, true)
                }
                val funcName = jsQuery?.getFuncName()
                val script = buildSendQuestionScript(text, funcName)
                if (!executeInPage(script)) {
                    copyToClipboard(text)
                    showNotification(DshBundle.message("sendLogExplanation.failed"))
                    return@invokeLater
                }
                if (funcName == null) {
                    // 无 JBCefJSQuery 通道：无法验证，乐观提示
                    pendingSend = null
                    showNotification(DshBundle.message("sendLogExplanation.done"))
                }
            } finally {
                sending.set(false)
            }
        }
    }

    /** 一键发送注入脚本：填 composer → 派发回车 → 轮询判定结果 → window.<funcName> 回传。 */
    private fun buildSendQuestionScript(text: String, funcName: String?): String {
        val json = escapeJs(text)
        val report = if (funcName != null) {
            "const report = (o) => { try { window.$funcName({ request: o, onSuccess: () => {}, onFailure: () => {} }); } catch (e) {} };"
        } else {
            "const report = () => {};"
        }
        return """
            (() => {
              const deadline = Date.now() + 8000;
              const text = $json;
              $report
              const tryInject = () => {
                const ta = document.querySelector('textarea');
                if (!ta) { if (Date.now() < deadline) setTimeout(tryInject, 300); else report('no-composer'); return; }
                const proto = window.HTMLTextAreaElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(ta, text);
                ta.dispatchEvent(new Event('input', { bubbles: true }));
                setTimeout(() => {
                  // 回车提交（dsh composer：非 shift 的 Enter → keyboard.submit；智能体忙时入队仍送达）
                  ta.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
                  const t0 = Date.now();
                  const clickSend = () => {
                    // 仅匹配"发送"按钮（发送/发送消息）；绝不用 class 通配，避免误点运行中的"停止"按钮
                    const btn = document.querySelector('button[aria-label="Send message"], button[aria-label="发送消息"], button[aria-label="Send"], button[aria-label="发送"]');
                    if (btn && !btn.disabled) { btn.click(); return true; }
                    return false;
                  };
                  const checkCleared = () => {
                    const cur = document.querySelector('textarea');
                    return !cur || cur.value.trim() === '';
                  };
                  const poll = () => {
                    if (checkCleared()) { report('submitted'); return; }
                    if (Date.now() - t0 < 3000) { setTimeout(poll, 250); return; }
                    if (clickSend()) {
                      setTimeout(() => { report(checkCleared() ? 'submitted' : 'blocked'); }, 500);
                    } else {
                      report('blocked');
                    }
                  };
                  setTimeout(poll, 400);
                }, 0);
              };
              tryInject();
            })();
        """.trimIndent()
    }

    /** JBCefJSQuery 结果处理（EDT）。 */
    private fun handleSendOutcome(text: String, outcome: String) {
        when (outcome) {
            "submitted" -> showNotification(DshBundle.message("sendLogExplanation.done"))
            "blocked" -> showNotification(DshBundle.message("sendLogExplanation.blocked"))
            else -> { // "no-composer" / 未知 → 剪贴板兜底
                copyToClipboard(text)
                showNotification(DshBundle.message("sendLogExplanation.failed"))
            }
        }
    }

    /** 在 dsh web 页面执行 JS（成功返回 true）。 */
    private fun executeInPage(script: String): Boolean {
        val b = browser ?: return false
        val cef = try { b.cefBrowser } catch (e: Throwable) { return false }
        return try {
            val pageUrl = runCatching { cef.url }.getOrNull() ?: "about:blank"
            cef.executeJavaScript(script, pageUrl, 0)
            true
        } catch (e: Throwable) {
            LOG.warn("JCEF executeJavaScript failed", e)
            false
        }
    }

    private fun escapeJs(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun copyToClipboard(text: String) {
        try {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        } catch (e: Exception) {
            LOG.warn("clipboard failed", e)
        }
    }

    private fun showNotification(content: String) {
        com.intellij.notification.Notifications.Bus.notify(
            com.intellij.notification.Notification(
                "DeepSeek Harness",
                "",
                content,
                com.intellij.notification.NotificationType.INFORMATION,
            ),
            project,
        )
    }

    // ---- DshProcessManager.Listener（后台线程回调，UI 更新切 EDT） ----

    override fun onStateChanged(oldState: DshProcessManager.State, newState: DshProcessManager.State) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = when (newState) {
                DshProcessManager.State.STARTING -> DshBundle.message("status.starting")
                DshProcessManager.State.RUNNING -> DshBundle.message("status.running")
                DshProcessManager.State.CRASHED -> DshBundle.message("status.crashed")
                DshProcessManager.State.STOPPED -> DshBundle.message("status.stopped")
            }
            // Step 5 FR-02.5：崩溃通知（自动重启已由 DshProcessManager 退避执行）
            if (newState == DshProcessManager.State.CRASHED && oldState != DshProcessManager.State.CRASHED) {
                notifyCrash()
            }
        }
    }

    override fun onUrlReady(url: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val b = browser ?: JBCefBrowser().also {
                    browser = it
                    add(it.component, CARD_BROWSER)
                }
                // JBCefJSQuery 必须在 loadURL 之前创建：CEF message router 在页面加载时把
                // window.<funcName> 注入页面；之后创建则函数不存在（自动发送无法回传结果）。
                setupJsQuery(b)
                // 页面加载完成后注入"点掉内测声明 + 捕获 API Key"脚本（CEF load handler 触发，
                // 比 onUrlReady 立即注入可靠；onUrlReady 时页面尚未加载，脚本不会执行）。
                installLoadHandler(b)
                b.loadURL(url)
                cards.show(this, CARD_BROWSER)
            } catch (e: Throwable) {
                LOG.warn("JCEF failed to load web ui", e)
                showError(buildJcefError(e))
            }
        }
    }

    /** 注册 CEF load handler：主 frame 加载完成后注入前端辅助脚本（内测声明点掉 + API Key 捕获）。 */
    private fun installLoadHandler(b: JBCefBrowser) {
        val injected = AtomicBoolean(false)
        try {
            b.getJBCefClient().addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain()) return
                    if (!injected.compareAndSet(false, true)) return
                    ApplicationManager.getApplication().invokeLater {
                        runCatching {
                            executeInPage(buildDismissNoticeScript())
                            val fn = jsQuery?.getFuncName()
                            if (fn != null) executeInPage(buildCaptureApiKeyScript(fn))
                        }
                    }
                }
            }, b.cefBrowser)
            LOG.info("JCEF load handler installed")
        } catch (e: Throwable) {
            LOG.warn("failed to install JCEF load handler", e)
        }
    }

    /** 构建捕获 dsh "Add an API key" 弹窗用户输入的 Key 的注入脚本（回传 Java 写 PasswordSafe）。 */
    private fun buildCaptureApiKeyScript(funcName: String): String = """
            (() => {
              const TITLE = 'Add an API key to get started';
              const SAVES = ['Save and continue', '保存并继续'];
              const findInput = () => {
                const inputs = document.querySelectorAll('input');
                for (const i of inputs) {
                  const ph = ((i.placeholder || '') + ' ' + (i.getAttribute('aria-label') || '')).toLowerCase();
                  if (ph.indexOf('api key') >= 0) return i;
                }
                return null;
              };
              const report = (key) => {
                try { window.${funcName}({ request: '__apikey__' + key, onSuccess: () => {}, onFailure: () => {} }); } catch (e) {}
              };
              const iv = setInterval(() => {
                const body = document.body ? document.body.innerText : '';
                if (body.indexOf(TITLE) < 0) return;
                const input = findInput();
                if (!input) return;
                const btns = document.querySelectorAll('button');
                for (const btn of btns) {
                  const txt = (btn.textContent || '').trim();
                  if (SAVES.indexOf(txt) >= 0) {
                    btn.addEventListener('click', () => {
                      const cur = findInput();
                      const k = (cur ? cur.value : input.value).trim();
                      if (k.length >= 8) report(k);
                    }, { once: true });
                    clearInterval(iv);
                    return;
                  }
                }
              }, 500);
              setTimeout(() => clearInterval(iv), 120000);
            })();
        """.trimIndent()

    /**
     * 构建"自动点掉内测声明（Internal Testing Notice / 内测声明）"的注入脚本：
     * 轮询检测模态出现，点击 Continue（en）/继续（zh）按钮一次。acknowledge 后 dsh 写入
     * settings.yaml（ui-onboarding.welcomeNoticeVersion），同项目后续不再显示。失败静默。
     */
    private fun buildDismissNoticeScript(): String = """
            (() => {
              const NOTICE_TEXTS = ['Internal Testing Notice', '内测声明'];
              const BTN_TEXTS = ['Continue', '继续'];
              const clicked = () => {
                const btns = document.querySelectorAll('button');
                for (const btn of btns) {
                  const t = (btn.textContent || '').trim();
                  if (BTN_TEXTS.indexOf(t) >= 0) { btn.click(); return true; }
                }
                return false;
              };
              const iv = setInterval(() => {
                const body = document.body ? document.body.innerText : '';
                if (NOTICE_TEXTS.some((t) => body.indexOf(t) >= 0)) {
                  if (clicked()) clearInterval(iv);
                }
              }, 500);
              setTimeout(() => clearInterval(iv), 15000);
            })();
        """.trimIndent()

    /** 创建 JBCefJSQuery 结果通道（失败不阻断：自动发送降级为无验证乐观提示）。 */
    private fun setupJsQuery(b: JBCefBrowserBase) {
        try {
            // create(JBCefBrowserBase) 为跨版本主 API（create(JBCefBrowser) 已弃用且 2026.2 可能移除）
            val query = JBCefJSQuery.create(b)
            query.addHandler { payload ->
                if (payload.startsWith(APIKEY_PREFIX)) {
                    // dsh "Add an API key" 弹窗输入的 Key：写回插件 PasswordSafe（脱敏显示 + 下次透传）
                    val key = payload.removePrefix(APIKEY_PREFIX)
                    ApplicationManager.getApplication().invokeLater {
                        if (key.isNotBlank()) {
                            runCatching { com.deepseek.harness.idea.runtime.DshCredentials.writeApiKey(key) }
                            showNotification(DshBundle.message("settings.apiKey.importedFromDsh"))
                        }
                    }
                } else {
                    val pending = pendingSend
                    if (pending != null) {
                        ApplicationManager.getApplication().invokeLater {
                            if (pendingSend === pending) {
                                pendingSend = null
                                handleSendOutcome(pending.text, payload)
                            }
                        }
                    }
                }
                JBCefJSQuery.Response("ok")
            }
            jsQuery = query
        } catch (e: Throwable) {
            LOG.warn("JBCefJSQuery unavailable; auto-send runs without result verification", e)
            jsQuery = null
        }
    }

    /**
     * JCEF 初始化失败提示（含根因线索，便于用户在真实 IDE 会话中排查）。
     * 2026.2 起 JCEF 是独立内置插件 com.intellij.modules.jcef（"Web Browser (JCEF)"），
     * 且需要 IDE 以带 JCEF 的 JBR 运行时启动；此处把异常信息与检查建议一并显示。
     */
    private fun buildJcefError(e: Throwable): String {
        val hint = DshBundle.message("error.jcef")
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$hint<br><br><b>${escapeHtml(detail)}</b><br><br>" +
            DshBundle.message("error.jcef.hint")
    }

    override fun onLogLine(line: String) {
        // Step 5 FR-08.1：转发到日志面板（后台线程回调，切 EDT）
        val panel = logPanel
        if (panel != null) {
            ApplicationManager.getApplication().invokeLater { panel.append(line) }
        }
    }

    private fun notifyCrash() {
        com.intellij.notification.Notifications.Bus.notify(
            com.intellij.notification.Notification(
                "DeepSeek Harness",
                DshBundle.message("crash.title"),
                DshBundle.message("crash.autoRestarting"),
                com.intellij.notification.NotificationType.WARNING,
            ),
            project,
        )
    }

    // ---- 卡片 ----

    private fun showCard(card: String) {
        ApplicationManager.getApplication().invokeLater { cards.show(this, card) }
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            errorLabel.text = "<html>${escapeHtml(message)}</html>"
            cards.show(this, CARD_ERROR)
        }
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildPlaceholderCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        JBLabel(DshBundle.message("toolwindow.placeholder.notStarted"), SwingConstants.CENTER)
    )

    private fun buildLoadingCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        JBLabel(DshBundle.message("toolwindow.placeholder.starting"), SwingConstants.CENTER),
        statusLabel
    )

    private fun buildProvisionCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        JBLabel(DshBundle.message("provision.status.downloadingTitle"), SwingConstants.CENTER),
        provisionStatusLabel,
        provisionProgress,
        buildUseLocalZipButton()
    )

    private fun buildErrorCard(): JComponent = columnCard(
        JBLabel(DshBundle.message("toolwindow.placeholder.title"), SwingConstants.CENTER),
        errorLabel,
        retryLabel.apply {
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = restart()
            })
        },
        buildUseLocalZipButton()
    )

    private fun buildUseLocalZipButton(): JButton =
        JButton(DshBundle.message("settings.runtimeDownload.chooseLocal")).apply {
            addActionListener { provisionLocalZip() }
        }

    private fun columnCard(vararg labels: JComponent): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }
        labels.forEachIndexed { i, c ->
            if (i > 0) c.border = JBUI.Borders.emptyTop(8)
            panel.add(c)
        }
        return panel
    }
}

/** 一次"一键发送"的等待态（token 用于丢弃过期回调，text 用于失败时剪贴板兜底）。 */
private class PendingSend(val token: Long, val text: String)

class OpenSettingsAction : AnAction(DshBundle.message("action.settings"), null, com.intellij.icons.AllIcons.General.Settings) {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, DshSettingsConfigurable::class.java)
    }
}

class OpenBrowserAction(private val panel: DshToolWindowPanel) :
    AnAction(DshBundle.message("action.browse"), null, com.intellij.icons.AllIcons.Toolwindows.WebToolWindow) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = panel.isRunning()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val url = panel.webUrl() ?: return
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (ex: Exception) {
            LOG.warn("failed to open browser $url", ex)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OpenBrowserAction::class.java)
    }
}

class RestartAction(private val panel: DshToolWindowPanel) : AnAction(DshBundle.message("action.restart"), null, com.intellij.icons.AllIcons.Actions.Restart) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
    }

    override fun actionPerformed(e: AnActionEvent) = panel.restart()
}
