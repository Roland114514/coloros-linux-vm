package com.oppo.coloros.linux.core

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * 负责将 assets 中的 Linux Rootfs (.tar.gz) 释放并解压到应用私有目录
 * 主通道：调用 Android 系统原生 toybox tar -xzf（极速、完整保留硬链接与扩展属性）
 * 备用通道：当系统命令受限或 SELinux 拦截时，自动回退至内置流式 GZIP + POSIX USTAR 解压器
 */
class RootfsInstaller(private val context: Context) {

    companion object {
        private const val TAG = "RootfsInstaller"
        const val ROOTFS_DIR_NAME = "ubuntu-rootfs"
        const val MARKER_FILE_NAME = ".installed_v2"
        const val ASSET_ARCHIVE_NAME = "rootfs/ubuntu-noble-xfce4-arm64.tar.gz"
    }

    sealed class InstallProgress {
        object Idle : InstallProgress()
        data class InProgress(val percent: Int, val message: String) : InstallProgress()
        object Completed : InstallProgress()
        data class Error(val throwable: Throwable) : InstallProgress()
    }

    private val _progress = MutableStateFlow<InstallProgress>(InstallProgress.Idle)
    val progress: StateFlow<InstallProgress> = _progress

    val rootfsDir: File
        get() = File(context.filesDir, ROOTFS_DIR_NAME)

    val isInstalled: Boolean
        get() = File(rootfsDir, MARKER_FILE_NAME).exists() && File(rootfsDir, "bin/sh").exists()

    suspend fun installIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInstalled) {
            _progress.value = InstallProgress.Completed
            return@withContext Result.success(Unit)
        }

        try {
            _progress.value = InstallProgress.InProgress(5, "正在校验存储与释放目录...")
            if (!rootfsDir.exists()) rootfsDir.mkdirs()

            val hasAsset = try {
                context.assets.list("rootfs")?.contains("ubuntu-noble-xfce4-arm64.tar.gz") == true
            } catch (e: Exception) {
                false
            }

            if (!hasAsset) {
                Log.w(TAG, "未在 assets 中检测到预置镜像包，生成骨架引导环境...")
                createSkeletonRootfs(rootfsDir)
                File(rootfsDir, MARKER_FILE_NAME).createNewFile()
                _progress.value = InstallProgress.Completed
                return@withContext Result.success(Unit)
            }

            _progress.value = InstallProgress.InProgress(15, "正在从应用提取 Ubuntu 24.04 镜像...")
            val tempArchive = File(context.cacheDir, "temp_rootfs.tar.gz")

            context.assets.open(ASSET_ARCHIVE_NAME).use { input ->
                FileOutputStream(tempArchive).use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }

            _progress.value = InstallProgress.InProgress(40, "正在解压 Linux 根文件系统...")
            val nativeSuccess = extractWithSystemTar(tempArchive, rootfsDir)
            if (!nativeSuccess) {
                Log.w(TAG, "System toybox tar failed, falling back to pure JVM POSIX tar extractor...")
                _progress.value = InstallProgress.InProgress(50, "正在通过备用流式引擎解压...")
                extractWithJavaFallback(tempArchive, rootfsDir)
            }

            tempArchive.delete()
            configureBaseSystem()
            File(rootfsDir, MARKER_FILE_NAME).createNewFile()
            _progress.value = InstallProgress.Completed
            Log.i(TAG, "Rootfs installed successfully into ${rootfsDir.absolutePath}")
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "Rootfs install failed", t)
            _progress.value = InstallProgress.Error(t)
            Result.failure(t)
        }
    }

    private fun extractWithSystemTar(archive: File, destDir: File): Boolean {
        return try {
            val process = ProcessBuilder(
                "/system/bin/tar",
                "-xzf",
                archive.absolutePath,
                "-C",
                destDir.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.e(TAG, "System tar exited with code $exitCode: $output")
            }
            exitCode == 0 && File(destDir, "bin").exists()
        } catch (e: Exception) {
            Log.e(TAG, "Exception running system tar", e)
            false
        }
    }

    private fun extractWithJavaFallback(archive: File, destDir: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(archive), 65536)).use { gzipIn ->
            val block = ByteArray(512)
            var longName: String? = null
            var longLink: String? = null

            while (true) {
                var read = 0
                while (read < 512) {
                    val r = gzipIn.read(block, read, 512 - read)
                    if (r == -1) break
                    read += r
                }
                if (read < 512 || block.all { it == 0.toByte() }) break

                val rawName = readTarString(block, 0, 100)
                val prefix = readTarString(block, 345, 155)
                val size = readTarOctal(block, 124, 12)
                val mode = readTarOctal(block, 100, 8).toInt()
                val typeFlag = block[156].toInt().toChar()
                val rawLink = readTarString(block, 157, 100)

                if (typeFlag == 'L') {
                    longName = readTarPayloadAsString(gzipIn, size)
                    continue
                }
                if (typeFlag == 'K') {
                    longLink = readTarPayloadAsString(gzipIn, size)
                    continue
                }

                val entryName = longName ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName
                val linkName = longLink ?: rawLink
                longName = null
                longLink = null

                val cleanName = entryName.removePrefix("./").removePrefix("/")
                if (cleanName.isEmpty()) {
                    skipTarPadding(gzipIn, size)
                    continue
                }

                val outFile = File(destDir, cleanName)
                val destPrefix = if (destDir.canonicalPath.endsWith(File.separator)) destDir.canonicalPath else destDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(destPrefix)) {
                    Log.w(TAG, "Zip-Slip path traversal attempt blocked: $cleanName")
                    skipTarPadding(gzipIn, size)
                    continue
                }
                when (typeFlag) {
                    '5' -> outFile.mkdirs()
                    '2' -> { // Symlink
                        outFile.parentFile?.mkdirs()
                        if (outFile.exists()) outFile.delete()
                        try {
                            Os.symlink(linkName, outFile.absolutePath)
                        } catch (e: Exception) {
                            Log.w(TAG, "Symlink failed: ${outFile.absolutePath} -> $linkName")
                        }
                    }
                    '1' -> { // Hardlink
                        outFile.parentFile?.mkdirs()
                        val targetFile = File(destDir, linkName.removePrefix("./").removePrefix("/"))
                        if (outFile.exists()) outFile.delete()
                        try {
                            Os.link(targetFile.absolutePath, outFile.absolutePath)
                        } catch (e: Exception) {
                            if (targetFile.exists()) targetFile.copyTo(outFile, overwrite = true)
                        }
                    }
                    '0', '\u0000' -> { // Regular file
                        outFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
                            var remaining = size
                            val buf = ByteArray(8192)
                            while (remaining > 0) {
                                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                                val r = gzipIn.read(buf, 0, toRead)
                                if (r == -1) break
                                out.write(buf, 0, r)
                                remaining -= r
                            }
                        }
                        if ((mode and 0b001001001) != 0) {
                            outFile.setExecutable(true, false)
                        }
                        val padding = ((512 - (size % 512)) % 512)
                        skipFully(gzipIn, padding)
                    }
                    else -> skipTarPadding(gzipIn, size)
                }
            }
        }
    }

    private fun readTarString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readTarOctal(buf: ByteArray, offset: Int, length: Int): Long {
        val str = readTarString(buf, offset, length).trim()
        return if (str.isEmpty()) 0L else str.toLongOrNull(8) ?: 0L
    }

    private fun readTarPayloadAsString(input: GZIPInputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val r = input.read(bytes, read, bytes.size - read)
            if (r == -1) break
            read += r
        }
        val padding = ((512 - (size % 512)) % 512)
        skipFully(input, padding)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skipTarPadding(input: GZIPInputStream, size: Long) {
        val total = size + ((512 - (size % 512)) % 512)
        skipFully(input, total)
    }

    private fun skipFully(input: GZIPInputStream, bytes: Long) {
        var remaining = bytes
        val dummy = ByteArray(4096)
        while (remaining > 0) {
            val r = input.read(dummy, 0, minOf(remaining, dummy.size.toLong()).toInt())
            if (r <= 0) break
            remaining -= r
        }
    }

    private fun configureBaseSystem() {
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        val tmpDir = File(rootfsDir, "tmp").apply { mkdirs() }
        tmpDir.setWritable(true, false)
        tmpDir.setReadable(true, false)
        tmpDir.setExecutable(true, false)

        // 部署剪贴板全双工 IPC 守护脚本
        try {
            val binDir = File(rootfsDir, "usr/local/bin").apply { mkdirs() }
            val targetDaemon = File(binDir, "clip_daemon.py")
            context.assets.open("scripts/clip_daemon.py").use { input ->
                FileOutputStream(targetDaemon).use { output ->
                    input.copyTo(output)
                }
            }
            targetDaemon.setExecutable(true, false)
            Log.i(TAG, "Deployed clip_daemon.py into rootfs: ${targetDaemon.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Note: assets/scripts/clip_daemon.py not copied: ${e.message}")
        }
    }

    private fun createSkeletonRootfs(targetDir: File) {
        val subDirs = listOf("bin", "etc", "dev", "proc", "sys", "tmp", "root", "home/oppo", "usr/bin", "usr/lib")
        for (sub in subDirs) {
            File(targetDir, sub).mkdirs()
        }
        File(targetDir, "etc/hostname").writeText("oppo-pad\n")
        File(targetDir, "etc/hosts").writeText("127.0.0.1 localhost oppo-pad\n")
        File(targetDir, "bin/sh").writeText("#!/system/bin/sh\n")
        File(targetDir, "bin/sh").setExecutable(true, false)
    }
}
