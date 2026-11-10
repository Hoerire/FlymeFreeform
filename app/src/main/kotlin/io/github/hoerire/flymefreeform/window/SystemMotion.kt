package io.github.hoerire.flymefreeform.window

import android.animation.ValueAnimator

/**
 * 跟随系统动效设置。
 *
 * 系统设置里的「动画时长缩放」对所有系统动画生效，模块的扇形也应该一致：
 * - scale = 0（关闭动画）：直接跳到终态，不做任何过渡；
 * - scale > 1：按同一比例拉长，与系统窗口动画同步，不会出现"我们的动画先走完、系统还在动"。
 *
 * 曲线取 Material 3 的 emphasized 系列，这也是 ColorOS 窗口动画的基准：
 * 进场用 decelerate（先快后慢，快速铺开再稳稳停住），退场用 accelerate（先蓄势再快速收回）。
 */
internal object SystemMotion {
    /** Material 3 emphasized decelerate。 */
    const val EMPHASIZED_DECELERATE_X1 = 0.05f
    const val EMPHASIZED_DECELERATE_Y1 = 0.7f
    const val EMPHASIZED_DECELERATE_X2 = 0.1f
    const val EMPHASIZED_DECELERATE_Y2 = 1f

    /** Material 3 emphasized accelerate。 */
    const val EMPHASIZED_ACCELERATE_X1 = 0.3f
    const val EMPHASIZED_ACCELERATE_Y1 = 0f
    const val EMPHASIZED_ACCELERATE_X2 = 0.8f
    const val EMPHASIZED_ACCELERATE_Y2 = 0.15f

    /** 当前系统动画时长缩放；读不到时按 1 处理。 */
    fun durationScale(): Float =
        try {
            ValueAnimator.getDurationScale()
        } catch (_: Throwable) {
            1f
        }

    /** 按系统缩放换算后的时长（毫秒）；调用方应先用 [animationsRunning] 判断是否该直接跳到终态。 */
    fun scaledMillis(baseMillis: Long, scale: Float): Int =
        (baseMillis * scale).toInt().coerceAtLeast(1)

    /** 系统是否允许播放动画：开关为关、或缩放被设为 0 时都返回 false。 */
    fun animationsRunning(scale: Float): Boolean =
        scale > 0f && runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
}
