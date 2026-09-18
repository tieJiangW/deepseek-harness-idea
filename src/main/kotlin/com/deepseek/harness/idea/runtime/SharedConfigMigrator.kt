package com.deepseek.harness.idea.runtime

import com.intellij.openapi.diagnostic.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * YAML 文本级工具（纯函数，无 IDE 依赖，可单测）。
 *
 * 背景：`settings.yaml` / `.credentials.yaml` 是**用户可编辑**的文档，dsh 自身写入时用 leaf-level diff
 * 刻意保留注释与格式（见 `dsh-settings-file` README「YAML edits are leaf-level diffs」）。插件因此
 * **不做 YAML 序列化/反序列化**，只按"顶层段落"与"行"做文本搬运，避免注释、锚点、`!!js` 表达式丢失。
 */
internal object YamlText {

    /** 顶层键行：`^[A-Za-z_][A-Za-z0-9_.-]*:(\s|$)`（YAML 里只有顶层键不缩进）。 */
    private val TOP_LEVEL_KEY = Regex("""^([A-Za-z_][A-Za-z0-9_.-]*):(\s.*|)$""")

    /** `key: value` 形态（用于识别 dsh 的 pre-release 扁平凭据 layout）。 */
    private val FLAT_ENTRY = Regex("""^[A-Za-z_][A-Za-z0-9_.-]*:\s*\S.*$""")

    /** 顶层 `key:` 单独成行的键名（用于段边界定位）。 */
    private val TOP_LEVEL_MARKER = Regex("""^([A-Za-z_][A-Za-z0-9_.-]*):\s*$""")

    /** 段内子键：允许 `/`（dsh 的 `records` 用 `<scope>/<id>` 作键，如 `client-connection/browser-session`）。 */
    private val CHILD_KEY = Regex("""^([A-Za-z_][A-Za-z0-9_.\-/]*):(\s.*|)$""")

    /** 文本是否有效 YAML 内容（忽略空行、注释、文档分隔符）。 */
    fun hasContent(text: String): Boolean = text.lineSequence().any { line ->
        val t = line.trim()
        t.isNotEmpty() && !t.startsWith("#") && t != "---" && t != "..."
    }

    /** 顶层键出现顺序（首次出现为准，重复键忽略）。 */
    fun topLevelKeys(text: String): List<String> {
        val keys = LinkedHashSet<String>()
        for (line in text.split('\n')) {
            if (line.isEmpty() || line[0] == ' ' || line[0] == '\t' || line[0] == '#') continue
            val t = line.trimEnd()
            if (t == "---" || t == "...") continue
            TOP_LEVEL_KEY.matchEntire(t)?.let { keys.add(it.groupValues[1]) }
        }
        return keys.toList()
    }

    /**
     * 顶层段落切分：`键 → 该键及其后续缩进/空行/注释行的原始文本`。
     * 保留原始字节形态（含注释与换行），供原样追加。
     */
    fun blocksOf(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        var currentKey: String? = null
        val buf = StringBuilder()
        fun flush() {
            val k = currentKey ?: return
            out[k] = (out[k] ?: "") + buf
        }
        for (line in text.split('\n')) {
            val isTop = line.isNotEmpty() && line[0] != ' ' && line[0] != '\t' && line[0] != '#'
            val match = if (isTop) TOP_LEVEL_KEY.matchEntire(line.trimEnd()) else null
            if (match != null) {
                flush()
                buf.setLength(0)
                currentKey = match.groupValues[1]
                buf.append(line).append('\n')
            } else if (currentKey != null) {
                buf.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    /** 追加共享文档缺失的顶层段落（共享侧优先，已存在的键不动）。返回合并后文本与追加的键。 */
    fun mergeMissingNamespaces(shared: String, incoming: Map<String, String>, marker: (String) -> String): Pair<String, List<String>> {
        val existing = topLevelKeys(shared).toSet()
        val sb = StringBuilder(shared)
        val added = ArrayList<String>()
        for ((key, block) in incoming) {
            if (key in existing || key in added) continue
            if (!sb.isEmpty() && sb.last() != '\n') sb.append('\n')
            sb.append(marker(key)).append('\n')
            sb.append(block.trimEnd('\n')).append('\n')
            added.add(key)
        }
        return sb.toString() to added
    }

    /** dsh pre-release 扁平凭据 layout（无 `version`，全部是 `KEY: value` 行）。 */
    fun isFlatCredentials(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return false
        return lines.none { TOP_LEVEL_MARKER.matches(it) } && lines.all { FLAT_ENTRY.matches(it) }
    }

    /**
     * 复刻 dsh `renderFlatLayoutMigration`：把扁平 layout 内联升级为 `version: 1` + `refs:` 段
     * （原行原样缩进两格，值不变）。非扁平文本原样返回。
     */
    fun upgradeFlatCredentials(text: String): String {
        if (!isFlatCredentials(text)) return text
        val body = text.trimEnd('\n').split("\n").joinToString("\n") { if (it.isEmpty()) it else "  $it" }
        return "version: 1\nrefs:\n" + body + "\n"
    }

    /**
     * 顶层 `<key>:` 段的行区间，**闭区间** `[start, end]`：`start` = 该键所在行，
     * `end` = 段内最后一行（即下一个顶层键之前一行 / 文档末行）。不存在返回 null。
     *
     * 注意：这里刻意用闭区间（`start..end`）。Kotlin 的 `until` 会得到 `start..(endExclusive-1)`，
     * 若调用方再按"endExclusive"使用就会**重复减一**、漏掉段末行（曾导致 `mergeCredentials`
     * 读不到 `refs` 段最后一行，把共享侧已有的键误判为缺失）。
     */
    private fun topLevelBlockRange(lines: List<String>, key: String): IntRange? {
        val start = lines.indexOfFirst { TOP_LEVEL_MARKER.matchEntire(it.trimEnd())?.groupValues?.get(1) == key }
        if (start < 0) return null
        var end = lines.size - 1
        for (i in (start + 1) until lines.size) {
            val line = lines[i]
            if (line.isNotEmpty() && line[0] != ' ' && line[0] != '\t' && line[0] != '#') {
                if (TOP_LEVEL_KEY.matchEntire(line.trimEnd()) != null) { end = i - 1; break }
            }
        }
        return if (end < start) start..start else start..end
    }

    /** 段行区间 → 段体（不含段首键行）的行区间。空段返回空区间。 */
    private fun bodyRange(range: IntRange): IntRange = (range.first + 1)..range.last

    /** 段内直接子键 → 行文本（缩进 = 段内最小缩进；用于 `refs` 下的 `KEY: value`）。 */
    fun childKeys(lines: List<String>, range: IntRange): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val body = bodyRange(range)
        if (body.isEmpty()) return out
        val indent = lines.subList(body.first, body.last + 1)
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .minOfOrNull { it.length - it.trimStart().length } ?: return out
        for (i in body.first..body.last) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val lead = line.length - line.trimStart().length
            if (lead != indent) continue
            val m = TOP_LEVEL_KEY.matchEntire(line.trim()) ?: continue
            out.putIfAbsent(m.groupValues[1], line)
        }
        return out
    }

    /** `records` 段的二级键（`records:` 下的 `scope/id:` 行）。 */
    fun recordKeys(lines: List<String>, range: IntRange): Set<String> {
        val out = LinkedHashSet<String>()
        val body = bodyRange(range)
        if (body.isEmpty()) return out
        val indent = lines.subList(body.first, body.last + 1)
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .minOfOrNull { it.length - it.trimStart().length } ?: return out
        for (i in body.first..body.last) {
            val line = lines[i]
            if (line.isBlank()) continue
            val lead = line.length - line.trimStart().length
            if (lead != indent) continue
            CHILD_KEY.matchEntire(line.trim())?.let { out.add(it.groupValues[1]) }
        }
        return out
    }

    /**
     * 合并两份凭据文档：**共享侧优先**，把 [incoming]（项目文件，可为扁平 layout）里共享缺失的
     * `refs` 键补入共享 `refs:` 段末尾、共享缺失的 `records` 条目整体追加。
     *
     * 共享侧已存在的任何内容都不改写（保留注释与格式）；只做"补缺"。
     */
    fun mergeCredentials(shared: String, incomingRaw: String): Pair<String, List<String>> {
        val incoming = upgradeFlatCredentials(incomingRaw)
        val sharedLines = shared.split("\n")
        val incLines = incoming.split("\n")
        val refsRange = topLevelBlockRange(sharedLines, "refs")
        val recordsRange = topLevelBlockRange(sharedLines, "records")
        if (refsRange == null) return shared to emptyList() // 非 versioned 文档：不猜结构，保持原样

        val sharedRefs = refsRange?.let { childKeys(sharedLines, it) } ?: LinkedHashMap()
        val incRefs = topLevelBlockRange(incLines, "refs")?.let { childKeys(incLines, it) } ?: LinkedHashMap()
        val sharedRecords = recordsRange?.let { recordKeys(sharedLines, it) } ?: emptySet()
        val incRecRange = topLevelBlockRange(incLines, "records")

        val added = ArrayList<String>()
        val lines = sharedLines.toMutableList()

        // 1. refs 补缺：插入到共享 refs 段末尾
        val missingRefs = incRefs.filterKeys { it !in sharedRefs }
        if (missingRefs.isNotEmpty() && refsRange != null) {
            val body = bodyRange(refsRange)
            val indent = if (!body.isEmpty()) {
                val existing = lines.subList(body.first, body.last + 1)
                    .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
                existing?.takeWhile { it == ' ' || it == '\t' }?.takeIf { it.isNotEmpty() } ?: "  "
            } else "  "
            lines.addAll(refsRange.last + 1, missingRefs.values.map { "$indent${it.trim()}" })
            added.addAll(missingRefs.keys)
        }

        // 2. records 补缺：整段追加（共享无 records）或逐条追加（共享有 records）
        if (incRecRange != null) {
            val incRecText = incLines.subList(incRecRange.first, incRecRange.last + 1).joinToString("\n")
            if (recordsRange == null) {
                if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
                lines.add(incRecText)
                added.add("records")
            } else {
                val sharedRecRangeNow = topLevelBlockRange(lines, "records") ?: recordsRange
                val missingKeys = recordKeys(incLines, incRecRange).filter { it !in sharedRecords }
                if (missingKeys.isNotEmpty()) {
                    val incBody = incLines.subList(incRecRange.first + 1, incRecRange.last + 1)
                    val indent = incBody.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                        .minOfOrNull { it.length - it.trimStart().length } ?: 2
                    val picked = ArrayList<String>()
                    var i = 0
                    while (i < incBody.size) {
                        val line = incBody[i]
                        val key = TOP_LEVEL_KEY.matchEntire(line.trim())?.groupValues?.get(1)
                        if (key != null && key in missingKeys) {
                            picked.add(line)
                            i++
                            // 连带该条目的缩进子行（直到下一个同缩进键）
                            while (i < incBody.size) {
                                val next = incBody[i]
                                if (next.isBlank()) { i++; continue }
                                val lead = next.length - next.trimStart().length
                                if (lead <= indent && TOP_LEVEL_KEY.matchEntire(next.trim()) != null) break
                                picked.add(next)
                                i++
                            }
                        } else i++
                    }
                    if (picked.isNotEmpty()) {
                        lines.addAll(sharedRecRangeNow.last + 1, picked)
                        added.addAll(missingKeys)
                    }
                }
            }
        }

        val text = lines.joinToString("\n").let { ensureTrailingNewline(it) }
        return text to added
    }

    private fun ensureTrailingNewline(s: String): String = if (s.endsWith("\n")) s else "$s\n"

    /**
     * 在凭据文档中插入/替换一个 `refs.<ref>` 值（**其它内容与注释原样保留**）。
     *
     * 写入语义：
     * - 空文档 → `version: 1` + `refs:` 新文档；
     * - dsh pre-release 扁平 layout → 内联升级为 `version: 1` 后追加该键；
     * - 已有 `version: 1` 文档 → 替换已存在的该键行（沿用原缩进）；不存在则插到 `refs:` 段末尾；
     *   连 `refs:` 段都没有则补一个。
     *
     * 为什么不用"整份覆盖"：dsh 用 `version: 1` + `refs`/`records` 承载凭据，`records` 里还有
     * `client-connection/browser-session` 这类 dsh 自己的记录，其它 provider 的密钥也在 `refs` 里。
     * v0.2.3 及以前的扁平整份覆盖会把它们全部抹掉。
     */
    fun upsertRef(text: String?, ref: String, value: String): String {
        val line = "$ref: $value"
        if (text.isNullOrBlank()) return "version: 1\nrefs:\n  $line\n"
        val normalized = if (isFlatCredentials(text)) upgradeFlatCredentials(text) else text
        val lines = normalized.split('\n').toMutableList()
        val idx = lines.indexOfFirst { it.trim().startsWith("$ref:") }
        if (idx >= 0) {
            val indent = lines[idx].takeWhile { it == ' ' || it == '\t' }.ifEmpty { "  " }
            lines[idx] = "$indent$line"
            return ensureTrailingNewline(lines.joinToString("\n"))
        }
        val refsIdx = lines.indexOfFirst { it.trimEnd() == "refs:" }
        if (refsIdx >= 0) {
            var end = refsIdx + 1
            while (end < lines.size) {
                val l = lines[end]
                if (l.isNotEmpty() && l[0] != ' ' && l[0] != '\t' && l[0] != '#') break
                end++
            }
            lines.add(end, "  $line")
            return ensureTrailingNewline(lines.joinToString("\n"))
        }
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("refs:")
        else lines.add("refs:")
        lines.add("  $line")
        return ensureTrailingNewline(lines.joinToString("\n"))
    }

    /** 文档中 `refs.<ref>`（或扁平 layout 的顶层该键）是否已是 [value]。 */
    fun hasRef(text: String?, ref: String, value: String): Boolean {
        if (text == null) return false
        return text.split('\n').any { line ->
            val t = line.trim()
            t.startsWith("$ref:") && t.substringAfter(':').trim().trim('"', '\'') == value
        }
    }
}

/**
 * 共享配置根迁移（v0.2.4，见 docs/DESIGN.md §4.4）。
 *
 * **背景**：v0.1.3-dev ~ v0.2.3 采用"全局配置 + 每项目副本"（方案 A）：`ensureHome` 每次启动用
 * `copyGlobalConfigTo` 把共享根的 `settings.yaml` / `.credentials.yaml` **覆盖**项目 DSH_HOME 的同名文件。
 * 后果：dsh 写进项目文件的用户配置（Web「Models」页新增的自定义 provider/模型 → `llm-pi-ai`；
 * 语言偏好 → `locale`）在下次启动被共享副本覆盖而**消失**。
 *
 * **本迁移**（幂等，仅执行一次，标记 `<共享根>/.plugin-layout-version`）：
 * 1. 把各项目 DSH_HOME 里 `settings.yaml` / `.credentials.yaml` 与共享文档**合并**（共享侧优先，只补缺）；
 * 2. 原文件**移入** `<共享根>/migrated/<md5(项目路径)前16位>/` 备份，不再放回项目目录（共享化后 dsh 不再读它）；
 * 3. 写标记，后续启动直接跳过。
 *
 * 迁移后 dsh 只读写共享根文档（由 `ide.yml` 的 `- id: settings` / `- id: credentials` patch 重定向），
 * 插件侧不再参与配置同步。
 */
object SharedConfigMigrator {

    private val LOG = Logger.getInstance(SharedConfigMigrator::class.java)

    /** 迁移完成标记文件名（写在共享根）。 */
    const val MARKER_FILE = ".plugin-layout-version"

    const val SETTINGS_FILE = "settings.yaml"
    const val CREDENTIALS_FILE = ".credentials.yaml"

    /** 备份目录名（共享根下）。 */
    const val MIGRATED_DIR = "migrated"

    /** 迁移结果（供调用方记日志/发通知）。 */
    data class Outcome(
        val ran: Boolean,
        val seededSettings: Boolean,
        /** 已迁移并备份的项目 DSH_HOME 目录名（`md5(项目路径)前16位`）。 */
        val migratedProjects: List<String>,
        /** 合并进共享文档的顶层 namespace（settings）。 */
        val mergedNamespaces: List<String>,
        /** 合并进共享凭据的 refs / records 键。 */
        val mergedCredentials: List<String>,
        val failures: List<String>,
    ) {
        companion object {
            val SKIPPED = Outcome(false, false, emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    /**
     * 幂等迁移。已写标记（且标记内容等于 [pluginVersion]）时直接返回 [Outcome.SKIPPED]。
     *
     * @param sharedRoot 共享配置根（`DshHomeManager.sharedConfigRoot()`）
     * @param projectHomes 共享根下所有每项目 DSH_HOME（调用方过滤 `[0-9a-f]{16}` 目录名）
     * @param pluginVersion 当前插件版本，写入标记
     */
    fun migrateIfNeeded(sharedRoot: Path, projectHomes: List<Path>, pluginVersion: String): Outcome {
        val marker = sharedRoot.resolve(MARKER_FILE)
        if (Files.isRegularFile(marker)) {
            val done = runCatching { Files.readString(marker, StandardCharsets.UTF_8).trim() }.getOrNull()
            if (done == pluginVersion) return Outcome.SKIPPED
            LOG.info("shared-config migration marker=$done, current=$pluginVersion; re-running")
        }

        val candidates = projectHomes.filter { dir ->
            val s = dir.resolve(SETTINGS_FILE)
            val c = dir.resolve(CREDENTIALS_FILE)
            (Files.isRegularFile(s, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(c, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
        if (candidates.isEmpty()) {
            writeMarker(marker, pluginVersion)
            return Outcome(false, false, emptyList(), emptyList(), emptyList(), emptyList())
        }

        // 先排空共享文档：不存在时用"namespace 最多的项目文件"做种子（尽力恢复用户配置）
        val sharedSettings = sharedRoot.resolve(SETTINGS_FILE)
        var seeded = false
        if (!Files.isRegularFile(sharedSettings)) {
            val richest = candidates
                .mapNotNull { p ->
                    val f = p.resolve(SETTINGS_FILE)
                    if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS)) null
                    else runCatching { Files.readString(f, StandardCharsets.UTF_8) }.getOrNull()
                        ?.takeIf { YamlText.hasContent(it) }
                        ?.let { YamlText.topLevelKeys(it).size to it }
                }
                .maxByOrNull { it.first }
            if (richest != null) {
                runCatching {
                    Files.createDirectories(sharedRoot)
                    Files.writeString(sharedSettings, richest.second, StandardCharsets.UTF_8)
                }.onSuccess {
                    seeded = true
                    LOG.info("seeded shared settings.yaml from a project copy (${richest.first} namespace(s))")
                }.onFailure { LOG.warn("failed to seed shared settings.yaml", it) }
            }
        }

        val migrated = ArrayList<String>()
        val mergedNs = LinkedHashSet<String>()
        val mergedCred = LinkedHashSet<String>()
        val failures = ArrayList<String>()

        for (home in candidates) {
            val name = home.fileName?.toString() ?: continue
            try {
                val backup = sharedRoot.resolve(MIGRATED_DIR).resolve(name)
                Files.createDirectories(backup)

                val projectSettings = home.resolve(SETTINGS_FILE)
                if (Files.isRegularFile(projectSettings, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    val incoming = Files.readString(projectSettings, StandardCharsets.UTF_8)
                    if (YamlText.hasContent(incoming) && Files.isRegularFile(sharedSettings)) {
                        val shared = Files.readString(sharedSettings, StandardCharsets.UTF_8)
                        val (merged, added) = YamlText.mergeMissingNamespaces(shared, YamlText.blocksOf(incoming)) {
                            "# merged by DeepSeek Harness from project $name"
                        }
                        if (added.isNotEmpty()) {
                            Files.writeString(sharedSettings, merged, StandardCharsets.UTF_8)
                            mergedNs.addAll(added)
                            LOG.info("merged settings namespace(s) $added from project $name into shared settings.yaml")
                        }
                    }
                    moveAside(projectSettings, backup.resolve(SETTINGS_FILE))
                }

                val projectCredentials = home.resolve(CREDENTIALS_FILE)
                if (Files.isRegularFile(projectCredentials, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    val incoming = Files.readString(projectCredentials, StandardCharsets.UTF_8)
                    val sharedCredentials = sharedRoot.resolve(CREDENTIALS_FILE)
                    if (YamlText.hasContent(incoming)) {
                        if (!Files.isRegularFile(sharedCredentials)) {
                            Files.writeString(sharedCredentials, YamlText.upgradeFlatCredentials(incoming), StandardCharsets.UTF_8)
                            mergedCred.add("*")
                        } else {
                            val shared = Files.readString(sharedCredentials, StandardCharsets.UTF_8)
                            val (merged, added) = YamlText.mergeCredentials(shared, incoming)
                            if (added.isNotEmpty()) {
                                Files.writeString(sharedCredentials, merged, StandardCharsets.UTF_8)
                                mergedCred.addAll(added)
                                LOG.info("merged credentials ${added} from project $name into shared .credentials.yaml")
                            }
                        }
                    }
                    moveAside(projectCredentials, backup.resolve(CREDENTIALS_FILE))
                }

                migrated.add(name)
            } catch (e: Exception) {
                LOG.warn("shared-config migration failed for project dir $home", e)
                failures.add(name)
            }
        }

        if (failures.isEmpty()) writeMarker(marker, pluginVersion) else LOG.warn("shared-config migration had failures: $failures; marker not written (will retry next start)")

        return Outcome(
            ran = true,
            seededSettings = seeded,
            migratedProjects = migrated,
            mergedNamespaces = mergedNs.toList(),
            mergedCredentials = mergedCred.toList(),
            failures = failures,
        )
    }

    /** 共享根残留清理（幂等）：清掉 v0.1.2 时代遗留的 profile / MCP 脚本 / junction。 */
    fun cleanLegacySharedRoot(sharedRoot: Path) {
        cleanup(sharedRoot.resolve("profiles"))
        cleanup(sharedRoot.resolve("node_modules"))
        cleanup(sharedRoot.resolve("mcp-ide-server.mjs"))
        cleanup(sharedRoot.resolve("ide.yml"))
        cleanup(sharedRoot.resolve("cordis.patch.yml"))
    }

    private fun cleanup(target: Path) {
        if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        try {
            deleteRecursively(target)
            LOG.info("removed stale entry from shared config root: $target")
        } catch (e: Exception) {
            LOG.warn("failed to remove stale entry $target", e)
        }
    }

    /** 递归删除；**junction/符号链接直接断链删除，绝不跟随**（否则会清空运行时树，见 PROJECT_NOTES §4）。 */
    private fun deleteRecursively(target: Path) {
        val attrs = Files.readAttributes(
            target, java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        if (attrs.isSymbolicLink || attrs.isOther || attrs.isRegularFile) {
            Files.deleteIfExists(target)
            return
        }
        Files.newDirectoryStream(target).use { entries -> entries.forEach { deleteRecursively(it) } }
        Files.deleteIfExists(target)
    }

    private fun moveAside(from: Path, to: Path) {
        try {
            Files.createDirectories(to.parent)
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING) }
                .onSuccess { runCatching { Files.deleteIfExists(from) } }
                .onFailure { LOG.warn("failed to back up $from -> $to", e) }
        }
    }

    private fun writeMarker(marker: Path, pluginVersion: String) {
        runCatching {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, pluginVersion + "\n", StandardCharsets.UTF_8)
        }.onFailure { LOG.warn("failed to write shared-config marker $marker", it) }
    }
}
