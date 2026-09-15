package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RuntimeAssetsTest {

    @Test
    fun `parse loads baseUrl and asset map`() {
        val spec = RuntimeAssets.parse(
            """{"baseUrl":"https://example.com/releases/download/v{version}","assets":{"win-x64":"runtime-win-x64.zip","linux-x64":"runtime-linux-x64.zip"}}"""
        )
        assertEquals("https://example.com/releases/download/v{version}", spec.baseUrl)
        assertEquals("runtime-win-x64.zip", spec.assets["win-x64"])
        assertEquals("runtime-linux-x64.zip", spec.assets["linux-x64"])
    }

    @Test
    fun `urlFor substitutes version and joins asset`() {
        val spec = RuntimeAssets.parse(
            """{"baseUrl":"https://github.com/o/r/releases/download/v{version}","assets":{"win-x64":"runtime-win-x64.zip"}}"""
        )
        val target = Platform.Target(Platform.Os.WINDOWS, Platform.Arch.X64)
        assertEquals("https://github.com/o/r/releases/download/v0.2.0/runtime-win-x64.zip", spec.urlFor(target, "0.2.0"))
    }

    @Test
    fun `shaUrlFor appends sha256 sidecar`() {
        val spec = RuntimeAssets.parse(
            """{"baseUrl":"https://host/base/","assets":{"linux-x64":"rt-linux-x64.zip"}}"""
        )
        val target = Platform.Target(Platform.Os.LINUX, Platform.Arch.X64)
        assertEquals("https://host/base/rt-linux-x64.zip.sha256", spec.shaUrlFor(target, "0.2.0"))
    }

    @Test
    fun `urlFor uses direct file url as-is`() {
        val spec = RuntimeAssets.parse(
            """{"baseUrl":"https://github.com/o/r/releases/download/v0.2.2/runtime-win-x64.zip","assets":{"win-x64":"runtime-win-x64.zip"}}"""
        )
        val target = Platform.Target(Platform.Os.WINDOWS, Platform.Arch.X64)
        // 覆盖值已是"到文件的完整 URL" → 原样使用，不再拼接平台资产名
        assertEquals(
            "https://github.com/o/r/releases/download/v0.2.2/runtime-win-x64.zip",
            spec.urlFor(target, "0.9.9")
        )
        assertEquals(
            "https://github.com/o/r/releases/download/v0.2.2/runtime-win-x64.zip.sha256",
            spec.shaUrlFor(target, "0.9.9")
        )
    }

    @Test
    fun `urlFor direct file url works even when platform has no asset entry`() {
        val spec = RuntimeAssets.parse("""{"baseUrl":"file:///D:/rt/runtime-macos-x64.zip","assets":{}}""")
        val target = Platform.Target(Platform.Os.WINDOWS, Platform.Arch.X64)
        assertEquals("file:///D:/rt/runtime-macos-x64.zip", spec.urlFor(target, "0.2.2"))
    }

    @Test
    fun `assetName null when platform unsupported`() {
        val spec = RuntimeAssets.parse("""{"baseUrl":"x","assets":{"win-x64":"a.zip"}}""")
        val target = Platform.Target(Platform.Os.MACOS, Platform.Arch.ARM64)
        assertNull(spec.assetName(target))
        assertNull(spec.urlFor(target, "0.2.0"))
    }

    @Test
    fun `load override replaces baseUrl but keeps assets`() {
        val spec = RuntimeAssets.load("https://my-mirror.example/x")
        assertEquals("https://my-mirror.example/x", spec.baseUrl)
        // 默认资产图应含当前平台（win-x64 在本机）
        assertEquals("runtime-win-x64.zip", spec.assets["win-x64"])
    }

    @Test
    fun `parse tolerant of unknown shape`() {
        val spec = RuntimeAssets.parse("""not-json""")
        assertEquals("", spec.baseUrl)
        assertEquals(emptyMap<String, String>(), spec.assets)
    }
}
