package com.oppo.coloros.linux.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import com.oppo.coloros.linux.R
import com.oppo.coloros.linux.ui.LinuxDisplayActivity
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class FloatingSwitchService : Service() {

    companion object {
        private const val TAG = "FloatingSwitchService"
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
    private var edgeAnimator: ValueAnimator? = null
    private var isViewAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 权限未授予，安全退出悬浮球服务")
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        updateScreenDimensions()
        initFloatingView()
    }

    private fun updateScreenDimensions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
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

        try {
            windowManager.addView(floatingView, layoutParams)
            isViewAdded = true
            fadeToIdleState()
        } catch (e: Exception) {
            Log.e(TAG, "添加悬浮窗视图异常", e)
            isViewAdded = false
            stopSelf()
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeAnimator?.cancel()
                updateScreenDimensions()
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false

                view.animate().alpha(1.0f).scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY

                // 高精度消抖：欧氏距离超过系统 touchSlop 才判定为拖拽，防止触控微抖动导致误触
                if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    isDragging = true
                }

                if (isDragging) {
                    layoutParams.x = max(0, min((initialX + dx).toInt(), screenWidth - view.width))
                    layoutParams.y = max(0, min((initialY + dy).toInt(), screenHeight - view.height))
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
     * 屏幕横竖屏旋转动态自适应，防止悬浮球越界丢失
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        updateScreenDimensions()

        if (oldWidth > 0 && oldHeight > 0 && ::floatingView.isInitialized && floatingView.isAttachedToWindow) {
            edgeAnimator?.cancel()
            val yRatio = layoutParams.y.toFloat() / oldHeight
            layoutParams.y = max(0, min((yRatio * screenHeight).toInt(), screenHeight - floatingView.height))

            val isOnRight = layoutParams.x > oldWidth / 2
            layoutParams.x = if (isOnRight) screenWidth - floatingView.width else 0

            windowManager.updateViewLayout(floatingView, layoutParams)
        }
    }

    private fun performOneClickSwitch() {
        val isExternalConnected = com.oppo.coloros.linux.bridge.ExternalDisplayCoordinator.isExternalDisplayConnected(this)
        if (isExternalConnected) {
            // 独立双屏模式下：点击悬浮球直接唤起内屏超低延迟智能触控板工作台
            val intent = Intent(this, com.oppo.coloros.linux.ui.SmartTrackpadActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
            return
        }

        if (!isLinuxForeground) {
            val intent = Intent(this, LinuxDisplayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
        } else {
            sendBroadcast(Intent(ACTION_MINIMIZE_LINUX).setPackage(packageName))
        }
    }

    private fun snapToNearestEdge(viewWidth: Int) {
        val centerX = layoutParams.x + viewWidth / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - viewWidth

        edgeAnimator = ValueAnimator.ofInt(layoutParams.x, targetX).apply {
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

    private fun fadeToIdleState() {
        floatingView.animate()
            .alpha(0.4f)
            .setStartDelay(1800)
            .setDuration(400)
            .start()
    }

    override fun onDestroy() {
        super.onDestroy()
        edgeAnimator?.cancel()
        if (isViewAdded && ::floatingView.isInitialized) {
            try {
                windowManager.removeViewImmediate(floatingView)
            } catch (_: Exception) {}
            isViewAdded = false
        }
    }
}
