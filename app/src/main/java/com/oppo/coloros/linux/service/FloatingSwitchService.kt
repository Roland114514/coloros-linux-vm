package com.oppo.coloros.linux.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.oppo.coloros.linux.R
import com.oppo.coloros.linux.ui.LinuxDisplayActivity
import kotlin.math.abs

class FloatingSwitchService : Service() {

    companion object {
        // 记录当前是否处于 Linux 全屏前台状态
        @Volatile
        var isLinuxForeground: Boolean = false
        
        const val ACTION_MINIMIZE_LINUX = "com.oppo.coloros.linux.ACTION_MINIMIZE_LINUX"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private var touchSlop = 0

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        updateScreenDimensions()
        initFloatingView()
    }

    private fun updateScreenDimensions() {
        val bounds = windowManager.currentWindowMetrics.bounds
        screenWidth = bounds.width()
        screenHeight = bounds.height()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun initFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_ball, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth
            y = screenHeight / 3
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        floatingView.setOnTouchListener { v, event ->
            handleTouch(v, event)
        }

        windowManager.addView(floatingView, layoutParams)
        fadeToIdleState()
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateScreenDimensions()
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false

                // 触控瞬间立刻恢复高亮度不透明并轻微放大
                view.animate().alpha(1.0f).scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }

                if (isDragging) {
                    layoutParams.x = (initialX + dx).coerceIn(0, screenWidth - view.width)
                    layoutParams.y = (initialY + dy).coerceIn(0, screenHeight - view.height)
                    windowManager.updateViewLayout(floatingView, layoutParams)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                if (!isDragging) {
                    performOneClickSwitch()
                } else {
                    snapToNearestEdge(view.width)
                }
                fadeToIdleState()
                return true
            }
        }
        return false
    }

    /**
     * 核心功能：一键在 ColorOS 17 与 Linux 桌面之间极速切换
     */
    private fun performOneClickSwitch() {
        if (!isLinuxForeground) {
            // 当前在 ColorOS -> 切入 Linux 全屏桌面
            val intent = Intent(this, LinuxDisplayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
        } else {
            // 当前在 Linux -> 退回 ColorOS 桌面
            sendBroadcast(Intent(ACTION_MINIMIZE_LINUX).setPackage(packageName))
        }
    }

    /**
     * 释放手指后平滑吸附到屏幕左侧或右侧边缘
     */
    private fun snapToNearestEdge(viewWidth: Int) {
        val centerX = layoutParams.x + viewWidth / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - viewWidth

        ValueAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                if (floatingView.isAttachedToWindow) {
                    layoutParams.x = animator.animatedValue as Int
                    windowManager.updateViewLayout(floatingView, layoutParams)
                }
            }
            start()
        }
    }

    /**
     * 静置后变为 40% 透明度，避免遮挡平板视野
     */
    private fun fadeToIdleState() {
        floatingView.animate()
            .alpha(0.4f)
            .setStartDelay(1800)
            .setDuration(400)
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            windowManager.removeView(floatingView)
        }
    }
}
