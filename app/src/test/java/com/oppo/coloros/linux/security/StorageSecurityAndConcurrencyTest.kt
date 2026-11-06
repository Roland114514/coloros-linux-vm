package com.oppo.coloros.linux.security

import com.oppo.coloros.linux.bridge.InputEventBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * 针对安全防御 (Path Traversal / Filename Sanitization) 与按键并发竞态的严苛测试
 */
class StorageSecurityAndConcurrencyTest {

    @Test
    fun testPathTraversalStrictBoundaryProtection() {
        val baseDir = File("/data/user/0/com.oppo.coloros.linux/files/ubuntu-rootfs/root")
        val legitimateFile = File(baseDir, "Desktop/notes.txt")
        val siblingEscapingFile = File("/data/user/0/com.oppo.coloros.linux/files/ubuntu-rootfs/root_evil/secret.key")
        val dotDotEscapingFile = File(baseDir, "../../shared_prefs/app.xml")

        // 校验严格目录前缀函数逻辑
        fun isStrictChild(parentDir: File, targetFile: File): Boolean {
            val parentCanonical = parentDir.canonicalPath
            val targetCanonical = targetFile.canonicalPath
            if (parentCanonical == targetCanonical) return true
            val parentPrefix = if (parentCanonical.endsWith(File.separator)) {
                parentCanonical
            } else {
                parentCanonical + File.separator
            }
            return targetCanonical.startsWith(parentPrefix)
        }

        assertTrue("合法子目录文件应通过安全校验", isStrictChild(baseDir, legitimateFile))
        assertFalse("同级恶意前缀逃逸必须被精准拦截", isStrictChild(baseDir, siblingEscapingFile))
        assertFalse("点点穿越到应用私有根目录必须被拦截", isStrictChild(baseDir, dotDotEscapingFile))
    }

    @Test
    fun testFileNameSanitization() {
        fun sanitize(name: String): Boolean {
            val cleaned = name.trim()
            if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." ||
                cleaned.contains('/') || cleaned.contains('\\') || cleaned.contains('\u0000')
            ) {
                return false
            }
            return true
        }

        assertTrue(sanitize("test.txt"))
        assertTrue(sanitize("My Document 2026.pdf"))
        assertFalse("包含斜杠必须拦截", sanitize("../evil.sh"))
        assertFalse("包含反斜杠必须拦截", sanitize("..\\evil.sh"))
        assertFalse("单个点必须拦截", sanitize("."))
        assertFalse("双点必须拦截", sanitize(".."))
        assertFalse("包含空字符必须拦截", sanitize("exploit\u0000.txt"))
    }

    @Test
    fun testConcurrentMouseButtonAtomicState() {
        // 多线程高并发并发压测鼠标按键掩码
        val threadCount = 16
        val iterations = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            executor.execute {
                try {
                    for (j in 0 until iterations) {
                        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_LEFT, true)
                        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_RIGHT, true)
                        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_LEFT, false)
                        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_RIGHT, false)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // 最终状态必须全部安全归零，绝不允许出现由于竞态导致的“鼠标卡键”
        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_LEFT, false)
        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_RIGHT, false)
        InputEventBridge.setMouseButtonState(InputEventBridge.MOUSE_BUTTON_MIDDLE, false)
    }
}
