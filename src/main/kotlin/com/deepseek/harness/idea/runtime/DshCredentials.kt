package com.deepseek.harness.idea.runtime

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * DeepSeek API Key 的统一存取入口（PasswordSafe，应用级）。
 * 设置页与 DshHomeManager 共用同一组 CredentialAttributes，避免两处维护。
 */
object DshCredentials {
    const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"
    const val USER_NAME = "deepseek-api-key"

    private val ATTRIBUTES = CredentialAttributes("DshSettings", USER_NAME)

    private fun passwordSafe(): PasswordSafe =
        ApplicationManager.getApplication().getService(PasswordSafe::class.java)

    fun readApiKey(): String? = passwordSafe().getPassword(ATTRIBUTES)

    fun writeApiKey(key: String) = passwordSafe().setPassword(ATTRIBUTES, key)

    /**
     * 从 `DEEPSEEK_API_KEY: <key>` 形式的凭据 YAML 中解析 Key（行级解析，与
     * [com.deepseek.harness.idea.settings.CredentialImporter] 一致；独立实现避免循环依赖）。
     *
     * 注意：这是**宽松**解析（匹配任意缩进层级的该键）。dsh 的 `version: 1` 文档把真正的 key 放在
     * `refs:` 段下，读取共享凭据文件请优先用 [readApiKeyFromSharedDocument]（按段定位，避免误命中
     * `records` 等段内的同名键）。
     * @return 找到的 Key；文件缺失/无该键时返回 null。
     */
    fun readApiKeyFromCredentialFile(file: Path): String? {
        if (!Files.isReadable(file)) return null
        return Files.readAllLines(file).asSequence()
            .map { it.trim() }
            .filter { it.startsWith(DEEPSEEK_API_KEY) }
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx < 0) null else line.substring(idx + 1).trim().trim('"').trim('\'')
            }
            .firstOrNull { it.isNotEmpty() }
    }

    /**
     * 从 dsh 的 `version: 1` 凭据文档中读取 `refs.DEEPSEEK_API_KEY`（按段定位）。
     *
     * 兼容两种形态：
     * - `version: 1` + `refs:` 段（dsh 0.1.5 的正式 layout）；
     * - pre-release 扁平 layout（顶层直接 `DEEPSEEK_API_KEY: <key>`）。
     *
     * @return 找到的 Key；文件缺失/无该键时返回 null。
     */
    fun readApiKeyFromSharedDocument(file: Path): String? {
        if (!Files.isReadable(file)) return null
        val lines = Files.readAllLines(file)
        var inRefs = false
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isEmpty() || line.trimStart().startsWith("#")) continue
            val indented = line.first() == ' ' || line.first() == '\t'
            if (!indented) {
                inRefs = line.trim() == "refs:"
                // 扁平 layout：顶层直接就是键
                if (!inRefs && line.trim().startsWith("$DEEPSEEK_API_KEY:")) {
                    return line.substringAfter(':').trim().trim('"', '\'')
                }
                continue
            }
            if (!inRefs) continue
            val t = line.trim()
            if (t.startsWith("$DEEPSEEK_API_KEY:")) {
                return t.substringAfter(':').trim().trim('"', '\'')
            }
        }
        return null
    }

    /**
     * 统一读取当前 Key：**先 PasswordSafe，无则回退到 [credentialFile]（插件 DSH_HOME 的
     * `.credentials.yaml`）**。用于设置页脱敏回显等纯读场景——PasswordSafe 读不到（如 IDE 密码库
     * 未解锁）时仍能反显已在 `.credentials.yaml` 中的 Key。
     * @param credentialFile - 兜底凭据文件（如 [DshHomeManager.sharedCredentialsPath]）。
     */
    fun readApiKeyWithFallback(credentialFile: Path?): String? =
        readApiKey() ?: credentialFile?.let { readApiKeyFromCredentialFile(it) }

    /**
     * 脱敏显示：保留 key 的前 6 位与后 6 位，中间以 `******` 掩码。
     *
     * 用于设置页回显——不暴露完整 key，又能让用户辨认当前所存值。
     * - key 为空 → 空串（不显示任何占位）。
     * - key 长度 ≤ 12（无法同时保留前后各 6 位且中间有掩码）→ 整段显示为 `******`。
     * - 否则 → 前 6 位 + `******` + 后 6 位。
     *
     * 注意：这是**显示层**投影，不可逆；写回时须与原始 key 区分（见设置页 apply 逻辑）。
     */
    fun maskApiKey(key: String?): String {
        if (key.isNullOrEmpty()) return ""
        if (key.length <= 12) return "******"
        return key.take(6) + "******" + key.takeLast(6)
    }
}
