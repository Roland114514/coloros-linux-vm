package com.oppo.coloros.linux.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ColorOS 17 与 Linux 双向剪贴板无缝实时同步桥接
 * 无论在哪个系统复制文字，切到另一个系统即可直接 Ctrl+V / 粘贴
 */
class ClipboardBridge(private val context: Context) : ClipboardManager.OnPrimaryClipChangedListener {

    companion object {
        private const val TAG = "ClipboardBridge"
    }

    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isInternalUpdate = AtomicBoolean(false)

    @Volatile
    private var activeWriter: DataOutputStream? = null
    private var listenerJob: Job? = null
    private var lastSyncedText: String = ""

    fun start() {
        clipboardManager.addPrimaryClipChangedListener(this)
        startSocketClientLoop()
    }

    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(this)
        listenerJob?.cancel()
        try {
            activeWriter?.close()
        } catch (_: Exception) {}
        activeWriter = null
    }

    override fun onPrimaryClipChanged() {
        if (isInternalUpdate.get()) return

        val clip = clipboardManager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty() || text == lastSyncedText) return

        lastSyncedText = text
        sendToLinux(text)
    }

    private fun sendToLinux(text: String) {
        scope.launch {
            val out = activeWriter ?: return@launch
            try {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                synchronized(out) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                    out.flush()
                }
                Log.d(TAG, "已同步 ColorOS 剪贴板至 Linux (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "发送剪贴板数据到 Linux 失败: ${e.message}")
            }
        }
    }

    private fun startSocketClientLoop() {
        val tmpDir = File(context.cacheDir, "linux-tmp")
        val socketFile = File(tmpDir, ".clip_ipc.sock")

        listenerJob = scope.launch {
            while (isActive) {
                if (!socketFile.exists()) {
                    delay(1500)
                    continue
                }

                val socket = LocalSocket()
                try {
                    val address = LocalSocketAddress(
                        socketFile.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM
                    )
                    socket.connect(address)
                    Log.i(TAG, "成功连接至 Linux 剪贴板守护进程: ${socketFile.absolutePath}")

                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    activeWriter = output

                    while (isActive) {
                        val length = input.readInt()
                        if (length <= 0 || length > 10 * 1024 * 1024) { // 限制最大 10MB 文本
                            break
                        }
                        val buffer = ByteArray(length)
                        input.readFully(buffer)
                        val linuxText = String(buffer, StandardCharsets.UTF_8)

                        if (linuxText.isNotEmpty() && linuxText != lastSyncedText) {
                            updateAndroidClipboard(linuxText)
                        }
                    }
                } catch (e: Exception) {
                    // 守护进程尚未就绪或连接断开，静默重试
                } finally {
                    activeWriter = null
                    try {
                        socket.close()
                    } catch (_: Exception) {}
                }
                delay(2000)
            }
        }
    }

    private fun updateAndroidClipboard(text: String) {
        mainHandler.post {
            try {
                isInternalUpdate.set(true)
                lastSyncedText = text
                val clip = ClipData.newPlainText("Linux Clipboard", text)
                clipboardManager.setPrimaryClip(clip)
                Log.d(TAG, "已同步 Linux 剪贴板至 ColorOS (${text.length} chars)")
            } catch (e: Exception) {
                Log.e(TAG, "写入 ColorOS 剪贴板失败", e)
            } finally {
                mainHandler.postDelayed({ isInternalUpdate.set(false) }, 150)
            }
        }
    }
}
