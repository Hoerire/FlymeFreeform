package io.github.hoerire.free.bubble

import android.graphics.Rect

/**
 * 窗口宿主：**唯一**持有三类浮层引用的地方。
 *
 * 职责只有“同一时刻每类窗口最多一个”和“任何 WMS 异常不外抛”。气泡的显示时机、
 * 队列语义、点击行为都在 Coordinator 里——这里不判断业务，只保证装/卸幂等。
 */
internal class BubbleWindowHost(private val logger: (code: String, throwable: Throwable?) -> Unit) {
    private var bubbleView: BubbleSurfaceView? = null
    private var stackView: BubbleStackOverlay? = null
    private var previewView: NotificationPreviewView? = null

    val bubble: BubbleSurfaceView? get() = bubbleView
    val stack: BubbleStackOverlay? get() = stackView
    val preview: NotificationPreviewView? get() = previewView

    fun showBubble(view: BubbleSurfaceView, timeoutSeconds: Int, dimOnTimeout: Boolean): Boolean {
        if (bubbleView != null && bubbleView !== view) {
            runCatching { bubbleView?.dismiss() }
        }
        val shown =
            runCatching { view.show(timeoutSeconds, dimOnTimeout) }.getOrElse {
                logger("BUBBLE_WINDOW_ADD_FAILED", it)
                false
            }
        bubbleView = if (shown) view else null
        return shown
    }

    fun forgetBubble(view: BubbleSurfaceView) {
        if (bubbleView === view) bubbleView = null
    }

    fun showStack(overlay: BubbleStackOverlay): Boolean {
        if (stackView != null && stackView !== overlay) {
            runCatching { stackView?.dismiss() }
        }
        val shown =
            runCatching { overlay.show() }.getOrElse {
                logger("BUBBLE_STACK_SHOW_FAILED", it)
                false
            }
        stackView = if (shown) overlay else null
        return shown
    }

    fun dismissStack() {
        val overlay = stackView ?: return
        stackView = null
        runCatching { overlay.dismiss() }
    }

    fun forgetStack(overlay: BubbleStackOverlay) {
        if (stackView === overlay) stackView = null
    }

    fun showPreview(overlay: NotificationPreviewView, anchor: Rect, position: Int, timeout: Int): Boolean {
        val shown =
            runCatching { overlay.show(anchor, position, timeout) }.getOrElse {
                logger("BUBBLE_PREVIEW_SHOW_FAILED", it)
                false
            }
        previewView = if (shown) overlay else null
        return shown
    }

    fun forgetPreview(overlay: NotificationPreviewView) {
        if (previewView === overlay) previewView = null
    }

    fun dismissPreview(animated: Boolean) {
        val overlay = previewView ?: return
        previewView = null
        runCatching {
            if (animated) overlay.dismiss() else overlay.removeImmediately()
        }
    }

    /** 页面被系统关闭（锁屏、切用户）时一次性收回，不走动画。 */
    fun dismissAllImmediately() {
        val bubble = bubbleView
        bubbleView = null
        stackView = null
        val preview = previewView
        previewView = null
        runCatching { preview?.removeImmediately() }
        runCatching { bubble?.removeImmediately() }
    }
}
