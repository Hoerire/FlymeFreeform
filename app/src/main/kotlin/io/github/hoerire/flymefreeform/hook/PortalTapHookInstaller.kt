package io.github.hoerire.flymefreeform.hook

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.hoerire.flymefreeform.window.portalPopupTop
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** ColorOS Content Portal 17.3.18. The stock service owns the clip, ranking, and dispatch. */
internal class PortalTapHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    private class Session(val service: Any) {
        var window: ViewGroup? = null
        var originX = 0
        var originY = 0
        var anchored = false
        var released = false
        var visible = false
        var layoutParams: WindowManager.LayoutParams? = null
        var panelOfWindow: ViewGroup? = null
        var timeout: Runnable? = null
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val service = loader.loadClass("$PACKAGE_NAME.service.ContentPortalService")
            val window = loader.loadClass("d6.x0")
            val receiver = loader.loadClass("$PACKAGE_NAME.drag.DragEventReceiver")
            val recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val gridManager = loader.loadClass("androidx.recyclerview.widget.GridLayoutManager")
            val holder = loader.loadClass("u5.a\$b")
            val adapter = loader.loadClass("u5.a")
            val windowUtil = loader.loadClass("$PACKAGE_NAME.utils.r0")
            val params = OemReflection.requireMethod(windowUtil, "h", 1)
            val helperField = requireNotNull(OemReflection.findField(window, "o"))
            val panelField = requireNotNull(OemReflection.findField(window, "m"))
            val rowField = requireNotNull(OemReflection.findField(window, "n"))
            val imageField = requireNotNull(OemReflection.findField(window, "p"))
            val clipField = requireNotNull(OemReflection.findField(window, "r"))
            val selection = OemReflection.requireMethod(window, "F", 1)
            val recordDrop = OemReflection.requireMethod(window, "B", 1)
            val showList = OemReflection.requireMethod(window, "O", 0)
            val cleanup = OemReflection.requireMethod(service, "L", 0)
            val dragEventFactory = DragEvent::class.java.declaredMethods.single {
                it.name == "obtain" && it.parameterCount == 13 &&
                    it.parameterTypes[8] == ClipDescription::class.java &&
                    it.parameterTypes[9] == ClipData::class.java
            }.apply { isAccessible = true }
            val enter = requireNotNull(loader.loadClass(
                "$PACKAGE_NAME.drag.DragEventReceiver\$DragState",
            ).enumConstants).first { (it as Enum<*>).name == "ENTER" }
            val notifyDragState = OemReflection.requireMethod(receiver, "l", 4)
            val getItemView = requireNotNull(OemReflection.findField(holder, "itemView"))
            val getPosition = OemReflection.requireMethod(holder, "getAbsoluteAdapterPosition", 0)
            val getAdapter = OemReflection.requireMethod(recycler, "getAdapter", 0)
            val receiverWindow = requireNotNull(OemReflection.findField(receiver, "a"))
            val layout = OemReflection.requireMethod(window, "onConfigurationChanged", 1)

            hook(OemReflection.requireMethod(service, "onBind", 1), "bind") { chain ->
                session?.let { dismiss(it, cleanup) }
                configuration.forceReload()
                val instance = chain.thisObject
                val source = (chain.getArg(0) as? Intent)?.getStringExtra("drag_pkg_detail")
                val context = instance as? android.content.Context
                val supported = context?.packageManager
                    ?.getPackageInfo(PACKAGE_NAME, 0)?.versionName == "17.3.18"
                if (instance != null && supported && configuration.snapshot.freeDragActive &&
                    source != null && configuration.snapshot.blacklistPackages.none {
                        source == it || source.startsWith("$it/") || source.startsWith("$it ")
                    }
                ) {
                    session = Session(instance).also { current ->
                        armTimeout(current, cleanup, 4000L)
                    }
                }
                chain.proceed()
            }
            hook(OemReflection.requireMethod(service, "B", 2), "window") { chain ->
                val result = chain.proceed()
                val current = session?.takeIf { it.service === chain.thisObject } ?: return@hook result
                current.window = OemReflection.readField(current.service, "c") as? ViewGroup
                result
            }
            hook(OemReflection.requireMethod(receiver, "g", 4), "origin") { chain ->
                val current = session
                val view = chain.getArg(2) as? View
                val event = chain.getArg(3) as? DragEvent
                if (current != null && view != null && event?.action == DragEvent.ACTION_DRAG_STARTED &&
                    receiverWindow.get(chain.getArg(0)) === view && event.x.isFinite() && event.y.isFinite()
                ) {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    current.originX = location[0] + event.x.roundToInt()
                    current.originY = location[1] + event.y.roundToInt()
                    current.anchored = true
                    chain.proceed()
                    // Incoming coordinates are local to the stock hot zone. Report ENTER
                    // through the same callback used when a finger reaches that zone.
                    notifyDragState.invoke(chain.getArg(0), enter, event.x, event.y, chain.getArg(1))
                    true
                } else {
                    chain.proceed()
                }
            }
            hook(OemReflection.requireMethod(window, "H", 1), "show") { chain ->
                val current = session
                val settlement = chain.thisObject as? ViewGroup
                if (current == null || settlement == null || current.window !== settlement ||
                    !current.anchored || !settlement.isAttachedToWindow
                ) return@hook chain.proceed()
                val panel = panelField.get(settlement) as? ViewGroup ?: return@hook chain.proceed()
                val row = rowField.get(settlement) as? View ?: return@hook chain.proceed()
                val layoutParams = params.invoke(null, settlement) as? WindowManager.LayoutParams
                    ?: return@hook chain.proceed()
                val clip = clipField.get(settlement) as? ClipData ?: return@hook chain.proceed()
                if (helperField.get(settlement) == null) return@hook chain.proceed()
                if (!current.visible) {
                    val event = dragEventFactory.invoke(
                        null, DragEvent.ACTION_DROP, 0f, 0f, 0f, 0f, 0, 0,
                        null, clip.description, clip, null, null, true,
                    ) as DragEvent
                    recordDrop.invoke(settlement, event)
                    val metrics = settlement.context.getSystemService(WindowManager::class.java).currentWindowMetrics
                    val bounds = Rect(metrics.bounds)
                    val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
                    )
                    bounds.inset(insets.left, insets.top, insets.right, insets.bottom)
                    val edge = dp(row, 8)
                    val maxWidth = bounds.width() - 2 * edge
                    val maxHeight = bounds.height() - 2 * edge
                    if (maxWidth <= 0 || maxHeight <= 0) return@hook chain.proceed()
                    // 五列网格：列数固定，行数由命中条数决定。
                    runCatching {
                        val constructor = gridManager.getConstructor(
                            android.content.Context::class.java,
                            Int::class.javaPrimitiveType,
                        )
                        val manager = constructor.newInstance(settlement.context, COLUMNS)
                        OemReflection.requireMethod(row.javaClass, "setLayoutManager", 1)
                            .invoke(row, manager)
                    }
                    val cellWidth = dp(row, 72)
                    var width = minOf(cellWidth * COLUMNS + dp(row, 16), maxWidth)
                    var height = minOf(dp(row, 240), maxHeight)
                    layoutParams.windowAnimations = 0
                    layoutParams.gravity = Gravity.TOP or Gravity.LEFT
                    layoutParams.width = width
                    layoutParams.height = height
                    layoutParams.x = bounds.left + (bounds.width() - width) / 2
                    layoutParams.y = bounds.top + (bounds.height() - height) / 2
                    layoutParams.flags = layoutParams.flags and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    layoutParams.flags = layoutParams.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    settlement.context.getSystemService(WindowManager::class.java)
                        .updateViewLayout(settlement, layoutParams)
                    for (index in 0 until settlement.childCount) {
                        val child = settlement.getChildAt(index)
                        if (child !== panel) child.visibility = View.GONE
                    }
                    row.setPadding(0, 0, 0, 0)
                    imageField.get(settlement)?.let { (it as View).visibility = View.GONE }
                    row.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    val night = (panel.resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val surface = if (night) 0xFF2A2A2A.toInt() else 0xFFF7F7F7.toInt()
                    panel.background = GradientDrawable().apply {
                        setColor((surface and 0x00FFFFFF) or (0xF0 shl 24))
                        cornerRadius = dp(panel, 22).toFloat()
                    }
                    row.animate().cancel()
                    row.translationY = 0f
                    panel.alpha = 1f
                    panel.visibility = View.VISIBLE
                    current.layoutParams = layoutParams
                    current.panelOfWindow = panel
                    current.visible = true
                    settlement.setOnTouchListener { view, event ->
                        val outsideByAction = event.actionMasked == MotionEvent.ACTION_OUTSIDE
                        val outsideByPosition =
                            event.actionMasked == MotionEvent.ACTION_DOWN &&
                                (event.x < 0f || event.y < 0f ||
                                    event.x > view.width || event.y > view.height)
                        if (outsideByAction || outsideByPosition) {
                            dismiss(current, cleanup)
                            true
                        } else {
                            false
                        }
                    }
                    showList.invoke(settlement)
                    settlement.post {
                        runCatching {
                            row.measure(
                                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
                            )
                            val measuredWidth = if (row.measuredWidth > 0) row.measuredWidth else width
                            val measuredHeight = if (row.measuredHeight > 0) row.measuredHeight else height
                            layoutParams.width = measuredWidth.coerceAtMost(maxWidth)
                            layoutParams.height = measuredHeight.coerceAtMost(maxHeight)
                            layoutParams.x = bounds.left + (bounds.width() - layoutParams.width) / 2
                            layoutParams.y = bounds.top + (bounds.height() - layoutParams.height) / 2
                            settlement.context.getSystemService(WindowManager::class.java)
                                ?.updateViewLayout(settlement, layoutParams)
                        }
                    }
                    armTimeout(current, cleanup, 20_000L)
                }
                null
            }
            hook(OemReflection.requireMethod(window, "U0", 0), "animate") { chain ->
                val current = session
                if (current != null && current.window === chain.thisObject) {
                    val result = chain.proceed()
                    restoreLayout(current)
                    result
                } else {
                    chain.proceed()
                }
            }
            // 系统栏隐藏动画：面板弹出期间不执行，避免状态栏/导航栏被收起来。
            hook(OemReflection.requireMethod(window, "s0", 0), "bars") { chain ->
                if (session?.window === chain.thisObject) null else chain.proceed()
            }
            hook(OemReflection.requireMethod(window, "onDragEvent", 1), "release") { chain ->
                val current = session
                if (current == null || current.window !== chain.thisObject || !current.anchored) {
                    return@hook chain.proceed()
                }
                when ((chain.getArg(0) as DragEvent).action) {
                    DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                        current.released = true
                        true
                    }
                    else -> true
                }
            }
            hook(OemReflection.requireMethod(service, "onUnbind", 1), "unbind") { chain ->
                val current = session
                if (current != null && current.service === chain.thisObject && current.anchored) {
                    current.released = true
                    false
                } else {
                    chain.proceed()
                }
            }
            hook(OemReflection.requireMethod(service, "onDestroy", 0), "destroy") { chain ->
                val current = session
                if (current != null && current.service === chain.thisObject && current.anchored) {
                    current.released = true
                    null
                } else {
                    chain.proceed()
                }
            }
            hook(cleanup, "cleanup") { chain ->
                val current = session
                if (current != null && current.service === chain.thisObject && current.anchored) {
                    current.released = true
                    null
                } else {
                    chain.proceed()
                }
            }
            hook(requireNotNull(OemReflection.findMethod(adapter, "w", holder, Int::class.javaPrimitiveType!!)), "click") { chain ->
                val result = chain.proceed()
                val current = session ?: return@hook result
                val settlement = current.window ?: return@hook result
                val row = rowField.get(settlement) ?: return@hook result
                if (!current.visible || getAdapter.invoke(row) !== chain.thisObject) return@hook result
                val item = chain.getArg(0) ?: return@hook result
                val itemView = getItemView.get(item) as View
                itemView.layoutParams = itemView.layoutParams.apply { height = dp(itemView, 88) }
                val icon = itemView.findViewById<View>(0x7f09005d)
                icon?.let {
                    it.layoutParams = (it.layoutParams as ViewGroup.MarginLayoutParams).apply {
                        width = dp(it, 34)
                        height = dp(it, 34)
                        bottomMargin = dp(it, 5)
                    }
                }
                val label = itemView.findViewById<View>(0x7f090061)
                label?.let {
                    it.layoutParams = (it.layoutParams as ViewGroup.MarginLayoutParams).apply {
                        bottomMargin = dp(it, 6)
                    }
                    (it as? android.widget.TextView)?.apply {
                        val nightLabel = (it.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                        setTextColor(if (nightLabel) 0xFFF2F2F2.toInt() else 0xFF1A1A1A.toInt())
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = android.graphics.Typeface.DEFAULT
                        paint.isFakeBoldText = false
                        setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                    }
                }
                itemView.setOnClickListener {
                    if (session !== current || !current.released) return@setOnClickListener
                    val position = getPosition.invoke(item) as Int
                    if (position < 0) return@setOnClickListener
                    runCatching { selection.invoke(settlement, position) }
                        .onFailure { ModuleLog.warn("PORTAL_TAP_ACTION_FAILED", it) }
                    dismiss(current, cleanup)
                }
                result
            }
            val dragPreview = loader.loadClass("$PACKAGE_NAME.drag.DragViewHelper")
            hook(OemReflection.requireMethod(dragPreview, "u", 1), "preview") { chain ->
                if (session?.anchored == true && chain.getArg(0) == true) null else chain.proceed()
            }
            hook(layout, "configuration") { chain ->
                val current = session
                if (current != null && current.window === chain.thisObject && current.visible) {
                    dismiss(current, cleanup)
                    null
                } else {
                    chain.proceed()
                }
            }
            ModuleLog.install("PORTAL_TAP_INSTALLED")
        }.onFailure { ModuleLog.warn("PORTAL_TAP_UNSUPPORTED", it) }
    }

    /** 原厂动画改写窗口位置后再复位，避免面板跑回底部、也不留动画状态。 */
    private fun restoreLayout(current: Session) {
        val window = current.window ?: return
        val params = current.layoutParams ?: return
        if (!current.visible) return
        main.post {
            runCatching {
                window.context.getSystemService(WindowManager::class.java)
                    ?.updateViewLayout(window, params)
                current.panelOfWindow?.alpha = 1f
            }
        }
    }

    private fun armTimeout(current: Session, cleanup: Method, delay: Long) {
        current.timeout?.let(main::removeCallbacks)
        current.timeout = Runnable { dismiss(current, cleanup) }.also { main.postDelayed(it, delay) }
    }

    private fun dismiss(current: Session, cleanup: Method) {
        if (session !== current) return
        session = null
        current.timeout?.let(main::removeCallbacks)
        if (!current.anchored) return
        runCatching { cleanup.invoke(current.service) }
            .onFailure { ModuleLog.warn("PORTAL_TAP_CLEANUP_FAILED", it) }
    }

    private fun hook(method: Method, id: String, block: (XposedInterface.Chain) -> Any?) {
        module.hook(method)
            .setId("flymefreeform.portal.$id")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> block(chain) }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    companion object {
        const val PACKAGE_NAME = "com.oplus.contentportal"

        /** 网格列数。 */
        const val COLUMNS = 5

        /** 设计尺寸：格子宽高、内边距、圆角与最大行数。 */
        const val CELL_WIDTH_DP = 80
        const val CELL_HEIGHT_DP = 96
        const val PADDING_DP = 8
        const val RADIUS_DP = 20
        const val MAX_ROWS = 3
    }
}
