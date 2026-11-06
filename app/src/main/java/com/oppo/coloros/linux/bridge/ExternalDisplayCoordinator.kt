package com.oppo.coloros.linux.bridge

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.oppo.coloros.linux.display.ExternalDisplayManager

/**
 * 跨进程 / 跨组件的独立双屏状态与拓扑调度协调器
 */
object ExternalDisplayCoordinator {

    private var displayManagerRef: ExternalDisplayManager? = null

    fun registerManager(manager: ExternalDisplayManager) {
        this.displayManagerRef = manager
    }

    fun unregisterManager() {
        this.displayManagerRef = null
    }

    fun isExternalDisplayConnected(context: Context): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return displays.isNotEmpty() || displayManagerRef?.isExternalDisplayConnected() == true
    }

    fun getActiveExternalDisplaySummary(context: Context): String? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            val d = displays.first()
            val metrics = android.util.DisplayMetrics()
            d.getRealMetrics(metrics)
            return "外屏: ${d.name} (${metrics.widthPixels}×${metrics.heightPixels} @ ${d.refreshRate.toInt()}Hz)"
        }
        return if (displayManagerRef?.isExternalDisplayConnected() == true) {
            "外屏扩展桌面 (4K/1080P 已连接)"
        } else {
            "外接显示器未连接"
        }
    }

    fun togglePresentationRoutingMode(context: Context) {
        val manager = displayManagerRef ?: return
        val currentlyConnected = manager.isExternalDisplayConnected()
        // 若当前外屏活跃，则临时拉回平板内屏；若已拉回，则重新推送至外屏
        manager.togglePresentationDisplay(forceInternal = currentlyConnected)
    }
}
