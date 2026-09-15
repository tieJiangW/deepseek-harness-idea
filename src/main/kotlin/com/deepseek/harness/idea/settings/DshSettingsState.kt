package com.deepseek.harness.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 应用级设置（跨项目共享）。API Key 不在此存储，经 PasswordSafe 保管，
 * 应用时写入插件 DSH_HOME 的 .credentials.yaml（Step 2 实现写入）。
 */
@State(name = "DshSettings", storages = [Storage("dsh-settings.xml")])
class DshSettingsState : PersistentStateComponent<DshSettingsState> {

    /** 模型：deepseek-chat / deepseek-reasoner */
    var model: String = "deepseek-chat"

    /** 兼容代理/自定义网关；默认官方地址 */
    var baseUrl: String = "https://api.deepseek.com"

    /** 高级：DSH_HOME 覆盖路径；null = 使用插件配置目录默认值 */
    var dshHomeOverride: String? = null

    /** 高级：运行时下载地址（覆盖默认 GitHub Releases baseUrl；支持 {version} 占位符） */
    var runtimeDownloadUrl: String? = null

    /**
     * 高级：手动指定的运行时目录（已解压、含 `node/` 与 `dsh/`）；null/空 = 使用默认下载/缓存目录。
     * 等价于环境变量 `DSH_IDEA_RUNTIME` 的 GUI 版本；环境变量优先级更高（见 `DshHomeManager.runtimeRoot()`）。
     */
    var runtimeDirectory: String? = null

    /** 高级：运行时下载读取超时（秒）；连接超时固定 60s。默认 600s，放宽以适配慢网络。 */
    var runtimeDownloadTimeoutSeconds: Int = 600

    var logLevel: String = "info"

    override fun getState(): DshSettingsState = this

    override fun loadState(state: DshSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(): DshSettingsState =
            ApplicationManager.getApplication().getService(DshSettingsState::class.java)
    }
}
