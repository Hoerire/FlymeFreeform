package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.hoerire.free.log.ModuleLog
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.gesture.AdaptiveCornerGestureConfig
import io.github.hoerire.free.gesture.CornerGestureConfig
import io.github.hoerire.free.gesture.CornerGestureEngine
import io.github.hoerire.free.gesture.CornerTriggerRegion
import io.github.hoerire.free.gesture.GestureAction
import io.github.hoerire.free.gesture.GesturePhase
import io.github.hoerire.free.platform.coloros.ColorOsLauncherInputTarget
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** ColorOS Quickstep 输入入口；命中角落的指针流从 DOWN 起不再进入系统手势链。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val gestureEngine = CornerGestureEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        ModuleLog.warn(code, exception)
    }
    private var environmentStarted = false
    private var activeConfig: CornerGestureConfig? = null
    private var activePointerId = -1
    private var suppressUntilTerminal = false
    private var pilfered = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var inputMonitorField: Field? = null
    private var pilferPointers: ((Any) -> Unit)? = null
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    private val installationStarted = AtomicBoolean(false)
    private val settingsSyncInstalled = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        if (!installationStarted.compareAndSet(false, true)) return
        val apk = File(applicationInfo.sourceDir)
        val cache = File(applicationInfo.dataDir, "code_cache/free/launcher-symbols")
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Free-LauncherDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                ModuleLog.warn("LAUNCHER_DEX_RESOLUTION_TIMEOUT")
            }
        }
        mainHandler.postDelayed(timeout, RESOLUTION_TIMEOUT_MS)
        // 解析前的事件完整交给桌面；发布 Hook 后也只从下一次 DOWN 开始接管。
        executor.execute {
            try {
                val target = ColorOsLauncherInputTarget.resolve(classLoader, apk, cache) { code, exception ->
                    ModuleLog.forward(if (exception == null) Log.INFO else Log.WARN, code, exception)
                }
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        installResolved(target)
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                ModuleLog.warn("LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                ModuleLog.warn("LAUNCHER_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                ModuleLog.warn("LAUNCHER_DEX_RESOLUTION_FAILED", exception)
            } catch (exception: LinkageError) {
                ModuleLog.warn("LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
            } finally {
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) mainHandler.removeCallbacks(timeout)
                }
            }
        }
        // 只有这一项任务；完成后线程退出，不保留后台线程池或重试循环。
        executor.shutdown()
    }

    private fun installResolved(target: ColorOsLauncherInputTarget) {
        try {
            inputMonitorField = target.monitorField
            pilferPointers = { wrapper ->
                try {
                    target.pilferMethod.invoke(wrapper)
                } catch (exception: InvocationTargetException) {
                    throw exception.targetException
                }
            }
            module
                .hook(target.inputMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.launcher.corner_input_owner")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                        ?: return@intercept chain.proceed()
                    val owner = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    if (shouldSuppress(owner, event)) {
                        null
                    } else {
                        chain.proceed()
                    }
                }
            ModuleLog.info("LAUNCHER_CORNER_INPUT_HOOK_INSTALLED")
        } catch (exception: IllegalArgumentException) {
            ModuleLog.warn("LAUNCHER_CORNER_INPUT_HOOK_REJECTED", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
        }
    }

    private fun shouldSuppress(owner: Context, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetStream()
            val config = createEligibleConfig(owner, event) ?: return false
            downRawX = event.rawX
            downRawY = event.rawY
            activePointerId = event.getPointerId(0)
            gestureEngine.down(activePointerId, event.rawX, event.rawY, config)
            if (gestureEngine.phase == GesturePhase.Armed) {
                activeConfig = config
                suppressUntilTerminal = true
                return true
            }
            resetStream()
            return false
        }
        if (!suppressUntilTerminal) return false

        if (!environmentState.isGestureAllowed()) cancelClaim()

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val config = activeConfig
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (config != null && pointerIndex >= 0) {
                    val action =
                        gestureEngine.move(
                            pointerId = activePointerId,
                            pointerCount = event.pointerCount,
                            x = event.getRawX(pointerIndex),
                            y = event.getRawY(pointerIndex),
                            config = config,
                        )
                    // 尽早抢流：只在 Activate 时抢的话，从 DOWN 到越过 touchSlop
                    // 之间的那几个事件仍归桌面，滑得快时会被桌面当成自己的手势。
                    // 位移超过 3dp 就抢，普通点击（几乎不位移）仍旧留给桌面。
                    if (!pilfered) {
                        val slop = PILFER_SLOP_DP * owner.resources.displayMetrics.density
                        val dx = event.getRawX(pointerIndex) - downRawX
                        val dy = event.getRawY(pointerIndex) - downRawY
                        if (dx * dx + dy * dy >= slop * slop) {
                            forcePilferPointers(owner)
                            pilfered = true
                            logClaimed()
                        }
                    }
                    if (!pilfered && action is GestureAction.Activate) {
                        forcePilferPointers(owner)
                        pilfered = true
                        logClaimed()
                    }
                } else {
                    gestureEngine.cancel()
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> gestureEngine.cancel()

            MotionEvent.ACTION_UP -> {
                gestureEngine.up(event.getPointerId(event.actionIndex))
                resetStream()
            }

            MotionEvent.ACTION_CANCEL -> resetStream()

            else -> Unit
        }
        return true
    }

    private fun createEligibleConfig(
        owner: Context,
        event: MotionEvent,
    ): CornerGestureConfig? {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return null
        // 无条件挂上设置通道：只在「模块未启用」时注册广播接收器，会让桌面进程
        // 开着模块时收不到后续设置推送 —— 关掉「启用交互」
        // 它仍然拿着启动时的旧快照继续抢底角输入。方法内部有幂等保护，重复调用无代价。
        installSettingsSync(owner)
        val settings = configuration.snapshot
        if (!settings.enabled) return null
        // 「启用交互」关掉 = 整套底角手势关掉：桌面这边也不再接管上滑与扇形呼出。
        if (!settings.cornerInteractionActive) return null
        if (!configuration.isAvailable) return null
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return null
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return null
        startEnvironment(owner)
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return null

        val metrics = owner.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return null
        return AdaptiveCornerGestureConfig.create(
            displayWidth = width,
            displayHeight = height,
            touchSlop = ViewConfiguration.get(owner).scaledTouchSlop.toFloat(),
            density = metrics.density,
            triggerRangeDp = settings.cornerTriggerRangeDp,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ).takeIf { config ->
            CornerTriggerRegion.detectSide(
                x = event.rawX,
                y = event.rawY,
                displayWidth = config.displayWidth,
                displayHeight = config.displayHeight,
                radius = config.triggerRadius,
                leftEnabled = config.leftEnabled,
                rightEnabled = config.rightEnabled,
            ) != null
        }
    }

    private fun resetStream() {
        gestureEngine.cancel()
        activeConfig = null
        activePointerId = -1
        suppressUntilTerminal = false
        pilfered = false
    }

    private fun startEnvironment(owner: Context) {
        if (environmentStarted) return
        environmentStarted = true
        val start = {
            environmentState.start(owner)
            environmentState.observe {
                if (!environmentState.isGestureAllowed()) cancelClaim()
            }
            configuration.observe {
                mainHandler.post {
                    // 环境不允许、或「启用交互」被关掉，都要立刻放弃已接管的那条流。
                    if (!environmentState.isGestureAllowed() ||
                        !configuration.snapshot.cornerInteractionActive
                    ) {
                        cancelClaim()
                    }
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post { start() }
    }

    private fun cancelClaim() {
        gestureEngine.cancel()
        activeConfig = null
        // 已拦下 DOWN 的旧流继续收尾，不能把缺少 DOWN 的 MOVE/UP 交回桌面。
    }

    private fun forcePilferPointers(owner: Context) {
        try {
            val monitor = inputMonitorField?.get(owner) ?: return
            pilferPointers?.invoke(monitor)
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
        }
    }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        ModuleLog.warn("LAUNCHER_CORNER_INPUT_PILFER_FAILED", exception)
    }

    private fun logClaimed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastClaimLogAt = now
        ModuleLog.info("LAUNCHER_CORNER_INPUT_CLAIMED")
    }

    /** 向 system_server 索取一次权威设置快照，并接收后续推送。 */
    private fun installSettingsSync(context: android.content.Context) {
        if (!settingsSyncInstalled.compareAndSet(false, true)) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                when (intent?.action) {
                    io.github.hoerire.free.config.ModulePreferences.ACTION_PINNED_TARGETS_CHANGED ->
                        configuration.forceReload()
                    io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_PUSHED,
                    io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_SNAPSHOT -> {
                        val map = io.github.hoerire.free.config.SettingsBridge.readMap(intent.extras) ?: return
                        runCatching { io.github.hoerire.free.config.SettingsBridge.decode(map) }
                            .onSuccess { configuration.override(it) }
                    }
                }
            }
        }
        runCatching {
            context.registerReceiver(receiver, android.content.IntentFilter().apply {
                addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_PINNED_TARGETS_CHANGED)
                addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_PUSHED)
                addAction(io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_SNAPSHOT)
            }, android.content.Context.RECEIVER_EXPORTED)
            context.sendBroadcast(android.content.Intent(io.github.hoerire.free.config.ModulePreferences.ACTION_SETTINGS_REQUEST).apply {
                setPackage("android")
                putExtra(io.github.hoerire.free.config.ModulePreferences.EXTRA_REQUESTER_PACKAGE, "com.android.launcher")
            })
            ModuleLog.info("LAUNCHER_SETTINGS_SYNC_REQUESTED")
        }.onFailure { ModuleLog.warn("LAUNCHER_SETTINGS_SYNC_FAILED", it) }
    }

    private companion object {
        const val TAG = "Free"
        const val RESOLUTION_TIMEOUT_MS = 10_000L
        /** 位移到这个距离就抢流，不等引擎的 touchSlop 判定。 */
        const val PILFER_SLOP_DP = 3f
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }

}
