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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
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
 * 安装/卸载一律在后台线程跑（首次会下载私有 pnpm，之后是 npm 依赖下载），EDT 只负责刷新 UI。
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
    private var outputArea: JBTextArea? = null
    private var installButton: JButton? = null
    private var removeButton: JButton? = null
    private var refreshButton: JButton? = null

    /** 当前选中的项目根路径（空串 = 没有打开的项目，落到 `default` DSH_HOME）。 */
    private var selectedProjectPath: String = ""

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
        val scroll = JBScrollPane(tbl).apply { preferredSize = Dimension(720, 260) }

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
        val open = JButton(DshBundle.message("plugins.open")).apply {
            addActionListener {
                runCatching {
                    val dir = targetProfileDir()?.toFile()
                    if (dir != null && dir.isDirectory) java.awt.Desktop.getDesktop().open(dir)
                }
            }
        }

        val status = JBLabel(" ")
        statusLabel = status

        val output = JBTextArea(6, 80).apply { isEditable = false; lineWrap = false }
        outputArea = output
        val outputScroll = JBScrollPane(output).apply { preferredSize = Dimension(720, 110) }

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
                add(open)
            }, BorderLayout.EAST)
        }

        // 首次打开即刷新（放在 invokeLater：等 createComponent 返回、字段全部就位后再更新 UI）
        ApplicationManager.getApplication().invokeLater { refreshAsync() }

        return JPanel(BorderLayout(0, 8)).apply {
            add(top, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout(0, 6)).apply {
                    add(specRow, BorderLayout.NORTH)
                    add(status, BorderLayout.CENTER)
                    add(outputScroll, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = Unit

    // ---- 实现 ----

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

    /** 后台刷新：确保 DSH_HOME 骨架（含共享链接）后再读 manifest。 */
    private fun refreshAsync() {
        setBusy(true, DshBundle.message("plugins.busy.preparing"))
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                val home = homeManager().ensureHome(selectedProjectPath)
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
                setBusy(false, " ")
                result.onSuccess { (home, state, profile) ->
                    val dir = home.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
                    profilePathLabel?.text = DshBundle.message("plugins.profilePath", dir.toString())
                    shareLabel?.text = shareText(state)
                    tableModel?.setEntries(profile.entries())
                }.onFailure { e ->
                    statusLabel?.text = DshBundle.message("plugins.error.generic", e.message ?: e.toString())
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
            statusLabel?.text = specErrorText(error)
            return
        }
        operate("add", spec)
    }

    private fun remove() {
        val tbl = table ?: return
        val row = tbl.selectedRow
        if (row < 0) {
            statusLabel?.text = DshBundle.message("plugins.remove.none")
            return
        }
        val name = tbl.model.getValueAt(tbl.convertRowIndexToModel(row), 0) as? String ?: return
        operate("remove", name)
    }

    private fun specErrorText(error: DshProfileManifest.SpecError): String = DshBundle.message(
        when (error) {
            DshProfileManifest.SpecError.EMPTY -> "plugins.error.empty"
            DshProfileManifest.SpecError.LEADING_DASH -> "plugins.error.leadingDash"
            DshProfileManifest.SpecError.ILLEGAL_CHARS -> "plugins.error.illegalChars"
            DshProfileManifest.SpecError.RELATIVE_PATH -> "plugins.error.relativePath"
        },
    )

    /** 统一的安装/卸载流程：后台跑命令 → 刷表格 → 展示输出。 */
    private fun operate(action: String, spec: String) {
        setBusy(true, DshBundle.message(if (action == "add") "plugins.busy.installing" else "plugins.busy.removing", spec))
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                val home = homeManager().ensureHome(selectedProjectPath)
                DshPluginInstaller.run(
                    projectHome = home,
                    sharedRoot = homeManager().sharedConfigRoot(),
                    nodeExe = homeManager().nodeExe(),
                    dshBin = homeManager().dshBin(),
                    action = action,
                    spec = spec,
                )
            }.getOrElse { e ->
                DshPluginInstaller.Outcome(false, "", failure = DshPluginInstaller.Failure.ERROR, detail = e.message)
            }
            ApplicationManager.getApplication().invokeLater {
                setBusy(false, " ")
                outputArea?.text = outcome.output.trim().takeLast(8000)
                outputArea?.caretPosition = 0
                if (outcome.ok) {
                    tableModel?.setEntries(outcome.profile.entries())
                    statusLabel?.text = DshBundle.message(
                        if (action == "add") "plugins.done.install" else "plugins.done.remove",
                        spec,
                    )
                    specField?.text = ""
                } else {
                    statusLabel?.text = failureText(outcome)
                }
            }
        }
    }

    private fun failureText(outcome: DshPluginInstaller.Outcome): String {
        val base = when (outcome.failure) {
            DshPluginInstaller.Failure.NO_RUNTIME -> DshBundle.message("plugins.error.noRuntime")
            DshPluginInstaller.Failure.PNPM_SETUP_FAILED -> DshBundle.message("plugins.error.pnpmSetup")
            DshPluginInstaller.Failure.TIMEOUT -> DshBundle.message("plugins.error.timeout")
            DshPluginInstaller.Failure.COMMAND_FAILED -> DshBundle.message("plugins.error.command")
            DshPluginInstaller.Failure.ERROR, null -> DshBundle.message("plugins.error.generic", outcome.detail ?: "")
        }
        return if (outcome.detail.isNullOrEmpty() || base.contains(outcome.detail)) base else "$base (${outcome.detail})"
    }

    /** 选择本地插件目录 → 填 `link:<绝对路径>`（开发态联调用；路径含空格会被校验拒绝）。 */
    private fun chooseLocalPlugin() {
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(DshBundle.message("plugins.chooseLocal"))
        val file = FileChooserFactory.getInstance()
            .createFileChooser(descriptor, null as Project?, null)
            .choose(null as Project?)
            .firstOrNull() ?: return
        specField?.text = "link:" + file.path
    }

    private fun setBusy(busy: Boolean, text: String) {
        installButton?.isEnabled = !busy
        removeButton?.isEnabled = !busy
        refreshButton?.isEnabled = !busy
        specField?.isEnabled = !busy
        projectCombo?.isEnabled = !busy
        statusLabel?.text = text
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
}
