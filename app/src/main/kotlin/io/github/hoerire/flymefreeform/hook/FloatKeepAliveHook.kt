package io.github.hoerire.flymefreeform.hook

import android.content.res.Configuration
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 「贴边保活」：小窗收成贴边浮标后，不把它的任务停掉。
 *
 * 原厂把窗口收成浮标时会把任务移回后台（`Task.moveTaskToBack`），可见性遍历随后让
 * ActivityRecord 走 `makeInvisible()`，状态落到 STOPPED；进程变成 cached，稍后即被冻结
 * 或回收，正在下载/更新的应用也就停了。
 *
 * 这里只做一件事：浮标列表里的任务不参与这次可见性收尾——跳过 `makeInvisible()`、
 * 拒绝把 visibleRequested 改回 false、拦掉原厂那次显式的 `addToStopping`，并挡住到
 * STOPPING/STOPPED 的状态迁移。ActivityRecord 因此维持「可见但未置顶」的状态，进程
 * 留在 perceptible 档，不会被冻结。窗口本身仍由原厂隐藏，用户只看到浮标。
 */
internal class FloatKeepAliveHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private var getInstanceMethod: Method? = null
    private var isInFloatingListMethod: Method? = null
    private val loggedTasks = ConcurrentHashMap<Int, Boolean>()

    /** 横竖屏切换期间为 true：这时可见性一律放行，避免浮标里的窗口被重新显示出来。 */
    private val rotationGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 浮标任务 -> ActivityRecord，旋转时按它主动把可见性收回去。 */
    private val rotationRecords = ConcurrentHashMap<Int, WeakReference<Any>>()
    private val rotationLogged = ConcurrentHashMap<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private val releasedTasks = ConcurrentHashMap<String, Boolean>()

    fun install(classLoader: ClassLoader) {
        val recordClass =
            runCatching { classLoader.loadClass(RECORD_CLASS) }.getOrElse {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_RECORD_UNAVAILABLE", it)
                return
            }
        resolveController(classLoader)

        installWithoutVisibility(recordClass)
        installVisibleRequested(recordClass)
        installStateGuard(recordClass)
        installStopGuard(recordClass)
        installPauseSkip(recordClass, classLoader)
        installPauseTimeout(recordClass)
        installRotationGuard(classLoader)
        installHandleObserver(classLoader)
        installKeyguardSnapshot(classLoader)
        installFullScreenResetGuard(classLoader)
        installAudioFocusGuard(classLoader)
        installAwakeGuard(classLoader)
        installScreenOffGuard(classLoader)
        installClientVisibleKeepOn(classLoader)
        installDisplayStateGuard(classLoader)
        installKeyguardLockGuard(classLoader)
        installIdleExemption()
        ModuleLog.install("FLOAT_KEEP_ALIVE_HOOK_INSTALLED")
    }

    /**
     * 浮标任务不做可见性收尾：保持「可见但未置顶」，进程不掉档。
     *
     * 代价是锁屏/旋转/切换应用时原厂会按「任务仍可见」重排，窗口可能被重新提交出来；
     * 那几处由 [shouldKeepVisible] 与旋转守卫放行，见各类注释。
     */
    private fun installWithoutVisibility(recordClass: Class<*>) {
        val method =
            findMethod(recordClass, "makeInvisible") ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_MAKE_INVISIBLE")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.make_invisible")
            .intercept { chain ->
                if (shouldKeepVisible(chain.thisObject)) {
                    logSkip(chain.thisObject, "invisible")
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    private fun installVisibleRequested(recordClass: Class<*>) {
        val method =
            findMethod(recordClass, "setVisibleRequested", Boolean::class.javaPrimitiveType!!) ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_VISIBLE_REQUESTED")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.visible_requested")
            .intercept { chain ->
                val visible = chain.getArg(0) as? Boolean
                if (visible == false && shouldKeepVisible(chain.thisObject)) {
                    logSkip(chain.thisObject, "visible_requested")
                    chain.proceed(arrayOf<Any?>(true))
                } else {
                    if (visible == false && shouldKeep(chain.thisObject)) {
                        logRelease(chain.thisObject, "visible_requested")
                    }
                    if (visible == true && shouldKeep(chain.thisObject)) {
                        logRaised(chain.thisObject)
                    }
                    chain.proceed()
                }
            }
    }

    private fun installStateGuard(recordClass: Class<*>) {
        val stateClass =
            runCatching { recordClass.classLoader?.loadClass("$RECORD_CLASS\$State") ?: Class.forName("$RECORD_CLASS\$State") }.getOrNull()
                ?: return
        val method =
            findMethod(recordClass, "setState", stateClass, String::class.java) ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_SET_STATE")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.set_state")
            .intercept { chain ->
                val state = (chain.getArg(0) as? Enum<*>)?.name
                if (state in STOP_STATES && shouldKeep(chain.thisObject)) {
                    logSkip(chain.thisObject, "state_$state")
                    null
                } else {
                    if (state != null && shouldKeep(chain.thisObject)) {
                        logStateOnce(chain.thisObject, state)
                    }
                    chain.proceed()
                }
            }
    }

    /**
     * 挡下停止：ActivityRecord 的所有 `addToStopping` 重载都挂上。
     *
     * 签名会随 ROM 版本变化（有的机器是 `(boolean, String)`，有的多了个 boolean），
     * 按名字遍历全部 void 重载，避免某台机器上这道保护静默缺失。
     */
    private fun installStopGuard(recordClass: Class<*>) {
        val overloads =
            generateSequence(recordClass as Class<*>?) { it.superclass }
                .flatMap { type -> type.declaredMethods.asSequence() }
                .filter { it.name == "addToStopping" && it.returnType == Void.TYPE }
                .distinctBy { method -> method.parameterTypes.joinToString { it.name } }
                .toList()
        if (overloads.isEmpty()) {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_ADD_TO_STOPPING")
            return
        }
        overloads.forEachIndexed { index, method ->
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.float_keep_alive.add_to_stopping_$index")
                .intercept { chain ->
                    if (shouldKeep(chain.thisObject)) {
                        logSkip(chain.thisObject, "add_to_stopping")
                        null
                    } else {
                        chain.proceed()
                    }
                }
        }
    }

    /**
     * 暂停请求不发给客户端：服务端照常进入 PAUSING，再由这里补一次 activityPaused
     * 让 AMS 完成暂停并继续恢复下一个应用；客户端收不到 onPause，画面与逻辑继续跑。
     */
    private fun installPauseSkip(recordClass: Class<*>, classLoader: ClassLoader) {
        val fragmentClass =
            runCatching { classLoader.loadClass(TASK_FRAGMENT_CLASS) }.getOrNull() ?: return
        val method =
            findMethod(
                fragmentClass,
                "schedulePauseActivity",
                recordClass,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
            ) ?: run {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_SCHEDULE_PAUSE")
                return
            }
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.schedule_pause")
            .intercept { chain ->
                val record = chain.getArg(0)
                if (shouldKeep(record) && scheduleFakePause(record)) {
                    logSkip(record, "pause")
                    markPausing(chain.thisObject, record)
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    private fun installPauseTimeout(recordClass: Class<*>) {
        val method = findMethod(recordClass, "schedulePauseTimeout") ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.pause_timeout")
            .intercept { chain ->
                if (shouldKeep(chain.thisObject)) {
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /**
     * 横竖屏切换：浮标里的窗口不能重新出现。
     *
     * 旋转时原厂会按「这个任务是否可见」重排浮动窗口，而保活为了进程档位一直把
     * ActivityRecord 按成可见，于是旋转一结束窗口就被重新提交出来。这里在配置变化
     * 开始时把浮标任务的可见性主动收回（守卫置位，可见性两道钩子整体放行），
     * 过渡结束 800ms 后再兜一次：仍是浮标却又是可见的，重新压下去。
     */
    private fun installRotationGuard(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull()
        if (flexibleClass != null) {
            val method =
                findMethod(
                    flexibleClass,
                    "onGlobalConfigurationChanged",
                    Int::class.javaPrimitiveType!!,
                    Configuration::class.java,
                    Configuration::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            if (method != null) {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.float_keep_alive.rotation_config")
                    .intercept { chain ->
                        val rotated = isRotationChange(chain.getArg(1), chain.getArg(2))
                        if (rotated) beginRotation()
                        val result = chain.proceed()
                        if (rotated) scheduleRotationEnd()
                        result
                    }
            } else {
                ModuleLog.warn("FLOAT_KEEP_ALIVE_NO_ROTATION_CONFIG")
            }
        }
        // 旋转过渡自身也有回调，作为配置变化之外的第二个入口。
        val floatClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
        if (floatClass != null) {
            findMethod(floatClass, "onDisplayRotationTransitionStarting")?.let { method ->
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.float_keep_alive.rotation_start")
                    .intercept { chain ->
                        beginRotation()
                        val result = chain.proceed()
                        scheduleRotationEnd()
                        result
                    }
            }
        }
    }

    /** 比较两份 Configuration 的窗口旋转值（windowConfiguration 是隐藏 API，走反射）。 */
    /**
     * 浮标被摘掉时记一行：息屏期间如果原厂把浮标收掉，这里能直接看到任务号与原因码。
     */
    private fun installHandleObserver(classLoader: ClassLoader) {
        val floatClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        val method = findMethod(floatClass, "removeFloatHandle", Int::class.javaPrimitiveType!!, Any::class.java, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.handle_removed")
            .intercept { chain ->
                val taskId = chain.getArg(0) as? Int ?: -1
                val flag = chain.getArg(3) as? Int ?: -1
                logHandleRemoved(taskId, flag, "public")
                chain.proceed()
            }
        // 内部入口：public 的那条只是转手，真正清场走这里。
        findMethod(
            floatClass,
            "removeFloatHandleInner",
            Int::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )?.let { inner ->
            module
                .hook(inner)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.float_keep_alive.handle_removed_inner")
                .intercept { chain ->
                    val taskId = chain.getArg(0) as? Int ?: -1
                    val flag = chain.getArg(2) as? Int ?: -1
                    logHandleRemoved(taskId, flag, "inner")
                    chain.proceed()
                }
        }
    }

    private fun logHandleRemoved(taskId: Int, flag: Int, source: String) {
        if (handleRemovedLogged.putIfAbsent(taskId.toString() + "/" + source, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_HANDLE_REMOVED_task" + taskId + "_flag" + flag + "_" + source)
    }

    /**
     * 解锁瞬间给每个浮标任务拍一张快照：还在不在浮标列表里、可见性是什么、进程状态如何。
     * 用来判断「解锁后自己变全屏」到底是浮标被摘了，还是窗口被抬起来了。
     */
    private fun installKeyguardSnapshot(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull() ?: return
        val method =
            findMethod(
                flexibleClass,
                "notifyKeyguardStateChanged",
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.unlock_snapshot")
            .intercept { chain ->
                val showing = chain.getArg(1) as? Boolean
                // 解锁和横竖屏一样，是一次整屏可见性重排：这时把守卫放下，窗口才不会被顶出来。
                if (showing == false) beginUnlockGuard()
                val result = chain.proceed()
                if (showing == false) {
                    scheduleUnlockGuardEnd()
                    handler.postDelayed({ snapshotTrackedTasks("unlock") }, 500L)
                } else {
                    handler.postDelayed({ snapshotTrackedTasks("lock") }, 500L)
                }
                result
            }
    }

    /** 解锁过渡期间为 true：可见性一律放行，避免浮标任务被重新提交成可见窗口。 */
    private val unlockGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    private val unlockLogged = ConcurrentHashMap<String, Boolean>()

    private fun beginUnlockGuard() {
        if (!unlockGuard.compareAndSet(false, true)) return
        rehideTracked("unlock")
    }

    private fun scheduleUnlockGuardEnd() {
        UNLOCK_CHECK_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                // 先压回去，再收回守卫，否则这次压回会被自己的钩子改回可见。
                rehideTracked("unlock" + (delay / 1000))
                if (delay == UNLOCK_CHECK_DELAYS_MS.last()) unlockGuard.set(false)
            }, delay)
        }
    }

    /** 仍是浮标、却被按成可见的任务，重新压回不可见，让它回到浮标状态。 */
    private fun rehideTracked(reason: String) {
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            if (readField(record, "mVisibleRequested") == true) {
                if (invoke(record, "setVisibleRequested", false)) {
                    logUnlockRehide(taskId, reason)
                }
            }
        }
    }

    private fun logUnlockRehide(taskId: Int, reason: String) {
        if (unlockLogged.putIfAbsent(taskId.toString() + "/" + reason, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_UNLOCK_task" + taskId + "_rehide_" + reason)
    }

    private fun snapshotTrackedTasks(phase: String) {
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            val floating = isFloating(taskId)
            val visibleRequested = readField(record, "mVisibleRequested")
            ModuleLog.info("FLOAT_KEEP_ALIVE_SNAPSHOT_" + phase + "_task" + taskId +
                    "_floating" + floating + "_visibleReq" + visibleRequested)
        }
    }

    private val handleRemovedLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 锁屏/息屏期间不让原厂把浮标任务退回全屏。
     *
     * 原厂在 `isAllowedToAdjustOptions()` 里有一条：锁屏状态下若目标任务是浮窗，直接把任务
     * `resetFlexibleTaskToFullScreen()`。正常使用没问题，但保活时应用在锁屏期间还在跑，
     * 它自己起一个新 Activity（例如短剧自动连播）就会命中这条，浮标被摘掉、任务变全屏，
     * 解锁后用户看到的是一屏全屏窗口。
     */
    private fun installFullScreenResetGuard(classLoader: ClassLoader) {
        val flexibleClass = runCatching { classLoader.loadClass(FLEXIBLE_CONTROLLER_CLASS) }.getOrNull() ?: return
        val taskClass = runCatching { classLoader.loadClass(TASK_CLASS) }.getOrNull() ?: return
        val method = findMethod(flexibleClass, "resetFlexibleTaskToFullScreen", taskClass, Int::class.javaPrimitiveType!!) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.fullscreen_reset")
            .intercept { chain ->
                val taskId = (readField(chain.getArg(0), "mTaskId") as? Int) ?: -1
                if (configuration.snapshot.floatKeepAliveEffective && isLockedOrOff(chain.thisObject) && isKeptFloat(taskId)) {
                    logResetSkipped(taskId)
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /** 保活负责的浮标任务：还在浮标列表里，或本轮保活记录过的（原厂可能已先摘了浮标）。 */
    private fun isKeptFloat(taskId: Int): Boolean = taskId > 0 && (isFloating(taskId) || rotationRecords[taskId]?.get() != null)

    /**
     * 保活期间放行音频焦点。
     *
     * ColorOS 的音频加固（`HardeningEnforcer.blockFocusMethod`）不允许后台应用申请音频焦点
     * （AppOps 只给前台，`level: partial` / `exemption: 6`）。应用贴边成浮标后就是后台，
     * 换集/换曲重新申请焦点被拒，播放就自己停了，解锁回前台才恢复。
     * 浮标保活的语义是「这个应用还在前台服务工作」，所以这里直接判为不拦截。
     */
    private fun installAudioFocusGuard(classLoader: ClassLoader) {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        val enforcerClass = runCatching { classLoader.loadClass(HARDENING_ENFORCER_CLASS) }.getOrNull() ?: return
        val method =
            findMethod(
                enforcerClass,
                "blockFocusMethod",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType!!,
                android.media.AudioAttributes::class.java,
            ) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.audio_focus")
            .intercept { chain ->
                val uid = chain.getArg(0) as? Int ?: -1
                val taskId = keptFloatTaskOfUid(uid)
                if (taskId > 0) {
                    logFocusAllowed(taskId, uid)
                    false
                } else {
                    chain.proceed()
                }
            }
    }

    private fun keptFloatTaskOfUid(uid: Int): Int {
        if (uid <= 0) return -1
        var found = -1
        rotationRecords.forEach { (taskId, reference) ->
            if (found > 0) return@forEach
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            if ((callNoArg(record, "getUid") as? Int) == uid) found = taskId
        }
        return found
    }

    private fun logFocusAllowed(taskId: Int, uid: Int) {
        if (focusAllowedLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_FOCUS_task" + taskId + "_uid" + uid + "_allowed")
    }

    private val focusAllowedLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 对保活中的浮标应用伪装成「亮屏、没进省电」。
     *
     * 视频类应用在换下一集前普遍会看 `PowerManager.isInteractive()`，息屏就不自动连播，
     * 于是浮标里的播放就停在换集那一刻（实测：音轨 started 三分半，换集时应用自己 stopped）。
     * 这里只对「正在被保活的浮标应用」按调用 uid 返回亮屏，其它进程不受影响。
     */
    private fun installAwakeGuard(classLoader: ClassLoader) {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        val binderClass = runCatching { classLoader.loadClass(POWER_BINDER_CLASS) }.getOrNull() ?: return
        hookAwakeAnswer(binderClass, "isInteractive", true)
        hookAwakeAnswer(binderClass, "isDeviceIdleMode", false)
        hookAwakeAnswer(binderClass, "isLightDeviceIdleMode", false)
    }

    private fun hookAwakeAnswer(binderClass: Class<*>, name: String, answer: Boolean) {
        val method = findMethod(binderClass, name) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.awake_" + name)
            .intercept { chain ->
                val uid = Binder.getCallingUid()
                val taskId = keptFloatTaskOfUid(uid)
                if (taskId > 0) {
                    logAwakeAnswer(name, taskId, uid)
                    answer
                } else {
                    chain.proceed()
                }
            }
    }

    private fun logAwakeAnswer(name: String, taskId: Int, uid: Int) {
        if (awakeLogged.putIfAbsent(name + "/" + taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_AWAKE_" + name + "_task" + taskId + "_uid" + uid)
    }

    private val awakeLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 不把息屏广播投给正在保活的浮标应用。
     *
     * 视频类应用收到 `ACTION_SCREEN_OFF` 后会把自己标成「熄屏」，当前这集照播，但换下一集时
     * 就不自动连播了（实测红果：锁屏后播 3 分半，换集那一刻应用自己 pause 并停止申请播放）。
     * 亮屏时同样的换集就正常，所以判断依据是屏幕状态而不是焦点或进程档位。
     *
     * 做法只在这一条广播上把保活应用的包名加进 `excludedPackages`，其它应用照常收到。
     */
    private fun installScreenOffGuard(classLoader: ClassLoader) {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        val controllerClass = runCatching { classLoader.loadClass(BROADCAST_CONTROLLER_CLASS) }.getOrNull() ?: return
        val method =
            controllerClass.declaredMethods.firstOrNull {
                it.name == "broadcastIntentLocked" && it.parameterCount >= 20
            }?.apply { isAccessible = true } ?: return
        val excludedIdx = excludedPackagesIndex(method)
        if (excludedIdx < 0) return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.screen_off")
            .intercept { chain ->
                val action = (chain.getArg(3) as? android.content.Intent)?.action
                if (action != android.content.Intent.ACTION_SCREEN_OFF) {
                    chain.proceed()
                } else {
                    val packages = keptFloatPackages()
                    if (packages.isEmpty()) {
                        chain.proceed()
                    } else {
                        val args = Array<Any?>(method.parameterCount) { chain.getArg(it) }
                        val previous = (args[excludedIdx] as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
                        args[excludedIdx] = (previous + packages).distinct().toTypedArray()
                        logScreenOffSkipped(packages)
                        chain.proceed(args)
                    }
                }
            }
    }

    /** 找 `broadcastIntentLocked` 里 excludedPackages 那个 String[] 的下标（第三个 String[]）。 */
    private fun excludedPackagesIndex(method: Method): Int {
        var seen = 0
        method.parameterTypes.forEachIndexed { index, type ->
            if (type == Array<String>::class.java) {
                seen++
                if (seen == 3) return index
            }
        }
        return -1
    }

    private fun keptFloatPackages(): List<String> {
        val packages = ArrayList<String>(2)
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            (readField(record, "packageName") as? String)?.let { if (!packages.contains(it)) packages.add(it) }
        }
        return packages
    }

    private fun logScreenOffSkipped(packages: List<String>) {
        val key = packages.joinToString(",")
        if (screenOffLogged.putIfAbsent(key, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_SCREEN_OFF_skipped_" + key)
    }

    private val screenOffLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 锁屏期间仍然告诉应用「你的窗口是可见的」。
     *
     * 窗口管理器侧该任务照旧是不可见的（不会在锁屏上显示），这里只改下发给应用的那一个标志：
     * 应用侧 `onWindowVisibilityChanged(true)` / `View.isShown()` 保持可见。
     * 对视频类应用很关键——实测红果在锁屏后播完当前集、准备连播下一集时，因为窗口被判为
     * 不可见就不连播；亮屏贴边时（窗口可见）完全正常。
     */
    private fun installClientVisibleKeepOn(classLoader: ClassLoader) {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        val tokenClass = runCatching { classLoader.loadClass(TOKEN_CLASS) }.getOrNull() ?: return
        val method = findMethod(tokenClass, "setClientVisible", Boolean::class.javaPrimitiveType!!) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.client_visible_keep")
            .intercept { chain ->
                val visible = chain.getArg(0) as? Boolean
                val target = chain.thisObject
                if (visible == false && target?.javaClass?.name == RECORD_CLASS && shouldKeep(target)) {
                    logClientKeep(target)
                    chain.proceed(arrayOf<Any?>(true))
                } else {
                    chain.proceed()
                }
            }
    }

    private fun logClientKeep(record: Any?) {
        val task = readField(record, "task") ?: readField(record, "mTask")
        val taskId = (task?.let { readField(it, "mTaskId") } as? Int) ?: -1
        if (clientKeepLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_CLIENT_VISIBLE_task" + taskId + "_kept")
    }

    private val clientKeepLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 对保活中的浮标应用把 `Display.getState()` 报成 STATE_ON。
     *
     * 除了电源状态，播放器还常用 `DisplayManager.getDisplayInfo(0).state` 判断屏幕是否可用；
     * 熄屏时它是 STATE_OFF/STATE_DOZE，播放器据此不启动下一集。这里对保活应用返回一份
     * state=STATE_ON 的副本（不修改系统内部对象），并记一行日志便于确认。
     */
    private fun installDisplayStateGuard(classLoader: ClassLoader) {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        val cls = runCatching { classLoader.loadClass(DISPLAY_BINDER_CLASS) }.getOrNull() ?: return
        val method = findMethod(cls, "getDisplayInfo", Int::class.javaPrimitiveType!!) ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.display_state")
            .intercept { chain ->
                val taskId = keptFloatTaskOfUid(Binder.getCallingUid())
                if (taskId <= 0) {
                    chain.proceed()
                } else {
                    val info = chain.proceed()
                    val state = readField(info, "state") as? Int ?: -1
                    if (info == null || state < 0 || state == DISPLAY_STATE_ON) {
                        info
                    } else {
                        val patched =
                            runCatching {
                                val ctor = info.javaClass.getDeclaredConstructor(info.javaClass).apply { isAccessible = true }
                                val copy = ctor.newInstance(info)
                                copy.javaClass.getDeclaredField("state").apply { isAccessible = true }.set(copy, DISPLAY_STATE_ON)
                                copy
                            }.getOrNull()
                        if (patched == null) {
                            info
                        } else {
                            logDisplayState(taskId, state)
                            patched
                        }
                    }
                }
            }
    }

    private fun logDisplayState(taskId: Int, original: Int) {
        if (displayLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_DISPLAY_task" + taskId + "_off" + original + "_reported_2")
    }

    private val displayLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 对保活中的浮标应用回答「没有锁屏」。
     *
     * 反编译红果确认了它换集的判断点：
     * `com.dragon.read.video.o.b()` 里 `if (!KeyguardManager.isKeyguardLocked()) { 继续播放 }`，
     * 锁屏时整段跳过，所以换集不连播。`KeyguardManager.isKeyguardLocked()` 的服务端就是
     * `WindowManagerService.isKeyguardLocked()`（转给 `mPolicy.isKeyguardLocked()`）。
     * 只对保活应用的调用方返回 false，系统内部组件走的是 LocalService，不受影响。
     */
    private fun installKeyguardLockGuard(classLoader: ClassLoader) {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        val wmsClass = runCatching { classLoader.loadClass(WMS_CLASS) }.getOrNull() ?: return
        val method = findMethod(wmsClass, "isKeyguardLocked") ?: return
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.float_keep_alive.keyguard_locked")
            .intercept { chain ->
                val taskId = keptFloatTaskOfUid(Binder.getCallingUid())
                if (taskId > 0) {
                    logKeyguardFaked(taskId)
                    false
                } else {
                    chain.proceed()
                }
            }
    }

    private fun logKeyguardFaked(taskId: Int) {
        if (keyguardLogged.putIfAbsent(taskId.toString(), true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_KEYGUARD_task" + taskId + "_reported_unlocked")
    }

    private val keyguardLogged = ConcurrentHashMap<String, Boolean>()

    /**
     * 贴边浮标出现时给该应用加系统省电白名单（等于“允许完全后台运行”），
     * 离开浮标后撤销。
     *
     * 实测结论：锁屏后应用仍在播，但换集要新建连接/解析新域名，会被打盹下的后台网络限制卡住，
     * 表现为“这一集播完就停、解锁立刻继续”。加入白名单（待机桶随之变成 EXEMPTED）后不再受限。
     * 所以这一条只在保活期间生效，退出浮标即撤销，不留长期副作用。
     */
    private fun installIdleExemption() {
        if (!configuration.snapshot.floatKeepAliveEffective) return
        exemptExecutor.execute {
            // 模组启动就补一次扫描，避免上次异常退出的残留。
            sweepExemptions()
        }
    }

    private val exemptExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "flymefreeform-idle-exempt").apply { isDaemon = true }
        }

    /** 包名 -> userId，只记本模组加过的。 */
    private val exemptedPackages = ConcurrentHashMap<String, Int>()

    private fun exemptFloat(record: Any?) {
        val pkg = readField(record, "packageName") as? String
        if (pkg.isNullOrEmpty()) return
        val userId = (readField(record, "mUserId") as? Int) ?: 0
        if (exemptedPackages.putIfAbsent(pkg, userId) != null) return
        exemptExecutor.execute {
            val ok = runCatching { deviceIdleCall("addPowerSaveWhitelistApp", pkg) }.getOrDefault(false)
            if (ok) ModuleLog.info("FLOAT_KEEP_ALIVE_EXEMPT_" + pkg) else ModuleLog.warn("FLOAT_KEEP_ALIVE_EXEMPT_FAILED_" + pkg)
        }
        scheduleExemptSweep()
    }

    private fun scheduleExemptSweep() {
        if (exemptSweepScheduled.compareAndSet(false, true)) {
            handler.postDelayed({ exemptSweepScheduled.set(false); exemptExecutor.execute { sweepExemptions() } }, EXEMPT_SWEEP_MS)
        }
    }

    private val exemptSweepScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun sweepExemptions() {
        val stillFloating = HashSet<String>()
        if (configuration.snapshot.floatKeepAliveEffective) {
            rotationRecords.forEach { (taskId, reference) ->
                val record = reference.get() ?: return@forEach
                if (!isFloating(taskId)) return@forEach
                (readField(record, "packageName") as? String)?.let { stillFloating.add(it) }
            }
        }
        val released = exemptedPackages.keys.filter { it !in stillFloating }
        released.forEach { pkg ->
            exemptedPackages.remove(pkg)
            val ok = runCatching { deviceIdleCall("removePowerSaveWhitelistApp", pkg) }.getOrDefault(false)
            if (ok) ModuleLog.info("FLOAT_KEEP_ALIVE_EXEMPT_OFF_" + pkg) else ModuleLog.warn("FLOAT_KEEP_ALIVE_EXEMPT_OFF_FAILED_" + pkg)
        }
        if (exemptedPackages.isNotEmpty()) scheduleExemptSweep()
    }

    private fun deviceIdleCall(method: String, pkg: String): Boolean {
        val iface = deviceIdleController() ?: return false
        val m = iface.javaClass.getMethod(method, String::class.java)
        m.invoke(iface, pkg)
        return true
    }

    private var deviceIdleControllerCache: Any? = null

    private fun deviceIdleController(): Any? {
        deviceIdleControllerCache?.let { return it }
        val binder =
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "deviceidle") as? android.os.IBinder ?: return null
        val stub = Class.forName("android.os.IDeviceIdleController\$Stub")
        val iface =
            stub.getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
                ?: return null
        deviceIdleControllerCache = iface
        return iface
    }

    private fun logResetSkipped(taskId: Int) {
        if (resetSkippedLogged.putIfAbsent(taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_SKIP_FULLSCREEN_task$taskId")
    }

    private val resetSkippedLogged = ConcurrentHashMap<Int, Boolean>()

    private fun isLockedOrOff(controller: Any?): Boolean {
        if (readField(controller, "mIsKeyGuardShowing") == true) return true
        val wms = readField(controller, "mFlexibleWindowManagerService")
        return callNoArg(wms, "isScreenOff") == true
    }

    private fun isRotationChange(oldArg: Any?, newArg: Any?): Boolean {
        if (oldArg == null || newArg == null) return false
        val oldConfig = readField(oldArg, "windowConfiguration") ?: return false
        val newConfig = readField(newArg, "windowConfiguration") ?: return false
        val oldRotation = readField(oldConfig, "mRotation") as? Int ?: return false
        val newRotation = readField(newConfig, "mRotation") as? Int ?: return false
        return oldRotation != newRotation
    }

    private fun beginRotation() {
        if (!rotationGuard.compareAndSet(false, true)) return
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            if (invoke(record, "setVisibleRequested", false)) {
                logRotation(taskId, "release")
            }
        }
    }

    private fun scheduleRotationEnd() {
        // 原厂旋转过渡可能拖到几秒，单点 800ms 复查会漏，这里多查几次。
        ROTATION_CHECK_DELAYS_MS.forEach { delay ->
            handler.postDelayed({
                if (delay >= ROTATION_SETTLE_MS) finishRotation(dropGuard = true) else finishRotation(dropGuard = false)
            }, delay)
        }
    }

    private fun finishRotation(dropGuard: Boolean) {
        rotationRecords.forEach { (taskId, reference) ->
            val record = reference.get() ?: return@forEach
            if (!isFloating(taskId)) return@forEach
            // 旋转过程中窗口被重新提交成可见的，这里再压回去一次。
            if (readField(record, "mVisibleRequested") == true) {
                if (invoke(record, "setVisibleRequested", false)) {
                    logRotation(taskId, "rehide")
                }
            }
        }
        if (dropGuard) rotationGuard.set(false)
    }

    /**
     * 采样进程状态：/proc/<pid>/stat 的第三列（T = 被 freezer 停住）与 freezer cgroup。
     * 用来判断「息屏后应用没声音了」到底是掉档被冻，还是它自己停了播放。
     */
    private fun logProcState(record: Any?, taskId: Int, phase: String) {
        val app = readField(record, "app") ?: return
        val pid = (readField(app, "pid") as? Int) ?: return
        val stat = runCatching { java.io.File("/proc/" + pid + "/stat").readText() }.getOrNull()
        val state = stat?.substringAfterLast(")")?.trim()?.split(" ")?.firstOrNull() ?: "?"
        val freezer =
            runCatching {
                java.io.File("/proc/" + pid + "/cgroup").readText().lineSequence()
                    .firstOrNull { line -> line.contains("freezer") }?.substringAfter(":")?.trim()
            }.getOrNull() ?: "?"
        ModuleLog.info("FLOAT_KEEP_ALIVE_PROC_task" + taskId + "_" + phase + "_state" + state + "_freezer" + freezer)
    }

    /** 浮标任务每经历一个不同状态记一行，用于看清息屏期间它走到哪一步。 */
    private fun logStateOnce(record: Any?, state: String) {
        val target = record ?: return
        val task = readField(target, "task") ?: readField(target, "mTask")
        val taskId = (task?.let { readField(it, "mTaskId") } as? Int) ?: return
        if (stateLogged.putIfAbsent(taskId.toString() + "/" + state, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_STATE_task" + taskId + "_" + state)
    }

    private val stateLogged = ConcurrentHashMap<String, Boolean>()

    /** 浮标任务被抬成可见时记一行（排障：谁把窗口顶出来的）。 */
    private fun logRaised(record: Any?) {
        val target = record ?: return
        val task = readField(target, "task") ?: readField(target, "mTask")
        val taskId = (task?.let { readField(it, "mTaskId") } as? Int) ?: return
        if (raisedLogged.putIfAbsent(taskId, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_RAISED_task" + taskId)
    }

    private val raisedLogged = ConcurrentHashMap<Int, Boolean>()

    private fun logRotation(taskId: Int, reason: String) {
        if (rotationLogged.putIfAbsent(taskId.toString() + "/" + reason, true) != null) return
        ModuleLog.info("FLOAT_KEEP_ALIVE_ROTATION_task" + taskId + "_" + reason)
    }

    private fun invoke(instance: Any?, name: String, vararg args: Any?): Boolean {
        if (instance == null) return false
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val method =
                runCatching {
                    type!!.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
                }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                return runCatching { method.invoke(instance, *args) }.isSuccess
            }
            type = type.superclass
        }
        return false
    }

    private fun markPausing(fragment: Any?, record: Any?) {
        if (fragment == null || record == null) return
        var type: Class<*>? = fragment.javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField("mPausingActivity") }.getOrNull()
            if (field != null) {
                runCatching {
                    field.isAccessible = true
                    field.set(fragment, record)
                }
                return
            }
            type = type.superclass
        }
    }

    private fun scheduleFakePause(record: Any?): Boolean {
        val target = record ?: return false
        val atmService = readField(target, "mAtmService") ?: return false
        val controller = readField(atmService, "mActivityClientController") ?: return false
        val handler = readField(atmService, "mH") as? Handler ?: return false
        val token = readField(target, "token") as? IBinder ?: return false
        val pausedMethod =
            runCatching {
                controller.javaClass.getDeclaredMethod("activityPaused", IBinder::class.java)
                    .apply { isAccessible = true }
            }.getOrNull() ?: return false
        handler.post {
            runCatching { pausedMethod.invoke(controller, token) }
                .onFailure {
                    ModuleLog.warn("FLOAT_KEEP_ALIVE_FAKE_PAUSE_FAILED", it)
                }
        }
        return true
    }

    private fun resolveController(classLoader: ClassLoader) {
        runCatching {
            val type = classLoader.loadClass(CONTROLLER_CLASS)
            getInstanceMethod = type.getDeclaredMethod("getInstance").apply { isAccessible = true }
            isInFloatingListMethod =
                type
                    .getDeclaredMethod("isInFloatingList", Int::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }
        }.onFailure {
            ModuleLog.warn("FLOAT_KEEP_ALIVE_CONTROLLER_UNAVAILABLE", it)
        }
    }

    private fun shouldKeep(record: Any?): Boolean {
        if (record == null) return false
        if (!configuration.snapshot.floatKeepAliveEffective) return false
        if (readField(record, "finishing") == true) return false
        val task = readField(record, "task") ?: readField(record, "mTask") ?: return false
        val taskId = (readField(task, "mTaskId") as? Int) ?: return false
        if (!isFloating(taskId)) return false
        if (rotationRecords[taskId]?.get() !== record) {
            rotationRecords[taskId] = WeakReference(record)
        }
        exemptFloat(record)
        return true
    }

    private fun isFloating(taskId: Int): Boolean {
        if (taskId <= 0) return false
        val getInstance = getInstanceMethod ?: return false
        val isInList = isInFloatingListMethod ?: return false
        val controller = runCatching { getInstance.invoke(null) }.getOrNull() ?: return false
        return runCatching { isInList.invoke(controller, taskId) == true }.getOrDefault(false)
    }

    /**
     * 可见性两道钩子只在「屏幕亮着且没锁屏」时生效。
     *
     * 锁屏时如果仍把浮标任务按成可见，窗口管理器会把任务表面（带浮标裁切）显示出来，
     * 锁屏上就能看到一小块迷你窗；放开可见性则只留浮标，暂停/停止照样拦着。
     */
    private fun shouldKeepVisible(record: Any?): Boolean =
        shouldKeep(record) && !isDisplayLockedOrOff(record) && !rotationGuard.get() && !unlockGuard.get()

    private fun isDisplayLockedOrOff(record: Any?): Boolean {
        val display = callNoArg(record, "getDisplayContent") ?: return false
        val policy = callNoArg(display, "getDisplayPolicy")
        if (policy != null) {
            if (callNoArg(policy, "isKeyguardShowing") == true) return true
            if (readField(policy, "mAwake") == false) return true
        }
        val state = readField(readField(display, "mDisplayInfo"), "state") as? Int
        return state == DISPLAY_STATE_OFF
    }

    private fun callNoArg(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching { type!!.getDeclaredMethod(name) }
                .getOrNull()
                ?.let { method ->
                    method.isAccessible = true
                    return runCatching { method.invoke(instance) }.getOrNull()
                }
            type = type.superclass
        }
        return null
    }

    private fun logRelease(record: Any?, reason: String) {
        val target = record ?: return
        val task = readField(target, "task") ?: readField(target, "mTask")
        val taskId = (task?.let { readField(it, "mTaskId") } as? Int) ?: -1
        if (taskId > 0) {
            // 锁屏/息屏放开可见性之后，进程会不会被系统按后台处理？持续采样一段时间。
            PROC_SAMPLE_DELAYS_MS.forEach { delay ->
                handler.postDelayed({ logProcState(target, taskId, "t" + (delay / 1000)) }, delay)
            }
        }
        if (releasedTasks.putIfAbsent("$taskId/$reason", true) == null) {
            ModuleLog.info("FLOAT_KEEP_ALIVE_RELEASE_task$taskId" + "_" + reason)
        }
    }

    private fun logSkip(record: Any?, reason: String) {
        val target = record ?: return
        val task = readField(target, "task") ?: readField(target, "mTask")
        val taskId = (task?.let { readField(it, "mTaskId") } as? Int) ?: -1
        if (loggedTasks.putIfAbsent(taskId, true) == null) {
            ModuleLog.info("FLOAT_KEEP_ALIVE_KEEP_task$taskId" + "_" + reason)
        }
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name) }
                .getOrNull()
                ?.let { field: Field ->
                    field.isAccessible = true
                    return runCatching { field.get(instance) }.getOrNull()
                }
            type = type.superclass
        }
        return null
    }

    private fun findMethod(type: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod(name, *parameterTypes) }
                .getOrNull()
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val RECORD_CLASS = "com.android.server.wm.ActivityRecord"
        const val CONTROLLER_CLASS = "com.android.server.wm.FloatHandleController"
        const val TASK_CLASS = "com.android.server.wm.Task"
        const val TOKEN_CLASS = "com.android.server.wm.WindowToken"

        /** ColorOS 音频加固：决定要不要拒绝后台应用的音频焦点申请。 */
        const val HARDENING_ENFORCER_CLASS = "com.android.server.audio.HardeningEnforcer"

        /** 电源服务的 Binder 实现：应用的 isInteractive()/省电查询都从这里过。 */
        const val POWER_BINDER_CLASS = "com.android.server.power.PowerManagerService\$BinderService"

        /** 广播分发的收口：在这里可以按包名排除某条广播的接收者。 */
        const val BROADCAST_CONTROLLER_CLASS = "com.android.server.am.BroadcastController"

        /** 显示服务的 Binder 实现：`Display.getState()` 等查询都从这里过。 */
        const val DISPLAY_BINDER_CLASS = "com.android.server.display.DisplayManagerService\$BinderService"

        /** Display.STATE_ON */
        const val DISPLAY_STATE_ON = 2

        /** 窗口管理服务：应用的 KeyguardManager.isKeyguardLocked() 最终落到这里。 */
        const val WMS_CLASS = "com.android.server.wm.WindowManagerService"
        const val TASK_FRAGMENT_CLASS = "com.android.server.wm.TaskFragment"
        val STOP_STATES = setOf("STOPPING", "STOPPED")

        /** ColorOS 的小窗控制器，横竖屏切换时由它重排浮动窗口。 */
        const val FLEXIBLE_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"

        /** 旋转过渡结束后留一点余量再收回守卫。 */
        const val ROTATION_SETTLE_MS = 800L

        /** 放开可见性后采样进程状态的时间点（毫秒）。 */
        val PROC_SAMPLE_DELAYS_MS = longArrayOf(5_000L, 30_000L, 120_000L)

        /** 旋转后复查窗口是否被重新提交的时间点（毫秒）。 */
        val ROTATION_CHECK_DELAYS_MS = longArrayOf(800L, 2000L, 4000L, 8000L)

        /** 解锁过渡期间复查浮标是否被顶出来的时间点（毫秒），最后一个点后收回守卫。 */
        val UNLOCK_CHECK_DELAYS_MS = longArrayOf(800L, 1600L, 2600L)

        /** Display.STATE_OFF */
        const val DISPLAY_STATE_OFF = 1

        /** 复查白名单豁免的时间间隔（毫秒）。 */
        const val EXEMPT_SWEEP_MS = 60_000L
    }
}
