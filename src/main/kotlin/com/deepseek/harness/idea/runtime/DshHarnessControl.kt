package com.deepseek.harness.idea.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用级的「重启 Harness」入口（v0.2.5）。
 *
 * **为什么需要它**：dsh 的 profile（含 `dsh.profile.bundles`）只在**启动时**解析，所以安装/卸载 dsh 插件后
 * 必须重启 Harness 才生效。而 [DshProcessManager] 是**每个工具窗一个实例**，设置页拿不到它 ——
 * 于是用户只能自己去点工具窗标题栏的「重启」，很容易漏掉（装完看着列表没变化，以为没装上）。
 *
 * 这里让工具窗面板在存活期间把"重启自己"注册进来，设置页（或通知里的 action）就能**一键重启全部**。
 * 只保存回调，不持有面板引用；面板 `dispose` 时注销。
 */
@Service(Service.Level.APP)
class DshHarnessControl : Disposable {

    private val restarters = ConcurrentHashMap<String, () -> Unit>()

    /** 工具窗面板启动时登记（key 用项目名，与 [DshRuntimeRegistry] 一致）。 */
    fun registerPanel(projectName: String, restart: () -> Unit) {
        restarters[projectName] = restart
        LOG.info("harness control: registered a restart hook for '$projectName' (total=${restarters.size})")
    }

    /** 面板销毁时注销。 */
    fun unregisterPanel(projectName: String) {
        if (restarters.remove(projectName) != null) {
            LOG.info("harness control: unregistered the restart hook for '$projectName' (total=${restarters.size})")
        }
    }

    /** 当前可重启的 Harness 实例数（0 = 未运行，此时装插件会在下次启动时生效）。 */
    fun runningCount(): Int = restarters.size

    /**
     * 重启所有已登记的 Harness 实例（异步：真正的重启在各自面板的池化线程里做）。
     *
     * @return 触发的实例数
     */
    fun restartAll(): Int {
        val targets = restarters.values.toList()
        if (targets.isEmpty()) {
            LOG.info("harness control: restart requested but no harness is running")
            return 0
        }
        LOG.info("harness control: restarting ${targets.size} harness instance(s)")
        targets.forEach { restart ->
            // 单个失败不能影响其它实例
            runCatching { restart() }.onFailure { LOG.warn("harness control: restart hook failed", it) }
        }
        return targets.size
    }

    override fun dispose() {
        restarters.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(DshHarnessControl::class.java)

        fun getInstance(): DshHarnessControl =
            ApplicationManager.getApplication().getService(DshHarnessControl::class.java)
    }
}
