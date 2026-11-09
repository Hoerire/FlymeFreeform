package io.github.hoerire.flymefreeform.window

import android.view.animation.PathInterpolator
import kotlin.math.PI
import kotlin.math.exp

/**
 * 面板动画参数。
 *
 * 入场不做自定义弹簧，直接跟随系统启动动画：时长 300 ms（受系统"动画时长缩放"影响，
 * 由调用方的 elapsed/durationScale 体现），曲线用系统的 fast_out_slow_in
 * （cubic-bezier(0.4, 0, 0.2, 1)），与 Activity / 小窗打开观感一致。
 * 退场仍保留临界阻尼弹簧（与 ColorOS 原生面板退场一致）。
 */
internal object AllAppsPanelMotion {
    data class Frame(val alpha: Float, val scale: Float)

    /** 系统 Activity 打开动画时长。 */
    private const val ENTER_DURATION_SECONDS = 0.45f

    /** 入场固定时长（毫秒）：完成判定用它，避免曲线尾部被判成"已到 1"而提前收工。 */
    const val ENTER_DURATION_MS = 450L

    private val enterInterpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    fun exit(seconds: Float): Frame = Frame(
        alpha = remaining(seconds, 0.3f),
        scale = 0.3f + 0.7f * remaining(seconds, 0.4f),
    )

    fun enter(seconds: Float): Frame {
        val progress = (seconds / ENTER_DURATION_SECONDS).coerceIn(0f, 1f)
        val eased = enterInterpolator.getInterpolation(progress)
        // 保留原实现的铺开幅度：缩放从 0.3 放大到 1，只是把时长与曲线换成系统那套。
        return Frame(alpha = eased, scale = 0.3f + 0.7f * eased)
    }

    private fun remaining(seconds: Float, period: Float): Float {
        val time = seconds.coerceAtLeast(0f)
        val phase = 2.0 * PI * time / period
        return ((1.0 + phase) * exp(-phase)).toFloat().coerceIn(0f, 1f)
    }

    // 与原生 alpha 的最小可见变化量及其位移阈值一致。
    const val EXIT_ALPHA_THRESHOLD = 0.00390625f * 0.75f
    const val MAX_DURATION_MS = 1_000L
}
