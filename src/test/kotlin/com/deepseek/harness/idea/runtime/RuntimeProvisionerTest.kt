package com.deepseek.harness.idea.runtime

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RuntimeProvisionerTest {

    @TempDir
    lateinit var tmp: Path

    private val target: Platform.Target = Platform.current()
    private val nodeBin = target.nodeBinName
    private val dshBinRel = "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js"

    @Test
    fun `provision skips fetch when runtime already present`() {
        val dest = tmp.resolve("rt")
        seedRuntime(dest)
        var downloaded = false
        val fetcher = fakeFetcher { downloaded = true }
        assertTrue(
            RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher) is RuntimeProvisioner.ProvisionResult.Ready
        )
        assertFalse(downloaded)
    }

    @Test
    fun `provision downloads verifies and extracts when present`() {
        val dest = tmp.resolve("rt2")
        val zip = buildRuntimeZip()
        val sha = RuntimeArchive.sha256(zip)
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean {
                Files.copy(zip, dest)
                return true
            }

            override fun fetchText(url: String): String? = sha
        }
        val result = RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Ready, "expected Ready, got $result")
        assertTrue(Files.isRegularFile(dest.resolve("node").resolve(nodeBin)))
        assertTrue(Files.isRegularFile(dest.resolve(dshBinRel)))
    }

    @Test
    fun `provision rejects on sha mismatch`() {
        val dest = tmp.resolve("rt3")
        val zip = buildRuntimeZip()
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean {
                Files.copy(zip, dest)
                return true
            }

            override fun fetchText(url: String): String? = "DEADBEEF" // 匹配不上
        }
        val result = RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.SHA_MISMATCH)
        assertFalse(Files.isRegularFile(dest.resolve(dshBinRel)))
    }

    @Test
    fun `provision fails when checksum sidecar unavailable`() {
        val dest = tmp.resolve("rt4")
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean = true

            override fun fetchText(url: String): String? = null
        }
        val result = RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.CHECKSUM_UNREACHABLE)
    }

    @Test
    fun `provision fails when download fails`() {
        val dest = tmp.resolve("rt5")
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean = false

            override fun fetchText(url: String): String? = "123"
        }
        val result = RuntimeProvisioner.provision(dest, spec(), "0.2.0", fetcher)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.DOWNLOAD_FAILED)
    }

    @Test
    fun `provision fails when platform has no asset`() {
        val dest = tmp.resolve("rt6")
        val noAssetSpec = RuntimeAssetSpec("https://base.example", emptyMap())
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean = true

            override fun fetchText(url: String): String? = "123"
        }
        val result = RuntimeProvisioner.provision(dest, noAssetSpec, "0.2.0", fetcher)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.NO_ASSET)
    }

    @Test
    fun `provision fails when baseUrl empty`() {
        val dest = tmp.resolve("rt7")
        val result = RuntimeProvisioner.provision(dest, RuntimeAssetSpec("", emptyMap()), "0.2.0", fakeFetcher {})
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.BASE_EMPTY)
    }

    @Test
    fun `provision reports progress from fetcher options`() {
        val dest = tmp.resolve("rt8")
        val zip = buildRuntimeZip()
        val sha = RuntimeArchive.sha256(zip)
        val progresses = mutableListOf<Triple<Long, Long, String>>()
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean {
                options.progress?.invoke(5, 10, "asset.zip")
                options.progress?.invoke(10, 10, "asset.zip")
                Files.copy(zip, dest)
                return true
            }

            override fun fetchText(url: String): String? = sha
        }
        RuntimeProvisioner.provision(
            dest, spec(), "0.2.0", fetcher,
            RuntimeProvisioner.DownloadOptions(progress = { d, t, n -> progresses.add(Triple(d, t, n)) }),
        )
        assertTrue(progresses.size == 2)
        assertTrue(progresses[0].first == 5L && progresses[0].second == 10L && progresses[0].third == "asset.zip")
    }

    @Test
    fun `provision cancels when cancelled returns true`() {
        val dest = tmp.resolve("rt9")
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean = false

            override fun fetchText(url: String): String? = "123"
        }
        val result = RuntimeProvisioner.provision(
            dest, spec(), "0.2.0", fetcher,
            RuntimeProvisioner.DownloadOptions(cancelled = { true }),
        )
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.CANCELLED)
    }

    @Test
    fun `provision passes custom timeouts to fetcher options`() {
        val dest = tmp.resolve("rt10")
        val zip = buildRuntimeZip()
        val sha = RuntimeArchive.sha256(zip)
        var seenConnect = 0
        var seenRead = 0
        val fetcher = object : RuntimeProvisioner.RuntimeFetcher {
            override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean {
                seenConnect = options.connectTimeoutMs
                seenRead = options.readTimeoutMs
                Files.copy(zip, dest)
                return true
            }

            override fun fetchText(url: String): String? = sha
        }
        RuntimeProvisioner.provision(
            dest, spec(), "0.2.0", fetcher,
            RuntimeProvisioner.DownloadOptions(connectTimeoutMs = 1111, readTimeoutMs = 2222),
        )
        assertTrue(seenConnect == 1111)
        assertTrue(seenRead == 2222)
    }

    @Test
    fun `provisionFromLocal succeeds on a valid runtime zip`() {
        val dest = tmp.resolve("rt-local")
        val zip = buildRuntimeZip()
        val result = RuntimeProvisioner.provisionFromLocal(zip, dest)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Ready, "expected Ready, got $result")
        assertTrue(Files.isRegularFile(dest.resolve("node").resolve(nodeBin)))
        assertTrue(Files.isRegularFile(dest.resolve(dshBinRel)))
    }

    @Test
    fun `provisionFromLocal rejects a non-runtime zip`() {
        val dest = tmp.resolve("rt-local3")
        val zip = tmp.resolve("not-runtime.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("foo.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }
        val result = RuntimeProvisioner.provisionFromLocal(zip, dest)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.LOCAL_INVALID)
    }

    @Test
    fun `provisionFromLocal verifies sha when expected provided`() {
        val dest = tmp.resolve("rt-local4")
        val zip = buildRuntimeZip()
        val sha = RuntimeArchive.sha256(zip)
        val result = RuntimeProvisioner.provisionFromLocal(zip, dest, sha)
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Ready, "expected Ready, got $result")
    }

    @Test
    fun `provisionFromLocal rejects on sha mismatch when expected provided`() {
        val dest = tmp.resolve("rt-local5")
        val zip = buildRuntimeZip()
        val result = RuntimeProvisioner.provisionFromLocal(zip, dest, "DEADBEEF")
        assertTrue(result is RuntimeProvisioner.ProvisionResult.Failed)
        assertTrue((result as RuntimeProvisioner.ProvisionResult.Failed).reason == RuntimeProvisioner.ProvisionReason.SHA_MISMATCH)
        assertFalse(Files.isRegularFile(dest.resolve(dshBinRel)))
    }

    @Test
    fun `httpFetcher downloads body and reports progress from a local server`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val body = ByteArray(2048) { it.toByte() }
        server.createContext("/runtime.zip") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/runtime.zip"
            val dest = tmp.resolve("dl.zip")
            val progresses = mutableListOf<Long>()
            val ok = RuntimeProvisioner.HttpFetcher.download(
                url, dest,
                RuntimeProvisioner.DownloadOptions(progress = { d, _, _ -> progresses.add(d) }),
            )
            assertTrue(ok)
            assertTrue(Files.size(dest) == body.size.toLong())
            assertTrue(progresses.last() == body.size.toLong())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `httpFetcher returns false on non-2xx response`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/missing") { exchange -> exchange.sendResponseHeaders(404, -1) }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/missing"
            val dest = tmp.resolve("dl404.zip")
            val ok = RuntimeProvisioner.HttpFetcher.download(url, dest, RuntimeProvisioner.DownloadOptions())
            assertFalse(ok)
            assertFalse(Files.exists(dest))
        } finally {
            server.stop(0)
        }
    }

    // ---- helpers ----

    private fun spec(): RuntimeAssetSpec =
        RuntimeAssetSpec("https://base.example", mapOf(target.id to "runtime-${target.id}.zip"))

    private fun seedRuntime(dest: Path) {
        Files.createDirectories(dest.resolve("node"))
        Files.write(dest.resolve("node").resolve(nodeBin), "node".toByteArray())
        Files.createDirectories(dest.resolve(dshBinRel).parent)
        Files.write(dest.resolve(dshBinRel), "dsh".toByteArray())
    }

    private fun buildRuntimeZip(): Path {
        val zip = tmp.resolve("runtime-${target.id}.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
            addEntry(zos, "node/$nodeBin", "node-bin")
            addEntry(zos, dshBinRel, "dsh-bin")
        }
        return zip
    }

    private fun addEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray())
        zos.closeEntry()
    }

    private fun fakeFetcher(onDownload: () -> Unit) = object : RuntimeProvisioner.RuntimeFetcher {
        override fun download(url: String, dest: Path, options: RuntimeProvisioner.DownloadOptions): Boolean {
            onDownload()
            return true
        }

        override fun fetchText(url: String): String? = "x"
    }
}
