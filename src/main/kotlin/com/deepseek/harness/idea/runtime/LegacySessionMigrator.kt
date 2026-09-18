package com.deepseek.harness.idea.runtime

import com.deepseek.harness.idea.util.JsonCodec
import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 旧版（v0.1.2，全局 DSH_HOME）→ 新版（v0.1.3-dev 起，每项目隔离 DSH_HOME）升级迁移。
 *
 * 旧版 dsh 的 DSH_HOME 就是全局根 `<config>/dsh-idea/dsh-home`（即 [DshHomeManager.sharedConfigRoot]），
 * session 数据位于其 `sessions/<projectKey(cwd)>/<sessionId>/`；新版 DSH_HOME 改为
 * `dsh-home/<md5(projectPath)>`，dsh 只读该子目录。升级后旧 session 目录仍在全局根，但已不被读取，
 * 用户表现为"升级后之前的 session 找不到了"。
 *
 * 迁移两部分：
 * 1. **session 日志**：把当前项目旧全局 `sessions/<projectKey(projectPath)>` 下各 session 目录复制到
 *    隔离目录 `sessions/<projectKey(projectPath)>/`（逐目录合并、目标已存在则跳过，幂等）。
 * 2. **投影缓存（session_projcache.json）**：dsh 的 `session.list` 用**零 I/O 缓存投影**（
 *    `cachedSnapshot`）读每行会话标题；若缓存无某会话记录，`session.title` 为 undefined，UI 回退
 *    显示 `basename(cwd)`（即项目目录名）。旧全局 `storages/session_projcache.json` 保存着各会话的
 *    `title` 投影。迁移时从全局缓存**筛选当前项目**（identity.cwd 匹配）的会话条目，合并写入隔离
 *    目录的 `storages/session_projcache.json`，使升级后历史会话标题立即可见（无需逐个打开才 warm）。
 *
 * **workspace 注册表（workspace.json）无需手工迁移**：dsh 启动时若 `workspace.json` 的
 * `initialized=false`（新隔离目录首次启动必然如此），workspace bootstrap 会从已复制 session 的
 * header（其 `cwd` 字段）自动重建 workspace 记录并挂接 session 归属，因此旧会话可正常显示。
 */
object LegacySessionMigrator {

    private val LOG = Logger.getInstance(LegacySessionMigrator::class.java)

    /** 投影缓存文件名（dsh session-projection-cache 域）。 */
    private const val PROJCACHE_FILE = "session_projcache.json"

    /**
     * 把 [oldGlobalHome] 下属于 [projectPath] 的 session 目录复制到 [isolatedHome]。
     * 幂等：目标已存在的 session 目录跳过（合并，不覆盖）。返回本调用复制的 session 目录数。
     */
    fun migrateProject(oldGlobalHome: Path, isolatedHome: Path, projectPath: String): Int {
        if (projectPath.isBlank()) return 0
        val key = dshProjectKey(projectPath)
        val srcDir = oldGlobalHome.resolve("sessions").resolve(key)
        if (!Files.isDirectory(srcDir)) return 0

        var copied = 0
        Files.newDirectoryStream(srcDir).use { entries ->
            for (entry in entries) {
                if (!Files.isDirectory(entry)) continue // 只迁移 session 目录
                val dstDir = isolatedHome.resolve("sessions").resolve(key).resolve(entry.fileName.toString())
                if (Files.exists(dstDir)) continue // 已存在 → 跳过（合并语义）
                try {
                    copyRecursively(entry, dstDir)
                    copied++
                } catch (e: Exception) {
                    LOG.warn("failed to migrate legacy session ${entry.fileName} for $projectPath", e)
                }
            }
        }
        if (copied > 0) {
            LOG.info("migrated $copied legacy session dir(s) for $projectPath -> ${isolatedHome.resolve("sessions").resolve(key)}")
        }
        return copied
    }

    /**
     * 从全局投影缓存 [oldGlobalHome]/storages/session_projcache.json 筛选 [projectPath]（cwd 匹配）的
     * 会话条目，合并写入 [isolatedHome]/storages/session_projcache.json（保留目标已有条目）。
     * 幂等。返回合并（含已存在）的会话条目数；全局无缓存或无匹配返回 0。
     */
    fun migrateProjectionCache(oldGlobalHome: Path, isolatedHome: Path, projectPath: String): Int {
        if (projectPath.isBlank()) return 0
        val src = oldGlobalHome.resolve("storages").resolve(PROJCACHE_FILE)
        if (!Files.isRegularFile(src)) return 0

        val srcDoc = JsonCodec.decodeObject(Files.readString(src, StandardCharsets.UTF_8))
        val srcTables = srcDoc["tables"] as? Map<*, *> ?: return 0
        val srcSessions = srcTables["sessions"] as? Map<*, *> ?: return 0

        // 当前项目 cwd 规范化（统一反斜杠以便与 identity.cwd 比较）
        val want = projectPath.replace('\\', '/')

        val picked = LinkedHashMap<String, Any?>()
        for ((sessionId, sessionRow) in srcSessions) {
            val row = sessionRow as? Map<*, *> ?: continue
            val identity = row["identity"] as? Map<*, *> ?: continue
            val cwd = identity["cwd"] as? String ?: continue
            if (cwd.replace('\\', '/') == want) picked[sessionId.toString()] = row
        }
        if (picked.isEmpty()) return 0

        // 目标隔离目录数据库（可能已被 dsh 建过：保留其已有条目，只补缺失）
        val dst = isolatedHome.resolve("storages").resolve(PROJCACHE_FILE)
        val merged: LinkedHashMap<String, Any?> = if (Files.isRegularFile(dst)) {
            val existing = JsonCodec.decodeObject(Files.readString(dst, StandardCharsets.UTF_8))
            val existingTables = existing["tables"] as? Map<*, *>
            val existingSessions = existingTables?.get("sessions") as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            LinkedHashMap(existingSessions?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())
        } else {
            LinkedHashMap()
        }
        var before = merged.size
        picked.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val added = merged.size - before
        if (added == 0) return merged.size

        val outDoc = LinkedHashMap<String, Any?>()
        outDoc["unit"] = LinkedHashMap<String, Any?>().apply {
            put("name", "session_projcache")
            put("version", 3)
        }
        outDoc["global"] = null
        outDoc["tables"] = LinkedHashMap<String, Any?>().apply {
            put("sessions", merged)
        }
        try {
            Files.createDirectories(dst.parent)
            Files.writeString(dst, JsonCodec.encode(outDoc), StandardCharsets.UTF_8)
            LOG.info("migrated ${merged.size} projection-cache session row(s) for $projectPath -> $dst (added $added)")
        } catch (e: Exception) {
            LOG.warn("failed to migrate projection cache for $projectPath", e)
        }
        return merged.size
    }

    private fun copyRecursively(src: Path, dst: Path) {
        Files.createDirectories(dst)
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /**
     * 复刻 dsh `session-persistence-jsonl` 的 `projectKey(cwd)`——可读的、文件系统安全的
     * 项目目录 key。用于定位旧全局 `sessions/` 下对应项目的目录（与 dsh 写入时的编码一致）。
     *
     * `/`、`\`、`:` 折叠为单个 `-`；安全字符（`[A-Za-z0-9._-]`）原样；其余以 `~XXXX`（UTF-16
     * 码元大写十六进制、补 4 位）转义；前缀 `--`、后缀 `--`，去首部 `-` 后空则用 `root`，截断 251。
     */
    fun dshProjectKey(cwd: String): String {
        if (cwd.isEmpty()) return "--root--"
        val sb = StringBuilder()
        var separatorRun = false
        for (ch in cwd) {
            val code = ch.code
            when {
                ch == '/' || ch == '\\' || ch == ':' -> {
                    if (!separatorRun) sb.append('-')
                    separatorRun = true
                }
                ch != '~' && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '-') -> {
                    sb.append(ch)
                    separatorRun = false
                }
                else -> {
                    sb.append('~').append(code.toString(16).uppercase().padStart(4, '0'))
                    separatorRun = false
                }
            }
        }
        val readable = sb.toString().replace(Regex("^-+"), "").ifEmpty { "root" }.take(251)
        return "--$readable--"
    }
}
