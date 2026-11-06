package com.oppo.coloros.linux.display

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalDisplayManagerTest {

    private fun calculateOptimalDpi(width: Int, height: Int): Int {
        return when {
            width >= 3840 || height >= 2160 -> 192 // 4K HiDPI
            width >= 2560 || height >= 1440 -> 144 // 2K
            else -> 96                             // 1080P
        }
    }

    @Test
    fun test4KResolutionDpiCalculation() {
        assertEquals(192, calculateOptimalDpi(3840, 2160))
        assertEquals(192, calculateOptimalDpi(4096, 2160))
    }

    @Test
    fun test2KResolutionDpiCalculation() {
        assertEquals(144, calculateOptimalDpi(2560, 1440))
        assertEquals(144, calculateOptimalDpi(2560, 1600))
    }

    @Test
    fun test1080PResolutionDpiCalculation() {
        assertEquals(96, calculateOptimalDpi(1920, 1080))
        assertEquals(96, calculateOptimalDpi(1280, 720))
    }
}
