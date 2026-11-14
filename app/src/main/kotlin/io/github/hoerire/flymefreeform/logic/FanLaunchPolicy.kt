package io.github.hoerire.flymefreeform.logic

/** 扇形松手后对目标要执行的动作。 */
enum class FanLaunchAction {
    FrontExisting,
    ToggleFlexible,
    LaunchAsZoom,
}

/**
 * 已有窗口分三种处理：
 *   可见窗口（全屏 / 小窗）        → 前置，不新建
 *   浮动场景但不可见（迷你 / 贴边 / 退出中） → 重新以小窗启动
 *   其余后台任务                   → 正常小窗启动
 *
 * 曾经这里走系统的 toggleFlexibleWindow(包名)，但那是个死调用：
 * 反编译 OplusFlexibleAnimationRunner.toggleFlexibleWindow(String) 可见参数是
 * "type_toggle_to_flexible_task" 这类**类型串**，不是包名；传包名只会走到
 * "unknown type" 分支什么都不做，表现为"点了轮盘没反应"。
 * 所以改回启动路径：和手动点桌面图标一致，系统的复用逻辑会把那个不可见任务拉起来。
 *
 * 可见窗口仍不能直接重启：已有任务时系统会命中 getReusableOptionsForFreeForm，
 * 原地改写参数且没有动画。
 */
object FanLaunchPolicy {
    /**
     * @param colorOs17 ColorOS 17 及以上：不可见的浮动窗口改用启动路径重新拉起；
     *   16 及更早沿用原来的"让系统切回小窗"分支，不做接管。
     */
    fun decide(form: WindowForm, colorOs17: Boolean): FanLaunchAction =
        when (form) {
            WindowForm.VisibleWindow -> FanLaunchAction.FrontExisting
            WindowForm.FlexibleHidden ->
                if (colorOs17) FanLaunchAction.LaunchAsZoom else FanLaunchAction.ToggleFlexible
            WindowForm.BackgroundTask, WindowForm.None -> FanLaunchAction.LaunchAsZoom
        }
}
