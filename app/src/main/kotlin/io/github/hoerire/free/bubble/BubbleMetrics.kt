package io.github.hoerire.free.bubble

import io.github.hoerire.free.config.ModulePreferences

/**
 * 气泡的几何基准与外观读取口。
 *
 * 视觉层（气泡、展开层、内容卡）只认这个接口，不认 Settings 快照、不认偏好键。
 * 这样外观参数只有一处来源，hook 换配置通道时视觉层不需要改。
 */
internal interface BubbleVisualConfig {
    /** 横向位置（屏幕宽度百分比，0–100）。 */
    fun positionX(landscape: Boolean): Int

    /** 纵向位置（屏幕高度百分比，0–100）。 */
    fun positionY(landscape: Boolean): Int

    /** -1 左贴边，0 不贴边，1 右贴边。 */
    fun dockEdge(landscape: Boolean): Int

    /** 整颗气泡缩放（60–140，100 为 92dp 原尺寸）。 */
    fun bubbleSizePercent(): Int

    /** 通知图标半径（60–150，100 为 20dp）。 */
    fun iconSizePercent(): Int

    /** 应用角标尺寸（50–180，100 为 16dp）。 */
    fun badgeSizePercent(): Int

    /** 装饰动效是否常驻（呼吸环、色散边）。关闭后只在交互时重绘。 */
    fun continuousMotion(): Boolean
}

/** 视觉层共用的数值基准；单位一律 dp，绘制时再乘密度。 */
internal object BubbleMetrics {
    /** 设计空间边长：一整颗气泡的窗口大小。 */
    const val DESIGN_DP = 92f

    const val DEFAULT_PORTRAIT_X = 88
    const val DEFAULT_PORTRAIT_Y = 88
    const val DEFAULT_LANDSCAPE_X = 92
    const val DEFAULT_LANDSCAPE_Y = 82

    /** 尺寸比例的上下限直接取自设置定义，避免视觉层与设置页各写一套区间。 */
    const val MIN_BUBBLE_SIZE_PERCENT = ModulePreferences.MIN_BUBBLE_SIZE_PERCENT
    const val MAX_BUBBLE_SIZE_PERCENT = ModulePreferences.MAX_BUBBLE_SIZE_PERCENT

    const val MIN_ICON_SIZE_PERCENT = ModulePreferences.MIN_BUBBLE_ICON_PERCENT
    const val MAX_ICON_SIZE_PERCENT = ModulePreferences.MAX_BUBBLE_ICON_PERCENT

    const val MIN_BADGE_SIZE_PERCENT = ModulePreferences.MIN_BUBBLE_BADGE_PERCENT
    const val MAX_BADGE_SIZE_PERCENT = ModulePreferences.MAX_BUBBLE_BADGE_PERCENT

    /** 内容卡的偏好位置；实际取值仍由设置快照统一收口（见 `ModulePreferences`）。 */
    const val CONTENT_AUTO = 0
    const val CONTENT_ABOVE = 1
    const val CONTENT_BELOW = 2

    /** 预留：屏幕内侧位置。[NotificationPreviewView.resolvePlacement] 尚未实现该分支。 */
    const val CONTENT_INWARD = 3
}
