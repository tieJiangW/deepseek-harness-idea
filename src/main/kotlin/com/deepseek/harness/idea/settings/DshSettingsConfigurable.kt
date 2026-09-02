package com.deepseek.harness.idea.settings

import com.deepseek.harness.idea.i18n.DshBundle
import com.deepseek.harness.idea.runtime.DshCredentials
import com.deepseek.harness.idea.runtime.DshHomeManager
import com.deepseek.harness.idea.runtime.RuntimeProvisioner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
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
    private var timeoutField: JBTextField? = null
    private var effectiveUrlLabel: JBLabel? = null
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

        // 平台兼容：运行时下载地址（覆盖默认 GitHub Releases baseUrl；{version} 占位符运行期替换）
        val runtimeDownload = JBTextField(state.runtimeDownloadUrl.orEmpty()).apply { columns = 40 }
        runtimeDownloadField = runtimeDownload

        // 只读回显：当前平台将下载的完整资产文件 URL（到文件名）+ 一键复制
        val urlLabel = JBLabel()
        urlLabel.isOpaque = false
        effectiveUrlLabel = urlLabel
        refreshEffectiveUrl()
        val copyButton = JButton(DshBundle.message("action.copy")).apply {
            addActionListener {
                val txt = computeEffectiveUrl()
                if (txt.isNotBlank()) {
                    try {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(txt), null)
                        runtimeStatus?.text = DshBundle.message("copy.done")
                    } catch (e: Exception) {
                        runtimeStatus?.text = " "
                    }
                }
            }
        }
        val urlRow = JPanel(BorderLayout()).apply {
            add(urlLabel, BorderLayout.CENTER)
            add(copyButton, BorderLayout.EAST)
        }

        // 选择本地已下载的运行时 zip（离线导入）
        val chooseLocalButton = JButton(DshBundle.message("settings.runtimeDownload.chooseLocal")).apply {
            addActionListener {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
                    .withTitle(DshBundle.message("settings.runtimeDownload.chooseLocal"))
                    .withFileFilter { it.extension?.equals("zip", ignoreCase = true) == true }
                val file = FileChooserFactory.getInstance()
                    .createFileChooser(descriptor, null, null)
                    .choose(null as com.intellij.openapi.project.Project?)
                    .firstOrNull()
                    ?: return@addActionListener
                runtimeStatus?.text = "…"
                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = DshHomeManager.getInstance().provisionFromLocalZip(java.nio.file.Paths.get(file.path))
                    ApplicationManager.getApplication().invokeLater {
                        runtimeStatus?.text = when (result) {
                            is RuntimeProvisioner.ProvisionResult.Ready -> DshBundle.message("settings.runtimeDownload.localDone")
                            is RuntimeProvisioner.ProvisionResult.Failed -> DshBundle.message("settings.runtimeDownload.localFailed")
                        }
                        refreshEffectiveUrl()
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
            .addLabeledComponent(JBLabel(DshBundle.message("settings.runtimeDownload.label")), runtimeDownload, 1, false)
            .addComponent(urlRow)
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
        return state.model != model || state.baseUrl != baseUrlField?.text?.trim().orEmpty() ||
            state.logLevel != logLevel || state.runtimeDownloadUrl?.trim().orEmpty() != runtimeDownloadField?.text?.trim().orEmpty() ||
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
        state.runtimeDownloadUrl = runtimeDownloadField?.text?.trim()?.takeIf { it.isNotEmpty() }
        state.runtimeDownloadTimeoutSeconds =
            (timeoutField?.text?.trim()?.toIntOrNull() ?: state.runtimeDownloadTimeoutSeconds).coerceIn(30, 100_000)
        refreshEffectiveUrl()

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
        runtimeDownloadField?.text = state.runtimeDownloadUrl.orEmpty()
        timeoutField?.text = state.runtimeDownloadTimeoutSeconds.toString()
        storedApiKey = readStoredApiKey()
        apiKeyField?.text = DshCredentials.maskApiKey(storedApiKey)
        importStatus?.text = " "
        refreshEffectiveUrl()
    }

    /** 当前平台将下载的完整资产文件 URL（到文件名）；无资产/无版本时回落到提示文案。 */
    private fun computeEffectiveUrl(): String =
        runCatching { DshHomeManager.getInstance().effectiveRuntimeDownloadUrl() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: DshBundle.message("settings.runtimeDownload.noEffectiveUrl")

    /** 刷新设置页的只读 URL 回显。 */
    private fun refreshEffectiveUrl() {
        effectiveUrlLabel?.text = computeEffectiveUrl()
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
