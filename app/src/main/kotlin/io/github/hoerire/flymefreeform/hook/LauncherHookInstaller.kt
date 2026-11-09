package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.hoerire.flymefreeform.gesture.CornerGestureConfig
import io.github.hoerire.flymefreeform.gesture.CornerGestureEngine
import io.github.hoerire.flymefreeform.gesture.CornerTriggerRegion
import io.github.hoerire.flymefreeform.gesture.GestureAction
import io.github.hoerire.flymefreeform.gesture.GesturePhase
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsLauncherInputTarget
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
        module.log(Log.WARN, TAG, code, exception)
    }
    private var environmentStarted = false
    private var activeConfig: CornerGestureConfig? = null
    private var activePointerId = -1
    private var suppressUntilTerminal = false
    private var pilfered = false
    private var inputMonitorField: Field? = null
    private var pilferPointers: ((Any) -> Unit)? = null
    private var lastClaimLogAt = -CLAIM_LOG_INTERVAL_MS
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    /** 每个进程只在首次拦下指针流时写一条常开日志，之后的命中交给「详细日志」。 */
    private var claimLogged = false

    private val installationStarted = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        if (!installationStarted.compareAndSet(false, true)) return
        val apk = File(applicationInfo.sourceDir)
        val cache = File(applicationInfo.dataDir, "code_cache/flymefreeform/launcher-symbols")
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlymeFreeform-LauncherDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_RESOLUTION_TIMEOUT")
            }
        }
        mainHandler.postDelayed(timeout, RESOLUTION_TIMEOUT_MS)
        // 解析前的事件完整交给桌面；发布 Hook 后也只从下一次 DOWN 开始接管。
        executor.execute {
            try {
                val target = ColorOsLauncherInputTarget.resolve(classLoader, apk, cache) { code, exception ->
                    module.log(if (exception == null) Log.INFO else Log.WARN, TAG, code, exception)
                }
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        installResolved(target)
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_RESOLUTION_FAILED", exception)
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
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
                // 不设置 PRIORITY_HIGHEST：多个模块同时启用时，抢最高优先级会压过别人的拦截链。
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.launcher.corner_input_owner")
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
            // 独立安装：桌面状态上报完全独立于手势 hook，拿实例靠 Launcher.onResume。
            runCatching {
                val launcherClass = target.inputMethod.declaringClass.classLoader
                    .loadClass("com.android.launcher3.Launcher")
                module.hook(launcherClass.getMethod("onResume"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.launcher.page_reporter")
                    .intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? Context)?.let { instance ->
                            installPageReporter(instance)
                            reportPageState(instance)
                        }
                        result
                    }
                module.log(Log.INFO, TAG, "LAUNCHER_PAGE_RESUME_HOOK_INSTALLED", null)
            }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_PAGE_RESUME_HOOK_FAILED", it) }
            // 上滑手势一开始就置位：等状态切到 OVERVIEW 会晚 600 ms，
            // 这段时间里斜滑仍能呼出扇形，属于误触。
            runCatching {
                val handlerClass = target.inputMethod.declaringClass.classLoader
                    .loadClass("com.android.quickstep.AbsSwipeUpHandler")
                val started = handlerClass.declaredMethods.firstOrNull {
                    it.name == "onGestureStarted" && it.parameterTypes.size == 2
                } ?: return@runCatching
                module.hook(started)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.launcher.swipe_start")
                    .intercept { chain ->
                        val result = chain.proceed()
                        // 证据：命中时是否拿得到 Context（launcherRef 在应用上滑时可能为空）。
                        val ctx = launcherRef ?: appContext()
                        module.log(
                            Log.DEBUG,
                            TAG,
                            "LAUNCHER_SWIPE_STARTED_ctx" + (ctx != null),
                            null,
                        )
                        ctx?.let { publishBlocked(it, true) }
                        result
                    }
                runCatching {
                    val end = handlerClass.declaredMethods.firstOrNull {
                        it.name == "endRunningWindowAnim" && it.parameterTypes.size == 1
                    } ?: return@runCatching
                    module.hook(end)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("flymefreeform.launcher.swipe_end")
                        .intercept { chain ->
                            val result = chain.proceed()
                            launcherRef?.let { reportPageState(it) }
                            result
                        }
                }
                module.log(Log.INFO, TAG, "LAUNCHER_SWIPE_HOOK_INSTALLED", null)
            }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_SWIPE_HOOK_FAILED", it) }
            // 探针 + 置位：createOverviewInputConsumer 只在"手势被判成多任务"时创建，
            // 比状态提交早得多；同时打印调用栈，作为入口成立的证据。
            runCatching {
                val loader = target.inputMethod.declaringClass.classLoader
                val serviceClass = loader.loadClass("com.android.quickstep.TouchInteractionService")
                val create = serviceClass.declaredMethods.firstOrNull { it.name == "createOverviewInputConsumer" }
                    ?: run {
                        module.log(Log.WARN, TAG, "LAUNCHER_OVERVIEW_CONSUMER_MISSING", null)
                        return@runCatching
                    }
                module.hook(create)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.launcher.overview_consumer")
                    .intercept { chain ->
                        val stack = Throwable().stackTrace.take(6).joinToString("<") { it.methodName }
                        module.log(Log.DEBUG, TAG, "LAUNCHER_OVERVIEW_CONSUMER_" + stack, null)
                        appContext()?.let { publishBlocked(it, true) }
                        chain.proceed()
                    }
                module.log(Log.INFO, TAG, "LAUNCHER_OVERVIEW_CONSUMER_HOOKED", null)
            }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_OVERVIEW_CONSUMER_FAILED", it) }
            // 桌面内上滑进入多任务：状态切换是「请求」出去的，完成要到 600 ms 之后，
            // 所以要挂在 goToState(BaseState) 上，目标为 OVERVIEW 时立刻置位。
            runCatching {
                val loader = target.inputMethod.declaringClass.classLoader
                val launcherClass = loader.loadClass("com.android.launcher3.Launcher")
                val app = appContext() ?: return@runCatching
                val launcherInstance = launcherClass
                    .getMethod("getLauncher", Context::class.java)
                    .invoke(null, app) ?: return@runCatching
                val stateManager = launcherClass.getMethod("getStateManager").invoke(launcherInstance)
                    ?: return@runCatching
                val goToState = stateManager.javaClass.methods.firstOrNull { method ->
                    method.name == "goToState" &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes[0].name.contains("BaseState")
                } ?: run {
                    module.log(Log.WARN, TAG, "LAUNCHER_GOTO_STATE_MISSING", null)
                    return@runCatching
                }
                module.hook(goToState)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.launcher.goto_state")
                    .intercept { chain ->
                        val targetState = chain.args.getOrNull(0)?.toString().orEmpty()
                        if (targetState.contains("OVERVIEW", ignoreCase = true)) {
                            module.log(Log.DEBUG, TAG, "LAUNCHER_GOTO_OVERVIEW", null)
                            appContext()?.let { publishBlocked(it, true) }
                        }
                        chain.proceed()
                    }
                module.log(Log.INFO, TAG, "LAUNCHER_GOTO_STATE_HOOKED", null)
            }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_GOTO_STATE_FAILED", it) }
            module.log(Log.INFO, TAG, "LAUNCHER_CORNER_INPUT_HOOK_INSTALLED")
        } catch (exception: IllegalArgumentException) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_HOOK_REJECTED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * 桌面是否处于系统页面（多任务 / 负一屏）。
     *
     * 这两者在 ColorOS 上都不是独立 Activity，而是桌面内部的页面状态，
     * 只能从桌面进程里读：多任务对应 LauncherState.OVERVIEW，
     * 负一屏对应 Workspace 的负页码。命中时我们完全不接管手势，交回桌面自己处理。
     */
    private val pageReporterStarted = AtomicBoolean(false)

    /** 供上滑 hook 使用：注册时缓存的 Launcher 实例。 */
    @Volatile private var launcherRef: Context? = null

    /** 注册桌面状态监听，并在状态变化时把"是否系统页面"上报给 system_server。 */
    /** 从当前 Context 解析出真正的 Launcher 实例（手势入口的 owner 是 TouchInteractionService）。 */
    private fun resolveLauncher(context: Context): Context? {
        if (context.javaClass.name.endsWith("Launcher")) return context
        return runCatching {
            val launcherClass = context.javaClass.classLoader
                .loadClass("com.android.launcher3.Launcher")
            launcherClass.getMethod("getLauncher", Context::class.java)
                .invoke(null, context) as? Context
        }.getOrNull()
    }

    private fun installPageReporter(owner: Context) {
        // 只接受真正的 Launcher 实例：手势入口传进来的是 TouchInteractionService，
        // 在它上面调用 getStateManager 会抛异常，并把这个一次性开关浪费掉。
        val launcher = resolveLauncher(owner)
        if (launcher == null) {
            module.log(Log.DEBUG, TAG, "LAUNCHER_PAGE_NO_INSTANCE_" + owner.javaClass.simpleName, null)
            return
        }
        if (!pageReporterStarted.compareAndSet(false, true)) return
        launcherRef = launcher
        runCatching {
            val stateManager = launcher.javaClass.getMethod("getStateManager").invoke(launcher)
            val listenerClass = launcher.javaClass.classLoader.loadClass(
                "com.android.launcher3.statemanager.StateManager\$StateListener",
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, _ ->
                if (method.name.startsWith("onState")) reportPageState(launcher)
                null
            }
            stateManager?.javaClass
                ?.getMethod("addStateListener", listenerClass)
                ?.invoke(stateManager, proxy)
            // 负一屏是桌面的一个翻页，不触发状态切换，这里单独跟一下页码变化。
            runCatching {
                val pagedView = launcher.javaClass.classLoader.loadClass("com.android.launcher3.PagedView")
                val setCurrentPage =
                    pagedView.getMethod("setCurrentPage", Int::class.javaPrimitiveType)
                module.hook(setCurrentPage)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.launcher.page_state")
                    .intercept { chain ->
                        val result = chain.proceed()
                        reportPageState(launcher)
                        result
                    }
            }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_PAGE_HOOK_FAILED", it) }
            module.log(Log.INFO, TAG, "LAUNCHER_PAGE_REPORTER_INSTALLED", null)
        }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_PAGE_REPORTER_FAILED", it) }
        reportPageState(launcher)
    }

    private fun appContext(): Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()

    /** 直接发布指定状态（用于上滑开始这类"已知要进入多任务"的时刻）。 */
    private fun publishBlocked(launcher: Context, blocked: Boolean) {
        runCatching {
            android.provider.Settings.System.putInt(
                launcher.contentResolver,
                io.github.hoerire.flymefreeform.config.ModulePreferences.KEY_LAUNCHER_PAGE_BLOCKED,
                if (blocked) 1 else 0,
            )
            launcher.sendBroadcast(
                android.content.Intent(
                    io.github.hoerire.flymefreeform.config.ModulePreferences.ACTION_LAUNCHER_PAGE_STATE,
                ).putExtra(
                    io.github.hoerire.flymefreeform.config.ModulePreferences.EXTRA_LAUNCHER_BLOCKED,
                    blocked,
                ),
            )
        }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_PAGE_PUBLISH_FAILED", it) }
    }

    private fun reportPageState(launcher: Context) {
        val blocked = isLauncherSystemPage(launcher)
        ModuleLog.debug(TAG) { "LAUNCHER_PAGE_CALC_" + blocked }
        // 直接写系统设置：system_server 在按下瞬间读它，避免广播排队造成的几十毫秒误差。
        runCatching {
            android.provider.Settings.System.putInt(
                launcher.contentResolver,
                io.github.hoerire.flymefreeform.config.ModulePreferences.KEY_LAUNCHER_PAGE_BLOCKED,
                if (blocked) 1 else 0,
            )
        }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_PAGE_SETTINGS_WRITE_FAILED", it) }
        runCatching {
            launcher.sendBroadcast(
                android.content.Intent(
                    io.github.hoerire.flymefreeform.config.ModulePreferences.ACTION_LAUNCHER_PAGE_STATE,
                ).putExtra(
                    io.github.hoerire.flymefreeform.config.ModulePreferences.EXTRA_LAUNCHER_BLOCKED,
                    blocked,
                ),
            )
            ModuleLog.debug(TAG) { "LAUNCHER_PAGE_REPORT_" + blocked }
        }.onFailure { module.log(Log.WARN, TAG, "LAUNCHER_PAGE_REPORT_FAILED", it) }
    }

    /** 沿继承链找方法：桌面类层级较深，直接 getMethod 容易漏。 */
    private fun findMethod(start: Class<*>, name: String, count: Int): java.lang.reflect.Method? {
        var type: Class<*>? = start
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == count }
                ?.let { it.isAccessible = true; return it }
            type = type.superclass
        }
        return null
    }

    private fun isLauncherSystemPage(launcher: Context): Boolean =
        runCatching {
            val stateManager = launcher.javaClass.getMethod("getStateManager").invoke(launcher)
            val state = stateManager?.javaClass?.getMethod("getState")?.invoke(stateManager)
            // 用状态名判断，而不是实例相等：多任务还可能是 OVERVIEW_MODAL_TASK 等状态。
            val stateName = runCatching { state?.toString().orEmpty() }.getOrDefault("")
            if (stateName.contains("OVERVIEW", ignoreCase = true)) return true
            // 负一屏：桌面自带 isAssistantScreenVisible()，比"负页码"可靠。
            // （实测负一屏是 com.coloros.assistantscreen 的独立窗口，不占 Workspace 页。）
            findMethod(launcher.javaClass, "isAssistantScreenVisible", 0)
                ?.invoke(launcher) as? Boolean == true
        }.getOrDefault(false)

    private fun shouldSuppress(owner: Context, event: MotionEvent): Boolean {
        // 多任务界面与负一屏不响应扇形手势；同时把状态上报给 system_server（手势判定在那里）。
        // 这里只做判定，注册交给 Launcher.onResume。
        resolveLauncher(owner)?.let { if (isLauncherSystemPage(it)) return false }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            resetStream()
            val config = createEligibleConfig(owner, event) ?: return false
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
                    if (!pilfered && action is GestureAction.Activate) {
                        forcePilferPointers(owner)
                        pilfered = true
                        logClaimed()
                    }
                } else {
                    gestureEngine.cancel()
                }
            }

            // 多指按下不取消主手势（与系统侧一致），避免滑选中途被判为断触。
            MotionEvent.ACTION_POINTER_DOWN -> GestureAction.Ignore

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
        val settings = configuration.snapshot
        if (!configuration.isAvailable || !settings.enabled) return null
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
                mainHandler.post { if (!environmentState.isGestureAllowed()) cancelClaim() }
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
        module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_PILFER_FAILED", exception)
    }

    /**
     * 记录一次角落指针流拦截。
     *
     * 该事件每次按角落都会发生，属于常态事件：进程内首次以 INFO 输出，用于确认 hook 真的生效；
     * 之后仅在「详细日志」开启时输出，避免日志被高频条目占满。
     */
    private fun logClaimed() {
        val now = SystemClock.uptimeMillis()
        if (now - lastClaimLogAt < CLAIM_LOG_INTERVAL_MS) return
        lastClaimLogAt = now
        if (claimLogged) {
            ModuleLog.debug(TAG) { "LAUNCHER_CORNER_INPUT_CLAIMED" }
        } else {
            claimLogged = true
            module.log(Log.INFO, TAG, "LAUNCHER_CORNER_INPUT_CLAIMED")
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val RESOLUTION_TIMEOUT_MS = 10_000L
        const val CLAIM_LOG_INTERVAL_MS = 2_000L
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }

}