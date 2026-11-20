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
     * 与选中后的缩回统一为 500ms：图标沿错峰轨迹退回底角的过程完整可读，
     * 不会出现"一眨眼就没了"，也与展开的节奏对得上。
     */
    const val RETRACT_DURATION_MILLIS = 500L

    /**
     * 选中目标后的缩回时长。
     *
     * 与取消路径一致（500ms）：应用启动不等这段动画（启动在抬手当帧就交出去了），
     * 浮层只是最后看一眼"收回底角"。取屏类工具不播这段动画（要求屏幕立刻干净）。
     */
    const val COMMIT_RETRACT_DURATION_MILLIS = 500L

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
