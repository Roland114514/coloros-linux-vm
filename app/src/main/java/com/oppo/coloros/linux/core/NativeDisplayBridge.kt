package com.oppo.coloros.linux.core

import android.view.Surface

/**
 * JNI 桥接层：对接底层嵌入式 Termux-X11 与 AHardwareBuffer 零拷贝渲染管线
 */
object NativeDisplayBridge {
    init {
        try {
            System.loadLibrary("coloros_x11_bridge")
        } catch (e: UnsatisfiedLinkError) {
            // 允许在尚未编译 C++ NDK 阶段正常运行 UI 与容器逻辑
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
    external fun sendKeyEvent(keyCode: Int, isDown: Boolean)
}
