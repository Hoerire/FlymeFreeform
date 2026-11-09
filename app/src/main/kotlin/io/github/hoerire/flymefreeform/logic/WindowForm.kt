package io.github.hoerire.flymefreeform.logic

/**
 * 目标应用此刻在屏幕上的状态，只分两类，用于扇形启动决策。
 *
 * 迷你窗、贴边浮标都属系统行为，模块不干预，因此不在此建模：
 * 它们不可见时按 [BackgroundTask] 处理，走正常小窗启动；系统按自己的规则呈现。
 */
enum class WindowForm {
    /** 该包没有任务。 */
    None,

    /** 该包有可见任务（前台全屏或屏幕上开着的小窗），此时不应重新启动。 */
    VisibleWindow,

    /** 有任务但不可见（后台、被收窗、被收成迷你或浮标），按正常小窗启动。 */
    BackgroundTask,
}
