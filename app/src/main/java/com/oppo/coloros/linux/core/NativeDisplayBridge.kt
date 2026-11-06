package com.oppo.coloros.linux.core

import android.view.Surface

/**
 * JNI 桥接层：对接底层嵌入式 Termux-X11 与 AHardwareBuffer 零拷贝渲染管线
 * 包含 OPPO Pencil 压感笔、蓝牙键鼠与 120Hz VSync 硬件控制
 */
object NativeDisplayBridge {
    init {
        try {
            System.loadLibrary("coloros_x11_bridge")
        } catch (e: UnsatisfiedLinkError) {
            // 允许在无 Native .so 环境下兼容运行
        }
    }

    @JvmStatic
    external fun attachSurface(surface: Surface, width: Int, height: Int, dpi: Int)

    @JvmStatic
    external fun detachSurface()

    @JvmStatic
    external fun setTargetFps(fps: Int)

    @JvmStatic
    external fun sendPointerEvent(x: Float, y: Float, action: Int, pointerId: Int, pressure: Float)

    @JvmStatic
    external fun sendStylusEvent(x: Float, y: Float, action: Int, pressure: Float, tilt: Float, isEraser: Boolean)

    @JvmStatic
    external fun sendRelativeMouseEvent(dx: Float, dy: Float, buttonState: Int)

    @JvmStatic
    external fun sendScrollEvent(vScroll: Float, hScroll: Float)

    @JvmStatic
    external fun sendKeyEvent(keyCode: Int, isDown: Boolean)
}
