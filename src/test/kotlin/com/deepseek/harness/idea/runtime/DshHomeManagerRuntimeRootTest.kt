package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * 运行时根解析优先级（`DshHomeManager.resolveRuntimeRoot`，纯逻辑）：
 * 环境变量 `DSH_IDEA_RUNTIME` > 设置页「运行时目录」> 默认下载/缓存目录；
 * 无效（不存在）或空白值自动跳过到下一级。
 */
class DshHomeManagerRuntimeRootTest {

    @TempDir
    lateinit var tmp: Path

    private fun dir(name: String): Path = Files.createDirectories(tmp.resolve(name))

    @Test
    fun `env wins over configured and fallback`() {
        val env = dir("env")
        val configured = dir("configured")
        val fallback = dir("fallback")
        assertEquals(env, DshHomeManager.resolveRuntimeRoot(env.toString(), configured.toString(), fallback))
    }

    @Test
    fun `configured used when env blank`() {
        val configured = dir("configured")
        val fallback = dir("fallback")
        assertEquals(configured, DshHomeManager.resolveRuntimeRoot(null, configured.toString(), fallback))
        assertEquals(configured, DshHomeManager.resolveRuntimeRoot("   ", configured.toString(), fallback))
    }

    @Test
    fun `fallback when both blank`() {
        val fallback = dir("fallback")
        assertEquals(fallback, DshHomeManager.resolveRuntimeRoot(null, null, fallback))
        assertEquals(fallback, DshHomeManager.resolveRuntimeRoot("", "", fallback))
    }

    @Test
    fun `nonexistent paths are skipped`() {
        val configured = dir("configured")
        val fallback = dir("fallback")
        val missing = tmp.resolve("nope").toString()
        assertEquals(fallback, DshHomeManager.resolveRuntimeRoot(missing, missing, fallback))
        assertEquals(configured, DshHomeManager.resolveRuntimeRoot(missing, configured.toString(), fallback))
    }

    @Test
    fun `samePath ignores separators case and trailing dots`() {
        assertTrue(DshHomeManager.samePath("C:/x/y", "c:\\x\\y\\."))
        assertTrue(DshHomeManager.samePath("C:/x/y/", "C:/x/y"))
        assertFalse(DshHomeManager.samePath("C:/x/y", "C:/x/z"))
        assertTrue(DshHomeManager.samePath("", ""))
        assertFalse(DshHomeManager.samePath(null, "C:/x"))
    }
}
