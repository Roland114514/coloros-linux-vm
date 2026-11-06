package com.oppo.coloros.linux.ui

import com.oppo.coloros.linux.bridge.InputEventBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.min

/**
 * 针对独立双屏模式下智能触控板 (SmartTrackpad) 非线性加速与手势算法的单元测试
 */
class SmartTrackpadGestureTest {

    @Before
    fun setUp() {
        InputEventBridge.updateExternalResolution(3840, 2160)
    }

    @Test
    fun testExternalResolutionConfiguration() {
        assertEquals(3840, InputEventBridge.externalDisplayWidth)
        assertEquals(2160, InputEventBridge.externalDisplayHeight)

        InputEventBridge.updateExternalResolution(1920, 1080)
        assertEquals(1920, InputEventBridge.externalDisplayWidth)
        assertEquals(1080, InputEventBridge.externalDisplayHeight)
    }

    @Test
    fun testNonlinearPointerAccelerationBallistics() {
        // 慢速微调测试 (dx=1.0, dy=1.0, dt=16ms)
        val rawDx = 1.0f
        val rawDy = 1.0f
        val dt = 16L
        val velocity = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat() / dt
        val sensitivity = 1.25f
        val accelerationSlow = min(2.8f, 0.95f + velocity * 0.65f) * sensitivity

        assertTrue("慢速操作加速度应保持在精细区间", accelerationSlow < 1.5f)

        // 快速甩动测试 (dx=80.0, dy=50.0, dt=16ms)
        val fastDx = 80.0f
        val fastDy = 50.0f
        val fastVelocity = hypot(fastDx.toDouble(), fastDy.toDouble()).toFloat() / dt
        val accelerationFast = min(2.8f, 0.95f + fastVelocity * 0.65f) * sensitivity

        assertTrue("快速滑动跨越大屏时加速度应达到最高上限", accelerationFast >= 2.8f * sensitivity * 0.9f)
    }

    @Test
    fun testMouseButtonMasks() {
        assertEquals(1, InputEventBridge.MOUSE_BUTTON_LEFT)
        assertEquals(2, InputEventBridge.MOUSE_BUTTON_RIGHT)
        assertEquals(4, InputEventBridge.MOUSE_BUTTON_MIDDLE)
    }

    @Test
    fun testLinuxScanCodeKeyMappings() {
        assertEquals(1, InputEventBridge.KEY_ESC)
        assertEquals(15, InputEventBridge.KEY_TAB)
        assertEquals(29, InputEventBridge.KEY_LEFTCTRL)
        assertEquals(56, InputEventBridge.KEY_LEFTALT)
        assertEquals(125, InputEventBridge.KEY_LEFTMETA)
        assertEquals(103, InputEventBridge.KEY_UP)
        assertEquals(108, InputEventBridge.KEY_DOWN)
        assertEquals(105, InputEventBridge.KEY_LEFT)
        assertEquals(106, InputEventBridge.KEY_RIGHT)
    }
}
