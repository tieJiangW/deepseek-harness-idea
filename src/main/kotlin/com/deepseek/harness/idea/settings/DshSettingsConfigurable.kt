package com.deepseek.harness.idea.settings

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshCredentials
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.deepseek.harness.idea.runtime.RuntimeProvisioner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 设置页（Settings → Tools → DeepSeek Harness）。
 *
 * API Key 经 [DshCredentials]（PasswordSafe）保管并在 apply 时同步到
 * [DshHomeManager] 的 DSH_HOME/.credentials.yaml；model/baseUrl 存 [DshSettingsState]。
 *
 * **脱敏回显**：账户字段回显"前 6 位 + ****** + 后 6 位"（绝不显示明文）。用 [JBTextField]（而非
 * [JBPasswordField]）以让脱敏串可被看到；`isModified`/`apply` 用"字段内容 ≠ 当前脱敏串"判定用户是否
 * 真的改了 key，从而避免把脱敏串当作真实 key 写回密码库。
 */
class DshSettingsConfigurable : SearchableConfigurable {

    private var apiKeyField: JBTextField? = null
    private var modelCombo: ComboBox<String>? = null
    private var baseUrlField: JBTextField? = null
    private var logLevelCombo: ComboBox<String>? = null
    private var runtimeDownloadField: JBTextField? = null
    private var runtimeDirField: TextFieldWithBrowseButton? = null
    private var timeoutField: JBTextField? = null
    private var runtimeStatus: JBLabel? = null
    private var importStatus: JBLabel? = null

    /** 当前密码库中的真实 API Key（用于 apply 时区分"用户未改"与"用户输入新值"）。 */
    private var storedApiKey: String? = null

    override fun getId(): String = "dsh.settings"

    override fun getDisplayName(): String = DshBundle.message("settings.displayName")

    override fun createComponent(): JComponent {
        val state = DshSettingsState.getInstance()

        // 脱敏回显：显示"前 6 位 + ****** + 后 6 位"；未存 key 则显示空。
        // 用 JBTextField 让脱敏串可见（JBPasswordField 会把文本渲染成掩码点，用户看不到脱敏串）。
        // 读取先 PasswordSafe，无则回退到插件全局 DSH_HOME 的 .credentials.yaml（方案A真源）——
        // PasswordSafe 读不到（如 IDE 密码库未解锁）时仍能反显已在 .credentials.yaml 中的 Key。
        storedApiKey = readStoredApiKey()
        val apiKey = JBTextField().apply {
            text = DshCredentials.maskApiKey(storedApiKey)
            columns = 40
        }
        apiKeyField = apiKey

        val model = ComboBox(arrayOf("deepseek-chat", "deepseek-reasoner")).apply {
            selectedItem = if (state.model == "deepseek-reasoner") "deepseek-reasoner" else "deepseek-chat"
        }
        modelCombo = model

        val baseUrl = JBTextField(state.baseUrl.ifEmpty { "https://api.deepseek.com" }).apply { columns = 40 }
        baseUrlField = baseUrl

        // Step 5 FR-03.5：日志级别（透传 DSH_LOG_LEVEL）
        val logLevels = arrayOf("info", "debug", "warn", "error")
        val logLevel = ComboBox(logLevels).apply {
            selectedItem = logLevels.firstOrNull { it == state.logLevel } ?: "info"
        }
        logLevelCombo = logLevel

        // 运行时下载地址：**反显当前生效地址**（未覆盖时即默认的完整资产 URL，含当前版本/平台）；
        // 编辑即视为覆盖（支持目录级 base、含 {version} 占位符，或直接到文件的完整 URL）；「默认」按钮恢复默认。
        val defaultUrl = runCatching { DshHomeManager.getInstance().defaultRuntimeDownloadUrl() }.getOrNull().orEmpty()
        val runtimeDownload = JBTextField(
            state.runtimeDownloadUrl?.trim()?.takeIf { it.isNotEmpty() } ?: defaultUrl
        ).apply {
            columns = 40
            toolTipText = DshBundle.message("settings.runtimeDownload.hint")
        }
        runtimeDownloadField = runtimeDownload
        val defaultUrlButton = JButton(DshBundle.message("action.default")).apply {
            addActionListener { runtimeDownload.text = defaultUrl }
        }
        val downloadRow = JPanel(BorderLayout()).apply {
            add(runtimeDownload, BorderLayout.CENTER)
            add(defaultUrlButton, BorderLayout.EAST)
        }

        // 运行时目录：等价于环境变量 DSH_IDEA_RUNTIME 的 GUI 版本（环境变量优先级更高）。
        // 「默认」按钮填入插件的默认目录；"填入默认目录"与"未指定"等价
        // （DshHomeManager.isDefaultRuntimeDirectory），因此仍会自动下载/解压供给。
        val defaultDir = runCatching { DshHomeManager.getInstance().defaultRuntimeRoot().toString() }.getOrNull().orEmpty()
        val runtimeDir = TextFieldWithBrowseButton().apply {
            text = state.runtimeDirectory?.trim()?.takeIf { it.isNotEmpty() } ?: defaultDir
            toolTipText = DshBundle.message("settings.runtimeDir.hint")
            addBrowseFolderListener(
                DshBundle.message("settings.runtimeDir.label"),
                DshBundle.message("settings.runtimeDir.hint"),
                null,
                FileChooserDescriptorFactory.createSingleFolderDescriptor(),
            )
        }
        runtimeDirField = runtimeDir
        val defaultDirButton = JButton(DshBundle.message("action.default")).apply {
            addActionListener { runtimeDir.text = defaultDir }
        }
        val runtimeDirRow = JPanel(BorderLayout()).apply {
            add(runtimeDir, BorderLayout.CENTER)
            add(defaultDirButton, BorderLayout.EAST)
        }

        // 选择本地已下载的运行时 zip（离线导入）
        val chooseLocalButton = JButton(DshBundle.message("settings.runtimeDownload.chooseLocal")).apply {
            addActionListener {
                // 注意：IDEA 把 `.zip` 视为"归档"（FileElement.isArchive）；chooseJars=false 时
                // FileChooserDescriptor.isFileVisible 会直接隐藏归档文件（与 chooseFiles / fileFilter 无关），
                // 用户侧表现为"文件选择器里只看得到文件夹、看不到 zip"。
                // 这里**不做扩展名过滤**（显示所有类型文件），仅用 chooseJars=true 保证 zip 可选；
                // 选中的文件由 DshHomeManager.provisionFromLocalZip 做结构 + SHA-256 校验。
                val descriptor = FileChooserDescriptor(
                    /* chooseFiles = */ true,
                    /* chooseFolders = */ false,
                    /* chooseJars = */ true,
                    /* chooseJarsAsFiles = */ false,
                    /* chooseJarContents = */ false,
                    /* chooseMultiple = */ false,
                )
                    .withTitle(DshBundle.message("settings.runtimeDownload.chooseLocal"))
                val file = FileChooserFactory.getInstance()
                    .createFileChooser(descriptor, null, null)
                    .choose(null as com.intellij.openapi.project.Project?)
                    .firstOrNull()
                    ?: return@addActionListener
                runtimeStatus?.text = "…"
                ApplicationManager.getApplication().executeOnPooledThread {
                    // 以 VFS 为准解析路径（NIO 在个别环境下看不到所选文件，见 RuntimeZipStaging）
                    val zipPath = com.deepseek.harness.idea.runtime.RuntimeZipStaging.resolve(file)
                    val result = if (zipPath == null) {
                        RuntimeProvisioner.ProvisionResult.Failed(RuntimeProvisioner.ProvisionReason.LOCAL_INVALID, file.name)
                    } else {
                        DshHomeManager.getInstance().provisionFromLocalZip(zipPath)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        runtimeStatus?.text = when (result) {
                            is RuntimeProvisioner.ProvisionResult.Ready -> DshBundle.message("settings.runtimeDownload.localDone")
                            is RuntimeProvisioner.ProvisionResult.Failed -> DshBundle.message("settings.runtimeDownload.localFailed")
                        }
                    }
                }
            }
        }
        val rStatus = JBLabel(" ")
        runtimeStatus = rStatus
        val localRow = JPanel(BorderLayout()).apply {
            add(chooseLocalButton, BorderLayout.WEST)
            add(rStatus, BorderLayout.CENTER)
        }

        // 高级：下载读取超时（秒）
        val timeout = JBTextField(state.runtimeDownloadTimeoutSeconds.toString()).apply { columns = 10 }
        timeoutField = timeout

        val importButton = JButton(DshBundle.message("settings.import.button")).apply {
            addActionListener {
                importStatus?.text = "…"
                ApplicationManager.getApplication().executeOnPooledThread {
                    val key = CredentialImporter.importApiKey()
                    ApplicationManager.getApplication().invokeLater {
                        if (key == null) {
                            importStatus?.text = DshBundle.message("settings.import.failed")
                        } else {
                            apiKey.text = key
                            importStatus?.text = DshBundle.message("settings.import.done")
                        }
                    }
                }
            }
        }
        val status = JBLabel(" ")
        importStatus = status

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(DshBundle.message("settings.apiKey.label")), apiKey, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.model.label")), model, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.baseUrl.label")), baseUrl, 1, false)
            .addComponentToRightColumn(importButton)
            .addComponentToRightColumn(status)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.logLevel.label")), logLevel, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.runtimeDownload.label")), downloadRow, 1, false)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.runtimeDir.label")), runtimeDirRow, 1, false)
            .addComponent(localRow)
            .addLabeledComponent(JBLabel(DshBundle.message("settings.runtimeDownload.timeout.label")), timeout, 1, false)
            .addComponent(JBLabel(DshBundle.message("settings.apply.note")))
            .addVerticalGap(8)
            .panel
    }

    override fun isModified(): Boolean {
        val state = DshSettingsState.getInstance()
        val model = modelCombo?.selectedItem as? String
        val logLevel = logLevelCombo?.selectedItem as? String
        val timeout = timeoutField?.text?.trim()?.toIntOrNull() ?: state.runtimeDownloadTimeoutSeconds
        // 下载地址：与"默认地址"等价的输入视为未覆盖（apply 时保存为 null）
        val downloadText = runtimeDownloadField?.text?.trim().orEmpty()
        val downloadOverride =
            if (DshHomeManager.getInstance().isDefaultRuntimeDownloadUrl(downloadText)) "" else downloadText
        // 运行时目录：与默认目录等价的输入视为未指定
        val dirText = runtimeDirField?.text?.trim().orEmpty()
        val dirOverride = if (DshHomeManager.getInstance().isDefaultRuntimeDirectory(dirText)) "" else dirText
        return state.model != model || state.baseUrl != baseUrlField?.text?.trim().orEmpty() ||
            state.logLevel != logLevel || state.runtimeDownloadUrl?.trim().orEmpty() != downloadOverride ||
            state.runtimeDirectory?.trim().orEmpty() != dirOverride ||
            state.runtimeDownloadTimeoutSeconds != timeout || apiKeyChanged()
    }

    /** 用户是否改了 API Key（字段内容 ≠ 当前脱敏回显，即为新值）。 */
    private fun apiKeyChanged(): Boolean {
        val typed = apiKeyField?.text?.trim().orEmpty()
        val mask = DshCredentials.maskApiKey(storedApiKey)
        return typed != mask
    }

    override fun apply() {
        val state = DshSettingsState.getInstance()
        state.model = modelCombo?.selectedItem as? String ?: "deepseek-chat"
        state.baseUrl = baseUrlField?.text?.trim()?.ifEmpty { "https://api.deepseek.com" }
            ?: "https://api.deepseek.com"
        state.logLevel = logLevelCombo?.selectedItem as? String ?: "info"
        // 与默认地址等价的输入不落盘（保持"跟随默认"，避免把版本/平台钉死在配置里）
        val downloadText = runtimeDownloadField?.text?.trim().orEmpty()
        state.runtimeDownloadUrl =
            if (DshHomeManager.getInstance().isDefaultRuntimeDownloadUrl(downloadText)) null else downloadText
        // 与默认（插件自身的）运行时目录等价的输入不落盘：等价于"未指定"，仍会自动下载供给
        val dirText = runtimeDirField?.text?.trim().orEmpty()
        state.runtimeDirectory =
            if (DshHomeManager.getInstance().isDefaultRuntimeDirectory(dirText)) null else dirText
        state.runtimeDownloadTimeoutSeconds =
            (timeoutField?.text?.trim()?.toIntOrNull() ?: state.runtimeDownloadTimeoutSeconds).coerceIn(30, 100_000)

        // 仅当用户实际输入了新 key（而非脱敏回显原样）才写回，避免把脱敏串当 key 保存。
        val key = apiKeyField?.text?.trim().orEmpty()
        if (key.isNotEmpty() && key != DshCredentials.maskApiKey(storedApiKey)) {
            DshCredentials.writeApiKey(key)
            storedApiKey = key
            // 同步写入各项目 DSH_HOME 凭据文件（按项目隔离，v0.1.3-dev；运行中的会话需重启生效）
            ApplicationManager.getApplication().executeOnPooledThread {
                DshHomeManager.getInstance().syncCredentialsAll()
            }
        }
    }

    override fun reset() {
        val state = DshSettingsState.getInstance()
        modelCombo?.selectedItem = state.model
        baseUrlField?.text = state.baseUrl
        logLevelCombo?.selectedItem = state.logLevel
        runtimeDownloadField?.text = state.runtimeDownloadUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { DshHomeManager.getInstance().defaultRuntimeDownloadUrl() }.getOrNull().orEmpty()
        runtimeDirField?.text = state.runtimeDirectory?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { DshHomeManager.getInstance().defaultRuntimeRoot().toString() }.getOrNull().orEmpty()
        timeoutField?.text = state.runtimeDownloadTimeoutSeconds.toString()
        storedApiKey = readStoredApiKey()
        apiKeyField?.text = DshCredentials.maskApiKey(storedApiKey)
        importStatus?.text = " "
    }

    /**
     * 读取当前真实的 API Key：先 PasswordSafe，回退到插件全局 DSH_HOME 的 `.credentials.yaml`。
     * 返回的 Key 用于 apply 时区分"用户未改"与"输入新值"，避免把脱敏串当真实 key 写回。
     */
    private fun readStoredApiKey(): String? {
        val globalCredFile = DshHomeManager.getInstance().globalConfigHome().resolve(".credentials.yaml")
        return DshCredentials.readApiKeyWithFallback(globalCredFile)
    }
}
