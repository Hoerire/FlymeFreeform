package io.github.hoerire.free.window

internal data class RadialExitVisuals(
    val contentAlpha: Float,
    val scrimAlpha: Float,
    val contentScale: Float,
)

/**
 * 让扇形条目留在原地，内容与遮罩各自按不同时间线退出。
 *
 * 退场时长不在这里：取消与选中后收起共用设置项「收回时长」
 *（[io.github.hoerire.free.config.ModulePreferences.DEFAULT_RADIAL_RETRACT_DURATION_MS] 是默认值），
 * 本对象只负责「给定进度时各层画到什么程度」。
 */
internal object RadialExitMotion {
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
