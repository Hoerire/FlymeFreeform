package io.github.hoerire.flymefreeform.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 窗外关闭的输入闸门：在事件分发之前扣住"可能构成双击"的那一下。
 *
 * 为什么必须扣住第一击：不扣就会直接落到下层应用，而去掉防穿透的前提正是
 * "用户要在小窗之外继续操作"。如果第一击放行，双击关闭就变成"关窗顺手点了应用一下"，
 * 等于没有防穿透。所以只能先扣住，等一个双击窗口过去再决定：
 *
 * - **单击模式**：抬手即确定是单击 → 丢弃并关窗，没有额外等待；
 * - **双击模式**：
 *   - 位移超过 touchSlop → 判为滑动，补发扣住的事件，之后正常放行；
 *   - 300ms 内第二击到达 → 两下都丢弃并关窗；
 *   - 300ms 内没有第二击 → 补发这一对事件，应用晚 300ms 收到。
 *
 * 输入法区域由 [OutsideTapGate] 排除，打字不会被当成窗外双击。
 */
internal class OutsideTapDoubleTapFilter(
    private val touchSlop: Int,
    private val doubleTapTimeout: Long,
    private val forward: (InputEvent?, Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val flushRunnable = Runnable { flushPending() }

    private var firstDown: MotionEvent? = null
    private var firstUp: MotionEvent? = null
    private var lastPolicyFlags = 0
    private var capturing = false
    private var awaitingSecondTap = false

    /** 双击已成立，正在吞掉第二击剩余事件（否则孤立的 UP 会穿透到应用）。 */
    private var swallowingUntilUp = false

    fun filter(event: InputEvent?, policyFlags: Int) {
        val motion = event as? MotionEvent
        if (motion == null) {
            forward(event, policyFlags)
            return
        }
        lastPolicyFlags = policyFlags
        when (motion.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(motion)
            MotionEvent.ACTION_MOVE -> handleMove(motion)
            MotionEvent.ACTION_UP -> handleUp(motion)
            MotionEvent.ACTION_CANCEL -> handleCancel(motion)
            else -> if (capturing || awaitingSecondTap || swallowingUntilUp) {
                motion.recycle()
            } else {
                forward(event, policyFlags)
            }
        }
    }

    private fun handleDown(event: MotionEvent) {
        if (awaitingSecondTap) {
            // 第二击到达：双击成立，两下都不落到应用。
            cancelFlush()
            awaitingSecondTap = false
            firstDown?.recycle()
            firstDown = null
            firstUp?.recycle()
            firstUp = null
            swallowingUntilUp = true
            ModuleLog.info("OUTSIDE_TAP_DOUBLE_FIRED")
            event.recycle()
            OutsideTapGate.performClose()
            return
        }
        if (!OutsideTapGate.shouldCapture(event.rawX, event.rawY)) {
            forward(event, lastPolicyFlags)
            return
        }
        capturing = true
        firstDown = MotionEvent.obtain(event)
        event.recycle()
    }

    private fun handleMove(event: MotionEvent) {
        if (swallowingUntilUp) {
            event.recycle()
            return
        }
        if (!capturing) {
            forward(event, lastPolicyFlags)
            return
        }
        val down = firstDown
        if (down != null) {
            val dx = event.rawX - down.rawX
            val dy = event.rawY - down.rawY
            if (dx * dx + dy * dy > (touchSlop * touchSlop).toFloat()) {
                // 判为滑动：补发按下，之后正常放行。
                ModuleLog.info("OUTSIDE_TAP_PASS_SLIDE")
                flushPending()
            }
        }
        if (capturing) event.recycle() else forward(event, lastPolicyFlags)
    }

    private fun handleUp(event: MotionEvent) {
        if (swallowingUntilUp) {
            swallowingUntilUp = false
            event.recycle()
            return
        }
        if (!capturing) {
            forward(event, lastPolicyFlags)
            return
        }
        if (!OutsideTapGate.isDoubleTapMode()) {
            // 单击模式：抬手就确定，丢弃并关窗，没有额外等待。
            event.recycle()
            discardPending()
            ModuleLog.info("OUTSIDE_TAP_SINGLE_FIRED")
            OutsideTapGate.performClose()
            return
        }
        firstUp = MotionEvent.obtain(event)
        event.recycle()
        capturing = false
        awaitingSecondTap = true
        scheduleFlush()
    }

    private fun handleCancel(event: MotionEvent) {
        if (swallowingUntilUp) {
            swallowingUntilUp = false
            event.recycle()
            return
        }
        if (capturing || awaitingSecondTap) {
            ModuleLog.info("OUTSIDE_TAP_CANCEL_FLUSH")
            flushPending()
        } else {
            forward(event, lastPolicyFlags)
        }
    }

    private fun scheduleFlush() {
        cancelFlush()
        handler.postDelayed(flushRunnable, doubleTapTimeout)
    }

    private fun cancelFlush() {
        handler.removeCallbacks(flushRunnable)
    }

    private fun discardPending() {
        cancelFlush()
        firstDown?.recycle()
        firstUp?.recycle()
        firstDown = null
        firstUp = null
        capturing = false
        awaitingSecondTap = false
    }

    /** 判定不是双击：把扣住的按下与抬起补发出去。 */
    private fun flushPending() {
        cancelFlush()
        val down = firstDown
        val up = firstUp
        firstDown = null
        firstUp = null
        capturing = false
        awaitingSecondTap = false
        if (down == null) {
            up?.recycle()
            return
        }
        ModuleLog.info("OUTSIDE_TAP_PASS_SINGLE")
        forward(down, lastPolicyFlags)
        down.recycle()
        if (up != null) {
            forward(up, lastPolicyFlags)
            up.recycle()
        }
    }
}
