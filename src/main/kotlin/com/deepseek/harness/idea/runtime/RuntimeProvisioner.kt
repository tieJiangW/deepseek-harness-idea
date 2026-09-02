package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedInputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 运行时下载器入口（瘦身通用插件的运行时下发）。可注入 [RuntimeFetcher] 便于单测。
 *
 * 流程：本地已有运行时短路 → 组装下载 URL（资产地图 + 插件版本）→ 拉取校验和侧车 `.sha256`
 * → 下载 bundle → SHA-256 比对（不一致拒绝）→ 安全解压到运行时根 → 再次校验 node + dsh 就绪。
 *
 * 信任锚定到同源 `.sha256` 侧车；任一步失败返回 [ProvisionResult.Failed]（调用方给出可操作报错）。
 * 下载支持进度回调与取消（见 [DownloadOptions]），并可在 [RuntimeFetcher.download] 中注入连接/读取超时。
 *
 * 连接质量：默认 [HttpFetcher] 用 `java.net.http.HttpClient`（HTTP/2 + 连接池复用 + 自动跟 302），
 * 并把校验和拉取的连接超时放宽到 60s，避免国内到 GitHub 的冷握手在 15s 内被放弃（浏览器却可用）。
 */
object RuntimeProvisioner {

    private val LOG = Logger.getInstance(RuntimeProvisioner::class.java)

    /** 下载抽象（接口，便于测试注入）。 */
    interface RuntimeFetcher {
        /** 把 [url] 下载到 [dest]；[options] 携带超时/进度/取消。成功返回 true。 */
        fun download(url: String, dest: Path, options: DownloadOptions): Boolean

        /** 拉取 [url] 全文（校验和侧车）；失败返回 null。 */
        fun fetchText(url: String): String?
    }

    /**
     * 下载选项：连接/读取超时 + 进度回调 + 取消探测。
     * [progress] 在下载过程中被反复调用（{downloaded, total, assetName}；total=-1 表示未知）。
     * [cancelled] 每次缓冲区读取后探测，返回 true 时中止下载（不重试）。
     */
    data class DownloadOptions(
        val connectTimeoutMs: Int = 60_000,
        val readTimeoutMs: Int = 600_000,
        val progress: ((downloaded: Long, total: Long, assetName: String) -> Unit)? = null,
        val cancelled: (() -> Boolean)? = null,
    )

    /** 下发结果。 */
    sealed interface ProvisionResult {
        data object Ready : ProvisionResult

        data class Failed(val reason: ProvisionReason, val assetUrl: String?, val detail: String? = null) : ProvisionResult
    }

    enum class ProvisionReason {
        BASE_EMPTY, NO_ASSET, CHECKSUM_UNREACHABLE, DOWNLOAD_FAILED,
        SHA_MISMATCH, EXTRACT_FAILED, INCOMPLETE, CANCELLED, LOCAL_INVALID
    }

    /** 默认实现：java.net.http.HttpClient（HTTP/2 + 连接池 + 自动跟 302 + 退避重试）。 */
    object HttpFetcher : RuntimeFetcher {

        private const val CONNECT_TIMEOUT_SECONDS = 60L
        private val CHECKSUM_TIMEOUT: Duration = Duration.ofMinutes(2)
        private const val MAX_ATTEMPTS = 3
        private const val BACKOFF_MS = 1000L

        /** 浏览器风格 UA：规避部分受限网络/反代对 `Java-http-client` 默认 UA 的拦截。 */
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

        /** 上次下载失败的根因（供 UI 展示，便于定位）。 */
        @Volatile
        var lastError: String? = null

        /** 惰性单例：连接复用 + 自动跟 302 + 退避重试。用 HTTP/1.1，避免受限网络下 HTTP/2 大流被重置。 */
        private val client: HttpClient by lazy {
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL) // 自动跟 GitHub 资产 302
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .proxy(ProxySelector.getDefault() ?: ProxySelector.of(null))
                .build()
        }

        override fun download(url: String, dest: Path, options: DownloadOptions): Boolean {
            lastError = null
            for (i in 1..MAX_ATTEMPTS) {
                if (downloadOnce(url, dest, options)) return true
                if (options.cancelled?.invoke() == true) return false // 取消不重试
                if (i < MAX_ATTEMPTS) {
                    LOG.info("runtime download attempt $i failed for $url; retrying in ${BACKOFF_MS * i}ms")
                    Thread.sleep(BACKOFF_MS * i)
                }
            }
            return false
        }

        private fun downloadOnce(url: String, dest: Path, options: DownloadOptions): Boolean {
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(options.readTimeoutMs.coerceAtLeast(30_000).toLong()))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    LOG.warn("runtime download http ${response.statusCode()} for $url")
                    lastError = "HTTP ${response.statusCode()}"
                    response.body().close()
                    runCatching { Files.deleteIfExists(dest) }
                    return false
                }
                val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L).let { if (it > 0) it else -1L }
                val assetName = url.substringAfterLast('/').substringBeforeLast('.')
                // 关键：临时下载文件的父目录在全新安装时可能还不存在，先确保其存在再落盘。
                dest.parent?.let { Files.createDirectories(it) }
                response.body().use { body ->
                    BufferedInputStream(body).use { input ->
                        Files.newOutputStream(dest).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var downloaded = 0L
                            while (true) {
                                if (options.cancelled?.invoke() == true) {
                                    runCatching { Files.deleteIfExists(dest) }
                                    return false
                                }
                                val n = input.read(buf)
                                if (n < 0) break
                                if (n > 0) {
                                    out.write(buf, 0, n)
                                    downloaded += n
                                    options.progress?.invoke(downloaded, total, assetName)
                                }
                            }
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                LOG.warn("runtime download failed: $url", e)
                lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
                runCatching { Files.deleteIfExists(dest) }
                return false
            }
        }

        override fun fetchText(url: String): String? {
            for (i in 1..MAX_ATTEMPTS) {
                val text = fetchTextOnce(url)
                if (text != null) return text
                if (i < MAX_ATTEMPTS) {
                    LOG.info("runtime checksum attempt $i failed for $url; retrying in ${BACKOFF_MS * i}ms")
                    Thread.sleep(BACKOFF_MS * i)
                }
            }
            return null
        }

        private fun fetchTextOnce(url: String): String? = try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(CHECKSUM_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                LOG.warn("runtime checksum http ${response.statusCode()} for $url")
                null
            } else {
                response.body().trim()
            }
        } catch (e: Exception) {
            LOG.warn("failed to fetch runtime checksum $url", e)
            null
        }
    }

    /**
     * 确保运行时就绪（[dest] = 运行时根）。
     * @return 可用=[ProvisionResult.Ready]；失败=[ProvisionResult.Failed]（含失败原因与尝试的资产 URL）。
     */
    fun provision(
        dest: Path,
        spec: RuntimeAssetSpec,
        version: String,
        fetcher: RuntimeFetcher = HttpFetcher,
        options: DownloadOptions = DownloadOptions(),
    ): ProvisionResult {
        if (isPresent(dest)) return ProvisionResult.Ready
        if (spec.baseUrl.isBlank()) {
            LOG.warn("runtime download base url is empty; cannot provision")
            return ProvisionResult.Failed(ProvisionReason.BASE_EMPTY, null)
        }
        val target = Platform.current()
        val assetUrl = spec.urlFor(target, version) ?: run {
            LOG.warn("no runtime asset for platform ${target.id} (assets=${spec.assets.keys})")
            return ProvisionResult.Failed(ProvisionReason.NO_ASSET, null)
        }
        val shaUrl = spec.shaUrlFor(target, version) ?: return ProvisionResult.Failed(ProvisionReason.NO_ASSET, assetUrl)
        val expected = fetcher.fetchText(shaUrl)?.takeIf { it.isNotBlank() } ?: run {
            LOG.warn("failed to fetch runtime checksum $shaUrl")
            return ProvisionResult.Failed(ProvisionReason.CHECKSUM_UNREACHABLE, assetUrl)
        }

        val tmpZip = dest.resolveSibling("runtime-download-${System.nanoTime()}.zip")
        try {
            if (!fetcher.download(assetUrl, tmpZip, options)) {
                val cancelled = options.cancelled?.invoke() == true
                val detail = (fetcher as? HttpFetcher)?.lastError
                val reason = if (cancelled) ProvisionReason.CANCELLED else ProvisionReason.DOWNLOAD_FAILED
                return ProvisionResult.Failed(reason, assetUrl, detail)
            }
            val actual = RuntimeArchive.sha256(tmpZip)
            if (!actual.equals(expected, ignoreCase = true)) {
                LOG.warn("runtime sha256 mismatch: expected $expected, got $actual ($assetUrl)")
                return ProvisionResult.Failed(ProvisionReason.SHA_MISMATCH, assetUrl)
            }
            Files.createDirectories(dest)
            RuntimeArchive.unzip(tmpZip, dest)
            return if (isPresent(dest)) ProvisionResult.Ready
            else ProvisionResult.Failed(ProvisionReason.INCOMPLETE, assetUrl)
        } catch (e: Exception) {
            LOG.warn("failed to provision runtime", e)
            return ProvisionResult.Failed(ProvisionReason.EXTRACT_FAILED, assetUrl)
        } finally {
            runCatching { Files.deleteIfExists(tmpZip) }
        }
    }

    /**
     * 从本地已下载的运行时 zip 直接导入（离线，无需网络）。
     * [expectedSha] 非空时先做 SHA-256 比对（通常来自同目录的 `.sha256` 侧车）。
     */
    fun provisionFromLocal(zip: Path, dest: Path, expectedSha: String? = null): ProvisionResult {
        if (!Files.isRegularFile(zip)) {
            return ProvisionResult.Failed(ProvisionReason.LOCAL_INVALID, zip.fileName?.toString())
        }
        if (!RuntimeArchive.validateRuntimeZip(zip)) {
            return ProvisionResult.Failed(ProvisionReason.LOCAL_INVALID, zip.fileName?.toString())
        }
        if (expectedSha != null && !RuntimeArchive.sha256(zip).equals(expectedSha.trim(), ignoreCase = true)) {
            LOG.warn("local runtime sha256 mismatch: expected $expectedSha")
            return ProvisionResult.Failed(ProvisionReason.SHA_MISMATCH, zip.fileName?.toString())
        }
        return try {
            Files.createDirectories(dest)
            RuntimeArchive.unzip(zip, dest)
            if (isPresent(dest)) ProvisionResult.Ready
            else ProvisionResult.Failed(ProvisionReason.INCOMPLETE, zip.fileName?.toString())
        } catch (e: Exception) {
            LOG.warn("failed to extract local runtime $zip", e)
            ProvisionResult.Failed(ProvisionReason.EXTRACT_FAILED, zip.fileName?.toString())
        }
    }

    /** 运行时根是否就绪（node + dsh 均已解压到位）。 */
    fun isPresent(dest: Path): Boolean =
        Files.isRegularFile(dest.resolve("node").resolve(Platform.current().nodeBinName)) &&
            Files.isRegularFile(dest.resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"))
}
