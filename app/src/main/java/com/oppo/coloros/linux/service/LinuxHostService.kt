package com.oppo.coloros.linux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oppo.coloros.linux.bridge.AudioStreamBridge
import com.oppo.coloros.linux.core.IRuntimeEngine
import com.oppo.coloros.linux.core.PRootEngine
import com.oppo.coloros.linux.core.RootfsInstaller
import com.oppo.coloros.linux.ui.LinuxDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

class LinuxHostService : Service() {

    companion object {
        private const val TAG = "LinuxHostService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "coloros_linux_daemon_channel"
    }

    interface StateListener {
        fun onStateChanged(isRunning: Boolean)
        fun onLogAppended(logLine: String)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wakeLock: PowerManager.WakeLock
    private var audioBridge: AudioStreamBridge? = null
    lateinit var rootfsInstaller: RootfsInstaller
        private set
    lateinit var runtimeEngine: IRuntimeEngine
        private set

    private val listeners = CopyOnWriteArrayList<StateListener>()
    private val logBuffer = StringBuilder()

    inner class LocalBinder : Binder() {
        fun getService(): LinuxHostService = this@LinuxHostService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        rootfsInstaller = RootfsInstaller(applicationContext)
        runtimeEngine = PRootEngine(applicationContext)

        acquirePartialWakeLock()
        startForegroundDaemon()
        startAudioBridge()
        startLinuxEnvironment()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun registerStateListener(listener: StateListener) {
        listeners.add(listener)
        listener.onStateChanged(isContainerRunning())
    }

    fun unregisterStateListener(listener: StateListener) {
        listeners.remove(listener)
    }

    fun isContainerRunning(): Boolean {
        return runtimeEngine.currentState == IRuntimeEngine.State.RUNNING
    }

    fun getLogHistory(): String = logBuffer.toString()

    fun startLinuxEnvironment() {
        serviceScope.launch(Dispatchers.IO) {
            notifyState(false)
            val installResult = rootfsInstaller.installIfNeeded()
            if (installResult.isSuccess) {
                runtimeEngine.bootContainer { logLine ->
                    appendLog(logLine)
                    if (isContainerRunning()) {
                        notifyState(true)
                    }
                }
                notifyState(isContainerRunning())
            } else {
                appendLog("[ERROR] Rootfs install failed: ${installResult.exceptionOrNull()?.message}")
                notifyState(false)
            }
        }
    }

    fun stopLinuxEnvironment() {
        runtimeEngine.shutdownContainer()
        notifyState(false)
    }

    private fun appendLog(line: String) {
        if (logBuffer.length > 50000) {
            logBuffer.delete(0, 10000)
        }
        logBuffer.append(line).append("\n")
        listeners.forEach { it.onLogAppended(line) }
    }

    private fun notifyState(running: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            listeners.forEach { it.onStateChanged(running) }
        }
    }

    private fun acquirePartialWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ColorOSLinux::ContainerKeepAliveWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.i(TAG, "CPU Partial WakeLock acquired to prevent ColorOS Hans freeze.")
    }

    private fun startAudioBridge() {
        audioBridge = AudioStreamBridge(port = 4713)
        audioBridge?.start()
    }

    private fun startForegroundDaemon() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ubuntu 24.04 Container Daemon",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Linux Desktop and background tasks alive on ColorOS 17"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val switchIntent = Intent(this, LinuxDisplayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, switchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ubuntu 24.04 LTS 正在运行")
            .setContentText("点击立即切入 Linux 桌面 | 悬浮球已就绪")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runtimeEngine.shutdownContainer()
        audioBridge?.stop()
        audioBridge = null

        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        serviceScope.cancel()
    }
}
