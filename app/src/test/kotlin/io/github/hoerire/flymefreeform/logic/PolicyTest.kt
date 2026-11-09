package io.github.hoerire.flymefreeform.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 契约测试：把 ARCHITECTURE.md 里契约的纯逻辑部分钉住。
 *
 * 这些用例的价值不是覆盖率，而是让“行为”可读：改判据时这里必须同步改，
 * 否则 CI 失败——不用装到手机上试才知道。
 */
class FanLaunchPolicyTest {
    @Test
    fun 可见普通小窗只前置不重启() {
        assertEquals(FanLaunchAction.FrontExisting, FanLaunchPolicy.decide(WindowForm.SmallWindow))
    }

    @Test
    fun 迷你窗恢复成普通小窗() {
        assertEquals(FanLaunchAction.RestoreToWindow, FanLaunchPolicy.decide(WindowForm.MiniWindow))
    }

    @Test
    fun 贴边浮标恢复成普通小窗() {
        assertEquals(FanLaunchAction.RestoreToWindow, FanLaunchPolicy.decide(WindowForm.EdgeBubble))
    }

    @Test
    fun 后台全屏任务按冷启动处理() {
        assertEquals(FanLaunchAction.LaunchAsZoom, FanLaunchPolicy.decide(WindowForm.BackgroundTask))
    }

    @Test
    fun 没有任务是冷启动() {
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
