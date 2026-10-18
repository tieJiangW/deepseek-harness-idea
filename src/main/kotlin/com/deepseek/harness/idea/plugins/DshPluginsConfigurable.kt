package com.deepseek.harness.idea.plugins

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.deepseek.harness.idea.settings.DshSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/**
 * 设置页：Settings → Tools → DeepSeek Harness → **DSH Plugins**（v0.2.5）。
 *
 * 为什么单独一页而不是塞进主设置页：主设置页是"全局参数"（API Key / 模型 / 运行时），
 * 而这一页是"对 dsh profile 执行操作"（安装/卸载插件包 + 看已装清单），有独立的忙/错状态
 * 与输出区域，混在一起会让两边的 apply 语义混乱（本页**没有**需要持久化的字段）。
 *
 * **作用范围**：操作目标是"当前 dsh 会话实际使用的 profile 目录"——
 * `<项目 DSH_HOME>/profiles/web`。开启共享（[DshSettingsState.shareDshProfiles]，默认开启）时
 * 该目录经链接指向共享根，因此**装一次所有项目生效**；关闭时只影响所选项目。
 *
 * **过程可见（v0.2.5 用户反馈驱动）**：pnpm 静默下载时可能几十秒没有任何输出，旧版只有一行
 * "Installing …" 和空白输出区，用户无法判断"是否装成功/是否卡死"。因此这里：
 * 1. **不定进度条**（indeterminate）+ 每秒刷新的"已用 Ns"；
 * 2. **实时流式输出**（`onLine` 每行回调，150ms 节流刷新，最多保留 [OUTPUT_MAX_LINES] 行）；
 * 3. **阶段文案**（校验 → 准备 pnpm → 安装），首次装 pnpm 会明确提示"需要联网"；
 * 4. **可取消**（杀子进程 + 回滚 profile manifest）；
 * 5. **明确结果**：绿色 ✓ / 红色 ✗ + 用时 + 结果行写入输出区（离开设置页也能从日志看到）。
 */
class DshPluginsConfigurable : SearchableConfigurable {

    private var projectCombo: com.intellij.openapi.ui.ComboBox<String>? = null
    private var projectPaths: List<String> = emptyList()
    private var profilePathLabel: JBLabel? = null
    private var shareLabel: JBLabel? = null
    private var table: JBTable? = null
    private var tableModel: PluginTableModel? = null
    private var specField: JBTextField? = null
    private var statusLabel: JBLabel? = null
    private var progressBar: JProgressBar? = null
    private var outputArea: JBTextArea? = null
    private var installButton: JButton? = null
    private var removeButton: JButton? = null
    private var refreshButton: JButton? = null
    private var cancelButton: JButton? = null

    /** 当前选中的项目根路径（空串 = 没有打开的项目，落到 `default` DSH_HOME）。 */
    private var selectedProjectPath: String = ""

    // ---- 运行态（只读于 EDT） ----

    /** 是否有安装/卸载在跑（决定按钮可用性与取消语义）。 */
    private var operationRunning = false

    /** 用户点了「取消」：传给 [DshPluginInstaller.run] 的取消信号。 */
    private val cancelRequested = AtomicBoolean(false)

    /** 当前阶段文案（"准备 pnpm" / "正在安装 x"…），ticker 每秒把它和用时一起显示。 */
    private var phaseText: String = ""

    /** 本次操作开始时间（毫秒），用于"已用 Ns"。 */
    private var startedAt: Long = 0L

    /**
     * 最后一次收到子进程活动（输出行或心跳）的时间；长时间无活动时提示"等待输出"。
     * 由后台线程（心跳）与 EDT（输出行）共同写入，因此 `@Volatile`。
     */
    @Volatile
    private var lastActivityAt: Long = 0L

    /** 每秒刷新"已用 Ns / 最近活动"的 ticker。 */
    private var ticker: Timer? = null

    /** 刷新超时后的"仍在读取"兜底文案（一次性）。 */
    private var slowHintTimer: Timer? = null

    /** 刷新序号：丢弃被更新一次刷新取代的旧结果/旧兜底文案。 */
    private val refreshSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 输出区缓冲（行列表；超过上限截断最旧的，避免 pnpm 刷屏把内存吃满）。 */
    private val outputLines = ArrayList<String>(256)

    /** 输出刷新的 150ms 节流（逐行设置 JTextArea 文本是 O(n²)，pnpm 进度行很多）。 */
    private var flushTimer: Timer? = null
    private var outputDirty = false

    override fun getId(): String = "dsh.plugins"

    override fun getDisplayName(): String = DshBundle.message("plugins.displayName")

    override fun createComponent(): JComponent {
        // 只列真实打开的项目（默认项目没有 basePath）；一个项目都没开时用空串 =
        // `homeDir("")` 的 `default` DSH_HOME —— 共享开启时它的 profile 同样指向共享根，安装依然有效。
        val openPaths = ProjectManager.getInstance().openProjects
            .filterNot { it.isDefault }
            .mapNotNull { it.basePath }
        projectPaths = openPaths.ifEmpty { listOf("") }
        selectedProjectPath = projectPaths.first()

        val combo = com.intellij.openapi.ui.ComboBox(projectPaths.map { displayProject(it) }.toTypedArray()).apply {
            addActionListener {
                if (operationRunning) return@addActionListener
                selectedProjectPath = projectPaths.getOrElse(selectedIndex) { "" }
                refreshAsync()
            }
        }
        projectCombo = combo

        val profilePath = JBLabel(" ").apply { font = font.deriveFont(font.size2D - 1f) }
        profilePathLabel = profilePath

        val share = JBLabel(" ")
        shareLabel = share

        val model = PluginTableModel()
        tableModel = model
        val tbl = JBTable(model)
        tbl.setShowGrid(false)
        tbl.rowSorter = TableRowSorter(model)
        tbl.emptyText.text = DshBundle.message("plugins.empty")
        tbl.columnModel.getColumn(0).preferredWidth = 360
        tbl.columnModel.getColumn(1).preferredWidth = 160
        tbl.columnModel.getColumn(2).preferredWidth = 140
        table = tbl
        val scroll = JBScrollPane(tbl).apply { preferredSize = Dimension(720, 240) }

        val spec = JBTextField().apply {
            emptyText.text = DshBundle.message("plugins.spec.hint")
            toolTipText = DshBundle.message("plugins.spec.hint.long")
        }
        specField = spec

        val installBtn = JButton(DshBundle.message("plugins.install")).apply {
            addActionListener { install() }
        }
        installButton = installBtn
        val removeBtn = JButton(DshBundle.message("plugins.remove")).apply {
            addActionListener { remove() }
        }
        removeButton = removeBtn
        val chooseLocal = JButton(DshBundle.message("plugins.chooseLocal")).apply {
            addActionListener { chooseLocalPlugin() }
        }
        val refreshBtn = JButton(DshBundle.message("plugins.refresh")).apply {
            addActionListener { refreshAsync() }
        }
        refreshButton = refreshBtn
        val cancelBtn = JButton(DshBundle.message("plugins.cancel")).apply {
            addActionListener { cancelOperation() }
            isVisible = false
        }
        cancelButton = cancelBtn
        val open = JButton(DshBundle.message("plugins.open")).apply {
            addActionListener {
                runCatching {
                    val dir = targetProfileDir()?.toFile()
                    if (dir != null && dir.isDirectory) java.awt.Desktop.getDesktop().open(dir)
                }
            }
        }

        // 不定进度条：安装时长不可预测（首次还要下 pnpm + 插件包），只表达"正在进行"
        val bar = JProgressBar().apply {
            isIndeterminate = false
            isVisible = false
            preferredSize = Dimension(160, progressBarHeight())
            toolTipText = DshBundle.message("plugins.progress.hint")
        }
        progressBar = bar

        val status = JBLabel(DshBundle.message("plugins.status.idle"))
        statusLabel = status

        val output = JBTextArea(8, 80).apply {
            isEditable = false
            lineWrap = false
            toolTipText = DshBundle.message("plugins.output.hint")
        }
        outputArea = output
        val outputScroll = JBScrollPane(output).apply { preferredSize = Dimension(720, 150) }

        val top = JPanel(BorderLayout()).apply {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JBLabel(DshBundle.message("plugins.project.label")))
                add(combo)
                add(JBLabel(DshBundle.message("plugins.share.label")))
                add(share)
            }
            add(row, BorderLayout.NORTH)
            add(profilePath, BorderLayout.CENTER)
        }

        val specRow = JPanel(BorderLayout(6, 0)).apply {
            add(spec, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(chooseLocal)
                add(installBtn)
                add(removeBtn)
                add(refreshBtn)
                add(cancelBtn)
                add(open)
            }, BorderLayout.EAST)
        }

        val statusRow = JPanel(BorderLayout(8, 0)).apply {
            add(bar, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }

        // 首次打开即刷新（放在 invokeLater：等 createComponent 返回、字段全部就位后再更新 UI）
        ApplicationManager.getApplication().invokeLater { refreshAsync() }

        return JPanel(BorderLayout(0, 8)).apply {
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout(0, 6)).apply {
                    add(specRow, BorderLayout.NORTH)
                    add(statusRow, BorderLayout.CENTER)
                    add(outputScroll, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = Unit

    /**
     * 关闭设置页（OK / Cancel / 关闭窗口都会走这里）时收尾：
     * 停掉 ticker 与刷新定时器，并**中止仍在跑的安装** —— 否则会留下一个"看不见的后台任务"，
     * 而用户点了 Cancel 后通常以为它已经停了。中止同样会回滚 profile manifest，可安全重跑。
     */
    override fun disposeUIResources() {
        stopTicker()
        flushTimer?.stop()
        flushTimer = null
        if (operationRunning) cancelRequested.set(true)
        operationRunning = false
    }

    // ---- 展示辅助 ----

    private fun progressBarHeight(): Int = JBLabel().preferredSize.height

    private fun okColor(): Color = JBColor(Color(0x1B, 0x7F, 0x37), Color(0x7A, 0xC5, 0x7A))

    private fun errorColor(): Color = JBColor(Color(0xB7, 0x1C, 0x1C), Color(0xFF, 0x7B, 0x72))

    private fun neutralColor(): Color = JBColor(Color(0x33, 0x33, 0x33), Color(0xBB, 0xBB, 0xBB))

    /** 设置状态行文本与颜色（`null` = 普通色）。 */
    private fun setStatus(text: String, color: Color? = null) {
        statusLabel?.text = text
        // 长文本会被 BorderLayout 裁掉，tooltip 兜住全文
        statusLabel?.toolTipText = text
        statusLabel?.foreground = color ?: neutralColor()
    }

    private fun secondsSince(now: Long, from: Long): Long = ((now - from) / 1000).coerceAtLeast(0)

    /** 每秒刷新忙碌状态：阶段 + 已用时长（+ 长时间无输出时提示仍在等待）。 */
    private fun startTicker(showSilenceHint: Boolean) {
        stopTicker()
        ticker = Timer(1000) {
            if (!operationRunning) return@Timer
            val now = System.currentTimeMillis()
            val elapsed = secondsSince(now, startedAt)
            // 只有**真的在跑子进程**（安装/卸载）时，"长时间无输出"才是有意义的提示；
            // 读取 profile 没有子进程，显示"pnpm 可能静默…可取消"只会误导（v0.2.5 用户实测）。
            val text = if (showSilenceHint && secondsSince(now, lastActivityAt) >= 6) {
                DshBundle.message("plugins.status.runningIdle", phaseText, elapsed, secondsSince(now, lastActivityAt))
            } else {
                DshBundle.message("plugins.status.running", phaseText, elapsed)
            }
            setStatus(text, neutralColor())
        }.apply { start() }
    }

    private fun stopTicker() {
        ticker?.stop()
        ticker = null
    }

    /** 忙碌态切换：进度条 / 取消按钮 / 输入控件一起联动。 */
    private fun setBusy(busy: Boolean, phase: String = "", cancellable: Boolean = false) {
        operationRunning = busy
        installButton?.isEnabled = !busy
        removeButton?.isEnabled = !busy
        refreshButton?.isEnabled = !busy
        specField?.isEnabled = !busy
        projectCombo?.isEnabled = !busy
        progressBar?.isVisible = busy
        progressBar?.isIndeterminate = busy
        cancelButton?.isVisible = busy && cancellable
        cancelButton?.isEnabled = busy && cancellable
        if (busy) {
            phaseText = phase
            startedAt = System.currentTimeMillis()
            lastActivityAt = startedAt
            cancelRequested.set(false)
            setStatus(DshBundle.message("plugins.status.running", phase, 0), neutralColor())
            startTicker(showSilenceHint = cancellable)
        } else {
            stopTicker()
            slowHintTimer?.stop()
            slowHintTimer = null
            cancelRequested.set(false)
        }
    }

    /** 切换阶段文案（ticker 会带上用时重新渲染）。 */
    private fun setPhase(text: String) {
        phaseText = text
        lastActivityAt = System.currentTimeMillis()
        if (operationRunning) {
            setStatus(DshBundle.message("plugins.status.running", text, secondsSince(System.currentTimeMillis(), startedAt)), neutralColor())
        } else {
            setStatus(text, neutralColor())
        }
    }

    // ---- 输出区（流式 + 节流 + 行数上限） ----

    /** 追加一行输出（**必须在 EDT 调用**）。 */
    private fun appendOutputLine(line: String) {
        outputLines.add(line)
        if (outputLines.size > OUTPUT_MAX_LINES) {
            outputLines.subList(0, outputLines.size - OUTPUT_MAX_LINES).clear()
        }
        lastActivityAt = System.currentTimeMillis()
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (outputDirty) return
        outputDirty = true
        val timer = flushTimer ?: Timer(150) {
            outputDirty = false
            val area = outputArea ?: return@Timer
            area.text = outputLines.joinToString("\n")
            area.caretPosition = area.document.length
        }.apply { isRepeats = false }.also { flushTimer = it }
        timer.restart()
    }

    private fun clearOutput() {
        outputLines.clear()
        outputDirty = false
        flushTimer?.stop()
        outputArea?.text = ""
    }

    // ---- 交互 ----

    /** 项目下拉显示名：目录名 + 完整路径（同名项目可区分）。 */
    private fun displayProject(path: String): String {
        if (path.isEmpty()) return DshBundle.message("plugins.project.none")
        val name = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return if (name.isEmpty()) path else "$name — $path"
    }

    /** 目标 profile 目录（项目 DSH_HOME 的 `profiles/web`）；DSH_HOME 还未就绪时返回 null。 */
    private fun targetProfileDir(): Path? = runCatching {
        homeManager().homeDir(selectedProjectPath).resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
    }.getOrNull()

    private fun homeManager(): DshHomeManager = DshHomeManager.getInstance()

    /** 后台刷新：确保 profile 目录就绪（**轻量路径**，见 [DshHomeManager.ensureProfileHome]）后读 manifest。 */
    private fun refreshAsync() {
        val seq = refreshSeq.incrementAndGet()
        setBusy(true, DshBundle.message("plugins.phase.preparing"))
        // 兜底：万一这一步仍然慢（首次迁移/磁盘抖动），5 秒后换成"仍在读取"的**中性**文案。
        // 旧版这里会显示"pnpm 可能静默…可取消"，但读取 profile 既没有子进程也没有取消按钮 —— 纯误导。
        slowHintTimer?.stop()
        slowHintTimer = Timer(5000) {
            if (operationRunning && refreshSeq.get() == seq) {
                setStatus(DshBundle.message("plugins.phase.preparingSlow"), neutralColor())
            }
        }.apply { isRepeats = false; start() }

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val home = homeManager().ensureProfileHome(selectedProjectPath)
                val state = homeManager().profilesState(selectedProjectPath)
                val profile = DshProfileManifest.parse(
                    runCatching {
                        java.nio.file.Files.readString(
                            home.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
                                .resolve(DshProfileManifest.FILE_NAME),
                            java.nio.charset.StandardCharsets.UTF_8,
                        )
                    }.getOrNull(),
                )
                Triple(home, state, profile)
            }
            ApplicationManager.getApplication().invokeLater {
                if (refreshSeq.get() != seq) return@invokeLater // 已有更新的一次刷新，丢弃本次结果
                slowHintTimer?.stop()
                slowHintTimer = null
                setBusy(false)
                result.onSuccess { (home, state, profile) ->
                    val dir = home.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
                    profilePathLabel?.text = DshBundle.message("plugins.profilePath", dir.toString())
                    shareLabel?.text = shareText(state)
                    tableModel?.setEntries(profile.entries())
                    setStatus(
                        DshBundle.message("plugins.status.loaded", profile.plugins.size, profile.dependencies.size),
                        neutralColor(),
                    )
                }.onFailure { e ->
                    setStatus(DshBundle.message("plugins.error.generic", e.message ?: e.toString()), errorColor())
                }
            }
        }
    }

    private fun shareText(state: DshHomeManager.ProfilesState): String =
        if (state.shared) {
            DshBundle.message("plugins.share.on")
        } else {
            DshBundle.message(
                "plugins.share.off",
                DshBundle.message("plugins.share.reason." + (state.reason ?: "not-created")),
            )
        }

    private fun install() {
        val spec = DshProfileManifest.normalizeSpec(specField?.text)
        val error = DshProfileManifest.validateSpec(spec)
        if (error != null) {
            setStatus(specErrorText(error), errorColor())
            return
        }
        operate("add", spec)
    }

    private fun remove() {
        val tbl = table ?: return
        val row = tbl.selectedRow
        if (row < 0) {
            setStatus(DshBundle.message("plugins.remove.none"), errorColor())
            return
        }
        val name = tbl.model.getValueAt(tbl.convertRowIndexToModel(row), 0) as? String ?: return
        operate("remove", name)
    }

    /**
     * 用户点「取消」：置取消信号，子进程在 200ms 内被杀并回滚 manifest。
     *
     * 注意**不能**命名为 `cancel()`：`UnnamedConfigurable`/`Configurable` 已有 `cancel()`（对话框「Cancel」
     * 按钮的回调），同名会被当作用户取消设置对话框。
     */
    private fun cancelOperation() {
        if (!operationRunning) return
        cancelRequested.set(true)
        cancelButton?.isEnabled = false
        setStatus(DshBundle.message("plugins.status.cancelling"), neutralColor())
    }

    private fun specErrorText(error: DshProfileManifest.SpecError): String = DshBundle.message(
        when (error) {
            DshProfileManifest.SpecError.EMPTY -> "plugins.error.empty"
            DshProfileManifest.SpecError.LEADING_DASH -> "plugins.error.leadingDash"
            DshProfileManifest.SpecError.ILLEGAL_CHARS -> "plugins.error.illegalChars"
            DshProfileManifest.SpecError.RELATIVE_PATH -> "plugins.error.relativePath"
        },
    )

    /** 统一的安装/卸载流程：后台跑命令（实时输出）→ 结果写入状态行与输出区。 */
    private fun operate(action: String, spec: String) {
        if (operationRunning) return
        clearOutput()
        val verb = if (action == "add") "plugins.phase.installing" else "plugins.phase.removing"
        setBusy(true, DshBundle.message(verb, spec), cancellable = true)
        appendOutputLine("== " + DshBundle.message(verb, spec))

        ApplicationManager.getApplication().executeOnPooledThread {
            fun post(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block)
            val outcome = runCatching {
                // 只确保 profile 目录/链接就绪（轻量）；重量级初始化留给 dsh 启动时的 ensureHome
                val home = homeManager().ensureProfileHome(selectedProjectPath)
                DshPluginInstaller.run(
                    projectHome = home,
                    sharedRoot = homeManager().sharedConfigRoot(),
                    nodeExe = homeManager().nodeExe(),
                    dshBin = homeManager().dshBin(),
                    action = action,
                    spec = spec,
                    onPhase = { phase ->
                        post { if (operationRunning) setPhase(phaseTextOf(phase, action, spec)) }
                    },
                    onLine = { line ->
                        // 心跳只更新时间戳（证明子进程还活着），不写进输出区
                        if (line == DshPluginInstaller.HEARTBEAT) {
                            lastActivityAt = System.currentTimeMillis()
                        } else {
                            post { if (operationRunning) appendOutputLine(line) }
                        }
                    },
                    cancelled = { cancelRequested.get() },
                )
            }.getOrElse { e ->
                DshPluginInstaller.Outcome(false, "", failure = DshPluginInstaller.Failure.ERROR, detail = e.message)
            }

            post {
                setBusy(false)
                val elapsed = (outcome.elapsedMs / 1000).coerceAtLeast(0)
                if (outcome.ok) {
                    tableModel?.setEntries(outcome.profile.entries())
                    val text = DshBundle.message(
                        if (action == "add") "plugins.done.install" else "plugins.done.remove",
                        spec,
                    )
                    appendOutputLine("")
                    appendOutputLine("✓ " + text + " " + DshBundle.message("plugins.elapsed", elapsed))
                    setStatus("✓ " + text, okColor())
                    specField?.text = ""
                    notifyResult(true, text)
                } else {
                    val text = failureText(outcome)
                    appendOutputLine("")
                    appendOutputLine("✗ " + text)
                    setStatus("✗ " + text, errorColor())
                    notifyResult(false, text)
                }
            }
        }
    }

    /**
     * 结果也走 IDE 通知（用户常常点完「安装」就切去看别的窗口，回来时对话框可能已关）：
     * 成功 = INFORMATION，失败 = ERROR，文案里带"需重启 Harness"。
     */
    private fun notifyResult(ok: Boolean, text: String) {
        runCatching {
            com.intellij.notification.Notifications.Bus.notify(
                com.intellij.notification.Notification(
                    "DeepSeek Harness",
                    DshBundle.message("plugins.displayName"),
                    text,
                    if (ok) com.intellij.notification.NotificationType.INFORMATION
                    else com.intellij.notification.NotificationType.ERROR,
                ),
            )
        }
    }

    private fun phaseTextOf(phase: DshPluginInstaller.Phase, action: String, spec: String): String = when (phase) {
        DshPluginInstaller.Phase.CHECKING -> DshBundle.message("plugins.phase.checking")
        DshPluginInstaller.Phase.PREPARING_PNPM -> DshBundle.message("plugins.phase.pnpm")
        DshPluginInstaller.Phase.RUNNING -> DshBundle.message(
            if (action == "add") "plugins.phase.installing" else "plugins.phase.removing",
            spec,
        )
    }

    private fun failureText(outcome: DshPluginInstaller.Outcome): String {
        val base = when (outcome.failure) {
            DshPluginInstaller.Failure.NO_RUNTIME -> DshBundle.message("plugins.error.noRuntime")
            DshPluginInstaller.Failure.PNPM_SETUP_FAILED -> DshBundle.message("plugins.error.pnpmSetup")
            DshPluginInstaller.Failure.TIMEOUT -> DshBundle.message("plugins.error.timeout")
            DshPluginInstaller.Failure.CANCELLED -> DshBundle.message("plugins.error.cancelled")
            DshPluginInstaller.Failure.COMMAND_FAILED -> DshBundle.message("plugins.error.command")
            DshPluginInstaller.Failure.ERROR, null -> DshBundle.message("plugins.error.generic", outcome.detail ?: "")
        }
        val detail = outcome.detail
        return if (detail.isNullOrEmpty() || outcome.failure == DshPluginInstaller.Failure.ERROR || base.contains(detail)) {
            base
        } else {
            "$base ($detail)"
        }
    }

    /** 选择本地插件目录 → 填 `link:<绝对路径>`（开发态联调用）。 */
    private fun chooseLocalPlugin() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(DshBundle.message("plugins.chooseLocal"))
        val file = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, null as Project?, null)
            .choose(null as Project?)
            .firstOrNull() ?: return
        specField?.text = "link:" + file.path
    }

    /** 表格模型：三列（包名 / 版本 / 类型）。 */
    private class PluginTableModel : AbstractTableModel() {

        private var entries: List<DshProfileManifest.Entry> = emptyList()

        fun setEntries(list: List<DshProfileManifest.Entry>) {
            entries = list
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = entries.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String = DshBundle.message(
            when (column) {
                0 -> "plugins.table.name"
                1 -> "plugins.table.version"
                else -> "plugins.table.type"
            },
        )

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val e = entries.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                0 -> e.name
                1 -> e.version.orEmpty()
                else -> DshBundle.message(if (e.bundle) "plugins.type.bundle" else "plugins.type.dependency")
            }
        }
    }

    private companion object {
        /** 输出区保留的最大行数（pnpm 进度行可能很多；超出后丢弃最旧的）。 */
        const val OUTPUT_MAX_LINES = 4000
    }
}
