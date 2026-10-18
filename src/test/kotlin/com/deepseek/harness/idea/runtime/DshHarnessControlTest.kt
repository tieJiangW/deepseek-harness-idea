package com.deepseek.harness.idea.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 应用级「重启 Harness」注册表（v0.2.5）。
 *
 * 装/卸 dsh 插件后必须重启 Harness 才生效，而 `DshProcessManager` 是每面板一个实例、设置页拿不到；
 * 这个注册表让设置页/通知一键触发重启。这里锁住它的**纯内存行为**：
 * 注册/注销计数、重启只触发已注册项、单个回调失败不影响其它实例、注销后不再被触发。
 */
class DshHarnessControlTest {

    @Test
    fun `registering panels increases the running count and unregistering removes them`() {
        val control = DshHarnessControl()
        assertEquals(0, control.runningCount())

        control.registerPanel("a") { }
        control.registerPanel("b") { }
        assertEquals(2, control.runningCount())

        control.unregisterPanel("a")
        assertEquals(1, control.runningCount())

        control.unregisterPanel("b")
        assertEquals(0, control.runningCount())
        // 幂等：注销不存在的 key 不抛异常
        control.unregisterPanel("b")
    }

    @Test
    fun `restartAll triggers every registered restarter`() {
        val control = DshHarnessControl()
        val restarted = mutableListOf<String>()
        control.registerPanel("a") { restarted.add("a") }
        control.registerPanel("b") { restarted.add("b") }

        val count = control.restartAll()

        assertEquals(2, count)
        assertEquals(listOf("a", "b"), restarted.sorted())
    }

    @Test
    fun `restartAll returns zero when nothing is running`() {
        val control = DshHarnessControl()
        assertEquals(0, control.restartAll())
    }

    @Test
    fun `a failing restarter does not prevent the others`() {
        val control = DshHarnessControl()
        val restarted = mutableListOf<String>()
        control.registerPanel("boom") { throw IllegalStateException("cannot restart") }
        control.registerPanel("ok") { restarted.add("ok") }

        val count = control.restartAll()

        assertEquals(2, count)
        assertEquals(listOf("ok"), restarted)
    }

    @Test
    fun `re-registering the same project replaces the hook`() {
        val control = DshHarnessControl()
        val restarted = mutableListOf<String>()
        control.registerPanel("p") { restarted.add("old") }
        control.registerPanel("p") { restarted.add("new") }

        assertEquals(1, control.runningCount())
        control.restartAll()
        assertEquals(listOf("new"), restarted)
    }

    @Test
    fun `an unregistered panel is not restarted`() {
        val control = DshHarnessControl()
        var called = false
        control.registerPanel("p") { called = true }
        control.unregisterPanel("p")

        assertEquals(0, control.restartAll())
        assertTrue(!called, "an unregistered hook must not run")
    }

    @Test
    fun `dispose clears every hook`() {
        val control = DshHarnessControl()
        control.registerPanel("a") { }
        control.dispose()
        assertEquals(0, control.runningCount())
        assertEquals(0, control.restartAll())
    }
}
