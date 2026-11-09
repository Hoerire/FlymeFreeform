package io.github.hoerire.flymefreeform.logic

/** 对系统发起的窗口退出请求，模块要做什么。 */
enum class CloseDecision {
    /** 不干预，保持系统原行为。 */
    KeepOriginal,

    /** 把“收成贴边浮标”换成“退出到后台”（关闭），并隐藏该任务 surface。 */
    ReplaceWithToBack,
}

/**
 * 收窗策略。
 *
 * 规则只有一条：开启「自动收窗」时，凡是**系统自己**裁决要收成浮标的退出，
 * 都改成关闭。不看这次退出由谁触发（扇形、更多面板、桌面、通知、应用内跳转
 * 都可能），因为逐一识别触发入口必然漏，历史上已经因此失败过两轮。
 *
 * 用户手动拖到边缘走的是拖拽入口，不属于系统裁决，必须保持原样。
 */
object ClosePolicy {
    fun decide(
        autoCloseEnabled: Boolean,
        isFloatExitStrategy: Boolean,
        fromSystemVerdict: Boolean,
    ): CloseDecision =
        if (autoCloseEnabled && isFloatExitStrategy && fromSystemVerdict) {
            CloseDecision.ReplaceWithToBack
        } else {
            CloseDecision.KeepOriginal
        }
}
