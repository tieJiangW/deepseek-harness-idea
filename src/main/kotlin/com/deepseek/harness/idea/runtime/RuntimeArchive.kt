package com.deepseek.harness.idea.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 运行时归档工具：SHA-256 计算 + 安全解压（zip-slip 防护 + 顶层单目录前缀剥离）。
 *
 * 提供/强化的压缩逻辑原在 [DshHomeManager] 私有方法内，抽出来供运行时下发
 * （[RuntimeProvisioner]）与内置运行时解压共用，并便于单测。
 */
object RuntimeArchive {

    /** 计算文件 SHA-256（大写 hex，与 Node 侧车 `.sha256` / `$NodeSha256` 校验值口径一致）。 */
    fun sha256(path: Path): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    /**
     * 校验一个 zip 是否为可用的运行时包：解压（含单层前缀剥离后）应包含
     * `node/<nodeBinName>` 与 `dsh/node_modules/@deepseek-ai/dsh/lib/bin.js`。
     * 供本地运行时 zip 导入前校验（[RuntimeProvisioner.provisionFromLocal]）。
     */
    fun validateRuntimeZip(zip: Path): Boolean = try {
        ZipFile(zip.toFile()).use { zf ->
            val fileEntries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            val topPrefix = fileEntries.mapNotNull { entry ->
                entry.name.split('/').firstOrNull()?.takeIf { it.isNotEmpty() }
            }.distinct().let { if (it.size == 1) it.first() + "/" else "" }
            val hasNode = fileEntries.any { it.name.removePrefix(topPrefix) == "node/${Platform.current().nodeBinName}" }
            val hasDsh = fileEntries.any { it.name.removePrefix(topPrefix) == "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js" }
            hasNode && hasDsh
        }
    } catch (e: Exception) {
        false
    }

    /**
     * 安全解压 zip 到 [dest]（幂等：可重复解压）。
     * - 兼容 zip 顶层带单目录前缀（如 `runtime/`）的情况：剥掉第一层；
     * - zip-slip 防护：目标必须位于 `dest` 下，否则跳过；
     * - Unix 下为 node 可执行文件补上可执行位。
     */
    fun unzip(zip: Path, dest: Path) {
        ZipFile(zip.toFile()).use { zf ->
            val entries = zf.entries().asSequence().filter { !it.isDirectory }.toList()
            val topPrefix = entries.mapNotNull { entry ->
                entry.name.split('/').firstOrNull()?.takeIf { it.isNotEmpty() }
            }.distinct().let { if (it.size == 1) it.first() + "/" else "" }

            for (entry in entries) {
                val rel = entry.name.removePrefix(topPrefix)
                val out = dest.resolve(rel).normalize()
                // 防 zip-slip
                if (!out.startsWith(dest)) continue
                Files.createDirectories(out.parent)
                zf.getInputStream(entry).use { input ->
                    Files.copy(input, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                if (isNodeBinRelative(rel)) makeExecutable(out)
            }
        }
    }

    private fun isNodeBinRelative(rel: String): Boolean = rel.startsWith("node/") && !rel.endsWith("/") && rel.substringAfterLast('/') == Platform.current().nodeBinName

    private fun makeExecutable(path: Path) {
        if (Platform.current().os == Platform.Os.WINDOWS) return
        try {
            path.toFile().setExecutable(true, false)
        } catch (e: IOException) {
            // 非致命：unix 上缺可执行位可能可运行，或由下次修复
        }
    }
}
