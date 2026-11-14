package io.github.hoerire.flymefreeform.config

import org.junit.Assert.assertEquals
import org.junit.Test

/** 背景滑条的两个方向互斥：负值只压暗，正值只模糊，0 两侧都不动。 */
class BackdropLevelTest {
    @Test
    fun middleKeepsBackdropUntouched() {
        assertEquals(0f, ModulePreferences.backdropShadeAlpha(0), 0f)
        assertEquals(0, ModulePreferences.backdropBlurDp(0))
    }

    @Test
    fun negativeSideIsShadeOnly() {
        assertEquals(0.4f, ModulePreferences.backdropShadeAlpha(-40), 0.0001f)
        assertEquals(1f, ModulePreferences.backdropShadeAlpha(-100), 0f)
        assertEquals(0, ModulePreferences.backdropBlurDp(-100))
    }

    @Test
    fun positiveSideIsBlurOnly() {
        assertEquals(0f, ModulePreferences.backdropShadeAlpha(50), 0f)
        assertEquals(50, ModulePreferences.backdropBlurDp(50))
        assertEquals(ModulePreferences.MAX_RADIAL_BLUR_DP, ModulePreferences.backdropBlurDp(100))
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(1f, ModulePreferences.backdropShadeAlpha(-400), 0f)
        assertEquals(ModulePreferences.MAX_RADIAL_BLUR_DP, ModulePreferences.backdropBlurDp(400))
    }
}
