package io.github.hoerire.flymefreeform.logic

/** 对系统发起的窗口退出请求，模块要做什么。 */
enum class CloseDecision {
    /** 不干预，保持系统原行为。 */
    KeepOriginal,

    /** 把“收成贴边浮标”换成“退出到后台”（关闭），并隐藏该任务 surface。 */
    ReplaceWithToBack,
}

/**
 * 开启「自动收窗」时，系统裁决要收成浮标的退出都改成关闭。
 *
 * 不看触发入口：扇形、面板、桌面、通知、应用内跳转都会触发，逐个识别一定会漏。
 * 用户拖到边缘走拖拽入口，不属于系统裁决，保持原样。
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
