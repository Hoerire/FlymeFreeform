package io.github.hoerire.flymefreeform.window

internal data class RadialDismissVisuals(
    val contentAlpha: Float,
    val radialProgress: Float,
    val horizontalOvershoot: Float,
    val rotationDegrees: Float,
    val iconScale: Float,
)

/** Reverses the corner expansion after a brief rotation and horizontal anticipation. */
internal object RadialDismissMotion {
    const val DURATION_MILLIS = 200L

    private const val PHASE_MILLIS = 100f
    private val fade =
        CubicMotionCurve(
            SystemMotion.EMPHASIZED_ACCELERATE_X1,
            SystemMotion.EMPHASIZED_ACCELERATE_Y1,
            SystemMotion.EMPHASIZED_ACCELERATE_X2,
            SystemMotion.EMPHASIZED_ACCELERATE_Y2,
        )
    private val anticipateRotation = CubicMotionCurve(0.17f, 0f, 0.53f, -6.56f)
    private val anticipateScale = CubicMotionCurve(0.17f, 0f, 0.53f, 0.7f)
    private val anticipatePosition = CubicMotionCurve(0.33f, 0f, 0.53f, -0.22f)
    // 收回走 Material 3 emphasized accelerate：先蓄一点势，再快速收拢，
    // 与系统关闭窗口/收起面板的节奏一致。
    private val collapseRotation =
        CubicMotionCurve(
            SystemMotion.EMPHASIZED_ACCELERATE_X1,
            SystemMotion.EMPHASIZED_ACCELERATE_Y1,
            SystemMotion.EMPHASIZED_ACCELERATE_X2,
            SystemMotion.EMPHASIZED_ACCELERATE_Y2,
        )
    private val collapseScale =
        CubicMotionCurve(
            SystemMotion.EMPHASIZED_ACCELERATE_X1,
            SystemMotion.EMPHASIZED_ACCELERATE_Y1,
            SystemMotion.EMPHASIZED_ACCELERATE_X2,
            SystemMotion.EMPHASIZED_ACCELERATE_Y2,
        )
    private val collapsePosition =
        CubicMotionCurve(
            SystemMotion.EMPHASIZED_ACCELERATE_X1,
            SystemMotion.EMPHASIZED_ACCELERATE_Y1,
            SystemMotion.EMPHASIZED_ACCELERATE_X2,
            SystemMotion.EMPHASIZED_ACCELERATE_Y2,
        )

    fun sample(linearProgress: Float): RadialDismissVisuals {
        val elapsed = linearProgress.coerceIn(0f, 1f) * DURATION_MILLIS
        if (elapsed <= PHASE_MILLIS) {
            val progress = elapsed / PHASE_MILLIS
            return RadialDismissVisuals(
                contentAlpha = 1f - fade.sample(progress),
                radialProgress = 1f,
                horizontalOvershoot = anticipatePosition.sample(progress),
                rotationDegrees = 5f * anticipateRotation.sample(progress),
                iconScale = 1f + 0.05f * anticipateScale.sample(progress),
            )
        }
        val progress = (elapsed - PHASE_MILLIS) / PHASE_MILLIS
        val remainingDistance = 1f - collapsePosition.sample(progress)
        return RadialDismissVisuals(
            contentAlpha = 0f,
            radialProgress = remainingDistance,
            horizontalOvershoot = remainingDistance,
            rotationDegrees = 5f - 275f * collapseRotation.sample(progress),
            iconScale = 0.8f + 0.25f * (1f - collapseScale.sample(progress)),
        )
    }
}
