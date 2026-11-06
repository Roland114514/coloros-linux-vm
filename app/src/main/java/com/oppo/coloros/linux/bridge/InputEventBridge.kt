package com.oppo.coloros.linux.bridge

import android.view.KeyEvent
import android.view.MotionEvent
import com.oppo.coloros.linux.core.NativeDisplayBridge

/**
 * 专为 OPPO 平板定制的高级输入协议桥接器
 * 精确解析 OPPO Pencil 压感、倾角、笔尖悬停，以及外接蓝牙/磁吸键盘、鼠标滚轮与指针捕获
 */
object InputEventBridge {

    // Linux evdev 按键码常量 (linux/input-event-codes.h)
    private const val KEY_RESERVED = 0
    private const val KEY_ESC = 1
    private const val KEY_1 = 2
    private const val KEY_2 = 3
    private const val KEY_3 = 4
    private const val KEY_4 = 5
    private const val KEY_5 = 6
    private const val KEY_6 = 7
    private const val KEY_7 = 8
    private const val KEY_8 = 9
    private const val KEY_9 = 10
    private const val KEY_0 = 11
    private const val KEY_MINUS = 12
    private const val KEY_EQUAL = 13
    private const val KEY_BACKSPACE = 14
    private const val KEY_TAB = 15
    private const val KEY_Q = 16
    private const val KEY_W = 17
    private const val KEY_E = 18
    private const val KEY_R = 19
    private const val KEY_T = 20
    private const val KEY_Y = 21
    private const val KEY_U = 22
    private const val KEY_I = 23
    private const val KEY_O = 24
    private const val KEY_P = 25
    private const val KEY_LEFTBRACE = 26
    private const val KEY_RIGHTBRACE = 27
    private const val KEY_ENTER = 28
    private const val KEY_LEFTCTRL = 29
    private const val KEY_A = 30
    private const val KEY_S = 31
    private const val KEY_D = 32
    private const val KEY_F = 33
    private const val KEY_G = 34
    private const val KEY_H = 35
    private const val KEY_J = 36
    private const val KEY_K = 37
    private const val KEY_L = 38
    private const val KEY_SEMICOLON = 39
    private const val KEY_APOSTROPHE = 40
    private const val KEY_GRAVE = 41
    private const val KEY_LEFTSHIFT = 42
    private const val KEY_BACKSLASH = 43
    private const val KEY_Z = 44
    private const val KEY_X = 45
    private const val KEY_C = 46
    private const val KEY_V = 47
    private const val KEY_B = 48
    private const val KEY_N = 49
    private const val KEY_M = 50
    private const val KEY_COMMA = 51
    private const val KEY_DOT = 52
    private const val KEY_SLASH = 53
    private const val KEY_RIGHTSHIFT = 54
    private const val KEY_LEFTALT = 56
    private const val KEY_SPACE = 57
    private const val KEY_CAPSLOCK = 58
    private const val KEY_F1 = 59
    private const val KEY_F2 = 60
    private const val KEY_F3 = 61
    private const val KEY_F4 = 62
    private const val KEY_F5 = 63
    private const val KEY_F6 = 64
    private const val KEY_F7 = 65
    private const val KEY_F8 = 66
    private const val KEY_F9 = 67
    private const val KEY_F10 = 68
    private const val KEY_F11 = 87
    private const val KEY_F12 = 88
    private const val KEY_RIGHTCTRL = 97
    private const val KEY_RIGHTALT = 100
    private const val KEY_UP = 103
    private const val KEY_LEFT = 105
    private const val KEY_RIGHT = 106
    private const val KEY_DOWN = 108
    private const val KEY_DELETE = 111
    private const val KEY_LEFTMETA = 125 // Super / Windows 键

    /**
     * 处理多点触控与手写笔压感
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val index = event.actionIndex
        val toolType = event.getToolType(index)
        val pointerId = event.getPointerId(index)
        val x = event.getX(index)
        val y = event.getY(index)

        // 判断是否为 OPPO Pencil 手写笔
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

        // 普通手指多点触控
        val pressure = event.getPressure(index)
        NativeDisplayBridge.sendPointerEvent(x, y, action, pointerId, pressure)
        return true
    }

    /**
     * 处理鼠标悬停、滚轮与通用运动事件
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // 1. 手写笔悬浮感知 (OPPO Pencil Hover)
        val toolType = event.getToolType(0)
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
                NativeDisplayBridge.sendStylusEvent(
                    x = event.x,
                    y = event.y,
                    action = event.actionMasked,
                    pressure = 0.0f,
                    tilt = event.getAxisValue(MotionEvent.AXIS_TILT),
                    isEraser = false
                )
                return true
            }
        }

        // 2. 鼠标滚轮事件
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
            if (vScroll != 0.0f || hScroll != 0.0f) {
                NativeDisplayBridge.sendScrollEvent(vScroll, hScroll)
                return true
            }
        }

        // 3. 鼠标指针捕获下的相对位移 (Pointer Capture)
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

    /**
     * 键盘物理按键码转换为 Linux evdev 扫描码
     */
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
