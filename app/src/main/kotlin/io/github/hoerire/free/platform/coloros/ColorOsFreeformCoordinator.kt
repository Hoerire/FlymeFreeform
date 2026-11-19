package io.github.hoerire.free.platform.coloros

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
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.SettingsBridge
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.PinnedTargetCodec
import io.github.hoerire.free.gesture.AdaptiveCornerGestureConfig
import io.github.hoerire.free.gesture.CornerGestureConfig
import io.github.hoerire.free.gesture.CornerGestureEngine
import io.github.hoerire.free.gesture.CornerSide
import io.github.hoerire.free.gesture.CornerTriggerRegion
import io.github.hoerire.free.gesture.GestureAction
import io.github.hoerire.free.gesture.GesturePhase
import io.github.hoerire.free.gesture.PointerStreamGate
import io.github.hoerire.free.hook.ModuleEnvironmentState
import io.github.hoerire.free.logic.WindowForm
import io.github.hoerire.free.hook.ProcessConfiguration
import io.github.hoerire.free.window.CornerRadialOverlayView
import io.github.hoerire.free.window.CornerTouchRegionView
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import io.github.hoerire.free.hook.OemReflection
import io.github.hoerire.free.hook.OemClasses

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
 *   （见 [io.github.hoerire.free.platform.coloros.ColorOsEntryCatalogBroker]）。
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
                // 载荷里带的就是整份设置快照：直接用它覆盖内存快照，改动即时生效。
                // 只 forceReload 远程偏好不够——那是模块应用刚写进去的值，system_server 的
                // SharedPreferences 句柄 reload() 未必看得到，表现就是「设置改了要重启才生效」
                // （「屏蔽键盘」这类新开关在重启前完全不生效就是这个原因）。
                val pushed =
                    runCatching { SettingsBridge.readMap(intent.extras) }.getOrNull()
                        ?.let { map -> runCatching { SettingsBridge.decode(map) }.getOrNull() }
                if (pushed != null) {
                    configuration.override(pushed)
                    return
                }
                // 旧格式兜底：载荷里没有整份快照时，重读偏好再叠上本次推送的固定项。
                val encoded = intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS) ?: return
                val targets = PinnedTargetCodec.decodeList(encoded)
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

    /** 最近任务广播的节流时间戳：扇形呼出与面板打开都要给侧边栏送一份最新的。 */
    private var lastRecentsBroadcastAt = 0L

    /** 本次 overlay 实际用的窗口类型；决定它压不压在输入法上面。 */
    private var overlayWindowType = OVERLAY_WINDOW_TYPE
    private val pointerQueueLock = Any()
    private val pointerStreamGate = PointerStreamGate()
    private var pendingMove: QueuedPointerEvent? = null
    private var movePosted = false
    private var lastOverlayFailureLogAt = -OVERLAY_FAILURE_LOG_INTERVAL_MS
    /** 模糊接口取不到只记一次：每次呼出都记会把日志刷满。 */
    private var blurUnavailableLogged = false
    /** 输入法服务实例与解析状态：「屏蔽键盘」判定要用它的窗口可见标志。 */
    private var inputMethodServiceCache: Any? = null
    private var imeAccessCache: ColorOsDisplayContentImeAccess? = null
    private var inputMethodResolved = false

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
                side = io.github.hoerire.free.gesture.CornerSide.Right,
                catalog = catalogSnapshot,
                x = 0f,
                y = 0f,
                hitRangeDp = lastSettings.radialHitRangeDp,
                iconSizeDp = lastSettings.radialIconSizeDp,
                radiusPercent = lastSettings.radialRadiusPercent,
                keepOpen = false,
                selectionRing = lastSettings.radialSelectionRing,
                shimmerEnabled = lastSettings.radialShimmerEnabled,
                shimmer = lastSettings.radialShimmer,
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
        // 「屏蔽键盘」是否真的到了本进程：设置推送/落地的诊断（详细日志）。
        trace("SYSTEM_SETTINGS_APPLIED_pkb" + settings.pauseInKeyboard)
        // 长按静音被关掉（或模块整体停用）时把还静着的应用恢复回去，否则会永久没声音。
        if (!settings.longPressMuteEffective) windowGestures.releaseAll()
        overlay?.setBackdropAlpha(ModulePreferences.backdropShadeAlpha(settings.backdropLevel))
        // monitor 只看"模块是否启用 + 扇形交互是否开启 + 角落是否开启"，不再看环境：
        // 环境判定本来就分两层（isInCornerZone 与 handlePointerEvent 都会拒），
        // 若这里还按环境决定是否注销，改设置时一旦环境判定暂时为 false，
        // monitor 就被拆掉且再也没人重建，表现是"改完触发范围后扇形完全拉不出来"。
        // 「启用交互」关掉时按同一套流程拆掉 monitor：不再吃底角手势，直接把事件交回系统。
        if (settings.enabled && settings.radialInteractionEnabled &&
            (settings.leftCornerEnabled || settings.rightCornerEnabled)
        ) {
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
        // 抢流（system_server InputMonitor）**只在 ColorOS 17+ 启用**：
        // 该路径在 Android 16 / ColorOS 16（API 36）上会导致系统界面崩溃（发版前实测），
        // 而 16 的原厂输入分发不需要这层抢流补偿。旧代一律走 WMS 旁观者，
        // 与 3.1.5 的已发布行为完全一致（代价是旧代的左滑穿透问题依然存在，
        // 那是既有状态、不是本次回归；宁可保留旧问题也不能让 16 用户系统界面崩）。
        //
        // ColorOS 17 现场日志：原 SystemUI 侧 monitor 通道整场收不到事件
        // （0 条 CORNER_TRACK），抢流环节缺失导致触发范围内左滑穿透到下层应用。
        // 改成 system_server 内建 monitor（收到全部事件副本、不消费）后，
        // 在首个有效 MOVE（6px 阈值）调 pilferPointers 抢流，穿透消失。
        if (RomFlavor.isColorOs17OrNewer && startSystemGestureMonitor(generation)) return
        // 退路：WMS 指针监听（只观察，不抢流）。扇形能用，但左滑会穿透到下层应用。
        registerWindowPointerListener(generation)
    }

    /** 建 system_server 侧 monitor；失败返回 false，调用方回退到 WMS 旁观者。 */
    private fun startSystemGestureMonitor(generation: Long): Boolean {
        val monitor = SystemServerGestureMonitor(
            context = context,
            onEvent = { event ->
                // 先喂原始流（窗口手势 / 菜单外点击 / 角区归属），再回答"这一帧抢不抢"。
                handleRawPointer(event, generation)
                shouldStartPilfer(event)
            },
            logger = { priority, code, throwable -> logger(priority, code, throwable) },
        )
        if (!monitor.start(MONITOR_NAME)) return false
        gestureMonitor = monitor
        pointerRegistered = true
        debug("SYSTEM_GESTURE_MONITOR_ACTIVE")
        return true
    }

    /** 原始指针流：浮动窗口手势、菜单外点击、角区归属都在这里判一次。 */
    private fun handleRawPointer(event: MotionEvent, generation: Long): Boolean {
        // 浮动窗口手势（长按静音、双击放大）：与角区手势互不影响，先看它。
        windowGestures.onEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 菜单已经挂出来时，主窗口不可触摸，事件会穿透到下层应用：
            // 这里只是"看一眼"有没有点在菜单外面，用来关闭菜单。
            if (overlay != null) {
                val x = event.x
                val y = event.y
                handler.post { handleOutsideTouch(x, y) }
                cornerStream = false
            } else {
                // 区域归属只在 DOWN 判定一次，避免手指滑出角区后 MOVE 被放给应用。
                cornerStream = isInCornerZone(event)
            }
        }
        if (cornerStream) enqueuePointerEvent(event, generation)
        // 旁观者从不抢流；返回 false 让系统继续按原来的路由分发。
        return false
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
                            if (event != null) handleRawPointer(event, generation)
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "FreePointerListener"
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

    /** 抢流即激活：抢流成功那刻的手势方向与原始落点，重发 DOWN 时消费。 */
    private var pilferActivateSide: CornerSide? = null
    private var pilferActivateX = 0f
    private var pilferActivateY = 0f

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
                    // 另一头：几乎纯水平的内向拖动也不是角区手势。
                    // 视频进度条、横向滑动的列表就在触发范围内，拖动时 upward≈0 而 inward 很大；
                    // 旧逻辑只挡"过于垂直"，于是拖进度条的第一帧（6px）就被抢流，
                    // 表现为进度条拖不动、视频没法快进后退。
                    // 同样只让这一帧、不 cancel：手指后续转成斜向时仍会正常抢流。
                    // 比例必须与 CornerGestureEngine.MIN_UPWARD_RATIO 一致，
                    // 否则会出现「扇形激活但流没被抢」的穿透。
                    if (upward < inward * CornerGestureEngine.MIN_UPWARD_RATIO) return false
                    // 诊断：抢流这一刻手指已经移动了多远。数值大说明应用在抢流前就收到了足够的位移。
                    pilferDistance = moved
                    logFirstPilfer(moved)
                    // 抢流即激活：抢流判定已确认这是角区斜推手势（非上滑方向），
                    // 记下方向与原始落点，等重发 DOWN 到达 UI 引擎时直接进入 Revealing，
                    // 不再等后续 MOVE 攒位移。快速轻扫（抢流后 1~25ms 抬手）的 MOVE 流
                    // 太短，等 MOVE 判定永远来不及，表现为扇形划不出来（实测 25% 失败率）。
                    pilferActivateSide = if (isLeftSide) CornerSide.Left else CornerSide.Right
                    pilferActivateX = pilferDownX
                    pilferActivateY = pilferDownY
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
        // 
        val now = android.os.SystemClock.uptimeMillis()
        if (pilferSessionActive && now - lastPointerEventAt > SESSION_GAP_MS) {
            trace("SYSTEM_GESTURE_SESSION_RESET_STALE")
            pilferSessionActive = false
            gestureEngine.cancel()
        }
        lastPointerEventAt = now
        val settings = lastSettings
        // 「启用交互」关掉后彻底不响应底角手势：正在进行的这次也立刻收掉。
        if (!settings.radialInteractionEnabled) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                trace("SYSTEM_GESTURE_SKIPPED_RADIAL_OFF")
            }
            cancelActiveGesture()
            return
        }
        if (!pointerRegistered || !environmentState.isGestureAllowed()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                trace("SYSTEM_GESTURE_SKIPPED_ENV_" + environmentRejectionReason())
            }
            cancelActiveGesture()
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && pilferSessionActive) {
            // 抢流后系统重发的那次 DOWN：坐标是手指当前位置，不是原始落点。
            // 不能 rebase（把 origin 挪到当前位置）：抢流发生在 6~9px 时，引擎已积累的
            // 位移会被清零，激活阈值实际变成「从重发点起算 14dp」，短滑就划不出扇形。
            // 直接忽略这次重发 DOWN，保留原 origin——MOVE 是屏幕绝对坐标，
            // 相对原落点的位移才是手势的真实位移。也不 reset：会把引擎打回 Armed，
            // 接着 CANCEL 一来整条流就断了，菜单定格。
            // 抢流即激活：抢流判定已经确认这是角区斜推（方向闸门在抢流时已过），
            // 快速轻扫的 MOVE 流在抢流后 1~25ms 就被 UP/CANCEL 掐断，等 MOVE 攒到
            // 14dp 激活阈值永远来不及（实测 25% 手势失败）。这里直接消费抢流时
            // 记下的方向与原始落点，立即弹出扇形——激活与抢流合并成同一事件。
            val pendingSide = pilferActivateSide
            if (pendingSide != null && overlay == null) {
                pilferActivateSide = null
                pilferSessionActive = true
                trace(
                    "SYSTEM_GESTURE_ACTIVATE_INSTANT_" + pendingSide.name + "_" +
                        pilferActivateX.toInt() + "_" + pilferActivateY.toInt(),
                )
                showOverlay(side = pendingSide, x = pilferActivateX, y = pilferActivateY)
            } else {
                pilferActivateSide = null
            }
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 手势生命周期的第一行，无条件记；每根手指只有一行，热路径负担可以忽略。
            pilferLoggedThisGesture = false
            // 把「屏蔽键盘」的两个输入也打出来：设置值 + 当前 IME 可见性，
            // 排查「开关开了但扇形照出」时一眼能看出是哪一环没到。
            // isInputMethodVisible 走 WMS 反射链，先确认详细日志开着再求值，
            // 否则每个 DOWN 都在为一条不会输出的日志付整条反射。
            if (io.github.hoerire.free.log.VerboseLog.enabled) {
                trace(
                    "SYSTEM_GESTURE_DOWN_" + event.x.toInt() + "_" + event.y.toInt() +
                        "_kb" + lastSettings.pauseInKeyboard +
                        "_ime" + isInputMethodVisible(),
                )
            }
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
        syncRecentsToSidebar()
    }

    /**
     * 把最近任务包名推给侧边栏进程（「更多」面板的「最近」分组在那里拼）。
     *
     * ColorOS 不把游戏写进 UsageStats，侧边栏只能看到「用过但不含游戏」的那一份；
     * system_server 这边 `getRecentTasks` 是完整的，用它补上游戏。
     */
    private fun broadcastRecentsIfDue() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRecentsBroadcastAt < RECENTS_BROADCAST_INTERVAL_MS) return
        lastRecentsBroadcastAt = now
        syncRecentsToSidebar()
    }

    private fun syncRecentsToSidebar() {
        val manager = runCatching { context.getSystemService(android.app.ActivityManager::class.java) }.getOrNull() ?: return
        val packages =
            runCatching {
                @Suppress("DEPRECATION")
                manager.getRecentTasks(RECENT_TASKS_LIMIT, android.app.ActivityManager.RECENT_WITH_EXCLUDED)
                    .mapNotNull { task -> task.baseIntent?.component?.packageName ?: task.origActivity?.packageName }
                    .filterNot { pkg -> pkg == ModulePreferences.SIDEBAR_PACKAGE || pkg == context.packageName }
                    .distinct()
            }.getOrDefault(emptyList())
        if (packages.isEmpty()) return
        runCatching {
            context.sendBroadcast(
                Intent(ModulePreferences.ACTION_RECENTS_SYNC)
                    .setPackage(ModulePreferences.SIDEBAR_PACKAGE)
                    .putExtra(ModulePreferences.EXTRA_RECENT_PACKAGES, packages.toTypedArray()),
            )
        }
    }

    private fun showOverlay(
        side: io.github.hoerire.free.gesture.CornerSide,
        x: Float,
        y: Float,
    ) {
        entryLaunchGeneration++
        entryCatalog.cancelLaunch()
        // 扇形先于「更多」面板出现；在这里就把最近任务推给侧边栏，第一次打开面板时才是最新的。
        broadcastRecentsIfDue()
        removeOverlay()
        // 扇形呼出时收掉「更多」面板：面板是应用层之上的 overlay，留着会盖住扇形，
        // 也会盖住随后打开的小窗。这里只做这一件事，面板内部状态机不动。
        if (sidebar.isPanelActive()) {
            sidebar.cancel()
            debug("SYSTEM_MORE_PANEL_CLOSED_BY_OVERLAY")
        }
        morePanelActive = false
        overlayAtLeft = side == io.github.hoerire.free.gesture.CornerSide.Left

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
        val blurRadiusPx = overlayBlurRadiusPx(lastSettings.backdropLevel)
        val params = createOverlayParams(blurRadiusPx = blurRadiusPx)
        var view: CornerRadialOverlayView? = null
        try {
            view = CornerRadialOverlayView(context, this, ::debug)
            // 每次呼出都按当前设置初始化遮罩浓度：设置变化时浮层通常不存在，
            // 只靠 applySettings 里的注入会漏掉"改完再呼出"这条路径。
            view.setBackdropAlpha(ModulePreferences.backdropShadeAlpha(lastSettings.backdropLevel))
            view.begin(
                side = side,
                catalog = catalogSnapshot,
                x = x,
                y = y,
                hitRangeDp = hitRangeDp,
                iconSizeDp = lastSettings.radialIconSizeDp,
                radiusPercent = lastSettings.radialRadiusPercent,
                keepOpen = keepOpen,
                selectionRing = lastSettings.radialSelectionRing,
                shimmerEnabled = lastSettings.radialShimmerEnabled,
                shimmer = lastSettings.radialShimmer,
            )
            overlay = view
            // 扇形展开才读侧边栏目录：Provider 按需启动，早读必然失败。
            if (lastSettings.pinnedTargets.any { it is PinnedTarget.Sidebar }) entryCatalog.refresh()
            try {
                windowManager.addView(view, params)
                overlayWindowType = OVERLAY_WINDOW_TYPE
                trace("SYSTEM_OVERLAY_SHOWN_" + overlayWindowType)
                trace("SYSTEM_OVERLAY_TYPE_" + OVERLAY_WINDOW_TYPE)
            } catch (exception: RuntimeException) {
                // 输入法层的窗口类型被系统拒绝时，退回普通悬浮窗，至少保证扇形可用。
                val fallback =
                    createOverlayParams(type = FALLBACK_OVERLAY_TYPE, blurRadiusPx = blurRadiusPx)
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
            // 扇形展开才读侧边栏目录：Provider 按需启动，早读必然失败。
            if (lastSettings.pinnedTargets.any { it is PinnedTarget.Sidebar }) entryCatalog.refresh()
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
            lastSettings.pauseInKeyboard && isInputMethodVisible() -> "KEYBOARD_SHOWN"
            else -> environmentState.gestureBlockReason() ?: "UNKNOWN"
        }
    }

    /** 手势生命周期事件：受「详细日志」控制。 */
    private fun trace(code: String) {
        // 同样受「详细日志」控制：关掉之后连这些状态点也不输出，
        // 否则一次手势仍会刷出十几行（DOWN/REJECTED/ACTIVATE/SHOWN/COMMIT…）。
        if (io.github.hoerire.free.log.VerboseLog.enabled) {
            logger(Log.DEBUG, code, null)
        }
    }

    /** 高频/细节日志，仍受设置里的详细日志开关控制（MOVE、启动细节、通知等）。 */
    private fun debug(code: String) {
        if (io.github.hoerire.free.log.VerboseLog.enabled) logger(Log.DEBUG, code, null)
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
        // 取屏类工具要求浮层彻底消失后再执行；应用/快捷方式让浮层自己播一段
        // "缩回底角"的动画，启动不等它（下面的 handler.post 立即执行），
        // 动画播完由 onDismissRequested 回调移除窗口。
        val requiresHiddenOverlay =
            (entry.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.Tool
        if (requiresHiddenOverlay) {
            removeOverlay(immediate = true)
        } else {
            overlay?.retractAnimated(quick = true) ?: removeOverlay(immediate = true)
        }
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
        // 一次窗口树快照：就地切换、还原迷你/浮标、最终启动判定都复用这份，
        // 不再每点一次连做 4 遍 WindowContainer 全树遍历。
        val startedAt = SystemClock.uptimeMillis()
        val tasks = taskSnapshot()
        // 「前台全屏 → 小窗」就地切换：走系统的 switchFocusedTaskToZoom，不启动 Activity、
        // 不建新任务、不出起始窗口，只是同一个窗口改形态。重新拉起要走 ActivityStarter
        // 加 OPEN 转场（实测到转场结束约 700 ms），差别就在这里。
        if (switchFocusedFullscreenToZoom(tasks, target.packageName)) {
            debug("SYSTEM_SWITCH_TO_ZOOM_" + target.packageName + "_" + (SystemClock.uptimeMillis() - startedAt))
            return
        }
        val form = windowFormOf(tasks, target.packageName)
        // 目标已经是一个可见的小窗：扇形再点它 → 切成迷你窗
        // （等于在小窗底部横条上快速上滑）。以前这里什么都不做，表现为"点了没反应"。
        if (form == WindowForm.VisibleWindow) {
            val zoomTask = visibleOrdinaryZoomTaskOf(tasks, target.packageName)
            if (zoomTask != null && switchTaskToMini(zoomTask)) {
                debug("SYSTEM_ZOOM_TO_MINI_" + target.packageName)
                return
            }
        }
        debug("SYSTEM_LAUNCH_APP_" + target.packageName + "_LEFT_" + overlayAtLeft)
        // 迷你/贴边的任务是"缩小的"浮动窗口，直接重启会被系统复用、把原比例带回来。
        // 那种情况强制新建任务（MULTIPLE_TASK），得到标准尺寸；旧的那个交由系统按上限收掉。
        // 诊断（详细日志）：把这次决策看到的真实状态打出来——每个该包的任务
        // 编号、是否可见、是否浮动场景、系统 zoomState，以及最终判定。
        runCatching {
            val candidates = tasks?.filter { packageOfTask(it) == target.packageName } ?: emptyList()
            val detail = candidates.joinToString(" | ") { task ->
                "id" + taskIdOf(task) +
                    "_vis" + isTaskVisible(task) +
                    "_flex" + isFlexibleScenario(task) +
                    "_zoom" + flexZoomStateOf(task)
            }
            debug("SYSTEM_FORM_" + target.packageName + "_n" + candidates.size + "_" + detail)
        }
        val restorable = restorableWindowsOf(tasks, target.packageName)
        debug(
            "SYSTEM_DECISION_" + target.packageName + "_form" + form +
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
        val ballTaskId = ballTaskIdOf(tasks, target.packageName)
        if (ballTaskId != null && openBallTask(ballTaskId)) {
            debug("SYSTEM_LAUNCH_BALL_OPENED_" + target.packageName + "_" + ballTaskId)
            return
        }
        // 后台任务不走 startActivityFromRecents：这台 ROM 上它只把任务提到前台，
        // 完全忽略 extra_window_mode 那套小窗参数（实测：只会变成全屏）。
        // 后台任务仍走 launcher.launch → startZoomWindow。
        val launchResult = launcher.launch(
            android.content.ComponentName(target.packageName, target.className),
            atLeft = overlayAtLeft,
            knownForm = form,
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
            // 不带模糊重建参数：「更多」面板自己负责背景，这一层退成完全透明。
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
     * 现在整屏都由 [CornerTouchRegionView] 接住（底部手势条除外），这条路径只兜底
     * 手势条那一条边：monitor 看到按下就先把挂起的扇形收起来。
     * 从底部上滑是"离开当前界面"，挂起的扇形必须一起收起，否则它会留在窗口栈里、
     * 回到桌面后重新显示出来。
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
     * 挂上"接住触摸"的窗口：覆盖整屏，底部让出手势条。
     *
     * 扇形激活之后屏幕上的触摸一律先由模块接住：落在扇形上选格，落在扇形之外则是
     * 「点空白关闭」。之前这块只盖下半屏，点上半屏会直接落到下面的应用（点进按钮、
     * 拖动列表），表现为"划着划着划到下层应用"。
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
        // 窗口贴顶摆放，屏幕坐标偏移为 0。
        view.screenOffsetY = 0
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
     * 内建「更多」面板的窗口参数。
     *
     * 与扇形主窗口的唯一区别是**可触摸**。回退到内建面板时系统侧边栏未接收输入事件，
     * 如果窗口还带 FLAG_NOT_TOUCHABLE、触摸层又被摘掉，面板就收不到任何点击，
     * 下一次按下会被 monitor 当成"点空白"直接把它关掉
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
            title = "FreeCornerOverlay"
        }
    }

    /**
     * 扇形主窗口的参数：只负责「画」，一律不接收触摸。
     *
     * 触摸由另建的 [CornerTouchRegionView] 接住——它覆盖整屏（底部手势条除外），
     * 所以扇形打开期间点击/滑动不会落到下层应用；扇形之外按下即关闭。
     * 主窗口自身带 FLAG_NOT_TOUCHABLE，因此不会把系统的窗口内触摸判据全部吃掉。
     */
    private fun createOverlayParams(
        type: Int = overlayWindowType,
        blurRadiusPx: Int = 0,
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
            title = "FreeCornerOverlay"
            // 压暗由 Compose 那一层绘制（遮罩浓度可调），窗口本身不参与调暗。
            // 模糊是另一档开关：只有开启「系统模糊」才把半径交给系统合成，
            // 关闭时窗口表面完全透明，行为与引入该开关之前一致。
            if (blurRadiusPx > 0) applyBlurBehind(blurRadiusPx)
        }
    }

    /**
     * 给浮层窗口挂上系统的背景模糊。
     *
     * 走窗口自带的那一档（[FLAG_BLUR_BEHIND] + LayoutParams#setBlurBehindRadius）：
     * WindowManagerService 把半径写进窗口表面的 SurfaceControl，由 SurfaceFlinger 合成时
     * 模糊窗口下方的内容，和系统面板同一套实现，模块不自己画近似磨砂。
     * 两个入口都是隐藏 API，取不到就保持透明窗口，不影响扇形本身。
     */
    private fun WindowManager.LayoutParams.applyBlurBehind(radiusPx: Int) {
        val setter =
            OemReflection.findMethod(
                javaClass,
                "setBlurBehindRadius",
                java.lang.Integer.TYPE,
            )
        if (setter == null) {
            if (!blurUnavailableLogged) {
                blurUnavailableLogged = true
                logger(Log.WARN, "SYSTEM_OVERLAY_BLUR_UNAVAILABLE", null)
            }
            return
        }
        val applied = runCatching { setter.invoke(this, radiusPx) }.isSuccess
        if (applied) {
            flags = flags or FLAG_BLUR_BEHIND
        }
    }

    /** 模糊半径按 dp 折算：与系统面板的模糊在同一量级，不同密度下观感一致。 */
    private fun overlayBlurRadiusPx(level: Int): Int =
        (ModulePreferences.backdropBlurDp(level) * context.resources.displayMetrics.density).toInt()

    /**
     * 抢流后流被系统 CANCEL 时启动观察窗。
     *
     * 背景：pilfer 是在 MOVE 帧调用，此时 pointer 已分发，系统夺流时会重发 DOWN 并
     * CANCEL 旧流；重启后的通道**可能不再投递任何事件**（CANCEL 之后连续 3.2 秒零 MOVE、
     * 菜单定格）。这时没有任何"是否还在交互"的外部依据，
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
        // 屏蔽键盘：输入法弹出时暂停扇形，否则打字时从底角起手会误触。
        if (lastSettings.pauseInKeyboard && isInputMethodVisible()) return false
        // 排除列表命中时不接管手势：不是「呼出后再关闭」，而是保持系统原样。
        return !lastSettings.isBlacklisted(focusedWindowPackage())
    }

    private fun isDynamicEnvironmentAllowed(): Boolean =
        environmentState.isGestureAllowed() &&
            !(lastSettings.pauseInKeyboard && isInputMethodVisible())

    /** 输入法窗口此刻是否可见；状态拿不到时按「不可见」处理，避免误拦手势。 */
    private fun isInputMethodVisible(): Boolean {
        // 主判据：WMS 里那个 IME 窗口。窗外关闭（OutsideTapClose）用的就是这条链路，
        // 已验证可用；早期版本只读 IMMS 的 mImeWindowVis，取到 0 就当作没弹键盘，
        // 结果「屏蔽键盘」永远不生效。
        inputMethodWindowState()?.let { window ->
            (OemReflection.callNoArg(window, "isVisible") as? Boolean)?.let { return it }
        }
        // 兜底：IMMS 的窗口可见标志 / 可见高度。
        val service = inputMethodService() ?: return false
        (OemReflection.readField(service, "mImeWindowVis") as? Int)?.let { return (it and IME_VISIBLE_BIT) != 0 }
        val height =
            OemReflection.call(service, "getInputMethodWindowVisibleHeight") as? Int
                // 带 userId 的重载：系统进程按用户 0（主用户）查询；多用户不是本模块的目标场景。
                ?: OemReflection.call(service, "getInputMethodWindowVisibleHeight", 0) as? Int
                ?: return false
        return height > 0
    }

    /** 顶层显示内容里的 IME 窗口（WindowState）；取不到返回 null。 */
    private fun inputMethodWindowState(): Any? =
        try {
            val wms = readWindowManagerService()
            val root = OemReflection.readField(wms, "mRoot") ?: return null
            val display =
                OemReflection.requireMethod(root.javaClass, "getTopFocusedDisplayContent", 0)
                    .invoke(root) ?: return null
            imeAccess().get(display)
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    /** DisplayContent → IME WindowState 的访问器（与窗外关闭共用同一份实现）。 */
    private fun imeAccess(): ColorOsDisplayContentImeAccess {
        imeAccessCache?.let { return it }
        val access =
            ColorOsDisplayContentImeAccess(
                classLoader.loadClass(OemClasses.DISPLAY_CONTENT),
                classLoader.loadClass(OemClasses.WINDOW_STATE),
            )
        imeAccessCache = access
        return access
    }

    /**
     * 系统进程里的输入法服务实例（不是 Binder 代理）：从 LocalServices 取真实对象，
     * 这样能直接读它的窗口可见状态。
     */
    private fun inputMethodService(): Any? {
        if (inputMethodResolved) return inputMethodServiceCache
        inputMethodResolved = true
        inputMethodServiceCache =
            runCatching {
                val type = classLoader.loadClass(INPUT_METHOD_SERVICE_CLASS)
                val localServices = classLoader.loadClass(LOCAL_SERVICES_CLASS)
                localServices.getMethod("getService", Class::class.java).invoke(null, type)
            }.getOrNull()
        return inputMethodServiceCache
    }

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

    /** 窗口树快照：一次手势只取一份，后续所有判定复用，避免每点一次遍历 4 遍。 */
    private fun taskSnapshot(): List<Any>? = tasksOfFocusedDisplay()

    private fun windowFormOf(packageName: String): WindowForm = windowFormOf(taskSnapshot(), packageName)

    private fun windowFormOf(tasks: List<Any>?, packageName: String): WindowForm {
        if (tasks == null) return WindowForm.None
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

    /** task.getWrapper().getExtImpl().getFlexibleZoomState()；判据与窗口手势共用一份。 */
    private fun flexZoomStateOf(task: Any): Int = ColorOsFlexibleWindowState.zoomStateOf(task)

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

    /** 系统小窗服务实例；模块跑在 system_server 内，直接读控制器的现成字段。 */
    private fun flexibleWindowService(): Any? =
        OemReflection.readField(controller, "mFlexibleWindowManagerService")

    /**
     * 「前台全屏 → 小窗」就地切换：`FlexibleWindowManagerService#switchFocusedTaskToZoom(Task)`。
     *
     * 与 [ColorOsFreeformLauncher] 走的 `OplusZoomWindowManager.startZoomWindow` 区别在于：
     * 后者是一次**启动**（ActivityStarter、新建任务、起始窗口、OPEN 转场，实测到转场结束约
     * 700 ms）；前者只是把已经在前台的全屏任务改成小窗形态，同一个窗口和 Surface 直接变形，
     * 所以点下去几乎立刻可见。
     *
     * 只在「目标包的全屏任务此刻就是焦点任务」时使用 —— 该方法的语义就是切换*聚焦*任务；
     * 可见小窗、迷你窗、贴边浮标、后台任务一律交回原有分支，保证行为可预期。
     * 方法不存在（ColorOS 15/16）或任一条件不满足时返回 false，调用方照常走原路径。
     */
    private fun switchFocusedFullscreenToZoom(tasks: List<Any>?, packageName: String): Boolean {
        val service = flexibleWindowService() ?: return false
        // 系统正在播同一次切换动画时不插队：插了会被它自己判成状态冲突而丢弃。
        if (isAnimatingSwitchToZoom(service)) return false
        val task = findFocusedFullscreenTaskOf(tasks, packageName) ?: return false
        return try {
            val method = OemReflection.requireMethod(service.javaClass, "switchFocusedTaskToZoom", 1)
            method.isAccessible = true
            method.invoke(service, task)
            true
        } catch (_: NoSuchMethodException) {
            false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /**
     * 该包此刻「焦点 + 可见 + 铺满整屏」的任务；不满足任一条件返回 null。
     *
     * 全屏判据分两种：
     *   - `getFlexibleZoomState() == 0`：从没进过小窗的普通全屏任务；
     *   - `== 1` 但 bounds 铺满屏幕：进过小窗、之后最大化或重新以全屏拉起的任务 ——
     *     ROM 不会把这个字段清回 0，只按字段判会让这类应用永远切不动。
     * 迷你窗(2)、贴边(8) 与其它取值一律不接，交回原有分支。
     */
    private fun findFocusedFullscreenTaskOf(tasks: List<Any>?, packageName: String): Any? {
        if (tasks == null) return null
        return tasks.firstOrNull { task ->
            if (packageOfTask(task) != packageName) return@firstOrNull false
            if (!isTaskFocused(task) || !isTaskVisible(task)) return@firstOrNull false
            when (flexZoomStateOf(task)) {
                ColorOsFlexibleWindowState.FULLSCREEN -> !isFlexibleScenario(task)
                ColorOsFlexibleWindowState.ORDINARY -> fillsScreen(task)
                else -> false
            }
        }
    }

    /** 该任务的 bounds 是否铺满整屏（容许状态栏/导航条带来的少量差异）。 */
    private fun fillsScreen(task: Any): Boolean {
        val bounds = OemReflection.callNoArg(task, "getBounds") as? android.graphics.Rect ?: return false
        val screen = displaySizePx() ?: return false
        return bounds.width() >= screen.width() * FULLSCREEN_SIZE_RATIO &&
            bounds.height() >= screen.height() * FULLSCREEN_SIZE_RATIO
    }

    /** 默认显示屏的物理尺寸；取不到返回 null。 */
    private fun displaySizePx(): android.graphics.Rect? =
        try {
            val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
            val mode = display.mode ?: return null
            android.graphics.Rect(0, 0, mode.physicalWidth, mode.physicalHeight)
        } catch (_: RuntimeException) {
            null
        }

    /** 系统正在播「切到小窗」的动画；方法缺失按「没有」处理。 */
    private fun isAnimatingSwitchToZoom(service: Any): Boolean =
        try {
            val method = OemReflection.requireMethod(service.javaClass, "isAnimatingSwitchToZoom", 0)
            method.isAccessible = true
            method.invoke(service) as? Boolean ?: false
        } catch (_: NoSuchMethodException) {
            false
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** 该任务此刻是不是焦点任务（Task#isFocused 是包内方法，要放开可访问性）。 */
    private fun isTaskFocused(task: Any): Boolean =
        try {
            val method = OemReflection.requireMethod(task.javaClass, "isFocused", 0)
            method.isAccessible = true
            method.invoke(task) as? Boolean ?: false
        } catch (_: NoSuchMethodException) {
            false
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

    /**
     * 该任务是否已经收成贴边浮标（在浮标列表里）。浮标控制器不可用时返回 null。
     *
     * 浮标态下窗口不可见、也不在浮动窗形态里，但它是同一个窗口，长按静音要继续保持。
     */
    private fun isFloatingTask(taskId: Int): Boolean? {
        val handleController = floatHandleController() ?: return null
        val method = OemReflection.requireMethod(handleController.javaClass, "isInFloatingList", 1)
        return try {
            method.invoke(handleController, taskId) as? Boolean
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 该包当前贴在屏幕边缘的浮标任务 id；没有浮标时返回 null。 */
    private fun ballTaskIdOf(tasks: List<Any>?, packageName: String): Int? {
        for (task in tasks.orEmpty()) {
            if (packageOfTask(task) != packageName) continue
            val id = taskIdOf(task)
            if (isFloatingTask(id) == true) return id
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
        switchTaskState(task, DEFAULT_SWITCH_STATE, RESTORE_REASON)

    /**
     * 把已经在屏幕上的小窗切成迷你窗。
     *
     * 用户看到的那条路径是"在小窗底部的横条上快速上滑"，内部同样是
     * `switchState(task, 状态, 原因)`；这里传 2（小窗的迷你态，与
     * `getFlexibleZoomState()` 的 MINI=2 一致）。
     */
    private fun switchTaskToMini(task: Any): Boolean =
        switchTaskState(task, MINI_SWITCH_STATE, MINI_REASON)

    /**
     * 在系统全局锁内调用 `FlexibleTaskController#switchState(task, state, reason)`。
     *
     * 系统自己是从 `FlexibleTaskCaptionView` 的点击回调里、持锁状态下调这个方法；
     * 不持锁直接调会撞上窗口状态机，表现为切换被丢弃或状态错乱。
     */
    private fun switchTaskState(task: Any, state: Int, reason: String): Boolean =
        try {
            val method = OemReflection.requireMethod(controller.javaClass, "switchState", 3)
            method.isAccessible = true
            val atms = OemReflection.readField(controller, "mAtms")
            val lock = runCatching {
                atms?.javaClass?.getMethod("getGlobalLock")?.invoke(atms)
            }.getOrNull()
            val action = Runnable {
                runCatching { method.invoke(controller, task, state, reason) }
                    .onFailure {
                        logger(Log.WARN, "SYSTEM_SWITCH_STATE_FAILED_" + it.javaClass.simpleName, it)
                    }
            }
            if (lock != null) synchronized(lock) { action.run() } else action.run()
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    /** 该包里处于"可见普通小窗"形态的任务；不是小窗时返回 null。 */
    private fun visibleOrdinaryZoomTaskOf(tasks: List<Any>?, packageName: String): Any? {
        if (tasks == null) return null
        return tasks.firstOrNull { task ->
            packageOfTask(task) == packageName &&
                isTaskVisible(task) &&
                flexZoomStateOf(task) == ColorOsFlexibleWindowState.ORDINARY
        }
    }

    /** 该包里处于迷你窗(2)或浮标 ready(8)状态、点一下就恢复为小窗的任务。 */
    private fun restorableWindowsOf(tasks: List<Any>?, packageName: String): List<Any> {
        if (tasks == null) return emptyList()
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
        const val CATALOG_THREAD_NAME = "Free-Catalog"

        /** 恢复原因串：与系统点击用的 "onSingleTapConfirmed" 区分，便于在系统日志里识别。 */
        const val RESTORE_REASON = "freeFanRestore"

        /** switchState 的目标状态：1 = 普通小窗，2 = 迷你窗。 */
        const val DEFAULT_SWITCH_STATE = 1
        const val MINI_SWITCH_STATE = 2

        /** 扇形点已开的小窗 → 切成迷你窗时用的原因串。 */
        const val MINI_REASON = "freeFanMini"

        /** 系统"关掉浮动窗口、任务退回后台"的退场策略类。 */
        const val EXIT_TO_BACK_STRATEGY_CLASS =
            OemClasses.EXIT_FLEXIBLE_TASK_TO_BACK_STRATEGY

        const val FLOAT_HANDLE_CONTROLLER_CLASS =
            OemClasses.FLOAT_HANDLE_CONTROLLER

        /** 系统输入法服务与 LocalServices：取真实实例读 IME 窗口可见状态。 */
        const val INPUT_METHOD_SERVICE_CLASS =
            "com.android.server.inputmethod.InputMethodManagerService"
        const val LOCAL_SERVICES_CLASS = "com.android.server.LocalServices"

        /** InputMethodManager.IME_VISIBLE：mImeWindowVis 的最低位。 */
        const val IME_VISIBLE_BIT = 1

        /** 与 FloatHandleView.REASON_START_FREEFORM_ONSINGLE_TAPUP 一致。 */
        const val BALL_OPEN_REASON = "onSingleTapUp"

        /** getFlexibleZoomState()：2 = 迷你窗，8 = ready（贴边悬挂）。 */
        const val MINI_ZOOM_STATE = ColorOsFlexibleWindowState.MINI
        const val READY_ZOOM_STATE = ColorOsFlexibleWindowState.READY

        /**
         * 判定任务「铺满整屏」的阈值。
         *
         * 小窗的最大尺寸约屏宽 100%、屏高 80%，
         * 与真正的全屏（1272×2800）在高度上差得很明显，90% 足够区分两侧。
         */
        const val FULLSCREEN_SIZE_RATIO = 0.9f



        const val MONITOR_NAME = "free-corner"
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

        /** `WindowManager.LayoutParams.FLAG_BLUR_BEHIND`；隐藏常量，值为 0x4。 */
        const val FLAG_BLUR_BEHIND = 0x00000004
        const val OVERLAY_FAILURE_LOG_INTERVAL_MS = 10_000L

        /** 浮层最小可见时长：短于该值的「呼出即收起」会造成一次画面闪烁。 */


        /** 最近任务校验的最小间隔。 */
        const val RECENTS_CHECK_INTERVAL_MS = 2_000L

        /** 推给侧边栏的最近任务条数。 */
        const val RECENT_TASKS_LIMIT = 16

        /** 最近任务广播的最小间隔（毫秒）。 */
        const val RECENTS_BROADCAST_INTERVAL_MS = 1_500L

        /** 任务树下钻深度：DisplayContent 下还套着 5 层以上 DisplayArea。 */
        const val MAX_TASK_WALK_DEPTH = 12

        /** 前置已有窗口时写进系统日志的原因串。 */
        const val FRONT_REASON = "FreeFan"

        val CRITICAL_PACKAGES =
            setOf(
                "com.android.systemui",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
            )
    }
}