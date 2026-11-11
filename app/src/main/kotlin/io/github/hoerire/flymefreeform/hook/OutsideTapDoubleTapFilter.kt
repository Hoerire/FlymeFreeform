package io.github.hoerire.flymefreeform.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 双击关闭的输入闸门：在事件分发之前扣住可能的双击。
 *
 * 只在 [OutsideTapGate] 判定「双击模式 + 有普通小窗 + 落点在窗外」时介入；
 * 其余情况一律原样放行，行为与没有这个过滤器时完全一致。
 *
 * 判定流程：
 * - DOWN：扣住，等后续事件决定；
 * - MOVE 超过 [touchSlop]：判为滑动，把扣住的事件补发，之后这条路全部放行；
 * - 超时（[doubleTapTimeout]）没等到第二击：补发这一下，应用晚一点收到；
 * - 第二击在超时内到达：两下都丢弃，并关闭小窗。
 *
 * 单击因此会延迟约 [doubleTapTimeout]（系统 300ms），这是双击判定的固有代价。
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
    private var firstDownAt = 0L
    private var lastPolicyFlags = 0
    private var capturing = false
    private var awaitingSecondTap = false

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
            else -> if (capturing || awaitingSecondTap) motion.recycle() else forward(event, policyFlags)
        }
    }

    private fun handleDown(event: MotionEvent) {
        if (awaitingSecondTap) {
            // 第二击到达：双击成立，两下都不落到应用。
            cancelFlush()
            awaitingSecondTap = false
            capturing = false
            firstDown?.recycle()
            firstDown = null
            firstUp?.recycle()
            firstUp = null
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
        firstDownAt = SystemClock.uptimeMillis()
        firstDown = MotionEvent.obtain(event)
        event.recycle()
    }

    private fun handleMove(event: MotionEvent) {
        if (!capturing) {
            forward(event, lastPolicyFlags)
            return
        }
        val down = firstDown
        if (down != null) {
            val dx = event.rawX - down.rawX
            val dy = event.rawY - down.rawY
            if (dx * dx + dy * dy > (touchSlop * touchSlop).toFloat()) {
                // 判为滑动：补发按下，后续正常放行。
                ModuleLog.info("OUTSIDE_TAP_PASS_SLIDE")
                flushPending()
            }
        }
        if (capturing) event.recycle() else forward(event, lastPolicyFlags)
    }

    private fun handleUp(event: MotionEvent) {
        if (!capturing) {
            forward(event, lastPolicyFlags)
            return
        }
        if (!OutsideTapGate.isDoubleTapMode()) {
            // 单击模式：抬手就确定是单击，立即丢弃并关窗，不需要等任何窗口。
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

    /** 丢弃扣住的事件，不补发（用于单击成立或取消）。 */
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
