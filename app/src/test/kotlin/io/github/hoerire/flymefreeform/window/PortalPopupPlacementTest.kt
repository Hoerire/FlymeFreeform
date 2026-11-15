package io.github.hoerire.flymefreeform.window

import org.junit.Assert.assertEquals
import org.junit.Test

class PortalPopupPlacementTest {
    @Test
    fun anchorsAboveTouchWhenThereIsRoom() {
        assertEquals(310, portalPopupTop(400, 80, 10, 24, 760))
    }

    @Test
    fun opensBelowTouchNearTop() {
        assertEquals(56, portalPopupTop(46, 80, 10, 24, 760))
    }

    @Test
    fun clampsWhenNeitherSideHasRoom() {
        assertEquals(24, portalPopupTop(65, 100, 10, 24, 130))
    }
}
