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
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** system_server 中的唯一长期所有者：WMS 指针监听、Overlay、目录缓存与启动适配。 */
/**
 * system_server 侧的手势与浮层协调器。
 *
 * 链路：SystemUI 的角落监听（[io.github.hoerire.flymefreeform.hook.SystemUiCornerInputMonitor]）
 * 与桌面侧监听把触摸点喂进 [gestureEngine]；引擎判定激活后在此创建 Compose 浮层窗口，
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
    private val context = readField(controller, "mContext") as Context
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
    )
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
    private var overlay: CornerRadialOverlayView? = null
    private var morePanelActive = false
    private var lastSettings = ModuleSettingsSnapshot(enabled = false)
    private var activeEnvironmentApproved = false
    private var activeGestureConfig: CornerGestureConfig? = null
    /** 当前这次扇形是从哪个下角拉起的：决定小窗从哪一侧出现。 */
    private var overlayAtLeft = true
    /** 浮层显示时刻与待执行的延迟移除标记：低于最小可见时长时不许拆除。 */
    private var overlayShownAt = 0L
    private var overlayRemovePending = false


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
        }
        logger(Log.INFO, "SYSTEM_GESTURE_COORDINATOR_READY", null)
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
        if (environmentState.isGestureAllowed(refreshKeyguard = true) && (settings.leftCornerEnabled || settings.rightCornerEnabled)) {
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

    private fun registerPointerListener() {
        if (pointerRegistered) return
        val generation = pointerGeneration
        // 首选进程内 gesture monitor：pilfer 之后事件仍留在 system_server，扇形判定不断流。
        val monitor =
            SystemServerGestureMonitor(
                context = context,
                onEvent = { event ->
                    enqueuePointerEvent(event, generation)
                    shouldStartPilfer(event)
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
            val register = findMethod(windowManagerService.javaClass, "registerPointerEventListener", 2)
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
            val unregister = findMethod(windowManagerService.javaClass, "unregisterPointerEventListener", 2)
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
    private var pilferConfig: CornerGestureConfig? = null

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
                    pilferEngine.down(event.getPointerId(0), event.x, event.y, config)
                    false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val config = pilferConfig ?: return false
                val index = event.findPointerIndex(event.getPointerId(0)).coerceAtLeast(0)
                pilferEngine.move(
                    event.getPointerId(index),
                    event.pointerCount,
                    event.getX(index),
                    event.getY(index),
                    config,
                ) is GestureAction.Activate
            }
            else -> {
                pilferEngine.cancel()
                pilferConfig = null
                false
            }
        }

    private fun handlePointerEvent(event: MotionEvent) {
        val settings = lastSettings
        if (!pointerRegistered || !environmentState.isGestureAllowed()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                trace("SYSTEM_GESTURE_SKIPPED_ENV_" + environmentRejectionReason())
            }
            cancelActiveGesture()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 手势生命周期的第一行，无条件记；每根手指只有一行，热路径负担可以忽略。
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
                MotionEvent.ACTION_CANCEL -> gestureEngine.cancel()
                // 多指按下不再取消主手势，只忽略新增的那根手指。
                MotionEvent.ACTION_POINTER_DOWN -> GestureAction.Ignore
                else -> if (gestureEngine.isClaimed) gestureEngine.cancel() else GestureAction.Ignore
            }
        if (action is GestureAction.Ignore) {
            debug("SYSTEM_GESTURE_IGNORE")
        }
        when (action) {
            is GestureAction.Activate -> {
                trace(
                    "SYSTEM_GESTURE_ACTIVATE_" + action.side.name + "_" +
                        action.x.toInt() + "_" + action.y.toInt(),
                )
                showOverlay(
                    side = action.side,
                    x = action.x,
                    y = action.y,
                )
            }
            is GestureAction.Update -> {
                val selected = overlay?.updateGesture(action.x, action.y)
                gestureEngine.setSelection(selected)
            }
            is GestureAction.Commit -> {
                val view = overlay
                if (view != null && !view.isSettled()) {
                    // 扇形还在展开中就抬手：快速上滑容易命中第一格而"秒启动"，判为误触。
                    debug("SYSTEM_GESTURE_COMMIT_TOO_EARLY")
                    view.cancelGesture()
                } else {
                    trace("SYSTEM_GESTURE_COMMIT")
                    view?.finishGesture()
                }
                // 收场不交给 UI 回调：首次呼出时 Compose 还没热，completeRadialExit 的回调会丢，
                // 浮层就挂在屏幕上不消失（实测最长 8.8s，用户看到的就是"卡住"）。
                armCloseGuard(view)
            }
            // 手势被判取消时不要立刻收起：交给抬手（Commit）或系统 ACTION_CANCEL 决定，
            // 否则滑动过程中一旦被判取消，扇形就会自己消失。
            GestureAction.Cancel -> Unit
            GestureAction.Ignore, GestureAction.PassThrough -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeEnvironmentApproved = false
            activeGestureConfig = null
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
        overlayShownAt = android.os.SystemClock.uptimeMillis()
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
        // 永久保持需要浮层自己接收点选，窗口必须可触摸。
        val keepOpen = lastSettings.radialKeepOpen
        val params = createOverlayParams(focusable = keepOpen)
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this, ::debug)
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
                val fallback = createOverlayParams(focusable = keepOpen, type = FALLBACK_OVERLAY_TYPE)
                windowManager.addView(view, fallback)
                overlayWindowType = FALLBACK_OVERLAY_TYPE
                logger(Log.WARN, "SYSTEM_OVERLAY_TYPE_FALLBACK_" + FALLBACK_OVERLAY_TYPE, exception)
            }
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

    /** 详细日志：只有设置里打开「详细日志」才输出。 */
    /**
     * 手势生命周期的关键事件：无条件进模块日志。
     *
     * 这些是排障时唯一有用的几行（按下 / 被环境拒绝 / 激活 / 浮层起落 / 监听注册），
     * 用户根本不知道要去设置里开"详细日志"，所以不挂 VerboseLog。每根手指只有 1~3 行。
     */
    private fun trace(code: String) {
        logger(Log.INFO, code, null)
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
        // 应用/快捷方式在松手当帧就下发启动，浮层自行淡出（与系统侧边栏一致的手感）；
        // 取屏类工具不能与浮层共存：先 removeViewImmediate，再等两帧合成完成才允许执行，
        // 否则截图/录屏会把扇形一起拍进画面（见 launchCommittedEntry 的两帧等待）。
        // 启动任何目标都立刻撤掉浮层：留着它会让深色背景盖住新窗口（表现为黑色遮罩）。
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
        when (val result = launcher.launch(
            android.content.ComponentName(target.packageName, target.className),
            atLeft = overlayAtLeft,
        )) {
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
                        // 遮罩只承担画面；触摸必须穿透到下层系统面板。保持挂起模式下
                        // 浮层为可触摸窗口；若不在此恢复穿透，面板与工具的首次点击会被浮层截获。
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
        val params = createOverlayParams(focusable = true)
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

    /** 面板打开期间把遮罩切成不可触摸，避免吞掉面板上的首次点击。 */
    private fun applyBackdropPassThrough(view: CornerRadialOverlayView) {
        try {
            windowManager.updateViewLayout(view, createOverlayParams(focusable = false))
            debug("SYSTEM_BACKDROP_PASS_THROUGH_OK")
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SYSTEM_BACKDROP_PASS_THROUGH_FAILED", exception)
        }
    }

    override fun onDismissRequested() {
        activeEnvironmentApproved = false
        gestureEngine.cancel()
        removeOverlay()
    }

    override fun onCleanupFailed(throwable: Throwable) {
        logOverlayFailure("SYSTEM_OVERLAY_DISPOSE_FAILED", throwable)
    }

    private fun createOverlayParams(
        focusable: Boolean,
        type: Int = overlayWindowType,
    ): WindowManager.LayoutParams {
        // 非聚焦窗不带 ALT 时位于输入法上方；聚焦面板则需要 ALT 保持相同层级。
        val inputFlags =
            if (focusable) {
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                inputFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "FlymeFreeformCornerOverlay"
        }
    }

    /**
     * 纯 handler 计时兜底：不依赖 Compose 动画回调，超时就把浮层强制撤掉。
     * 只撤"当时那一份"，新浮层已经换上去时不动它。
     */
    private fun armCloseGuard(view: CornerRadialOverlayView?) {
        if (view == null) return
        handler.postDelayed(
            {
                if (overlay === view) {
                    trace("SYSTEM_OVERLAY_CLOSE_GUARD")
                    removeOverlay(immediate = true)
                }
            },
            OVERLAY_CLOSE_GUARD_MS,
        )
    }

    private fun cancelActiveGesture() {
        activeGestureConfig = null
        gestureEngine.cancel()
        overlay?.cancelGesture()
    }

    private fun removeOverlay(immediate: Boolean = false) {
        val view = overlay ?: return
        // 点击贴边浮标/窗外时，角触手势会先呼出再立刻收起，表现为屏幕闪一下。
        // 低于最小可见时长时把移除推迟，且只移除当时那一份浮层。
        // immediate=true 用于"已经启动应用"：此时必须马上撤掉浮层，否则它的深色背景
        // 会盖在新窗口上，看起来像一层黑色遮罩。
        val elapsed = android.os.SystemClock.uptimeMillis() - overlayShownAt
        if (!immediate && elapsed < MIN_OVERLAY_VISIBLE_MS) {
            if (!overlayRemovePending) {
                overlayRemovePending = true
                handler.postDelayed(
                    {
                        overlayRemovePending = false
                        if (overlay === view) removeOverlay()
                    },
                    MIN_OVERLAY_VISIBLE_MS - elapsed,
                )
            }
            return
        }
        overlayRemovePending = false
        trace("SYSTEM_OVERLAY_REMOVE")
        overlay = null
        if (sidebar.isPending) sidebar.cancel()
        morePanelActive = false
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
            val atms = readField(controller, "mAtms") ?: return null
            val root = readField(atms, "mRootWindowContainer") ?: return null
            val task = findMethod(root.javaClass, "getTopDisplayFocusedRootTask", 0).invoke(root) ?: return null
            val activity = findMethod(task.javaClass, "topRunningActivity", 0).invoke(task) ?: return null
            readField(activity, "packageName") as? String
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun focusedWindowPackage(): String? = try {
        focusedWindow()?.let { findMethod(it.javaClass, "getOwningPackage", 0).invoke(it) as? String }
    } catch (_: ReflectiveOperationException) { null }
      catch (_: RuntimeException) { null }

    private fun focusedWindow(): Any? = try {
        val root = readField(readWindowManagerService(), "mRoot") ?: return null
        val display = findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root) ?: return null
        readField(display, "mCurrentFocus")
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
    /** 该包此刻在屏幕上的状态，供 FanLaunchPolicy 判断。 */
    private fun windowFormOf(packageName: String): WindowForm {
        val tasks = tasksOfFocusedDisplay() ?: return WindowForm.None
        val mine = tasks.filter { packageOfTask(it) == packageName }
        if (mine.isEmpty()) return WindowForm.None
        return if (mine.any { isTaskVisible(it) }) WindowForm.VisibleWindow else WindowForm.BackgroundTask
    }

    /** 把该包的可见任务提到前台。 */
    private fun bringWindowToFront(packageName: String): Boolean {
        val tasks = tasksOfFocusedDisplay() ?: return false
        val task = tasks.firstOrNull { packageOfTask(it) == packageName && isTaskVisible(it) }
            ?: return false
        return try {
            val moveToFront = findMethod(task.javaClass, "moveToFront", 1)
            val atms = readField(controller, "mAtms")
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

    /** 焦点显示内容下的全部任务；取不到返回 null。 */
    private fun tasksOfFocusedDisplay(): List<Any>? =
        try {
            val atms = readField(controller, "mAtms") ?: return null
            val root = readField(atms, "mRootWindowContainer") ?: return null
            val display = findMethod(root.javaClass, "getTopFocusedDisplayContent", 0).invoke(root)
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
     * TaskDisplayArea → Task），早先按 4 层截断，一个任务都爬不到。这里只沿
     * DisplayArea / DisplayContent 下钻，跳过窗口节点。
     */
    private fun collectTasks(container: Any, out: MutableList<Any>, depth: Int) {
        if (depth > MAX_TASK_WALK_DEPTH) return
        val self = findMethod(container.javaClass, "asTask", 0).invoke(container)
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
        val count = findMethod(container.javaClass, "getChildCount", 0).invoke(container) as? Int ?: 0
        if (count <= 0) return
        val childAt = findMethod(container.javaClass, "getChildAt", 1)
        for (index in 0 until count) {
            val child = childAt.invoke(container, index) ?: continue
            collectTasks(child, out, depth + 1)
        }
    }

    /** 任务此刻是否在屏幕上可见。 */
    private fun isTaskVisible(task: Any): Boolean =
        try {
            findMethod(task.javaClass, "isVisible", 0).invoke(task) as? Boolean ?: false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /**
     * 任务所属包。
     *
     * 依次尝试 baseIntent 的组件、realActivity、顶层/根 Activity 的 packageName：
     * 被收成浮标的任务实测前两者可能为空，只认 baseIntent 会导致匹配不到、判定整体失效。
     */
    private fun packageOfTask(task: Any): String? {
        runCatching {
            val intent = findMethod(task.javaClass, "getBaseIntent", 0).invoke(task) as? android.content.Intent
            intent?.component?.packageName
        }.getOrNull()?.let { return it }
        runCatching {
            (findMethod(task.javaClass, "getRealActivity", 0).invoke(task)
                as? android.content.ComponentName)?.packageName
        }.getOrNull()?.let { return it }
        listOf("topRunningActivity", "getRootActivity", "getTopNonFinishingActivity").forEach { name ->
            runCatching {
                val activity = findMethod(task.javaClass, name, 0).invoke(task) ?: return@runCatching null
                readField(activity, "packageName") as? String
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun readWindowManagerService(): Any {
        val atms = readField(controller, "mAtms") ?: throw NoSuchFieldException("mAtms")
        return readField(atms, "mWindowManager") ?: throw NoSuchFieldException("mWindowManager")
    }

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): java.lang.reflect.Method {
        var current: Class<*>? = type
        while (current != null) {
            current.declaredMethods.firstOrNull { method ->
                method.name == name && method.parameterCount == parameterCount
            }?.let { method ->
                method.isAccessible = true
                return method
            }
            current = current.superclass
        }
        throw NoSuchMethodException(name)
    }

    private data class QueuedPointerEvent(val event: MotionEvent, val generation: Long, val stream: Long)

    private companion object {
        const val CATALOG_THREAD_NAME = "FlymeFreeform-Catalog"
        const val MONITOR_NAME = "flymefreeform-corner"
        const val OVERLAY_CLOSE_GUARD_MS = 600L
        // 窗口层级实测：APPLICATION_OVERLAY=111000，INPUT_METHOD=131000。
        // 用 APPLICATION_OVERLAY 时键盘一弹出就盖住扇形，故选输入法对话框层（141000），
        // 只压在键盘之上，仍低于状态栏等更高层系统窗口。被系统拒绝时回落到 APPLICATION_OVERLAY。
        const val OVERLAY_WINDOW_TYPE = WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG
        const val FALLBACK_OVERLAY_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L

        /** 浮层最小可见时长：短于此值的"呼出即收起"会把画面闪一下。 */
        const val MIN_OVERLAY_VISIBLE_MS = 200L

        /** 最近任务校验的最小间隔。 */
        const val RECENTS_CHECK_INTERVAL_MS = 2_000L

        /** 任务树下钻深度：实测 DisplayContent 下还套着 5 层以上 DisplayArea。 */
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