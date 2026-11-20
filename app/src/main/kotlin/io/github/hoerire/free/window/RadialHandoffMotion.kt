package io.github.hoerire.free.window

import kotlin.math.max

internal data class RadialHandoffVisuals(
    val revealProgress: Float,
    val contentAlpha: Float,
    val contentScale: Float,
    val scrimProgress: Float,
)

/** 面板接手视觉状态时，让刚松手的扇形几何保持不动。 */
internal object RadialHandoffMotion {
    const val DURATION_MILLIS = 160L

    fun sample(
        frozenRevealProgress: Float,
        handoffProgress: Float,
        panelProgress: Float,
    ): RadialHandoffVisuals {
        val reveal = frozenRevealProgress.coerceIn(0f, 1f)
        val exit = RadialExitMotion.sample(handoffProgress)
        return RadialHandoffVisuals(
            revealProgress = reveal,
            contentAlpha = exit.contentAlpha,
            contentScale = exit.contentScale,
            scrimProgress = max(reveal, panelProgress.coerceIn(0f, 1f)),
        )
    }
}
