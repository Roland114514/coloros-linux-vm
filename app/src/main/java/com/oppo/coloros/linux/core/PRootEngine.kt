package com.oppo.coloros.linux.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Field

/**
 * 负责启动与管理 PRoot Linux 容器进程树 (工业级高鲁棒性版)
 * 采用 setsid 独立会话组、双重 PID 提取兜底与 kill(-pgid, SIGKILL) 级联终止机制
 */
class PRootEngine(private val context: Context) : IRuntimeEngine {

    companion object {
        private const val TAG = "PRootEngine"
    }

    private val rootfsDir = File(context.filesDir, "ubuntu-rootfs")
    private val tmpDir = File(context.cacheDir, "linux-tmp")
    private val nativeLibDir = context.applicationInfo.nativeLibraryDir
    private val prootBin = File(nativeLibDir, "libproot.so")
    private val pidFile = File(tmpDir, "container.pid")

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
            if (pidFile.exists()) pidFile.delete()

            // 启动脚本：配置虚拟音频声卡、PulseAudio Simple 协议直通、启动剪贴板守护进程与 XFCE4
            val launchScript = """
                echo $$ > /tmp/container.pid
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
                mkdir -p /tmp/.X11-unix /run/dbus /home/oppo
                chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true
                
                echo "[INIT] Configuring Virtual Null Sink & PulseAudio Simple TCP Server on 4713..."
                pulseaudio --start --exit-idle-time=-1 --disallow-exit \
                    -L "module-null-sink sink_name=ColorOS_Sink sink_properties=device.description=ColorOS_Virtual_Output" \
                    -L "module-simple-protocol-tcp rate=44100 format=s16le channels=2 source=ColorOS_Sink.monitor record=true port=4713 listen=127.0.0.1" 2>/dev/null || true
                pactl set-default-sink ColorOS_Sink 2>/dev/null || true
                
                if [ -f /usr/local/bin/clip_daemon.py ]; then
                    echo "[INIT] Starting Clipboard Sync Daemon..."
                    python3 /usr/local/bin/clip_daemon.py >/dev/null 2>&1 &
                fi
                
                echo "[INIT] Launching XFCE4 Session..."
                exec dbus-launch --exit-with-session startxfce4
            """.trimIndent()

            val command = mutableListOf(
                "setsid",
                prootBin.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "${tmpDir.absolutePath}:/tmp"
            )

            // 安全挂载外部存储：若 /sdcard 不可读则优雅回退至应用专属外置目录，防挂起闪退
            val sdcard = File("/sdcard")
            if (sdcard.exists() && sdcard.canRead()) {
                command.addAll(listOf("-b", "/sdcard:/mnt/sdcard"))
            } else {
                val appExternalDir = context.getExternalFilesDir(null)
                if (appExternalDir != null && appExternalDir.exists()) {
                    command.addAll(listOf("-b", "${appExternalDir.absolutePath}:/mnt/sdcard"))
                }
            }

            command.addAll(listOf(
                "-w", "/root",
                "/bin/bash", "-c", launchScript
            ))

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

    /**
     * 三级高鲁棒性 PID 提取算法（完全合规 Android 15+）
     */
    private fun extractPid(process: Process): Int {
        // 尝试 1: Java 9 / Android API 26+ Process.pid() 方法
        try {
            val pidMethod = Process::class.java.getMethod("pid")
            val pid = (pidMethod.invoke(process) as? Long)?.toInt() ?: -1
            if (pid > 0) return pid
        } catch (_: Throwable) {}

        // 尝试 2: 反射 pid 字段 (兼容旧版 Android)
        try {
            val pidField: Field = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            val pid = pidField.getInt(process)
            if (pid > 0) return pid
        } catch (_: Throwable) {}

        // 尝试 3: 从容器内 /tmp/container.pid 读取真实会话 PGID（100% 免疫 ART 反射限制）
        for (retry in 1..25) {
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim().toIntOrNull() ?: -1
                if (pid > 0) return pid
            }
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
        }
        return -1
    }

    @Synchronized
    private fun cleanupProcessTree() {
        if (containerPid > 0) {
            try {
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
        if (pidFile.exists()) pidFile.delete()
        currentState = IRuntimeEngine.State.STOPPED
    }

    override fun setRenderFrameRate(fps: Int) {
        NativeDisplayBridge.setTargetFps(fps)
    }

    override fun shutdownContainer() {
        cleanupProcessTree()
    }

    private fun prepareDirectories() {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
    }
}
