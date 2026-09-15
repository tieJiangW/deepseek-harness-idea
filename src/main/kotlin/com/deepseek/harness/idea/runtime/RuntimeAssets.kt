package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.util.JsonCodec

/**
 * 运行时资产地图：把目标平台映射到可下载的运行时 bundle 资产名，并组装下载 URL。
 *
 * 数据源为插件资源 `resources/runtime-assets.json`（瘦身通用插件随包携带）；
 * `baseUrl` 可被设置项覆盖（自建镜像/内网源），`{version}` 占位符在运行期替换为插件版本。
 */
data class RuntimeAssetSpec(
    val baseUrl: String,
    val assets: Map<String, String>,
) {
    /** 目标平台对应的资产文件名（如 `runtime-win-x64.zip`）；无匹配返回 null（当前平台不支持）。 */
    fun assetName(target: Platform.Target): String? = assets[target.id]

    fun urlFor(target: Platform.Target, version: String): String? {
        val resolved = baseUrl.replace("{version}", version).trim()
        // 覆盖值可直接是"到文件的完整 URL"（设置页反显/用户粘贴）→ 原样使用，不再拼接平台资产名
        if (resolved.endsWith(".zip", ignoreCase = true)) return resolved
        val name = assetName(target) ?: return null
        return if (resolved.endsWith("/")) resolved + name else "$resolved/$name"
    }

    /** 校验和侧车 URL（`<资产名>.sha256`，同源）。 */
    fun shaUrlFor(target: Platform.Target, version: String): String? = urlFor(target, version)?.plus(".sha256")
}

object RuntimeAssets {

    const val RESOURCE = "/runtime-assets.json"

    /** 加载资产地图；[downloadUrlOverride] 非空时替换 baseUrl（自建镜像/内网源）。 */
    fun load(downloadUrlOverride: String? = null): RuntimeAssetSpec {
        val default = readResource() ?: RuntimeAssetSpec("", emptyMap())
        return if (downloadUrlOverride.isNullOrBlank()) default
        else default.copy(baseUrl = downloadUrlOverride.trim())
    }

    fun parse(text: String): RuntimeAssetSpec {
        val obj = JsonCodec.decodeObject(text)
        val base = (obj["baseUrl"] as? String) ?: ""
        val assets = (obj["assets"] as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to (it.value?.toString() ?: "") }
            ?.filter { it.value.isNotBlank() }
            ?: emptyMap()
        return RuntimeAssetSpec(base, assets)
    }

    private fun readResource(): RuntimeAssetSpec? = try {
        RuntimeAssets::class.java.getResourceAsStream(RESOURCE)?.use { s ->
            parse(s.readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        null
    }
}
