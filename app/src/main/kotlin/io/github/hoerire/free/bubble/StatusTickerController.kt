package io.github.hoerire.free.bubble

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 状态栏滚动：把最新通知的「应用·标题 正文」塞进状态栏时钟的位置滚动一遍。
 *
 * 与气泡共用同一条通知管线，但**不共用白名单**：状态栏滚动默认对全部应用生效
 *（可在设置里收窄），这样即使某个应用没开气泡，也能只看状态栏摘要。
 *
 * 视图本体是 [StatusScrollOverlay]（逐行转写），这里只负责：
 * 找到状态栏容器、装钩子、把通知内容整理成一行、结束后轮下一条。
 */
internal class StatusTickerController(
    private val context: Context,
    private val module: XposedModule,
    private val settings: () -> BubbleSettings,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: StatusScrollOverlay? = null
    private var root: ViewGroup? = null
    private var tickingKey: String? = null
    private var pending: PendingTicker? = null
    private var scannerAttempts = 0
    private val scanAction = Runnable { scanRoots() }

    fun install(classLoader: ClassLoader) {
        runCatching { installFragmentHooks(classLoader) }
            .onFailure { logger("STATUS_TICKER_FRAGMENT_HOOK_FAILED", it) }
        runCatching { installViewHooks(classLoader) }
            .onFailure { logger("STATUS_TICKER_VIEW_HOOK_FAILED", it) }
        runCatching { installWindowHooks(classLoader) }
            .onFailure { logger("STATUS_TICKER_WINDOW_HOOK_FAILED", it) }
        if (settings().statusScrollActive) ensureScanner()
    }

    private fun ensureScanner() {
        if (root != null) return
        scannerAttempts = 0
        handler.removeCallbacks(scanAction)
        handler.post(scanAction)
    }

    fun onPosted(sbn: StatusBarNotification, payload: BubblePayload) {
        val config = settings()
        if (!config.statusScrollActive) return
        if (!config.acceptsStatusScroll(BubbleIdentity(sbn.packageName, sbn.userId))) return
        val text = buildText(payload)
        if (text.isBlank()) return
        val icon = runCatching { tickerIcon(sbn) }.getOrNull()
        // 同一条通知的更新不打断；正在滚另一条时排队，让当前这一轮先走完。
        if (overlay?.isShowing() == true) {
            if (sbn.key != null && sbn.key == tickingKey) return
            pending = PendingTicker(sbn.key, icon, text, config.statusScrollRounds)
            runCatching { overlay?.finishCurrentRoundSoon() }
            return
        }
        val target = root
        if (target == null) {
            pending = PendingTicker(sbn.key, icon, text, config.statusScrollRounds)
            ensureScanner()
            return
        }
        show(target, sbn.key, icon, text, config.statusScrollRounds)
    }

    /** 设置关闭/模块关闭时立刻收回，正显示的那条也一起停。 */
    fun applySettings(config: BubbleSettings) {
        if (!config.statusScrollActive) {
            handler.removeCallbacks(scanAction)
            removeAll()
        } else if (root == null) {
            ensureScanner()
        }
    }

    fun removeAll() {
        tickingKey = null
        pending = null
        runCatching { overlay?.removeImmediately() }
    }

    /**
     * 用户已经看过/打开了这条通知：立刻停掉对应的状态栏滚动。
     *
     * 只认同一个 key；如果当前滚的是别的通知，不动它。key 为 null 时不清任何东西
     *（调用方拿不到 key 时宁可让它自然结束，也不要误停别人的）。
     */
    fun stopFor(key: String?) {
        if (key == null) return
        if (key == tickingKey) {
            tickingKey = null
            pending = null
            handler.removeCallbacks(scanAction)
            runCatching { overlay?.removeImmediately() }
            return
        }
        if (pending?.key == key) pending = null
    }

    private fun show(
        host: ViewGroup,
        key: String?,
        icon: Drawable?,
        text: String,
        rounds: Int,
    ) {
        val view = overlay ?: StatusScrollOverlay(context).also { created ->
            created.setFinishedAction { onFinished() }
            overlay = created
        }
        runCatching { view.attachTo(host) }
            .onFailure { logger("STATUS_TICKER_ATTACH_FAILED", it) }
        runCatching { view.show(icon, text, rounds) }
            .onFailure { logger("STATUS_TICKER_SHOW_FAILED", it) }
        tickingKey = key
    }

    private fun onFinished() {
        tickingKey = null
        val next = pending ?: return
        pending = null
        val host = root ?: return
        show(host, next.key, next.icon, next.text, next.rounds)
    }

    private fun buildText(payload: BubblePayload): String =
        buildString {
            append(payload.appName)
            if (payload.title.isNotEmpty()) {
                if (isNotEmpty()) append('·')
                append(payload.title)
            }
            if (payload.body.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(payload.body)
            }
        }

    // ---- 状态栏容器定位 -------------------------------------------------------

    private fun attach(view: View?) {
        if (view == null) return
        val host = runCatching { resolveHost(view) }.getOrNull() ?: return
        root = host
        val pendingNow = pending
        if (pendingNow != null) {
            pending = null
            show(host, pendingNow.key, pendingNow.icon, pendingNow.text, pendingNow.rounds)
        } else {
            runCatching { overlay?.attachTo(host) }
        }
    }

    private fun resolveHost(view: View): ViewGroup? {
        val candidate = (view as? ViewGroup) ?: return null
        val startId = systemUiResourceId("status_bar_start_side_content", "id")
        val contentId = systemUiResourceId("status_bar_contents", "id")
        val start = startId.takeIf { it != 0 }?.let { candidate.findViewById<View>(it) }
        val content = contentId.takeIf { it != 0 }?.let { candidate.findViewById<View>(it) }
        var host = start as? ViewGroup
        if (host == null || host.measuredWidth < dp(120f)) {
            val contentGroup = content as? ViewGroup
            if (contentGroup != null &&
                contentGroup.measuredWidth > (host?.measuredWidth ?: 0)
            ) {
                host = contentGroup
            }
        }
        if (host != null) return host
        // ROM 改名时按时钟视图反查父容器，与参考实现一致。
        val clock = findClockView(candidate)
        return clock?.parent as? ViewGroup ?: candidate
    }

    private fun findClockView(view: View): View? {
        runCatching {
            if (view.javaClass.simpleName == "ClockView") return view
        }
        if (view is TextView) {
            if (view.tag == "clock") return view
            val id = view.id
            if (id != View.NO_ID) {
                runCatching {
                    if (context.resources.getResourceEntryName(id) == "clock") return view
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findClockView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun scanRoots() {
        if (root != null) return
        runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null) ?: return
            val views = global.getMethod("getWindowViews").invoke(instance)
            val list = views as? List<*> ?: return
            for (item in list) {
                val windowRoot = item as? View ?: continue
                attach(findStatusBarContents(windowRoot) ?: windowRoot)
            }
        }.onFailure { logger("STATUS_TICKER_SCAN_FAILED", it) }
        if (root == null && scannerAttempts < MAX_SCAN_ATTEMPTS) {
            scannerAttempts++
            handler.postDelayed(scanAction, SCAN_INTERVAL_MS)
        }
    }

    private fun findStatusBarContents(view: View): View? {
        val id = systemUiResourceId("status_bar_contents", "id")
        if (id != 0) {
            runCatching { view.findViewById<View>(id) }.getOrNull()?.let { return it }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findStatusBarContents(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun systemUiResourceId(name: String, type: String): Int =
        runCatching {
            context.resources.getIdentifier(name, type, "com.android.systemui")
        }.getOrDefault(0)

    private fun dp(value: Float): Int =
        Math.round(value * context.resources.displayMetrics.density)

    // ---- 图标 -----------------------------------------------------------------

    private fun tickerIcon(sbn: StatusBarNotification): Drawable? {
        val fromStatusBar = runCatching {
            findStatusBarIconDrawable(root, sbn.key, 0)
        }.getOrNull()
        if (fromStatusBar != null) return monochrome(fromStatusBar)
        val notification = sbn.notification ?: return null
        val packageContext =
            runCatching { context.createPackageContext(sbn.packageName, 0) }.getOrNull()
                ?: context
        val icon =
            runCatching { notification.getSmallIcon()?.loadDrawable(packageContext) }.getOrNull()
                ?: return null
        return monochrome(icon)
    }

    /** 从状态栏自己的 StatusBarIconView 取已经染好色的单色图标（含通知图标增强的改写）。 */
    private fun findStatusBarIconDrawable(view: View?, key: String?, depth: Int): Drawable? {
        if (view == null || key == null || depth > 12) return null
        if (view is ImageView &&
            view.javaClass.name.contains("StatusBarIconView") &&
            statusBarNotificationOf(view)?.key == key
        ) {
            return view.drawable
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findStatusBarIconDrawable(view.getChildAt(index), key, depth + 1)?.let {
                    return it
                }
            }
        }
        return null
    }

    private fun statusBarNotificationOf(view: View): StatusBarNotification? {
        var type: Class<*>? = view.javaClass
        while (type != null && type != View::class.java) {
            runCatching {
                val method = type.getDeclaredMethod("getNotification")
                method.isAccessible = true
                return method.invoke(view) as? StatusBarNotification
            }
            type = type.superclass
        }
        return null
    }

    private fun monochrome(drawable: Drawable): Drawable {
        if (drawable !is AdaptiveIconDrawable) return drawable
        return runCatching {
            val monochrome = drawable.monochrome
            val foreground = drawable.foreground
            val chosen = monochrome ?: foreground ?: return drawable
            chosen.constantState?.newDrawable()?.mutate() ?: chosen
        }.getOrElse { drawable }
    }

    // ---- 钩子 -----------------------------------------------------------------

    private fun installFragmentHooks(classLoader: ClassLoader) {
        var hooked = false
        for (name in FRAGMENT_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: continue
            for (method in cls.declaredMethods) {
                val types = method.parameterTypes
                if (method.name != "onViewCreated" || types.isEmpty() ||
                    !View::class.java.isAssignableFrom(types[0])
                ) {
                    continue
                }
                method.isAccessible = true
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.bubble.statusbar.fragment." + name)
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.args?.firstOrNull() as? View)?.let { view ->
                            handler.post { attach(view) }
                        }
                        result
                    }
                hooked = true
                break
            }
            if (hooked) break
        }
        // ColorOS 有些版本只走 onCreateView，没有 onViewCreated 回调。
        for (name in FRAGMENT_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: continue
            val method =
                cls.declaredMethods.firstOrNull {
                    it.name == "onCreateView" && View::class.java.isAssignableFrom(it.returnType)
                } ?: continue
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.bubble.statusbar.createview." + name)
                .intercept { chain ->
                    val result = chain.proceed()
                    (result as? View)?.let { view -> handler.post { attach(view) } }
                    result
                }
            break
        }
    }

    private fun installViewHooks(classLoader: ClassLoader) {
        for (name in STATUS_BAR_VIEW_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: continue
            val method = runCatching { cls.getDeclaredMethod("onFinishInflate") }.getOrNull()
                ?: continue
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.bubble.statusbar.inflate." + name)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view -> handler.post { attach(view) } }
                    result
                }
        }
    }

    private fun installWindowHooks(classLoader: ClassLoader) {
        for (name in WINDOW_CLASSES) {
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
                ?: continue
            val method =
                cls.declaredMethods.firstOrNull {
                    it.name == "onAttachedToWindow" && it.parameterCount == 0
                } ?: continue
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.bubble.statusbar.window." + name)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? View)?.let { view -> handler.post { attach(view) } }
                    result
                }
        }
    }

    private data class PendingTicker(
        val key: String?,
        val icon: Drawable?,
        val text: String,
        val rounds: Int,
    )

    private companion object {
        const val SCAN_INTERVAL_MS = 500L
        const val MAX_SCAN_ATTEMPTS = 60

        val FRAGMENT_CLASSES =
            listOf(
                "com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                "com.oplus.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment",
                "com.oplus.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentEx",
                "com.oplus.systemui.statusbar.phone.fragment.CollapsedStatusBarFragmentExImpl",
            )

        val STATUS_BAR_VIEW_CLASSES =
            listOf(
                "com.android.systemui.statusbar.phone.PhoneStatusBarView",
                "com.oplus.systemui.statusbar.phone.PhoneStatusBarView",
                "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarView",
                "com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarViewExImpl",
            )

        val WINDOW_CLASSES =
            listOf(
                "com.android.systemui.statusbar.window.StatusBarWindowView",
                "com.oplus.systemui.statusbar.window.StatusBarWindowView",
                "com.oplus.systemui.statusbar.phone.OplusStatusBarWindowView",
            )
    }
}
