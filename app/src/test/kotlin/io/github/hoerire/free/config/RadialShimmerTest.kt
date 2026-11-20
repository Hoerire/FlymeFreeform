package io.github.hoerire.free.config

import org.junit.Assert.assertEquals
import org.junit.Test

class RadialShimmerTest {
    @Test
    fun legacySevenSegmentKeepsNewFieldsAtDefaults() {
        val shimmer = RadialShimmer.decode("210,55,90,55,18,85,20")

        assertEquals(210, shimmer.speedDegPerSecond)
        assertEquals(20, shimmer.selectionScalePercent)
        assertEquals(RadialShimmer.DEFAULT_SELECTED_PUSH_PERCENT, shimmer.selectedPushPercent)
    }

    @Test
    fun legacyEightSegmentIgnoresRemovedRingSize() {
        val shimmer = RadialShimmer.decode("210,55,90,55,18,85,20,120")

        assertEquals(20, shimmer.selectionScalePercent)
        assertEquals(RadialShimmer.DEFAULT_SELECTED_PUSH_PERCENT, shimmer.selectedPushPercent)
    }

    @Test
    fun newFieldsRoundTripAndAreCoerced() {
        val shimmer = RadialShimmer(selectedPushPercent = 16)

        val restored = RadialShimmer.decode(shimmer.encode())

        assertEquals(16, restored.selectedPushPercent)
        // 越界值会被收敛到区间上限。
        assertEquals(
            RadialShimmer.SELECTED_PUSH_RANGE.last,
            RadialShimmer.decode("210,55,90,55,18,85,20,99,0").selectedPushPercent,
        )
    }
}
