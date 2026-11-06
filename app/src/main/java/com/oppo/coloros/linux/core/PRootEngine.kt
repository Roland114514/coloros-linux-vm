package com.oppo.coloros.linux.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Field

/**
 * 负责启动与管理 PRoot Linux 容器进程树
 * 采用 setsid 独立进程组与 kill(-pgid, SIGKILL) 级联终止机制，彻底根除孤儿进程泄漏
 */
class PRootEngine(private val context: Context) : IRuntimeEngine {

    companion object {
        private const val TAG = "PRootEngine"
    }

    private val rootfsDir = File(context.filesDir, "ubuntu-rootfs")
    private val tmpDir = File(context.cacheDir, "linux-tmp")
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private val prootBin = File(nativeLibDir, "libproot.so")

    private var containerProcess: Process? = null
    @Volatile
    private var containerPid: Int = -1
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    override var currentState: IRuntimeEngine.State = IRuntimeEngine.State.STOPPED
        private set

    override suspend fun bootContainer(onLogOutput: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        if (currentState == IRuntimeEngine.State.RUNNING || currentState == IRuntimeEngine.State.STARTING) {
            return@withContext Result.success(Unit)
        }

        currentState = IRuntimeEngine.State.STARTING
        try {
            prepareDirectories()

            // 启动脚本：配置 PulseAudio Simple 协议直通，启动 dbus 与极简 XFCE4
            val launchScript = """
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export TERM=xterm-256color
                export HOME=/root
                export USER=root
                export DISPLAY=:0
                export PULSE_SERVER=127.0.0.1:4713
                
                # 启用 Turnip Vulkan -> Zink OpenGL 硬件加速
                export MESA_LOADER_DRIVER_OVERRIDE=zink
                export TU_DEBUG=noconform
                export GALLIUM_DRIVER=zink
                export GDK_SCALE=2
                
                rm -rf /tmp/.X0-lock /tmp/.X11-unix/X0 2>/dev/null
                mkdir -p /tmp/.X11-unix /run/dbus
                
                echo "[INIT] Starting PulseAudio Simple Protocol TCP Server on 4713..."
                pulseaudio --start --exit-idle-time=-1 --disallow-exit \
                    -L "module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=0 record=true port=4713 listen=127.0.0.1" 2>/dev/null || true
                
                echo "[INIT] Launching XFCE4 Session..."
                dbus-launch --exit-with-session startxfce4
            """.trimIndent()

            val command = listOf(
                "setsid", // 创建独立会话组，便于后续按进程组直接整组终止
                prootBin.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${tmpDir.absolutePath}:/tmp",
                "-b", "/sdcard:/mnt/sdcard",
                "-w", "/root",
                "/bin/bash", "-c", launchScript
            )

            val pb = ProcessBuilder(command).apply {
                directory(rootfsDir)
                environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                environment()["PROOT_LOADER"] = File(nativeLibDir, "libproot-loader.so").absolutePath
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["LD_LIBRARY_PATH"] = nativeLibDir
                redirectErrorStream(true)
            }

            containerProcess = pb.start()
            containerPid = extractPid(containerProcess!!)
            currentState = IRuntimeEngine.State.RUNNING

            onLogOutput("[SYSTEM] PRoot container started (PGID: $containerPid)")
            Log.i(TAG, "PRoot container started with Process Group Leader PID: $containerPid")

            engineScope.launch {
                containerProcess?.inputStream?.let { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let {
                                onLogOutput(it)
                            }
                        }
                    }
                }
                Log.w(TAG, "Container process exited.")
                cleanupProcessTree()
                currentState = IRuntimeEngine.State.STOPPED
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to boot PRoot container", e)
            cleanupProcessTree()
            currentState = IRuntimeEngine.State.ERROR
            Result.failure(e)
        }
    }

    private fun extractPid(process: Process): Int {
        return try {
            val pidField: Field = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PID", e)
            -1
        }
    }

    @Synchronized
    private fun cleanupProcessTree() {
        if (containerPid > 0) {
            try {
                // 使用 setsid 后，containerPid 等同于 PGID，发送 -containerPid 将连根拔起子进程
                Log.i(TAG, "Sending SIGKILL to process group -$containerPid")
                Os.kill(-containerPid, OsConstants.SIGKILL)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to kill process group -$containerPid, fallback to direct kill", e)
                try {
                    Os.kill(containerPid, OsConstants.SIGKILL)
                } catch (_: Exception) {}
            }
            containerPid = -1
        }

        try {
            containerProcess?.destroyForcibly()
        } catch (_: Exception) {}
        containerProcess = null
        currentState = IRuntimeEngine.State.STOPPED
    }

    override fun setRenderFrameRate(fps: Int) {
        NativeDisplayBridge.setTargetFps(fps)
    }

    override fun shutdownContainer() {
        Log.i(TAG, "Shutting down PRoot container...")
        cleanupProcessTree()
    }

    private fun prepareDirectories() {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        File(tmpDir, ".X0-lock").delete()
        File(tmpDir, ".X11-unix/X0").delete()
    }
}
