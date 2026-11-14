package io.github.hoerire.flymefreeform.window

internal data class RadialEntryVisuals(
    val contentAlpha: Float,
    val radialProgress: Float,
    /** Multiplier for the horizontal, inward overshoot distance; may become negative. */
    val horizontalOvershoot: Float,
    val rotationDegrees: Float,
    val iconScale: Float,
)

/** 扇形进场：半径从角落铺开到满值，图标一路放大并轻微回弹。 */
internal object RadialEntryMotion {
    /**
     * 进场总时长（毫秒），从"手指按下、浮层开始创建"那一刻算起。
     *
     * 注意这是**整段时间预算**，不是 tween 的时长：窗口 addView 到出第一帧之间的
     * 延迟由 CornerRadialOverlayView 从这里面扣掉，因此展开完成时刻是固定的。
     */
    const val DURATION_MILLIS = 300L
    const val SELECTION_DURATION_MILLIS = 130L
    const val HORIZONTAL_OVERSHOOT_DP = 6f
    const val SELECTION_RING_ALPHA = 64f / 255f

    /** 淡入曲线：Material 3 emphasized decelerate。 */
    private val fade =
        CubicMotionCurve(
            SystemMotion.EMPHASIZED_DECELERATE_X1,
            SystemMotion.EMPHASIZED_DECELERATE_Y1,
            SystemMotion.EMPHASIZED_DECELERATE_X2,
            SystemMotion.EMPHASIZED_DECELERATE_Y2,
        )

    /**
     * 缩放回弹曲线：中间控制点 1.32，采样值轻微超过 1 再回落。
     *
     * CubicMotionCurve 只钳制输入，输出允许越界，这才有"冲过头再收回来"的手感。
     */
    private val springEase = CubicMotionCurve(0.25f, 1.32f, 0.5f, 1f)

    /**
     * 展开曲线：起步陡、收尾缓，最快把半径铺满。
     *
     * 历史：(0.45, 0, …) 头三分之一几乎不动；(0.3, 0, 0.1f) 仍偏慢；
     * 现在前段更陡，配合总时长预算，抬手就铺开。
     */
    private val expandEase = CubicMotionCurve(0.2f, 0f, 0f, 1f)

    private val selection = CubicMotionCurve(0.2f, 0f, 0f, 1f)

    fun sample(linearProgress: Float): RadialEntryVisuals {
        val progress = linearProgress.coerceIn(0f, 1f)
        val spring = springEase.sample(progress)
        // 透明度提前到位：飞行途中图标必须是实心的，半透明叠加高速位移将产生残影。
        val alphaRamp = fade.sample((progress * 1.6f).coerceIn(0f, 1f))
        return RadialEntryVisuals(
            contentAlpha = alphaRamp,
            // 半径从 0 长到 100%：图标从角落飞出来，而不是直接出现在终点。
            radialProgress = expandEase.sample(progress),
            horizontalOvershoot = 0f,
            rotationDegrees = 0f,
            // 起步 0.7：整段飞行图标都是完整可辨的，只做最后一段放大回弹。
            iconScale = 0.7f + 0.3f * spring,
        )
    }

    /** Animates ring thickness only; the selected icon retains its size. */
    fun selectionProgress(linearProgress: Float): Float = selection.sample(linearProgress)
}

/** Evaluates time-based cubic motion while preserving values outside the unit interval. */
internal class CubicMotionCurve(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {
    fun sample(linearProgress: Float): Float {
        val x = linearProgress.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        var low = 0f
        var high = 1f
        repeat(20) {
            val parameter = (low + high) * 0.5f
            if (coordinate(parameter, x1, x2) < x) {
                low = parameter
            } else {
                high = parameter
            }
        }
        // Time is bounded, but the curve's output must retain its overshoot.
        return coordinate((low + high) * 0.5f, y1, y2)
    }

    private fun coordinate(parameter: Float, first: Float, second: Float): Float {
        val remainder = 1f - parameter
        return 3f * remainder * remainder * parameter * first +
            3f * remainder * parameter * parameter * second +
            parameter * parameter * parameter
    }
}
