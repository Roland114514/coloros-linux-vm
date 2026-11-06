package com.oppo.coloros.linux.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import com.oppo.coloros.linux.R
import com.oppo.coloros.linux.bridge.ExternalDisplayCoordinator
import com.oppo.coloros.linux.bridge.InputEventBridge
import kotlin.math.abs
import kotlin.math.hypot

/**
 * ColorOS 17 双屏工作站 - 内屏全尺寸智能触控板与 OPPO Pencil 数位板终端
 *
 * 当外接显示器处于独立扩展桌面模式时，平板内屏可随时切换至此界面：
 * 1. 支持多指手势（单指移动/轻击、双指右击/平滑滚动、三指工作区切换）
 * 2. 支持 OPPO Pencil 4096 级压感与倾角，自动映射为外屏绝对坐标数位板 (Graphics Tablet)
 * 3. 顶部集成 Linux 生产力快捷键、修饰键锁存器 (Sticky Modifiers) 与软键盘桥接
 * 4. 底部配备实体左/中/右微动按键，支持按住单指拖拽 (Drag & Drop)
 */
class SmartTrackpadActivity : AppCompatActivity() {

    private lateinit var trackpadSurface: FrameLayout
    private lateinit var tvModeIndicator: TextView
    private lateinit var tvExternalDisplayBadge: TextView

    // 修饰键锁存开关
    private lateinit var toggleCtrl: ToggleButton
    private lateinit var toggleAlt: ToggleButton
    private lateinit var toggleShift: ToggleButton

    // 触控手势状态机参数
    private var touchSlop: Int = 0
    private var downTime: Long = 0L
    private var maxPointerCountInGesture: Int = 0

    private var initialX: Float = 0f
    private var initialY: Float = 0f
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    private var twoFingerInitialX: Float = 0f
    private var twoFingerInitialY: Float = 0f
    private var lastTwoFingerX: Float = 0f
    private var lastTwoFingerY: Float = 0f

    private var threeFingerStartX: Float = 0f
    private var threeFingerStartY: Float = 0f
    private var threeFingerSwiped: Boolean = false

    private var isDraggingMoved: Boolean = false
    private var isStylusActive: Boolean = false

    // 鼠标按键物理按住状态追踪（用于配合触控板实现拖拽）
    private var isLeftBtnHeld: Boolean = false
    private var isMiddleBtnHeld: Boolean = false
    private var isRightBtnHeld: Boolean = false

    private lateinit var displayManager: android.hardware.display.DisplayManager

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            updateExternalDisplayInfo()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (!ExternalDisplayCoordinator.isExternalDisplayConnected(this@SmartTrackpadActivity)) {
                // 外接显示器已断开拔出，自动退出内屏触控板模式，平滑过渡回平板显示
                finish()
            } else {
                updateExternalDisplayInfo()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            updateExternalDisplayInfo()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_smart_trackpad)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        displayManager.registerDisplayListener(displayListener, null)

        initViews()
        setupTrackpadTouchEngine()
        setupToolbarKeys()
        setupMouseButtons()
        enableImmersiveMode()
        updateExternalDisplayInfo()
    }

    private fun initViews() {
        trackpadSurface = findViewById(R.id.trackpad_surface_area)
        tvModeIndicator = findViewById(R.id.tv_trackpad_mode_hint)
        tvExternalDisplayBadge = findViewById(R.id.tv_external_display_info)

        toggleCtrl = findViewById(R.id.toggle_key_ctrl)
        toggleAlt = findViewById(R.id.toggle_key_alt)
        toggleShift = findViewById(R.id.toggle_key_shift)

        // 确保触控板视图可聚焦以接收软键盘输入
        trackpadSurface.isFocusable = true
        trackpadSurface.isFocusableInTouchMode = true

        // 防误触：在 Android 10+ / ColorOS 17 下排除触控板边缘的系统返回手势
        trackpadSurface.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
            }
        }
    }

    /**
     * 核心多点触控与 OPPO Pencil 数位板绝对映射引擎
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTrackpadTouchEngine() {
        // 监听手写笔悬停 (OPPO Pencil Hover)
        trackpadSurface.setOnGenericMotionListener { view, event ->
            val toolType = event.getToolType(0)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> {
                        switchUiToStylusMode(true)
                        sendMappedStylus(event, view.width, view.height)
                        true
                    }
                    MotionEvent.ACTION_HOVER_MOVE -> {
                        sendMappedStylus(event, view.width, view.height)
                        true
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        sendMappedStylus(event, view.width, view.height)
                        switchUiToStylusMode(false)
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }

        // 监听触控板手指手势与手写笔压感落笔
        trackpadSurface.setOnTouchListener { view, event ->
            val toolType = event.getToolType(event.actionIndex)

            // 1. OPPO Pencil 手写笔分支：绝对坐标数位板映射
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                if (!isStylusActive) {
                    switchUiToStylusMode(true)
                }
                sendMappedStylus(event, view.width, view.height)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    switchUiToStylusMode(false)
                }
                return@setOnTouchListener true
            }

            // 若之前处于手写笔模式，手指触碰时切回多指触控板模式
            if (isStylusActive) {
                switchUiToStylusMode(false)
            }

            // 2. 多指触控板分支
            handleTrackpadMultiTouch(view, event)
            true
        }
    }

    /**
     * 将内屏手写笔事件按比例无损映射至外屏 Linux 桌面分辨率
     */
    private fun sendMappedStylus(event: MotionEvent, surfaceWidth: Int, surfaceHeight: Int) {
        val index = event.actionIndex
        val pressure = event.getPressure(index).coerceIn(0.0f, 1.0f)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, index)
        val isEraser = event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER

        InputEventBridge.sendTabletMappedStylusEvent(
            touchX = event.getX(index),
            touchY = event.getY(index),
            surfaceWidth = surfaceWidth.coerceAtLeast(1),
            surfaceHeight = surfaceHeight.coerceAtLeast(1),
            action = event.actionMasked,
            pressure = pressure,
            tilt = tilt,
            isEraser = isEraser
        )
    }

    /**
     * 处理单指相对移动/轻击、双指右击/滚轮、三指工作区切换
     */
    private fun handleTrackpadMultiTouch(view: View, event: MotionEvent) {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downTime = SystemClock.uptimeMillis()
                maxPointerCountInGesture = 1
                isDraggingMoved = false
                threeFingerSwiped = false

                initialX = event.x
                initialY = event.y
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                when (pointerCount) {
                    2 -> {
                        val cx = (event.getX(0) + event.getX(1)) / 2f
                        val cy = (event.getY(0) + event.getY(1)) / 2f
                        twoFingerInitialX = cx
                        twoFingerInitialY = cy
                        lastTwoFingerX = cx
                        lastTwoFingerY = cy
                    }
                    3 -> {
                        val cx = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                        val cy = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                        threeFingerStartX = cx
                        threeFingerStartY = cy
                        threeFingerSwiped = false
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (pointerCount) {
                    1 -> {
                        // 仅当本次手势全程只有单指时才移动指针，防止多指抬起瞬间指针跳变
                        if (maxPointerCountInGesture == 1) {
                            val dx = event.x - lastX
                            val dy = event.y - lastY

                            if (!isDraggingMoved) {
                                val totalDist = hypot(event.x - initialX, event.y - initialY)
                                if (totalDist > touchSlop) {
                                    isDraggingMoved = true
                                }
                            }

                            if (isDraggingMoved && (dx != 0f || dy != 0f)) {
                                InputEventBridge.sendTrackpadRelativeDelta(dx, dy)
                            }

                            lastX = event.x
                            lastY = event.y
                        }
                    }

                    2 -> {
                        if (maxPointerCountInGesture == 2) {
                            val cx = (event.getX(0) + event.getX(1)) / 2f
                            val cy = (event.getY(0) + event.getY(1)) / 2f
                            val dx = cx - lastTwoFingerX
                            val dy = cy - lastTwoFingerY

                            if (!isDraggingMoved) {
                                val totalDist = hypot(cx - twoFingerInitialX, cy - twoFingerInitialY)
                                if (totalDist > touchSlop) {
                                    isDraggingMoved = true
                                }
                            }

                            if (isDraggingMoved && (abs(dx) > 0.5f || abs(dy) > 0.5f)) {
                                val vScroll = dy * 0.08f
                                val hScroll = -dx * 0.08f
                                InputEventBridge.sendTrackpadScroll(vScroll, hScroll)
                            }

                            lastTwoFingerX = cx
                            lastTwoFingerY = cy
                        }
                    }

                    3 -> {
                        if (!threeFingerSwiped) {
                            val cx = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                            val cy = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                            val deltaX = cx - threeFingerStartX
                            val deltaY = cy - threeFingerStartY

                            val swipeThreshold = touchSlop * 4.5f
                            if (abs(deltaX) > swipeThreshold && abs(deltaX) > abs(deltaY) * 1.5f) {
                                threeFingerSwiped = true
                                isDraggingMoved = true
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                                val arrowKey = if (deltaX < 0) {
                                    InputEventBridge.KEY_RIGHT
                                } else {
                                    InputEventBridge.KEY_LEFT
                                }
                                InputEventBridge.sendKeyCombo(
                                    InputEventBridge.KEY_LEFTCTRL,
                                    InputEventBridge.KEY_LEFTALT,
                                    arrowKey
                                )
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 保持手势状态
            }

            MotionEvent.ACTION_UP -> {
                val duration = SystemClock.uptimeMillis() - downTime
                val anyPhysicalButtonHeld = isLeftBtnHeld || isMiddleBtnHeld || isRightBtnHeld
                if (!isDraggingMoved && !anyPhysicalButtonHeld && duration < TAP_MAX_DURATION_MS) {
                    when (maxPointerCountInGesture) {
                        1 -> {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            InputEventBridge.performMouseClick(InputEventBridge.MOUSE_BUTTON_LEFT)
                        }
                        2 -> {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            InputEventBridge.performMouseClick(InputEventBridge.MOUSE_BUTTON_RIGHT)
                        }
                        3 -> {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            InputEventBridge.performMouseClick(InputEventBridge.MOUSE_BUTTON_MIDDLE)
                        }
                    }
                }
                resetGestureTracking()
            }

            MotionEvent.ACTION_CANCEL -> {
                resetGestureTracking()
            }
        }
    }

    private fun resetGestureTracking() {
        maxPointerCountInGesture = 0
        isDraggingMoved = false
        threeFingerSwiped = false
    }

    /**
     * 绑定顶部生产力工具栏：快捷键、修饰键锁存器、方向键、软键盘与双屏模式切换
     */
    private fun setupToolbarKeys() {
        // 1. 瞬发单键按钮
        bindTapKey(R.id.btn_key_esc, InputEventBridge.KEY_ESC)
        bindTapKey(R.id.btn_key_tab, InputEventBridge.KEY_TAB)
        bindTapKey(R.id.btn_key_super, InputEventBridge.KEY_LEFTMETA)

        bindTapKey(R.id.btn_key_up, InputEventBridge.KEY_UP)
        bindTapKey(R.id.btn_key_down, InputEventBridge.KEY_DOWN)
        bindTapKey(R.id.btn_key_left, InputEventBridge.KEY_LEFT)
        bindTapKey(R.id.btn_key_right, InputEventBridge.KEY_RIGHT)

        // 2. 组合快捷键 Ctrl+C / Ctrl+V
        findViewById<Button>(R.id.btn_key_copy)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            InputEventBridge.sendKeyCombo(InputEventBridge.KEY_LEFTCTRL, InputEventBridge.KEY_C)
        }

        findViewById<Button>(R.id.btn_key_paste)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            InputEventBridge.sendKeyCombo(InputEventBridge.KEY_LEFTCTRL, InputEventBridge.KEY_V)
        }

        // 3. 修饰键锁存器 (Ctrl / Alt / Shift)
        toggleCtrl.setOnCheckedChangeListener { btn, isChecked ->
            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            InputEventBridge.sendKeyState(InputEventBridge.KEY_LEFTCTRL, isChecked)
        }

        toggleAlt.setOnCheckedChangeListener { btn, isChecked ->
            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            InputEventBridge.sendKeyState(InputEventBridge.KEY_LEFTALT, isChecked)
        }

        toggleShift.setOnCheckedChangeListener { btn, isChecked ->
            btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            InputEventBridge.sendKeyState(InputEventBridge.KEY_LEFTSHIFT, isChecked)
        }

        // 4. 唤起系统软键盘 (IME)
        findViewById<View>(R.id.btn_toggle_ime)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            trackpadSurface.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }

        // 5. 切换显示目标（拉回内屏 / 外屏独占）
        findViewById<Button>(R.id.btn_switch_display_target)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            ExternalDisplayCoordinator.togglePresentationRoutingMode(this)
            finish()
        }
    }

    private fun bindTapKey(viewId: Int, linuxKeyCode: Int) {
        findViewById<View>(viewId)?.setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            InputEventBridge.sendKeyClick(linuxKeyCode)
            releaseStickyModifiersIfAny()
        }
    }

    /**
     * 当用户点击普通按键后，如果修饰键处于锁存状态，自动释放锁存以符合直觉
     */
    private fun releaseStickyModifiersIfAny() {
        if (toggleCtrl.isChecked) toggleCtrl.isChecked = false
        if (toggleAlt.isChecked) toggleAlt.isChecked = false
        if (toggleShift.isChecked) toggleShift.isChecked = false
    }

    /**
     * 底部实体微动按键（左键、中键、右键）：支持按住不放配合触控板单指拖拽 (Drag & Drop)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMouseButtons() {
        val btnLeft = findViewById<Button>(R.id.btn_mouse_left)
        val btnMiddle = findViewById<Button>(R.id.btn_mouse_middle)
        val btnRight = findViewById<Button>(R.id.btn_mouse_right)

        btnLeft?.setOnTouchListener { v, event ->
            handleMouseButtonTouch(v, event, InputEventBridge.MOUSE_BUTTON_LEFT) { held ->
                isLeftBtnHeld = held
            }
            true
        }

        btnMiddle?.setOnTouchListener { v, event ->
            handleMouseButtonTouch(v, event, InputEventBridge.MOUSE_BUTTON_MIDDLE) { held ->
                isMiddleBtnHeld = held
            }
            true
        }

        btnRight?.setOnTouchListener { v, event ->
            handleMouseButtonTouch(v, event, InputEventBridge.MOUSE_BUTTON_RIGHT) { held ->
                isRightBtnHeld = held
            }
            true
        }
    }

    private inline fun handleMouseButtonTouch(
        view: View,
        event: MotionEvent,
        buttonMask: Int,
        onStateChanged: (Boolean) -> Unit
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onStateChanged(true)
                InputEventBridge.setMouseButtonState(buttonMask, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onStateChanged(false)
                InputEventBridge.setMouseButtonState(buttonMask, false)
            }
        }
    }

    /**
     * 切换内屏 UI 提示状态（多指触控板模式 vs OPPO Pencil 绝对坐标数位板模式）
     */
    private fun switchUiToStylusMode(stylusActive: Boolean) {
        if (isStylusActive == stylusActive) return
        isStylusActive = stylusActive

        if (stylusActive) {
            tvModeIndicator.text = "OPPO Pencil 数位板绝对映射模式 (4096级压感 / 倾角激活)"
            trackpadSurface.alpha = 0.95f
        } else {
            tvModeIndicator.text = "多指智能触控板模式 (单指移动 · 双指滚动/右击 · 三指切工作区)"
            trackpadSurface.alpha = 1.0f
        }
    }

    private fun updateExternalDisplayInfo() {
        val info = ExternalDisplayCoordinator.getActiveExternalDisplaySummary(this)
        tvExternalDisplayBadge.text = info ?: "外屏扩展桌面已连接"
    }

    /**
     * 拦截物理键盘与软键盘事件，直接桥接至 Linux 虚拟桌面
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return super.dispatchKeyEvent(event)
        }

        if (InputEventBridge.onKeyEvent(event)) {
            if (event.action == KeyEvent.ACTION_UP) {
                releaseStickyModifiersIfAny()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    override fun onStop() {
        super.onStop()
        if (toggleCtrl.isChecked) toggleCtrl.isChecked = false
        if (toggleAlt.isChecked) toggleAlt.isChecked = false
        if (toggleShift.isChecked) toggleShift.isChecked = false

        if (isLeftBtnHeld) {
            isLeftBtnHeld = false
            InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_LEFT, false)
        }
        if (isMiddleBtnHeld) {
            isMiddleBtnHeld = false
            InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_MIDDLE, false)
        }
        if (isRightBtnHeld) {
            isRightBtnHeld = false
            InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_RIGHT, false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
        }
    }

    companion object {
        private const val TAP_MAX_DURATION_MS = 180L

        fun start(context: Context) {
            val intent = Intent(context, SmartTrackpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }
}
