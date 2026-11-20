package io.github.hoerire.free.bubble

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 融入状态栏的滚动通知组件（类似 lyricon 的状态栏歌词）。
 * 不创建独立浮层，而是直接以子视图插入状态栏通知区，并替换时钟位置：
 * 使用状态栏时钟本身的字体与颜色，随时钟随状态栏着色而联动变化。
 */
internal class StatusScrollOverlay(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val iconView = ImageView(context)
    private val ticker: FadingTickerView = FadingTickerView(context)
    private var attached = false
    private var hasText = false
    private var host: ViewGroup? = null
    private var overlayAttached = false
    private var clockView: TextView? = null
    private var clockOriginalVisibility = -1
    private var clockSuppressed = false
    private var pendingIcon: Drawable? = null
    private var pendingText: String? = null
    private var hasPending = false
    private val hiddenPeers = ArrayList<View>()
    private val hiddenPeerVisibility = ArrayList<Int>()
    private var notificationIconsContainer: ViewGroup? = null
    private val removeAction = Runnable { removeImmediately() }
    private val pageAdvanceAction = Runnable { advancePage() }
    private val overlayKeepAliveAction = Runnable { keepOverlayAlive() }
    private val textPages = ArrayList<String>()
    private var pageIndex = 0
    private var contentWidth = 0
    private var fullText = ""
    private var shownAt = 0L
    private var displayRounds = 1
    private var completedRounds = 0
    private var finishedAction: (() -> Unit)? = null
    private val fluidCloudBounds = Rect()

    init {
        orientation = LinearLayout.HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        val pad = dp(5f)
        setPadding(pad, 0, pad, 0)
        setClipToPadding(true)
        setClipChildren(false)
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.VISIBLE
        alpha = 0f

        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
        addView(iconView, LinearLayout.LayoutParams(dp(16f), dp(16f)))

        ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        ticker.setSingleLine(true)
        ticker.gravity = Gravity.CENTER_VERTICAL
        ticker.setEllipsize(TextUtils.TruncateAt.END)
        ticker.isFocusable = false
        ticker.setIncludeFontPadding(false)
        ticker.setPadding(dp(6f), 0, dp(4f), 0)
        // 文本用 WRAP_CONTENT 而非权重(0,MATCH,1)，避免在 WRAP_CONTENT 容器里塌缩成 0 宽；
        // 整体也按内容自适应宽度，随状态栏布局自动测量。
        // ViewOverlay 手工测量/布局时不一定会解析 LinearLayout 的权重，
        // 所以这里给定一个确定的文本宽度，并在状态栏宿主挂上或重新测量时更新。
        addView(ticker, LinearLayout.LayoutParams(dp(160f), LinearLayout.LayoutParams.MATCH_PARENT))
    }

    /** 纯文本滚动条：最右侧的字形保持完全不透明。 */
    private inner class FadingTickerView(context: Context) : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
        }
    }

    /** 把组件插入状态栏容器，定位到状态栏时钟所在位置。 */
    fun attachTo(parent: ViewGroup) {
        var discoveredClock: View? = findClockView(parent)
        if (discoveredClock !is TextView) {
            // 被测量的 status_bar_contents / start-side 宿主可能是时钟的兄弟节点，
            // 需要遍历整个 SystemUI 装饰树才能拿到时钟真实的纵向位置。
            val root = parent.rootView
            if (root is ViewGroup && root !== parent) {
                discoveredClock = findClockView(root)
            }
        }
        val clock = discoveredClock as? TextView
        if (clock != null) {
            clockView = clock
        }
        if (host === parent && overlayAttached) return
        detachOverlay()
        host = parent
        attachClockStyle(parent)
        // 画在时钟与图标子视图之上，但不参与 ColorOS 容器的
        // 测量与子视图顺序规则。
        parent.overlay.add(this)
        overlayAttached = true
        var left = clockOffsetInHost(false)
        val height = tickerRowHeight()
        val top = centeredTopInHost(height)
        var right = findRightSystemBoundary(parent)
        val tickerBounds = resolveFluidCloudAvoidance(parent, left, right, top, height)
        left = tickerBounds[0]
        right = tickerBounds[1]
        val availableWidth = Math.max(1, right - left)
        val width = if (availableWidth >= dp(144f)) availableWidth - dp(24f) else availableWidth
        val textWidth = Math.max(1, width - paddingLeft - paddingRight - dp(16f))
        val tickerParams = ticker.layoutParams as LinearLayout.LayoutParams
        tickerParams.width = textWidth
        tickerParams.height = LinearLayout.LayoutParams.MATCH_PARENT
        tickerParams.weight = 0f
        ticker.layoutParams = tickerParams
        ticker.visibility = View.VISIBLE
        contentWidth = Math.max(1, textWidth - ticker.paddingLeft - ticker.paddingRight)
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(left, top, left + width, top + height)
        visibility = View.VISIBLE
        if (!hasText) alpha = 0f
        if (hasPending) {
            val icon = pendingIcon
            val text = pendingText ?: ""
            hasPending = false
            pendingIcon = null
            pendingText = null
            show(icon, text)
        }
        // SystemUI 可能晚一帧才量完状态栏宿主：
        // 那时再布局一次，让文本区用上真实宿主边界。
        post { relayoutOverlay() }
        postDelayed({ relayoutOverlay() }, 90L)
        postDelayed({ relayoutOverlay() }, 260L)
        if (hasText) scheduleOverlayKeepAlive()
    }

    private fun scheduleOverlayKeepAlive() {
        handler.removeCallbacks(overlayKeepAliveAction)
        if (overlayAttached && hasText) {
            handler.postDelayed(overlayKeepAliveAction, 180L)
        }
    }

    private fun keepOverlayAlive() {
        val currentHost = host
        if (!overlayAttached || !hasText || currentHost == null) return
        try {
            // SystemUI 重建通知图标行时可能清空 ViewGroupOverlay：
            // 把同一个视图重新加回去，滚动条就不会在
            // 正常的超时/移除流程之前凭空消失。
            currentHost.overlay.remove(this)
            currentHost.overlay.add(this)
            // 滚动进行中可能有新的通知图标视图插入；
            // 每次保活都把它们隐藏，避免与本条重叠。
            hideStatusBarPeers(currentHost)
            relayoutOverlay()
        } catch (ignored: Throwable) {
        }
        scheduleOverlayKeepAlive()
    }

    private fun relayoutOverlay() {
        if (!overlayAttached) return
        val currentHost = host ?: return
        // 折叠状态下的状态栏层级可能先暴露宿主、后挂上它的
        // 时钟子视图：一旦出现就重新绑定，不必等
        // 用户手动刷新状态栏。
        val clock = clockView
        if (clock == null || clock.parent == null) {
            val found = findClockView(currentHost)
            if (found is TextView) attachClockStyle(currentHost)
        }
        if (currentHost.measuredWidth <= 0) return
        var left = clockOffsetInHost(false)
        val height = tickerRowHeight()
        val top = centeredTopInHost(height)
        var right = findRightSystemBoundary(currentHost)
        val tickerBounds = resolveFluidCloudAvoidance(currentHost, left, right, top, height)
        left = tickerBounds[0]
        right = tickerBounds[1]
        val availableWidth = Math.max(1, right - left)
        val width = if (availableWidth >= dp(144f)) availableWidth - dp(24f) else availableWidth
        val textWidth = Math.max(1, width - paddingLeft - paddingRight - dp(16f))
        val tickerParams = ticker.layoutParams as LinearLayout.LayoutParams
        tickerParams.width = textWidth
        tickerParams.height = LinearLayout.LayoutParams.MATCH_PARENT
        tickerParams.weight = 0f
        ticker.layoutParams = tickerParams
        contentWidth = Math.max(1, textWidth - ticker.paddingLeft - ticker.paddingRight)
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(left, top, left + width, top + height)
        if (hasText) {
            hideStatusBarPeers(currentHost)
            copyClockStyle()
        }
        if (hasText && fullText.isNotEmpty()) {
            prepareTextPages(fullText)
            pageIndex = Math.min(pageIndex, Math.max(0, textPages.size - 1))
            ticker.setText(if (textPages.isEmpty()) fullText else textPages[pageIndex])
        }
    }

    private fun chooseClockAnchor(root: ViewGroup, clock: View): ViewGroup {
        // 保留 BubbleNotificationHook 选定的宿主：把它提升到更宽的时钟祖先上，
        // 会改变 ViewOverlay 的坐标系，
        // 让滚动条在状态栏第一次重新布局后跳位。
        return root
    }

    /** 查找状态栏时钟，并让本组件实时跟随其着色变化。 */
    private fun attachClockStyle(root: ViewGroup) {
        val found = findClockView(root)
        if (found !is TextView) return
        clockView = found
        found.viewTreeObserver.addOnPreDrawListener {
            // 状态栏每次重绘（颜色随着色变化）时同步本组件的样式与颜色。
            if (hasText) copyClockStyle()
            true
        }
    }

    private fun placeAtClockPosition(parent: ViewGroup) {
        var index = parent.childCount
        val clock = clockView
        if (clock != null && clock.parent === parent) {
            index = parent.indexOfChild(clock)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )

        val clockLp = clock?.layoutParams
        if (clockLp != null) {
            val maxWidth = parent.measuredWidth
            ticker.maxWidth = if (maxWidth > 0) (maxWidth * 0.45f).toInt() else dp(160f)
        }
        parent.addView(this, index, lp)
        suppressClock()
        Log.i(
            "BubbleNotify",
            "overlay placed: parent=" + parent.javaClass.simpleName +
                " idx=" + index + " clockFound=" + (clockView != null) +
                " clockSuppressed=" + clockSuppressed,
        )
    }

    private fun suppressClock() {
        val clock = clockView ?: return
        // 时钟的显隐由 hideStatusBarPeers() 负责：退场动画开始后，
        // 不要再覆盖它记录的原始可见性。
        if (hiddenPeers.contains(clock)) return
        if (clockSuppressed) return
        clockOriginalVisibility = clock.visibility
        clock.visibility = View.GONE
        clockSuppressed = true
    }

    private fun hideStatusBarPeers(parent: ViewGroup) {
        val clock = clockView
        if (clock != null && clock.visibility != View.GONE) {
            hiddenPeers.add(clock)
            hiddenPeerVisibility.add(clock.visibility)
            animatePeerOut(clock)
        }
        val icons = findNotificationIcons(parent)
        if (icons != null && icons !== this && icons !== clockView &&
            icons.visibility != View.GONE
        ) {
            notificationIconsContainer = icons as? ViewGroup
            hiddenPeers.add(icons)
            hiddenPeerVisibility.add(icons.visibility)
            animatePeerOut(icons)
        }
    }

    private fun animatePeerOut(view: View) {
        val distance = Math.max(statusBarHeight(), height).toFloat()
        view.animate().cancel()
        view.translationY = -distance
        view.animate().alpha(0f).setDuration(150L)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    private fun hidePeersRecursive(view: View) {
        if (view === this) return
        // 宿主自己也可能叫 status_bar_start_side / statusIcons：
        // 必须保持可见，因为滚动条画在该宿主的叠加层里。
        if (view === host) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    hidePeersRecursive(view.getChildAt(i))
                }
            }
            return
        }
        val name = view.javaClass.name
        var idName = ""
        try {
            if (view.id != View.NO_ID) idName = view.resources.getResourceEntryName(view.id)
        } catch (ignored: Throwable) {
        }
        // 尾侧的容器承载信号、Wi-Fi、蓝牙与电量，
        // 永远不要递归进去、也不要隐藏这块区域。
        val rightSystemArea = idName.contains("end_side") ||
            idName.contains("system_icon_area") ||
            idName.contains("status_bar_end")
        if (rightSystemArea) return
        // 只隐藏单个通知图标视图，不要隐藏 statusIcons / start_side 这类
        // 大容器：ColorOS 可能把信号、电量或滚动条宿主
        // 放在这些容器里面。
        val notificationPeer = name.contains("StatusBarIconView") ||
            name.contains("NotificationIcon")
        if (notificationPeer && view.visibility != View.GONE) {
            hiddenPeers.add(view)
            hiddenPeerVisibility.add(view.visibility)
            view.visibility = View.GONE
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) hidePeersRecursive(view.getChildAt(i))
        }
    }

    private fun restorePeers() {
        for (i in 0 until hiddenPeers.size) {
            val view = hiddenPeers[i]
            val visibility = hiddenPeerVisibility[i]
            view.animate().cancel()
            view.visibility = when (visibility) {
                View.INVISIBLE -> View.INVISIBLE
                View.GONE -> View.GONE
                else -> View.VISIBLE
            }
            view.translationY = -Math.max(statusBarHeight(), height).toFloat()
            view.alpha = 0f
            view.animate().translationY(0f).alpha(1f).setDuration(160L).start()
        }
        hiddenPeers.clear()
        hiddenPeerVisibility.clear()
        notificationIconsContainer = null
    }

    private fun findNotificationIcons(view: View?): View? {
        if (view == null || view === this || view === clockView) return null
        try {
            if (view.id != View.NO_ID) {
                val idName = view.resources.getResourceEntryName(view.id)
                if (idName == "notificationIcons" ||
                    idName.contains("notification_icon_area")
                ) {
                    return view
                }
            }
        } catch (ignored: Throwable) {
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findNotificationIcons(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun clockOffsetInHost(vertical: Boolean): Int {
        val clock = clockView ?: return 0
        val currentHost = host ?: return 0
        try {
            val clockLocation = IntArray(2)
            val parentLocation = IntArray(2)
            clock.getLocationInWindow(clockLocation)
            currentHost.getLocationInWindow(parentLocation)
            return Math.max(
                0,
                if (vertical) {
                    clockLocation[1] - parentLocation[1]
                } else {
                    clockLocation[0] - parentLocation[0]
                },
            )
        } catch (ignored: Throwable) {
            return if (vertical) clock.top else clock.left
        }
    }

    private fun clockCenterOffsetInHost(): Int {
        val clock = clockView ?: return statusBarHeight() / 2
        val currentHost = host ?: return statusBarHeight() / 2
        try {
            val clockLocation = IntArray(2)
            val parentLocation = IntArray(2)
            clock.getLocationInWindow(clockLocation)
            currentHost.getLocationInWindow(parentLocation)
            return Math.max(0, clockLocation[1] - parentLocation[1] + clock.height / 2)
        } catch (ignored: Throwable) {
            return clock.top + clock.height / 2
        }
    }

    private fun tickerRowHeight(): Int {
        val currentHost = host
        var hostHeight = currentHost?.height ?: 0
        if (hostHeight <= 0 && currentHost != null) hostHeight = currentHost.measuredHeight
        val clockHeight = clockView?.height ?: 0
        val base = if (hostHeight > 0) {
            hostHeight
        } else {
            if (clockHeight > 0) clockHeight else statusBarHeight()
        }
        return Math.max(dp(18f), Math.min(statusBarHeight(), base))
    }

    private fun centeredTopInHost(height: Int): Int {
        val currentHost = host
        var hostHeight = currentHost?.height ?: 0
        if (hostHeight <= 0 && currentHost != null) hostHeight = currentHost.measuredHeight
        if (hostHeight > 0) return Math.max(0, (hostHeight - height) / 2 + dp(4f))
        // 首次布局前先把这一行放在状态栏原点，
        // 等宿主有了高度，再由延后的一次布局把它居中。
        return dp(4f)
    }

    private fun findRightSystemBoundary(parent: ViewGroup): Int {
        val fallback = parent.measuredWidth
        val boundary = findRightSystemView(parent) ?: return fallback
        try {
            val child = IntArray(2)
            val root = IntArray(2)
            boundary.getLocationInWindow(child)
            parent.getLocationInWindow(root)
            return Math.max(0, child[0] - root[0])
        } catch (ignored: Throwable) {
            return boundary.left
        }
    }

    /**
     * 让滚动条落在状态栏里没有被可见流体云胶囊占据的那一段。
     * 某些版本里胶囊是独立的 SystemUI 窗口，所以先在屏幕坐标里算出来，
     * 再换回浮层宿主的本地坐标系。
     */
    private fun resolveFluidCloudAvoidance(
        parent: ViewGroup?,
        left: Int,
        right: Int,
        top: Int,
        height: Int,
    ): IntArray {
        if (parent == null || right <= left) return intArrayOf(left, right)
        val cloud = findFluidCloudBounds() ?: return intArrayOf(left, right)
        try {
            val hostLocation = IntArray(2)
            parent.getLocationOnScreen(hostLocation)
            val row = Rect(
                hostLocation[0] + left,
                hostLocation[1] + top,
                hostLocation[0] + right,
                hostLocation[1] + top + height,
            )
            if (!Rect.intersects(row, cloud)) return intArrayOf(left, right)
            val gap = dp(6f)
            val cloudLeft = cloud.left - hostLocation[0]
            val cloudRight = cloud.right - hostLocation[0]
            val leftWidth = Math.max(0, Math.min(right, cloudLeft - gap) - left)
            val rightWidth = Math.max(0, right - Math.max(left, cloudRight + gap))
            // 优先用更宽的无遮挡区间；两段都窄时
            // 保留较宽的一段，交给现有的分页逻辑处理。
            if (rightWidth > leftWidth) {
                val newLeft = Math.min(right, cloudRight + gap)
                return intArrayOf(newLeft, right)
            }
            val newRight = Math.max(left, cloudLeft - gap)
            return intArrayOf(left, newRight)
        } catch (ignored: Throwable) {
            return intArrayOf(left, right)
        }
    }

    private fun findFluidCloudBounds(): Rect? {
        fluidCloudBounds.setEmpty()
        try {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstance = globalClass.getMethod("getInstance")
            val getWindowViews = globalClass.getMethod("getWindowViews")
            val global = getInstance.invoke(null)
            val roots = if (global == null) null else getWindowViews.invoke(global)
            if (roots !is List<*>) return null
            for (root in roots) {
                if (root !is View) continue
                val found = findFluidCloudInView(root) ?: continue
                if (fluidCloudBounds.isEmpty() ||
                    found.width() * found.height() >
                    fluidCloudBounds.width() * fluidCloudBounds.height()
                ) {
                    fluidCloudBounds.set(found)
                }
            }
        } catch (ignored: Throwable) {
        }
        return if (fluidCloudBounds.isEmpty()) null else Rect(fluidCloudBounds)
    }

    private fun findFluidCloudInView(view: View?): Rect? {
        if (view == null) return null
        if (view === this || view.visibility != View.VISIBLE ||
            view.alpha < 0.05f || view.width <= 0 || view.height <= 0
        ) {
            return null
        }
        var identity = view.javaClass.name.lowercase(Locale.ROOT)
        try {
            val id = view.id
            if (id != View.NO_ID && id != 0) {
                val entry = view.resources.getResourceEntryName(id)
                if (entry != null) identity += " " + entry.lowercase(Locale.ROOT)
            }
        } catch (ignored: Throwable) {
        }
        val tag = view.tag
        if (tag != null) identity += " " + tag.toString().lowercase(Locale.ROOT)
        val capsule = identity.contains("fluid") || identity.contains("capsule") ||
            identity.contains("seedling") || identity.contains("mini_capsule") ||
            identity.contains("smartcapsule") || identity.contains("smart_capsule") ||
            identity.contains("fluid_cloud") || identity.contains("fluidcloud") ||
            identity.contains("floating_cloud") || identity.contains("notificationcapsule") ||
            identity.contains("statusbarcapsule") || identity.contains("dynamicisland")
        var own: Rect? = null
        if (capsule && view.height <= dp(180f) &&
            view.width >= dp(24f) &&
            view.width <= Math.round(resources.displayMetrics.widthPixels * 0.82f) &&
            view.height >= dp(18f)
        ) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            own = Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height,
            )
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findFluidCloudInView(view.getChildAt(i))
                val current = own
                if (child != null && (current == null ||
                        child.width() * child.height() > current.width() * current.height())
                ) {
                    own = child
                }
            }
        }
        return own
    }

    private fun findRightSystemView(view: View): View? {
        if (view === this) return null
        var idName = ""
        try {
            if (view.id != View.NO_ID) idName = view.resources.getResourceEntryName(view.id)
        } catch (ignored: Throwable) {
        }
        if (idName.contains("end_side") || idName.contains("system_icon_area") ||
            idName.contains("status_bar_end")
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findRightSystemView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun restoreClock() {
        if (!clockSuppressed) return
        val clock = clockView ?: return
        val restored = when (clockOriginalVisibility) {
            View.INVISIBLE -> View.INVISIBLE
            View.GONE -> View.GONE
            else -> View.VISIBLE
        }
        clock.visibility = restored
        clockSuppressed = false
    }

    /** 从状态栏时钟复制字体与颜色；找不到时钟时退回到状态栏前景色。 */
    private fun copyClockStyle() {
        var color = statusForeground()
        val clock = clockView
        if (clock != null) {
            color = clock.currentTextColor
            val clockSize = clock.textSize
            if (clockSize > 0f) {
                ticker.setTextSize(
                    TypedValue.COMPLEX_UNIT_PX,
                    Math.max(dp(9f).toFloat(), Math.min(dp(12f).toFloat(), clockSize)),
                )
            } else {
                ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            ticker.typeface = clock.typeface
        } else {
            ticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        if (Color.alpha(color) == 0) color = statusForeground()
        ticker.setTextColor(color)
        val iconDrawable = iconView.drawable
        if (iconView.visibility != View.GONE && iconDrawable != null) {
            // 与状态栏图标的黑白单色观感保持一致。
            iconDrawable.mutate().setColorFilter(
                PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN),
            )
            iconView.invalidate()
        }
    }

    fun show(icon: Drawable?, text: String) {
        show(icon, text, 1)
    }

    fun show(icon: Drawable?, text: String, rounds: Int) {
        val currentHost = host
        if (currentHost == null) {
            pendingIcon = icon
            pendingText = text
            hasPending = true
            return
        }
        if (!overlayAttached) attachTo(currentHost)
        visibility = View.VISIBLE
        animate().cancel()
        alpha = 1f
        shownAt = SystemClock.uptimeMillis()
        attached = true
        displayRounds = Math.max(1, Math.min(5, rounds))
        completedRounds = 0
        hideStatusBarPeers(currentHost)
        setStatusIcon(icon)
        copyClockStyle()
        val next = text
        fullText = next
        prepareTextPages(next)
        ticker.animate().cancel()
        handler.removeCallbacks(pageAdvanceAction)
        val distance = scrollDistance()
        if (!hasText) {
            ticker.setText(if (textPages.isEmpty()) "" else textPages[0])
            pageIndex = 0
            ticker.translationY = distance.toFloat()
            ticker.alpha = 0f
            iconView.translationY = distance.toFloat()
            iconView.alpha = 0f
            ticker.animate().translationY(0f).alpha(1f).setDuration(190L)
                .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start()
            iconView.animate().translationY(0f).alpha(1f).setDuration(190L)
                .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start()
        } else {
            ticker.animate().translationY(-distance.toFloat()).alpha(0f).setDuration(150L)
                .setInterpolator(SCROLL_EXIT_INTERPOLATOR)
                .withEndAction {
                    ticker.setText(if (textPages.isEmpty()) "" else textPages[0])
                    pageIndex = 0
                    ticker.translationY = distance.toFloat()
                    ticker.animate().translationY(0f).alpha(1f).setDuration(190L)
                        .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start()
                }.start()
        }
        hasText = true
        schedulePageAdvance()
        handler.removeCallbacks(removeAction)
        if (textPages.size <= 1) {
            handler.postDelayed(removeAction, 2_400L)
        }
        scheduleOverlayKeepAlive()
    }

    fun updateIcon(icon: Drawable?) {
        if (!hasText || icon == null) return
        setStatusIcon(icon)
        copyClockStyle()
    }

    fun setFinishedAction(action: (() -> Unit)?) {
        finishedAction = action
    }

    fun isShowing(): Boolean {
        return hasText
    }

    fun finishCurrentRoundSoon() {
        if (!hasText) return
        displayRounds = Math.min(displayRounds, completedRounds + 1)
    }

    private fun setStatusIcon(icon: Drawable?) {
        if (icon == null) {
            iconView.visibility = View.GONE
            return
        }
        iconView.visibility = View.VISIBLE
        setImageDrawable(icon)
        copyClockStyle()
    }

    private fun setImageDrawable(icon: Drawable) {
        val state = icon.constantState
        val copy = if (state == null) icon else state.newDrawable()
        iconView.setImageDrawable(copy.mutate())
    }

    fun removeImmediately() {
        val elapsed = SystemClock.uptimeMillis() - shownAt
        if (hasText && elapsed >= 0L && elapsed < 1_500L && isScreenInteractive()) {
            // 防止滚动条首帧遇到 SystemUI/状态栏重建而误走公共清理路径；
            // 真正息屏时的清理仍会执行，因为那时显示已经不可交互。
            handler.removeCallbacks(removeAction)
            handler.postDelayed(removeAction, 1_500L - elapsed)
            scheduleOverlayKeepAlive()
            Log.i("BubbleNotify", "status ticker early remove deferred")
            return
        }
        handler.removeCallbacks(removeAction)
        handler.removeCallbacks(pageAdvanceAction)
        handler.removeCallbacks(overlayKeepAliveAction)
        attached = false
        hasText = false
        completedRounds = 0
        restorePeers()
        restoreClock()
        ticker.animate().cancel()
        animate().cancel()
        alpha = 0f
        ticker.alpha = 0f
        ticker.setText("")
        textPages.clear()
        fullText = ""
        pageIndex = 0
        iconView.setImageDrawable(null)
        iconView.visibility = View.GONE
        shownAt = 0L
        // 保持透明宿主挂载：否则 ColorOS 会重建左侧状态栏分组，
        // 让时钟与图标的显隐和这里抢时序。
        val callback = finishedAction
        if (callback != null) handler.post { callback() }
    }

    private fun isScreenInteractive(): Boolean {
        return try {
            val power = context.getSystemService(PowerManager::class.java)
            power == null || power.isInteractive
        } catch (ignored: Throwable) {
            true
        }
    }

    private fun prepareTextPages(text: String?) {
        textPages.clear()
        if (text == null || text.isEmpty()) return
        val width = Math.max(dp(90f), contentWidth)
        var remaining: String = text
        while (remaining.isNotEmpty()) {
            var count: Int
            try {
                count = ticker.paint.breakText(remaining, true, width.toFloat(), null)
            } catch (ignored: Throwable) {
                count = Math.min(24, remaining.length)
            }
            if (count <= 0) count = Math.min(24, remaining.length)
            var split = Math.min(count, remaining.length)
            val newline = remaining.lastIndexOf('\n', split - 1)
            if (newline > 0) split = newline
            textPages.add(remaining.substring(0, split).trim())
            remaining = remaining.substring(split).trim()
        }
    }

    private fun schedulePageAdvance() {
        if (textPages.size > 1) handler.postDelayed(pageAdvanceAction, 1800L)
    }

    private fun advancePage() {
        if (!hasText || textPages.size <= 1) return
        val distance = scrollDistance()
        if (pageIndex >= textPages.size - 1) {
            completedRounds++
            if (completedRounds >= displayRounds) {
                removeImmediately()
                return
            }
            pageIndex = 0
        } else {
            pageIndex++
        }
        ticker.animate().cancel()
        ticker.animate().translationY(-distance.toFloat()).alpha(0f).setDuration(150L)
            .setInterpolator(SCROLL_EXIT_INTERPOLATOR)
            .withEndAction {
                ticker.setText(textPages[pageIndex])
                ticker.translationY = distance.toFloat()
                ticker.animate().translationY(0f).alpha(1f).setDuration(190L)
                    .setInterpolator(SCROLL_ENTER_INTERPOLATOR).start()
            }.start()
        handler.postDelayed(pageAdvanceAction, 2200L)
    }

    /** 每页只滚半行状态栏高度，让每页在视觉上仍有依托。 */
    private fun scrollDistance(): Int {
        val row = if (height > 0) height else statusBarHeight()
        return Math.max(dp(8f), Math.round(row * 0.5f))
    }

    private fun detachOverlay() {
        handler.removeCallbacks(overlayKeepAliveAction)
        restorePeers()
        restoreClock()
        val currentHost = host
        if (currentHost != null && overlayAttached) {
            try {
                currentHost.overlay.remove(this)
            } catch (ignored: Throwable) {
            }
        }
        overlayAttached = false
    }

    private fun statusForeground(): Int {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.WHITE else Color.BLACK
    }

    private fun findClockView(root: ViewGroup): View? {
        // 优先按 id 查找状态栏时钟
        var clockId = resources.getIdentifier("status_bar_clock", "id", "android")
        if (clockId == 0) {
            clockId = resources.getIdentifier("clock", "id", "com.android.systemui")
        }
        if (clockId == 0) {
            clockId = resources.getIdentifier("status_bar_clock", "id", "com.android.systemui")
        }
        if (clockId == 0) {
            clockId = resources.getIdentifier("status_bar_elapsed_time", "id", "android")
        }
        if (clockId != 0) {
            val match = root.findViewById<View>(clockId)
            if (match is TextView) return match
        }
        val byClass = findClockByClass(root)
        return byClass ?: findClockByResourceName(root)
    }

    private fun findClockByClass(view: View): View? {
        if (view is TextView) {
            val name = view.javaClass.name
            if (name.endsWith("Clock") || name.contains(".ClockController")) return view
        }
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val found = findClockByClass(view.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    private fun findClockByResourceName(view: View?): View? {
        if (view == null) return null
        try {
            if (view.id != View.NO_ID) {
                val name = view.resources.getResourceEntryName(view.id)
                if (name != null && name.lowercase(Locale.ROOT).contains("clock") &&
                    view is TextView
                ) {
                    return view
                }
            }
        } catch (ignored: Throwable) {
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findClockByResourceName(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun dp(value: Float): Int {
        return Math.round(value * resources.displayMetrics.density)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id == 0) dp(24f) else resources.getDimensionPixelSize(id)
    }

    private companion object {
        private val SCROLL_EXIT_INTERPOLATOR =
            PathInterpolator(0.40f, 0.00f, 1.00f, 1.00f)
        private val SCROLL_ENTER_INTERPOLATOR =
            PathInterpolator(0.05f, 0.70f, 0.10f, 1.00f)
    }
}
