package io.github.hoerire.free.platform.coloros

import io.github.hoerire.free.platform.coloros.ColorOsFlexibleWindowState.MINI
import io.github.hoerire.free.platform.coloros.ColorOsFlexibleWindowState.ORDINARY
import io.github.hoerire.free.platform.coloros.ColorOsFlexibleWindowState.READY
import io.github.hoerire.free.platform.coloros.ColorOsFlexibleWindowState.UNREADABLE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsFlexibleWindowStateTest {

    private val displayWidth = 1080
    private val displayHeight = 2400

    /** 迷你窗尺寸：宽高都只有屏幕的一小半。 */
    private fun isMini(
        apiSaysMini: Boolean?,
        zoomState: Int,
        widthPx: Int = 400,
        heightPx: Int = 500,
        screenWidth: Int = displayWidth,
        screenHeight: Int = displayHeight,
    ): Boolean =
        ColorOsFlexibleWindowState.isMiniWindow(
            apiSaysMini,
            zoomState,
            widthPx,
            heightPx,
            screenWidth,
            screenHeight,
        )

    @Test
    fun systemApiMiniStateIsMini() {
        assertTrue(isMini(apiSaysMini = true, zoomState = UNREADABLE))
        assertTrue(isMini(apiSaysMini = true, zoomState = MINI))
        // API 说迷你时即便尺寸还没缩到底也算：动画中途的状态不该影响判定。
        assertTrue(isMini(apiSaysMini = true, zoomState = UNREADABLE, widthPx = 900, heightPx = 1600))
    }

    @Test
    fun zoomFieldMiniIsMini() {
        assertTrue(isMini(apiSaysMini = null, zoomState = MINI))
        assertTrue(isMini(apiSaysMini = false, zoomState = MINI))
    }

    @Test
    fun ordinaryWindowIsNotMiniEvenWhenSmall() {
        // 普通小窗被用户手动缩到很小，长按仍属于系统的「缩成迷你窗」，不能被模块吃掉。
        assertFalse(isMini(apiSaysMini = false, zoomState = ORDINARY))
        assertFalse(isMini(apiSaysMini = false, zoomState = UNREADABLE))
        assertFalse(isMini(apiSaysMini = null, zoomState = ORDINARY))
    }

    @Test
    fun edgeHungReadyIsNotMini() {
        // ready 态是贴边悬挂，不是用户所说的迷你窗。
        assertFalse(isMini(apiSaysMini = false, zoomState = READY))
        assertFalse(isMini(apiSaysMini = null, zoomState = READY))
    }

    @Test
    fun unreadableStateFallsBackToSize() {
        assertTrue(isMini(apiSaysMini = null, zoomState = UNREADABLE))
        assertFalse(
            isMini(
                apiSaysMini = null,
                zoomState = UNREADABLE,
                widthPx = 900,
                heightPx = 1600,
            ),
        )
    }

    @Test
    fun unreadableStateNeedsBothDimensionsSmall() {
        // 只有一边小的是普通小窗被拉长/压扁，不是「最小的可见窗口」。
        assertFalse(isMini(apiSaysMini = null, zoomState = UNREADABLE, widthPx = 400, heightPx = 1600))
        assertFalse(isMini(apiSaysMini = null, zoomState = UNREADABLE, widthPx = 900, heightPx = 400))
    }

    @Test
    fun unreadableStateWithMissingGeometryIsNotMini() {
        assertFalse(isMini(apiSaysMini = null, zoomState = UNREADABLE, widthPx = 0, heightPx = 0))
        assertFalse(isMini(apiSaysMini = null, zoomState = UNREADABLE, screenWidth = 0, screenHeight = 0))
    }
}
