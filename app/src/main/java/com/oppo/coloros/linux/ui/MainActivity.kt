package com.oppo.coloros.linux.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.oppo.coloros.linux.R
import com.oppo.coloros.linux.core.NativeDisplayBridge
import com.oppo.coloros.linux.service.FloatingSwitchService
import com.oppo.coloros.linux.service.LinuxHostService

class MainActivity : AppCompatActivity(), LinuxHostService.StateListener {

    private var hostService: LinuxHostService? = null
    private var isBound = false

    private lateinit var tvContainerStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var statusIndicator: android.view.View
    private lateinit var btnToggleContainer: MaterialButton
    private lateinit var btnBatteryOpt: MaterialButton
    private lateinit var btnEnterDesktop: MaterialButton
    private lateinit var switchFloatingBall: MaterialSwitch
    private lateinit var switchHighRefresh: MaterialSwitch

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as LinuxHostService.LocalBinder
            hostService = binder.getService()
            isBound = true
            hostService?.registerStateListener(this@MainActivity)
            updateContainerUI(hostService?.isContainerRunning() == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            hostService = null
            updateContainerUI(false)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
        if (Settings.canDrawOverlays(this)) {
            startFloatingBallService()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updatePermissionStatus()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, LinuxHostService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            hostService?.unregisterStateListener(this)
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun initViews() {
        tvContainerStatus = findViewById(R.id.tv_container_status)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        statusIndicator = findViewById(R.id.status_indicator)
        btnToggleContainer = findViewById(R.id.btn_toggle_container)
        btnBatteryOpt = findViewById(R.id.btn_battery_opt)
        btnEnterDesktop = findViewById(R.id.btn_enter_desktop)
        switchFloatingBall = findViewById(R.id.switch_floating_ball)
        switchHighRefresh = findViewById(R.id.switch_high_refresh)

        // 1. 悬浮窗开关与权限管理
        switchFloatingBall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingBallService()
                } else {
                    switchFloatingBall.isChecked = false
                    requestOverlayPermission()
                }
            } else {
                stopService(Intent(this, FloatingSwitchService::class.java))
            }
        }

        // 2. 电池优化白名单申请
        btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        // 3. 120Hz 高刷锁定
        switchHighRefresh.setOnCheckedChangeListener { _, isChecked ->
            val targetFps = if (isChecked) 120 else 60
            try {
                NativeDisplayBridge.setTargetFps(targetFps)
            } catch (_: UnsatisfiedLinkError) {}
            Toast.makeText(this, "目标渲染帧率已设为: ${targetFps}Hz", Toast.LENGTH_SHORT).show()
        }

        // 4. 容器开关调度
        btnToggleContainer.setOnClickListener {
            val service = hostService
            if (service != null && service.isContainerRunning()) {
                service.stopLinuxEnvironment()
            } else {
                startLinuxService()
            }
        }

        // 5. 直达 Linux 全屏桌面
        btnEnterDesktop.setOnClickListener {
            if (hostService?.isContainerRunning() != true) {
                startLinuxService()
            }
            val intent = Intent(this, LinuxDisplayActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            overridePendingTransition(0, 0)
        }
    }

    private fun startLinuxService() {
        val serviceIntent = Intent(this, LinuxHostService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        hostService?.startLinuxEnvironment()

        if (Settings.canDrawOverlays(this) && !switchFloatingBall.isChecked) {
            switchFloatingBall.isChecked = true
        }
    }

    private fun startFloatingBallService() {
        val intent = Intent(this, FloatingSwitchService::class.java)
        startService(intent)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            batteryOptimizationLauncher.launch(intent)
        } else {
            Toast.makeText(this, "已处于省电白名单中", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val hasBatteryWhitelist = pm.isIgnoringBatteryOptimizations(packageName)

        tvOverlayStatus.text = if (hasOverlay) "已授权" else "未授权 (点击申请)"
        btnBatteryOpt.isEnabled = !hasBatteryWhitelist
        btnBatteryOpt.text = if (hasBatteryWhitelist) "已锁定后台不杀 (白名单)" else "申请省电白名单 (必选)"
    }

    private fun updateContainerUI(isRunning: Boolean) {
        runOnUiThread {
            if (isRunning) {
                tvContainerStatus.text = "运行中 (X11 & PulseAudio 正常)"
                statusIndicator.setBackgroundResource(android.R.color.holo_green_light)
                btnToggleContainer.text = "停止容器引擎"
                btnEnterDesktop.isEnabled = true
            } else {
                tvContainerStatus.text = "已停止"
                statusIndicator.setBackgroundResource(android.R.color.holo_red_light)
                btnToggleContainer.text = "启动容器引擎"
                btnEnterDesktop.isEnabled = false
            }
        }
    }

    override fun onStateChanged(isRunning: Boolean) {
        updateContainerUI(isRunning)
    }

    override fun onLogAppended(logLine: String) {
        // 后台日志接收
    }
}
