package com.oppo.coloros.linux.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class PRootEngine(private val context: Context) : IRuntimeEngine {

    companion object {
        private const val TAG = "PRootEngine"
    }

    @Volatile
    override var currentState: IRuntimeEngine.State = IRuntimeEngine.State.STOPPED
        private set

    private var prootProcess: Process? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 路径定义
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private val rootfsDir = File(context.filesDir, "ubuntu-rootfs")
    private val tmpDir = File(context.cacheDir, "linux-tmp")

    override suspend fun bootContainer(onLogOutput: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentState == IRuntimeEngine.State.RUNNING || currentState == IRuntimeEngine.State.STARTING) {
            return@withContext Result.success(Unit)
        }

        currentState = IRuntimeEngine.State.STARTING
        try {
            prepareDirectories()

            // APK 打包时将 proot 可执行文件命名为 libproot.so 以绕过 Android 10+ W^X (应用数据目录禁止执行) 限制
            val prootBinary = File(nativeLibDir, "libproot.so")
            if (!prootBinary.exists() || !prootBinary.canExecute()) {
                Log.w(TAG, "libproot.so not yet deployed in nativeLibDir, preparing environment placeholder.")
            }

            val command = buildPRootCommand(prootBinary.absolutePath)
            val processBuilder = ProcessBuilder(command).apply {
                directory(rootfsDir)
                redirectErrorStream(true)
                setupEnvironment(environment())
            }

            Log.i(TAG, "Starting PRoot container: ${command.joinToString(" ")}")
            prootProcess = processBuilder.start()
            currentState = IRuntimeEngine.State.RUNNING

            // 异步读取容器标准输出流
            engineScope.launch {
                prootProcess?.inputStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let {
                                Log.d(TAG, "[Ubuntu] $it")
                                onLogOutput(it)
                            }
                        }
                    }
                }
                Log.w(TAG, "PRoot process exited.")
                currentState = IRuntimeEngine.State.STOPPED
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to boot PRoot container", e)
            currentState = IRuntimeEngine.State.ERROR
            Result.failure(e)
        }
    }

    private fun prepareDirectories() {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        // 确保 X11 Unix Domain Socket 目录存在且权限正确
        val x11SocketDir = File(tmpDir, ".X11-unix")
        if (!x11SocketDir.exists()) x11SocketDir.mkdirs()
        x11SocketDir.setExecutable(true, false)
        x11SocketDir.setWritable(true, false)
        x11SocketDir.setReadable(true, false)
    }

    private fun buildPRootCommand(prootPath: String): List<String> {
        return listOf(
            prootPath,
            "--kill-on-exit",
            "--link2symlink",
            "-0", // 伪造 Root 身份 (fake root)
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${tmpDir.absolutePath}:/tmp",
            "-b", "/sdcard:/mnt/sdcard",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DISPLAY=:0",
            "PULSE_SERVER=tcp:127.0.0.1:4713",
            // 启用 Adreno Turnip + Zink 硬件加速管线
            "MESA_LOADER_DRIVER_OVERRIDE=zink",
            "TU_DEBUG=noconform",
            "GALLIUM_DRIVER=zink",
            "GDK_SCALE=2", // 适配 OPPO 平板高分屏 DPI
            "/bin/bash", "-c",
            "dbus-launch --exit-with-session startxfce4"
        )
    }

    private fun setupEnvironment(env: MutableMap<String, String>) {
        env.clear()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = File(nativeLibDir, "libproot-loader.so").absolutePath
        // 针对 Android 15/16 (ColorOS 17) Phantom Process 及 seccomp 策略规避
        env["PROOT_NO_SECCOMP"] = "1"
        env["LD_LIBRARY_PATH"] = nativeLibDir
    }

    override fun setRenderFrameRate(fps: Int) {
        Log.d(TAG, "Adjusting X11 Render Target FPS to: $fps")
        NativeDisplayBridge.setTargetFps(fps)
    }

    override fun shutdownContainer() {
        Log.i(TAG, "Shutting down PRoot container...")
        prootProcess?.destroyForcibly()
        prootProcess = null
        currentState = IRuntimeEngine.State.STOPPED
    }
}
