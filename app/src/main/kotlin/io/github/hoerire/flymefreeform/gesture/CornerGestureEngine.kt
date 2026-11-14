package io.github.hoerire.flymefreeform.gesture

import kotlin.math.max

internal enum class CornerSide {
    Left,
    Right,
}

internal enum class GesturePhase {
    Idle,
    Armed,
    Revealing,
    Selecting,
    Committing,
    MorePanel,
    Cancelling,
}

internal sealed interface GestureAction {
    data object Ignore : GestureAction
    data object PassThrough : GestureAction
    data class Activate(
        val side: CornerSide,
        val originX: Float,
        val originY: Float,
        val x: Float,
        val y: Float,
    ) : GestureAction
    data class Update(val side: CornerSide, val x: Float, val y: Float) : GestureAction
    data class Commit(val selectedIndex: Int?) : GestureAction
    data object Cancel : GestureAction
}

internal data class CornerGestureConfig(
    val displayWidth: Float,
    val displayHeight: Float,
    val triggerRadius: Float,
    val inwardThreshold: Float,
    val upwardThreshold: Float,
    val reverseTolerance: Float,
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
)

internal object AdaptiveCornerGestureConfig {
    fun create(
        displayWidth: Float,
        displayHeight: Float,
        touchSlop: Float,
        density: Float,
        triggerRangeDp: Int,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): CornerGestureConfig {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val triggerRadius = CornerTriggerRegion.radiusPx(triggerRangeDp, safeDensity)
        return CornerGestureConfig(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            triggerRadius = triggerRadius,
            inwardThreshold = max(touchSlop * INWARD_SLOP_MULTIPLIER, safeDensity * INWARD_DP),
            upwardThreshold = max(touchSlop * UPWARD_SLOP_MULTIPLIER, safeDensity * UPWARD_DP),
            reverseTolerance = max(touchSlop, safeDensity * REVERSE_TOLERANCE_DP),
            leftEnabled = leftEnabled,
            rightEnabled = rightEnabled,
        )
    }

    private const val INWARD_SLOP_MULTIPLIER = 1.75f
    private const val UPWARD_SLOP_MULTIPLIER = 0.50f
    private const val INWARD_DP = 14f
    private const val UPWARD_DP = 4f
    private const val REVERSE_TOLERANCE_DP = 8f
}

/** 单指角落手势状态机；不持有 MotionEvent，便于跨进程入口复用与单元测试。 */
internal class CornerGestureEngine {
    var phase: GesturePhase = GesturePhase.Idle
        private set
    var selectedIndex: Int? = null
        private set

    private var pointerId = -1
    private var originX = 0f
    private var originY = 0f
    private var side: CornerSide? = null
    private var claimed = false

    fun down(pointerId: Int, x: Float, y: Float, config: CornerGestureConfig): GestureAction {
        reset()
        val detectedSide = detectSide(x, y, config) ?: return GestureAction.PassThrough
        this.pointerId = pointerId
        originX = x
        originY = y
        side = detectedSide
        phase = GesturePhase.Armed
        return GestureAction.PassThrough
    }

    fun move(
        pointerId: Int,
        pointerCount: Int,
        x: Float,
        y: Float,
        config: CornerGestureConfig,
    ): GestureAction {
        val activeSide = side ?: return GestureAction.PassThrough
        // 只跟随发起手势的那根手指：滑到图标附近时拇指侧面常会多报一根手指，
        // 以前这里直接 cancel，表现为扇形自行关闭（类似断触）。
        if (pointerId != this.pointerId) return cancel()

        val inward = if (activeSide == CornerSide.Left) x - originX else originX - x
        val upward = originY - y
        // 滑动期间不因反向移动取消：只有抬手或系统取消触摸才结束手势，
        // 否则滑到图标附近时手指轻微回退，就会出现"划着划着扇形自己没了"。
        if (!claimed) {
            val reverseLimit = config.reverseTolerance * 3f
            if (inward < -reverseLimit || upward < -reverseLimit) {
                return cancel()
            }
        }
        if (!claimed) {
            if (inward < config.inwardThreshold || upward < config.upwardThreshold) {
                return GestureAction.PassThrough
            }
            // 垂直上滑不是角区手势，那是系统的「回桌面 / 最近任务」。
            // 以前只要求 inward ≥ 14dp、upward ≥ 4dp 就激活，而手指从角落往上滑时
            // 一点横向抖动就能超过 49px，于是上滑会顺手把扇形拉起来。
            // 角区手势是斜向的（inward 与 upward 量级相当），系统上滑几乎是纯垂直。
            if (upward > inward * VERTICAL_UPWARD_RATIO) {
                return GestureAction.PassThrough
            }
            claimed = true
            phase = GesturePhase.Revealing
            return GestureAction.Activate(activeSide, originX, originY, x, y)
        }

        phase = GesturePhase.Selecting
        return GestureAction.Update(activeSide, x, y)
    }

    /**
     * 抢流之后系统会重发一次 DOWN 来重启这条流。那不是一次新手势，只是基准点变了：
     * 把 origin 挪到新位置，保留 claimed / phase，后续 MOVE 继续走 Update。
     * 如果在这里走 down()，phase 会被打回 Armed，扇形就再也收不到 Update。
     */
    fun rebase(x: Float, y: Float) {
        originX = x
        originY = y
    }

    private companion object {
        /**
         * upward / inward 超过这个比值就按「垂直上滑」处理，不接管。
         *
         * 角区手势是斜着滑出来的（两者量级相近），系统上滑回桌面几乎是纯垂直。
         * 3 倍留足余量：手指抖一点、稍微斜一点的正常呼出都不会被误判成上滑。
         */
        const val VERTICAL_UPWARD_RATIO = 3f
    }

    fun up(pointerId: Int): GestureAction {
        if (pointerId != this.pointerId) return cancel()
        if (!claimed) {
            reset()
            return GestureAction.PassThrough
        }
        val selection = selectedIndex
        clearTracking(GesturePhase.Committing)
        val result = GestureAction.Commit(selection)
        return result
    }

    fun cancel(): GestureAction {
        val wasClaimed = claimed
        clearTracking(if (wasClaimed) GesturePhase.Cancelling else GesturePhase.Idle)
        return if (wasClaimed) GestureAction.Cancel else GestureAction.PassThrough
    }

    fun setSelection(index: Int?) {
        if (claimed) selectedIndex = index
    }

    val isClaimed: Boolean
        get() = claimed

    private fun detectSide(x: Float, y: Float, config: CornerGestureConfig): CornerSide? {
        return CornerTriggerRegion.detectSide(
            x = x,
            y = y,
            displayWidth = config.displayWidth,
            displayHeight = config.displayHeight,
            radius = config.triggerRadius,
            leftEnabled = config.leftEnabled,
            rightEnabled = config.rightEnabled,
        )
    }

    private fun reset() {
        clearTracking(GesturePhase.Idle)
    }

    private fun clearTracking(nextPhase: GesturePhase) {
        phase = nextPhase
        selectedIndex = null
        pointerId = -1
        originX = 0f
        originY = 0f
        side = null
        claimed = false
    }
}
