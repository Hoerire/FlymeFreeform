package io.github.hoerire.flymefreeform.hook

import android.graphics.Rect
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode

/**
 * 窗外关闭的输入闸门：在事件分发之前决定"这一下要不要先扣住"。
 *
 * 为什么需要它：小窗打开时用户还要操作下层应用，所以不能再用「扩展小窗触摸区」
 * 那套防穿透；但关闭动作要用的那一下又绝不能落到下层应用上。窗口层做不到两全——
 * 事件给窗口就不给应用，反之亦然。唯一的办法是站到分发之前（InputFilter）先判一次：
 *
 * - 单击模式：抬手就确定是单击，立即丢弃并关窗，**没有延迟**；
 * - 双击模式：先扣住，300ms 内等第二击；等到了两下都丢弃并关窗，
 *   没等到就补发这一下（应用晚 300ms 收到，这是双击判定的固有代价）。
 *
 * 状态由 [OutsideTapCloseHookInstaller] 在窗口区域更新时写入，由
 * [OutsideTapDoubleTapFilter] 在输入线程读取，因此字段全部 volatile。
 */
internal object OutsideTapGate {
    @Volatile
    private var mode = OutsideTapCloseMode.Disabled

    @Volatile
    private var bounds: Rect? = null

    @Volatile
    private var closeAction: (() -> Unit)? = null

    /**
     * 不该被扣住的区域：输入法就是典型。
     *
     * 键盘弹出时它整个落在小窗之外，若不排除，用户快速打字会被连续当成
     * "窗外点击"，双击模式下两下就关窗了——这是绝对不能发生的事。
     */
    @Volatile
    private var excludedBounds: Rect? = null

    fun update(
        nextMode: OutsideTapCloseMode,
        windowBounds: Rect?,
        excluded: Rect? = null,
        onClose: (() -> Unit)?,
    ) {
        mode = nextMode
        bounds = windowBounds?.let { Rect(it) }
        excludedBounds = excluded?.let { Rect(it) }
        closeAction = onClose
    }

    /** 只刷新排除区（输入法显示/隐藏时单独更新，不必重建关闭动作）。 */
    fun updateExcluded(rect: Rect?) {
        excludedBounds = rect?.let { Rect(it) }
    }

    fun clear() {
        mode = OutsideTapCloseMode.Disabled
        bounds = null
        excludedBounds = null
        closeAction = null
    }

    fun isDoubleTapMode(): Boolean = mode == OutsideTapCloseMode.DoubleTap

    /** 这一下是否应该被扣住：闸门开着，且落点在小窗之外。 */
    fun shouldCapture(x: Float, y: Float): Boolean {
        if (mode == OutsideTapCloseMode.Disabled) return false
        val region = bounds ?: return false
        val px = x.toInt()
        val py = y.toInt()
        if (region.contains(px, py)) return false
        // 落在输入法上：那是打字，不是"点窗外"。
        if (excludedBounds?.contains(px, py) == true) return false
        return true
    }

    /** 判定成立：执行关闭。 */
    fun performClose() {
        val action = closeAction
        closeAction = null
        mode = OutsideTapCloseMode.Disabled
        runCatching { action?.invoke() }
    }
}
