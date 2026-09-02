package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.bridge.IdeBridgeResources
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * DSH 运行时与 DSH_HOME 管理（应用级服务）。
 *
 * 目录布局（见 docs/DESIGN.md §4.4）：
 * - 运行时根：环境变量 `DSH_IDEA_RUNTIME`（开发态覆盖）或
 *   `<config>/dsh-idea/runtime/<version>`（生产态，Step 5 从插件资源解压）。
 *   内含 `node/`（Node.js）与 `dsh/`（npm 安装的 @deepseek-ai/dsh 树）。
 * - DSH_HOME：`<config>/dsh-idea/dsh-home`，其中 profiles/web/package.json 声明 bundle；
 *   dsh 首次启动时自愈创建 profiles/node_modules 的 junction 指向 dsh 树。
 */
@Service(Service.Level.APP)
class DshHomeManager : Disposable {

    companion object {
        private val LOG = Logger.getInstance(DshHomeManager::class.java)

        /** 固定 dsh 版本（升级 = 换版本 + 重建运行时，见 DESIGN §3.2） */
        const val DSH_VERSION = "0.1.1-rc.2"

        /** 构建期注入的版本信息资源（generateBuildInfo 产出，供运行期读取插件版本，避免用内部 API）。 */
        const val BUILD_INFO_RESOURCE = "/dsh-build-info.properties"

        /** 开发态覆盖：DSH_IDEA_RUNTIME=<目录> 直接使用该目录下的 node/ 与 dsh/ */
        const val RUNTIME_OVERRIDE_ENV = "DSH_IDEA_RUNTIME"

        /** 插件资源中的运行时压缩包（build-runtime.ps1 -Bundle 产物，Step 5 打入 resources） */
        const val RUNTIME_BUNDLE_RESOURCE = "/runtime-bundle.zip"

        const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

        /** 与启动 dsh web --patch 使用的 ide.yml 文件名 */
        const val IDE_PATCH_FILE = "ide.yml"

        private val WEB_PROFILE_MANIFEST =
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}"""

        fun getInstance(): DshHomeManager =
            ApplicationManager.getApplication().getService(DshHomeManager::class.java)

        /** dsh 内测声明 acknowledge 版本（与 dsh 源码 WELCOME_NOTICE_VERSION 一致；变化需同步）。 */
        const val WELCOME_NOTICE_VERSION = "2026-08-13.1"
    }

    /** 运行时根目录（node/ + dsh/ 的父目录）。 */
    fun runtimeRoot(): Path {
        System.getenv(RUNTIME_OVERRIDE_ENV)?.takeIf { Files.isDirectory(Path.of(it)) }?.let { return Path.of(it) }
        return PathManager.getConfigDir().resolve("dsh-idea").resolve("runtime").resolve(DSH_VERSION)
    }

    /** node 可执行文件（Windows=`node/node.exe`；Unix=`node/node`，构建期已归一化布局）。 */
    fun nodeExe(): Path = runtimeRoot().resolve("node").resolve(Platform.current().nodeBinName)

    fun dshBin(): Path = runtimeRoot().resolve("dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")

    /**
     * 运行时可用性检查（Step 5 FR-02.1；扩展：瘦身通用插件按平台下载）：
     * - `DSH_IDEA_RUNTIME` 覆盖存在 → 用之；
     * - 否则若配置目录缺运行时：先尝试从插件资源 `runtime-bundle.zip` 解压（fat zip / 旧版），
     *   无资源时按当前平台从资产地图下载运行时（瘦身版）。
     */
    fun hasRuntime(): Boolean {
        if (RuntimeProvisioner.isPresent(runtimeRoot())) return true
        if (System.getenv(RUNTIME_OVERRIDE_ENV) != null) return false // 覆盖显式指向但缺失 → 报错
        return provisionBundledOrDownload()
    }

    /** 供供给：内置资源解压 → 无资源时按平台下载。任一步成功即视为运行时就绪。 */
    private fun provisionBundledOrDownload(): Boolean =
        if (extractBundledRuntime()) true
        else downloadRuntimeInternal().let { it is RuntimeProvisioner.ProvisionResult.Ready }

    /**
     * 异步友好：按当前平台从资产地图下载运行时，支持进度/超时/取消（供工具窗口后台任务调用）。
     * 已就绪短路返回 [RuntimeProvisioner.ProvisionResult.Ready]；DSH_IDEA_RUNTIME 显式指向但缺失 → 失败。
     */
    fun ensureRuntimeProvisioned(options: RuntimeProvisioner.DownloadOptions): RuntimeProvisioner.ProvisionResult {
        if (RuntimeProvisioner.isPresent(runtimeRoot())) return RuntimeProvisioner.ProvisionResult.Ready
        if (System.getenv(RUNTIME_OVERRIDE_ENV) != null) {
            LOG.warn("$RUNTIME_OVERRIDE_ENV is set but runtime is missing at ${runtimeRoot()}")
            return RuntimeProvisioner.ProvisionResult.Failed(RuntimeProvisioner.ProvisionReason.INCOMPLETE, runtimeRoot().toString())
        }
        return downloadRuntimeInternal(options)
    }

    /** 瘦身通用插件：按当前平台从资产地图下载运行时（SHA-256 校验 + 安全解压）。 */
    private fun downloadRuntimeInternal(options: RuntimeProvisioner.DownloadOptions = RuntimeProvisioner.DownloadOptions()): RuntimeProvisioner.ProvisionResult {
        val override = com.deepseek.harness.idea.settings.DshSettingsState.getInstance().runtimeDownloadUrl
            ?.trim()?.takeIf { it.isNotEmpty() }
        val spec = RuntimeAssets.load(override)
        val result = RuntimeProvisioner.provision(runtimeRoot(), spec, pluginVersion(), RuntimeProvisioner.HttpFetcher, options)
        if (result !is RuntimeProvisioner.ProvisionResult.Ready) {
            LOG.warn("runtime download/provision failed for ${Platform.current().id} (base=${spec.baseUrl})")
        }
        return result
    }

    /** 当前平台将下载的**完整资产文件 URL**（设置覆盖或默认 base + 资产文件名），无资产返回 null。 */
    fun effectiveRuntimeDownloadUrl(): String? {
        val override = com.deepseek.harness.idea.settings.DshSettingsState.getInstance().runtimeDownloadUrl
            ?.trim()?.takeIf { it.isNotEmpty() }
        return RuntimeAssets.load(override).urlFor(Platform.current(), pluginVersion())
    }

    /** 从本地已下载的运行时 zip 导入（离线，不联网）；存在同目录 `.sha256` 则一并校验。 */
    fun provisionFromLocalZip(zip: java.nio.file.Path): RuntimeProvisioner.ProvisionResult {
        val sidecar = zip.resolveSibling(zip.fileName.toString() + ".sha256")
        val expectedSha = if (Files.isRegularFile(sidecar)) runCatching { Files.readString(sidecar).trim() }.getOrNull() else null
        return RuntimeProvisioner.provisionFromLocal(zip, runtimeRoot(), expectedSha)
    }

    /** 运行期读取插件版本：来自构建期注入的 `dsh-build-info.properties`（无内部 API，见 build.gradle.kts generateBuildInfo）。 */
    private fun pluginVersion(): String = try {
        val stream = DshHomeManager::class.java.getResourceAsStream(BUILD_INFO_RESOURCE)
            ?: run { LOG.warn("$BUILD_INFO_RESOURCE not found; runtime download version falls back to empty"); return "" }
        val props = java.util.Properties().apply { load(stream) }
        (props.getProperty("version") ?: "").takeIf { it.isNotBlank() } ?: ""
    } catch (e: Exception) {
        LOG.warn("failed to read $BUILD_INFO_RESOURCE", e)
        ""
    }

    /** 从插件资源解压内嵌运行时（幂等：已存在则跳过；无资源返回 false）。 */
    private fun extractBundledRuntime(): Boolean {
        val target = runtimeRoot()
        if (RuntimeProvisioner.isPresent(target)) return true
        val resource = RUNTIME_BUNDLE_RESOURCE
        val stream = try {
            DshHomeManager::class.java.getResourceAsStream(resource)
        } catch (e: Exception) {
            null
        }
        if (stream == null) {
            LOG.info("no bundled runtime resource ($resource); thin build expects on-demand download")
            return false
        }
        LOG.info("extracting bundled runtime to $target")
        return try {
            Files.createDirectories(target)
            val tmpZip = target.resolveSibling("runtime-bundle-${System.nanoTime()}.zip")
            stream.use { src -> Files.copy(src, tmpZip, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            RuntimeArchive.unzip(tmpZip, target)
            Files.deleteIfExists(tmpZip)
            RuntimeProvisioner.isPresent(target)
        } catch (e: Exception) {
            LOG.warn("failed to extract bundled runtime", e)
            false
        }
    }

    /**
     * 独立 DSH_HOME（会话数据持久化；按项目隔离，不随运行时版本变化）。
     *
     * v0.1.3-dev（切换项目工作区修复）：每个项目使用独立目录（MD5(projectPath) 前 16 位），
     * 使 dsh 的工作区注册表（workspace.json）与会话数据按项目隔离——切换项目后 dsh 进程的
     * 工作区从当前项目"白纸"开始，从机制上杜绝"显示其他项目工作区"（用户实测：仅旧项目复现，
     * 全新项目无问题，因为 dsh 记住了既有 workspace 的历史会话状态）。
     */
    /**
     * 全局配置目录（方案 C：dsh 配置全局化）——`.credentials.yaml` / `settings.yaml` 的
     * **唯一真源**，所有项目共享；每项目启动时通过 ide.yml patch 把 dsh 的
     * `settings-file.path` / `credentials-local.path` 指向这里，实现"配置共享 + 数据隔离"。
     */
    fun globalConfigHome(): Path = PathManager.getConfigDir().resolve("dsh-idea").resolve("dsh-home")

    fun homeDir(projectPath: String): Path {
        val safe = if (projectPath.isBlank()) "default" else md5(projectPath).take(16)
        return globalConfigHome().resolve(safe)
    }

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 幂等创建 DSH_HOME 骨架：
     * - profiles/web/（package.json + cordis.yml + cordis.patch.yml）
     * - ide.yml（--patch 覆盖层占位）
     * - 顶层 node_modules junction → runtime dsh 树（mcp-ide-server.mjs 从 DSH_HOME 顶层
     *   解析 @modelcontextprotocol/sdk；dsh 自愈的 profiles/node_modules 不会被 ESM 向上查找命中）
     * - mcp-ide-server.mjs（插件资源部署）
     */
    fun ensureHome(projectPath: String): Path {
        // 全局配置目录：.credentials.yaml / settings.yaml 唯一真源（所有项目共享，由 ide.yml patch 指向）
        val ghome = globalConfigHome()
        Files.createDirectories(ghome)
        prefillAcknowledgeWelcomeNotice()

        // 每项目子目录 DSH_HOME（数据隔离；storages/sessions 由 dsh 创建；不写独立配置）
        val home = homeDir(projectPath)
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)

        writeIfAbsent(web.resolve("package.json"), WEB_PROFILE_MANIFEST)
        writeIfAbsent(web.resolve("cordis.yml"), "[]\n")
        writeIfAbsent(web.resolve("cordis.patch.yml"), "# 本层由插件通过 --patch 覆盖，不在此修改\n[]\n")
        writeIfAbsent(home.resolve(IDE_PATCH_FILE), "[]\n")
        ensureTopLevelNodeModules(home)
        deployMcpServer(home)
        // 方案 A：把全局唯一配置复制到本子目录（dsh 从子目录读；全局为真源；dsh 内改动下次启动被全局覆盖）
        copyGlobalConfigTo(home)
        // 升级迁移：v0.1.2 全局 DSH_HOME 的 session 数据 → 当前项目隔离目录（幂等；workspace 由 dsh 自动重建）
        migrateLegacySessions(home, projectPath)
        return home
    }

    /**
     * 旧版（v0.1.2）在全局 DSH_HOME 根（= [globalConfigHome]）下存 session；新版改为每项目隔离目录。
     * 把旧全局 `sessions/<projectKey(projectPath)>` 复制到本子目录（含投影缓存 `session_projcache.json`），
     * 使用户升级后旧会话仍可见且标题正确（dsh 的 `session.list` 用零 I/O 投影缓存读标题，需一并迁移）。
     * 仅当全局根下存在对应项目目录且子目录数据尚未迁移时复制（幂等）。
     */
    private fun migrateLegacySessions(home: Path, projectPath: String) {
        if (projectPath.isBlank()) return
        val oldRoot = globalConfigHome()
        if (!Files.isDirectory(oldRoot.resolve("sessions"))) return
        try {
            LegacySessionMigrator.migrateProject(oldRoot, home, projectPath)
            LegacySessionMigrator.migrateProjectionCache(oldRoot, home, projectPath)
        } catch (e: Exception) {
            LOG.warn("legacy session migration failed for $projectPath", e)
        }
    }

    /** 把全局配置文件（.credentials.yaml / settings.yaml）复制到子目录（幂等；仅当全局存在）。 */
    private fun copyGlobalConfigTo(home: Path) {
        val g = globalConfigHome()
        for (name in listOf(".credentials.yaml", "settings.yaml")) {
            val src = g.resolve(name)
            if (Files.exists(src)) {
                Files.createDirectories(home)
                Files.copy(src, home.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** 预写全局 settings.yaml：ui-onboarding.welcomeNoticeVersion = 已接受版本（文件已存在则不覆盖）。 */
    private fun prefillAcknowledgeWelcomeNotice() {
        val f = globalConfigHome().resolve("settings.yaml")
        if (Files.exists(f)) return
        writeUtf8(f, "ui-onboarding:\n  welcomeNoticeVersion: \"$WELCOME_NOTICE_VERSION\"\n")
        LOG.info("prefilled settings.yaml welcomeNoticeVersion=$WELCOME_NOTICE_VERSION")
    }

    /** 顶层 node_modules junction（缺失才建；指向运行时 dsh 树，供 mcp-ide-server.mjs 解析 SDK）。 */
    private fun ensureTopLevelNodeModules(home: Path) {
        val link = home.resolve("node_modules")
        if (Files.exists(link)) return
        val target = runtimeRoot().resolve("dsh/node_modules")
        if (!Files.isDirectory(target)) {
            LOG.warn("runtime dsh tree missing: $target")
            return
        }
        try {
            Files.createSymbolicLink(link, target)
            LOG.info("created DSH_HOME/node_modules junction -> $target")
        } catch (e: Exception) {
            // 沙箱/权限受限时，Windows 退回 cmd mklink /J（junction 不需要管理员）；
            // Unix 上 createSymbolicLink 通常无需管理员即可成功，此处不调用 Windows 专用命令。
            if (Platform.current().os != Platform.Os.WINDOWS) {
                LOG.warn("failed to create node_modules symlink $link -> $target (unix)", e)
                return
            }
            try {
                val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start()
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                if (Files.exists(link)) LOG.info("created DSH_HOME/node_modules junction via mklink -> $target")
                else LOG.warn("mklink junction failed for $link -> $target")
            } catch (e2: Exception) {
                LOG.warn("failed to create node_modules junction $link", e2)
            }
        }
    }

    /** 从插件资源部署 mcp-ide-server.mjs 到 DSH_HOME（内容变化时覆盖）。 */
    private fun deployMcpServer(home: Path) {
        val target = home.resolve("mcp-ide-server.mjs")
        try {
            val resource = IdeBridgeResources.mcpServerScript() ?: return
            if (!Files.exists(target) || Files.readString(target) != resource) {
                writeUtf8(target, resource)
                LOG.info("deployed mcp-ide-server.mjs to $target")
            }
        } catch (e: Exception) {
            LOG.warn("failed to deploy mcp-ide-server.mjs", e)
        }
    }

    /** MCP server 脚本路径（DSH_HOME 顶层，ESM 可解析顶层 node_modules junction）。 */
    fun mcpServerScript(projectPath: String): Path = homeDir(projectPath).resolve("mcp-ide-server.mjs")

    /** 将 PasswordSafe 中的 API Key 同步到全局 .credentials.yaml（所有项目共享，由 ide.yml patch 指向）。 */
    fun syncCredentials(): Boolean {
        val key = DshCredentials.readApiKey() ?: return false
        val credFile = globalConfigHome().resolve(".credentials.yaml")
        val content = "$DEEPSEEK_API_KEY: $key\n"
        return try {
            if (!Files.exists(credFile) || Files.readString(credFile) != content) {
                writeUtf8(credFile, content)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            LOG.warn("failed to sync credentials to DSH_HOME", e)
            false
        }
    }

    /** 设置页 apply：把 API Key 同步到全局 .credentials.yaml（运行中的会话需重启生效）。 */
    fun syncCredentialsAll() {
        syncCredentials()
    }

    private fun writeIfAbsent(path: Path, content: String) {
        if (!Files.exists(path)) writeUtf8(path, content)
    }

    private fun writeUtf8(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    override fun dispose() = Unit
}
