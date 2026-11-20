package io.github.hoerire.free.window

/** 把面板的入场进度映射成从底角展开的形状，不缩放内容。 */
internal object PanelRevealMotion {
    fun extent(
        containerExtent: Float,
        seedExtent: Float,
        progress: Float,
    ): Float {
        val extent = containerExtent.coerceAtLeast(0f)
        val startExtent = seedExtent.coerceIn(0f, extent)
        val revealProgress = progress.coerceIn(0f, 1f)
        return startExtent + (extent - startExtent) * revealProgress
    }

    fun horizontalOffset(
        containerWidth: Float,
        revealWidth: Float,
        anchorOnLeft: Boolean,
    ): Float = if (anchorOnLeft) 0f else (containerWidth - revealWidth).coerceAtLeast(0f)

    fun verticalOffset(
        containerHeight: Float,
        revealHeight: Float,
    ): Float = (containerHeight - revealHeight).coerceAtLeast(0f)
}
