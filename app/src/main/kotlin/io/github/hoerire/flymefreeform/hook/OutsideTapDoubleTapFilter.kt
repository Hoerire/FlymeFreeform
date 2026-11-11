package io.github.hoerire.flymefreeform.hook

import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import io.github.hoerire.flymefreeform.log.ModuleLog
import kotlin.math.abs

/**
 * 双击关闭的第二击拦截。
 *
 * 设计前提（来自实际使用）：**第一击照常穿透给下层应用**，用户本来就在小窗外
 * 继续操作。只有"第一击之后很快又点了一下"——也就是他真的在做双击——那一击才
 * 需要拦下来，否则应用会被点到两次。
 *
 * 这样做的代价最小：
 * - 单击完全无延迟，也不需要缓存／补发任何事件；
 * - 第二击到达时时间差已经确定，判定是即时的，同样没有延迟；
 * - 只需要在输入线程记一个"上一击的时间和位置"，其余事件原样放行。
 *
 * 是否介入由 [OutsideTapGate] 决定：双击模式、有普通小窗、落点在窗外、
 * 且不在输入法上。
 */
internal class OutsideTapDoubleTapFilter(
    private val touchSlop: Int,
    private val doubleTapTimeout: Long,
    private val forward: (InputEvent?, Int) -> Unit,
) {
    private var lastDownAt = 0L
    private var lastX = 0f
    private var lastY = 0f

    /** 正在吞掉第二击的剩余事件（MOVE / UP / CANCEL）。 */
    private var swallowing = false

    fun filter(event: InputEvent?, policyFlags: Int) {
        val motion = event as? MotionEvent
        if (motion == null) {
            forward(event, policyFlags)
            return
        }
        when (motion.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(motion, policyFlags)
            else -> {
                if (swallowing) {
                    if (motion.actionMasked == MotionEvent.ACTION_UP ||
                        motion.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        swallowing = false
                    }
                    motion.recycle()
                } else {
                    forward(event, policyFlags)
                }
            }
        }
    }

    private fun onDown(event: MotionEvent, policyFlags: Int) {
        val now = SystemClock.uptimeMillis()
        val withinTimeout = lastDownAt != 0L && now - lastDownAt <= doubleTapTimeout
        val nearLast =
            abs(event.rawX - lastX) <= touchSlop && abs(event.rawY - lastY) <= touchSlop
        if (withinTimeout && nearLast && OutsideTapGate.shouldCapture(event.rawX, event.rawY)) {
            // 第二击：吞掉它并关窗。第一击早就照常转发给应用了。
            swallowing = true
            lastDownAt = 0L
            ModuleLog.info("OUTSIDE_TAP_DOUBLE_FIRED")
            event.recycle()
            OutsideTapGate.performClose()
            return
        }
        // 第一击（或距离太远、超时）：记录后原样放行，让应用正常响应。
        if (OutsideTapGate.shouldCapture(event.rawX, event.rawY)) {
            lastDownAt = now
            lastX = event.rawX
            lastY = event.rawY
        } else {
            lastDownAt = 0L
        }
        forward(event, policyFlags)
    }
}
