package com.deepseek.harness.idea.plugins

import com.deepseek.harness.idea.runtime.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安装器的**过程反馈**（v0.2.5 用户反馈驱动）：实时流式输出、静默期心跳、取消、失败回滚。
 *
 * 用真实 node 跑一个假的 `dsh bin.js`（不需要联网、不碰真实 profile）：把 `nodeExe` 指向运行时里的
 * node、`dshBin` 指向临时脚本，并**预置私有 pnpm 的两个文件**让 [DshPluginInstaller] 跳过 npm 下载。
 * 需要 `DSH_IDEA_RUNTIME`，否则跳过（与其它冒烟测试一致）。
 */
class DshPluginInstallerStreamingTest {

    @TempDir
    lateinit var tmp: Path

    private val runtimeRoot: Path? = System.getenv("DSH_IDEA_RUNTIME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    private fun nodeExe(): Path =
        runtimeRoot!!.resolve("node").resolve(if (Platform.current().os == Platform.Os.WINDOWS) "node.exe" else "node")

    @BeforeEach
    fun requireRuntime() {
        assumeTrue(
            runtimeRoot != null && Files.isRegularFile(nodeExe()),
            "DSH_IDEA_RUNTIME is not set to a runtime with node/ — skipping",
        )
    }

    private fun sharedRoot(): Path = Files.createDirectories(tmp.resolve("shared"))

    /** 预置私有 pnpm（空文件即可）：`ensurePnpm` 的存在性检查通过 → 不会联网 `npm install pnpm`。 */
    private fun presetPnpm(shared: Path) {
        val os = Platform.current().os
        val cli = DshPluginInstaller.pnpmCli(DshPluginInstaller.toolsDir(shared))
        Files.createDirectories(cli.parent)
        Files.writeString(cli, "// stub\n", StandardCharsets.UTF_8)
        val shim = DshPluginInstaller.shimDir(shared).resolve(DshPluginInstaller.shimName(os))
        Files.createDirectories(shim.parent)
        Files.writeString(shim, "stub\n", StandardCharsets.UTF_8)
    }

    /** 一个"项目 DSH_HOME"，profile manifest 内容已知（用于验证失败回滚）。 */
    private fun projectHome(manifest: String): Path {
        val home = Files.createDirectories(tmp.resolve("home"))
        val web = Files.createDirectories(home.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME))
        Files.writeString(web.resolve(DshProfileManifest.FILE_NAME), manifest, StandardCharsets.UTF_8)
        return home
    }

    private fun fakeDsh(body: String): Path {
        val script = tmp.resolve("fake-dsh-${System.nanoTime()}.mjs")
        Files.writeString(script, body, StandardCharsets.UTF_8)
        return script
    }

    private val manifest =
        """{"name":"dsh-profile-web","dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base"]}}}"""

    @Test
    fun `streams output while the command runs and reports success`() {
        val shared = sharedRoot()
        presetPnpm(shared)
        val home = projectHome(manifest)
        val script = fakeDsh(
            """
            for (let i = 0; i < 5; i++) {
              console.log('line-' + i);
              await new Promise((r) => setTimeout(r, 150));
            }
            """.trimIndent(),
        )

        val seen = CopyOnWriteArrayList<Pair<String, Long>>()
        val phases = CopyOnWriteArrayList<DshPluginInstaller.Phase>()
        val startedAt = System.currentTimeMillis()
        val outcome = DshPluginInstaller.run(
            projectHome = home,
            sharedRoot = shared,
            nodeExe = nodeExe(),
            dshBin = script,
            action = "add",
            spec = "pkg",
            onPhase = { phases.add(it) },
            onLine = { seen.add(it to (System.currentTimeMillis() - startedAt)) },
        )
        val finishedAt = System.currentTimeMillis() - startedAt

        assertTrue(outcome.ok, "expected success, output:\n${outcome.output}")
        assertEquals(listOf(DshPluginInstaller.Phase.CHECKING, DshPluginInstaller.Phase.RUNNING), phases.toList())
        val lines = seen.map { it.first }
        assertEquals(listOf("line-0", "line-1", "line-2", "line-3", "line-4"), lines)

        // 流式：第一行必须**远早于**命令结束才到达（否则就是"憋到最后一次性吐出来"）。
        // 不断言绝对耗时 —— node 冷启动本身就要几百毫秒。
        val firstLineAt = seen.first().second
        assertTrue(
            finishedAt - firstLineAt >= 400,
            "output only appeared at the end (first=$firstLineAt ms, end=$finishedAt ms) — not streaming",
        )
        assertTrue(outcome.elapsedMs >= 600, "elapsed=${outcome.elapsedMs}")
    }

    @Test
    fun `emits heartbeats while the child is silent`() {
        val shared = sharedRoot()
        presetPnpm(shared)
        val home = projectHome(manifest)
        // 3.5s 静默：远超 2s 心跳间隔，且不靠"有输出"驱动
        val script = fakeDsh("await new Promise((r) => setTimeout(r, 3500));")

        val lines = CopyOnWriteArrayList<String>()
        val outcome = DshPluginInstaller.run(
            projectHome = home,
            sharedRoot = shared,
            nodeExe = nodeExe(),
            dshBin = script,
            action = "add",
            spec = "pkg",
            onLine = { lines.add(it) },
        )

        assertTrue(outcome.ok, outcome.output)
        val beats = lines.count { it == DshPluginInstaller.HEARTBEAT }
        assertTrue(beats >= 1, "expected at least one heartbeat, got none (lines=$lines)")
        // 心跳只是 UI 信号，绝不能混进命令输出
        assertFalse(outcome.output.contains(DshPluginInstaller.HEARTBEAT), "heartbeat leaked into the command output")
    }

    @Test
    fun `cancelling kills the child quickly and rolls the manifest back`() {
        val shared = sharedRoot()
        presetPnpm(shared)
        val home = projectHome(manifest)
        // 子进程先篡改 manifest，再长时间挂起：取消后必须恢复原样
        val web = home.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
        val script = fakeDsh(
            """
            import { writeFileSync } from 'node:fs';
            writeFileSync(${quote(web.resolve(DshProfileManifest.FILE_NAME).toString())}, '{"dependencies":{"pkg":"1.0.0"}}');
            console.log('about to hang');
            await new Promise((r) => setTimeout(r, 60000));
            """.trimIndent(),
        )

        val cancel = AtomicBoolean(false)
        val worker = Thread {
            Thread.sleep(2500)
            cancel.set(true)
        }.apply { isDaemon = true; start() }

        val startedAt = System.currentTimeMillis()
        val outcome = DshPluginInstaller.run(
            projectHome = home,
            sharedRoot = shared,
            nodeExe = nodeExe(),
            dshBin = script,
            action = "add",
            spec = "pkg",
            cancelled = { cancel.get() },
        )
        val elapsed = System.currentTimeMillis() - startedAt
        worker.join(5000)

        assertFalse(outcome.ok)
        assertEquals(DshPluginInstaller.Failure.CANCELLED, outcome.failure, "output:\n${outcome.output}")
        assertTrue(elapsed < 15_000, "cancel took ${elapsed}ms")
        assertEquals(
            manifest,
            Files.readString(web.resolve(DshProfileManifest.FILE_NAME), StandardCharsets.UTF_8),
            "the profile manifest must be restored after a cancelled install",
        )
    }

    @Test
    fun `a failing command restores the manifest and reports the exit code`() {
        val shared = sharedRoot()
        presetPnpm(shared)
        val home = projectHome(manifest)
        val web = home.resolve("profiles").resolve(DshProfileManifest.PROFILE_NAME)
        val script = fakeDsh(
            """
            import { writeFileSync } from 'node:fs';
            writeFileSync(${quote(web.resolve(DshProfileManifest.FILE_NAME).toString())}, '{"dependencies":{"pkg":"1.0.0"}}');
            console.error('ERR_PNPM_FETCH_404  pkg is not in the registry');
            process.exit(3);
            """.trimIndent(),
        )

        val outcome = DshPluginInstaller.run(
            projectHome = home,
            sharedRoot = shared,
            nodeExe = nodeExe(),
            dshBin = script,
            action = "add",
            spec = "pkg",
        )

        assertFalse(outcome.ok)
        assertEquals(DshPluginInstaller.Failure.COMMAND_FAILED, outcome.failure)
        assertEquals("exit=3", outcome.detail)
        assertTrue(outcome.output.contains("ERR_PNPM_FETCH_404"), "output:\n${outcome.output}")
        assertEquals(
            manifest,
            Files.readString(web.resolve(DshProfileManifest.FILE_NAME), StandardCharsets.UTF_8),
            "the profile manifest must be restored after a failed install",
        )
    }

    @Test
    fun `missing runtime fails fast without running anything`() {
        val outcome = DshPluginInstaller.run(
            projectHome = projectHome(manifest),
            sharedRoot = sharedRoot(),
            nodeExe = tmp.resolve("no-such-node"),
            dshBin = tmp.resolve("no-such-dsh.js"),
            action = "add",
            spec = "pkg",
        )
        assertFalse(outcome.ok)
        assertEquals(DshPluginInstaller.Failure.NO_RUNTIME, outcome.failure)
        assertEquals("", outcome.output)
    }

    /** JS 字符串字面量（Windows 路径含反斜杠，必须转义）。 */
    private fun quote(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
