package io.github.hoerire.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot
import io.github.hoerire.flymefreeform.config.PinnedTarget
import io.github.hoerire.flymefreeform.config.PinnedTargetCodec
import io.github.hoerire.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.hoerire.flymefreeform.gesture.CornerGestureConfig
import io.github.hoerire.flymefreeform.gesture.CornerGestureEngine
import io.github.hoerire.flymefreeform.gesture.CornerTriggerRegion
import io.github.hoerire.flymefreeform.gesture.GestureAction
import io.github.hoerire.flymefreeform.gesture.GesturePhase
import io.github.hoerire.flymefreeform.gesture.PointerStreamGate
import io.github.hoerire.flymefreeform.hook.ModuleEnvironmentState
import io.github.hoerire.flymefreeform.logic.WindowForm
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration
import io.github.hoerire.flymefreeform.window.CornerRadialOverlayView
import io.github.hoerire.flymefreeform.window.CornerTouchRegionView
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import io.github.hoerire.flymefreeform.hook.OemReflection
import io.github.hoerire.flymefreeform.hook.OemClasses

/**
 * system_server 侧的手势与浮层协调器，本进程内唯一长期所有者：
 * gesture monitor、Overlay、目录缓存与启动适配都归它。
 *
 * 链路：[SystemServerGestureMonitor] 把整条指针流喂进 [gestureEngine]；引擎判定激活后
 * 在这里创建 Compose 浮层窗口。为什么 monitor 必须在本进程：pilferPointers() 抢下的
 * 只是「这一个 monitor 通道」，事件继续投递给本进程；monitor 若建在 SystemUI，
 * 抢完的 MOVE 全落到另一个进程，这里的引擎只收到 DOWN，扇形永远不激活。
 *
 * 松手时按选中目标分两条启动路径：
 * 松手时按选中目标分两条启动路径：
 * - 普通应用：本进程持 ActivityOptions 直接 startActivity（见 [launchCommittedEntry]）；
 * - 侧边栏条目（工具/快捷方式/侧边栏应用）：转交侧边栏进程执行原生路由
 *   （见 [io.github.hoerire.flymefreeform.platform.coloros.ColorOsEntryCatalogBroker]）。
 *
 * 环境门禁：横屏/游戏模式暂停、关键系统界面在前台、目标在前台黑名单内时一律不接管手势。
 * 浮层窗口类型见 [createOverlayParams]，需压在输入法之上，否则键盘弹出会遮挡扇形。
 */
internal class ColorOsFreeformCoordinator(
    private val controller: Any,
    private val classLoader: ClassLoader,
    private val configuration: ProcessConfiguration,
    private val environmentState: ModuleEnvironmentState,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) : CornerRadialOverlayView.Listener {
    private val context = OemReflection.readField(controller, "mContext") as Context
    private val handler = Handler(Looper.getMainLooper())
    private val catalogExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, CATALOG_THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val gestureEngine = CornerGestureEngine()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    // 扇形启动绕开 ActivityStarter，这里把「禁用浮窗」的判定直接传给启动器。
    private val launcher = ColorOsFreeformLauncher(
        context,
        isZoomBlocked = { packageName ->
            lastSettings.isZoomLaunchExcluded(packageName) || lastSettings.isBlacklisted(packageName)
        },
        // 判断在 FanLaunchPolicy：有可见窗口就前置，否则正常启动。
        existingForm = { packageName -> windowFormOf(packageName) },
        frontExisting = { packageName -> bringWindowToFront(packageName) },
        toggleFlexible = { packageName -> toggleFlexibleWindow(packageName) },
        centerPlacement = { lastSettings.zoomMaxWindowCountEffective == 1 },
    )
    private val windowGestures =
        ColorOsWindowGestures(context, controller, configuration, handler, logger)
    private val sidebar = ColorOsSidebarClient(context, handler, logger)
    private val appCatalog =
        ColorOsAppCatalog(context, catalogExecutor, logger) { snapshot ->
            handler.post {
                // 后台任务完成时配置可能已再次变化，旧结果不得覆盖新外观。
                if (snapshot.matches(lastSettings)) {
                    catalogSnapshot = snapshot
                    overlay?.updateRadialAppearance(snapshot)
                }
            }
        }
    private val entryCatalog = ColorOsEntryCatalogBroker(context, handler, logger) { entries ->
        appCatalog.updateSidebarEntries(entries, lastSettings)
    }

    private var entryLaunchGeneration = 0L
    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                entryCatalog.invalidate()
                if (lastSettings.enabled) {
                    appCatalog.refresh(lastSettings)
                    if (lastSettings.pinnedTargets.any { it is PinnedTarget.Sidebar }) entryCatalog.refresh()
                }
            }
        }
    private val pinnedTargetsReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ModulePreferences.ACTION_PINNED_TARGETS_CHANGED ||
                    sentFromUid != moduleUid()
                ) return
                val encoded = intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS) ?: return
                val targets = PinnedTargetCodec.decodeList(encoded)
                // 广播现在覆盖全部设置变更：先重读磁盘快照，再叠上本次推送的固定项。
                configuration.forceReload()
                configuration.override(
                    configuration.snapshot.copy(
                        enabled = intent.getBooleanExtra(
                            ModulePreferences.EXTRA_MODULE_ENABLED,
                            configuration.snapshot.enabled,
                        ),
                        pinsSaved = intent.getBooleanExtra(
                            ModulePreferences.EXTRA_PINS_SAVED,
                            configuration.snapshot.pinsSaved,
                        ),
                        pinnedTargets = targets,
                    ),
                )
            }
        }

    @Volatile
    private var catalogSnapshot = AppCatalogSnapshot()
    private var pointerListener: Any? = null
    private var gestureMonitor: SystemServerGestureMonitor? = null
    @Volatile
    private var pointerRegistered = false
    @Volatile
    private var pointerGeneration = 0L
    // 从 Activate（开始 pilfer）到 UP/CANCEL 之间为 true。
    // 这段时间里的重复 DOWN 不能当新流处理，否则会把引擎打回 Armed。
    private var pilferSessionActive = false
    // 抢流期间收到系统 CANCEL 后的观察窗：只有在没有任何 MOVE 为这条流"续命"时，
    // 才认定为流已断，按取消收场。见 [scheduleCancelWatch]。
    private var cancelWatchToken = 0L
    private var cancelWatchScheduled = false
    /** 当前这条指针流是不是从角落起手的；monitor 线程读写。 */
    @Volatile private var cornerStream = false
    /** 上一次指针事件的时间戳；用来识别"新手势"并复位卡住的抢流会话。 */
    private var lastPointerEventAt = 0L
    /** 只负责接触摸的小窗口；主窗口 FLAG_NOT_TOUCHABLE，点选靠它。 */
    private var touchRegion: CornerTouchRegionView? = null
    private var overlay: CornerRadialOverlayView? = null
    private var morePanelActive = false
    private var lastSettings = ModuleSettingsSnapshot(enabled = false)
    private var activeEnvironmentApproved = false
    private var activeGestureConfig: CornerGestureConfig? = null
    /** 当前这次扇形是从哪个下角拉起的：决定小窗从哪一侧出现。 */
    private var overlayAtLeft = true
    /** 浮层显示时刻与待执行的延迟移除标记：低于最小可见时长时不得拆除。 */



    /** 最近任务校验的节流时间戳。 */
    private var lastRecentsCheckAt = 0L

    /** 本次 overlay 实际用的窗口类型；决定它压不压在输入法上面。 */
    private var overlayWindowType = OVERLAY_WINDOW_TYPE
    private val pointerQueueLock = Any()
    private val pointerStreamGate = PointerStreamGate()
    private var pendingMove: QueuedPointerEvent? = null
    private var movePosted = false
    private var lastOverlayFailureLogAt = -OVERLAY_FAILURE_LOG_INTERVAL_MS

    fun start() {
        // 识屏宿主被限制后台运行会让三条入口同时失效；开机纠正一次，之后省电策略再改也不会失效。
        ScreenIdentifyAppOps.ensure(context)
        handler.post {
            entryCatalog.start()
            environmentState.start(context)
            environmentState.observe { applySettings(configuration.snapshot) }
            registerPackageObserver()
            registerPinnedTargetsObserver()
            configuration.observe {
                handler.post { applySettings(configuration.snapshot) }
            }
            prewarmOverlay()
        }
        logger(Log.INFO, "SYSTEM_GESTURE_COORDINATOR_READY", null)
    }


    /**
     * 预热浮层：把 Compose 的一次性成本（类加载、Skia、字体、首次组合与布局）挪到开机后的空闲时段。
     *
     * 首次呼出时现创建的话，首帧约 400ms 才出来（日志：SHOWN → 第一条 HIT_ENTER 间隔 457ms），
     * 表现为一张冻住的图：不能选、不能滑，只能等抬手后点空白关掉。
     * 这里拿一个 1×1、几乎全透明、不可触摸的窗口把整条链路走一遍，400ms 后移除。
     * 只是优化，任何一步失败都吞掉，不影响真手势。
     */
    private fun prewarmOverlay() {
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this, ::debug)
            val params = createOverlayParams()
            params.width = 1
            params.height = 1
            params.alpha = 0.01f
            windowManager.addView(view, params)
            view.begin(
                side = io.github.hoerire.flymefreeform.gesture.CornerSide.Right,
                catalog = catalogSnapshot,
                x = 0f,
                y = 0f,
                hitRangeDp = lastSettings.radialHitRangeDp,
                iconSizeDp = lastSettings.radialIconSizeDp,
                keepOpen = false,
            )
            trace("SYSTEM_OVERLAY_PREWARM_STARTED")
        } catch (_: Throwable) {
            // 预热失败不影响真手势。
        }
        val warm = view ?: return
        handler.postDelayed(
            {
                try {
                    warm.disposeOverlay()
                    windowManager.removeViewImmediate(warm)
                } catch (_: Throwable) {
                }
                trace("SYSTEM_OVERLAY_PREWARM_DONE")
            },
            OVERLAY_PREWARM_LIFETIME_MS,
        )
    }

    private fun registerPackageObserver() {
        try {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_CHANGED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addDataScheme("package")
                }
            // Coordinator 与 system_server 同生命周期；热重载被拒绝，因此只注册一次。
            context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PACKAGE_OBSERVER_UNAVAILABLE", exception)
        }
    }

    private fun registerPinnedTargetsObserver() {
        try {
            context.registerReceiver(
                pinnedTargetsReceiver,
                IntentFilter(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "PINNED_TARGETS_OBSERVER_UNAVAILABLE", exception)
        }
    }

    private fun moduleUid(): Int = try {
        context.packageManager.getApplicationInfo(
            SidebarEntryProtocol.MODULE_PACKAGE,
            android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
        ).uid
    } catch (_: RuntimeException) {
        -1
    }

    private fun applySettings(settings: ModuleSettingsSnapshot) {
        val selectionChanged =
            settings.pinsSaved != lastSettings.pinsSaved ||
                settings.pinnedTargets != lastSettings.pinnedTargets
        lastSettings = settings
        overlay?.setBackdropAlpha(settings.shadePercent / 100f)
        // monitor 只看"模块是否启用 + 角落是否开启"，不再看环境：
        // 环境判定本来就分两层（isInCornerZone 与 handlePointerEvent 都会拒），
        // 若这里还按环境决定是否注销，改设置时一旦环境判定暂时为 false，
        // monitor 就被拆掉且再也没人重建，表现是"改完触发范围后扇形完全拉不出来"。
        if (settings.enabled && (settings.leftCornerEnabled || settings.rightCornerEnabled)) {
            val resuming = !pointerRegistered
            if ((resuming || selectionChanged) && settings.pinnedTargets.any { it is PinnedTarget.Sidebar }) entryCatalog.refresh()
            registerPointerListener()
            if (resuming || selectionChanged || !catalogSnapshot.matches(settings) || catalogSnapshot.radialApps.isEmpty()) {
                appCatalog.refresh(settings, reloadApps = resuming || selectionChanged || catalogSnapshot.radialApps.isEmpty())
            }
        } else {
            sidebar.cancel()
            entryLaunchGeneration++
            entryCatalog.cancel()
            unregisterPointerListener()
            activeEnvironmentApproved = false
            activeGestureConfig = null
            gestureEngine.cancel()
            removeOverlay()
        }
    }

    /**
     * 这次按下是否落在角落热区。
     *
     * 只用来决定"这条流要不要继续处理"，与引擎判定同一套参数，保证两边判断一致。
     * 必须在 monitor 线程调用，避免每帧都跨线程。
     */
    private fun isInCornerZone(event: MotionEvent): Boolean {
        val settings = lastSettings
        if (!settings.enabled) return false
        if (!environmentState.isGestureAllowed()) return false
        val metrics = context.resources.displayMetrics
        return CornerTriggerRegion.detectSide(
            x = event.x,
            y = event.y,
            displayWidth = metrics.widthPixels.toFloat(),
            displayHeight = metrics.heightPixels.toFloat(),
            radius = CornerTriggerRegion.radiusPx(settings.cornerTriggerRangeDp, metrics.density),
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
    }

    private fun registerPointerListener() {
        if (pointerRegistered) return
        val generation = pointerGeneration
        // 首选进程内 gesture monitor：pilfer 之后事件仍留在 system_server，扇形判定不断流。
        val monitor =
            SystemServerGestureMonitor(
                context = context,
                onEvent = { event ->
                    // 浮动窗口手势（长按静音、双击放大）：与角区手势互不影响，先看它。
                    windowGestures.onEvent(event)
                    // 只处理从角落起手的流：桌面左右滑、应用内普通滑动都不在角区，
                    // 这里直接放行，连 MotionEvent 复制和主线程投递都省掉。
                    // 之前每一帧都 obtain+post，桌面滑动时页面抖动由该开销导致。
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        // 菜单已经挂出来时，主窗口不可触摸，事件会穿透到下层应用：
                        // 这里只是"看一眼"有没有点在菜单外面，用来关闭菜单。
                        // 只对 DOWN 做一次判定，不接流、不复制 MotionEvent。
                        // 只要菜单在屏幕上就检测：pilferSessionActive 可能残留为 true
                        // （流被系统掐断时没有 UP），拿它当条件会让点空白完全失效。
                        if (overlay != null) {
                            val x = event.x
                            val y = event.y
                            handler.post { handleOutsideTouch(x, y) }
                            cornerStream = false
                        } else {
                            // 区域归属只在 DOWN 判定一次。之前每个事件都重算，
                            // 手指从角落滑出该区域后 cornerStream 变 false，后续 MOVE
                            // 全被放行给应用，页面先横向滚动后才被接管，表现为抖动。
                            cornerStream = isInCornerZone(event)
                        }
                    }
                    if (!cornerStream) {
                        false
                    } else {
                        enqueuePointerEvent(event, generation)
                        // 绝不能在 DOWN 就抢流：pilfer 拿走的是整条流，点击的 UP 也一并被
                        // 拿走，角落区就彻底点不上了。挖角本来只挡系统手势，点击必须留给应用。
                        // 所以维持"极小位移阈值"：应用能收到完整 DOWN/UP（点击正常），
                        // 一旦开始拖动就在 6px 处抢走，页面来不及滚出可见位移。
                        shouldStartPilfer(event)
                    }
                },
                logger = logger,
            )
        if (monitor.start(MONITOR_NAME + ":" + context.packageName)) {
            gestureMonitor = monitor
            pointerRegistered = true
            trace("SYSTEM_POINTER_LISTENER_REGISTERED")
            return
        }
        registerWindowPointerListener(generation)
    }

    /** monitor 建不起来时的退路：WMS 指针监听（只观察，不抢流）。 */
    private fun registerWindowPointerListener(generation: Long) {
        try {
            // 系统指针监听仅有隐藏接口；注册失败由本方法回退，不影响系统输入。
            @SuppressLint("PrivateApi")
            val listenerInterface =
                Class.forName(
                    "android.view.WindowManagerPolicyConstants\$PointerEventListener",
                    false,
                    classLoader,
                )
            val listener =
                Proxy.newProxyInstance(classLoader, arrayOf(listenerInterface)) { proxy, method, args ->
                    when (method.name) {
                        "onPointerEvent" -> {
                            val event = args?.firstOrNull() as? MotionEvent
                            if (event != null) enqueuePointerEvent(event, generation)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "FlymeFreeformPointerListener"
                        else -> null
                    }
                }
            val windowManagerService = readWindowManagerService()
            val register = OemReflection.requireMethod(windowManagerService.javaClass, "registerPointerEventListener", 2)
            register.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
            pointerListener = listener
            pointerRegistered = true
            // 每次重新注册都会走到这里，事件级信息，交给详细日志。
            debug("SYSTEM_POINTER_LISTENER_REGISTERED")
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_FAILED", exception)
        }
    }

    private fun enqueuePointerEvent(event: MotionEvent, generation: Long) {
        if (!pointerRegistered || generation != pointerGeneration) return
        val stream = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pointerStreamGate.begin()
        } else {
            pointerStreamGate.current()
        }
        // DOWN 仍用于取消待执行动作；主线程排除角落手势后才停止复制该流的后续事件。
        if (event.actionMasked != MotionEvent.ACTION_DOWN && !pointerStreamGate.shouldForward(stream)) return
        val copy = QueuedPointerEvent(MotionEvent.obtain(event), generation, stream)
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            val precedingMove =
                synchronized(pointerQueueLock) {
                    pendingMove.also { pendingMove = null }
                }
            handler.post {
                precedingMove?.let(::processPointerEvent)
                processPointerEvent(copy)
            }
            return
        }
        var shouldPost = false
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = copy
            if (!movePosted) {
                movePosted = true
                shouldPost = true
            }
        }
        if (shouldPost) handler.post(::drainPendingMove)
    }

    private fun drainPendingMove() {
        val event =
            synchronized(pointerQueueLock) {
                movePosted = false
                pendingMove.also { pendingMove = null }
            } ?: return
        processPointerEvent(event)
    }

    private fun processPointerEvent(queued: QueuedPointerEvent) {
        val event = queued.event
        try {
            if (queued.generation != pointerGeneration || !pointerRegistered) return
            if (event.actionMasked != MotionEvent.ACTION_DOWN && !pointerStreamGate.shouldForward(queued.stream)) return
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                entryLaunchGeneration++
                entryCatalog.cancelLaunch()
            }
            handlePointerEvent(event)
            if (!shouldKeepPointerStream()) {
                activeEnvironmentApproved = false
                activeGestureConfig = null
                pointerStreamGate.ignore(queued.stream)
            }
        } catch (exception: RuntimeException) {
            gestureEngine.cancel()
            removeOverlay()
            unregisterPointerListener()
            logger(Log.ERROR, "SYSTEM_POINTER_PROCESSING_FAILED", exception)
        } finally {
            event.recycle()
        }
    }

    private fun unregisterPointerListener() {
        pointerGeneration++
        pointerStreamGate.reset()
        clearPendingMove()
        pointerRegistered = false
        gestureMonitor?.stop()
        gestureMonitor = null
        val listener = pointerListener ?: return
        try {
            val windowManagerService = readWindowManagerService()
            val unregister = OemReflection.requireMethod(windowManagerService.javaClass, "unregisterPointerEventListener", 2)
            unregister.invoke(windowManagerService, listener, Display.DEFAULT_DISPLAY)
        } catch (exception: ReflectiveOperationException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_POINTER_LISTENER_REMOVE_FAILED", exception)
        } finally {
            pointerListener = null
        }
    }

    private fun clearPendingMove() {
        synchronized(pointerQueueLock) {
            pendingMove?.event?.recycle()
            pendingMove = null
            movePosted = false
        }
    }

    // 「更多」面板开着时指针流必须继续：否则扇形呼不出来，页就没有机会把面板收掉。
    private fun shouldKeepPointerStream(): Boolean =
        activeEnvironmentApproved && when (gestureEngine.phase) {
            GesturePhase.Armed, GesturePhase.Revealing, GesturePhase.Selecting -> true
            else -> false
        }

    // 与 UI 引擎分开的一份判定实例：只在 monitor 线程同步跑，专门回答"这一帧能不能抢流"。
    // 抢流不可逆，所以必须等引擎确认这是角落斜推（Activate）再抢，不能让普通点击陪葬。
    private val pilferEngine = CornerGestureEngine()
    /** 本条手势是否已经输出过抢流诊断（防止每帧重复打点）。 */
    private var pilferLoggedThisGesture = false

    private var pilferConfig: CornerGestureConfig? = null
    private var pilferDownX = 0f
    private var pilferDownY = 0f
    /** 本次抢流时手指累计位移（像素），仅用于诊断输出。 */
    private var pilferDistance = 0f

    private fun shouldStartPilfer(event: MotionEvent): Boolean =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pilferEngine.cancel()
                pilferConfig = null
                val settings = lastSettings
                if (!settings.enabled || !environmentState.isGestureAllowed()) {
                    false
                } else {
                    val metrics = context.resources.displayMetrics
                    val config =
                        AdaptiveCornerGestureConfig.create(
                            displayWidth = metrics.widthPixels.toFloat(),
                            displayHeight = metrics.heightPixels.toFloat(),
                            touchSlop = touchSlop,
                            density = metrics.density,
                            triggerRangeDp = settings.cornerTriggerRangeDp,
                            leftEnabled = settings.leftCornerEnabled,
                            rightEnabled = settings.rightCornerEnabled,
                        )
                    pilferConfig = config
                    pilferDownX = event.x
                    pilferDownY = event.y
                    pilferEngine.down(event.getPointerId(0), event.x, event.y, config)
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val config = pilferConfig ?: return false
                val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                val x = event.getX(index)
                val y = event.getY(index)
                // 抢流时机压到"第一个 MOVE"：
                // 页面横向滚动是按手指位移累加的，只要应用多收到一帧 MOVE 就会滚一段，
                // 接管后回弹即用户可见的横移。等 touchSlop（8dp≈28px）才抢，
                // 这 28px 已经足够桌面翻小半页。所以这里只用一个极小的位移阈值挡手抖，
                // 方向判定照旧：偏上（回桌面/迷你窗）让给系统，其余立刻抢。
                if (pilferEngine.phase == GesturePhase.Armed) {
                    val downX = pilferDownX
                    val downY = pilferDownY
                    val isLeftSide = downX <= config.displayWidth / 2f
                    val inward = if (isLeftSide) x - downX else downX - x
                    val upward = downY - y
                    val moved = kotlin.math.sqrt(inward * inward + upward * upward)
                    if (moved < PILFER_MIN_MOVE_PX) return false
                    // 只让这一帧，不弃权整条手势。
                    // 从底部角落起手时手指天然先往上走，首帧的 upward 必然偏大；
                    // 此处不得 cancel 并把 pilferConfig 置空：该流一旦不再被抢，
                    // 而 UI 引擎逐帧重判，稍后方向转内时扇形仍然激活，
                    // 结果是扇形弹出但事件留在应用侧，形成穿透。
                    if (upward > inward * VERTICAL_ABORT_RATIO) return false
                    // 诊断：抢流这一刻手指已经移动了多远。数值大说明应用在抢流前就收到了足够的位移。
                    pilferDistance = moved
                    logFirstPilfer(moved)
                    return true
                }
                pilferEngine.move(
                    event.getPointerId(index),
                    event.pointerCount,
                    x,
                    y,
                    config,
                ) is GestureAction.Activate
            }
            else -> {
                pilferEngine.cancel()
                pilferConfig = null
                false
            }
        }

    /**
     * 抢流诊断只在本条手势**第一次**抢准时输出一次。
     *
     * 抢流成功后 pilferEngine 仍停在 Armed，该判定每帧都会再次进入；
     * 每次进入都打日志会让单条手势产生数百行输出，并带来每帧的字符串开销，故仅记录首次。
     */
    private fun logFirstPilfer(movedPx: Float) {
        if (pilferLoggedThisGesture) return
        pilferLoggedThisGesture = true
        trace("SYSTEM_PILFER_AT_" + movedPx.toInt() + "PX")
    }

    private fun handlePointerEvent(event: MotionEvent) {
        // 会话间隙保护：抢流后如果流被系统掐断（没有 UP/CANCEL），pilferSessionActive 会一直
        // 停在 true，之后每一次新的按下都被当成"重复 DOWN"走 rebase，扇形再也激活不了
        // （实机 02:49:08：连续 REBASED、没有一次 ACTIVATE）。这里用事件间隔判定新手势。
        val now = android.os.SystemClock.uptimeMillis()
        if (pilferSessionActive && now - lastPointerEventAt > SESSION_GAP_MS) {
            trace("SYSTEM_GESTURE_SESSION_RESET_STALE")
            pilferSessionActive = false
            gestureEngine.cancel()
        }
        lastPointerEventAt = now
        val settings = lastSettings
        if (!pointerRegistered || !environmentState.isGestureAllowed()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                trace("SYSTEM_GESTURE_SKIPPED_ENV_" + environmentRejectionReason())
            }
            cancelActiveGesture()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && pilferSessionActive) {
            // 抢流后系统重发的那次 DOWN：只换基准点，不重置会话（日志：
            // 走重置会把引擎打回 Armed，接着 CANCEL 一来整条流就断了，菜单定格）。
            gestureEngine.rebase(event.x, event.y)
            trace("SYSTEM_GESTURE_DOWN_REBASED_" + event.x.toInt() + "_" + event.y.toInt())
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 手势生命周期的第一行，无条件记；每根手指只有一行，热路径负担可以忽略。
            pilferLoggedThisGesture = false
            trace("SYSTEM_GESTURE_DOWN_" + event.x.toInt() + "_" + event.y.toInt())
            activeEnvironmentApproved = isGestureEnvironmentAllowed()
            if (!activeEnvironmentApproved) {
                trace("SYSTEM_GESTURE_REJECTED_" + environmentRejectionReason())
                activeGestureConfig = null
                return
            }
        } else if (!activeEnvironmentApproved) {
            return
        }
        if (overlay != null && !isDynamicEnvironmentAllowed()) {
            activeEnvironmentApproved = false
            cancelActiveGesture()
            return
        }
        val metrics = context.resources.displayMetrics
        val config =
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                AdaptiveCornerGestureConfig.create(
                    displayWidth = metrics.widthPixels.toFloat(),
                    displayHeight = metrics.heightPixels.toFloat(),
                    touchSlop = touchSlop,
                    density = metrics.density,
                    triggerRangeDp = settings.cornerTriggerRangeDp,
                    leftEnabled = settings.leftCornerEnabled,
                    rightEnabled = settings.rightCornerEnabled,
                ).also { activeGestureConfig = it }
            } else {
                activeGestureConfig ?: return
            }
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    gestureEngine.down(event.getPointerId(0), event.x, event.y, config)
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                    gestureEngine.move(
                        event.getPointerId(index),
                        event.pointerCount,
                        event.getX(index),
                        event.getY(index),
                        config,
                    )
                }
                MotionEvent.ACTION_UP -> gestureEngine.up(event.getPointerId(event.actionIndex))
                // 抢流之后系统可能给旧流发一次 CANCEL。直接当成取消会立刻复位引擎，
                // 所以先挂一个观察窗：观察窗内没有任何 MOVE 才认定为流已断、按取消收场；
                // 有 MOVE 说明流还活着，观察窗随即作废（见 cancelWatchOnActivity）。
                MotionEvent.ACTION_CANCEL ->
                    if (pilferSessionActive) {
                        scheduleCancelWatch()
                        GestureAction.Ignore
                    } else {
                        gestureEngine.cancel()
                    }
                // 多指按下不再取消主手势，只忽略新增的那根手指。
                MotionEvent.ACTION_POINTER_DOWN -> GestureAction.Ignore
                else -> if (gestureEngine.isClaimed) gestureEngine.cancel() else GestureAction.Ignore
            }
        if (event.actionMasked == MotionEvent.ACTION_MOVE ||
            event.actionMasked == MotionEvent.ACTION_UP
        ) {
            cancelWatchOnActivity()
        }
        if (action is GestureAction.Ignore) {
            debug("SYSTEM_GESTURE_IGNORE")
        }
        when (action) {
            is GestureAction.Activate -> {
                pilferSessionActive = true
                trace(
                    "SYSTEM_GESTURE_ACTIVATE_" + action.side.name + "_" +
                        action.x.toInt() + "_" + action.y.toInt(),
                )
                // 保持挂起时屏幕上已经有一份扇形：此时从角区起手的上滑（返回桌面、
                // 进多任务）也会被判成 Activate，若照常重建，就会看到"扇形收起来又冒出来"。
                // 已有浮层时不再重建，把这个手势交回系统。
                if (overlay != null && lastSettings.radialKeepOpen) {
                    trace("SYSTEM_GESTURE_ACTIVATE_IGNORED_PINNED")
                    gestureEngine.cancel()
                } else {
                    showOverlay(
                        side = action.side,
                        x = action.x,
                        y = action.y,
                    )
                }
            }
            is GestureAction.Update -> {
                val selected = overlay?.updateGesture(action.x, action.y)
                gestureEngine.setSelection(selected)
            }
            is GestureAction.Commit -> {
                val view = overlay
                // 开了「保持挂起」时不判误触：该模式下用户期望滑动后扇形保持显示，
                // 再按「展开动画是否跑完」去拦，会变成"要等一会儿才挂得住"。
                if (view != null && !view.isSettled() && !lastSettings.radialKeepOpen) {
                    // 扇形还在展开中就抬手：快速上滑容易命中第一格而"秒启动"，判为误触。
                    debug("SYSTEM_GESTURE_COMMIT_TOO_EARLY")
                    view.cancelGesture()
                } else {
                    trace("SYSTEM_GESTURE_COMMIT")
                    view?.finishGesture()
                }

            }
            // 手势被判取消时不要立刻收起：交给抬手（Commit）或系统 ACTION_CANCEL 决定，
            // 否则滑动过程中一旦被判取消，扇形就会自己消失。
            GestureAction.Cancel -> Unit
            GestureAction.Ignore, GestureAction.PassThrough -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeEnvironmentApproved = false
            activeGestureConfig = null
            pilferSessionActive = false
        }
    }

    /** 打开「更多」面板前按最新最近任务重排一次；扇形呼出不走这里。 */
    private fun refreshPanelRecents() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRecentsCheckAt < RECENTS_CHECK_INTERVAL_MS) return
        lastRecentsCheckAt = now
        appCatalog.refresh(lastSettings, reloadApps = false, forceRecentOrder = true)
    }

    private fun showOverlay(
        side: io.github.hoerire.flymefreeform.gesture.CornerSide,
        x: Float,
        y: Float,
    ) {
        entryLaunchGeneration++
        entryCatalog.cancelLaunch()
        removeOverlay()
        // 扇形呼出时收掉「更多」面板：面板是应用层之上的 overlay，留着会盖住扇形，
        // 也会盖住随后打开的小窗。这里只做这一件事，面板内部状态机不动。
        if (sidebar.isPanelActive()) {
            sidebar.cancel()
            debug("SYSTEM_MORE_PANEL_CLOSED_BY_OVERLAY")
        }
        morePanelActive = false
        overlayAtLeft = side == io.github.hoerire.flymefreeform.gesture.CornerSide.Left

        val hitRangeDp = lastSettings.radialHitRangeDp
        debug(
            "SYSTEM_OVERLAY_HIT_RANGE_" + hitRangeDp + "DP_" +
                (hitRangeDp * context.resources.displayMetrics.density).toInt() + "PX_" +
                "SIDE_" + side.name +
                "_APPS_" + catalogSnapshot.radialApps.size +
                "_PANEL_" + catalogSnapshot.panelApps.size +
                "_BLACKLIST_" + lastSettings.blacklistPackages.size,
        )
        if (catalogSnapshot.radialApps.isEmpty()) {
            // 目录尚未就绪时扇形只有「更多」一格；触发一次刷新，下次呼出恢复。
            debug("SYSTEM_CATALOG_EMPTY_REFRESH")
            appCatalog.refresh(lastSettings, reloadApps = true)
        }
        // 保持挂起只影响「抬手后是否收起」，与窗口是否可触摸无关：
        // 触摸统一由 attachTouchRegion() 那块小窗口接住。
        val keepOpen = lastSettings.radialKeepOpen
        val params = createOverlayParams()
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this, ::debug)
            // 每次呼出都按当前设置初始化遮罩浓度：设置变化时浮层通常不存在，
            // 只靠 applySettings 里的注入会漏掉"改完再呼出"这条路径。
            view.setBackdropAlpha(lastSettings.shadePercent / 100f)
            view.begin(
                side = side,
                catalog = catalogSnapshot,
                x = x,
                y = y,
                hitRangeDp = hitRangeDp,
                iconSizeDp = lastSettings.radialIconSizeDp,
                keepOpen = keepOpen,
            )
            overlay = view
            try {
                windowManager.addView(view, params)
                overlayWindowType = OVERLAY_WINDOW_TYPE
                trace("SYSTEM_OVERLAY_SHOWN_" + overlayWindowType)
                trace("SYSTEM_OVERLAY_TYPE_" + OVERLAY_WINDOW_TYPE)
            } catch (exception: RuntimeException) {
                // 输入法层的窗口类型被系统拒绝时，退回普通悬浮窗，至少保证扇形可用。
                val fallback = createOverlayParams(type = FALLBACK_OVERLAY_TYPE)
                windowManager.addView(view, fallback)
                overlayWindowType = FALLBACK_OVERLAY_TYPE
                logger(Log.WARN, "SYSTEM_OVERLAY_TYPE_FALLBACK_" + FALLBACK_OVERLAY_TYPE, exception)
            }
            // 主窗口只是画；点选必须另有人接。
            attachTouchRegion()
        } catch (exception: RuntimeException) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", exception)
        } catch (error: LinkageError) {
            handleOverlayFailure(view, "SYSTEM_OVERLAY_ADD_FAILED", error)
        }
    }

    private fun handleOverlayFailure(
        view: CornerRadialOverlayView?,
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        gestureEngine.cancel()
        if (view != null) {
            overlay = view
            removeOverlay()
        } else {
            overlay = null
        }
        logOverlayFailure(diagnosticCode, throwable)
    }

    /** 详细日志里标注扇形为什么被拦下：暂停规则 / 关键系统界面在前台。 */
    private fun environmentRejectionReason(): String {
        val foreground = focusedWindowPackage()
        return when {
            lastSettings.isBlacklisted(foreground) -> "BLACKLISTED_" + foreground
            isCriticalSystemUiForeground() -> "CRITICAL_FOREGROUND"
            else -> environmentState.gestureBlockReason() ?: "UNKNOWN"
        }
    }

    /** 手势生命周期事件：受「详细日志」控制。 */
    private fun trace(code: String) {
        // 同样受「详细日志」控制：关掉之后连这些状态点也不输出，
        // 否则一次手势仍会刷出十几行（DOWN/REJECTED/ACTIVATE/SHOWN/COMMIT…）。
        if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
            logger(Log.DEBUG, code, null)
        }
    }

    /** 高频/细节日志，仍受设置里的详细日志开关控制（MOVE、启动细节、通知等）。 */
    private fun debug(code: String) {
        if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) logger(Log.DEBUG, code, null)
    }

    private fun logOverlayFailure(
        diagnosticCode: String,
        throwable: Throwable,
    ) {
        val now = SystemClock.uptimeMillis()
        if (now - lastOverlayFailureLogAt >= OVERLAY_FAILURE_LOG_INTERVAL_MS) {
            lastOverlayFailureLogAt = now
            logger(Log.WARN, diagnosticCode, throwable)
        }
    }

    override fun onEntryCommitted(entry: RadialEntry) {
        entryLaunchGeneration++
        entryCatalog.cancelLaunch()
        val generation = pointerGeneration
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        // 退场动画已停用，浮层在松手当帧直接撤掉：
        // 留着它会让深色背景盖住新窗口（表现为黑色遮罩）。
        removeOverlay(immediate = true)
        handler.post {
            if (generation == pointerGeneration) launchCommittedEntry(entry)
        }
    }

    private fun launchCommittedEntry(entry: RadialEntry) {
        if (!isGestureEnvironmentAllowed()) return
        val target = entry.target
        if (target is PinnedTarget.Sidebar) {
            val focus = focusedWindow() ?: return
            val rotation = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
            val generation = ++entryLaunchGeneration
            debug("SYSTEM_LAUNCH_SIDEBAR_" + target.kind.name + "_" + target.alias)
            // 只有取屏类工具要求浮层彻底消失；应用/快捷方式在浮层淡出的同时就放行。
            val requiresHiddenOverlay = target.kind == PinnedTarget.Kind.Tool
            // removeViewImmediate 后等待两次合成帧，再把执行权交给取屏类工具。
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback {
                    if (generation == entryLaunchGeneration &&
                        (!requiresHiddenOverlay || overlay == null) && isGestureEnvironmentAllowed()) {
                        entryCatalog.launch(target) {
                            generation == entryLaunchGeneration &&
                                (!requiresHiddenOverlay || overlay == null) && isGestureEnvironmentAllowed() &&
                                target in lastSettings.pinnedTargets && focusedWindow() === focus &&
                                context.getSystemService(android.hardware.display.DisplayManager::class.java)
                                    .getDisplay(Display.DEFAULT_DISPLAY)?.rotation == rotation
                        }
                    }
                }
            }
            return
        }
        target as PinnedTarget.App
        debug("SYSTEM_LAUNCH_APP_" + target.packageName + "_LEFT_" + overlayAtLeft)
        // 迷你/贴边的任务是"缩小的"浮动窗口，直接重启会被系统复用、把原比例带回来。
        // 那种情况强制新建任务（MULTIPLE_TASK），得到标准尺寸；旧的那个交由系统按上限收掉。
        // 诊断（详细日志）：把这次决策看到的真实状态打出来——每个该包的任务
        // 编号、是否可见、是否浮动场景、系统 zoomState，以及最终判定。
        runCatching {
            val candidates = tasksOfFocusedDisplay()
                ?.filter { packageOfTask(it) == target.packageName }
                ?: emptyList()
            val detail = candidates.joinToString(" | ") { task ->
                "id" + taskIdOf(task) +
                    "_vis" + isTaskVisible(task) +
                    "_flex" + isFlexibleScenario(task) +
                    "_zoom" + flexZoomStateOf(task)
            }
            debug("SYSTEM_FORM_" + target.packageName + "_n" + candidates.size + "_" + detail)
        }
        val restorable = restorableWindowsOf(target.packageName)
        debug(
            "SYSTEM_DECISION_" + target.packageName + "_form" + windowFormOf(target.packageName) +
                "_restorable" + restorable.size,
        )
        // 迷你窗/浮标都不该"新建任务"（每点一次多一个任务，日志中出现过累积到 25 个的情况）。
        // 系统自己点迷你窗走的是 FlexibleTaskCaptionView.onSingleTapConfirmed →
        //   controller.switchState(task, 1, "onSingleTapConfirmed")
        // 这里调同一个方法、同一个目标状态，动画与状态机完全一致。
        if (restorable.isNotEmpty()) {
            var restored = 0
            for (task in restorable) {
                if (switchTaskToDefault(task)) restored++
            }
            if (restored > 0) {
                debug("SYSTEM_RESTORED_TO_DEFAULT_" + target.packageName + "_n" + restored)
                return
            }
        }
        // 贴边浮标里的任务：手指点浮标走的是 FloatHandleView.onSingleTapUp →
        // FloatHandleController.startActivityByFloatInfo(info, "onSingleTapUp")，
        // 由它把任务从浮标展开成小窗（SurfaceControl 动画 + startActivityFromRecents）。
        // 这里直接调同一个入口：走"提到前台"对浮标无效——保活让这个任务在 AMS 里
        // 仍然是"已恢复/聚焦"状态，系统会判定无需处理，表现就是点了没有任何反应。
        val ballTaskId = ballTaskIdOf(target.packageName)
        if (ballTaskId != null && openBallTask(ballTaskId)) {
            debug("SYSTEM_LAUNCH_BALL_OPENED_" + target.packageName + "_" + ballTaskId)
            return
        }
        val launchResult = launcher.launch(
            android.content.ComponentName(target.packageName, target.className),
            atLeft = overlayAtLeft,
        )
        when (val result = launchResult) {
            FreeformLaunchResult.Started -> debug("SYSTEM_LAUNCH_APP_OK_" + target.packageName)
            FreeformLaunchResult.AlreadyOpen ->
                debug("SYSTEM_LAUNCH_APP_ALREADY_OPEN_" + target.packageName)
            is FreeformLaunchResult.TargetUnavailable -> {
                // WARN 常开：目标不可用时必须能定位到具体组件与原因。
                val code = "FREEFORM_LAUNCH_TARGET_UNAVAILABLE_" +
                    result.component.packageName + "/" + result.component.className +
                    "_" + result.reason
                debug(code)
                logger(Log.WARN, code, null)
            }
            is FreeformLaunchResult.Failed -> {
                debug("SYSTEM_LAUNCH_APP_FAILED_" + result.diagnosticCode)
                logger(Log.WARN, result.diagnosticCode, result.cause)
            }
        }
    }

    override fun onMorePanelRequested() {
        debug("SYSTEM_MORE_PANEL_REQUESTED")
        // 「更多」面板用最新的最近任务重排；扇形本身已不再依赖这份数据。
        refreshPanelRecents()
        if (!isGestureEnvironmentAllowed()) {
            removeOverlay()
            return
        }
        val view = overlay ?: return
        morePanelActive = true
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        sidebar.open(
                beforeOpen = {
                    val ready =
                        overlay === view && lastSettings.enabled && isGestureEnvironmentAllowed()
                    debug("SYSTEM_MORE_PANEL_BEFORE_OPEN_" + ready)
                    if (ready) {
                        view.retainBackdropForPanel()
                        // 面板是系统独立窗口，必须把屏幕让给它：摘掉模块自己的触摸层，
                        // 否则面板与工具的首次点击会被 [CornerTouchRegionView] 截获。
                        applyBackdropPassThrough(view)
                        true
                    } else {
                        false
                    }
                },
                onResult = { result ->
                    debug("SYSTEM_MORE_PANEL_RESULT_" + result.name)
                    if (overlay === view) {
                        if (result == ColorOsSidebarClient.Outcome.Fallback && lastSettings.enabled && isGestureEnvironmentAllowed() &&
                            context.getSystemService(android.os.UserManager::class.java)?.isUserForeground == true
                        ) {
                            view.visibility = android.view.View.VISIBLE
                            showBuiltInMorePanel(view)
                        } else if (result != ColorOsSidebarClient.Outcome.Shown) removeOverlay()
                    }
                },
                onExitStarted = { if (overlay === view) view.beginBackdropExit() },
                onHideBackdrop = { hidden ->
                    if (overlay === view) view.hideBackdropAfterFrame(hidden) else hidden()
                },
                onClosed = { if (overlay === view) removeOverlay() },
            )
    }

    private fun showBuiltInMorePanel(view: CornerRadialOverlayView) {
        val params = createPanelParams()
        detachTouchRegion()
        try {
            windowManager.updateViewLayout(view, params)
            view.showBuiltInMorePanel()
            view.requestFocus()
            debug("SYSTEM_BUILTIN_PANEL_SHOWN_f" + params.flags)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_FOCUS_FAILED", exception)
            removeOverlay()
        }
    }

    /**
     * 面板打开期间让出屏幕。
     *
     * 「更多」面板是系统的独立窗口，模块这层必须彻底退到它后面：
     * - 主窗口在面板模式下不画遮罩（见 [CornerRadialOverlayView] 的 backdropOnlyState），
     *   否则会把面板整体压暗；
     * - 摘掉 [CornerTouchRegionView]：它覆盖屏幕下半部分且可触摸，留着会吞掉
     *   面板与工具的点击（现象：面板无法点击）。
     */
    private fun applyBackdropPassThrough(view: CornerRadialOverlayView) {
        try {
            val params = createOverlayParams()
            windowManager.updateViewLayout(view, params)
            detachTouchRegion()
            debug("SYSTEM_BACKDROP_PASS_THROUGH_OK")
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_BACKDROP_PASS_THROUGH_FAILED", exception)
        }
    }

    override fun onDismissRequested() {
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        // 退场动画已播完，直接移除；不再经过最小可见时长的延迟。
        removeOverlay(immediate = true)
    }

    override fun onCleanupFailed(throwable: Throwable) {
        logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
    }

    /**
     * 底部手势区的高度（像素）。
     *
     * 保持挂起的浮层要把这一条让给系统，否则从底部上滑会被窗口拦截。
     * 优先读系统 navigation_bar_height（手势导航下就是那条手势区），读不到才用兜底值；
     * 再补一点余量，盖住系统实际判定的吸附边界。
     */
    private fun bottomGestureInsetPx(metrics: android.util.DisplayMetrics): Int {
        val resources = context.resources
        val identifier = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val systemHeight =
            if (identifier > 0) {
                runCatching { resources.getDimensionPixelSize(identifier) }.getOrDefault(0)
            } else {
                0
            }
        return if (systemHeight > 0) {
            systemHeight + (4 * metrics.density).toInt()
        } else {
            (BOTTOM_GESTURE_INSET_DP * metrics.density).toInt()
        }
    }

    /**
     * 菜单之外的一次按下：关闭菜单。
     *
     * 主窗口不可触摸之后，点空白不再由窗口接住，只能靠 monitor 的副本"看到"。
     * 只有一个区域例外：
     * - 菜单区域（下方一块）：由 [CornerTouchRegionView] 处理点选与空白关闭，这里不重复；
     * - 其余全部关闭，**包括底部手势区**：从底部上滑是"离开当前界面"，
     *   挂起的扇形必须一起收起，否则它会留在窗口栈里、回到桌面后重新显示出来。
     */
    private fun handleOutsideTouch(x: Float, y: Float) {
        if (overlay == null) return
        val metrics = context.resources.displayMetrics
        val height = metrics.heightPixels.toFloat()
        val inset = bottomGestureInsetPx(metrics).toFloat()
        val menuTop = height - inset - height * CornerTouchRegionView.HEIGHT_FRACTION
        // 菜单区域：由触摸窗口处理点选与空白关闭，这里不重复。
        val inMenuRegion = y >= menuTop && y < height - inset
        if (inMenuRegion) return
        // 其余一律关闭，**包括底部手势区**：
        // 从底部上滑是"离开当前界面"（回桌面 / 进多任务），扇形必须一起收起来。
        // 手势区不设例外：一旦保留挂起的扇形，它会留在窗口栈中，
        // 回到桌面后重新显示，表现为扇形自行弹出。
        trace("SYSTEM_OVERLAY_OUTSIDE_CLOSE")
        // 只启动缩回动画：cancelActiveGesture 内部会调 overlay.cancelGesture()，
        // 动画播完由 completeRadialExit → onDismissRequested 收场。
        // 紧跟着 removeOverlay 会把窗口立刻拆掉，动画一帧都看不到。
        cancelActiveGesture()
    }

    /**
     * 挂上只覆盖菜单区域的触摸窗口。
     *
     * 主窗口 FLAG_NOT_TOUCHABLE 之后没有人接点选，这里补一块。它和主窗口同层
     * （同一个 overlayWindowType），所以压在应用之上；尺寸只有屏幕下方一半，
     * 底部再让出手势条，因此不会碰到系统上滑。
     */
    private fun attachTouchRegion() {
        detachTouchRegion()
        val metrics = context.resources.displayMetrics
        val inset = bottomGestureInsetPx(metrics)
        val params = CornerTouchRegionView.layoutParams(context, overlayWindowType, inset)
        val view =
            CornerTouchRegionView(context) { event, offsetY ->
                handleTouchRegionEvent(event, offsetY)
            }
        view.screenOffsetY = (metrics.heightPixels - params.height).coerceAtLeast(0)
        try {
            windowManager.addView(view, params)
            touchRegion = view
            trace("SYSTEM_TOUCH_REGION_ATTACHED_H" + params.height)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_TOUCH_REGION_FAILED", exception)
        }
    }

    /** 摘掉触摸窗口；与主窗口同生共死。 */
    private fun detachTouchRegion() {
        val view = touchRegion ?: return
        touchRegion = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 已被系统移除。
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_TOUCH_REGION_REMOVE_FAILED", exception)
        }
    }

    /**
     * 触摸窗口收到的事件转成屏幕坐标，喂给扇形做命中判定。
     *
     * 这条路径只在「菜单已经挂出来」时使用：手势进行中的选格走 gesture monitor，
     * 这里是窗口点选（含保持挂起后的单击）。
     */
    private fun handleTouchRegionEvent(event: MotionEvent, offsetY: Int) {
        val view = overlay ?: return
        // 内建面板本身即为可触摸窗口，触摸层这时候不该再判空白/选格。
        if (view.isPanelMode()) return
        val x = event.x
        val y = event.y + offsetY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val selected = view.updateGesture(x, y)
                gestureEngine.setSelection(selected)
            }
            MotionEvent.ACTION_UP -> {
                val selected = view.updateGesture(x, y)
                if (selected == null) {
                    // 点在扇形之外：即点空白关闭。没有这一支的话，
                    // 主窗口又不可触摸，菜单会一直挂在屏幕上无法关闭。
                    trace("SYSTEM_OVERLAY_TOUCH_BLANK_CLOSE")
                    cancelActiveGesture()
                } else {
                    trace("SYSTEM_HIT_RELEASE_TOUCH_REGION")
                    view.finishGesture()
                }
            }
            MotionEvent.ACTION_CANCEL -> view.cancelGesture()
        }
    }

    /**
     * 主窗口只负责「画」，一律不接收触摸。
     *
     * 触摸由另建的 [CornerTouchRegionView]（只覆盖菜单区域的小窗口）捕获，
     * 点选交给 [CornerRadialOverlayView.updateGesture]，外部点击交给 gesture monitor。
     * 以避免全屏可触摸窗口拦截底部手势——
     * 这也是 v744 的做法：它的角区窗口带 FLAG_NOT_TOUCHABLE，事件全走 monitor。
     *
     * 不使用 FLAG_DIM_BEHIND：它会让系统对下层窗口做调暗合成，每次呼出都会让
     * 屏幕内容左右抖一下。遮罩改回由 Compose 画（窗口本身 NOT_TOUCHABLE，不影响触摸）。
     * windowAnimations 也显式置 0，避免系统给新窗口套上默认的进入动画。
     */
    /**
     * 内建「更多」面板的窗口参数。
     *
     * 与扇形主窗口的唯一区别是**可触摸**。回退到内建面板时系统侧边栏未接收输入事件，
     * 如果窗口还带 FLAG_NOT_TOUCHABLE、触摸层又被摘掉，面板就收不到任何点击，
     * 下一次按下会被 monitor 当成"点空白"直接把它关掉（实机 00:58:14 就是这样）。
     * 同时去掉 FLAG_NOT_FOCUSABLE，让返回键能关闭面板。
     */
    private fun createPanelParams(
        type: Int = overlayWindowType,
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            windowAnimations = 0
            title = "FlymeFreeformCornerOverlay"
        }
    }

    private fun createOverlayParams(
        type: Int = overlayWindowType,
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            windowAnimations = 0
            title = "FlymeFreeformCornerOverlay"
            // 背景压暗由 Compose 那一层绘制（遮罩透明度可调），不使用窗口模糊：
            // ColorOS 上 FLAG_BLUR_BEHIND 的合成时序不稳定，窗口加入前不会开始计算，
            // 表现为"第一帧清晰、之后才模糊"，并伴随多余的合成闪烁。
        }
    }

    /**
     * 抢流后流被系统 CANCEL 时启动观察窗。
     *
     * 背景：pilfer 是在 MOVE 帧调用，此时 pointer 已分发，系统夺流时会重发 DOWN 并
     * CANCEL 旧流；重启后的通道**可能不再投递任何事件**（实机 02:17:36 那次：CANCEL 之后
     * 3.2 秒零 MOVE，菜单定格）。这时没有任何"是否还在交互"的外部依据，
     * 唯一可靠的判据是「观察窗内有没有 MOVE 给这条流续命」。
     */
    private fun scheduleCancelWatch() {
        if (cancelWatchScheduled) return
        cancelWatchScheduled = true
        val token = ++cancelWatchToken
        handler.postDelayed(
            {
                cancelWatchScheduled = false
                if (token != cancelWatchToken) return@postDelayed
                if (!pilferSessionActive) return@postDelayed
                trace("SYSTEM_GESTURE_CANCELLED_CLOSE")
                pilferSessionActive = false
                cancelActiveGesture()
            },
            CANCEL_WATCH_MS,
        )
    }

    /** 有 MOVE/UP 到来说明这条流还活着，观察窗作废（重复 DOWN 不算，重启后它可能照样是死流）。 */
    private fun cancelWatchOnActivity() {
        cancelWatchToken++
        cancelWatchScheduled = false
    }

    private fun cancelActiveGesture() {
        activeGestureConfig = null
        gestureEngine.cancel()
        overlay?.cancelGesture()
    }

    private fun removeOverlay(immediate: Boolean = false) {
        val view = overlay ?: return
        // 不引入"最短可见时长"：把移除推迟到临界点可以规避闪烁，
        // 但会用延迟换取视觉稳定，代价是每次收起都要等待。
        // 这里立即移除；若仍出现闪烁，应在渲染层解决，不以延迟状态变更规避。
        // 触摸窗口必须和主窗口同生共死：主窗口已经不可触摸，留下触摸窗口就等于
        // 一块看不见的板子盖在菜单区域上。
        detachTouchRegion()
        overlay = null
        if (sidebar.isPending) sidebar.cancel()
        morePanelActive = false
        trace("SYSTEM_OVERLAY_REMOVE")
        val cleanupFailure = view.disposeOverlay()
        try {
            windowManager.removeViewImmediate(view)
        } catch (_: IllegalArgumentException) {
            // 已被系统移除；本地所有权仍需清空。
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_OVERLAY_REMOVE_FAILED", exception)
        }
        cleanupFailure?.let { throwable ->
            logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
        }
    }

    private fun isGestureEnvironmentAllowed(): Boolean {
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return false
        if (isCriticalSystemUiForeground()) return false
        // 排除列表命中时不接管手势：不是「呼出后再关闭」，而是保持系统原样。
        return !lastSettings.isBlacklisted(focusedWindowPackage())
    }

    private fun isDynamicEnvironmentAllowed(): Boolean = environmentState.isGestureAllowed()

    private fun isCriticalSystemUiForeground(): Boolean {
        focusedWindowPackage()?.let { packageName ->
            if (packageName in CRITICAL_PACKAGES) return true
        }
        val packageName = topRunningPackage() ?: return false
        return packageName in CRITICAL_PACKAGES
    }

    /**
     * 顶层（焦点栈）正在运行的 Activity 所属包；取不到返回 null。
     *
     * 走的是 WindowManager 的焦点根任务，不依赖「最后 resumed 的活动」这条缓存：
     * 后者在小窗之间切换时可能滞后一帧，而这里要判的是「此刻屏幕上是谁」。
     */
    private fun topRunningPackage(): String? {
        return try {
            val atms = OemReflection.readField(controller, "mAtms") ?: return null
            val root = OemReflection.readField(atms, "mRootWindowContainer") ?: return null
            val task = OemReflection.requireMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return null
            val activity = OemReflection.requireMethod(task.javaClass, "topRunningActivity", 0).invoke(task) ?: return null
            OemReflection.readField(activity, "packageName") as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun focusedWindowPackage(): String? = try {
        focusedWindow()?.let { OemReflection.requireMethod(it.javaClass, "getOwningPackage", 0).invoke(it) as? String }
    } catch (_: ReflectiveOperationException) { null }
      catch (_: RuntimeException) { null }

    private fun focusedWindow(): Any? = try {
        val root = OemReflection.readField(readWindowManagerService(), "mRoot") ?: return null
        val display = OemReflection.requireMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root) ?: return null
        OemReflection.readField(display, "mCurrentFocus")
    } catch (_: ReflectiveOperationException) { null }
      catch (_: RuntimeException) { null }

    /**
     * 该包此刻是否有可见窗口；有就把它提到前台，不重新启动。
     *
     * 判据是「可见」，不是「任务存在」：收窗只是把任务退到后台，任务对象仍在任务树里，
     * 按任务存在判定会让被收过窗的应用永远打不开。有可见窗口时重新启动，会被系统
     * 原地改写成小窗且没有动画（getReusableOptionsForFreeForm），所以只做前置；
     * 没有可见窗口（应用在后台、或刚被收窗）时返回 false，调用方按正常小窗启动。
     */
    /**
     * 该包此刻在屏幕上的状态，供 FanLaunchPolicy 判断。
     *
     * 三种已有窗口都不该再新建小窗：
     *   - 可见（全屏或小窗）   → 前置即可，等于什么都不做
     *   - 不可见但属于浮动场景（迷你窗 / 贴边挂起） → 前置即可让它恢复成正常小窗
     * 只有"后台的普通全屏任务"才走正常的小窗启动。
     */
    private fun windowFormOf(packageName: String): WindowForm {
        val tasks = tasksOfFocusedDisplay() ?: return WindowForm.None
        val mine = tasks.filter { packageOfTask(it) == packageName }
        if (mine.isEmpty()) return WindowForm.None
        // 迷你窗（zoomState 2）和贴边悬挂（8）必须排在可见判定之前：
        // 迷你窗同样处于可见状态；若不先判定，会按普通可见窗口前置，但不产生形态变化。
        // 迷你窗/浮标的识别不再按 ROM 分流：ColorOS 15/16 上即便这些状态不存在，
        // 判定也不会命中（zoomState 取不到 2/8），放开没有任何副作用。
        if (mine.any { needsRebuild(it) }) {
            return WindowForm.FlexibleHidden
        }
        if (mine.any { isTaskVisible(it) }) return WindowForm.VisibleWindow
        if (mine.any { isFlexibleScenario(it) }) return WindowForm.FlexibleHidden
        return WindowForm.BackgroundTask
    }

    /**
     * 这个窗口是不是"已经不是标准小窗、需要按标准尺寸重建"的形态。
     *
     * 系统的 zoom 状态（FlexibleCaptionView.isMiniZoomState / isMiniOrReadyState 用的同一个字段）：
     *   2 = 迷你窗（可见的迷你态）
     *   8 = ready 态（贴边悬挂那个位置）
     * 这两个都算"缩着的"，直接复用会被系统按原比例还原，所以先退场再重建。
     * 另外不可见的浮动任务（退出中、挂起）也一并在内。
     */
    private fun needsRebuild(task: Any): Boolean {
        val state = flexZoomStateOf(task)
        if (state == MINI_ZOOM_STATE || state == READY_ZOOM_STATE) return true
        return !isTaskVisible(task) && isFlexibleScenario(task)
    }

    /** task.getWrapper().getExtImpl().getFlexibleZoomState() */
    private fun flexZoomStateOf(task: Any): Int =
        try {
            val wrapper = OemReflection.requireMethod(task.javaClass, "getWrapper", 0).invoke(task) ?: return -1
            val ext = OemReflection.requireMethod(wrapper.javaClass, "getExtImpl", 0).invoke(wrapper) ?: return -1
            val method = OemReflection.requireMethod(ext.javaClass, "getFlexibleZoomState", 0)
            method.isAccessible = true
            method.invoke(ext) as? Int ?: -1
        } catch (_: ReflectiveOperationException) {
            -1
        } catch (_: RuntimeException) {
            -1
        }

    /** 系统的判据：该任务是否属于浮动窗口场景（带标题的小窗、迷你窗、贴边挂起都算）。 */
    private fun isFlexibleScenario(task: Any): Boolean =
        try {
            val method = OemReflection.requireMethod(controller.javaClass, "isTaskInFlexibleScenario", 1)
            method?.isAccessible = true
            method?.invoke(controller, task) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /**
     * 让系统把该包处于迷你窗/贴边挂起状态的窗口切回可见小窗。
     *
     * 用系统的 toggleFlexibleWindow(String)：这是侧边栏等外部入口用的那条路径，
     * 内部会把动作投递到小窗动画执行器，自己处理迷你态恢复。
     */
    private fun toggleFlexibleWindow(packageName: String): Boolean =
        try {
            val service = OemReflection.readField(controller, "mFlexibleWindowManagerService")
                ?: return false
            val method = OemReflection.requireMethod(service.javaClass, "toggleFlexibleWindow", 1)
                ?: return false
            method.isAccessible = true
            val result = method.invoke(service, packageName)
            debug("SYSTEM_LAUNCH_TOGGLE_FLEXIBLE_OK_" + packageName)
            result as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** 系统浮标控制器实例；拿不到就当作没有贴边浮标。 */
    private fun floatHandleController(): Any? =
        try {
            val type = classLoader.loadClass(FLOAT_HANDLE_CONTROLLER_CLASS)
            type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** 该包当前贴在屏幕边缘的浮标任务 id；没有浮标时返回 null。 */
    private fun ballTaskIdOf(packageName: String): Int? {
        val handleController = floatHandleController() ?: return null
        val isInFloatingList =
            OemReflection.requireMethod(handleController.javaClass, "isInFloatingList", 1)
        for (task in tasksOfFocusedDisplay().orEmpty()) {
            if (packageOfTask(task) != packageName) continue
            val id = taskIdOf(task)
            val floating =
                try {
                    isInFloatingList.invoke(handleController, id) as? Boolean
                } catch (_: ReflectiveOperationException) {
                    null
                } catch (_: RuntimeException) {
                    null
                }
            if (floating == true) return id
        }
        return null
    }

    /**
     * 复刻"手指点浮标"的那一次调用。
     *
     * 系统的调用点是 `FloatHandleController.startActivityByFloatInfo(FloatHandleInfo, String)`，
     * 传的就是浮标自己的 FloatHandleInfo 和原因串 "onSingleTapUp"。用同一个入口，展开动画、
     * 状态机、窗口恢复全部和手动点击一致，不依赖本模块对 AMS 状态的假设。
     */
    private fun openBallTask(taskId: Int): Boolean =
        try {
            val handleController = floatHandleController() ?: return false
            val info =
                OemReflection.requireMethod(handleController.javaClass, "getFloatHandleInfo", 1)
                    .invoke(handleController, taskId) ?: return false
            OemReflection.requireMethod(handleController.javaClass, "startActivityByFloatInfo", 2)
                .invoke(handleController, info, BALL_OPEN_REASON)
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** 把该包的可见任务提到前台。 */
    private fun bringWindowToFront(packageName: String): Boolean {        val tasks = tasksOfFocusedDisplay() ?: return false
        val task = tasks.firstOrNull { packageOfTask(it) == packageName && isTaskVisible(it) }
            ?: return false
        return try {
            val moveToFront = OemReflection.requireMethod(task.javaClass, "moveToFront", 1)
            val atms = OemReflection.readField(controller, "mAtms")
            val lock = atms?.javaClass?.getMethod("getGlobalLock")?.invoke(atms)
            val action = Runnable { moveToFront.invoke(task, FRONT_REASON) }
            if (lock != null) synchronized(lock) { action.run() } else action.run()
            debug("SYSTEM_LAUNCH_FRONT_EXISTING_" + packageName + "_n" + tasks.size)
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 和"手指点迷你窗/浮标"完全相同的一次调用。
     *
     * FlexibleTaskCaptionView.onSingleTapConfirmed 在全局锁内执行：
     *     mFlexibleTaskController.switchState(mTask, 1, "onSingleTapConfirmed")
     * 这里复刻它：同一个方法、同一个目标状态（1 = 普通浮动窗）。
     */
    private fun switchTaskToDefault(task: Any): Boolean =
        try {
            val method = OemReflection.requireMethod(controller.javaClass, "switchState", 3)
            method.isAccessible = true
            val atms = OemReflection.readField(controller, "mAtms")
            val lock = runCatching {
                atms?.javaClass?.getMethod("getGlobalLock")?.invoke(atms)
            }.getOrNull()
            val action = Runnable {
                runCatching { method.invoke(controller, task, 1, RESTORE_REASON) }
                    .onFailure {
                        logger(Log.WARN, "SYSTEM_RESTORE_FAILED_" + it.javaClass.simpleName, it)
                    }
            }
            if (lock != null) synchronized(lock) { action.run() } else action.run()
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** 该包里处于迷你窗(2)或浮标 ready(8)状态、点一下就恢复为小窗的任务。 */
    private fun restorableWindowsOf(packageName: String): List<Any> {
        val tasks = tasksOfFocusedDisplay() ?: return emptyList()
        return tasks.filter { task ->
            if (packageOfTask(task) != packageName) return@filter false
            val state = flexZoomStateOf(task)
            state == MINI_ZOOM_STATE || state == READY_ZOOM_STATE
        }
    }

    /** 任务编号，仅用于诊断与排序兜底。 */
    private fun taskIdOf(task: Any): Int =
        try {
            val field = OemReflection.readField(task, "mTaskId") ?: return -1
            field as? Int ?: -1
        } catch (_: RuntimeException) {
            -1
        }

    /** 焦点显示内容下的全部任务；取不到返回 null。 */
    private fun tasksOfFocusedDisplay(): List<Any>? =
        try {
            val atms = OemReflection.readField(controller, "mAtms") ?: return null
            val root = OemReflection.readField(atms, "mRootWindowContainer") ?: return null
            val display = OemReflection.requireMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root)
                ?: return null
            val collected = ArrayList<Any>(8)
            collectTasks(display, collected, 0)
            collected
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /**
     * 容器树里收集 Task。实际层级有 8 层左右（ROOT → Display → 若干 DisplayArea →
     * TaskDisplayArea → Task），按 4 层截断时一个任务都爬不到。这里只沿
     * DisplayArea / DisplayContent 下钻，跳过窗口节点。
     */
    private fun collectTasks(container: Any, out: MutableList<Any>, depth: Int) {
        if (depth > MAX_TASK_WALK_DEPTH) return
        val self = OemReflection.requireMethod(container.javaClass, "asTask", 0).invoke(container)
        if (self != null) {
            out.add(self)
            return
        }
        val typeName = container.javaClass.name
        if (!typeName.contains("DisplayArea") &&
            !typeName.contains("DisplayContent") &&
            !typeName.contains("RootWindowContainer")
        ) {
            return
        }
        val count = OemReflection.requireMethod(container.javaClass, "getChildCount", 0).invoke(container) as? Int ?: 0
        if (count <= 0) return
        val childAt = OemReflection.requireMethod(container.javaClass, "getChildAt", 1)
        for (index in 0 until count) {
            val child = childAt.invoke(container, index) ?: continue
            collectTasks(child, out, depth + 1)
        }
    }

    /** 任务此刻是否在屏幕上可见。 */
    private fun isTaskVisible(task: Any): Boolean =
        try {
            OemReflection.requireMethod(task.javaClass, "isVisible", 0).invoke(task) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /**
     * 任务所属包。
     *
     * 依次尝试 baseIntent 的组件、realActivity、顶层/根 Activity 的 packageName：
     * 被收成浮标的任务前两者可能为空，只认 baseIntent 会导致匹配不到、判定整体失效。
     */
    private fun packageOfTask(task: Any): String? {
        runCatching {
            val intent = OemReflection.requireMethod(task.javaClass, "getBaseIntent", 0).invoke(task) as? android.content.Intent
            intent?.component?.packageName
        }.getOrNull()?.let { return it }
        runCatching {
            (OemReflection.requireMethod(task.javaClass, "getRealActivity", 0).invoke(task)
                as? android.content.ComponentName)?.packageName
        }.getOrNull()?.let { return it }
        listOf("topRunningActivity", "getRootActivity", "getTopNonFinishingActivity").forEach { name ->
            runCatching {
                val activity = OemReflection.requireMethod(task.javaClass, name, 0).invoke(task) ?: return@runCatching null
                OemReflection.readField(activity, "packageName") as? String
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun readWindowManagerService(): Any {
        val atms = OemReflection.readField(controller, "mAtms") ?: throw NoSuchFieldException("mAtms")
        return OemReflection.readField(atms, "mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
    }



    private data class QueuedPointerEvent(val event: MotionEvent, val generation: Long, val stream: Long)

    private companion object {
        const val CATALOG_THREAD_NAME = "FlymeFreeform-Catalog"

        /** 恢复原因串：与系统点击用的 "onSingleTapConfirmed" 区分，便于在系统日志里识别。 */
        const val RESTORE_REASON = "flymefreeformFanRestore"

        /** 系统"关掉浮动窗口、任务退回后台"的退场策略类。 */
        const val EXIT_TO_BACK_STRATEGY_CLASS =
            OemClasses.EXIT_FLEXIBLE_TASK_TO_BACK_STRATEGY

        const val FLOAT_HANDLE_CONTROLLER_CLASS =
            OemClasses.FLOAT_HANDLE_CONTROLLER

        /** 与 FloatHandleView.REASON_START_FREEFORM_ONSINGLE_TAPUP 一致。 */
        const val BALL_OPEN_REASON = "onSingleTapUp"

        /** getFlexibleZoomState()：2 = 迷你窗，8 = ready（贴边悬挂）。 */
        const val MINI_ZOOM_STATE = 2
        const val READY_ZOOM_STATE = 8



        const val MONITOR_NAME = "flymefreeform-corner"
        /** 抢流被 CANCEL 后，等这么久没有 MOVE 就按取消收场。 */
        const val CANCEL_WATCH_MS = 150L
        /** 两次指针事件间隔超过该值即判定上一次会话结束，避免状态滞留。 */
        const val SESSION_GAP_MS = 800L

        /**
         * 判定"这是系统上滑不是角区手势"的比值：upward 超过 inward 这么多倍就放弃抢流。
         * 垂直上滑要留给系统回桌面，抢了会让上滑失效。
         */
        /**
         * upward / inward 超过这个比值就按「垂直上滑」处理，这一帧不抢流。
         *
         * 必须与 CornerGestureEngine 的 VERTICAL_UPWARD_RATIO（3f）一致：
         * 抢流侧若更严（例如 2.5）会出现「UI 认为仍是角区手势、抢流已经放弃」的窄带，
         * 落在窄带里的手势全程留给应用。
         */
        const val VERTICAL_ABORT_RATIO = 3f


        /**
         * 抢流的最小位移（像素）。
         *
         * 只用来挡手指按住时的轻微抖动；不能按 touchSlop（8dp≈28px）来算——
         * 页面横向滚动按位移累加，多收到一帧 MOVE 就会先滚一段再被抢回来，
         * 那一段就是用户看到的左右横移。
         */
        const val PILFER_MIN_MOVE_PX = 6f
        /** 读不到系统导航栏高度时的兜底值（dp）。 */
        const val BOTTOM_GESTURE_INSET_DP = 40f




        /** WindowManager.LayoutParams.TOUCHABLE_INSETS_REGION 是 @hide 常量。 */
        const val TOUCHABLE_INSETS_REGION = 3
        const val OVERLAY_PREWARM_LIFETIME_MS = 400L
        // 窗口层级取值：APPLICATION_OVERLAY=111000，INPUT_METHOD=131000。
        // 用 APPLICATION_OVERLAY 时键盘一弹出就盖住扇形，故选输入法对话框层（141000），
        // 只压在键盘之上，仍低于状态栏等更高层系统窗口。被系统拒绝时回落到 APPLICATION_OVERLAY。
        /**
         * 浮层窗口类型：输入法对话框层，压在其他窗口与输入法之上。
         *
         * 呼出时"画面向屏幕内滑一下又滑回去"是窗口自身的进入动画，不是 IME 区域变化，
         * 由 [createOverlayParams] 里的 windowAnimations = 0 关闭，与这个类型无关。
         */
        const val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
        const val FALLBACK_OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L

        /** 浮层最小可见时长：短于该值的「呼出即收起」会造成一次画面闪烁。 */


        /** 最近任务校验的最小间隔。 */
        const val RECENTS_CHECK_INTERVAL_MS = 2_000L

        /** 任务树下钻深度：DisplayContent 下还套着 5 层以上 DisplayArea。 */
        const val MAX_TASK_WALK_DEPTH = 12

        /** 前置已有窗口时写进系统日志的原因串。 */
        const val FRONT_REASON = "FlymeFreeformFan"

        val CRITICAL_PACKAGES =
            setOf(
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            )
    }
}