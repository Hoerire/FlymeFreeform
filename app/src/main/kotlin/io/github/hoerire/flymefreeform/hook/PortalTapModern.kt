package io.github.hoerire.flymefreeform.hook

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
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * 内容传送门面板接管（按版本配置）。
 *
 * 每个系统版本单独一份 [PortalProfile]，混淆名全部来自配置；流程与 2.7.1 的做法一致：
 *   1. 拖动开始补一次 ENTER 状态，面板直接出现在手指位置，不需要拖到底部热区；
 *   2. 面板出现时一次性接管：五列、居中、按条目数收高、隐藏整屏截图底图、铺不透明底色；
 *   3. 原厂在入场动画与拖动结束时改写窗口参数、收起面板，这几处跑完只复位一次；
 *   4. 接管之后吞掉后续拖拽事件，关闭只走「点窗外」与「点条目」。
 */
internal class PortalTapModern(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val main = Handler(Looper.getMainLooper())
    private var profile: PortalProfile? = null
    private var stateMethod: Method? = null
    private var enterState: Any? = null

    /** 当前拖动会话的面板窗口；接管成功后保持，直到关闭。 */
    private var window: View? = null
    private var takeover = false
    private var enterSent = false
    private var lastX = 0f
    private var lastY = 0f
    private var appliedWidth = -1
    private var appliedHeight = -1
    private var appliedX = -1
    private var appliedY = -1
    private var itemBinderHooked = false
    private var watching = false
    private var treeLogged = false
    private var idleTimeout: Runnable? = null
    private var panelHideMethod: Method? = null

    fun install(loader: ClassLoader, profile: PortalProfile): Boolean = runCatching {
        this.profile = profile
        val windowClass = loader.loadClass(profile.windowClass)
        val receiverClass = loader.loadClass(profile.receiverClass)
        val stateClass = loader.loadClass(profile.stateClass)
        enterState = stateClass.getDeclaredField(profile.enterStateField)
            .apply { isAccessible = true }
            .get(null)
        stateMethod = receiverClass.methods.firstOrNull {
            it.parameterCount == 4 && it.parameterTypes[0] == stateClass
        } ?: error("receiver state dispatch missing")

        val entry = windowClass.methods.firstOrNull {
            it.name == profile.dragEntry && it.parameterCount == 1
        } ?: error("drag entry missing")
        hookEntry(entry)

        // 面板入场动画会把窗口推回默认位置：动画结束复位一次。
        windowClass.methods.firstOrNull {
            it.name == profile.animMethod && it.parameterCount == 0
        }?.let { method ->
            hook(method, "anim") { chain ->
                val result = chain.proceed()
                if (takeover) restoreGeometry()
                result
            }
        }

        // 拖动结束/收起面板：接管期间跳过，面板才能留到用户点选。
        panelHideMethod = windowClass.methods.firstOrNull {
            it.name == profile.panelHideMethod && it.parameterCount == 1 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        panelHideMethod?.let { method ->
            hook(method, "hide") { chain ->
                if (takeover && chain.getArg(0) == true) {
                    ModuleLog.install("PORTAL_KEEP_ALIVE_panel")
                    return@hook null
                }
                chain.proceed()
            }
        }

        // 拖动结束会解绑服务并拆掉窗口，接管期间抑制。
        val serviceClass = loader.loadClass(profile.serviceClass)
        for (name in KEEP_ALIVE_METHODS) {
            val method = serviceClass.methods.firstOrNull { it.name == name } ?: continue
            hook(method, "keep_" + name) { chain ->
                if (takeover) {
                    ModuleLog.install("PORTAL_KEEP_ALIVE_" + name)
                    return@hook if (method.returnType == Boolean::class.javaPrimitiveType) true else null
                }
                chain.proceed()
            }
        }

        // 拖动结束系统侧会把面板窗口从 WindowManager 摘掉，接管期间拦住这次摘除。
        runCatching {
            val windowManagerClass = loader.loadClass("android.view.WindowManagerImpl")
            for (name in arrayOf("removeView", "removeViewImmediate")) {
                val method = windowManagerClass.methods.firstOrNull {
                    it.name == name && it.parameterCount == 1 &&
                        it.parameterTypes[0] == View::class.java
                } ?: continue
                hook(method, "detach_" + name) { chain ->
                    val target = chain.getArg(0)
                    if (takeover && target === window) {
                        ModuleLog.install("PORTAL_KEEP_WINDOW")
                        return@hook null
                    }
                    chain.proceed()
                }
            }
        }.onFailure { ModuleLog.warn("PORTAL_WINDOW_HOOK_FAILED", it) }
        ModuleLog.install("PORTAL_TAP_INSTALLED_" + profile.portalVersion)
        true
    }.onFailure {
        ModuleLog.warn("PORTAL_TAP_UNSUPPORTED", it)
        false
    }.getOrDefault(false)

    private fun hookEntry(entry: Method) {
        hook(entry, "drag") { chain ->
            val event = chain.getArg(0) as? DragEvent
            if (event == null || !configuration.snapshot.freeDragActive) return@hook chain.proceed()
            window = chain.thisObject as? View
            if (event.action == DragEvent.ACTION_DRAG_STARTED ||
                (!takeover && event.action == DragEvent.ACTION_DRAG_LOCATION && window != null && !enterSent)
            ) {
                resetSession()
                lastX = event.x
                lastY = event.y
                main.post { startWatch() }
            }
            // 面板窗口全屏，拖动事件在任意位置都会到这里；把它们转发给底部热区监听器，
            // 原厂就按「手指在面板上」处理：弹出、跟手、松手后保持可点选。
            // 原厂选中分支会读窗口上最近一次 DragEvent / ClipData（中转站走这条），
            // 事件被本模块转发后不再经过原处理，这里等价补写一次。
            writeFields(window, event)
            if (!takeover) {
                val relay = hotView()
                if (relay != null) {
                    runCatching { relay.second.invoke(relay.first, relay.third, event) }
                    ModuleLog.debug { "PORTAL_FORWARD_" + event.action }
                }
            }
            // 接管之后整段状态机都归本模块：原厂按「手指是否在底部热区」判定，
            // 再收到面板范围内的 LOCATION / ENTERED 就会重排列表并收回窗口，
            // 表现为拖进面板后选项全部消失、面板按它自己的尺寸炸开。
            // 这里连同 DROP / ENDED 一起吞掉，只保留条目点击这条路。
            if (takeover) {
                when (event.action) {
                    DragEvent.ACTION_DRAG_ENTERED,
                    DragEvent.ACTION_DRAG_LOCATION,
                    DragEvent.ACTION_DRAG_EXITED,
                    DragEvent.ACTION_DROP,
                    DragEvent.ACTION_DRAG_ENDED,
                    -> return@hook true
                }
            }
            chain.proceed()
        }
    }

    private fun receiverOf(): Any? {
        val current = window ?: return null
        val controller = readField(current, profile?.controllerField ?: return null) ?: return null
        return readField(controller, profile?.receiverField ?: return null)
    }

    /** 记录最近一次拖拽事件与剪贴数据，供原厂选中分支读取。 */
    private fun writeFields(panel: View?, event: DragEvent) {
        val profile = this.profile ?: return
        panel ?: return
        writeField(panel, profile.eventField, event)
        event.clipData?.let { clip -> writeField(panel, profile.clipField, clip) }
    }

    private fun writeField(target: Any, name: String, value: Any) {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(target, value)
                }
                return
            }
            type = type.superclass
        }
    }

    /** 底部热区监听器：监听器实例、onDrag 方法、热区 View。 */
    private fun hotView(): Triple<Any, Method, View>? = runCatching {
        val receiver = receiverOf() ?: return null
        val view = readField(receiver, profile?.hotViewField ?: return null) as? View ?: return null
        val info = readField(view, LISTENER_INFO_FIELD) ?: return null
        val instance = readField(info, profile?.listenerField ?: return null) ?: return null
        val method = instance.javaClass.methods.firstOrNull {
            it.name == "onDrag" && it.parameterCount == 2 &&
                it.parameterTypes[0] == View::class.java &&
                it.parameterTypes[1] == DragEvent::class.java
        } ?: return null
        Triple(instance, method, view)
    }.getOrNull()

    private fun resetSession() {
        takeover = false
        enterSent = false
        itemBinderHooked = false
        appliedWidth = -1
        appliedHeight = -1
        appliedX = -1
        appliedY = -1
        idleTimeout?.let(main::removeCallbacks)
        idleTimeout = null
    }

    /** 面板由原厂在拖动开始时创建，等它 attach 且列表有条目后一次性接管。 */
    private fun startWatch() {
        if (watching) return
        watching = true
        pollPanel(0)
    }

    private fun pollPanel(attempt: Int) {
        if (window == null) {
            watching = false
            return
        }
        if (attempt > POLL_ATTEMPTS) {
            watching = false
            ModuleLog.warn("PORTAL_WINDOW_TIMEOUT", null)
            return
        }
        val panel = window ?: return
        forceEnter()
        if (!panel.isAttachedToWindow || !takeOver(panel)) {
            main.postDelayed({ pollPanel(attempt + 1) }, POLL_INTERVAL_MS)
            return
        }
        watching = false
        takeover = true
        ModuleLog.install("PORTAL_PANEL_READY")
        // 条目可能后到，收高一次即可，不再重复改布局。
        main.postDelayed({ if (takeover) runCatching { takeOver(panel, quiet = true) } }, REINFORCE_DELAY_MS)
    }

    /** 补一次 ENTER：原厂只在手指进入底部热区时才把面板升起来。 */
    private fun forceEnter() {
        if (enterSent) return
        val current = window ?: return
        val controller = readField(current, profile?.controllerField ?: return) ?: return
        val receiver = readField(controller, profile?.receiverField ?: return) ?: return
        val method = stateMethod ?: return
        val enter = enterState ?: return
        val callback = readCallback(receiver) ?: run {
            ModuleLog.install("PORTAL_ENTER_PENDING")
            return
        }
        enterSent = true
        runCatching { method.invoke(receiver, enter, lastX, lastY, callback) }
            .onFailure { ModuleLog.warn("PORTAL_ENTER_FAILED", it) }
    }

    /**
     * 状态回调实例只挂在底部热区的拖拽监听器上：热区 View 由状态接收者的字段持有，
     * 监听器再从 View 的 ListenerInfo 取出。
     */
    private fun readCallback(receiver: Any): Any? {
        val profile = profile ?: return null
        val hotView = readField(receiver, profile.hotViewField) as? View ?: return null
        val info = readField(hotView, LISTENER_INFO_FIELD) ?: return null
        val listener = readField(info, profile.listenerField) ?: return null
        return readField(listener, profile.callbackField)
    }

    private fun readField(target: Any, name: String): Any? = runCatching {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                return it.get(target)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    /** 呈现接管；列表没有条目时返回 false，继续等下一帧。 */
    private fun takeOver(panelWindow: View, quiet: Boolean = false): Boolean = runCatching {
        val params = panelWindow.layoutParams as? WindowManager.LayoutParams ?: return false
        val manager = panelWindow.context.getSystemService(WindowManager::class.java) ?: return false
        val row = readList(panelWindow) ?: return false
        val count = itemCount(row)
        if (count <= 0) return false
        val panel = readPanel(panelWindow) ?: row

        setColumns(row)
        hookItemBinder(row)

        val metrics = manager.currentWindowMetrics
        val bounds = Rect(metrics.bounds)
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
        )
        bounds.inset(insets.left, insets.top, insets.right, insets.bottom)
        val maxWidth = bounds.width() - 2 * dp(panelWindow, 8)
        val maxHeight = bounds.height() - 2 * dp(panelWindow, 8)
        if (maxWidth <= 0 || maxHeight <= 0) return false

        // 高度按实际条数算：列表异步填充，先到几个条目时固定成一行会裁掉后面的行。
        val rows = ((count + COLUMNS - 1) / COLUMNS).coerceIn(1, MAX_ROWS)
        val width = minOf(dp(panelWindow, CELL_WIDTH_DP) * COLUMNS + dp(panelWindow, 16), maxWidth)
        val height = minOf(dp(panelWindow, CELL_HEIGHT_DP) * rows + dp(panelWindow, 16), maxHeight)
        val targetX = bounds.left + (bounds.width() - width) / 2
        val targetY = bounds.top + (bounds.height() - height) / 2
        params.windowAnimations = 0
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.width = width
        params.height = height
        params.x = targetX
        params.y = targetY
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        params.flags = params.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (appliedWidth != width || appliedHeight != height ||
            appliedX != targetX || appliedY != targetY
        ) {
            appliedWidth = width
            appliedHeight = height
            appliedX = targetX
            appliedY = targetY
            manager.updateViewLayout(panelWindow, params)
        }

        // 原厂把整屏截图铺在窗口里（窗口 1272×2800，列表只是子视图），
        // 不隐藏这一层面板会整片发灰、透出后面的画面。
        hideScreenshot(panelWindow)
        stretchToList(panel, row)
        panel.alpha = 1f
        panelWindow.alpha = 1f
        paint(panel, panelWindow)
        paint(panelWindow, panelWindow)
        panelWindow.setOnTouchListener { touched, event -> handledOutside(touched, event) }
        // 列表自己会消费触摸，窗口上的监听收不到；在列表与面板上各自重新计时，
        // 只要还在滑动，5 秒兜底就不该触发。
        val keepAlive: (View, MotionEvent) -> Boolean = { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_MOVE
            ) {
                armIdle(panelWindow)
            }
            false
        }
        row.setOnTouchListener(keepAlive)
        panel.setOnTouchListener(keepAlive)
        armIdle(panelWindow)
        if (!quiet) {
            ModuleLog.install("PORTAL_PANEL_STYLED")
            if (!treeLogged) {
                treeLogged = true
                logTree(panelWindow, row)
            }
        }
        true
    }.onFailure { ModuleLog.warn("PORTAL_PANEL_STYLE_FAILED", it) }.getOrDefault(false)

    /** 动画或原厂布局跑完后只复位一次窗口参数。 */
    private fun restoreGeometry() {
        appliedWidth = -1
        appliedHeight = -1
        appliedX = -1
        appliedY = -1
        val panel = window ?: return
        main.post { runCatching { takeOver(panel, quiet = true) } }
    }

    private fun readList(panelWindow: View): View? = runCatching {
        val field = profile?.listField ?: return null
        panelWindow.javaClass.declaredFields.firstOrNull { it.name == field }?.let {
            it.isAccessible = true
            return it.get(panelWindow) as? View
        }
        null
    }.getOrNull()

    private fun readPanel(panelWindow: View): View? = runCatching {
        val field = profile?.panelField ?: return null
        panelWindow.javaClass.declaredFields.firstOrNull { it.name == field }?.let {
            it.isAccessible = true
            return it.get(panelWindow) as? View
        }
        null
    }.getOrNull()

    private fun hideScreenshot(panelWindow: View) = runCatching {
        val field = profile?.screenshotField ?: return@runCatching
        panelWindow.javaClass.declaredFields.firstOrNull { it.name == field }?.let {
            it.isAccessible = true
            (it.get(panelWindow) as? View)?.visibility = View.GONE
        }
    }

    /** 列表铺满面板：原厂把列表压在底部并留了大片内边距。 */
    private fun stretchToList(panel: View, row: View) {
        val group = panel as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child !== row && !contains(child, row)) continue
            val params = child.layoutParams
            if (params == null) {
                child.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            } else {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                child.layoutParams = params
            }
        }
        row.setPadding(0, 0, 0, 0)
    }

    private fun handledOutside(view: View, event: MotionEvent): Boolean {
        val outside =
            event.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    (event.x < 0f || event.y < 0f || event.x > view.width || event.y > view.height))
        if (!outside) return false
        ModuleLog.install("PORTAL_DISMISS_OUTSIDE")
        dismiss()
        return true
    }

    private fun contains(root: View, target: View): Boolean {
        var node: View? = target
        while (node != null) {
            if (node === root) return true
            node = node.parent as? View
        }
        return false
    }

    private fun paint(panel: View, panelWindow: View) {
        val night = (panelWindow.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = if (night) SURFACE_DARK else SURFACE_LIGHT
        panel.background = GradientDrawable().apply {
            setColor(surface)
            cornerRadius = dp(panelWindow, PANEL_CORNER_DP).toFloat()
        }
        (panel as? ViewGroup)?.clipToOutline = true
    }

    private fun armIdle(panelWindow: View) {
        idleTimeout?.let(main::removeCallbacks)
        idleTimeout = Runnable { dismiss() }.also { main.postDelayed(it, IDLE_DISMISS_MS) }
    }

    /** 关闭：交还原厂的收起流程，再清会话状态。 */
    private fun dismiss() {
        idleTimeout?.let(main::removeCallbacks)
        idleTimeout = null
        val panel = window
        val hide = panelHideMethod
        takeover = false
        enterSent = false
        window = null
        appliedWidth = -1
        appliedHeight = -1
        appliedX = -1
        appliedY = -1
        if (panel != null) {
            runCatching { hide?.invoke(panel, true) }
                .onFailure { ModuleLog.warn("PORTAL_DISMISS_FAILED", it) }
            runCatching {
                panel.context.getSystemService(WindowManager::class.java)?.removeView(panel)
            }.onFailure { ModuleLog.warn("PORTAL_REMOVE_FAILED", it) }
        }
    }

    private fun setColumns(row: View) {
        runCatching {
            // 已是五列网格就直接返回：重建 LayoutManager 会让列表每次回顶，表现为闪动。
            val current = row.javaClass.methods
                .firstOrNull { it.name == "getLayoutManager" && it.parameterCount == 0 }
                ?.invoke(row)
            if (current != null && current.javaClass.simpleName == "GridLayoutManager" &&
                current.javaClass.methods.firstOrNull { it.name == "getSpanCount" }
                    ?.invoke(current) == COLUMNS
            ) {
                return
            }
            val grid = Class.forName(
                "androidx.recyclerview.widget.GridLayoutManager", false, row.javaClass.classLoader,
            )
            val manager = runCatching {
                grid.getConstructor(android.content.Context::class.java, Int::class.javaPrimitiveType)
                    .newInstance(row.context, COLUMNS)
            }.getOrElse {
                runCatching {
                    grid.getConstructor(Int::class.javaPrimitiveType).newInstance(COLUMNS)
                }.getOrElse {
                    grid.getConstructor(
                        android.content.Context::class.java,
                        android.util.AttributeSet::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).newInstance(row.context, null, 0, 0)
                }
            }
            val setter = row.javaClass.methods.firstOrNull {
                it.name == "setLayoutManager" && it.parameterCount == 1
            } ?: return
            setter.invoke(row, manager)
        }.onFailure { ModuleLog.warn("PORTAL_COLUMNS_FAILED", it) }
    }

    /**
     * 条目尺寸在绑定回调里改：窗口出来后再遍历已 attach 的子视图去改会撑坏布局。
     * 绑定方法按「参数是 ViewHolder + int」定位，ViewHolder 那侧按「有 View 字段」判断。
     */
    private fun hookItemBinder(row: View) {
        if (itemBinderHooked) return
        runCatching {
            val adapter = row.javaClass.methods
                .firstOrNull { it.name == "getAdapter" && it.parameterCount == 0 }
                ?.invoke(row) ?: return
            val bind = adapter.javaClass.methods.firstOrNull { method ->
                method.parameterCount == 2 &&
                    method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[0].declaredFields.any { View::class.java.isAssignableFrom(it.type) }
            } ?: return
            val holderType = bind.parameterTypes[0]
            val viewField = holderType.declaredFields
                .firstOrNull { View::class.java.isAssignableFrom(it.type) }
                ?.apply { isAccessible = true }
            // 条目点击：原厂面板没有点击监听器，按下标取 ServiceInfo 后调用窗口的启动方法。
            val serviceInfo = Class.forName(
                profile?.serviceInfoClass ?: return, false, row.javaClass.classLoader,
            )
            val getItem = adapter.javaClass.methods.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    serviceInfo.isAssignableFrom(it.returnType)
            }
            val launchName = profile?.launchMethod ?: return
            // 点击时按 ViewHolder 的实时位置取项，绑定时的位置在列表回收后会漂移。
            val positionGetter = holderType.methods.firstOrNull {
                it.parameterCount == 0 && it.returnType == Int::class.javaPrimitiveType &&
                    (it.name == "getAbsoluteAdapterPosition" || it.name == "getBindingAdapterPosition" ||
                        it.name == "getAdapterPosition")
            }
            itemBinderHooked = true
            hook(bind, "item") { chain ->
                val result = chain.proceed()
                runCatching {
                    val holder = chain.getArg(0)
                    val item = viewField?.get(holder) as? View ?: return@runCatching
                    val boundPosition = chain.getArg(1) as? Int ?: -1
                    restyleItem(item)
                    if (boundPosition < 0 || getItem == null) return@runCatching
                    item.isClickable = true
                    val recycler = row
                    val getPosition = recycler.javaClass.methods.firstOrNull {
                        it.name == "getChildAdapterPosition" && it.parameterCount == 1
                    }
                    item.setOnClickListener {
                        runCatching {
                            val position = getPosition?.invoke(recycler, item) as? Int ?: boundPosition
                            if (position < 0) return@runCatching
                            launchSelected(position, adapter, getItem, launchName)
                        }
                    }
                }
                result
            }
            ModuleLog.install("PORTAL_ITEM_BINDER_HOOKED")
        }.onFailure { ModuleLog.warn("PORTAL_ITEM_BINDER_FAILED", it) }
    }

    /** 用当前剪贴数据补一个 DROP 事件，供原厂选中分支读取。 */
    private fun seedDropEvent(panel: View) {
        val profile = this.profile ?: return
        val clip = readField(panel, profile.clipField) as? android.content.ClipData ?: return
        runCatching {
            val factory = DragEvent::class.java.declaredMethods.single {
                it.name == "obtain" && it.parameterCount == 13 &&
                    it.parameterTypes[8] == android.content.ClipDescription::class.java &&
                    it.parameterTypes[9] == android.content.ClipData::class.java
            }.apply { isAccessible = true }
            val event = factory.invoke(
                null, DragEvent.ACTION_DROP, lastX, lastY, 0f, 0f, 0, 0,
                null, clip.description, clip, null, null, true,
            )
            writeField(panel, profile.eventField, event)
        }.onFailure { ModuleLog.warn("PORTAL_SEED_FAILED", it) }
    }

    /** 面板数据表下标：按包名与组件名比对，找不到再用适配器位置。 */
    private fun listIndexOf(panel: View, info: Any, fallback: Int): Int {
        val field = profile?.listDataField ?: return fallback
        val data = readField(panel, field) as? List<*> ?: return fallback
        val pkg = runCatching { info.javaClass.getMethod("getPkgName").invoke(info) as? String }.getOrNull()
        val cls = runCatching { info.javaClass.getMethod("getClassName").invoke(info) as? String }.getOrNull()
        val hit = data.indexOfFirst { item ->
            item != null &&
                runCatching { item.javaClass.getMethod("getPkgName").invoke(item) as? String }.getOrNull() == pkg &&
                runCatching { item.javaClass.getMethod("getClassName").invoke(item) as? String }.getOrNull() == cls
        }
        return if (hit >= 0) hit else fallback
    }

    /**
     * 选中项交给原厂窗口按位置分发：内部按包名区分 AI 笔记 / 语音助手 / AI 记忆 / 普通应用，
     * 直接调启动方法会漏掉前几种内置动作。
     */
    private fun launchSelected(
        position: Int,
        adapter: Any,
        getItem: Method?,
        launchName: String,
    ) {
        val panel = window ?: return
        val info = runCatching { getItem?.invoke(adapter, position) }.getOrNull() ?: return
        val index = listIndexOf(panel, info, position)
        val profile = this.profile
        ModuleLog.install(
            "PORTAL_LAUNCH_${position}_$index" +
                "_e" + (readField(panel, profile?.eventField ?: "") != null) +
                "_c" + (readField(panel, profile?.clipField ?: "") != null),
        )
        // 中转站分支读窗口上的 DragEvent 并取 ClipData；原处理被吞掉后该事件可能没有剪贴数据，
        // 这里按 2.7.1 的做法先放一个带剪贴数据的 DROP 事件进去。
        seedDropEvent(panel)
        val byPosition = panel.javaClass.methods.firstOrNull {
            it.name == launchName && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (byPosition != null) {
            runCatching { byPosition.invoke(panel, index) }
                .onFailure { failure ->
                    val cause = (failure as? java.lang.reflect.InvocationTargetException)?.cause
                    ModuleLog.warn("PORTAL_LAUNCH_FAILED", cause ?: failure)
                }
            dismiss()
            return
        }
        val byInfo = panel.javaClass.methods.firstOrNull {
            it.name == launchName && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(info.javaClass)
        } ?: return
        runCatching { byInfo.invoke(panel, info) }
            .onFailure { ModuleLog.warn("PORTAL_LAUNCH_FAILED", it) }
        dismiss()
    }

    /** 单条目尺寸与字色：88dp 高、34dp 图标、12sp 标签。 */
    private fun restyleItem(item: View) {
        item.layoutParams = item.layoutParams?.apply { height = dp(item, 88) }
        val night = (item.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        fun visit(view: View) {
            when (view) {
                is android.widget.TextView -> {
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    view.typeface = android.graphics.Typeface.DEFAULT
                    view.paint.isFakeBoldText = false
                    view.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
                    view.setTextColor(if (night) 0xFFF2F2F2.toInt() else 0xFF1A1A1A.toInt())
                }
                is android.widget.ImageView -> {
                    view.layoutParams = view.layoutParams?.apply {
                        width = dp(view, 34)
                        height = dp(view, 34)
                    }
                }
            }
            val group = view as? ViewGroup ?: return
            for (index in 0 until group.childCount) visit(group.getChildAt(index))
        }
        visit(item)
    }

    private fun itemCount(row: View): Int = runCatching {
        val adapter = row.javaClass.getMethod("getAdapter").invoke(row) ?: return 0
        adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int ?: 0
    }.getOrDefault(0)

    /** 视图树诊断：只在首次接管时输出一次。 */
    private fun logTree(root: View, row: View) {
        val builder = StringBuilder()
        fun walk(view: View, depth: Int) {
            if (depth > 4) return
            builder.append("  ".repeat(depth))
                .append(view.javaClass.simpleName)
                .append(" v=").append(view.visibility)
                .append(" a=").append(view.alpha)
                .append(" s=").append(view.width).append('x').append(view.height)
            if (view === row) builder.append("  <== row")
            builder.append('\n')
            val group = view as? ViewGroup ?: return
            for (index in 0 until group.childCount) walk(group.getChildAt(index), depth + 1)
        }
        walk(root, 0)
        builder.toString().trimEnd().split('\n')
            .forEach { ModuleLog.install("PORTAL_TREE_$it") }
    }

    private fun hook(method: Method, id: String, block: (XposedInterface.Chain) -> Any?) {
        runCatching {
            method.isAccessible = true
            module.hook(method)
                .setId("flymefreeform.portal.$id")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> block(chain) }
        }.onFailure { ModuleLog.warn("PORTAL_HOOK_FAILED_$id", it) }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val LISTENER_INFO_FIELD = "mListenerInfo"

        /** 拖动结束会走的服务生命周期方法，接管期间跳过。 */
        private val KEEP_ALIVE_METHODS = arrayOf("onUnbind", "onDestroy")

        /** 面板在拖动开始后才创建，最多等 2 秒（50ms × 40）。 */
        private const val POLL_ATTEMPTS = 40
        private const val POLL_INTERVAL_MS = 50L

        /** 条目后到时的唯一一次收高。 */
        private const val REINFORCE_DELAY_MS = 300L

        /** 无人操作时的兜底关闭时间。 */
        const val IDLE_DISMISS_MS = 5_000L

        /** 网格列数。 */
        const val COLUMNS = 5

        /** 设计尺寸：格子宽高、最多行数。 */
        const val CELL_WIDTH_DP = 72
        const val CELL_HEIGHT_DP = 96
        const val MAX_ROWS = 5

        /** 面板底色 = 应用页面骨架的分组背景色；不透明，面板下面是别的应用内容。 */
        const val SURFACE_LIGHT = 0xFFF2F2F7.toInt()
        const val SURFACE_DARK = 0xFF000000.toInt()

        /** 与应用主题卡片同一档圆角（Glasmio shapes.medium = 26dp）。 */
        const val PANEL_CORNER_DP = 26
    }
}

/** 单个系统版本的接管配置。混淆名逐版本实测，不跨版本复用。 */
internal data class PortalProfile(
    val portalVersion: String,
    val serviceClass: String,
    val windowClass: String,
    val receiverClass: String,
    val stateClass: String,
    val enterStateField: String,
    val dragEntry: String,
    /** 窗口上「收起面板/恢复热区光效」的方法名，接管期间跳过。 */
    val panelHideMethod: String,
    /** 面板入场动画方法名，动画结束复位窗口参数。 */
    val animMethod: String,
    /** 窗口里持有拖拽控制器的字段，控制器再持有状态回调实例。 */
    val controllerField: String,
    val receiverField: String,
    /** 状态接收者持有的底部热区 View，其拖拽监听器上挂着状态回调实例。 */
    val hotViewField: String,
    val listenerField: String,
    val callbackField: String,
    val serviceInfoClass: String,
    /** 窗口上按选中项启动应用的方法名。 */
    val launchMethod: String,
    val panelField: String,
    val listField: String,
    val screenshotField: String,
    /** 窗口上最近一次 DragEvent 与 ClipData 的字段，选中分支会读取。 */
    val listDataField: String,
    val eventField: String,
    val clipField: String,
)
