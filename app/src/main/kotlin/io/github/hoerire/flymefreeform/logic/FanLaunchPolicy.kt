package io.github.hoerire.flymefreeform.logic

/** 扇形松手后对目标要执行的动作。 */
enum class FanLaunchAction {
    /** 目标没有任何窗口：正常以小窗启动（会走完整启动动画）。 */
    LaunchAsZoom,

    /** 目标已有可见窗口：把它提到前台，不重新启动。 */
    FrontExisting,

    /** 目标是迷你窗或贴边浮标：恢复成普通小窗。 */
    RestoreToWindow,
}

/**
 * 扇形启动策略：给定目标当前形态，决定该做什么。
 *
 * 纯函数，无副作用、无 Android 依赖，便于单测覆盖全部形态组合。
 * 事实采集（读任务树、读厂商状态）由 platform 层负责，本类只做判断。
 *
 * 为什么需要这条策略：已有任务再走一次小窗启动，系统会命中
 * getReusableOptionsForFreeForm，原地改写窗口参数且没有启动动画——
 * 表现为“瞬间缩小”或“开出来还是迷你窗”。所以必须先判形态再决定动作。
 */
object FanLaunchPolicy {
    fun decide(form: WindowForm): FanLaunchAction =
        when (form) {
            WindowForm.SmallWindow -> FanLaunchAction.FrontExisting
            WindowForm.MiniWindow, WindowForm.EdgeBubble -> FanLaunchAction.RestoreToWindow
            WindowForm.BackgroundTask, WindowForm.None -> FanLaunchAction.LaunchAsZoom
        }
}
