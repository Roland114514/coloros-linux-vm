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
import com.oppo.coloros.linux.core.IRuntimeEngine
import com.oppo.coloros.linux.core.PRootEngine
import com.oppo.coloros.linux.ui.LinuxDisplayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LinuxHostService : Service() {

    companion object {
        private const val TAG = "LinuxHostService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "coloros_linux_daemon_channel"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wakeLock: PowerManager.WakeLock
    lateinit var runtimeEngine: IRuntimeEngine
        private set

    inner class LocalBinder : Binder() {
        fun getService(): LinuxHostService = this@LinuxHostService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        runtimeEngine = PRootEngine(applicationContext)
        acquirePartialWakeLock()
        startForegroundDaemon()
        bootLinuxEnvironment()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
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

    private fun startForegroundDaemon() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ubuntu 24.04 Container Daemon",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Linux Desktop and background compilation tasks alive on ColorOS 17"
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

    private fun bootLinuxEnvironment() {
        serviceScope.launch {
            runtimeEngine.bootContainer { logLine ->
                Log.d(TAG, "[Container] $logLine")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runtimeEngine.shutdownContainer()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        serviceScope.cancel()
    }
}
