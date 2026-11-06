package com.oppo.coloros.linux.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.oppo.coloros.linux.R
import com.oppo.coloros.linux.bridge.InputEventBridge
import com.oppo.coloros.linux.core.NativeDisplayBridge
import com.oppo.coloros.linux.service.FloatingSwitchService

class LinuxDisplayActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView

    private val minimizeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingSwitchService.ACTION_MINIMIZE_LINUX) {
                // 收到悬浮球点击指令，立即将 Linux 退回后台，不销毁实例，实现毫秒级切换
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    surfaceView.releasePointerCapture()
                }
                moveTaskToBack(true)
                overridePendingTransition(0, 0)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreenAnd120Hz()
        setContentView(R.layout.activity_linux_display)

        surfaceView = findViewById(R.id.linux_surface_view)
        surfaceView.holder.addCallback(this)
        surfaceView.isFocusable = true
        surfaceView.isFocusableInTouchMode = true
        surfaceView.requestFocus()

        setupInputForwarding()

        val filter = IntentFilter(FloatingSwitchService.ACTION_MINIMIZE_LINUX)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(minimizeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(minimizeReceiver, filter)
        }
    }

    private fun setupFullscreenAnd120Hz() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 请求 ColorOS 开启 120Hz 最高刷新率档位
        val display = display
        val supportedModes = display?.supportedModes
        val highestRefreshMode = supportedModes?.maxByOrNull { it.refreshRate }
        highestRefreshMode?.let { mode ->
            val params = window.attributes
            params.preferredDisplayModeId = mode.modeId
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            window.attributes = params
        }

        // 彻底隐藏状态栏与导航手势小白条
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInputForwarding() {
        // 触控与手写笔事件转发
        surfaceView.setOnTouchListener { _, event ->
            InputEventBridge.onTouchEvent(event)
        }

        // 鼠标滚轮、相对移动与手写笔 Hover 悬浮事件转发
        surfaceView.setOnGenericMotionListener { _, event ->
            InputEventBridge.onGenericMotionEvent(event)
        }

        // 单击自动请求指针捕获（当连接蓝牙鼠标或触控板时独占光标）
        surfaceView.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!surfaceView.hasPointerCapture()) {
                    surfaceView.requestPointerCapture()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 拦截并转发物理键盘（含 Alt+Tab、Esc、Ctrl、Super 等快捷键）直通 Linux X11/evdev
        if (InputEventBridge.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        FloatingSwitchService.isLinuxForeground = true
        try {
            NativeDisplayBridge.setTargetFps(120)
        } catch (_: UnsatisfiedLinkError) {}
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
        FloatingSwitchService.isLinuxForeground = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            surfaceView.releasePointerCapture()
        }
        // 退回 ColorOS 时将 X11 渲染限制为 1 FPS 极低功耗保活
        try {
            NativeDisplayBridge.setTargetFps(1)
        } catch (_: UnsatisfiedLinkError) {}
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val dpi = resources.displayMetrics.densityDpi
        try {
            NativeDisplayBridge.attachSurface(holder.surface, width, height, dpi)
        } catch (_: UnsatisfiedLinkError) {}
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            NativeDisplayBridge.detachSurface()
        } catch (_: UnsatisfiedLinkError) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(minimizeReceiver)
    }
}
