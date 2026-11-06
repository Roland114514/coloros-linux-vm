package com.oppo.coloros.linux.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.oppo.coloros.linux.R
import com.oppo.coloros.linux.bridge.InputEventBridge
import com.oppo.coloros.linux.core.NativeDisplayBridge

/**
 * 专为 Type-C / HDMI 外接大屏设计的独立 Presentation 容器
 * 具备独立全屏硬件加速 SurfaceView，支持外接显示器 1080P/2K/4K 原生输出
 */
class LinuxPresentation(
    outerContext: Context,
    display: Display,
    private val onSurfaceReady: (surface: Surface, width: Int, height: Int) -> Unit,
    private val onSurfaceDestroyed: () -> Unit
) : Presentation(outerContext, display), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window?.let { win ->
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            win.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            win.setDecorFitsSystemWindows(false)
        }

        setContentView(R.layout.presentation_linux_display)

        surfaceView = findViewById(R.id.external_linux_surface)
        surfaceView.holder.addCallback(this)
        surfaceView.requestFocus()

        // 支持带触控功能的外接便携显示器
        surfaceView.setOnTouchListener { _, event ->
            InputEventBridge.onTouchEvent(event)
        }

        // 鼠标滚轮与悬浮
        surfaceView.setOnGenericMotionListener { _, event ->
            InputEventBridge.onGenericMotionEvent(event)
        }

        hideSystemBars()
    }

    private fun hideSystemBars() {
        window?.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (holder.surface != null && holder.surface.isValid) {
            onSurfaceReady(holder.surface, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurfaceDestroyed()
    }
}
