package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import io.github.hoerire.flymefreeform.log.VerboseLog
import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.gesture.OutsideTapGestureEngine
import io.github.hoerire.flymefreeform.logic.CloseDecision
import io.github.hoerire.flymefreeform.logic.ClosePolicy
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsDisplayContentImeAccess
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsTaskImeBoundsAccess
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor

/** 扩展 ColorOS 自有全局指针监听，只处理已验证的普通小窗窗外短点击。 */
@SuppressLint("PrivateApi")
internal class OutsideTapCloseHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private var lastFailureLogAt = -FAILURE_LOG_INTERVAL_MS

    /** 允许强制重读配置的次数：只在拦截初始阶段用，之后不再读盘。 */
    @Volatile private var reloadBudget = 3

    /** 关闭去抖时间戳：一次点击只允许触发一次关闭。 */
    @Volatile private var lastCloseFiredAt = 0L


    fun install(classLoader: ClassLoader) {
        try {
            val listenerClass = classLoader.loadClass(TOUCH_LISTENER_CLASS)
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val taskClass = classLoader.loadClass(TASK_CLASS)
            val displayContentClass = classLoader.loadClass(DISPLAY_CONTENT_CLASS)
            val windowStateClass = classLoader.loadClass(WINDOW_STATE_CLASS)
            val captionClass = classLoader.loadClass(FLEXIBLE_CAPTION_VIEW_CLASS)
            val onPointerEvent =
                listenerClass.getDeclaredMethod("onPointerEvent", MotionEvent::class.java)
            val updateTouchableRegion =
                captionClass.getDeclaredMethod("updateTouchableRegion", Region::class.java)
            val access =
                ColorOsOutsideTapAccess(
                    listenerClass = listenerClass,
                    controllerClass = controllerClass,
                    taskClass = taskClass,
                    taskExtClass = classLoader.loadClass(TASK_EXT_CLASS),
                    displayContentClass = displayContentClass,
                    windowStateClass = windowStateClass,
                    captionClass = captionClass,
                    onFailure = ::logFailure,
                    // 锁屏与息屏状态下不参与判定：此时不存在需要关闭的小窗，
                    // 且触摸可能穿透至下层界面。
                    // 横屏与游戏模式不在排除范围内：该功能针对已打开的小窗，
                    // 不涉及呼出手势，暂停策略不适用。
                    environmentAllowed = {
                        environment.isModuleActive(refreshKeyguard = true) &&
                            !environment.isForegroundExcluded()
                    },
                )
            ColorOsOutsideTapAccess.settingsProvider = { configuration.snapshot }
            // 底部关闭：hook caption 自己的触摸分发（与 oupao.wiz 同一 hook 点）。
            runCatching {
                val dispatch =
                    captionClass.requiredMethod(
                        "dispatchTouchEvent",
                        arrayOf(MotionEvent::class.java),
                    )
                module
                    .hook(dispatch)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.system.bottom_close_caption_touch")
                    .intercept { chain ->
                        val caption = chain.thisObject
                        val event = chain.getArg(0) as? MotionEvent
                        if (caption != null && event != null) {
                            runCatching { access.onCaptionTouch(caption, event) }
                        }
                        chain.proceed()
                    }
                io.github.hoerire.flymefreeform.log.ModuleLog.info("BOTTOM_CLOSE_TOUCH_HOOK_INSTALLED")
            }.onFailure { logFailure("BOTTOM_CLOSE_TOUCH_HOOK_FAILED", it) }
            module
                .hook(updateTouchableRegion)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.outside_tap_input_region")
                .intercept { chain ->
                    val caption = chain.thisObject ?: return@intercept chain.proceed()
                    val originalRegion = chain.getArg(0) as? Region ?: return@intercept chain.proceed()
                    // 刚关闭过窗口时系统正在重排剩余浮标，此时再改写触摸区域会放大成闪烁。
                    if (android.os.SystemClock.uptimeMillis() - lastCloseFiredAt < REGION_QUIET_MS) {
                        return@intercept chain.proceed()
                    }
                    access.rememberRegion(caption, originalRegion)
                    val protectedRegion =
                        try {
                            access.buildProtectedRegion(caption, originalRegion, configuration.snapshot)
                        } catch (exception: ReflectiveOperationException) {
                            access.clearProtection(caption)
                            logFailure("OUTSIDE_TAP_REGION_REFLECTION_FAILED", exception)
                            null
                        } catch (exception: RuntimeException) {
                            access.clearProtection(caption)
                            logFailure("OUTSIDE_TAP_REGION_FAILED", exception)
                            null
                        }
                    if (protectedRegion == null) {
                        return@intercept chain.proceed()
                    }
                    val result = chain.proceed(arrayOf(protectedRegion.region))
                    access.markProtected(caption, protectedRegion.task)
                    result
                }
            // 小窗创建初期窗口仍为全屏尺寸，无法计算窗外区域；动画结束后重新触发一次。
            runCatching {
                val addFlexibleTask =
                    controllerClass.requiredMethod("addFlexibleTask", arrayOf(taskClass))
                val skinHandler = android.os.Handler(android.os.Looper.getMainLooper())
                module
                    .hook(addFlexibleTask)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.system.caption_region_refresh")
                    .intercept { chain ->
                        val result = chain.proceed()
                        val controller = chain.thisObject
                        val task = chain.getArg(0)
                        if (controller != null && task != null) {
                            // 记下刚创建的窗口：驱逐排序要保证它不会被系统从尾部踢掉，
                            // 否则会出现"新窗开出来立刻被自己关掉"。
                            lastAddedTaskId = taskIdOf(task)
                            lastAddedAt = android.os.SystemClock.uptimeMillis()
                            if (VerboseLog.enabled) {
                                ModuleLog.debug { "ZOOM_ADD_task" + lastAddedTaskId + "_" + (packageOfTask(task) ?: "-") }
                            }
                            listOf(500L, 1000L, 1600L, 2600L).forEach { delay ->
                                skinHandler.postDelayed(
                                    {
                                        runCatching { access.refreshCaptionRegion(controller, task) }
                                        Unit
                                    },
                                    delay,
                                )
                            }
                        }
                        result
                    }
            }.onFailure { logFailure("OUTSIDE_TAP_REFRESH_HOOK_FAILED", it) }
            configuration.observe(access::onConfigurationChanged)
            environment.observe { access.onConfigurationChanged(configuration.snapshot) }
            module
                .hook(onPointerEvent)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.outside_tap_close")
                .intercept { chain ->
                    val listener = chain.thisObject
                    val event = chain.getArg(0) as? MotionEvent
                    val closeCandidate =
                        if (listener != null && event != null) {
                            try {
                                access.beforeOriginal(listener, event, configuration.snapshot)
                            } catch (exception: ReflectiveOperationException) {
                                access.interrupt(listener)
                                logFailure("OUTSIDE_TAP_RUNTIME_REFLECTION_FAILED", exception)
                                null
                            } catch (exception: RuntimeException) {
                                access.interrupt(listener)
                                logFailure("OUTSIDE_TAP_RUNTIME_FAILED", exception)
                                null
                            }
                        } else {
                            null
                        }
                    val result = chain.proceed()
                    if (listener != null && closeCandidate != null) {
                        // 一次点击窗外会连续投递多个 pointer 事件，逐个关闭会表现为屏幕连闪几下。
                        if (android.os.SystemClock.uptimeMillis() - lastCloseFiredAt < CLOSE_DEBOUNCE_MS) {
                            return@intercept result
                        }
                        lastCloseFiredAt = android.os.SystemClock.uptimeMillis()
                        if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
                            ModuleLog.debug { "OUTSIDE_TAP_CLOSE_FIRED" }
                        }
                        try {
                            access.closeIfStillValid(listener, closeCandidate)
                        } catch (exception: ReflectiveOperationException) {
                            logFailure("OUTSIDE_TAP_CLOSE_REFLECTION_FAILED", exception)
                        } catch (exception: RuntimeException) {
                            logFailure("OUTSIDE_TAP_CLOSE_FAILED", exception)
                        }
                    }
                    result
                }
            io.github.hoerire.flymefreeform.log.ModuleLog.info("OUTSIDE_TAP_INPUT_REGION_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("OUTSIDE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("OUTSIDE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
        // 独立安装：任何失败都只影响"替换转关闭"这一项，不能连累窗外关闭。
        runCatching { installReplaceToClose(classLoader) }
            .onFailure {
                ModuleLog.warn("ZOOM_REPLACE_TO_CLOSE_FAILED_" + it.javaClass.simpleName + "_" + it.message, it)
            }
    }

    /**
     * 把「因新窗口替换而转成浮标」改成「直接关闭」。
     *
     * 系统的策略对象 ExitFlexibleTaskToFloatStrategy 会带一个来源标记，
     * 其中 SOURCE_FROM_HANDLE_REPLACE_TO_FLOAT 表示"被新窗口顶掉、收成浮标"。
     * 在 controller.exitFlexibleTaskWindowInnerLocked 入口把它替换成 ToBack（关闭）策略，
     * 结果是窗口被关掉而不是留下浮标；用户主动拖到边缘（其他来源）仍然保持原生行为。
     */
    /**
     * 把「因窗口超过上限被收成浮标」改成「直接关闭」。
     *
     * 判据是调用栈：超限路径固定经过 updateTaskMaxNumWhenFocusChanged / handleFlexibleExChange，
     * 用户手动拖到边缘走的是拖拽入口，不经过这两处，因此不会被误伤。
     * 命中时把 ToFloat（exitTo:5，变浮标）换成 ToBack（exitTo:6，真关闭）。
     */
    private fun installReplaceToClose(loader: ClassLoader) {
        val controllerClass = loader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
        val baseClass = loader.loadClass(ABS_EXIT_STRATEGY_CLASS)
        val floatStrategyClass = loader.loadClass(EXIT_TO_FLOAT_STRATEGY_CLASS)
        val backStrategyClass = loader.loadClass(EXIT_TO_BACK_STRATEGY_CLASS)
        val innerLocked =
            methodInHierarchy(controllerClass, "exitFlexibleTaskWindowInnerLocked", 1)
                ?: run {
                    ModuleLog.warn("ZOOM_REPLACE_TO_CLOSE_NO_METHOD")
                    return
                }
        val taskClass = loader.loadClass("com.android.server.wm.Task")
        val backConstructor =
            backStrategyClass.declaredConstructors
                .firstOrNull { candidate ->
                    candidate.parameterTypes.size == 2 &&
                        candidate.parameterTypes[0].isAssignableFrom(taskClass) &&
                        candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType
                }
                ?.apply { isAccessible = true }
                ?: run {
                    ModuleLog.warn("ZOOM_REPLACE_TO_CLOSE_NO_CTOR")
                    return
                }
        // 台账超限时要用同一个退场入口关掉最旧的窗口。
        exitInnerLocked = innerLocked
        exitBackConstructor = backConstructor
        installVictimOrderHook(controllerClass)
        module
            .hook(innerLocked)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.system.replace_to_close")
            .intercept { chain ->
                if (!configuration.snapshot.enabled) return@intercept chain.proceed()
                val strategy = chain.args.getOrNull(0)
                val isFloatExit = strategy != null && floatStrategyClass.isInstance(strategy)
                // 诊断：ColorOS 17 收贴边时交来的策略类不是浮动策略，仅详细日志时输出。
                if (strategy != null && !isFloatExit && VerboseLog.enabled) {
                    ModuleLog.debug { "ZOOM_EXIT_NOT_FLOAT_" + strategy.javaClass.simpleName }
                }
                // 开关状态：仅在"确实是一次转浮标退出"且快照为关闭时才重读磁盘配置，
                // 避免把预算浪费在无关退出上。
                if (isFloatExit && !configuration.snapshot.autoCloseWindowEffective && reloadBudget > 0) {
                    reloadBudget--
                    configuration.forceReload()
                }
                if (isFloatExit && configuration.snapshot.autoCloseWindowEffective) {
                    // 应用内转为小窗时，ColorOS 17 同样会执行一次退场裁决，且退出对象为
                    // 刚创建的窗口本身。该情形予以放行：关闭该窗口等同于窗口一建立即消失，
                    // 与「自动收窗」所处理的「旧窗口被顶掉」并非同一语义。
                    var exitingTask = strategy?.let { exiting ->
                        fieldInHierarchy(exiting.javaClass, "mTask")?.get(exiting)
                    }
                    val frames = Throwable().stackTrace
                    // 系统的「谁该退出」判定有两条入口：超出数量上限（updateTaskMaxNum* /
                    // handleFlexibleExChange）与统一裁决（determineFlexibleTaskToExit /
                    // setLastFlexibleTaskToExit*）。冷启动新窗口走的是后者——
                    // 实测 23:30:00 那次只出现 determineFlexibleTaskToExit:2376，
                    // 早先只认前者，于是判据不成立、原样放行，旧窗被收成浮标。
                    val fromLimit = frames.any { frame ->
                        val name = frame.methodName
                        name.contains("updateTaskMaxNum") ||
                            name.contains("handleFlexibleExChange") ||
                            name.contains("determineFlexibleTaskToExit") ||
                            name.contains("setLastFlexibleTaskToExit")
                    }
                    // 这里不再有"最近创建就放行"的特例。
                    // 应用自己变小窗时，determineFlexibleTaskToExit 收到的浮动条数是 1，
                    // 不会超过设置值，闸门已经把它挡在退场之前；能走到这里的都是真超限的驱逐。
                    // 判据只看这次退出是不是系统自己的"该谁退出"裁决
                    //（determine*/updateTaskMaxNum*）；用户拖到边缘走的拖拽入口不经过这里。
                    //
                    // 不再用「最近 1.5 秒内有启动」当门槛：那条规则本意是区分"被新应用顶掉"
                    // 与"自身变形"，但它要求打开小窗后 1.5 秒内系统就做裁决，实际几乎必然错过，
                    // 结果是窗口照样被收成贴边浮标（用户日志里的 KeepOriginal_launch0）。
                    // 至于"退出的到底是不是刚创建的那个窗口"，不再需要单独判断：
                    // 应用自己变小窗时 determine 收到的条数不会超过设置值，闸门已经挡住了。
                    val decision =
                        ClosePolicy.decide(
                            autoCloseEnabled = true,
                            isFloatExitStrategy = isFloatExit,
                            fromSystemVerdict = fromLimit,
                        )
                    // 每次"要收浮标"的退出留一行诊断，仅详细日志开启时输出。
                    if (VerboseLog.enabled) {
                        ModuleLog.debug { "ZOOM_CLOSE_POLICY_" + decision.name + "_limit" + (if (fromLimit) 1 else 0) }
                    }
                    if (decision == CloseDecision.ReplaceWithToBack) {
                        val current = strategy ?: return@intercept chain.proceed()
                        val task = runCatching {
                            fieldInHierarchy(current.javaClass, "mTask")?.get(current)
                        }.getOrNull()
                        val back = task?.let { runCatching { backConstructor.newInstance(it, true) }.getOrNull() }
                        if (task != null && back != null) {
                            if (VerboseLog.enabled) {
                                ModuleLog.debug { "ZOOM_REPLACE_TO_CLOSE" }
                            }
                            // 同帧替换为 ToBack，但不再隐藏 surface：退场动画交给系统照常播完。
                            return@intercept chain.proceed(arrayOf(back))
                        }
                    }
                }
                chain.proceed()
            }
        io.github.hoerire.flymefreeform.log.ModuleLog.info("ZOOM_REPLACE_TO_CLOSE_INSTALLED")
    }


    @Volatile private var exitInnerLocked: java.lang.reflect.Method? = null
    @Volatile private var exitBackConstructor: java.lang.reflect.Constructor<*>? = null

    /**
     * 决定"超限时先踢谁"。
     *
     * 系统的超限判定只有一处：handleExceedLimitFlexibleTask。它把浮动窗口排成一个列表，
     * 列表头部是最新创建的，然后从头部开始踢——这正是"新窗口一开就消失"的原因，
     * 也和"自动收窗应该关最旧的"相反。
     *
     * 这里只做一件事：把列表重排成"最旧在前"。踢谁、怎么踢、动画怎么播，全部交回系统，
     * 关闭与创建因此仍落在系统同一次超限处理里（同帧）。
     *
     * 参数：allVisibleWindowSize, flexibleTasks(List), maxNum, maxFlexibleTaskNum, reason, currentTask
     */
    private fun installVictimOrderHook(controllerClass: Class<*>) {
        val budget = methodInHierarchy(controllerClass, "handleExceedLimitFlexibleTask", 6)
        if (budget != null) {
            hookListOrder(budget, "flymefreeform.system.victim_order.budget", listArgIndex = 1)
        } else {
            ModuleLog.warn("ZOOM_VICTIM_ORDER_NO_METHOD")
        }
        // 另一条限制驱动入口（非 panoramic 的焦点重算走这里）。
        // 本机尚未观察到调用，但入口存在，排序必须一致，否则某些模式下会改由系统挑最新的。
        // 只排序、不拦截：判定与时机完全不动。
        listOfNotNull(
            methodInHierarchy(controllerClass, "determineFlexibleTaskToExit", 5),
            methodInHierarchy(controllerClass, "determineFlexibleTaskToExit", 4),
        ).forEach { method ->
            hookListOrder(
                method,
                "flymefreeform.system.victim_order.determine_" + method.parameterCount,
                listArgIndex = 2,
            )
        }
    }

    /** 挂一个"只把列表重排成最旧在前"的 hook，不改变任何判定。 */
    private fun hookListOrder(
        method: java.lang.reflect.Method,
        hookId: String,
        listArgIndex: Int,
    ) {
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId(hookId)
            .intercept { chain ->
                // 只服务「自动收窗」：关闭时保持系统原生的驱逐顺序。
                if (configuration.snapshot.autoCloseWindowEffective) {
                    val tasks = chain.args.getOrNull(listArgIndex) as? MutableList<Any?>
                    if (tasks != null && tasks.size > 0 && VerboseLog.enabled) {
                        ModuleLog.debug { "ZOOM_VICTIM_BEFORE_" + ids(tasks) }
                    }
                    if (tasks != null && tasks.size > 1) {
                        runCatching { reorderOldestLast(tasks) }
                    }
                    if (tasks != null && tasks.size > 0 && VerboseLog.enabled) {
                        ModuleLog.debug { "ZOOM_VICTIM_AFTER_" + ids(tasks) }
                    }
                }
                chain.proceed()
            }
    }

    /** 列表内容转成系统的 taskId，用于诊断。 */
    private fun ids(tasks: List<Any?>): String = tasks.joinToString(",") { taskIdOf(it).toString() }

    /** 系统的任务编号，仅用于诊断与排序兜底。 */
    private fun taskIdOf(task: Any?): Int {
        val field = fieldOf(task, "mTaskId") ?: return -1
        return runCatching { field.getInt(task) }.getOrDefault(-1)
    }

    /** 刚由 addFlexibleTask 建出来的窗口，用于把它挡在驱逐排序的最前面。 */
    /** 浮动任务所属包名；取不到时为 null（回收侧按包名可选匹配）。 */
    private fun packageOfTask(task: Any): String? =
        runCatching {
            (fieldInHierarchy(task.javaClass, "realActivity")?.get(task)
                as? android.content.ComponentName)?.packageName
        }.getOrNull()

    @Volatile private var lastAddedTaskId: Int = -1

    @Volatile private var lastAddedAt: Long = 0L

    /**
     * 系统的最后活跃时间（Task.touchActiveTime 写入 elapsedRealtime）。
     *
     * 这是判断"哪个窗口最旧"的正确依据：任务编号是创建顺序，而 ColorOS 会复用同应用的
     * 已有 task，被复用的窗口编号很旧但刚刚才被激活——按编号排会把"正在打开的那个"当成最旧。
     */
    private fun lastActiveOf(task: Any?): Long {
        val field = fieldOf(task, "lastActiveTime") ?: return -1L
        return runCatching { field.getLong(task) }.getOrDefault(-1L)
    }

    /** 活跃时间键：取不到或为 0 时按"最新"处理，避免新窗口被判成最旧。 */
    private fun activeKeyOf(task: Any?): Long {
        val value = lastActiveOf(task)
        return if (value > 0L) value else Long.MAX_VALUE
    }

    private fun fieldOf(task: Any?, name: String): java.lang.reflect.Field? {
        if (task == null) return null
        val key = task.javaClass.name + "#" + name
        return fields.getOrPut(key) {
            var type: Class<*>? = task.javaClass
            var found: java.lang.reflect.Field? = null
            while (type != null && found == null) {
                found = runCatching { type.getDeclaredField(name) }.getOrNull()
                type = type.superclass
            }
            found?.apply { isAccessible = true }
        }
    }

    private val fields =
        java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Field>()

    /**
     * 把列表排成"最旧在尾部"——注意方向。
     *
     * 系统的驱逐循环是 sub-int index, size, i 后取 list.get(index)，i 从 1 起，
     * 也就是依次取 size-1、size-2……：它从列表尾部往前踢。
     * 所以要让"最旧"被踢，最旧的必须排在最后 → 按 taskId 降序（新的在前）。
     */
    private fun reorderOldestLast(tasks: MutableList<Any?>) {
        // 最近活跃的排前面，最久没被激活的排最后 → 系统从尾部踢，被移除的即最旧窗口。
        //
        // 两条例外必须挡在排序之前：
        //   1. 刚打开的那个窗口永远排最前——limit=1 时它本该顶替旧窗，绝不能被踢；
        //   2. 拿不到活跃时间（字段缺失，或任务刚建好还是 0）的按"最新"处理，
        //      否则新窗口会被当成最旧的那个。
        // 不要用"上一次 addFlexibleTask 记录"来做保护：系统的驱逐发生在 addFlexibleTask
        // 之前（实测早约 20ms），那时记录里还是上一个窗口，反而会把新窗推到尾部挨踢。
        // 只按活跃时间排：取不到活跃时间的（刚建好还是 0）按"最新"处理，自然排在最前。
        val sorted = tasks.sortedWith(
            compareByDescending<Any?> { activeKeyOf(it) }
                .thenByDescending<Any?> { taskIdOf(it) },
        )
        if (sorted.indices.all { sorted[it] === tasks[it] }) return
        for (index in sorted.indices) tasks[index] = sorted[index]
        if (VerboseLog.enabled) {
            ModuleLog.debug { "ZOOM_VICTIM_ORDER_OLDEST_LAST" }
        }
    }


    /** 沿继承链找字段（厂商把字段放在父类上）。 */
    private fun fieldInHierarchy(start: Class<*>, name: String): java.lang.reflect.Field? {
        var type: Class<*>? = start
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        return null
    }

    /** 沿继承链找指定参数个数的方法。 */
    private fun methodInHierarchy(start: Class<*>, name: String, count: Int): java.lang.reflect.Method? {
        var type: Class<*>? = start
        while (type != null) {
            type.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == count }?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        return null
    }

    private fun logFailure(code: String, throwable: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureLogAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureLogAt = now
        ModuleLog.warn(code, throwable)
    }

    private class ColorOsOutsideTapAccess(
        listenerClass: Class<*>,
        controllerClass: Class<*>,
        taskClass: Class<*>,
        taskExtClass: Class<*>,
        displayContentClass: Class<*>,
        windowStateClass: Class<*>,
        captionClass: Class<*>,
        private val onFailure: (String, Throwable) -> Unit,
        private val environmentAllowed: () -> Boolean,
    ) {
        private val controllerField = listenerClass.requiredField("this$0")

        /** 关窗动作由输入线程触发，必须回到主线程执行，否则会和窗口退出流程抢锁。 */
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** 关闭去重时间戳：同一窗口短时间内只关一次。 */
        private var lastCloseAt = 0L

        /** 底部关闭的按下状态，在 caption 的 dispatchTouchEvent 里记录。 */
        private var handleTapTask: Any? = null
        private var handleDownX = 0f
        private var handleDownY = 0f
        private var handleDownAt = 0L

        private val contextField = controllerClass.requiredField("mContext")
        private val flexibleTasksField = controllerClass.requiredField("mFlexibleTasks")
        private val captionTaskField = captionClass.requiredField("mTask")
        private val captionControllerField = captionClass.requiredField("mFlexibleTaskController")
        private val inputMethodWindow =
            ColorOsDisplayContentImeAccess(displayContentClass, windowStateClass)
        private val inputMethodTaskBounds =
            ColorOsTaskImeBoundsAccess(taskClass, taskExtClass, Rect::class.java)
        private val updateCaptionTouchRegion = captionClass.requiredMethod("updateTouchRegion", 0)
        private val getFlexibleCaptionView =
            controllerClass.requiredMethod(
                name = "getFlexibleCaptionView",
                parameterTypes = arrayOf(taskClass, String::class.java),
            )
        private val lastCaptionRegions = java.util.WeakHashMap<Any, Region>()
        private val getTopZoomTask = controllerClass.requiredMethod("getTopZoomTask", 0)
        private val isCanRespondEvent = controllerClass.requiredMethod("isCanRespondEvent", 0)
        private val isTaskInFlexibleState =
            controllerClass.requiredMethod("isTaskInFlexibleState", 2)
        private val getVisibleBounds =
            controllerClass.requiredMethod(
                name = "getFlexibleTaskVisibleBounds",
                parameterTypes = arrayOf(taskClass),
            )
        private val hasTouchableTask =
            controllerClass.requiredMethod("hasFlexibleTaskInTouchableRegion", 2)
        private val hasMenuShowing = controllerClass.requiredMethod("hasFlexibleTaskMenuShow", 0)
        private val updateTapExcludeRegion =
            controllerClass.requiredMethod(
                name = "updateWindowTapExcludeRegion",
                parameterTypes = arrayOf(displayContentClass, Region::class.java),
            )
        private val isIgnoreExpandRegion =
            controllerClass.requiredMethod("isIgnoreExpandRegion", 1)
        private val exitFlexibleTask =
            controllerClass.requiredMethod(
                name = "exitFlexibleTask",
                parameterTypes =
                    arrayOf(
                        taskClass,
                        Boolean::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                        Int::class.javaPrimitiveType!!,
                    ),
            )
        private val getDisplayContent = taskClass.requiredMethod("getDisplayContent", 0)
        private val getDisplayBounds = displayContentClass.requiredMethod("getBounds", 0)
        private val isWindowVisible = windowStateClass.requiredMethod("isVisible", 0)
        private val getTouchableRegion =
            windowStateClass.requiredMethod(
                name = "getTouchableRegion",
                parameterTypes = arrayOf(Region::class.java),
            )
        private val engines = IdentityHashMap<Any, OutsideTapGestureEngine>()
        private val captions = WeakHashMap<Any, Unit>()
        private val protectedTasks = WeakHashMap<Any, Unit>()


        fun onConfigurationChanged(
            settings: io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot,
        ) {
            interruptAll()
            val knownCaptions = synchronized(captions) { captions.keys.toList() }
            knownCaptions.forEach { caption ->
                try {
                    updateCaptionTouchRegion.invokeUnwrapped(caption)
                } catch (exception: ReflectiveOperationException) {
                    clearProtection(caption)
                    onFailure("OUTSIDE_TAP_REGION_REFRESH_REFLECTION_FAILED", exception)
                } catch (exception: RuntimeException) {
                    clearProtection(caption)
                    onFailure("OUTSIDE_TAP_REGION_REFRESH_FAILED", exception)
                }
            }
            if (!environmentAllowed() || !settings.enabled || settings.outsideTapCloseMode == OutsideTapCloseMode.Disabled) {
                synchronized(protectedTasks) { protectedTasks.clear() }
            }
        }

        fun buildProtectedRegion(
            caption: Any,
            originalRegion: Region,
            settings: io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot,
        ): ProtectedTouchableRegion? {
            synchronized(captions) { captions[caption] = Unit }
            val task = captionTaskField.get(caption) ?: return clearProtection(caption, "NO_TASK")
            val controller = captionControllerField.get(caption)
                ?: return clearProtection(caption, "NO_CONTROLLER")
            val mode =
                if (settings.enabled && environmentAllowed()) settings.outsideTapCloseMode
                else OutsideTapCloseMode.Disabled
            if (mode == OutsideTapCloseMode.Disabled) return clearProtection(caption, "MODE_OFF")
            // 底部关闭不做任何防穿透：这条路径只认"点小窗底部横条"，
            // 触摸区保持窗口原样，窗外点击照常落到下层应用。
            if (mode == OutsideTapCloseMode.BottomTap) {
                return clearProtection(caption, "BOTTOM_TAP_NO_GUARD")
            }
            if (!isOrdinaryZoom(controller, task)) return clearProtection(caption, "NOT_ZOOM")
            if (hasMenuShowing.invokeUnwrapped(controller) == true) {
                return clearProtection(caption, "MENU_SHOWING")
            }
            val displayContent = getDisplayContent.invokeUnwrapped(task)
                ?: return clearProtection(caption, "NO_DISPLAY")
            if (isIgnoreExpandRegion.invokeUnwrapped(controller, displayContent) == true) {
                return clearProtection(caption, "IGNORE_REGION")
            }
            val captionView = caption as? View ?: return clearProtection(caption, "NOT_VIEW")
            val localWidth = captionView.width
            val localHeight = captionView.height
            val visibleBounds = visibleTaskBounds(controller, task)
                ?: return clearProtection(caption, "NO_BOUNDS")
            val displayBounds = getDisplayBounds.invokeUnwrapped(displayContent) as? Rect
                ?: return clearProtection(caption, "NO_DISPLAY_BOUNDS")
            if (localWidth <= 0 || localHeight <= 0) {
                return clearProtection(caption, "CAPTION_ZERO")
            }
            if (visibleBounds.isEmpty || displayBounds.isEmpty) {
                return clearProtection(caption, "BOUNDS_EMPTY")
            }
            val scaleX = visibleBounds.width().toFloat() / localWidth
            val scaleY = visibleBounds.height().toFloat() / localHeight
            if (!scaleX.isFinite() || !scaleY.isFinite() || scaleX <= 0f || scaleY <= 0f) {
                return clearProtection(caption, "SCALE_INVALID")
            }
            if (kotlin.math.abs(scaleX - scaleY) > SCALE_TOLERANCE) {
                diag { "REGION_SCALE_MISMATCH_" + scaleX + "_" + scaleY }
                return clearProtection(caption, "SCALE_MISMATCH")
            }

            val outsideOnScreen = Region(displayBounds)
            outsideOnScreen.op(visibleBounds, Region.Op.DIFFERENCE)
            allFlexibleTaskBounds(controller).forEach { bounds ->
                outsideOnScreen.op(bounds, Region.Op.DIFFERENCE)
            }
            val context = contextField.get(controller) as? Context ?: return clearProtection(caption)
            val statusBarBottom = displayBounds.top + statusBarHeight(context)
            if (statusBarBottom > displayBounds.top) {
                outsideOnScreen.op(
                    Rect(displayBounds.left, displayBounds.top, displayBounds.right, statusBarBottom),
                    Region.Op.DIFFERENCE,
                )
            }
            val excludedRegion =
                updateTapExcludeRegion.invokeUnwrapped(controller, displayContent, null) as? Region
            if (excludedRegion != null && !excludedRegion.isEmpty) {
                outsideOnScreen.op(excludedRegion, Region.Op.DIFFERENCE)
            }
            visibleImeRegion(displayContent)?.let { imeRegion ->
                outsideOnScreen.op(imeRegion, Region.Op.DIFFERENCE)
            }
            if (outsideOnScreen.isEmpty) return clearProtection(caption, "OUTSIDE_EMPTY")

            val localOutside =
                screenToCaptionRegion(
                    screenRegion = outsideOnScreen,
                    visibleBounds = visibleBounds,
                    scaleX = scaleX,
                    scaleY = scaleY,
                )
            if (localOutside.isEmpty) return clearProtection(caption, "LOCAL_OUTSIDE_EMPTY")
            val protectedRegion = Region(originalRegion)
            protectedRegion.op(localOutside, Region.Op.UNION)
            diag { "REGION_OK" }
            return ProtectedTouchableRegion(task, protectedRegion)
        }

        /**
         * 系统通道（通知中心、控制中心、横幅）拉起的小窗，首次更新触摸区时窗口尚未缩至
         * 小窗尺寸，窗外区域为空，不产生保护标记，点击窗外会穿透到下层。
         * 窗口尺寸稳定后重新触发触摸区计算，补上窗外区域。
         */
        fun refreshCaptionRegion(controller: Any, task: Any) {
            if (isProtected(task)) {
                diag { "REFRESH_SKIP_protected" }
                return
            }
            val caption =
                captionForTask(task)
                    ?: runCatching { getFlexibleCaptionView.invokeUnwrapped(controller, task, null) }
                        .getOrNull()
                    ?: runCatching { getFlexibleCaptionView.invokeUnwrapped(controller, task, "") }
                        .getOrNull()
            diag { "REFRESH_caption_" + (if (caption != null) "ok" else "null") }
            if (caption == null) return
            recallRegion(caption)
            diag { "REFRESH_after_protected_" + isProtected(task) }
        }

        /** 按 task 从已记录的 caption 中反查；getFlexibleCaptionView 需要 tag 参数，常返回 null。 */
        fun captionForTask(task: Any): Any? {
            val known = synchronized(lastCaptionRegions) { lastCaptionRegions.keys.toList() }
            for (caption in known) {
                if (captionTaskField.get(caption) === task) return caption
            }
            return null
        }

        fun recallRegion(caption: Any) {
            val region = synchronized(lastCaptionRegions) { lastCaptionRegions[caption]?.let(::Region) }
            diag { "REFRESH_region_" + (if (region == null) "null" else "ok") }
            if (region == null) return
            runCatching { updateCaptionTouchRegion.invokeUnwrapped(caption) }
                .onFailure { onFailure("OUTSIDE_TAP_REFRESH_FAILED", it) }
        }

        fun rememberRegion(caption: Any, region: Region) {
            synchronized(lastCaptionRegions) { lastCaptionRegions[caption] = Region(region) }
        }

        /** 排障日志：关闭「详细日志」时连消息构造一并跳过。 */
        private fun diag(code: () -> String) {
            io.github.hoerire.flymefreeform.log.ModuleLog.debug("FFOUT", code)
        }

        fun markProtected(caption: Any, task: Any) {
            synchronized(captions) { captions[caption] = Unit }
            synchronized(protectedTasks) { protectedTasks[task] = Unit }
        }

        fun clearProtection(caption: Any, reason: String = ""): ProtectedTouchableRegion? {
            if (reason.isNotEmpty()) diag { "REGION_FAIL_" + reason }
            val task = captionTaskField.get(caption)
            if (task != null) synchronized(protectedTasks) { protectedTasks.remove(task) }
            return null
        }

        private fun isProtected(task: Any): Boolean =
            synchronized(protectedTasks) { protectedTasks.containsKey(task) }

        private fun allFlexibleTaskBounds(controller: Any): List<Rect> {
            val tasks = flexibleTasksField.get(controller) ?: return emptyList()
            val taskSnapshot =
                synchronized(tasks) {
                    (tasks as? Iterable<*>)?.filterNotNull()?.toList().orEmpty()
                }
            return taskSnapshot.mapNotNull { flexibleTask ->
                visibleTaskBounds(controller, flexibleTask)?.takeUnless { it.isEmpty }
            }
        }

        private fun visibleTaskBounds(controller: Any, task: Any): Rect? {
            // 输入法会单独平移、裁短标题层，Task 原始边界仍可能保持弹出前的尺寸。
            val imeBounds = (inputMethodTaskBounds.get(task) as? Rect)?.let(::Rect)
            if (imeBounds != null && !imeBounds.isEmpty) return imeBounds
            return (getVisibleBounds.invokeUnwrapped(controller, task) as? Rect)?.let(::Rect)
        }

        private fun visibleImeRegion(displayContent: Any): Region? {
            val imeWindow = inputMethodWindow.get(displayContent) ?: return null
            if (isWindowVisible.invokeUnwrapped(imeWindow) != true) return null
            return Region().also { getTouchableRegion.invokeUnwrapped(imeWindow, it) }
        }

        private fun screenToCaptionRegion(
            screenRegion: Region,
            visibleBounds: Rect,
            scaleX: Float,
            scaleY: Float,
        ): Region {
            val result = Region()
            val iterator = RegionIterator(screenRegion)
            val screenRect = Rect()
            while (iterator.next(screenRect)) {
                val localRect =
                    Rect(
                        floor((screenRect.left - visibleBounds.left) / scaleX).toInt(),
                        floor((screenRect.top - visibleBounds.top) / scaleY).toInt(),
                        ceil((screenRect.right - visibleBounds.left) / scaleX).toInt(),
                        ceil((screenRect.bottom - visibleBounds.top) / scaleY).toInt(),
                    )
                if (!localRect.isEmpty) result.op(localRect, Region.Op.UNION)
            }
            return result
        }

        fun beforeOriginal(
            listener: Any,
            event: MotionEvent,
            settings: io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot,
        ): Any? =
            synchronized(engines) {
                val engine = engines.getOrPut(listener) { OutsideTapGestureEngine() }
                val mode =
                    if (settings.enabled && environmentAllowed()) settings.outsideTapCloseMode
                    else OutsideTapCloseMode.Disabled
                engine.updateMode(mode)
                if (mode == OutsideTapCloseMode.Disabled) return@synchronized null
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_CANCEL,
                    -> {
                        engine.interrupt()
                        null
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!engine.isTracking) return@synchronized null
                        val controller = controllerField.get(listener) ?: run {
                            engine.interrupt()
                            return@synchronized null
                        }
                        updateOutsideTap(engine, controller, event)
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!engine.isTracking) {
                            engine.interrupt()
                            return@synchronized null
                        }
                        val controller = controllerField.get(listener) ?: run {
                            engine.interrupt()
                            return@synchronized null
                        }
                        finishOutsideTap(engine, controller, event)
                    }
                    MotionEvent.ACTION_DOWN -> {
                        val controller = controllerField.get(listener) ?: run {
                            engine.interrupt()
                            return@synchronized null
                        }
                        beginOutsideTap(engine, controller, event, mode)
                    }
                    else -> null
                }
            }

        fun interrupt(listener: Any) {
            synchronized(engines) { engines[listener]?.interrupt() }
        }

        fun interruptAll() {
            synchronized(engines) { engines.values.forEach(OutsideTapGestureEngine::interrupt) }
        }

        /**
         * 关闭兜底。
         *
         * 触摸区扩大之后，系统自己就会处理「点窗外关闭」；此前这里又立即调了一次
         * `exitFlexibleTask`，等于同一个窗口被退出两次，表现为关闭瞬间画面闪很多下
         * （从浮标恢复的小窗尤其明显）。现在改为：先让系统关，延迟一小段再检查，
         * 只有窗口仍然存在时才补一次关闭。
         */
        /**
         * 「底部关闭」：处理 FlexibleCaptionView 的触摸分发。
         *
         * hook 点与 com.oupao.wiz 一致（它挂的也是 caption 自己的 dispatchTouchEvent）。
         * 不能改挂 android.view.View#onTouchEvent：那是所有控件共用的方法，
         * 等于给整机每个 View 的触摸分发插一层，会拖垮 system_server。
         */
        fun onCaptionTouch(caption: Any, event: MotionEvent) {
            val settings = settingsProvider() ?: return
            if (settings.outsideTapCloseMode != OutsideTapCloseMode.BottomTap) {
                handleTapTask = null
                return
            }
            val task = captionTaskField.get(caption) ?: return
            val controller = captionControllerField.get(caption) ?: return
            if (!isOrdinaryZoom(controller, task)) {
                handleTapTask = null
                return
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleTapTask = task
                    handleDownX = event.rawX
                    handleDownY = event.rawY
                    handleDownAt = event.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val active = handleTapTask
                    handleTapTask = null
                    if (active !== task) return
                    // 与 oupao 一致：抬手超过 400ms 视为长按，不做点击处理。
                    if (event.eventTime - handleDownAt > TAP_MAX_DURATION_MS) return
                    val bounds = visibleTaskBounds(controller, task) ?: return
                    if (bounds.isEmpty) return
                    if (!inStrip(handleDownX, handleDownY, bounds, controller)) return
                    if (!inStrip(event.rawX, event.rawY, bounds, controller)) return
                    val density = (contextField.get(controller) as? Context)
                        ?.resources?.displayMetrics?.density ?: 1f
                    val dx = event.rawX - handleDownX
                    val dy = event.rawY - handleDownY
                    // 向上移动即上滑手势（变迷你窗）：轻扫特征为位移小、速度快，
                    // 单靠位移阈值拦不住，必须单独看方向，否则上滑会被当成点击关窗。
                    if (handleDownY - event.rawY > TAP_UPWARD_GUARD_DP * density) return
                    val slop = (TAP_MAX_DRIFT_DP * density)
                    if (dx * dx + dy * dy > slop * slop) return
                    io.github.hoerire.flymefreeform.log.ModuleLog.info("BOTTOM_CLOSE_TAP")
                    mainHandler.post { runCatching { closeTask(controller, task, caption) } }
                }
                MotionEvent.ACTION_CANCEL -> handleTapTask = null
            }
        }

        /**
         * 点是否落在小窗底部的小白条带内。
         *
         * 参数取自 oupao.wiz 的 inStrip：底部 32dp 高、水平两侧各让出 15%
         * 再各放宽 6dp，避免窗口下沿左右角被误判成横条。
         */
        private fun inStrip(x: Float, y: Float, bounds: Rect, controller: Any): Boolean {
            if (bounds.width() <= 0 || bounds.height() <= 0) return false
            val density = (contextField.get(controller) as? Context)
                ?.resources?.displayMetrics?.density ?: 1f
            val height = (STRIP_HEIGHT_DP * density).toInt()
            val slack = (STRIP_SLACK_DP * density).toInt()
            val sideMargin = bounds.width() * 15 / 100
            if (x < bounds.left + sideMargin - slack || x > bounds.right - sideMargin + slack) {
                return false
            }
            return y >= bounds.bottom - height && y <= bounds.bottom + slack
        }

        fun closeIfStillValid(listener: Any, task: Any) {
            if (!environmentAllowed()) return
            val controller = controllerField.get(listener) ?: return
            closeTask(controller, task, captionForTask(task))
        }

        /**
         * 关闭小窗，按 com.oupao.wiz 的链路（它已验证稳定）：
         *
         * 1. 去重：同一窗口短时间内只关一次；
         * 2. handleCloseEventForLightOpenIfNeed(caption)：先让"轻量打开"状态结清；
         * 3. 构造 ExitFlexibleTaskToBackStrategy(task, true) 并 setSourceFrom(1)——
         *    告诉系统这次退出的来源，否则会落到强制动画分支；
         * 4. exitFlexibleTaskWindowInnerLocked(strategy)；
         * 5/6. 失败时退回 View 层：mCloseView.performClick()，再失败合成点击。
         *
         * 缺 2、3 两步直接调 WMS，窗口状态会和系统不一致，反复关闭会拖垮 system_server。
         */
        fun closeTask(controller: Any, task: Any, caption: Any?) {
            if (!environmentAllowed()) return
            if (getTopZoomTask.invokeUnwrapped(controller) !== task) return
            if (!isOrdinaryZoom(controller, task)) return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastCloseAt < CLOSE_DEBOUNCE_MS) return
            lastCloseAt = now
            val byStrategy = runCatching {
                // 2. 让轻量打开状态结清
                if (caption != null) {
                    controller.javaClass.methods
                        .firstOrNull {
                            it.name == "handleCloseEventForLightOpenIfNeed" && it.parameterTypes.size == 1
                        }
                        ?.let { method ->
                            method.isAccessible = true
                            runCatching { method.invoke(controller, caption) }
                        }
                }
                // 3. 策略 + setSourceFrom(1)
                val loader = controller.javaClass.classLoader
                val strategyClass =
                    Class.forName("com.android.server.wm.ExitFlexibleTaskToBackStrategy", false, loader)
                val constructor = strategyClass.declaredConstructors.firstOrNull { candidate ->
                    candidate.parameterTypes.size == 2 &&
                        candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType
                } ?: return@runCatching false
                constructor.isAccessible = true
                val strategy = constructor.newInstance(task, true)
                strategyClass.methods
                    .firstOrNull { it.name == "setSourceFrom" && it.parameterTypes.size == 1 }
                    ?.let { setter ->
                        setter.isAccessible = true
                        runCatching { setter.invoke(strategy, 1) }
                    }
                // 4. 原生退出
                val baseClass =
                    Class.forName("com.android.server.wm.AbsFlexibleTaskExitStrategy", false, loader)
                val inner = controller.javaClass.getMethod("exitFlexibleTaskWindowInnerLocked", baseClass)
                inner.isAccessible = true
                inner.invoke(controller, strategy)
                true
            }.getOrDefault(false)
            io.github.hoerire.flymefreeform.log.ModuleLog.info(
                "BOTTOM_CLOSE_STRATEGY_" + byStrategy,
            )
            if (!byStrategy) closeByViewLayer(caption)
        }

        /**
         * 回退到 View 层关闭：点系统自己的关闭按钮。
         *
         * 这条路完全不碰 WMS，最安全；performClick 无效时再合成一次 DOWN/UP。
         */
        private fun closeByViewLayer(caption: Any?) {
            val closeView = caption?.let { cap ->
                runCatching {
                    cap.javaClass
                        .getDeclaredField("mCloseView")
                        .apply { isAccessible = true }
                        .get(cap) as? View
                }.getOrNull()
            } ?: return
            val clicked = runCatching { closeView.performClick() }.getOrDefault(false)
            io.github.hoerire.flymefreeform.log.ModuleLog.info("BOTTOM_CLOSE_PERFORM_CLICK_" + clicked)
            if (clicked) return
            runCatching {
                val location = IntArray(2)
                closeView.getLocationInWindow(location)
                val x = location[0] + closeView.width / 2f
                val y = location[1] + closeView.height / 2f
                val now = android.os.SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0)
                closeView.dispatchTouchEvent(down)
                closeView.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                io.github.hoerire.flymefreeform.log.ModuleLog.info("BOTTOM_CLOSE_SYNTHETIC_CLICK")
            }.onFailure {
                io.github.hoerire.flymefreeform.log.ModuleLog.warn("BOTTOM_CLOSE_CLICK_FAILED", it)
            }
        }
        private fun beginOutsideTap(
            engine: OutsideTapGestureEngine,
            controller: Any,
            event: MotionEvent,
            mode: OutsideTapCloseMode,
        ): Any? {
            if (
                mode == OutsideTapCloseMode.Disabled ||
                    isCanRespondEvent.invokeUnwrapped(controller) != true ||
                    event.pointerCount != 1 ||
                    event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER ||
                    !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
            ) {
                engine.interrupt()
                return null
            }
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: run {
                engine.interrupt()
                return null
            }
            if (!isOrdinaryZoom(controller, task) || !isOutsideEligibleRegion(controller, task, event)) {
                engine.interrupt()
                return null
            }
            // 只有标题输入层已从 DOWN 起接管窗外区域时才启用关闭判定。
            // 系统通道（通知中心、控制中心、横幅）拉起的小窗，触摸区在窗口仍为全屏时
            // 已更新一次，窗外区域为空，不产生保护标记。防穿透取决于触摸区扩展本身，
            // 与该判定无关，因此未标记保护的小窗同样允许窗外关闭。
            val protected = isProtected(task)
            io.github.hoerire.flymefreeform.log.ModuleLog.debug("FFOUT") {
                "OUTSIDE_TAP_DOWN_protected_" + protected
            }
            engine.begin(
                task = task,
                pointerId = event.getPointerId(0),
                x = event.rawX,
                y = event.rawY,
                eventTime = event.eventTime,
                captured = true,
            )
            return null
        }

        private fun updateOutsideTap(
            engine: OutsideTapGestureEngine,
            controller: Any,
            event: MotionEvent,
        ): Any? {
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: run {
                engine.interrupt()
                return null
            }
            if (!isOrdinaryZoom(controller, task)) {
                engine.interrupt()
                return null
            }
            val pointerId = event.getPointerId(0)
            engine.move(
                task = task,
                pointerId = pointerId,
                pointerCount = event.pointerCount,
                x = event.getRawX(0),
                y = event.getRawY(0),
                touchSlop = viewConfiguration(controller).scaledTouchSlop.toFloat(),
            )
            return null
        }

        private fun finishOutsideTap(
            engine: OutsideTapGestureEngine,
            controller: Any,
            event: MotionEvent,
        ): Any? {
            if (event.pointerCount == 0) {
                engine.interrupt()
                return null
            }
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: run {
                engine.interrupt()
                return null
            }
            if (!isOrdinaryZoom(controller, task)) {
                engine.interrupt()
                return null
            }
            val configuration = viewConfiguration(controller)
            val pointerIndex = event.actionIndex
            val shouldClose =
                engine.finish(
                    task = task,
                    pointerId = event.getPointerId(pointerIndex),
                    x = event.getRawX(pointerIndex),
                    y = event.getRawY(pointerIndex),
                    eventTime = event.eventTime,
                    longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong(),
                    touchSlop = configuration.scaledTouchSlop.toFloat(),
                    doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong(),
                    doubleTapSlop = configuration.scaledDoubleTapSlop.toFloat(),
                )
            return task.takeIf { shouldClose }
        }

        /**
         * 是否为普通小窗。
         *
         * 仅状态 1 的窗口参与窗外关闭。游戏助手拉起的悬浮窗口不处于任何柔性状态，
         * 由系统自行管理触摸与收起，模块不介入。
         */
        private fun isOrdinaryZoom(controller: Any, task: Any): Boolean =
            isTaskInFlexibleState.invokeUnwrapped(controller, task, ORDINARY_ZOOM_STATE) == true

        private fun isOutsideEligibleRegion(controller: Any, task: Any, event: MotionEvent): Boolean {
            val pointX = event.rawX.toInt()
            val pointY = event.rawY.toInt()
            val context = contextField.get(controller) as? Context ?: return false
            if (pointY < statusBarHeight(context)) return false
            val displayContent = getDisplayContent.invokeUnwrapped(task) ?: return false
            if (isIgnoreExpandRegion.invokeUnwrapped(controller, displayContent) == true) return false
            if (hasMenuShowing.invokeUnwrapped(controller) == true) return false
            if (isInsideVisibleIme(displayContent, pointX, pointY)) return false
            val excludedRegion =
                updateTapExcludeRegion.invokeUnwrapped(controller, displayContent, null) as? Region
            if (excludedRegion?.contains(pointX, pointY) == true) return false
            if (hasTouchableTask.invokeUnwrapped(controller, pointX, pointY) == true) return false
            val visibleBounds = visibleTaskBounds(controller, task) ?: return false
            return !visibleBounds.isEmpty && !visibleBounds.contains(pointX, pointY)
        }

        private fun isInsideVisibleIme(displayContent: Any, x: Int, y: Int): Boolean {
            val imeWindow = inputMethodWindow.get(displayContent) ?: return false
            if (isWindowVisible.invokeUnwrapped(imeWindow) != true) return false
            val touchRegion = Region()
            getTouchableRegion.invokeUnwrapped(imeWindow, touchRegion)
            return touchRegion.contains(x, y)
        }

        private fun viewConfiguration(controller: Any): ViewConfiguration {
            val context = contextField.get(controller) as? Context
                ?: throw IllegalStateException("Flexible task context unavailable")
            return ViewConfiguration.get(context)
        }

        // 此处运行于系统输入回调，无附着 View；保留厂商状态栏资源作为顶部排除区。
        @SuppressLint("InternalInsetResource", "DiscouragedApi")
        private fun statusBarHeight(context: Context): Int {
            val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        }

        companion object {
            /** 当前配置快照；由外层在安装时注入，避免反射类持有 configuration。 */
            @Volatile
            var settingsProvider: () -> io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot? =
                { null }
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"

        /** 同一次点击内的重复关闭抑制窗口。 */
        const val CLOSE_DEBOUNCE_MS = 400L

        /** 关闭后这段时间内不改写触摸区域：留给系统做剩余浮标的重排。 */
        const val REGION_QUIET_MS = 900L



        const val TOUCH_LISTENER_CLASS =
            "com.android.server.wm.FlexibleTaskController\$TouchListener"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val ABS_EXIT_STRATEGY_CLASS = "com.android.server.wm.AbsFlexibleTaskExitStrategy"
        const val EXIT_TO_FLOAT_STRATEGY_CLASS = "com.android.server.wm.ExitFlexibleTaskToFloatStrategy"
        const val EXIT_TO_BACK_STRATEGY_CLASS = "com.android.server.wm.ExitFlexibleTaskToBackStrategy"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val TASK_EXT_CLASS = "com.android.server.wm.TaskExtImpl"
        const val DISPLAY_CONTENT_CLASS = "com.android.server.wm.DisplayContent"
        const val WINDOW_STATE_CLASS = "com.android.server.wm.WindowState"
        const val FLEXIBLE_CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleCaptionView"
        const val ORDINARY_ZOOM_STATE = 1

        /** 底部小白条的高度（dp）与水平余量，取自 oupao.wiz 的 inStrip。 */
        const val STRIP_HEIGHT_DP = 32f
        const val STRIP_SLACK_DP = 6f

        /** 点击判定：抬手超过这个时长算长按，位移超过这个距离算滑动。 */
        const val TAP_MAX_DURATION_MS = 400L
        const val TAP_MAX_DRIFT_DP = 8f

        /**
         * 向上移动超过这个距离就判为上滑（变迷你窗）。
         *
         * 上滑是速度触发，位移往往很小，用 TAP_MAX_DRIFT_DP 拦不住；
         * 只要方向朝上就先放行，避免把迷你窗手势当成点击。
         */
        const val TAP_UPWARD_GUARD_DP = 4f
        const val SCALE_TOLERANCE = 0.02f
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}

private data class ProtectedTouchableRegion(
    val task: Any,
    val region: Region,
)

private fun Class<*>.requiredField(name: String): Field {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException(name)
}

private fun Class<*>.requiredMethod(name: String, parameterCount: Int): Method {
    var current: Class<*>? = this
    while (current != null) {
        current.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        }?.let { return it.apply { isAccessible = true } }
        current = current.superclass
    }
    throw NoSuchMethodException("$name/$parameterCount")
}

private fun Class<*>.requiredMethod(name: String, parameterTypes: Array<Class<*>>): Method {
    var current: Class<*>? = this
    while (current != null) {
        try {
            return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: NoSuchMethodException) {
            current = current.superclass
        }
    }
    throw NoSuchMethodException(name)
}

private fun Method.invokeUnwrapped(instance: Any?, vararg arguments: Any?): Any? =
    try {
        invoke(instance, *arguments)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }

