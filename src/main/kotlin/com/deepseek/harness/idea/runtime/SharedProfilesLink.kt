package com.deepseek.harness.idea.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * `<项目DSH_HOME>/profiles` 与共享 profile 根之间的链接判定（纯逻辑，无 IDE 依赖，可单测）。
 *
 * **背景（v0.2.5）**：dsh 从 `$DSH_HOME/profiles/<name>` 解析 profile（`dsh-app-boot` 的
 * `resolveProfileDir`），而插件让每个项目拥有独立 `DSH_HOME`。dsh 插件包装在该 profile 的
 * `node_modules` 里，因此天然按项目隔离。把**每项目的 `profiles` 目录**链接到同一共享目录后：
 * - `dsh plugin … add <pkg>` 装一次，所有项目都能加载（实测两个 DSH_HOME + junction，并发亦正常）；
 * - `sessions` / `storages` / `attachments` 仍在各自 DSH_HOME，数据隔离不回归。
 *
 * 本对象只做"该不该建链 / 要不要迁移 / 回填什么"的纯判断，不触碰文件系统副作用。
 */
internal object SharedProfilesLink {

    /** 项目 `profiles` 相对于共享 profile 根的状态。 */
    enum class State {
        /** 项目侧不存在（需要建链）。 */
        ABSENT,

        /** 已是指向共享根的链接。 */
        CORRECT_LINK,

        /** 是链接，但指向别处（需要修复）。 */
        WRONG_LINK,

        /** 是真实目录（需要迁移 + 建链，或降级时保持原样）。 */
        REAL_DIR,
    }

    /** profile 目录名（插件只启动 `--profile web`）。 */
    const val PROFILE_NAME = "web"

    /** 需要从项目 profile 播种到共享 profile 的文件（相对 profile 目录）。 */
    val SEED_FILES = listOf("package.json", "cordis.yml", "cordis.patch.yml")

    /**
     * 判断 [projectProfiles] 相对 [sharedProfiles] 的状态。
     *
     * - 用 `NOFOLLOW_LINKS` 读属性：Windows junction 在跟随语义下既不是符号链接也不是普通目录，
     *   必须用 `isSymbolicLink || isOther` 识别（与 [DshHomeManager] 清理遗留链接同款判据）。
     * - 判断"指向是否正确"用 `toRealPath` 比较（junction/符号链接都会解析到真实路径）。
     */
    fun state(projectProfiles: Path, sharedProfiles: Path): State {
        if (!Files.exists(projectProfiles, LinkOption.NOFOLLOW_LINKS)) return State.ABSENT
        if (!isLink(projectProfiles)) return State.REAL_DIR
        return if (pointsTo(projectProfiles, sharedProfiles)) State.CORRECT_LINK else State.WRONG_LINK
    }

    /** 是否为链接（符号链接或 Windows junction）。 */
    fun isLink(path: Path): Boolean = try {
        val attrs = Files.readAttributes(
            path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        )
        attrs.isSymbolicLink || attrs.isOther
    } catch (e: Exception) {
        false
    }

    /** 链接是否解析到 [target]（两者都必须存在；否则返回 false）。 */
    fun pointsTo(link: Path, target: Path): Boolean = try {
        Files.exists(target) && link.toRealPath() == target.toRealPath()
    } catch (e: Exception) {
        false
    }

    /** 共享 profile 里缺哪些文件（用于首次启用时用项目 profile 播种）。 */
    fun filesToSeed(projectProfileDir: Path, sharedProfileDir: Path): List<String> =
        seedOrRestore(projectProfileDir, sharedProfileDir)

    /** 关闭共享时，独立 profile 里缺哪些文件（用共享内容回填）。 */
    fun filesToRestore(sharedProfileDir: Path, projectProfileDir: Path): List<String> =
        seedOrRestore(sharedProfileDir, projectProfileDir)

    private fun seedOrRestore(from: Path, to: Path): List<String> = SEED_FILES.filter { name ->
        Files.isRegularFile(from.resolve(name)) && !Files.exists(to.resolve(name))
    }

    /**
     * 项目侧是否"只有插件默认生成物"（迁移前判断是否值得整体搬迁）：
     * 若存在 `node_modules` 或自定义 `cordis.patch.yml`，说明用户可能装过插件，迁移需保留备份。
     */
    fun hasUserContent(projectProfileDir: Path): Boolean {
        if (!Files.isDirectory(projectProfileDir)) return false
        if (Files.exists(projectProfileDir.resolve("node_modules"), LinkOption.NOFOLLOW_LINKS)) return true
        val patch = projectProfileDir.resolve("cordis.patch.yml")
        if (Files.isRegularFile(patch)) {
            val text = runCatching { Files.readString(patch) }.getOrDefault("")
            // 默认占位是注释 + `[]`；出现 insert/insertBefore 等条目才视为用户内容
            if (text.contains("insert")) return true
        }
        return false
    }
}
