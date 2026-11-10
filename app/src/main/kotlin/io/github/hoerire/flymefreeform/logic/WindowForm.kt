package io.github.hoerire.flymefreeform.logic

/**
 * 目标包在屏幕上的状态，只分三种，供扇形启动判断用。
 * 迷你窗和贴边浮标由系统处理，模块不干预，所以不在这里建模：不可见时按 BackgroundTask 算。
 */
enum class WindowForm {
    None,
    VisibleWindow,
    BackgroundTask,
}
