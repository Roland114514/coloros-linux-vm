package com.oppo.coloros.linux.bridge

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class InputEventBridgeTest {

    @Test
    fun testKeycodeMappingEscAndEnter() {
        // KEY_ESC = 1, KEY_ENTER = 28
        assertEquals(1, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(28, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun testKeycodeMappingTabAndBackspace() {
        // KEY_TAB = 15, KEY_BACKSPACE = 14
        assertEquals(15, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_TAB))
        assertEquals(14, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_DEL))
    }

    @Test
    fun testKeycodeMappingModifiers() {
        // KEY_LEFTCTRL = 29, KEY_LEFTSHIFT = 42, KEY_LEFTALT = 56, KEY_LEFTMETA = 125
        assertEquals(29, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(42, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(56, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(125, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_META_LEFT))
    }

    @Test
    fun testKeycodeMappingLetters() {
        // KEY_A = 30, KEY_Z = 44, KEY_Q = 16
        assertEquals(30, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_A))
        assertEquals(44, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_Z))
        assertEquals(16, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun testKeycodeMappingUnknownReturnsReserved() {
        assertEquals(0, InputEventBridge.androidKeyCodeToLinux(KeyEvent.KEYCODE_UNKNOWN))
    }
}
