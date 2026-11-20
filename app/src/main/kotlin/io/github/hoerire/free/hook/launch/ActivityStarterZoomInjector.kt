package io.github.hoerire.free.hook.launch

import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.hoerire.free.hook.ModuleEnvironmentState
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.hook.ProcessConfiguration
import io.github.hoerire.free.log.ModuleLog
import io.github.hoerire.free.log.VerboseLog
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.hook.OemReflection

/**
 * 在 ActivityStarter 执行启动前，将符合条件的请求改写为 ColorOS 自由小窗。
 *
 * 仅安装于 system_server 进程。判定依据为 Intent 上的来源标记与控制中心的系统字段，
 * 不推断调用方身份。每个入口各有一个开关，关闭后该分支不做任何改动。
 */
internal class ActivityStarterZoomInjector(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState? = null,
    private val interAppTracker: InterAppLaunchTracker? = null,
) {
    private var installedHooks = 0
    private val preempting = ThreadLocal<Boolean>()
    private var cachedSystemUiUid: Int? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun install(classLoader: ClassLoader) {
        val starter =
            try {
                classLoader.loadClass(ACTIVITY_STARTER_CLASS)
            } catch (exception: ClassNotFoundException) {
                ModuleLog.warn("ZOOM_STARTER_UNAVAILABLE", exception)
                return
            } catch (exception: LinkageError) {
                ModuleLog.warn("ZOOM_STARTER_LINKAGE_FAILED", exception)
                return
            }
        val targets =
            starter.declaredMethods.filter { method ->
                method.name == "execute" || method.name == "executeRequest"
            }
        if (targets.isEmpty()) {
            ModuleLog.warn("ZOOM_STARTER_METHODS_MISSING")
            return
        }
        targets.forEach { method ->
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.zoom.starter." + method.name + "." + method.parameterCount)
                .intercept { chain ->
                    // 总开关关闭时完全让行：不读配置、不改参数、不启动窗口，
                    // 保证与其他同时启用的模块互不干扰。
                    if (!configuration.snapshot.enabled) return@intercept chain.proceed()
                    runCatching { trace(chain.thisObject, method.name) }
                    val preempted =
                        runCatching { preemptWithZoomWindow(chain.thisObject, method.name) }
                            .onFailure { ModuleLog.warn("ZOOM_PREEMPT_FAILED", it) }
                            .getOrDefault(false)
                    if (preempted) {
                        0
                    } else {
                        runCatching { inject(chain.thisObject) }
                            .onFailure { ModuleLog.warn("ZOOM_INJECT_FAILED", it) }
                        chain.proceed()
                    }
                }
            installedHooks++
        }
        io.github.hoerire.free.log.ModuleLog.info("ZOOM_STARTER_HOOK_INSTALLED_" + installedHooks, null)
    }

    /**
     * 通知点击改由系统 zoom 入口启动。
     *
     * 通知点击经 PendingIntent 下发，SystemUI 不提供过渡动画适配器，
     * 只补 options 会出现窗口延迟出现且无动画。此处直接调用系统 zoom 入口，
     * 与扇形菜单使用同一启动路径，获得完整过渡效果。
     */
    /** 上一次注入的目标与时刻，用于识别「目标应用用中转页二次发起同一目标」的退化情形。 */
    @Volatile
    private var lastInjectTarget: String? = null

    @Volatile
    private var lastInjectAt = 0L

    /**
     * 上一次注入的目标是不是"中转页启动"（组件类名含 Trampoline）。
     *
     * 必须在这里记录：中转页自己二次发起的那次是隐式 Intent，`intent.component` 为 null，
     * 到那时再判类名永远取不到值。
     */
    @Volatile
    private var lastInjectTrampoline = false

    private fun preemptWithZoomWindow(starter: Any?, stage: String): Boolean {
        if (starter == null) return false
        if (preempting.get() == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_PREEMPT_REENTRANT_" + stage }
            return false
        }
        val settings = configuration.snapshot
        if (!settings.enabled) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_DISABLED_" + stage }
            return false
        }
        // 闸门 2：排除列表内的应用在前台时停用全部增强，抢占这条快捷通道也要一并失效。
        if (environment?.isForegroundExcluded() == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_BLACKLIST_PREEMPT_" + stage }
            return false
        }
        // 闸门 3：禁用浮窗内的应用在前台时，抢占同样不做。
        if (environment?.isForegroundZoomExcluded() == true && settings.zoomInteractionActive) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_ZOOM_EXCLUDED_PREEMPT_" + stage }
            return false
        }
        val request = OemReflection.readField(starter, "mRequest")
        val intent =
            OemReflection.readField(starter, "mIntent") as? Intent
                ?: (request?.let { OemReflection.readField(it, "intent") } as? Intent)
                ?: run {
                    ModuleLog.debug(TAG) { "ZOOM_SKIP_PREEMPT_NO_INTENT_" + stage }
                    return false
                }
        // 模块自己代发的启动（更多面板里的快捷方式）显式要求保持全屏：
        // 按组件去猜会误伤真正该开小窗的应用启动，只有发起方自己知道这次是不是快捷方式。
        if (intent.getBooleanExtra(ModulePreferences.EXTRA_SKIP_ZOOM, false)) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_MARKED_FULLSCREEN_" + stage }
            return false
        }
        val pendingIntent = request?.let { OemReflection.readField(it, "originatingPendingIntent") }
        val callingPackage =
            request?.let { OemReflection.readField(it, "callingPackage") as? String }.orEmpty()
        val callingUid = intField(starter, "mCallingUid").takeIf { it > 0 }
            ?: request?.let { intField(it, "callingUid") }
            ?: 0
        val realCallingUid = intField(starter, "mRealCallingUid")
        val requestRealUid = request?.let { intField(it, "realCallingUid") } ?: -1
        val systemUiUid = systemUiUid()
        val fromSystemUi =
            systemUiUid > 0 &&
                (callingUid == systemUiUid || realCallingUid == systemUiUid ||
                    requestRealUid == systemUiUid)
        val target = resolveTarget(intent, request)
        if (target == null) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_PREEMPT_NO_TARGET_" + stage }
            return false
        }
        val hasResultToValue = request?.let { OemReflection.readField(it, "resultTo") } != null
        // 分享流程（SEND / SEND_MULTIPLE / SENDTO / CHOOSER）一律不抢占：官方自己会把
        // 分享页放进浮窗，模块另起一次系统级启动会丢掉 content:// 的读授权，
        // 目标应用读不到文件，分享直接失败。
        if (intent.action in SHARE_FLOW_ACTIONS) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_SHARE_FLOW_PREEMPT_" + stage }
            return false
        }
        // 闸门 3（目标维度）：目标是禁用浮窗内的应用，抢占不做。
        if (environment?.isZoomExcluded(target) == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_EXCLUDED_TARGET_PREEMPT_" + target }
            return false
        }
        // 闸门 3（发起方维度）：禁用浮窗内的应用代发的启动，抢占同样不做。
        if (environment?.isZoomExcluded(callingPackage) == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_EXCLUDED_CALLER_PREEMPT_" + callingPackage }
            return false
        }
        // 带 resultTo 的跨应用启动（登录、授权、选图、分享回传）一律不做改写：
        // 官方自己就会把这类流程放进浮窗，模块另起一次启动反而会打断回传链路，
        // 表现为授权窗不关闭、登录页白屏。
        //
        // 例外只有一个：目标应用自己的「中转页」二次发起同一目标（Via 用
        // mark.via/.Trampoline 接链接）。它的结果只是给自己用，放行就等于必然退化成
        // 全屏；判据限定得很窄：Activity 名含 Trampoline、由目标自己发起、action 为
        // VIEW、距上次注入 3 秒内。
        val hasResultTo = hasResultToValue
        val trampolineRelay = hasResultTo && isTrampolineRelay(intent, target, callingPackage)
        if (hasResultTo && !trampolineRelay) {
            if (isLauncherIconLaunch(callingPackage, intent.action, hasResultTo, realCallingUid, requestRealUid)) return false
            ModuleLog.debug(TAG) { "ZOOM_SKIP_RESULT_TO_cp" + callingPackage +
                "_tg" + target +
                "_ac" + intent.action.orEmpty() +
                "_li" + (lastInjectTarget ?: "-") +
                "_tp" + (if (lastInjectTrampoline) 1 else 0) +
                "_ag" + (android.os.SystemClock.uptimeMillis() - lastInjectAt) }
            return false
        }
        val eligible =
            when {
                // 锁屏与息屏状态下不接管任何启动：此时系统正在显示锁屏界面或恢复桌面，
                // 抢占会使窗口创建在锁屏之下，解锁前不可见，表现为点击无响应。
                environment?.isDeviceUnlocked(refreshKeyguard = true) == false -> false
                // 第三方应用把 Intent 交给系统分发服务代发（酷安点链接 → 浏览器就属这类）：
                // 该启动由后台发起，往 options 里注入小窗参数会被柔性任务控制器拒绝
                // （adjustOptionsForFlexibleTask reason:9），窗口创建不出来，表现为点击无反应。
                // 只能改走系统 zoom 入口重发；重发失败会返回 false，原启动照常进行，退化为全屏。
                pendingDispatchOwner(callingPackage, realCallingUid, requestRealUid) != null &&
                    settings.zoomLaunchClipboardEffective && target !in SKIP_TARGETS -> true
                // 目标应用自己用中转页（Via 的 .Trampoline 就属这类）二次发起同一目标的 VIEW：
                // 第一次注入的 options 会在第二次启动时被 adjustOptionsForFlexibleTask 以 reason:9 拒绝，
                // 结果退化成全屏。判据是「目标自己发起 + 目标与前一次注入相同 + 3 秒内」。
                settings.zoomLaunchInterAppEffective && target !in SKIP_TARGETS && trampolineRelay -> true
                // 控制中心必须先于「通知」判定：磁贴长按等入口同样携带 PendingIntent，
                // 若先按 pi 判，这次启动就会落到「通知中心」开关下，两个开关互相串。
                isControlCenterLaunch(intent) ->
                    settings.zoomLaunchControlCenterEffective && target !in SKIP_TARGETS_FOR_TILES
                // 通知与横幅：SystemUI 正常注入时，通知入队那一刻就把小窗参数写进
                // PendingIntent。但 execute 阶段还读不到那份 options（az 恒为假），
                // 这里先按系统 zoom 入口代发；代发失败再走 inject 的补参数兜底。
                pendingIntent != null ->
                    fromSystemUi &&
                        settings.zoomLaunchNoticeEffective &&
                        target !in SKIP_TARGETS &&
                        !ZoomLaunchSpec.alreadyZoom(optionsBundle(starter, request))
                // 控制中心磁贴的长按入口与通知点击一样，需要系统 zoom 启动才带过渡动画；
                // 该路径允许打开系统设置页，仅排除系统界面自身。
                fromSystemUi ->
                    settings.zoomLaunchControlCenterEffective && target !in SKIP_TARGETS_FOR_TILES
                else -> false
            }
        ModuleLog.debug(TAG) { "ZOOM_DECISION_" + stage +
                "_ok" + eligible +
                "_pi" + (if (pendingIntent != null) 1 else 0) +
                "_su" + (if (fromSystemUi) 1 else 0) +
                "_cp" + callingPackage +
                "_tg" + target }
        if (!eligible) return false
        // 收横幅/通知面板必须离开 ActivityStarter 的调用栈。
        //
        // 这里正持有 WindowManagerGlobalLock（ActivityStarter.execute 的入参上下文），
        // 而 sendBroadcast 要取 ActivityManagerService 锁；AMS 侧 attachApplicationLocked
        // 又会反过来取 WM 锁 —— 同一次点击里两边互等，即 12:16 的 system_server
        // pre-watchdog 死锁（各线程 Blocked 15s，system_server 被重启）。
        // 因此只做标记，真正的收起动作丢到主线程下一轮，锁已经全部释放。
        handler.post { collapseShadeImmediately() }
        preempting.set(true)
        return try {
            val started = startZoomWindow(intent, target)
            if (started) {
                ModuleLog.debug(TAG) { "ZOOM_PREEMPTED_" + target }
            }
            started
        } finally {
            preempting.set(false)
        }
    }

    /**
     * 启动来源探针：只读，不改写任何字段，仅在「详细日志」开启时输出一行。
     *
     * 记录发起包、uid、解析出的目标、action、PendingIntent/resultTo/grants 标志与 intent flags，
     * 排障时据此判断某次启动是否应当被接管。
     */
    private fun trace(starter: Any?, stage: String) {
        if (starter == null) return
        if (!VerboseLog.enabled) return
        val request = OemReflection.readField(starter, "mRequest")
        val intent =
            (OemReflection.readField(starter, "mIntent") as? Intent)
                ?: (request?.let { OemReflection.readField(it, "intent") } as? Intent)
        val resolveInfo =
            request?.let { OemReflection.readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo
        val target =
            intent?.component?.packageName
                ?: resolveInfo?.activityInfo?.packageName
                ?: intent?.getPackage()
        val parts =
            listOf(
                "m=" + stage,
                "cp=" + (request?.let { OemReflection.readField(it, "callingPackage") } as? String).orEmpty(),
                "cu=" + intField(starter, "mCallingUid"),
                "ru=" + intField(starter, "mRealCallingUid"),
                "rr=" + (request?.let { intField(it, "realCallingUid") } ?: -1),
                "tg=" + target.orEmpty(),
                "ac=" + (intent?.let { it.action }.orEmpty()),
                "pi=" + (if (request?.let { OemReflection.readField(it, "originatingPendingIntent") } != null) 1 else 0),
                "rt=" + (if (request?.let { OemReflection.readField(it, "resultTo") } != null) 1 else 0),
                "gr=" + (if (request?.let { OemReflection.readField(it, "intentGrants") } != null) 1 else 0),
                "fl=" + (intent?.let { "0x" + Integer.toHexString(it.flags) }.orEmpty()),
                "cn=" + (intent?.component?.className ?: "-"),
            )
        ModuleLog.debug(TAG) { "ZOOM_TRACE " + parts.joinToString(" ") }
    }

    /**
     * 桌面图标点击：桌面以 startActivityForResult + MAIN 打开应用。
     *
     * 这一类不参与启动改写；逐条记录「跳过原因」只会掩盖真正的异常，
     * 因此三条兜底日志对它一律静默；其余启动照常记录。
     */
    private fun isLauncherIconLaunch(
        callingPackage: String?,
        action: String?,
        hasResultTo: Boolean,
        realCallingUid: Int = -1,
        requestRealUid: Int = -1,
    ): Boolean {
        if (!hasResultTo) return false
        // 桌面图标：桌面以 startActivityForResult 拉起应用入口。
        if (callingPackage in LAUNCHER_PACKAGES && action == Intent.ACTION_MAIN) return true
        // 图标点开的是中转页（如手机管家的 lite 版），真正的发起方仍是桌面：
        // 只看包名会把第二跳当成跨应用跳转，于是同一个图标点开变成小窗。
        return realCallingUid in launcherUids || requestRealUid in launcherUids
    }

    /** 桌面可能使用的包名；ColorOS 上还有一套 ohome。 */
    private val launcherUids: Set<Int> by lazy {
        LAUNCHER_PACKAGES.mapNotNull { pkg ->
            runCatching {
                systemContext()
                    ?.packageManager
                    ?.getApplicationInfo(pkg, 0)
                    ?.uid
            }.getOrNull()
        }.toSet()
    }

    /** 判定「目标应用自己的中转页二次发起同一目标」：判据尽量窄，避免误伤授权与回传链路。 */
    private fun isTrampolineRelay(intent: Intent, target: String, callingPackage: String?): Boolean {
        if (callingPackage != target) return false
        if (intent.action != Intent.ACTION_VIEW) return false
        if (lastInjectTarget != target) return false
        if (android.os.SystemClock.uptimeMillis() - lastInjectAt >= TRAMPOLINE_WINDOW_MS) return false
        // 类名在中转页自己的隐式 Intent 里取不到，只能沿用注入时记录的结果。
        return lastInjectTrampoline
    }

    /**
     * 链接跳转：第三方应用以 ACTION_VIEW + URI 拉起另一个第三方应用。
     *
     * 典型场景是浏览器点 App Link（Via → 中国电信）或应用内点深链。这类启动归官方时
     * 通常退化成全屏：官方跨栈浮窗要求「源任务本身是浮窗」且「源包在 acrossTaskJump
     * 白名单（sys_wms_split_app.xml，默认 80 个包）」，全屏源与普通应用两条都不满足，
     * 于是「让给官方」实际等于「无人接手」，目标直接新起一个全屏任务。
     *
     * 判据保持最窄：VIEW + 带 URI + 双方第三方 + 不同包 + 目标不是用户刚离开的应用。
     * 回传方向（目标应用反拉起发起方）由 reasonFor 里更早的 consumeIfReturning 排除，
     * 桌面图标、文件选择、分享中转、无 action 的 SDK 回传各有更早的拦截点，都不受影响。
     */
    private fun isLinkJump(intent: Intent, callingPackage: String?, target: String): Boolean {
        if (!configuration.snapshot.zoomLaunchInterAppEffective) return false
        if (intent.action != Intent.ACTION_VIEW) return false
        if (intent.data == null) return false
        if (callingPackage == null || callingPackage == target) return false
        if (callingPackage in LAUNCHER_PACKAGES) return false
        if (target in SKIP_TARGETS) return false
        if (!isThirdPartyApplication(callingPackage) || !isThirdPartyApplication(target)) return false
        if (target == environment?.previousForeground()) return false
        return true
    }

    private fun startZoomWindow(intent: Intent, targetPackage: String): Boolean =
        runCatching {
            val managerClass = Class.forName(ZOOM_MANAGER_CLASS)
            val instance = managerClass.getMethod("getInstance").invoke(null) ?: return false
            val start =
                managerClass.getMethod(
                    "startZoomWindow",
                    Intent::class.java,
                    Bundle::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            val result =
                start.invoke(instance, intent, zoomBundle(), userId(), targetPackage) as? Int
            result != null && result >= 0
        }.getOrDefault(false)

    private fun zoomBundle(): Bundle =
        ZoomLaunchSpec.bundle(null).apply {
            putInt("android.activity.splashScreenStyle", 1)
            putInt("start_zoom_at_other_side", LAUNCH_AT_RIGHT)
        }

    private fun userId(): Int =
        runCatching {
            Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as? Int ?: 0
        }.getOrDefault(0)

    private fun inject(starter: Any?) {
        if (starter == null) return
        val settings = configuration.snapshot
        if (!settings.enabled) return
        // 当前框架实现中，execute() 从 ActivityStarter.mIntent 取意图，
        // executeRequest() 从 ActivityStarter$Request.intent 取（该字段无 m 前缀）。
        val request = OemReflection.readField(starter, "mRequest")
        val intent =
            OemReflection.readField(starter, "mIntent") as? Intent
                ?: (request?.let { OemReflection.readField(it, "intent") } as? Intent)
                ?: run {
                    ModuleLog.debug(TAG) { "ZOOM_SKIP_NO_INTENT" }
                    return
                }
        val originalOptions = optionsBundle(starter, request)
        if (intent.getIntExtra(ZoomLaunchSpec.EXTRA_APPLIED, 0) == 1) {
            val label = intent.component?.packageName ?: intent.getPackage()
            if (ZoomLaunchSpec.alreadyZoom(originalOptions)) {
                ModuleLog.debug(TAG) { "ZOOM_SKIP_ALREADY_APPLIED_" + label }
                return
            }
            // 标记还在、小窗参数却没了：这次启动被其他模块重新派发过（日志
            // com.alphi.activitystartmanager 这类「跳转拦截器」会先拦下询问，再拿原始
            // Intent 重发一次，ActivityOptions 在这一步丢失）。此时按标记跳过就等于永远
            // 全屏，必须重新注入。正常的两段回调里参数跟着 starter 走，不会走到这里。
            ModuleLog.debug(TAG) { "ZOOM_REAPPLY_OPTIONS_LOST_" + label }
        }
        val callingUid =
            intField(starter, "mCallingUid").takeIf { it > 0 }
                ?: request?.let { intField(it, "callingUid") }
                ?: 0
        val realCallingUid = intField(starter, "mRealCallingUid")
        val requestRealUid = request?.let { intField(it, "realCallingUid") } ?: -1
        val pendingIntent = request?.let { OemReflection.readField(it, "originatingPendingIntent") }
        val callingPackage = request?.let { OemReflection.readField(it, "callingPackage") as? String }
        val hasResultTo = request?.let { OemReflection.readField(it, "resultTo") } != null
        val systemUiUid = systemUiUid()
        if (VerboseLog.enabled) {
            ModuleLog.debug(TAG) { "ZOOM_ENTRY_c" + callingUid + "_r" + realCallingUid + "_rr" + requestRealUid +
                "_pi" + (if (pendingIntent != null) 1 else 0) +
                "_" + (intent.component?.packageName ?: intent.getPackage()) +
                "_" + intent.action +
                    "_cc" + (if (fromControlCenter(intent.component?.packageName ?: intent.getPackage() ?: "")) 1 else 0) }
        }
        val reason =
            reasonFor(
                intent,
                callingUid,
                realCallingUid,
                requestRealUid,
                pendingIntent,
                systemUiUid,
                callingPackage,
                hasResultTo,
            ) ?: run {
                // 诊断：不接管的通知/横幅启动，记录关键状态，
                // 用于区分「放行后系统原生打开」与「此处已写入参数」。
                if (pendingIntent != null || callingPackage == SYSTEM_UI_PACKAGE) {
                    val az = ZoomLaunchSpec.alreadyZoom(originalOptions)
                    ModuleLog.debug(TAG) {
                        "ZOOM_SHADE_DIAG_cp" + callingPackage +
                            "_pi" + (if (pendingIntent != null) 1 else 0) +
                            "_az" + (if (az) 1 else 0) +
                            "_ex" + intent.getIntExtra(ZoomLaunchSpec.EXTRA_APPLIED, 0) +
                            "_ac" + intent.action.orEmpty() +
                            "_tg" + (intent.component?.packageName ?: intent.getPackage()).orEmpty()
                    }
                }
                return
            }
        intent.putExtra(ZoomLaunchSpec.EXTRA_APPLIED, 1)

        val original = originalOptions
        if (ZoomLaunchSpec.alreadyZoom(original)) {
            ModuleLog.debug(TAG) { "ZOOM_NATIVE_CHANNEL_" + reason }
            return
        }
        val bundle = ZoomLaunchSpec.bundle(original)
        val callingPid = request?.let { intField(it, "callingPid") } ?: 0
        val appliedByStarter =
            runCatching { callSetActivityOptions(starter, bundle, callingPid, callingUid) }
                .getOrDefault(false)
        if (!appliedByStarter) {
            runCatching { writeOptions(starter, bundle) }
                .onFailure { ModuleLog.warn("ZOOM_OPTIONS_WRITE_FAILED", it) }
        }
        ModuleLog.debug(TAG) { "ZOOM_INJECTED_" + reason }
        // 记录本次注入的目标与时刻：目标应用若用中转页二次发起同一目标（Via 的 .Trampoline），
        // 第二次启动的 options 会被 reason:9 拒绝，届时据此抢占重发。
        lastInjectTarget = intent.component?.packageName ?: intent.getPackage()
        lastInjectAt = android.os.SystemClock.uptimeMillis()
        lastInjectTrampoline =
            intent.component?.className?.contains("Trampoline", ignoreCase = true) == true
        if (reason == "notification") collapseShade()
        // 记录本次跨应用跳转，供发起方回到前台时关闭目标浮窗。
        if (reason == "inter_app" && callingPackage != null) {
            val destination = intent.component?.packageName ?: intent.getPackage()
            if (destination != null) interAppTracker?.record(callingPackage, destination)
        }
    }

    /** 隐式意图的目标包名；解析失败返回 null。 */
    private fun resolveImplicitTarget(intent: Intent): String? =
        runCatching {
            systemContext()?.packageManager?.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull()

    /** 是否为非系统应用；无法判定时按系统应用处理，不改写。 */
    private fun isThirdPartyApplication(packageName: String): Boolean =
        runCatching {
            val info = systemContext()?.packageManager?.getApplicationInfo(packageName, 0)
            info != null && (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }.getOrDefault(false)

    /** 目标包名：显式组件优先，其次系统解析结果，最后才是 Intent 自带的包名。 */
    private fun resolveTarget(intent: Intent, request: Any?): String? {
        intent.component?.packageName?.let { return it }
        (request?.let { OemReflection.readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo)
            ?.activityInfo?.packageName?.let { return it }
        return intent.getPackage()
    }

    /**
     * 返回命中的场景标识；不满足条件时返回 null，启动交由系统处理。
     *
     * 判定按以下顺序进行，先命中先返回：
     * 1. 来源标记（SystemUI 写入的通知标记、控制中心磁贴扩展）；
     * 2. 排除列表与返回识别，两者直接放行；
     * 3. 通知点击（发起方为 SystemUI 且携带 PendingIntent）；
     * 4. 文件选择，不改写；
     * 5. 应用跳转（双方均为第三方的跨包启动；ACTION_VIEW 的链接跳转即使携带 resultTo
     *    也由模块接管，其余携带 resultTo 的跨包启动仍让给官方）；
     * 6. SystemUI 自身发起的其余启动，按磁贴 / 通知栏区分。
     *
     * system_server 以 MAIN 启动的落点（系统通知、模块管理工具）不再单列场景：
     * 这部分改由系统按全屏处理，模块不参与改写。
     *
     * 未命中任何分支的来源落到末尾的「无规则」出口，原样放行。
     *
     * 顺序不可随意调整：通知点击与文件选择都携带 resultTo，若排在其后会先被
     * 应用跳转分支命中；控制中心的判定依赖发起方信息，必须晚于前两者。
     */
    /**
     * 是否为控制中心发起的启动（磁贴、快捷入口）。
     *
     * 系统给这类启动带 `start_from_systemui_qs`；个别入口只带 quicksettings / QS_TILE 形态的
     * action，所以两者都认。判定必须先于「通知点击」：磁贴长按同样会携带 PendingIntent，
     * 只按 pi 分类会把控制中心的启动算到通知中心开关下。
     */
    private fun isControlCenterLaunch(intent: Intent): Boolean {
        if (intent.getBooleanExtra(EXTRA_QUICK_SETTINGS, false)) return true
        val action = intent.action.orEmpty()
        return action.contains("quicksettings", ignoreCase = true) ||
            action.contains("QS_TILE", ignoreCase = true)
    }

    private fun reasonFor(
        intent: Intent,
        callingUid: Int,
        realCallingUid: Int,
        requestRealUid: Int,
        pendingIntent: Any?,
        systemUiUid: Int,
        callingPackage: String?,
        hasResultTo: Boolean,
    ): String? {
        // 全局搜索（桌面下拉）必须最先短路：桌面以 startActivityForResult 拉起搜索页，
        // 携带 resultTo 且 action 不是 MAIN，交给后面的「应用互跳」分支就会被改成浮窗。
        if (intent.action == ACTION_GLOBAL_SEARCH) {
            ModuleLog.info("ZOOM_SKIP_GLOBAL_SEARCH")
            return null
        }
        // 「小窗跳转」是启动入口的改写开关：关闭时全部回到系统默认的全屏行为。
        if (!configuration.snapshot.zoomInteractionActive) {
            ModuleLog.info("ZOOM_SCENES_ALL_OFF")
            return null
        }
        // 闸门 2（排除列表）：该应用在前台时停用模块全部增强，判定点与全局开关并列，
        // 因此通知点击、磁贴、中转页抢占、应用互跳全部一并失效。
        if (environment?.isForegroundExcluded() == true) {
            ModuleLog.info("ZOOM_SKIP_BLACKLIST_FOREGROUND")
            return null
        }
        // 闸门 3（禁用浮窗）：该列表内的应用在前台时，任何启动都不改写。
        // 与闸门 2 分开判定、分开打日志，排障时能看出是哪一层拦下的。
        if (environment?.isForegroundZoomExcluded() == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_ZOOM_EXCLUDED" }
            return null
        }
        if (isControlCenterLaunch(intent)) {
            if (!configuration.snapshot.zoomLaunchControlCenterEffective) {
                ModuleLog.debug(TAG) { "ZOOM_SKIP_CC_OFF" }
                return null
            }
            return "control_center"
        }
        // 通知点击经 PendingIntent 下发，发起方为 SystemUI。
        //
        // 常规通知由 SystemUI 的通知启动入口改写参数，此处若再次写入会覆盖系统已设定的
        // 值；但系统通知（如电池、下载完成）走的是另一条路径，SystemUI 并不改写。
        // 因此不在这里放弃，改为交由下方判定处理：已被改写的启动会在 alreadyZoom
        // 处原样放行，未改写的才补写参数。
        val systemUiPending = pendingIntent != null && callingPackage == SYSTEM_UI_PACKAGE
        // 大量跳转以 action 隐式启动，没有显式组件；解析出目标包后才能参与判定。
        val target =
            intent.component?.packageName
                ?: intent.getPackage()
                ?: resolveImplicitTarget(intent)
                ?: run {
                    ModuleLog.debug(TAG) { "ZOOM_SKIP_NO_TARGET_ac" + intent.action.orEmpty() }
                    return null
                }
        // 系统/服务代发启动：没有启动入口的应用（如设备互联 com.oplus.linker）靠服务
        // 在后台代为发起，前台往往还是别的应用，只有「目标包」这一维度能判准。
        // 2.6.0 曾用 zoomLaunchSystem 整体关掉这类改写，该开关在 2.7.2 被移除后，
        // 列表里的应用会重新被开成小窗（透明 Activity 在小窗里会露出一块深色面板）。
        // 这里只针对「屏蔽列表 / 禁用浮窗」里的目标包回退成全屏，其余系统代发不受影响。
        if (callingPackage == SYSTEM_SERVER_PACKAGE && intent.action == Intent.ACTION_MAIN) {
            val targetListed =
                environment?.isZoomExcluded(target) == true || environment?.isExcluded(target) == true
            if (targetListed) {
                ModuleLog.info("ZOOM_SKIP_SYSTEM_LAUNCH_EXCLUDED_tg" + target)
                return null
            }
        }
        // 闸门 3（目标维度）：命中「禁用浮窗」的应用，不以任何形式的自由浮窗打开。
        if (environment?.isZoomExcluded(target) == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_EXCLUDED_TARGET_" + target }
            return null
        }
        // 闸门 3（发起方维度）：禁用浮窗内的应用代发的启动同样全屏。
        // 设备互联这类应用常在后台代发（前台仍是别的应用），只按目标包名拦不住。
        if (environment?.isZoomExcluded(callingPackage) == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_EXCLUDED_CALLER_" + callingPackage }
            return null
        }
        // 应用锁放行：系统在 ActivityStarter 之前就拦掉了用户那次启动，解锁成功后由安全
        // 中心拿被拦截的原意图（MAIN + resultTo）把目标重新拉起。它不属于应用互跳，而是
        // 「用户点进来的那次启动」被守卫接手后继续执行，形态必须跟原入口一致——密码页本身
        // 不改写，此处再开成小窗会让同一流程前后形态不一致（日志：解锁后邮件变成小窗）。
        if (callingPackage != null && callingPackage in GUARD_CALLERS) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_GUARD_LAUNCH_cp" + callingPackage + "_tg" + target }
            return null
        }

        // 目标应用完成操作后重新唤起发起方，特征与主动跳转相同（跨包、携带 resultTo），
        // 但这次启动用于回到原应用，不参与浮窗化。关闭浮窗交由系统的返回入口处理。
        if (interAppTracker?.consumeIfReturning(callingPackage, target) == true) {
            ModuleLog.debug(TAG) { "ZOOM_INTER_APP_RETURN" }
            return null
        }

        // 通知点击：目标非系统组件时按小窗启动。
        if (systemUiPending) {
            if (target !in SKIP_TARGETS) {
                if (!configuration.snapshot.zoomLaunchNoticeEffective) {
                    ModuleLog.debug(TAG) { "ZOOM_SKIP_NOTICE_OFF_tg" + target }
                    return null
                }
                return "notification"
            }
            ModuleLog.debug(TAG) { "ZOOM_SKIP_NOTIFICATION_SYSTEM_TARGET_" + target }
            return null
        }

        // 文件选择类界面不改写：这类页面依赖完整的列表与预览布局，
        // 在小窗内可用性差，且调用频繁。该判定先于其余分支，避免被结果回传条件命中。
        if (intent.action in PICKER_ACTIONS) {
            ModuleLog.debug(TAG) { "ZOOM_SKIPPED_PICKER" }
            return null
        }

        // 分享类意图属于"流程界面"：目标通常是应用的中转页（如 QQ 的 JumpActivity），
        // 分享流程依赖它的生命周期。把它开成小窗后，退场时要么被系统收成贴边浮标，
        // 要么被收窗关掉，两条路都会让"点了分享跳不过去"。与文件选择、授权页同一口径，不改写。
        if (intent.action == "android.intent.action.CHOOSER") {
            ModuleLog.debug(TAG) { "ZOOM_SKIPPED_SHARE_CHOOSER" }
            return null
        }

        // SDK 回传页：应用之间的「结果回传 / 登录回调」拉起的是对方包里的 Entry/Auth
        // Activity，Intent **不带 action**（例如 QQ → B站 com.tencent.tauth.AuthActivity、
        // QQ → 微信 com.tencent.mm.plugin.base.stub.WXEntryActivity）。这类页面存活极短，
        // 开成小窗会表现为分享结束后再次弹出浮窗，按流程界面与分享面板同一口径处理。
        //
        // 判据只能用 action：注入发生在 execute 阶段，此时 component 还没解析出来
        // （日志里 cn=-，要到 executeRequest 才是 AuthActivity），按组件名排除拿不到依据。
        // 无 action 的跨应用启动必然是「跳到对方某个具体页面」，不是用户主动打开另一个应用。
        //
        // 判据必须同时满足「目标是用户上一个所在的应用」：微信/QQ 的分享入口
        // （WXEntryActivity 等）同样是空 action，只按 action 判会把主动分享也吞掉。
        if (callingPackage != null &&
            intent.action.isNullOrEmpty() &&
            target == environment?.previousForeground() &&
            isThirdPartyApplication(callingPackage) &&
            isThirdPartyApplication(target)
        ) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_SDK_CALLBACK_cp" + callingPackage + "_tg" + target }
            return null
        }

        // 带 resultTo 的跨应用启动（登录、授权、选图、分享回传）默认交给官方处理：
        // 官方自己就会把这类流程放进浮窗，模块再补参数或另起一次启动都会打断回传链路。
        // 桌面同样以 startActivityForResult 启动应用，但这批启动已在前置条件里排除。
        //
        // 例外是链接跳转（见 isLinkJump）：官方对它的浮窗能力依赖「源任务本身是浮窗 +
        // 源包在 acrossTaskJump 白名单」，全屏源与普通应用两条都不满足，放行即等于必然
        // 全屏（日志表现为目标新起全屏任务、全程没有 AcrossTaskJump 判定）。因此这里改由
        // 模块接管，继续走下方「应用互跳」分支注入小窗入参。
        if (hasResultTo && !isLinkJump(intent, callingPackage, target)) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_RESULT_TO_SYSTEM_cp" + callingPackage + "_tg" + target }
            return null
        }

        // 跨应用启动：发起方与目标均为第三方应用且不同包。
        //
        // 登录、分享、深链等场景的递交方式各不相同：有的携带 resultTo，有的经
        // PendingIntent，有的两者皆无，仅凭这些标志无法覆盖全部入口。
        // 此分支以「双方都是用户安装的应用」为准，覆盖上述分支未命中的情形；
        // 系统组件与桌面图标点击已在前置条件中排除。
        if (callingPackage != null &&
            callingPackage != target &&
            callingPackage != LAUNCHER_PACKAGE &&
            intent.action != Intent.ACTION_MAIN &&
            isThirdPartyApplication(callingPackage) &&
            isThirdPartyApplication(target)
        ) {
            if (!configuration.snapshot.zoomLaunchInterAppEffective) {
                ModuleLog.debug(TAG) { "ZOOM_SKIP_INTER_APP_OFF_tg" + target }
                return null
            }
            return "inter_app"
        }
        if (target in SKIP_TARGETS) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_SYSTEM_TARGET_" + target }
            return null
        }
        // 来自 SystemUI 的启动：控制中心已按专用标记在前面处理完，
        // 这里剩下的就是通知栏 / 横幅点击。
        val fromSystemUi =
            systemUiUid > 0 &&
                (callingUid == systemUiUid || realCallingUid == systemUiUid || requestRealUid == systemUiUid)
        if (fromSystemUi) {
            // 通知栏/横幅点击：SystemUI 未注入时不会写入小窗参数，此处兜底补注入。
            // 已带小窗参数的那次会在下面的 alreadyZoom 处原样放行，不会重复写。
            if (pendingIntent != null &&
                configuration.snapshot.zoomLaunchNoticeEffective &&
                target !in SKIP_TARGETS
            ) {
                return "notification"
            }
            ModuleLog.debug(TAG) { "ZOOM_LEFT_SHADE_TO_NATIVE" }
            return null
        }
        // 控制中心内点击应用图标时，ColorOS 转由桌面代理启动，发起 uid 为 launcher。
        // 系统在 OplusListManagerImpl 中记录该包来自控制中心，据此判定。
        //
        // 排除通知点击：记录按包名保留，该应用从控制中心启动过一次后，
        // 点它的通知同样会命中，通知中心点击被算成控制中心启动、跟着另一个开关走。
        if (pendingIntent == null && fromControlCenter(target)) {
            // 与控制中心磁贴同一个开关：用户视角它们都是「控制中心里打开应用」。
            if (!configuration.snapshot.zoomLaunchControlCenterEffective) {
                ModuleLog.debug(TAG) { "ZOOM_SKIP_CC_APP_OFF_tg" + target }
                return null
            }
            return "control_center_launcher"
        }
        if (!isLauncherIconLaunch(callingPackage, intent.action, hasResultTo, realCallingUid, requestRealUid)) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_NO_RULE_cp" + callingPackage +
                "_tg" + target +
                "_ac" + intent.action.orEmpty() +
                "_rt" + (if (hasResultTo) 1 else 0) +
                "_su" + (if (fromSystemUi) 1 else 0) }
        }
        return null
    }

    /** 同步收起通知面板/横幅：不排延迟，调用即发出收起请求。 */
    private fun collapseShadeImmediately() {
        runCatching {
            val statusBar = systemContext()?.getSystemService("statusbar")
            if (statusBar != null) {
                statusBar.javaClass.getMethod("collapsePanels").invoke(statusBar)
                ModuleLog.debug(TAG) { "ZOOM_SHADE_IMMEDIATE" }
            }
        }.onFailure { ModuleLog.warn("ZOOM_SHADE_IMMEDIATE_FAILED", it) }
    }

    /**
     * 延迟收起通知面板：注入完成后面板仍在，需等新窗口提交再收，过早会被启动流程重新展开。
     */
    private fun collapseShade() {
        handler.postDelayed(
            {
                runCatching {
                    val statusBar = systemContext()?.getSystemService("statusbar")
                    if (statusBar != null) {
                        statusBar.javaClass.getMethod("collapsePanels").invoke(statusBar)
                        ModuleLog.debug(TAG) { "ZOOM_SHADE_COLLAPSED" }
                    } else {
                        ModuleLog.debug(TAG) { "ZOOM_SHADE_UNAVAILABLE" }
                    }
                }.onFailure { ModuleLog.warn("ZOOM_SHADE_COLLAPSE_FAILED", it) }
            },
            SHADE_COLLAPSE_DELAY_MS,
        )
    }

    private fun systemContext(): android.content.Context? =
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val current = activityThread.getMethod("currentActivityThread").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(current) as android.content.Context
        }.getOrNull()

    /** 查询系统记录的「来自控制中心」包名。 */
    private fun fromControlCenter(packageName: String): Boolean =
        runCatching {
            val cls = Class.forName(OPLUS_LIST_MANAGER)
            val instance = cls.getMethod("getInstance").invoke(null)
            val method = cls.getMethod("isFromControlCenterPkg", String::class.java)
            method.invoke(instance, packageName) as? Boolean ?: false
        }.getOrDefault(false)

    private fun systemUiUid(): Int {
        cachedSystemUiUid?.let { return it }
        val uid = runCatching {
            systemContext()?.packageManager?.getApplicationInfo(SYSTEM_UI_PACKAGE, 0)?.uid ?: 0
        }.getOrDefault(0)
        cachedSystemUiUid = uid
        return uid
    }

    /**
     * 经系统分发服务转交的启动，返回其真实发起者；不属于该情形时返回 null。
     *
     * 分发服务自己不是发起方，只是替原应用把 Intent 交出去，发起方身份需回查 uid。
     */
    private fun pendingDispatchOwner(callingPackage: String?, realCallingUid: Int, requestRealUid: Int): String? {
        if (callingPackage == null || callingPackage !in PENDING_DISPATCH_PACKAGES) return null
        val owner = owningPackageOf(realCallingUid) ?: owningPackageOf(requestRealUid) ?: return null
        return owner.takeIf { isThirdPartyApplication(it) }
    }

    /** 由 uid 反查所属包名；一个 uid 对应多个包时取第一个。 */
    private fun owningPackageOf(uid: Int): String? =
        runCatching {
            if (uid <= 0) null
            else systemContext()?.packageManager?.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()

    private fun optionsBundle(starter: Any, request: Any?): Bundle? {
        val options =
            OemReflection.readField(starter, "mOptions")
                ?: request?.let { OemReflection.readField(it, "activityOptions") }
                ?: return null
        return runCatching {
            val toBundle = options.javaClass.getMethod("toBundle")
            toBundle.isAccessible = true
            toBundle.invoke(options) as? Bundle
        }.getOrNull()
    }

    private fun callSetActivityOptions(
        starter: Any,
        bundle: Bundle,
        callingPid: Int,
        callingUid: Int,
    ): Boolean {
        val method =
            starter.javaClass.declaredMethods.firstOrNull { candidate ->
                candidate.name == "setActivityOptions" &&
                    candidate.parameterCount == 3 &&
                    candidate.parameterTypes[0] == Bundle::class.java
            } ?: return false
        method.isAccessible = true
        method.invoke(starter, bundle, callingPid, callingUid)
        return true
    }

    /** setActivityOptions 不可用时，将 ActivityOptions 直接写回 ActivityStarter。 */
    private fun writeOptions(starter: Any, bundle: Bundle) {
        val fromBundle =
            Class.forName("android.app.ActivityOptions").getMethod("fromBundle", Bundle::class.java)
        fromBundle.isAccessible = true
        val options = fromBundle.invoke(null, bundle)
        var current: Class<*>? = starter.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField("mOptions")
                field.isAccessible = true
                field.set(starter, options)
                return
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
    }


    private fun intField(instance: Any, name: String): Int {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                return field.getInt(instance)
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return 0
    }

    private companion object {
        const val TAG = "Free"
        const val ACTIVITY_STARTER_CLASS = "com.android.server.wm.ActivityStarter"

        /** 系统全局搜索：桌面下滑呼出的搜索页，始终全屏。 */
        const val ACTION_GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH"

        /** 控制中心启动应用时携带的字段。 */
        const val EXTRA_QUICK_SETTINGS = "start_from_systemui_qs"

        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 桌面进程：其发起的启动属于图标点击，不计入应用间跳转。 */
        const val LAUNCHER_PACKAGE = "com.android.launcher"

        /** ColorOS 17 上桌面还可能是 ohome，两套包名都要认。 */
        val LAUNCHER_PACKAGES = setOf(LAUNCHER_PACKAGE, "com.oplus.ohome")

        /** 分享流程相关动作：不参与小窗改写（含文件分享与选择器）。 */
        val SHARE_FLOW_ACTIONS =
            setOf(
                "android.intent.action.SEND",
                "android.intent.action.SEND_MULTIPLE",
                "android.intent.action.SENDTO",
                "android.intent.action.CHOOSER",
            )

        /** 文件选择与文档选取类动作：始终全屏。 */
        val PICKER_ACTIONS =
            setOf(
                "android.intent.action.GET_CONTENT",
                "android.intent.action.OPEN_DOCUMENT",
                "android.intent.action.OPEN_DOCUMENT_TREE",
                "android.intent.action.CREATE_DOCUMENT",
                "android.intent.action.PICK",
            )

        /** system_server 代发启动时的发起包名。 */
        const val SYSTEM_SERVER_PACKAGE = "android"

        /**
         * 由 system_server 直接启动、且不属于用户安装应用的目标。
         *
         * 系统通知的落点常常是系统包（例如经 shell 转发的入口），不会命中
         * 「第三方应用」判据，因此按实际落点单独登记。第三方应用由同一分支的
         * 另一条判据覆盖，无需列入此处。
         */
        val SYSTEM_NOTIFICATION_TARGETS = setOf("org.lsposed.manager", "com.android.shell")


        /** 代表其它应用转交启动的系统服务。 */
        val PENDING_DISPATCH_PACKAGES = setOf("android.ext.services")

        /**
         * 守卫类进程：应用锁（安全中心）代发的启动不参与改写。
         *
         * ColorOS 的应用锁在 ActivityStarter 之前拦掉用户那次启动，解锁成功后由安全中心
         * 用被拦截的原意图（MAIN + resultTo）重新拉起目标；若按「携带 resultTo 的应用
         * 互跳」处理，解锁完成的应用会变成小窗。安全中心只在守卫流程里代发应用启动，
         * 因此按发起方整包排除。旧版包名一并登记，覆盖 ColorOS 16 及更早。
         */
        val GUARD_CALLERS = setOf("com.oplus.safecenter", "com.coloros.safecenter")

        const val OPLUS_LIST_MANAGER = "com.android.server.OplusListManagerImpl"
        const val ZOOM_MANAGER_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"
        const val LAUNCH_AT_RIGHT = 14

        /** 中转页二次启动的时间窗：超过这个时间就不再视为同一次点击的后续启动。 */
        const val TRAMPOLINE_WINDOW_MS = 3000L

        /** 收起面板的延迟：过早执行会被启动流程重新展开。 */
        const val SHADE_COLLAPSE_DELAY_MS = 60L

        /** 磁贴长按场景允许小窗化系统设置页，仅排除界面自身。 */
        val SKIP_TARGETS_FOR_TILES =
            setOf(
                "com.android.systemui",
                "android",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
            )

        /** 不参与启动改写的目标包。 */
        val SKIP_TARGETS =
            setOf(
                "com.android.systemui",
                "com.android.settings",
                "com.coloros.settings",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "android",
            )

    }
}