package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * [DshCredentialsSync] 的残留语义测试（v0.2.4 起该类已废弃：配置面共享化后不再需要跨目录同步）。
 * 保留这些用例是为了保证"即使被显式调用，也不会写坏共享凭据文件"。
 */
@Suppress("DEPRECATION")
class DshCredentialsSyncTest {

    @TempDir
    lateinit var tmp: Path

    // ---- resolveSync：项目 key 与全局 key 比对，决定是否回写 ----

    @Test
    fun `resolveSync returns project key when differs from global`() {
        assertEquals("sk-new-1234567890", DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("sk-new-1234567890", "sk-old-0000000000"))
    }

    @Test
    fun `resolveSync returns null when project and global agree`() {
        assertNull(DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("sk-same-1234567890", "sk-same-1234567890"))
    }

    @Test
    fun `resolveSync returns null when project key empty`() {
        assertNull(DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("", "sk-old-0000000000"))
        assertNull(DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync(null, "sk-old-0000000000"))
    }

    @Test
    fun `resolveSync returns project key when global absent`() {
        assertEquals("sk-first-1234567890", DshCredentialsSync(tmp.resolve(".credentials.yaml")).resolveSync("sk-first-1234567890", null))
    }

    // ---- v0.2.4：类已废弃，register 必须是空操作（不再启动任何 watch）----

    @Test
    fun `register is a no-op in shared-config mode`() {
        val file = tmp.resolve(".credentials.yaml")
        Files.writeString(file, "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-a\n", StandardCharsets.UTF_8)
        assertNull(DshCredentialsSync.register("proj", file), "register must not create a watcher anymore")
        DshCredentialsSync.release("proj") // 幂等，不抛
    }

    @Test
    fun `synced file is no longer used to write shared credentials`() {
        // onFileChanged 只读 key（这里文件不存在 → 直接返回，绝不能抛或创建共享文件）
        val missing = tmp.resolve("nope/.credentials.yaml")
        DshCredentialsSync(missing).onFileChanged()
        assertFalse(Files.exists(missing.parent))
    }

    // ---- start() 注册 watch（纯生命周期，不触发 Platform 心跳）----

    @Test
    fun `start registers watch service and closeProject is idempotent`() {
        val dir = tmp.resolve("iso")
        Files.createDirectories(dir)
        val file = dir.resolve(".credentials.yaml")
        Files.writeString(file, "version: 1\nrefs:\n  DEEPSEEK_API_KEY: sk-a\n", StandardCharsets.UTF_8)

        val sync = DshCredentialsSync(file)
        sync.start()
        // 重复 start 幂等（不抛）
        sync.start()
        // 关闭（幂等）
        sync.closeProject()
        sync.closeProject()
    }
}
