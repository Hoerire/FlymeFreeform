package io.github.hoerire.flymefreeform.window

internal fun portalPopupTop(touchY: Int, height: Int, gap: Int, top: Int, bottom: Int): Int {
    val lastTop = (bottom - height).coerceAtLeast(top)
    val above = touchY - gap - height
    val below = touchY + gap
    return when {
        above >= top -> above.coerceAtMost(lastTop)
        below <= lastTop -> below.coerceAtLeast(top)
        else -> above.coerceIn(top, lastTop)
    }
}
