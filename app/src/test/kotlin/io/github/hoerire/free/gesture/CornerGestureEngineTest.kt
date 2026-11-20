package io.github.hoerire.free.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CornerGestureEngineTest {
    private val config =
        CornerGestureConfig(
            displayWidth = 1000f,
            displayHeight = 2000f,
            triggerRadius = 100f,
            inwardThreshold = 14f,
            upwardThreshold = 4f,
            reverseTolerance = 8f,
            leftEnabled = true,
            rightEnabled = true,
        )

    @Test
    fun leftAndRightRequireMirroredInwardMovement() {
        val left = CornerGestureEngine()
        left.down(0, 5f, 1995f, config)
        assertTrue(left.move(0, 1, 20f, 1989f, config) is GestureAction.Activate)

        val right = CornerGestureEngine()
        right.down(0, 995f, 1995f, config)
        assertTrue(right.move(0, 1, 980f, 1989f, config) is GestureAction.Activate)
    }

    @Test
    fun reverseMoveCancelsButSecondPointerDoesNot() {
        val reverse = CornerGestureEngine()
        reverse.down(0, 5f, 1995f, config)
        assertTrue(reverse.move(0, 1, -5f, 1995f, config) is GestureAction.PassThrough)
        assertFalse(reverse.isClaimed)

        // 第二根手指不参与判定：引擎只跟随发起手势的那根手指，
        // 否则滑到图标附近拇指侧面多报一根手指时，扇形会像断触一样自己关掉。
        val multi = CornerGestureEngine()
        multi.down(0, 5f, 1995f, config)
        assertTrue(multi.move(0, 2, 30f, 1980f, config) is GestureAction.Activate)
        assertTrue(multi.isClaimed)
    }

    @Test
    fun repeatedMovesActivateOnlyOnceThenUpdate() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        val activation = engine.move(0, 1, 25f, 1985f, config)
        assertTrue(activation is GestureAction.Activate)
        activation as GestureAction.Activate
        assertEquals(CornerSide.Left, activation.side)
        assertEquals(5f, activation.originX, 0f)
        assertEquals(1995f, activation.originY, 0f)
        assertEquals(25f, activation.x, 0f)
        assertEquals(1985f, activation.y, 0f)
        assertTrue(engine.move(0, 1, 35f, 1975f, config) is GestureAction.Update)
        assertTrue(engine.up(0) is GestureAction.Commit)
    }

    @Test
    fun tapAndSubThresholdMovementNeverClaimInput() {
        val engine = CornerGestureEngine()

        assertTrue(engine.down(0, 5f, 1995f, config) is GestureAction.PassThrough)
        assertTrue(engine.move(0, 1, 15f, 1992f, config) is GestureAction.PassThrough)
        assertTrue(engine.up(0) is GestureAction.PassThrough)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun cancelAfterActivationReportsCancellationAndResets() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        engine.move(0, 1, 25f, 1985f, config)
        assertTrue(engine.cancel() is GestureAction.Cancel)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun secondPointerKeepsTheMenuAndOtherPointersAreIgnored() {
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        engine.move(0, 1, 25f, 1985f, config)

        // 多一根手指只带 pointerCount，不影响当前手势。
        assertTrue(engine.move(0, 2, 30f, 1980f, config) is GestureAction.Update)
        assertTrue(engine.isClaimed)

        // 误触的另一根手指：移动或抬起都只忽略，不能把主手势取消掉。
        assertTrue(engine.move(1, 2, 35f, 1975f, config) is GestureAction.Ignore)
        assertTrue(engine.up(1) is GestureAction.Ignore)
        assertTrue(engine.isClaimed)

        // 主手指抬起才提交。
        assertTrue(engine.up(0) is GestureAction.Commit)
        assertFalse(engine.isClaimed)
    }

    @Test
    fun horizontalDragFromCornerIsNotACornerGesture() {
        // 视频进度条、横向滑动的列表就压在触发范围内：从角落起手横向拖动时 upward≈0，
        // 以前只挡了"过于垂直"，这种手势会被当成斜推并抢流，表现为进度条拖不动。
        val engine = CornerGestureEngine()
        engine.down(0, 5f, 1995f, config)
        assertTrue(engine.move(0, 1, 120f, 1993f, config) is GestureAction.PassThrough)
        assertFalse(engine.isClaimed)
        assertTrue(engine.move(0, 1, 300f, 1990f, config) is GestureAction.PassThrough)
        assertFalse(engine.isClaimed)
        // 反向（朝屏幕外）拖动同样不能接管。
        val outward = CornerGestureEngine()
        outward.down(0, 5f, 1995f, config)
        assertTrue(outward.move(0, 1, -80f, 1992f, config) is GestureAction.PassThrough)
        assertFalse(outward.isClaimed)
    }

    @Test
    fun shallowDiagonalIsNotClaimedButSteepDiagonalIs() {
        // 约 8° 的浅斜向（inward 200 / upward 30）：更像横向滑动，不接管。
        val shallow = CornerGestureEngine()
        shallow.down(0, 5f, 1995f, config)
        assertTrue(shallow.move(0, 1, 205f, 1965f, config) is GestureAction.PassThrough)
        assertFalse(shallow.isClaimed)

        // 45°：正常呼出，必须接管。
        val steep = CornerGestureEngine()
        steep.down(0, 5f, 1995f, config)
        assertTrue(steep.move(0, 1, 60f, 1940f, config) is GestureAction.Activate)
        assertTrue(steep.isClaimed)
    }

    @Test
    fun configuredRangeUsesDpWhileMovementThresholdsIgnoreRange() {
        val compact =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 800f,
                displayHeight = 1600f,
                touchSlop = 10f,
                density = 2f,
                triggerRangeDp = 84,
                leftEnabled = true,
                rightEnabled = true,
            )
        val wide =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 1600f,
                displayHeight = 2400f,
                touchSlop = 10f,
                density = 2f,
                triggerRangeDp = 160,
                leftEnabled = true,
                rightEnabled = true,
            )
        val largerTouchSlop =
            AdaptiveCornerGestureConfig.create(
                displayWidth = 800f,
                displayHeight = 1600f,
                touchSlop = 30f,
                density = 2f,
                triggerRangeDp = 84,
                leftEnabled = true,
                rightEnabled = true,
            )

        assertTrue(wide.triggerRadius > compact.triggerRadius)
        assertTrue(largerTouchSlop.triggerRadius == compact.triggerRadius)
        assertTrue(wide.inwardThreshold == compact.inwardThreshold)
        assertTrue(wide.upwardThreshold == compact.upwardThreshold)
        assertTrue(largerTouchSlop.inwardThreshold > compact.inwardThreshold)
    }
}
