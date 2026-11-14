package io.github.hoerire.flymefreeform.logic

/**
 * 目标包在屏幕上的状态，供扇形启动判断用。
 */
enum class WindowForm {
    None,

    /** 屏幕上已有可见窗口：全屏或小窗，都不该再新建。 */
    VisibleWindow,

    /**
     * 任务存在、属于浮动场景，但当前不可见（迷你窗 / 贴边挂起）。
     * 需要让系统把它切回可见的小窗，而不是重新启动一个。
     */
    FlexibleHidden,

    /** 只有后台的普通全屏任务：按正常小窗启动。 */
    BackgroundTask,
}
