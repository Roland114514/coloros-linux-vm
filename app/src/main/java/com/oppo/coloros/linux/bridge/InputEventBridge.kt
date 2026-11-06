package com.oppo.coloros.linux.bridge

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.oppo.coloros.linux.core.NativeDisplayBridge
import kotlin.math.hypot
import kotlin.math.min

/**
 * 专为 OPPO 平板定制的高级输入协议桥接器 (4-Argon 增强版)
 * 支持：相对触控板非线性加速、OPPO Pencil 跨屏绝对坐标数位板映射、修饰键锁存与组合键注入
 */
object InputEventBridge {

    // Linux evdev 按键码常量 (linux/input-event-codes.h)
    const val KEY_RESERVED = 0
    const val KEY_ESC = 1
    const val KEY_1 = 2
    const val KEY_2 = 3
    const val KEY_3 = 4
    const val KEY_4 = 5
    const val KEY_5 = 6
    const val KEY_6 = 7
    const val KEY_7 = 8
    const val KEY_8 = 9
    const val KEY_9 = 10
    const val KEY_0 = 11
    const val KEY_MINUS = 12
    const val KEY_EQUAL = 13
    const val KEY_BACKSPACE = 14
    const val KEY_TAB = 15
    const val KEY_Q = 16
    const val KEY_W = 17
    const val KEY_E = 18
    const val KEY_R = 19
    const val KEY_T = 20
    const val KEY_Y = 21
    const val KEY_U = 22
    const val KEY_I = 23
    const val KEY_O = 24
    const val KEY_P = 25
    const val KEY_LEFTBRACE = 26
    const val KEY_RIGHTBRACE = 27
    const val KEY_ENTER = 28
    const val KEY_LEFTCTRL = 29
    const val KEY_A = 30
    const val KEY_S = 31
    const val KEY_D = 32
    const val KEY_F = 33
    const val KEY_G = 34
    const val KEY_H = 35
    const val KEY_J = 36
    const val KEY_K = 37
    const val KEY_L = 38
    const val KEY_SEMICOLON = 39
    const val KEY_APOSTROPHE = 40
    const val KEY_GRAVE = 41
    const val KEY_LEFTSHIFT = 42
    const val KEY_BACKSLASH = 43
    const val KEY_Z = 44
    const val KEY_X = 45
    const val KEY_C = 46
    const val KEY_V = 47
    const val KEY_B = 48
    const val KEY_N = 49
    const val KEY_M = 50
    const val KEY_COMMA = 51
    const val KEY_DOT = 52
    const val KEY_SLASH = 53
    const val KEY_RIGHTSHIFT = 54
    const val KEY_LEFTALT = 56
    const val KEY_SPACE = 57
    const val KEY_CAPSLOCK = 58
    const val KEY_F1 = 59
    const val KEY_F2 = 60
    const val KEY_F3 = 61
    const val KEY_F4 = 62
    const val KEY_F5 = 63
    const val KEY_F6 = 64
    const val KEY_F7 = 65
    const val KEY_F8 = 66
    const val KEY_F9 = 67
    const val KEY_F10 = 68
    const val KEY_F11 = 87
    const val KEY_F12 = 88
    const val KEY_RIGHTCTRL = 97
    const val KEY_RIGHTALT = 100
    const val KEY_UP = 103
    const val KEY_LEFT = 105
    const val KEY_RIGHT = 106
    const val KEY_DOWN = 108
    const val KEY_DELETE = 111
    const val KEY_LEFTMETA = 125 // Super / Windows 键

    // 鼠标按键掩码常量 (对齐 Android MotionEvent & Linux X11 ButtonMask)
    const val MOUSE_BUTTON_NONE = 0
    const val MOUSE_BUTTON_LEFT = MotionEvent.BUTTON_PRIMARY     // 1
    const val MOUSE_BUTTON_RIGHT = MotionEvent.BUTTON_SECONDARY  // 2
    const val MOUSE_BUTTON_MIDDLE = MotionEvent.BUTTON_TERTIARY  // 4

    // 外接显示器目标分辨率缓存（供 OPPO Pencil 跨屏绝对坐标映射）
    @Volatile var externalDisplayWidth: Int = 1920
    @Volatile var externalDisplayHeight: Int = 1080

    // 当前按下的虚拟鼠标按键状态掩码
    private var activeMouseButtons: Int = MOUSE_BUTTON_NONE
    private var lastMoveTimestamp: Long = 0L

    /**
     * 更新外屏目标分辨率（在 ExternalDisplayManager 绑定外屏时调用）
     */
    fun updateExternalResolution(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            externalDisplayWidth = width
            externalDisplayHeight = height
        }
    }

    /**
     * 智能触控板：带非线性速度增益曲线的相对鼠标位移注入
     */
    fun sendTrackpadRelativeDelta(rawDx: Float, rawDy: Float, sensitivity: Float = 1.25f) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastMoveTimestamp).coerceIn(1L, 100L)
        lastMoveTimestamp = now

        val velocity = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat() / dt
        // 非线性指针加速：慢速精细(1.0x)，快速跨越4K大屏(最高2.8x)
        val acceleration = min(2.8f, 0.95f + velocity * 0.65f) * sensitivity

        val acceleratedDx = rawDx * acceleration
        val acceleratedDy = rawDy * acceleration

        NativeDisplayBridge.sendRelativeMouseEvent(acceleratedDx, acceleratedDy, activeMouseButtons)
    }

    /**
     * 智能触控板：模拟鼠标左/中/右键按下与释放
     */
    fun sendMouseButton(buttonMask: Int, isDown: Boolean) {
        activeMouseButtons = if (isDown) {
            activeMouseButtons or buttonMask
        } else {
            activeMouseButtons and buttonMask.inv()
        }
        NativeDisplayBridge.sendRelativeMouseEvent(0f, 0f, activeMouseButtons)
    }

    fun setMouseButtonState(buttonMask: Int, isDown: Boolean) {
        sendMouseButton(buttonMask, isDown)
    }

    /**
     * 智能触控板：模拟一次完整的鼠标点击 (Tap-to-Click)
     */
    fun performMouseClick(buttonMask: Int) {
        sendMouseButton(buttonMask, true)
        sendMouseButton(buttonMask, false)
    }

    /**
     * 智能触控板：模拟双指平滑滚轮
     */
    fun sendTrackpadScroll(deltaY: Float, deltaX: Float) {
        NativeDisplayBridge.sendScrollEvent(deltaY, deltaX)
    }

    /**
     * OPPO Pencil 专属：跨屏数位板模式（按坐标归一化并映射到外接大屏绝对坐标）
     */
    fun sendTabletMappedStylusEvent(
        touchX: Float,
        touchY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int,
        action: Int,
        pressure: Float,
        tilt: Float,
        isEraser: Boolean
    ) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        val normalizedX = (touchX / surfaceWidth.toFloat()).coerceIn(0.0f, 1.0f)
        val normalizedY = (touchY / surfaceHeight.toFloat()).coerceIn(0.0f, 1.0f)

        val mappedX = normalizedX * externalDisplayWidth
        val mappedY = normalizedY * externalDisplayHeight

        NativeDisplayBridge.sendStylusEvent(
            x = mappedX,
            y = mappedY,
            action = action,
            pressure = pressure,
            tilt = tilt,
            isEraser = isEraser
        )
    }

    /**
     * OPPO Pencil 专属：跨屏数位板模式（将平板触控区归一化并映射到外接大屏绝对坐标）
     */
    fun sendTabletMappedStylusEvent(
        event: MotionEvent,
        padWidth: Int,
        padHeight: Int
    ): Boolean {
        if (padWidth <= 0 || padHeight <= 0) return false
        val index = event.actionIndex
        val normalizedX = (event.getX(index) / padWidth.toFloat()).coerceIn(0.0f, 1.0f)
        val normalizedY = (event.getY(index) / padHeight.toFloat()).coerceIn(0.0f, 1.0f)

        val mappedX = normalizedX * externalDisplayWidth
        val mappedY = normalizedY * externalDisplayHeight

        val toolType = event.getToolType(index)
        val isHover = event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT

        val pressure = if (isHover) 0.0f else event.getPressure(index).coerceIn(0.0f, 1.0f)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
        val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER

        NativeDisplayBridge.sendStylusEvent(
            x = mappedX,
            y = mappedY,
            action = event.actionMasked,
            pressure = pressure,
            tilt = tilt,
            isEraser = isEraser
        )
        return true
    }

    /**
     * 发送单个 Linux 扫描码按键脉冲（按下+释放）
     */
    fun sendKeyClick(linuxKeyCode: Int) {
        NativeDisplayBridge.sendKeyEvent(linuxKeyCode, true)
        NativeDisplayBridge.sendKeyEvent(linuxKeyCode, false)
    }

    /**
     * 发送单个 Linux 扫描码按下或抬起（用于 Ctrl/Alt/Shift/Super 修饰键锁存）
     */
    fun sendKeyState(linuxKeyCode: Int, isDown: Boolean) {
        NativeDisplayBridge.sendKeyEvent(linuxKeyCode, isDown)
    }

    /**
     * 发送原子组合快捷键（例如 Ctrl+C, Ctrl+V, Ctrl+Alt+Left 切换工作区）
     */
    fun sendKeyCombo(vararg linuxKeyCodes: Int) {
        for (code in linuxKeyCodes) {
            NativeDisplayBridge.sendKeyEvent(code, true)
        }
        for (i in linuxKeyCodes.indices.reversed()) {
            NativeDisplayBridge.sendKeyEvent(linuxKeyCodes[i], false)
        }
    }

    /**
     * 处理直接画布模式下的多点触控与手写笔压感
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        val toolType = event.getToolType(index)
        val pointerId = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            val pressure = event.getPressure(index).coerceIn(0.0f, 1.0f)
            val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
            val isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER

            NativeDisplayBridge.sendStylusEvent(
                x = x,
                y = y,
                action = action,
                pressure = pressure,
                tilt = tilt,
                isEraser = isEraser
            )
            return true
        }

        val pressure = event.getPressure(index)
        NativeDisplayBridge.sendPointerEvent(x, y, action, pointerId, pressure)
        return true
    }

    /**
     * 处理鼠标悬停、滚轮与通用运动事件
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                NativeDisplayBridge.sendStylusEvent(
                    x = event.x,
                    y = event.y,
                    action = event.actionMasked,
                    pressure = 0.0f,
                    tilt = event.getAxisValue(MotionEvent.AXIS_TILT),
                    isEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
                )
                return true
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vScroll != 0.0f || hScroll != 0.0f) {
                NativeDisplayBridge.sendScrollEvent(vScroll, hScroll)
                return true
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
            val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
            val buttonState = event.buttonState
            if (dx != 0.0f || dy != 0.0f || buttonState != 0) {
                NativeDisplayBridge.sendRelativeMouseEvent(dx, dy, buttonState)
                return true
            }
        }

        return false
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val linuxScanCode = androidKeyCodeToLinux(event.keyCode)

        if (linuxScanCode != KEY_RESERVED) {
            NativeDisplayBridge.sendKeyEvent(linuxScanCode, isDown)
            return true
        }
        return false
    }

    fun androidKeyCodeToLinux(androidKeyCode: Int): Int {
        return when (androidKeyCode) {
            KeyEvent.KEYCODE_ESCAPE -> KEY_ESC
            KeyEvent.KEYCODE_1 -> KEY_1
            KeyEvent.KEYCODE_2 -> KEY_2
            KeyEvent.KEYCODE_3 -> KEY_3
            KeyEvent.KEYCODE_4 -> KEY_4
            KeyEvent.KEYCODE_5 -> KEY_5
            KeyEvent.KEYCODE_6 -> KEY_6
            KeyEvent.KEYCODE_7 -> KEY_7
            KeyEvent.KEYCODE_8 -> KEY_8
            KeyEvent.KEYCODE_9 -> KEY_9
            KeyEvent.KEYCODE_0 -> KEY_0
            KeyEvent.KEYCODE_MINUS -> KEY_MINUS
            KeyEvent.KEYCODE_EQUALS -> KEY_EQUAL
            KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
            KeyEvent.KEYCODE_TAB -> KEY_TAB
            KeyEvent.KEYCODE_Q -> KEY_Q
            KeyEvent.KEYCODE_W -> KEY_W
            KeyEvent.KEYCODE_E -> KEY_E
            KeyEvent.KEYCODE_R -> KEY_R
            KeyEvent.KEYCODE_T -> KEY_T
            KeyEvent.KEYCODE_Y -> KEY_Y
            KeyEvent.KEYCODE_U -> KEY_U
            KeyEvent.KEYCODE_I -> KEY_I
            KeyEvent.KEYCODE_O -> KEY_O
            KeyEvent.KEYCODE_P -> KEY_P
            KeyEvent.KEYCODE_LEFT_BRACKET -> KEY_LEFTBRACE
            KeyEvent.KEYCODE_RIGHT_BRACKET -> KEY_RIGHTBRACE
            KeyEvent.KEYCODE_ENTER -> KEY_ENTER
            KeyEvent.KEYCODE_CTRL_LEFT -> KEY_LEFTCTRL
            KeyEvent.KEYCODE_A -> KEY_A
            KeyEvent.KEYCODE_S -> KEY_S
            KeyEvent.KEYCODE_D -> KEY_D
            KeyEvent.KEYCODE_F -> KEY_F
            KeyEvent.KEYCODE_G -> KEY_G
            KeyEvent.KEYCODE_H -> KEY_H
            KeyEvent.KEYCODE_J -> KEY_J
            KeyEvent.KEYCODE_K -> KEY_K
            KeyEvent.KEYCODE_L -> KEY_L
            KeyEvent.KEYCODE_SEMICOLON -> KEY_SEMICOLON
            KeyEvent.KEYCODE_APOSTROPHE -> KEY_APOSTROPHE
            KeyEvent.KEYCODE_GRAVE -> KEY_GRAVE
            KeyEvent.KEYCODE_SHIFT_LEFT -> KEY_LEFTSHIFT
            KeyEvent.KEYCODE_BACKSLASH -> KEY_BACKSLASH
            KeyEvent.KEYCODE_Z -> KEY_Z
            KeyEvent.KEYCODE_X -> KEY_X
            KeyEvent.KEYCODE_C -> KEY_C
            KeyEvent.KEYCODE_V -> KEY_V
            KeyEvent.KEYCODE_B -> KEY_B
            KeyEvent.KEYCODE_N -> KEY_N
            KeyEvent.KEYCODE_M -> KEY_M
            KeyEvent.KEYCODE_COMMA -> KEY_COMMA
            KeyEvent.KEYCODE_PERIOD -> KEY_DOT
            KeyEvent.KEYCODE_SLASH -> KEY_SLASH
            KeyEvent.KEYCODE_SHIFT_RIGHT -> KEY_RIGHTSHIFT
            KeyEvent.KEYCODE_ALT_LEFT -> KEY_LEFTALT
            KeyEvent.KEYCODE_SPACE -> KEY_SPACE
            KeyEvent.KEYCODE_CAPS_LOCK -> KEY_CAPSLOCK
            KeyEvent.KEYCODE_F1 -> KEY_F1
            KeyEvent.KEYCODE_F2 -> KEY_F2
            KeyEvent.KEYCODE_F3 -> KEY_F3
            KeyEvent.KEYCODE_F4 -> KEY_F4
            KeyEvent.KEYCODE_F5 -> KEY_F5
            KeyEvent.KEYCODE_F6 -> KEY_F6
            KeyEvent.KEYCODE_F7 -> KEY_F7
            KeyEvent.KEYCODE_F8 -> KEY_F8
            KeyEvent.KEYCODE_F9 -> KEY_F9
            KeyEvent.KEYCODE_F10 -> KEY_F10
            KeyEvent.KEYCODE_F11 -> KEY_F11
            KeyEvent.KEYCODE_F12 -> KEY_F12
            KeyEvent.KEYCODE_CTRL_RIGHT -> KEY_RIGHTCTRL
            KeyEvent.KEYCODE_ALT_RIGHT -> KEY_RIGHTALT
            KeyEvent.KEYCODE_DPAD_UP -> KEY_UP
            KeyEvent.KEYCODE_DPAD_LEFT -> KEY_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_RIGHT
            KeyEvent.KEYCODE_DPAD_DOWN -> KEY_DOWN
            KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KEY_LEFTMETA
            else -> KEY_RESERVED
        }
    }
}
