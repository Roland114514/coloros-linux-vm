package com.oppo.coloros.linux.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import com.oppo.coloros.linux.core.NativeDisplayBridge

/**
 * 外接显示器热插拔与双屏工作站调度管理器
 * 监听 Type-C DP Alt-Mode / HDMI 外接屏幕，实现外屏全屏独占输出 Linux，平板继续运行 ColorOS 原生界面
 */
class ExternalDisplayManager(
    private val context: Context,
    private val onDisplayModeChanged: (isExternalActive: Boolean) -> Unit
) : DisplayManager.DisplayListener {

    companion object {
        private const val TAG = "ExternalDisplayManager"
    }

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPresentation: LinuxPresentation? = null
    private var internalSurface: Surface? = null
    private var internalWidth: Int = 1920
    private var internalHeight: Int = 1080
    private var internalDpi: Int = 320

    fun initialize(defaultSurface: Surface?, width: Int, height: Int, dpi: Int = 320) {
        this.internalSurface = defaultSurface
        this.internalWidth = width
        this.internalHeight = height
        this.internalDpi = dpi
        displayManager.registerDisplayListener(this, mainHandler)
        checkExistingDisplays()
    }

    fun updateInternalSurface(surface: Surface?, width: Int, height: Int, dpi: Int = 320) {
        this.internalSurface = surface
        this.internalWidth = width
        this.internalHeight = height
        this.internalDpi = dpi
        if (currentPresentation == null && surface != null && surface.isValid) {
            NativeDisplayBridge.attachSurface(surface, width, height, dpi)
        }
    }

    fun isExternalDisplayConnected(): Boolean {
        return currentPresentation != null
    }

    private fun checkExistingDisplays() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            attachToExternalDisplay(displays.first())
        }
    }

    override fun onDisplayAdded(displayId: Int) {
        val display = displayManager.getDisplay(displayId) ?: return
        if ((display.flags and Display.FLAG_PRESENTATION) != 0) {
            Log.i(TAG, "检测到 Type-C/HDMI 外接显示器接入: ${display.name} (ID: $displayId)")
            attachToExternalDisplay(display)
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (currentPresentation?.display?.displayId == displayId) {
            Log.i(TAG, "外接显示器已拔出 (ID: $displayId)，正在毫秒级热迁移回平板内屏...")
            detachFromExternalDisplay()
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        if (currentPresentation?.display?.displayId == displayId) {
            val display = displayManager.getDisplay(displayId) ?: return
            val metrics = android.util.DisplayMetrics()
            display.getRealMetrics(metrics)
            Log.d(TAG, "外接显示器参数变更: ${metrics.widthPixels}x${metrics.heightPixels}")
        }
    }

    private fun attachToExternalDisplay(display: Display) {
        if (currentPresentation != null) {
            currentPresentation?.dismiss()
            currentPresentation = null
        }

        val presentation = LinuxPresentation(
            outerContext = context,
            display = display,
            onSurfaceReady = { extSurface, width, height ->
                Log.i(TAG, "外屏 Surface 就绪，切换 Linux 渲染管线至 ${width}x${height}")
                val dpi = calculateOptimalDpi(width, height)
                NativeDisplayBridge.attachSurface(extSurface, width, height, dpi)
                onDisplayModeChanged(true)
            },
            onSurfaceDestroyed = {
                // 如果是因为拔线导致的销毁，交由 detachFromExternalDisplay 统一处理
            }
        )

        try {
            presentation.show()
            currentPresentation = presentation
        } catch (e: Exception) {
            Log.e(TAG, "启动外屏 Presentation 失败", e)
            currentPresentation = null
        }
    }

    private fun detachFromExternalDisplay() {
        try {
            currentPresentation?.dismiss()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 Presentation 异常", e)
        } finally {
            currentPresentation = null
        }

        // 零丢失无缝回切到平板内屏 Surface
        val targetSurface = internalSurface
        if (targetSurface != null && targetSurface.isValid) {
            NativeDisplayBridge.attachSurface(targetSurface, internalWidth, internalHeight, internalDpi)
        } else {
            NativeDisplayBridge.detachSurface()
        }
        onDisplayModeChanged(false)
    }

    private fun calculateOptimalDpi(width: Int, height: Int): Int {
        return when {
            width >= 3840 || height >= 2160 -> 192 // 4K 缩放
            width >= 2560 || height >= 1440 -> 144 // 2K 缩放
            else -> 96                             // 1080P 标准 100% 像素点对点
        }
    }

    fun release() {
        displayManager.unregisterDisplayListener(this)
        currentPresentation?.dismiss()
        currentPresentation = null
    }
}
