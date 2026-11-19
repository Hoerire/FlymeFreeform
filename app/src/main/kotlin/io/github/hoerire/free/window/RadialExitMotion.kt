package io.github.hoerire.free.window

internal data class RadialExitVisuals(
    val contentAlpha: Float,
    val scrimAlpha: Float,
    val contentScale: Float,
)

/** Keeps radial items in place while their content and scrim leave on separate timelines. */
internal object RadialExitMotion {
    /**
     * 取消手势时的「缩回」时长。
     *
     * 比展开短：展开是"铺开给人看"，收起只需要交代清楚"收回去了"，
     * 拖久了会让下一次呼出显得迟钝。
     */
    const val RETRACT_DURATION_MILLIS = 240L

    /**
     * 选中目标后的缩回时长。
     *
     * 这条路径上应用已经在启动，浮层只做最后一眼"收回"，不能挡住新窗口太久；
     * 取屏类工具不播这段动画（要求屏幕立刻干净）。
     */
    const val COMMIT_RETRACT_DURATION_MILLIS = 150L

    private const val CONTENT_END_FRACTION = 0.72f
    private const val MIN_CONTENT_SCALE = 0.94f

    fun sample(linearProgress: Float): RadialExitVisuals {
        val progress = linearProgress.coerceIn(0f, 1f)
        val contentProgress = smoothStep((progress / CONTENT_END_FRACTION).coerceIn(0f, 1f))
        val scrimProgress = smoothStep(progress)
        return RadialExitVisuals(
            contentAlpha = 1f - contentProgress,
            scrimAlpha = 1f - scrimProgress,
            contentScale = 1f - (1f - MIN_CONTENT_SCALE) * contentProgress,
        )
    }

    private fun smoothStep(value: Float): Float = value * value * (3f - 2f * value)
}
