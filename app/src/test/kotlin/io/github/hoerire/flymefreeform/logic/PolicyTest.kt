package io.github.hoerire.flymefreeform.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约测试：把 ARCHITECTURE.md 契约 B 的纯逻辑部分钉住。
 * 改判据时必须同步改这里，否则 CI 失败。
 */
class FanLaunchPolicyTest {
    @Test
    fun 有可见窗口时只前置不重启() {
        assertEquals(FanLaunchAction.FrontExisting, FanLaunchPolicy.decide(WindowForm.VisibleWindow))
    }

    @Test
    fun 后台任务按正常小窗启动() {
        assertEquals(FanLaunchAction.LaunchAsZoom, FanLaunchPolicy.decide(WindowForm.BackgroundTask))
    }

    @Test
    fun 没有任务是小窗启动() {
        assertEquals(FanLaunchAction.LaunchAsZoom, FanLaunchPolicy.decide(WindowForm.None))
    }
}

class ClosePolicyTest {
    @Test
    fun 开关关闭时保持原行为() {
        assertEquals(
            CloseDecision.KeepOriginal,
            ClosePolicy.decide(autoCloseEnabled = false, isFloatExitStrategy = true, fromSystemVerdict = true),
        )
    }

    @Test
    fun 系统裁决收浮标时改成关闭() {
        assertEquals(
            CloseDecision.ReplaceWithToBack,
            ClosePolicy.decide(autoCloseEnabled = true, isFloatExitStrategy = true, fromSystemVerdict = true),
        )
    }

    @Test
    fun 用户手势拉成浮标不干预() {
        assertEquals(
            CloseDecision.KeepOriginal,
            ClosePolicy.decide(autoCloseEnabled = true, isFloatExitStrategy = true, fromSystemVerdict = false),
        )
    }

    @Test
    fun 非浮标退出不干预() {
        assertEquals(
            CloseDecision.KeepOriginal,
            ClosePolicy.decide(autoCloseEnabled = true, isFloatExitStrategy = false, fromSystemVerdict = true),
        )
    }
}
