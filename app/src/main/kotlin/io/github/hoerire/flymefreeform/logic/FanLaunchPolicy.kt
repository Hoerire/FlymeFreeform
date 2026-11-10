package io.github.hoerire.flymefreeform.logic

/** 扇形松手后对目标要执行的动作。 */
enum class FanLaunchAction {
    FrontExisting,
    LaunchAsZoom,
}

/**
 * 有可见窗口就前置，否则正常以小窗启动。
 * 不能直接重启：已有任务时系统会命中 getReusableOptionsForFreeForm，原地改写参数且没有动画。
 */
object FanLaunchPolicy {
    fun decide(form: WindowForm): FanLaunchAction =
        when (form) {
            WindowForm.VisibleWindow -> FanLaunchAction.FrontExisting
            WindowForm.BackgroundTask, WindowForm.None -> FanLaunchAction.LaunchAsZoom
        }
}
