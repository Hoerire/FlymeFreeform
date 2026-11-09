package io.github.hoerire.flymefreeform.logic

/** 扇形松手后对目标要执行的动作。 */
enum class FanLaunchAction {
    /** 目标已有可见窗口：把它提到前台，不重新启动。 */
    FrontExisting,

    /** 目标没有可见窗口：正常以小窗启动。 */
    LaunchAsZoom,
}

/**
 * 扇形启动策略。
 *
 * 为什么需要这条策略：已有任务再走一次小窗启动，系统会命中
 * getReusableOptionsForFreeForm，原地改写窗口参数且没有启动动画，
 * 表现为“瞬间缩小”。所以先判有没有可见窗口，再决定前置还是启动。
 *
 * 迷你窗与贴边浮标不做特殊处理——它们是系统行为，模块不干预。
 */
object FanLaunchPolicy {
    fun decide(form: WindowForm): FanLaunchAction =
        when (form) {
            WindowForm.VisibleWindow -> FanLaunchAction.FrontExisting
            WindowForm.BackgroundTask, WindowForm.None -> FanLaunchAction.LaunchAsZoom
        }
}
