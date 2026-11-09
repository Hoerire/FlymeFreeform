package io.github.hoerire.flymefreeform.logic

/**
 * 目标应用此刻的窗口形态。
 *
 * ColorOS 的三种小窗形态共享同一个任务对象，区别只在厂商状态与 surface 表现：
 * - [SmallWindow]：普通小窗，可见，可拖动可缩放
 * - [MiniWindow]：迷你窗（右上角那种小窗），可见，但 Task 边界仍是满屏
 * - [EdgeBubble]：贴边浮标，不可见，任务已移出柔性表
 * - [BackgroundTask]：后台全屏任务（被收窗或退到后台），不是小窗
 *
 * 形态判定只能依赖厂商状态值与可见性，**不能**依赖 Task 边界：实测迷你窗的
 * Task.getBounds() 仍是 1272x2800（与后台全屏任务完全相同）。
 */
enum class WindowForm {
    None,
    SmallWindow,
    MiniWindow,
    EdgeBubble,
    BackgroundTask,
}
