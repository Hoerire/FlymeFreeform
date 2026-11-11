package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
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
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.gesture.OutsideTapGestureEngine
import io.github.hoerire.flymefreeform.logic.CloseDecision
import io.github.hoerire.flymefreeform.logic.ClosePolicy
import io.github.hoerire.flymefreeform.logic.LaunchInFlight
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
                            module.log(Log.DEBUG, TAG, "OUTSIDE_TAP_CLOSE_FIRED", null)
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
            module.log(Log.INFO, TAG, "OUTSIDE_TAP_INPUT_REGION_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "OUTSIDE_TAP_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "OUTSIDE_TAP_TARGET_LINKAGE_FAILED", exception)
        }
        // 独立安装：任何失败都只影响"替换转关闭"这一项，不能连累窗外关闭。
        runCatching { installReplaceToClose(classLoader) }
            .onFailure {
                module.log(
                    Log.WARN,
                    TAG,
                    "ZOOM_REPLACE_TO_CLOSE_FAILED_" + it.javaClass.simpleName + "_" + it.message,
                    it,
                )
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
                    module.log(Log.WARN, TAG, "ZOOM_REPLACE_TO_CLOSE_NO_METHOD", null)
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
                    module.log(Log.WARN, TAG, "ZOOM_REPLACE_TO_CLOSE_NO_CTOR", null)
                    return
                }
        module
            .hook(innerLocked)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.system.replace_to_close")
            .intercept { chain ->
                if (!configuration.snapshot.enabled) return@intercept chain.proceed()
                val strategy = chain.args.getOrNull(0)
                val isFloatExit = strategy != null && floatStrategyClass.isInstance(strategy)
                // 开关状态：仅在"确实是一次转浮标退出"且快照为关闭时才重读磁盘配置，
                // 避免把预算浪费在无关退出上。
                if (isFloatExit && !configuration.snapshot.autoCloseWindowEffective && reloadBudget > 0) {
                    reloadBudget--
                    configuration.forceReload()
                }
                if (isFloatExit && configuration.snapshot.autoCloseWindowEffective) {
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
                    // 不再判断"这次退出是不是启动引起的"：入口太多（扇形、更多面板、桌面、
                    // 通知、应用内跳转），而用户的规则只有一条——不允许窗口被收成贴边浮标。
                    // 判据只看这次退出是不是系统自己的"该谁退出"裁决（determine*/updateTaskMaxNum*），
                    // 用户拖到边缘走的拖拽入口不经过这里。
                    // 只有"被新应用顶掉"才收窗：最近 1.5 秒内有界面启动。迷你窗变形
                    //（ColorOS 17 也走同一套退场裁决）没有启动发生，保持系统原策略。
                    // 一次启动只收一个窗口：命中即失效，避免随后的"划成迷你窗"复用同一个标记。
                    val launching = LaunchInFlight.consume()
                    val decision = if (!launching) {
                        CloseDecision.KeepOriginal
                    } else {
                        ClosePolicy.decide(
                            autoCloseEnabled = true,
                            isFloatExitStrategy = isFloatExit,
                            fromSystemVerdict = fromLimit,
                        )
                    }
                    // 常驻诊断：每次"要收浮标"的退出都留一行，便于确认是否被接管。
                    module.log(
                        Log.DEBUG,
                        TAG,
                        "ZOOM_CLOSE_POLICY_" + decision.name + "_limit" + (if (fromLimit) 1 else 0) +
                            "_launch" + (if (launching) 1 else 0),
                        null,
                    )
                    if (decision == CloseDecision.ReplaceWithToBack) {
                        val current = strategy ?: return@intercept chain.proceed()
                        val task = runCatching {
                            fieldInHierarchy(current.javaClass, "mTask")?.get(current)
                        }.getOrNull()
                        val back = task?.let { runCatching { backConstructor.newInstance(it, true) }.getOrNull() }
                        if (task != null && back != null) {
                            module.log(Log.DEBUG, TAG, "ZOOM_REPLACE_TO_CLOSE", null)
                            // 同帧替换为 ToBack，但不再隐藏 surface：退场动画交给系统照常播完。
                            return@intercept chain.proceed(arrayOf(back))
                        }
                    }
                }
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "ZOOM_REPLACE_TO_CLOSE_INSTALLED", null)
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
        module.log(Log.WARN, TAG, code, throwable)
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

        // 底部横条点击：命中后单击直接关窗，独立于双击/单击关闭那套判定。
        // 这里刻意不做任何防穿透处理——横条本身就在窗内，点击照常交给窗口。
        private var handleTapTask: Any? = null
        private var handleTapDownX = 0f
        private var handleTapDownY = 0f
        private var handleTapDownTime = 0L

        private val contextField = controllerClass.requiredField("mContext")
        private val flexibleTasksField = controllerClass.requiredField("mFlexibleTasks")
        private val captionTaskField = captionClass.requiredField("mTask")
        private val captionControllerField = captionClass.requiredField("mFlexibleTaskController")
        private val inputMethodWindow =
            ColorOsDisplayContentImeAccess(displayContentClass, windowStateClass)
        private val inputMethodTaskBounds =
            ColorOsTaskImeBoundsAccess(taskClass, taskExtClass, Rect::class.java)
        private val updateCaptionTouchRegion = captionClass.requiredMethod("updateTouchRegion", 0)

        /**
         * 小窗底部横条的高度（dp），取自 FlexibleCaptionView 自己的常量。
         *
         * 用它而不是写死一个值：横条高度随系统版本/控件实现变化，读常量才能对上
         * 用户实际看到的那一条。读不到时退回 28dp。
         */
        private val bottomHandleHeightDp: Int =
            runCatching {
                captionClass.getDeclaredField("BOTTOM_FRAME_HIGHT_DP").apply { isAccessible = true }
                    .getInt(null)
            }.getOrDefault(28)
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
                        cancelHandleTap()
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
                        val controller = controllerField.get(listener) ?: run {
                            cancelHandleTap()
                            engine.interrupt()
                            return@synchronized null
                        }
                        // 底部横条点击：单击即关窗，不走窗外那套双击判定。
                        if (finishHandleTap(controller, event)) {
                            engine.interrupt()
                            return@synchronized null
                        }
                        if (!engine.isTracking) {
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
                        // 底部关闭优先：命中后这次手势只用于点底部关窗，不进入窗外判定。
                        if (beginHandleTap(controller, event, settings) != null) {
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
         * 我们把触摸区扩大之后，系统自己就会处理「点窗外关闭」；此前这里又立即调了一次
         * `exitFlexibleTask`，等于同一个窗口被退出两次，表现为关闭瞬间画面闪很多下
         * （从浮标恢复的小窗尤其明显）。现在改为：先让系统关，延迟一小段再检查，
         * 只有窗口仍然存在时才补一次关闭。
         */
        fun closeIfStillValid(listener: Any, task: Any) {
            val controller = controllerField.get(listener) ?: return
            closeTask(controller, task)
        }

        /** 执行关闭；调用方保证 controller/task 来源正确。 */
        fun closeTask(controller: Any, task: Any) {
            if (!environmentAllowed()) return
            if (getTopZoomTask.invokeUnwrapped(controller) !== task || !isOrdinaryZoom(controller, task)) {
                return
            }
            // 优先走系统关闭按钮的入口：构造退出策略 → exitFlexibleTaskWindowInnerLocked。
            // 直接调 exitFlexibleTask(task,true,0,0) 会落到强制动画分支，
            // 从浮标恢复的窗口会逐帧重排（每秒几十次 relayout，表现为闪）。
            val byStrategy = runCatching {
                val loader = controller.javaClass.classLoader
                val strategyClass = Class.forName("com.android.server.wm.ExitFlexibleTaskToBackStrategy", false, loader)
                val constructor = strategyClass.declaredConstructors.firstOrNull { candidate ->
                    candidate.parameterTypes.size == 2 &&
                        candidate.parameterTypes[1] == Boolean::class.javaPrimitiveType
                } ?: return@runCatching false
                constructor.isAccessible = true
                val strategy = constructor.newInstance(task, true)
                val baseClass = Class.forName("com.android.server.wm.AbsFlexibleTaskExitStrategy", false, loader)
                val inner = controller.javaClass.getMethod("exitFlexibleTaskWindowInnerLocked", baseClass)
                inner.isAccessible = true
                inner.invoke(controller, strategy)
                true
            }.getOrDefault(false)
            io.github.hoerire.flymefreeform.log.ModuleLog.debug("FFOUT") {
                "OUTSIDE_TAP_CLOSE_STRATEGY_OK_" + byStrategy
            }
            if (!byStrategy) exitFlexibleTask.invokeUnwrapped(controller, task, true, 0, 0)
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

        /** 坐标是否落在小窗底部横条上（窗内底部若干 dp）。 */
        fun isOnBottomHandle(controller: Any, task: Any, x: Float, y: Float): Boolean {
            val bounds = visibleTaskBounds(controller, task) ?: return false
            if (bounds.isEmpty) return false
            val density = (contextField.get(controller) as? Context)
                ?.resources?.displayMetrics?.density ?: 1f
            val handleHeight = (bottomHandleHeightDp * density).toInt().coerceAtLeast(1)
            val px = x.toInt()
            val py = y.toInt()
            if (px < bounds.left || px > bounds.right) return false
            return py >= bounds.bottom - handleHeight && py <= bounds.bottom
        }

        /**
         * 记录一次「底部关闭」的按下；返回 task 表示这次手势按在小窗底部。
         *
         * 只在设置开启时介入。这个功能不做任何防穿透处理：底部那一条本来就是窗口
         * 自己的装饰区域，点击照常交给窗口，我们只在抬手确认是短点击后关窗。
         */
        fun beginHandleTap(
            controller: Any,
            event: MotionEvent,
            settings: io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot,
        ): Any? {
            if (!settings.bottomCloseEffective) return null
            val task = getTopZoomTask.invokeUnwrapped(controller) ?: return null
            if (!isOrdinaryZoom(controller, task)) return null
            if (!isOnBottomHandle(controller, task, event.rawX, event.rawY)) return null
            handleTapTask = task
            handleTapDownX = event.rawX
            handleTapDownY = event.rawY
            handleTapDownTime = event.eventTime
            return task
        }

        /** 抬手判定：短点击且没移动就关闭；返回 true 表示这次手势已消费。 */
        fun finishHandleTap(controller: Any, event: MotionEvent): Boolean {
            val task = handleTapTask ?: return false
            handleTapTask = null
            if (!isOrdinaryZoom(controller, task)) return true
            val configuration = viewConfiguration(controller)
            val slop = configuration.scaledTouchSlop.toFloat()
            val dx = event.rawX - handleTapDownX
            val dy = event.rawY - handleTapDownY
            val moved = dx * dx + dy * dy > slop * slop
            val tooLong = event.eventTime - handleTapDownTime > ViewConfiguration.getLongPressTimeout()
            if (!moved && !tooLong) {
                io.github.hoerire.flymefreeform.log.ModuleLog.info("BOTTOM_HANDLE_CLOSE")
                closeTask(controller, task)
            }
            return true
        }

        fun cancelHandleTap() {
            handleTapTask = null
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

