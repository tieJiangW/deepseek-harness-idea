package com.deepseek.harness.idea.runtime

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 把用户选中的"本地运行时 zip"落到一个**必然可由 NIO 访问**的路径。
 *
 * 背景（v0.2.2 实测）：文件选择器返回的 [VirtualFile] 由 VFS 读取（一定可见），但其路径
 * 经 `Paths.get(...)` / `toNioPath()` 交给 `Files.exists` 时可能为 false
 * （远程/虚拟文件系统、安全软件拦截等），从而被误判为"不是有效的运行时 zip"
 * （错误卡实测：`path=…\runtime-win-x64.zip; isRegularFile=false; exists=false`）。
 *
 * 策略：
 * 1. 优先直接用 VFS 对应的 io 路径（`VfsUtilCore.virtualToIoFile`，仍是本地常规文件时）；
 * 2. 否则通过 VFS 输入流把 zip 复制到 `<config>/dsh-idea/imported-runtime.zip` 再使用
 *    （复制发生在后台线程；103MB 量级可接受）。
 */
object RuntimeZipStaging {

    private val LOG = Logger.getInstance(RuntimeZipStaging::class.java)

    /** 解析出可供 [RuntimeProvisioner.provisionFromLocal] 使用的 zip 路径；失败返回 null。 */
    fun resolve(file: VirtualFile): Path? {
        val io = runCatching { VfsUtilCore.virtualToIoFile(file) }.getOrNull()
        if (io != null && io.isFile) return io.toPath()

        LOG.info("runtime zip not reachable via NIO (vfs=${file.path}, io=${io?.absolutePath}); staging through VFS")
        return try {
            val tmp = PathManager.getConfigDir().resolve("dsh-idea").resolve("imported-runtime.zip")
            Files.createDirectories(tmp.parent)
            file.inputStream.use { input ->
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            }
            val size = runCatching { Files.size(tmp) }.getOrElse { -1L }
            LOG.info("staged runtime zip from VFS: ${file.path} -> $tmp ($size bytes)")
            tmp
        } catch (e: Exception) {
            LOG.warn("failed to stage runtime zip from VFS: ${file.path}", e)
            null
        }
    }
}
