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

    // ---- v0.2.4：共享配置根解析（纯逻辑） ——
    // 与运行时目录不同，共享根**不要求预先存在**（首次使用会创建），但必须是绝对路径。

    @Test
    fun `shared root falls back when unset`() {
        val fallback = tmp.resolve("dsh-home")
        assertEquals(fallback, DshHomeManager.resolveSharedConfigRoot(null, fallback))
        assertEquals(fallback, DshHomeManager.resolveSharedConfigRoot("", fallback))
        assertEquals(fallback, DshHomeManager.resolveSharedConfigRoot("   ", fallback))
    }

    @Test
    fun `shared root accepts an absolute path that does not exist yet`() {
        val fallback = tmp.resolve("dsh-home")
        val custom = tmp.resolve("custom-shared")
        assertFalse(Files.exists(custom), "precondition: the override directory must not exist")
        assertEquals(custom, DshHomeManager.resolveSharedConfigRoot(custom.toString(), fallback))
    }

    @Test
    fun `shared root falls back on a relative or malformed path`() {
        val fallback = tmp.resolve("dsh-home")
        assertEquals(fallback, DshHomeManager.resolveSharedConfigRoot("relative/dir", fallback))
        assertEquals(fallback, DshHomeManager.resolveSharedConfigRoot("\u0000bad", fallback))
    }

    @Test
    fun `shared root normalizes the override path`() {
        val fallback = tmp.resolve("dsh-home")
        val custom = tmp.resolve("custom-shared")
        assertEquals(custom, DshHomeManager.resolveSharedConfigRoot("$custom/sub/..".replace('/', java.io.File.separatorChar), fallback))
    }

    @Test
    fun `project dir name pattern only matches 16 hex chars`() {
        assertTrue(DshHomeManager.PROJECT_DIR_NAME.matches("0123456789abcdef"))
        assertFalse(DshHomeManager.PROJECT_DIR_NAME.matches("0123456789ABCDEF"), "md5 hex is lowercase")
        assertFalse(DshHomeManager.PROJECT_DIR_NAME.matches("0123456789abcde"), "15 chars is not a project home")
        assertFalse(DshHomeManager.PROJECT_DIR_NAME.matches("migrated"))
        assertFalse(DshHomeManager.PROJECT_DIR_NAME.matches("0123456789abcdef0"), "17 chars is not a project home")
    }
}
