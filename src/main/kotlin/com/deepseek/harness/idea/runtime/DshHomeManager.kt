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
import java.nio.file.StandardCopyOption

/**
 * DSH 运行时与 DSH_HOME 管理（应用级服务）。
 *
 * 目录布局（见 docs/DESIGN.md §4.4）：
 * - 运行时根：环境变量 `DSH_IDEA_RUNTIME`（开发态覆盖）或
 *   `<config>/dsh-idea/runtime/<version>`（生产态从插件资源解压/下载）。
 *   内含 `node/`（Node.js）、`dsh/`（npm 安装的 @deepseek-ai/dsh 树）与
 *   `.dsh-ide-bridge/mcp-ide-server.mjs`（全局唯一一份 MCP 脚本）。
 * - **共享配置根** [sharedConfigRoot]：`<config>/dsh-idea/dsh-home`，dsh 的**用户级配置面**唯一真源
 *   （`settings.yaml`、`.credentials.yaml`、`.agent-presets/`、`skills/`），所有项目共享；
 *   由 `ide.yml` 的 `- id: settings` / `- id: credentials` / `- id: agent-presets` /
 *   `- id: skill-filesystem` patch 指向（见 [com.deepseek.harness.idea.mcp.McpPatchGenerator]）。
 * - **每项目 DSH_HOME** [homeDir]：`<共享配置根>/<md5(项目路径)前16位>`，只承载 dsh 的**数据面**
 *   （`sessions/`、`storages/`、`profiles/web/`、`ide.yml`），使工作区注册表与会话按项目隔离
 *   （v0.1.3-dev 切换项目修复）。
 *
 * **v0.2.4 变更**：删除"全局配置复制到每项目子目录"（`copyGlobalConfigTo`）与每项目
 * `node_modules` junction；配置改为经 patch 直接落在共享根，插件不再参与配置同步。
 */
@Service(Service.Level.APP)
class DshHomeManager : Disposable {

    companion object {
        private val LOG = Logger.getInstance(DshHomeManager::class.java)

        /** 固定 dsh 版本（升级 = 换版本 + 重建运行时，见 DESIGN §3.2） */
        const val DSH_VERSION = "0.1.5-rc.2"

        /** 构建期注入的版本信息资源（generateBuildInfo 产出，供运行期读取插件版本，避免用内部 API）。 */
        const val BUILD_INFO_RESOURCE = "/dsh-build-info.properties"

        /** 开发态覆盖：DSH_IDEA_RUNTIME=<目录> 直接使用该目录下的 node/ 与 dsh/ */
        const val RUNTIME_OVERRIDE_ENV = "DSH_IDEA_RUNTIME"

        /** 插件资源中的运行时压缩包（build-runtime.ps1 -Bundle 产物，Step 5 打入 resources） */
        const val RUNTIME_BUNDLE_RESOURCE = "/runtime-bundle.zip"

        const val DEEPSEEK_API_KEY = "DEEPSEEK_API_KEY"

        /** 与启动 dsh web --patch 使用的 ide.yml 文件名 */
        const val IDE_PATCH_FILE = "ide.yml"

        /** MCP server 脚本名（部署在 dsh 安装树内，见 [mcpServerScript]）。 */
        const val MCP_SERVER_SCRIPT = "mcp-ide-server.mjs"

        /**
         * MCP 脚本在运行时树内的部署目录（相对 `<运行时根>/dsh/node_modules`）。
         *
         * **为什么必须在这里**：脚本 `import '@modelcontextprotocol/sdk/...'` / `'zod/v4'`，
         * Node 的 ESM 解析按**真实路径**向上逐级查找 `node_modules`，并**不越过 junction/符号链接**
         * （实测：把脚本放在 `<运行时根>/.dsh-ide-bridge/` 并给 `<运行时根>/dsh` 建 junction 仍然报
         * `ERR_MODULE_NOT_FOUND`）。放在 `<dsh 树>/node_modules/@deepseek-ai/dsh-ide-bridge/` 时：
         * ① 同级 `node_modules`（即包名 `@deepseek-ai/dsh-ide-bridge` 的推断位置）→
         * ② `<dsh 树>/node_modules`（真正命中的一级）——解析稳定，且全局只有一份脚本、**零链接**。
         */
        const val MCP_BRIDGE_PACKAGE = "dsh-ide-bridge"

        private val WEB_PROFILE_MANIFEST =
            """{"name":"dsh-profile-web","private":true,"dependencies":{},"dsh":{"profile":{"bundles":["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]}}}"""

        fun getInstance(): DshHomeManager =
            ApplicationManager.getApplication().getService(DshHomeManager::class.java)

        /** dsh 内测声明 acknowledge 版本（与 dsh 源码 WELCOME_NOTICE_VERSION 一致；变化需同步）。 */
        const val WELCOME_NOTICE_VERSION = "2026-08-13.1"

        /** 每项目 DSH_HOME 目录名：`md5(项目路径)` 前 16 位十六进制（应用级共享根的隔离键）。 */
        internal val PROJECT_DIR_NAME = Regex("^[0-9a-f]{16}$")

        /**
         * 运行时根解析（纯逻辑，便于单测）：环境变量 > 设置页「运行时目录」> 默认目录，
         * 且只有**确实存在的目录**才会被采用（无效值回落下一级）。
         */
        internal fun resolveRuntimeRoot(envDir: String?, configuredDir: String?, fallback: Path): Path {
            fun existing(raw: String?): Path? {
                val s = raw?.trim().orEmpty()
                if (s.isEmpty()) return null
                val p = runCatching { Path.of(s) }.getOrNull() ?: return null
                return if (Files.isDirectory(p)) p else null
            }
            return existing(envDir) ?: existing(configuredDir) ?: fallback
        }

        /**
         * 共享配置根解析（纯逻辑，便于单测）：显式覆盖目录 > 默认。
         * 与运行时目录不同，共享根**不需要预先存在**（首次使用会创建；dsh 自身也会 `mkdir -p`）。
         */
        internal fun resolveSharedConfigRoot(configuredDir: String?, fallback: Path): Path {
            val s = configuredDir?.trim().orEmpty()
            if (s.isEmpty()) return fallback
            val p = runCatching { Path.of(s) }.getOrNull() ?: return fallback
            return if (p.isAbsolute) p.normalize() else fallback
        }

        /** 路径等价比较（纯逻辑）：规范化绝对路径后忽略大小写与分隔符差异（Windows 大小写不敏感）。 */
        internal fun samePath(a: String?, b: String?): Boolean {
            val x = a?.trim().orEmpty()
            val y = b?.trim().orEmpty()
            if (x.isEmpty() || y.isEmpty()) return x == y
            fun norm(s: String): String? = runCatching {
                Path.of(s).toAbsolutePath().normalize().toString().replace('\\', '/')
            }.getOrNull()
            val nx = norm(x) ?: return x.equals(y, ignoreCase = true)
            val ny = norm(y) ?: return x.equals(y, ignoreCase = true)
            return nx.equals(ny, ignoreCase = true)
        }

        /** MD5 十六进制小写（项目隔离目录名）。 */
        private fun md5(s: String): String =
            java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * 运行时根目录（node/ + dsh/ 的父目录）。优先级：
     * 1. 环境变量 `DSH_IDEA_RUNTIME`（存在的目录）；
     * 2. 设置页「运行时目录」（存在的目录，等价于环境变量的 GUI 版本）；
     * 3. 默认 `<config>/dsh-idea/runtime/<DSH_VERSION>`（插件下载/解压目标）。
     */
    fun runtimeRoot(): Path {
        val configured = com.deepseek.harness.idea.settings.DshSettingsState.getInstance().runtimeDirectory
        return resolveRuntimeRoot(System.getenv(RUNTIME_OVERRIDE_ENV), configured, defaultRuntimeRoot())
    }

    /** 插件的默认运行时目录（**下载/解压目标**，与用户是否指定无关）：`<config>/dsh-idea/runtime/<DSH_VERSION>`。 */
    fun defaultRuntimeRoot(): Path =
        PathManager.getConfigDir().resolve("dsh-idea").resolve("runtime").resolve(DSH_VERSION)

    /** 给定文本是否等价于默认运行时目录（空串/空白同样视为等价"未指定"）。 */
    fun isDefaultRuntimeDirectory(text: String?): Boolean =
        text.isNullOrBlank() || samePath(text, defaultRuntimeRoot().toString())

    /**
     * 是否由用户**显式**指定了**非默认**的运行时目录（环境变量，或设置页填了默认目录以外的路径）：
     * 显式指定但内容缺失时报错、不回退下载。注意"填的正是默认目录"与"未指定"等价 —— 该目录由插件
     * 下载/解压管理，仍应自动供给（避免点「默认」后陷入"目录为空又不下载"）。
     */
    fun hasExplicitRuntimeRoot(): Boolean {
        if (!System.getenv(RUNTIME_OVERRIDE_ENV).isNullOrBlank()) return true
        val configured = com.deepseek.harness.idea.settings.DshSettingsState.getInstance().runtimeDirectory
        if (configured.isNullOrBlank()) return false
        return !isDefaultRuntimeDirectory(configured)
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
        if (hasExplicitRuntimeRoot()) return false // 显式指向但缺失 → 报错（不触发下载）
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
        if (hasExplicitRuntimeRoot()) {
            LOG.warn("runtime directory is set explicitly (env/settings) but runtime is missing at ${runtimeRoot()}")
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

    /** 默认（未被覆盖）的运行时下载 base URL 模板（含 `{version}` 占位符）。 */
    fun defaultRuntimeBaseUrl(): String = RuntimeAssets.load(null).baseUrl

    /** 默认下载地址：当前平台 + 当前插件版本的**完整资产 URL**（设置页反显与「默认」按钮用）。 */
    fun defaultRuntimeDownloadUrl(): String? =
        RuntimeAssets.load(null).urlFor(Platform.current(), pluginVersion())

    /**
     * 给定文本是否等价于"默认下载地址"（留空、默认 base 模板、或当前版本/平台的完整默认 URL）
     * —— 等价时设置页视为**未覆盖**（保存为 null，跟随插件版本/平台动态变化）。
     */
    fun isDefaultRuntimeDownloadUrl(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return true
        if (t == defaultRuntimeBaseUrl()) return true
        return t == defaultRuntimeDownloadUrl()
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
    internal fun pluginVersion(): String = try {
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
            stream.use { src -> Files.copy(src, tmpZip, StandardCopyOption.REPLACE_EXISTING) }
            RuntimeArchive.unzip(tmpZip, target)
            Files.deleteIfExists(tmpZip)
            RuntimeProvisioner.isPresent(target)
        } catch (e: Exception) {
            LOG.warn("failed to extract bundled runtime", e)
            false
        }
    }

    // ---- 共享配置根（v0.2.4） ----

    /**
     * 共享配置根：`<config>/dsh-idea/dsh-home`。
     *
     * 这是 dsh **用户级配置面**的唯一真源（`settings.yaml` / `.credentials.yaml` /
     * `.agent-presets/` / `skills/`），由 `ide.yml` patch 指向，所有项目共享；也是每项目
     * DSH_HOME 的父目录（数据面仍按项目隔离）。
     *
     * 路径沿用 v0.1.3-dev 以来的全局根，避免丢失用户既有的语言偏好与内测声明接受状态。
     */
    fun sharedConfigRoot(): Path =
        resolveSharedConfigRoot(
            com.deepseek.harness.idea.settings.DshSettingsState.getInstance().dshHomeOverride,
            PathManager.getConfigDir().resolve("dsh-idea").resolve("dsh-home"),
        )

    /** 共享设置文档：`<共享根>/settings.yaml`（dsh `dsh-settings-file` 的 `path`）。 */
    fun sharedSettingsPath(): Path = sharedConfigRoot().resolve(SharedConfigMigrator.SETTINGS_FILE)

    /** 共享凭据文档：`<共享根>/.credentials.yaml`（dsh `dsh-credentials-local` 的 `path`）。 */
    fun sharedCredentialsPath(): Path = sharedConfigRoot().resolve(SharedConfigMigrator.CREDENTIALS_FILE)

    /** 共享 Agent 预设根：`<共享根>/.agent-presets`（dsh `dsh-agent-presets` 的 user root）。 */
    fun sharedAgentPresetsRoot(): Path = sharedConfigRoot().resolve(".agent-presets")

    /** 共享用户技能根：`<共享根>/skills`（dsh `dsh-skill-filesystem` 的 `dshHome` 下 skills）。 */
    fun sharedSkillsRoot(): Path = sharedConfigRoot().resolve("skills")

    /** 每项目 DSH_HOME：`<共享根>/<md5(项目路径)前16位>`（数据面隔离，见类注释）。 */
    fun homeDir(projectPath: String): Path {
        val safe = if (projectPath.isBlank()) "default" else md5(projectPath).take(16)
        return sharedConfigRoot().resolve(safe)
    }

    /** 共享根下已存在的每项目 DSH_HOME 目录（迁移遍历用）。 */
    internal fun projectHomes(): List<Path> {
        val root = sharedConfigRoot()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.newDirectoryStream(root).use { entries ->
            entries.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && PROJECT_DIR_NAME.matches(it.fileName.toString()) }
        }
    }

    /**
     * 幂等创建某项目的 DSH_HOME 骨架（**只建数据面**，不写配置）：
     * - `profiles/web/`（package.json + cordis.yml + cordis.patch.yml）
     * - `ide.yml`（`--patch` 覆盖层占位，随后由 `DshBridgeManager` 覆盖写入）
     * - 共享根：部署全局唯一的 MCP 脚本、清理 v0.1.2 遗留、执行一次性配置迁移
     *
     * v0.2.4 起**不再**向项目目录复制 `settings.yaml` / `.credentials.yaml`，也**不再**建
     * `node_modules` junction（配置面由 patch 指向共享根，MCP 脚本依赖由运行时树解析）。
     */
    fun ensureHome(projectPath: String): Path {
        val shared = sharedConfigRoot()
        Files.createDirectories(shared)

        // 全局唯一 MCP 脚本（解析依赖靠运行时树，无需任何 junction）
        ensureMcpServerScript(shared)

        // v0.1.2 遗留（共享根曾同时是全局 DSH_HOME）+ 每项目遗留 junction/脚本清理
        SharedConfigMigrator.cleanLegacySharedRoot(shared)

        // 一次性配置迁移：项目目录 settings/credentials → 合并进共享文档并备份原文件
        val outcome = SharedConfigMigrator.migrateIfNeeded(shared, projectHomes(), pluginVersion())
        if (outcome.ran) {
            LOG.info(
                "shared-config migration: seeded=${outcome.seededSettings} projects=${outcome.migratedProjects} " +
                    "namespaces=${outcome.mergedNamespaces} credentials=${outcome.mergedCredentials} failures=${outcome.failures}"
            )
        }

        // 每项目 DSH_HOME（数据面）
        val home = homeDir(projectPath)
        val web = home.resolve("profiles/web")
        Files.createDirectories(web)

        writeIfAbsent(web.resolve("package.json"), WEB_PROFILE_MANIFEST)
        writeIfAbsent(web.resolve("cordis.yml"), "[]\n")
        writeIfAbsent(web.resolve("cordis.patch.yml"), "# 本层由插件通过 --patch 覆盖，不在此修改\n[]\n")
        writeIfAbsent(home.resolve(IDE_PATCH_FILE), "[]\n")

        cleanLegacyProjectHome(home)
        prefillAcknowledgeWelcomeNotice()
        migrateLegacySessions(home, projectPath)
        return home
    }

    /**
     * 清理项目 DSH_HOME 里的 v0.1.3-dev~v0.2.3 遗留：
     * - 顶层 `node_modules` junction（曾供 MCP 脚本 ESM 解析 SDK；v0.2.4 起脚本在运行时树内解析）
     * - 顶层 `mcp-ide-server.mjs`（曾按项目部署）
     *
     * `settings.yaml` / `.credentials.yaml` 由 [SharedConfigMigrator] 搬入备份目录（保留原文件，
     * 失败下次启动重试），此处**不动**，避免迁移失败时造成不可恢复的丢失。
     *
     * **junction 必须断链**（保留原始目标），否则会删空运行时树（见 PROJECT_NOTES §4）。
     */
    private fun cleanLegacyProjectHome(home: Path) {
        val nodeModules = home.resolve("node_modules")
        if (Files.exists(nodeModules, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try {
                val attrs = Files.readAttributes(
                    nodeModules, java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                )
                if (attrs.isSymbolicLink || attrs.isOther) {
                    Files.deleteIfExists(nodeModules)
                    LOG.info("removed legacy DSH_HOME/node_modules link (no longer needed): $nodeModules")
                }
            } catch (e: Exception) {
                LOG.warn("failed to unlink legacy node_modules at $nodeModules", e)
            }
        }
        val legacyScript = home.resolve(MCP_SERVER_SCRIPT)
        if (Files.isRegularFile(legacyScript, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.deleteIfExists(legacyScript) }
                .onSuccess { LOG.info("removed legacy per-project $MCP_SERVER_SCRIPT: $legacyScript") }
                .onFailure { LOG.warn("failed to remove legacy $legacyScript", it) }
        }
    }

    /**
     * 旧版（v0.1.2）在全局 DSH_HOME 根（= [sharedConfigRoot]）下存 session；新版改为每项目隔离目录。
     * 把旧全局 `sessions/<projectKey(projectPath)>` 复制到本子目录（含投影缓存 `session_projcache.json`），
     * 使用户升级后旧会话仍可见且标题正确（dsh 的 `session.list` 用零 I/O 投影缓存读标题，需一并迁移）。
     * 仅当全局根下存在对应项目目录且子目录数据尚未迁移时复制（幂等）。
     */
    private fun migrateLegacySessions(home: Path, projectPath: String) {
        if (projectPath.isBlank()) return
        val oldRoot = sharedConfigRoot()
        if (!Files.isDirectory(oldRoot.resolve("sessions"))) return
        try {
            LegacySessionMigrator.migrateProject(oldRoot, home, projectPath)
            LegacySessionMigrator.migrateProjectionCache(oldRoot, home, projectPath)
        } catch (e: Exception) {
            LOG.warn("legacy session migration failed for $projectPath", e)
        }
    }

    /** 共享 `settings.yaml` 首次创建时预写 `ui-onboarding.welcomeNoticeVersion`（已存在则不覆盖）。 */
    private fun prefillAcknowledgeWelcomeNotice() {
        val f = sharedSettingsPath()
        if (Files.exists(f)) return
        runCatching {
            writeUtf8(f, "ui-onboarding:\n  welcomeNoticeVersion: \"$WELCOME_NOTICE_VERSION\"\n")
            LOG.info("prefilled shared settings.yaml welcomeNoticeVersion=$WELCOME_NOTICE_VERSION")
        }.onFailure { LOG.warn("failed to prefill shared settings.yaml", it) }
    }

    /**
     * 部署全局唯一的 `mcp-ide-server.mjs`（幂等：内容相同跳过）。
     *
     * 目标：`<运行时根>/dsh/node_modules/@deepseek-ai/dsh-ide-bridge/mcp-ide-server.mjs`。
     * 该位置向上查找 `node_modules` 会命中 `<运行时根>/dsh/node_modules/`（dsh 自身依赖树，
     * 含 `@modelcontextprotocol/sdk` 与 `zod`），因此**无需任何 junction**，且全局只有一份脚本。
     *
     * 注意：Node 的 ESM 解析**不越过 junction**（实测），所以脚本必须真正落在 dsh 树内部，
     * 不能靠"给运行时根建链接"来凑解析路径。
     */
    private fun ensureMcpServerScript(shared: Path) {
        val resource = try {
            IdeBridgeResources.mcpServerScript()
        } catch (e: Exception) {
            LOG.warn("failed to read bundled mcp-ide-server.mjs", e)
            null
        } ?: return

        val target = mcpServerScript()
        if (deployScript(resource, target)) return

        // 运行时树只读（如 DSH_IDEA_RUNTIME 指向只读目录）：无法部署 → MCP 工具不可用，
        // 但 Web UI 与对话不受影响（DshBridgeManager 已容忍脚本缺失）。仅记日志。
        LOG.warn("failed to deploy $MCP_SERVER_SCRIPT into the runtime tree at $target; IDE MCP tools will be unavailable (shared=$shared)")
    }

    /** 写脚本（内容变化才写）；成功返回 true。 */
    private fun deployScript(resource: String, target: Path): Boolean = try {
        if (!Files.exists(target) || Files.readString(target) != resource) {
            writeUtf8(target, resource)
            LOG.info("deployed $MCP_SERVER_SCRIPT to $target")
        }
        true
    } catch (e: Exception) {
        LOG.warn("failed to deploy $MCP_SERVER_SCRIPT to $target", e)
        false
    }

    /**
     * MCP server 脚本路径：`<运行时根>/dsh/node_modules/@deepseek-ai/dsh-ide-bridge/mcp-ide-server.mjs`。
     * 依赖（`@modelcontextprotocol/sdk` / `zod`）由该位置向上查找 `<运行时根>/dsh/node_modules` 解析。
     */
    fun mcpServerScript(): Path =
        runtimeRoot().resolve("dsh/node_modules/@deepseek-ai").resolve(MCP_BRIDGE_PACKAGE).resolve(MCP_SERVER_SCRIPT)

    /**
     * 把 PasswordSafe 中的 API Key **合并写入**共享 `.credentials.yaml`
     * （所有项目共享，由 `ide.yml` 的 `- id: credentials` patch 指向）。
     *
     * 关键：只更新 `refs.DEEPSEEK_API_KEY` 一行，**保留**用户/ dsh 写入的其它 `refs` 键与整个
     * `records` 段（v0.2.3 及以前用扁平 layout 整份覆盖，会丢掉其它 provider 的密钥与
     * `client-connection/browser-session` 记录）。dsh 用的是 `version: 1` + `refs`/`records` 文档，
     * 旧扁平文件在首次写入时内联升级（与 `dsh-credentials-local` 的 `renderFlatLayoutMigration` 等价）。
     *
     * @return 是否实际发生变更（供 UI 提示）。
     */
    fun syncCredentials(): Boolean {
        val key = DshCredentials.readApiKey() ?: return false
        val credFile = sharedCredentialsPath()
        return try {
            val existing = if (Files.isRegularFile(credFile)) Files.readString(credFile, StandardCharsets.UTF_8) else null
            if (YamlText.hasRef(existing, DEEPSEEK_API_KEY, key)) return false
            val updated = YamlText.upsertRef(existing, DEEPSEEK_API_KEY, key)
            writeOwnerOnly(credFile, updated)
            true
        } catch (e: Exception) {
            LOG.warn("failed to sync credentials to shared config home", e)
            false
        }
    }

    /** 设置页 apply：把 API Key 同步到共享凭据文件（运行中的会话需重启生效）。 */
    fun syncCredentialsAll() {
        syncCredentials()
    }

    /** owner-only 写入（Unix 下 dsh 的 `assertOwnerOnly` 会拒绝组/他人可读的凭据文件）。 */
    private fun writeOwnerOnly(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp-${System.nanoTime()}")
        Files.writeString(tmp, content, StandardCharsets.UTF_8)
        runCatching { setOwnerOnly(tmp) }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching { setOwnerOnly(path) }
    }

    private fun setOwnerOnly(path: Path) {
        if (Platform.current().os == Platform.Os.WINDOWS) return // Windows 无 POSIX 权限位（dsh 亦跳过）
        val posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
        posix?.setPermissions(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            )
        )
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
