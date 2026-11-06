package com.oppo.coloros.linux.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class RootfsInstaller(private val context: Context) {

    companion object {
        private const val TAG = "RootfsInstaller"
        const val ROOTFS_DIR_NAME = "ubuntu-rootfs"
        const val MARKER_FILE_NAME = ".installed"
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
        get() = File(rootfsDir, MARKER_FILE_NAME).exists()

    /**
     * 首次运行异步解包与环境初始化
     */
    suspend fun installIfNeeded(
        assetFileName: String = "rootfs/ubuntu-noble-xfce4-arm64.tar.xz"
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isInstalled) {
            _progress.value = InstallProgress.Completed
            return@withContext Result.success(Unit)
        }

        try {
            _progress.value = InstallProgress.InProgress(5, "正在校验存储与释放目录...")
            if (!rootfsDir.exists()) {
                rootfsDir.mkdirs()
            }

            // 检查 assets 中是否有内嵌镜像包
            val hasAsset = try {
                context.assets.list("rootfs")?.contains("ubuntu-noble-xfce4-arm64.tar.xz") == true
            } catch (e: Exception) {
                false
            }

            if (!hasAsset) {
                Log.w(TAG, "未在 assets 中检测到预置镜像包，生成骨架引导环境...")
                // 创建极简虚拟根文件系统目录树
                createSkeletonRootfs(rootfsDir)
                File(rootfsDir, MARKER_FILE_NAME).createNewFile()
                _progress.value = InstallProgress.Completed
                return@withContext Result.success(Unit)
            }

            _progress.value = InstallProgress.InProgress(15, "正在解压 Ubuntu 24.04 根文件系统...")
            context.assets.open(assetFileName).use { inputStream ->
                val tempTar = File(context.cacheDir, "temp_rootfs.tar.xz")
                copyStreamWithProgress(inputStream, tempTar) { p ->
                    _progress.value = InstallProgress.InProgress(15 + (p * 0.4).toInt(), "正在解包镜像数据 ($p%)...")
                }

                _progress.value = InstallProgress.InProgress(60, "正在写入目录并配置权限...")
                extractTarArchive(tempTar, rootfsDir)
                tempTar.delete()
            }

            // 写入完成标志
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

    private fun createSkeletonRootfs(targetDir: File) {
        val subDirs = listOf(
            "bin", "etc", "dev", "proc", "sys", "tmp", "root",
            "home/oppo", "usr/bin", "usr/lib", "usr/local/bin", "var/log"
        )
        for (sub in subDirs) {
            File(targetDir, sub).mkdirs()
        }
        // 创建基础配置文件
        File(targetDir, "etc/hostname").writeText("oppo-pad\n")
        File(targetDir, "etc/hosts").writeText("127.0.0.1 localhost oppo-pad\n")
    }

    private fun copyStreamWithProgress(
        input: InputStream,
        targetFile: File,
        onProgress: (Int) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var totalBytesRead = 0L
        val estimatedSize = 350 * 1024 * 1024L // 约 350MB 预估体积

        FileOutputStream(targetFile).use { output ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                val percent = ((totalBytesRead.toFloat() / estimatedSize) * 100).toInt().coerceIn(0, 100)
                onProgress(percent)
            }
            output.flush()
        }
    }

    private fun extractTarArchive(tarFile: File, destDir: File) {
        val process = ProcessBuilder("tar", "-xf", tarFile.absolutePath, "-C", destDir.absolutePath)
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorOutput = process.inputStream.bufferedReader().readText()
            Log.w(TAG, "tar command exited with $exitCode: $errorOutput")
        }
    }

    fun cleanRootfs(): Boolean {
        return rootfsDir.deleteRecursively()
    }
}
