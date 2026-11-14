package io.github.hoerire.flymefreeform.hook.launch

import android.content.Intent
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.hoerire.flymefreeform.hook.ModuleEnvironmentState
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.hoerire.flymefreeform.log.VerboseLog
import io.github.hoerire.flymefreeform.hook.OemReflection

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
                .setId("flymefreeform.zoom.starter." + method.name + "." + method.parameterCount)
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
        io.github.hoerire.flymefreeform.log.ModuleLog.info("ZOOM_STARTER_HOOK_INSTALLED_" + installedHooks, null)
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
        // 分享动作（SEND/SEND_MULTIPLE/SENDTO）只有在目标是用户安装的第三方应用时，
        // 才算「用户主动跳到另一个应用」；选择器、分享面板与系统落点一律放行。
        val hasResultToValue = request?.let { OemReflection.readField(it, "resultTo") } != null
        val shareRelayHere = hasResultToValue && isShareRelay(intent, request, target, callingPackage)
        if (intent.action in SHARE_FLOW_ACTIONS && !shareRelayHere) {
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
        // startActivityForResult 的跨应用启动（登录、授权、选图、分享回传）需要目标
        // 把结果回传并自行 finish；改由 zoom 入口另起一次启动会打断回传链路，
        // 表现为授权窗口不关闭、或直接提示用户取消。这类启动一律放行。
        //
        // 例外只有一个：目标应用自己的「中转页」二次发起同一目标。Via 用
        // mark.via/.Trampoline 接链接，它同样带 resultTo，但它的结果只是给自己用，
        // 放行就等于必然退化成全屏（日志 reason:9）。判据限定得很窄：Activity 名含
        // Trampoline、由目标自己发起、action 为 VIEW、距上次注入 3 秒内。
        val hasResultTo = hasResultToValue
        val trampolineRelay = hasResultTo && isTrampolineRelay(intent, target, callingPackage)
        // 分享中转页（QQ 的 .activity.JumpActivity 这类）：结果回传本身即返回调用方，
        // 但页面会并入发起方任务、窗口形态跟着任务变成全屏。用户开着「应用跳转」时
        // 这里应当是小窗，因此也走抢占通道。系统会复用目标应用既有的任务，不会产生
        // 第二条任务，所以不需要额外的回收动作。
        val shareRelay = shareRelayHere
        if (hasResultTo && !trampolineRelay && !shareRelay) {
            if (isLauncherIconLaunch(callingPackage, intent.action, hasResultTo)) return false
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
                // 分享中转页：系统把它并入发起方任务后窗口形态必为全屏，注入参数无效，
                // 只能由模块另起一次 zoom 启动。判据见 isShareRelay()。
                settings.zoomLaunchShareRelayEffective && target !in SKIP_TARGETS && shareRelay -> true
                // 通知与横幅由 SystemUI 处理：该进程把 isSupportZoonByIntent 判定为 true 后，
                // 会先播 hideBanner 收起动画再以小窗启动，此处不参与。
                pendingIntent != null -> false
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
        handler.post {
            collapseShadeImmediately()
            requestBannerCollapse()
        }
        preempting.set(true)
        return try {
            val started = startZoomWindow(intent, target)
            if (started) {
                ModuleLog.debug(TAG) { "ZOOM_PREEMPTED_" + target }
                if (shareRelay) {
                    ModuleLog.debug(TAG) { "ZOOM_FORCED_SHARE_cp" + callingPackage + "_tg" + target }
                }
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
    private fun isLauncherIconLaunch(callingPackage: String?, action: String?, hasResultTo: Boolean): Boolean =
        callingPackage == LAUNCHER_PACKAGE && action == Intent.ACTION_MAIN && hasResultTo

    /**
     * 分享中转页：跨应用、带 resultTo、action 为 VIEW，且目标组件是分享入口。
     *
     * 只认类名里明确带 JumpActivity / ShareActivity / ShareEntry 的组件（例如 QQ 的
     * `com.tencent.mobileqq/.activity.JumpActivity`）。微信的 `WXEntryActivity` 同时承担
     * 登录授权，不在名单内——授权链断掉的后果比一次全屏严重。
     */
    private fun isShareRelay(
        intent: Intent,
        request: Any?,
        target: String,
        callingPackage: String?,
    ): Boolean {
        if (callingPackage == null || callingPackage == target) return false
        if (callingPackage == LAUNCHER_PACKAGE || callingPackage == SYSTEM_UI_PACKAGE) return false
        if (!isThirdPartyApplication(callingPackage) || !isThirdPartyApplication(target)) return false
        // 分享动作（SEND / SEND_MULTIPLE / SENDTO）暂不强制小窗：这类 Intent 常带
        // content:// 文件与 FLAG_GRANT_READ_URI_PERMISSION，模块另起一次系统级启动会丢掉
        // 这次授权（授权是按发起方 uid 发的），目标应用读不到文件，分享直接失败。
        // 只认不带文件传输的 VIEW 中转页。
        if (intent.action != Intent.ACTION_VIEW) return false
        if (intent.component?.className == null) {
            val resolved =
                (request?.let { OemReflection.readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo)
                    ?.activityInfo?.name
            if (resolved == null) return false
            return SHARE_RELAY_CLASS_HINTS.any { resolved.contains(it) }
        }
        val name = intent.component?.className ?: return false
        return SHARE_RELAY_CLASS_HINTS.any { name.contains(it) }
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
            ) ?: return
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
        if ((reason == "inter_app" || reason == "inter_app_result") && callingPackage != null) {
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

    /**
     * 是否为分享面板。
     *
     * 该界面由系统包承载，若只按包名排除会漏掉分享这一常用入口；
     * 按组件精确匹配，避免整套系统界面被小窗化。
     */
    private fun isShareSheet(intent: Intent): Boolean {
        val component = intent.component ?: return false
        val name = component.className ?: return false
        return SHARE_SHEET_COMPONENTS.any { (pkg, simpleName) ->
            component.packageName == pkg && name.contains(simpleName)
        }
    }

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
     * 5. 应用间跳转（携带 resultTo 的跨包启动、双方均为第三方的跨包启动）；
     * 6. system_server 以 MAIN 代发的启动（系统通知落点、模块管理工具的启动入口）；
     * 7. SystemUI 自身发起的其余启动，按磁贴 / 通知栏区分。
     *
     * 未命中任何分支的来源落到末尾的「无规则」出口，原样放行。
     *
     * 顺序不可随意调整：通知点击与文件选择都携带 resultTo，若排在其后会先被
     * 应用跳转分支命中；控制中心的判定依赖发起方信息，必须晚于前两者。
     */
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
        if (intent.getBooleanExtra(EXTRA_QUICK_SETTINGS, false)) {
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

        // 带 resultTo 的跨应用 VIEW 启动一律交给抢占通道判定（shareRelay 分支）。
        //
        // 原因：注入发生在 execute 阶段，此时组件名还没解析出来（日志里 cn=-），无法判断
        // 是不是分享中转页；而系统对这类启动会按「发起方任务」的形态处理，注入的参数被直接
        // 丢弃。更糟的是同一发启动会在 executeRequest 被抢占通道再判一次，结果是两边都动手：
        // 注入一次 + zoom 再启动一次（日志中同一启动先建窗 20656 再 PREEMPTED）。
        // 这里先让行，由抢占通道在组件可见时统一决定是否小窗。
        val viewResult = hasResultTo &&
            intent.action == Intent.ACTION_VIEW &&
            callingPackage != null &&
            callingPackage != LAUNCHER_PACKAGE &&
            callingPackage != SYSTEM_UI_PACKAGE &&
            callingPackage != target &&
            isThirdPartyApplication(callingPackage) &&
            isThirdPartyApplication(target)
        if (viewResult) {
            val knownShare = isShareRelay(intent, null, target, callingPackage)
            val componentUnknown = intent.component?.className == null
            // 已经认出是分享入口：只归「应用分享」管。开关关闭也不回落到「登录授权」，
            // 避免两个开关互相影响（关闭时抢占通道不强制，原样放行）。
            if (knownShare) {
                ModuleLog.debug(TAG) {
                    "ZOOM_DEFER_SHARE_RELAY_cp" + callingPackage + "_tg" + target +
                        "_on" + (if (configuration.snapshot.zoomLaunchShareRelayEffective) 1 else 0)
                }
                return null
            }
            // execute 阶段组件还没解析出来，认不出是不是分享入口：先让行，等
            // executeRequest 组件可见后再判——是分享走「应用分享」，不是分享由本通道
            // 按「登录授权」处理。否则要么误伤登录授权，要么同一次启动被注入两次。
            if (componentUnknown) {
                ModuleLog.debug(TAG) { "ZOOM_DEFER_VIEW_PENDING_cp" + callingPackage + "_tg" + target }
                return null
            }
        }

        // 携带 resultTo 的跨应用启动：调用方在等待结果。
        // 本方法只补 ActivityOptions，不另起一次启动，结果回传链路保持完整。
        //
        // 桌面同样以 startActivityForResult 启动应用，若不做区分会把每一次图标点击
        // 都当成应用间跳转；系统界面自身的启动由后续分支处理。两者都不参与改写。
        // 应用内部页面之间的跳转同样携带 resultTo，因此还要求目标与发起方不同包。
        //
        // 该判定先于 SKIP_TARGETS：分享面板由系统包承载，但属于用户主动发起的跳转，
        // 按组件精确放行，其余系统组件不做改写。
        // 目标必须已知：分享 / 选择器这类意图在 execute 阶段常常还没解析出目标，
        // 这时写进 zoom 参数会落在一个"还不知道给谁"的 Intent 上，分享页会以小窗打开，
        // 紧接着被收窗关掉，表现为点击无响应。
        if (hasResultTo &&
            target != null &&
            callingPackage != null &&
            callingPackage != LAUNCHER_PACKAGE &&
            callingPackage != SYSTEM_UI_PACKAGE &&
            callingPackage != target &&
            (target !in SKIP_TARGETS || isShareSheet(intent))
        ) {
            if (!configuration.snapshot.zoomLaunchInterAppResultEffective) {
                ModuleLog.debug(TAG) { "ZOOM_SKIP_INTER_APP_RESULT_OFF_tg" + target }
                return null
            }
            return "inter_app_result"
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
        // system_server 以 MAIN 启动应用的两类来源：
        // 1. 通知点击，落点通常是系统包，见 SYSTEM_NOTIFICATION_TARGETS；
        // 2. 没有启动入口的模块管理工具（如 LSPosed 管理器）经 root 权限代发，
        //    落点是用户安装的应用，没有来源标记也没有 PendingIntent。
        // 两者都按应用跳转处理，以自由浮窗打开。
        if (callingPackage == SYSTEM_SERVER_PACKAGE && intent.action == Intent.ACTION_MAIN) {
            if (target in SYSTEM_NOTIFICATION_TARGETS || isThirdPartyApplication(target)) {
                if (!configuration.snapshot.zoomLaunchSystemEffective) {
                    ModuleLog.debug(TAG) { "ZOOM_SKIP_SYSTEM_LAUNCH_OFF_tg" + target }
                    return null
                }
                return "system_launch"
            }
        }
        // 通知栏与控制中心的启动均由 SystemUI 发起，需进一步区分：
        // 磁贴类启动携带 quicksettings 相关 action，交给注入器处理；
        // 其余来自 SystemUI 的启动属于通知栏或横幅点击，已由浮窗通道接管，
        // 再次注入会覆盖系统参数，表现为窗口延迟出现且无过渡动画。
        val fromSystemUi =
            systemUiUid > 0 &&
                (callingUid == systemUiUid || realCallingUid == systemUiUid || requestRealUid == systemUiUid)
        if (fromSystemUi) {
            val action = intent.action.orEmpty()
            val isTileLaunch =
                action.contains("quicksettings", ignoreCase = true) ||
                    action.contains("QS_TILE", ignoreCase = true)
            if (isTileLaunch) {
                if (!configuration.snapshot.zoomLaunchControlCenterEffective) {
                    ModuleLog.debug(TAG) { "ZOOM_SKIP_CC_OFF_tg" + target }
                    return null
                }
                return "control_center"
            }
            ModuleLog.debug(TAG) { "ZOOM_LEFT_SHADE_TO_NATIVE" }
            return null
        }
        // 控制中心内点击应用图标时，ColorOS 转由桌面代理启动，发起 uid 为 launcher。
        // 系统在 OplusListManagerImpl 中记录该包来自控制中心，据此判定。
        if (fromControlCenter(target)) {
            // 与控制中心磁贴同一个开关：用户视角它们都是「控制中心里打开应用」。
            if (!configuration.snapshot.zoomLaunchControlCenterEffective) {
                ModuleLog.debug(TAG) { "ZOOM_SKIP_CC_APP_OFF_tg" + target }
                return null
            }
            return "control_center_launcher"
        }
        if (!isLauncherIconLaunch(callingPackage, intent.action, hasResultTo)) {
            ModuleLog.debug(TAG) { "ZOOM_SKIP_NO_RULE_cp" + callingPackage +
                "_tg" + target +
                "_ac" + intent.action.orEmpty() +
                "_rt" + (if (hasResultTo) 1 else 0) +
                "_su" + (if (fromSystemUi) 1 else 0) }
        }
        return null
    }

    /** 请求 SystemUI 收起 ColorOS 大横幅（它不受 collapsePanels 控制）。 */
    private fun requestBannerCollapse() {
        runCatching {
            systemContext()?.sendBroadcast(
                Intent(ACTION_COLLAPSE_BANNER).setPackage(SYSTEM_UI_PACKAGE),
            )
            ModuleLog.debug(TAG) { "ZOOM_BANNER_COLLAPSE_REQUESTED" }
        }.onFailure { ModuleLog.warn("ZOOM_BANNER_COLLAPSE_FAILED", it) }
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
        const val TAG = "FlymeFreeform"
        const val ACTIVITY_STARTER_CLASS = "com.android.server.wm.ActivityStarter"

        /** 系统全局搜索：桌面下滑呼出的搜索页，始终全屏。 */
        const val ACTION_GLOBAL_SEARCH = "android.search.action.GLOBAL_SEARCH"

        /** 控制中心启动应用时携带的字段。 */
        const val EXTRA_QUICK_SETTINGS = "start_from_systemui_qs"

        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 分享中转页的组件类名特征：只认这些明确表示「分享入口」的页面。 */
        val SHARE_RELAY_CLASS_HINTS = listOf("JumpActivity", "ShareActivity", "ShareEntry")

        /** 桌面进程：其发起的启动属于图标点击，不计入应用间跳转。 */
        const val LAUNCHER_PACKAGE = "com.android.launcher"

        /** 文件选择与文档选取类动作：始终全屏。 */
        /** 分享流程相关动作：不参与小窗改写。 */
        val SHARE_FLOW_ACTIONS =
            setOf(
                "android.intent.action.SEND",
                "android.intent.action.SEND_MULTIPLE",
                "android.intent.action.SENDTO",
                "android.intent.action.CHOOSER",
            )

        val PICKER_ACTIONS =
            setOf(
                "android.intent.action.GET_CONTENT",
                "android.intent.action.OPEN_DOCUMENT",
                "android.intent.action.OPEN_DOCUMENT_TREE",
                "android.intent.action.CREATE_DOCUMENT",
                "android.intent.action.PICK",
            )

        /**
         * 分享面板的组件：包名与类名片段。
         *
         * 分享面板由系统包承载，但在 manifest 的排除名单内；作为用户主动发起的跳转，
         * 此处按组件精确放行。文件选择与权限授权界面不改写，不在名单内。
         */
        val SHARE_SHEET_COMPONENTS =
            listOf(
                "android" to "com.android.internal.app.ResolverActivity",
                "com.android.intentresolver" to "ChooserActivity",
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

        /** 与 SystemUI 侧 BannerCollapseBridge 约定的动作。 */
        const val ACTION_COLLAPSE_BANNER = "io.github.hoerire.flymefreeform.action.COLLAPSE_BANNER"

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