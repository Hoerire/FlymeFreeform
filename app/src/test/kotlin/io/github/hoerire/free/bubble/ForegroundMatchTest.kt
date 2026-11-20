package io.github.hoerire.free.bubble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前台豁免必须区分「主应用」与「分身应用」：两者包名相同、userId 不同。
 */
class ForegroundMatchTest {
    private val main = BubbleEnvironmentGate.ForegroundApp("com.example", 0)
    private val clone = BubbleEnvironmentGate.ForegroundApp("com.example", 999)
    private val other = BubbleEnvironmentGate.ForegroundApp("com.other", 0)

    @Test
    fun mainAppForegroundExemptsOnlyMainAppNotifications() {
        assertTrue(BubbleEnvironmentGate.matchesForeground(main, "com.example", 0))
        // 主应用在前台时，分身自己的通知不该被豁免。
        assertFalse(BubbleEnvironmentGate.matchesForeground(main, "com.example", 999))
        assertFalse(BubbleEnvironmentGate.matchesForeground(main, "com.other", 0))
    }

    @Test
    fun cloneForegroundExemptsOnlyThatClone() {
        assertTrue(BubbleEnvironmentGate.matchesForeground(clone, "com.example", 999))
        // 分身在前台时，主应用的通知不该被豁免。
        assertFalse(BubbleEnvironmentGate.matchesForeground(clone, "com.example", 0))
        assertFalse(BubbleEnvironmentGate.matchesForeground(other, "com.example", 999))
    }

    @Test
    fun unknownForegroundNeverExempts() {
        val unknown = BubbleEnvironmentGate.ForegroundApp(null, 0)
        assertFalse(BubbleEnvironmentGate.matchesForeground(unknown, "com.example", 0))
    }
}
