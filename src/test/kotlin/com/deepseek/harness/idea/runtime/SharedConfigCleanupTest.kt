package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * 共享根遗留清理**不得误删当前版本的真源目录**（v0.2.5 回归）。
 *
 * 事故：`cleanLegacySharedRoot` 把 `<共享根>/profiles` 当 v0.1.2 残留删除，而 v0.2.5 起它正是
 * 共享 dsh profile 的唯一真源。由于 `ensureHome` 每次启动/刷新都会调用它，表现为
 * "刷新卡住数十秒（递归删上万个 node_modules 文件）+ 已装插件凭空消失"。
 */
class SharedConfigCleanupTest {

    @TempDir
    lateinit var tmp: Path

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text, StandardCharsets.UTF_8)
        return path
    }

    @Test
    fun `shared profile root survives the legacy cleanup`() {
        val shared = Files.createDirectories(tmp.resolve("dsh-home"))
        val profile = write(shared.resolve("profiles/web/package.json"), """{"name":"dsh-profile-web"}""")
        val plugin = write(shared.resolve("profiles/web/node_modules/dshmarket/package.json"), """{"name":"dshmarket"}""")
        val fallback = write(shared.resolve("profiles/node_modules/@deepseek-ai/dsh-base/package.json"), """{}""")
        // v0.1.2 时代的真正残留
        write(shared.resolve("node_modules/@modelcontextprotocol/sdk/package.json"), """{}""")
        write(shared.resolve("mcp-ide-server.mjs"), "// legacy\n")
        write(shared.resolve("ide.yml"), "[]\n")

        SharedConfigMigrator.cleanLegacySharedRoot(shared)

        assertTrue(Files.isRegularFile(profile), "the shared profile manifest must never be deleted")
        assertTrue(Files.isRegularFile(plugin), "installed plugin packages must never be deleted")
        assertTrue(Files.isRegularFile(fallback), "the shared module fallback must never be deleted")
        assertFalse(Files.exists(shared.resolve("node_modules")), "legacy top-level node_modules should be removed")
        assertFalse(Files.exists(shared.resolve("mcp-ide-server.mjs")), "legacy per-root MCP script should be removed")
        assertFalse(Files.exists(shared.resolve("ide.yml")), "legacy per-root ide.yml should be removed")
    }

    @Test
    fun `cleanup is idempotent and tolerant of a missing shared root`() {
        val shared = Files.createDirectories(tmp.resolve("empty"))
        SharedConfigMigrator.cleanLegacySharedRoot(shared)
        SharedConfigMigrator.cleanLegacySharedRoot(shared)
        assertTrue(Files.isDirectory(shared))
    }

    @Test
    fun `other shared-root state is left alone`() {
        val shared = Files.createDirectories(tmp.resolve("state"))
        // sessions/storages 是 v0.1.2 遗留数据（供迁移器读取），migrated/ 是备份，都不能删
        val session = write(shared.resolve("sessions/some-project/s1/session.jsonl"), "{}\n")
        val settings = write(shared.resolve("settings.yaml"), "locale:\n  preference: zh\n")
        val credentials = write(shared.resolve(".credentials.yaml"), "version: 1\n")
        val backup = write(shared.resolve("migrated/abc/settings.yaml"), "x: 1\n")

        SharedConfigMigrator.cleanLegacySharedRoot(shared)

        listOf(session, settings, credentials, backup).forEach {
            assertTrue(Files.isRegularFile(it), "must not delete $it")
        }
    }
}
