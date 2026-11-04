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

/**
 * 在 ActivityStarter 执行启动前，将符合条件的请求改写为 ColorOS 自由小窗。
 *
 * 仅安装于 system_server 进程。判定依据为 Intent 上的来源标记与控制中心的系统字段，
 * 不推断调用方身份。全部改写受「小窗跳转」单一开关控制，关闭后不做任何改动。
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
    private var cachedLauncherUid: Int? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun install(classLoader: ClassLoader) {
        val starter =
            try {
                classLoader.loadClass(ACTIVITY_STARTER_CLASS)
            } catch (exception: ClassNotFoundException) {
                module.log(Log.WARN, TAG, "ZOOM_STARTER_UNAVAILABLE", exception)
                return
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "ZOOM_STARTER_LINKAGE_FAILED", exception)
                return
            }
        val targets =
            starter.declaredMethods.filter { method ->
                method.name == "execute" || method.name == "executeRequest"
            }
        if (targets.isEmpty()) {
            module.log(Log.WARN, TAG, "ZOOM_STARTER_METHODS_MISSING", null)
            return
        }
        targets.forEach { method ->
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.zoom.starter." + method.name + "." + method.parameterCount)
                .intercept { chain ->
                    runCatching { trace(chain.thisObject, method.name) }
                    val preempted =
                        runCatching { preemptWithZoomWindow(chain.thisObject, method.name) }
                            .onFailure { module.log(Log.WARN, TAG, "ZOOM_PREEMPT_FAILED", it) }
                            .getOrDefault(false)
                    if (preempted) {
                        0
                    } else {
                        runCatching { inject(chain.thisObject) }
                            .onFailure { module.log(Log.WARN, TAG, "ZOOM_INJECT_FAILED", it) }
                        chain.proceed()
                    }
                }
            installedHooks++
        }
        module.log(Log.INFO, TAG, "ZOOM_STARTER_HOOK_INSTALLED_" + installedHooks, null)
    }

    /**
     * 通知点击改由系统 zoom 入口启动。
     *
     * 通知点击经 PendingIntent 下发，SystemUI 不提供过渡动画适配器，
     * 只补 options 会出现窗口延迟出现且无动画。此处直接调用系统 zoom 入口，
     * 与扇形菜单使用同一启动路径，获得完整过渡效果。
     */
    private fun preemptWithZoomWindow(starter: Any?, stage: String): Boolean {
        if (starter == null) return false
        if (preempting.get() == true) return false
        val settings = configuration.snapshot
        if (!settings.enabled) return false
        val request = readField(starter, "mRequest")
        val intent =
            readField(starter, "mIntent") as? Intent
                ?: (request?.let { readField(it, "intent") } as? Intent)
                ?: return false
        val pendingIntent = request?.let { readField(it, "originatingPendingIntent") }
        val callingPackage =
            request?.let { readField(it, "callingPackage") as? String }.orEmpty()
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
        val target = resolveTarget(intent, request) ?: return false
        // startActivityForResult 的跨应用启动（登录、授权、选图、分享回传）需要目标
        // 把结果回传并自行 finish；改由 zoom 入口另起一次启动会打断回传链路，
        // 表现为授权窗口不关闭、或直接提示用户取消。这类启动一律放行。
        if (request?.let { readField(it, "resultTo") } != null) return false
        val eligible =
            when {
                // 锁屏与息屏状态下不接管任何启动：此时系统正在显示锁屏界面或恢复桌面，
                // 抢占会使窗口创建在锁屏之下，解锁前不可见，表现为点击无响应。
                environment?.isDeviceUnlocked(refreshKeyguard = true) == false -> false
                // 通知与横幅由 SystemUI 处理：该进程把 isSupportZoonByIntent 判定为 true 后，
                // 会先播 hideBanner 收起动画再以小窗启动，此处不参与。
                pendingIntent != null -> false
                // 控制中心磁贴的长按入口与通知点击一样，需要系统 zoom 启动才带过渡动画；
                // 该路径允许打开系统设置页，仅排除系统界面自身。
                fromSystemUi ->
                    settings.zoomLaunch && target !in SKIP_TARGETS_FOR_TILES
                else -> false
            }
        ModuleLog.debug(TAG) { "ZOOM_DECISION_" + stage +
                "_ok" + eligible +
                "_pi" + (if (pendingIntent != null) 1 else 0) +
                "_su" + (if (fromSystemUi) 1 else 0) +
                "_cp" + callingPackage +
                "_tg" + target }
        if (!eligible) return false
        // 先收横幅再启动。collapsePanels 只对通知面板有效，ColorOS 大横幅要交给
        // SystemUI 侧的 BannerCollapseBridge 触发它自己的收起动画。
        collapseShadeImmediately()
        requestBannerCollapse()
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
        val request = readField(starter, "mRequest")
        val intent =
            (readField(starter, "mIntent") as? Intent)
                ?: (request?.let { readField(it, "intent") } as? Intent)
        val resolveInfo =
            request?.let { readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo
        val target =
            intent?.component?.packageName
                ?: resolveInfo?.activityInfo?.packageName
                ?: intent?.getPackage()
        val parts =
            listOf(
                "m=" + stage,
                "cp=" + (request?.let { readField(it, "callingPackage") } as? String).orEmpty(),
                "cu=" + intField(starter, "mCallingUid"),
                "ru=" + intField(starter, "mRealCallingUid"),
                "rr=" + (request?.let { intField(it, "realCallingUid") } ?: -1),
                "tg=" + target.orEmpty(),
                "ac=" + (intent?.let { it.action }.orEmpty()),
                "pi=" + (if (request?.let { readField(it, "originatingPendingIntent") } != null) 1 else 0),
                "rt=" + (if (request?.let { readField(it, "resultTo") } != null) 1 else 0),
                "gr=" + (if (request?.let { readField(it, "intentGrants") } != null) 1 else 0),
                "fl=" + (intent?.let { "0x" + Integer.toHexString(it.flags) }.orEmpty()),
            )
        ModuleLog.debug(TAG) { "ZOOM_TRACE " + parts.joinToString(" ") }
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
        val request = readField(starter, "mRequest")
        val intent =
            readField(starter, "mIntent") as? Intent
                ?: (request?.let { readField(it, "intent") } as? Intent)
                ?: return
        if (intent.getIntExtra(ZoomLaunchSpec.EXTRA_APPLIED, 0) == 1) return
        val callingUid =
            intField(starter, "mCallingUid").takeIf { it > 0 }
                ?: request?.let { intField(it, "callingUid") }
                ?: 0
        val realCallingUid = intField(starter, "mRealCallingUid")
        val requestRealUid = request?.let { intField(it, "realCallingUid") } ?: -1
        val pendingIntent = request?.let { readField(it, "originatingPendingIntent") }
        val callingPackage = request?.let { readField(it, "callingPackage") as? String }
        val hasResultTo = request?.let { readField(it, "resultTo") } != null
        val systemUiUid = systemUiUid()
        if (VerboseLog.enabled) {
            ModuleLog.debug(TAG) { "ZOOM_ENTRY_c" + callingUid + "_r" + realCallingUid + "_rr" + requestRealUid +
                "_pi" + (if (pendingIntent != null) 1 else 0) +
                "_" + (intent.component?.packageName ?: intent.getPackage()) +
                "_" + intent.action +
                "_e" + intent.getIntExtra(ZoomLaunchSpec.EXTRA_SOURCE, 0) +
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

        val original = optionsBundle(starter, request)
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
                .onFailure { module.log(Log.WARN, TAG, "ZOOM_OPTIONS_WRITE_FAILED", it) }
        }
        ModuleLog.debug(TAG) { "ZOOM_INJECTED_" + reason }
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

    /** 是否为非系统应用；无法判定时按系统应用处理，保持全屏。 */
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
        (request?.let { readField(it, "resolveInfo") } as? android.content.pm.ResolveInfo)
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
     * 4. 文件选择，始终全屏；
     * 5. 应用间跳转（携带 resultTo 的跨包启动、双方均为第三方的跨包启动）；
     * 6. 桌面发起的小窗场景，见 [launcherOriginReason]（快捷项、快捷菜单、卡片）；
     * 7. system_server 以 MAIN 代发的启动（系统通知落点、模块管理工具的启动入口）；
     * 8. SystemUI 自身发起的其余启动，按磁贴 / 通知栏区分。
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
        // 「小窗跳转」是唯一的启动改写开关：关闭时任何入口都不改写，
        // 全部启动回到系统默认的全屏行为。
        if (!configuration.snapshot.zoomLaunch) return null
        if (intent.getIntExtra(ZoomLaunchSpec.EXTRA_SOURCE, 0) == ZoomLaunchSpec.SOURCE_NOTIFICATION) {
            return "notification"
        }
        if (intent.getBooleanExtra(EXTRA_QUICK_SETTINGS, false)) return "control_center"
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
                ?: return null
        // 排除列表内的应用不参与任何启动改写。
        if (environment?.isExcluded(target) == true) {
            ModuleLog.debug(TAG) { "ZOOM_SKIPPED_EXCLUDED_" + target }
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
            return if (target !in SKIP_TARGETS) "notification" else null
        }

        // 文件选择类界面保持全屏：这类页面依赖完整的列表与预览布局，
        // 在小窗内可用性差，且调用频繁。该判定先于其余分支，避免被结果回传条件命中。
        if (intent.action in PICKER_ACTIONS) {
            ModuleLog.debug(TAG) { "ZOOM_SKIPPED_PICKER" }
            return null
        }

        // 携带 resultTo 的跨应用启动：调用方在等待结果。
        // 本方法只补 ActivityOptions，不另起一次启动，结果回传链路保持完整。
        //
        // 桌面同样以 startActivityForResult 启动应用，若不做区分会把每一次图标点击
        // 都当成应用间跳转；系统界面自身的启动由后续分支处理。两者一律保持全屏。
        // 应用内部页面之间的跳转同样携带 resultTo，因此还要求目标与发起方不同包。
        //
        // 该判定先于 SKIP_TARGETS：分享面板由系统包承载，但属于用户主动发起的跳转，
        // 按组件精确放行，其余系统组件维持全屏。
        if (hasResultTo &&
            callingPackage != null &&
            callingPackage != LAUNCHER_PACKAGE &&
            callingPackage != SYSTEM_UI_PACKAGE &&
            callingPackage != target &&
            (target !in SKIP_TARGETS || isShareSheet(intent))
        ) {
            return "inter_app"
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
            return "inter_app"
        }
        launcherOriginReason(intent, target, callingPackage, realCallingUid, requestRealUid, hasResultTo)
            ?.let { return it }
        if (target in SKIP_TARGETS) return null
        // system_server 以 MAIN 启动应用的两类来源：
        // 1. 通知点击，落点通常是系统包，见 SYSTEM_NOTIFICATION_TARGETS；
        // 2. 无桌面入口的模块管理工具（如 LSPosed 管理器）经 root 权限代发，
        //    落点是用户安装的应用，没有来源标记也没有 PendingIntent。
        // 两者都按应用跳转处理，以自由浮窗打开。
        if (callingPackage == SYSTEM_SERVER_PACKAGE && intent.action == Intent.ACTION_MAIN) {
            if (target in SYSTEM_NOTIFICATION_TARGETS || isThirdPartyApplication(target)) {
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
                return "control_center"
            }
            ModuleLog.debug(TAG) { "ZOOM_LEFT_SHADE_TO_NATIVE" }
            return null
        }
        // 控制中心内点击应用图标时，ColorOS 转由桌面代理启动，发起 uid 为 launcher。
        // 系统在 OplusListManagerImpl 中记录该包来自控制中心，据此判定。
        if (fromControlCenter(target)) {
            return "control_center_launcher"
        }
        return null
    }

    /**
     * 桌面进程发起的启动，按入口种类分流。
     *
     * 桌面是唯一同时承载图标点击、长按快捷项、快捷菜单与卡片页面的进程，四类入口在
     * ActivityStarter 中的字段组合各不相同，因此集中在此判定，避免与主判定流程混在一处：
     *
     * | 入口 | callingPackage | realCallingUid | resultTo | action |
     * |---|---|---|---|---|
     * | 图标点击 | 桌面 | 桌面 | 有 | MAIN |
     * | 长按快捷项 | 目标应用 | 桌面 | 无 | 任意（可能为 MAIN） |
     * | 快捷菜单（应用详情） | 桌面 | 桌面 | 无 | 非 MAIN |
     * | 卡片（含负一屏） | 桌面 | 桌面 | 有 | 非 MAIN |
     *
     * 图标点击是唯一必须保持全屏的一类：每次打开应用都改小窗会破坏桌面最基本的行为。
     *
     * @return 命中时返回场景标识，否则返回 null 交由主流程继续判定。
     */
    private fun launcherOriginReason(
        intent: Intent,
        target: String,
        callingPackage: String?,
        realCallingUid: Int,
        requestRealUid: Int,
        hasResultTo: Boolean,
    ): String? {
        val fromLauncherProcess = callingPackage == LAUNCHER_PACKAGE
        // 卡片页：桌面以 startActivityForResult 打开，携带卡片自己的 action。
        // 确认类界面（安装器、分享面板）列在排除名单内，仍按全屏处理。
        if (fromLauncherProcess &&
            hasResultTo &&
            intent.action != Intent.ACTION_MAIN &&
            target !in LAUNCHER_CARD_SKIP
        ) {
            return "launcher_card"
        }
        // 快捷菜单里的系统设置入口（应用详情 / 应用信息）：桌面直接发起，不带 resultTo。
        // 设置页整包在保持全屏名单内，仅这一条发起路径放行；应用内跳设置、权限授权页不变。
        if (fromLauncherProcess &&
            !hasResultTo &&
            intent.action != Intent.ACTION_MAIN &&
            target in LAUNCHER_SETTINGS_TARGETS
        ) {
            return "launcher_settings"
        }
        // 长按快捷项：系统经 LauncherApps.startShortcut 解析出快捷方式的 Intent 后，
        // 以「快捷方式所属包」作为 callingPackage 代发，realCallingUid 仍是桌面。
        // action 不参与判定：快捷项的内核就是应用自己的启动入口，QQ 这类直接以
        // ACTION_MAIN 加参数下发（实测 flags 0x14000000）。
        val launcherUid = launcherUid()
        val fromShortcut =
            callingPackage != null &&
                callingPackage == target &&
                launcherUid > 0 &&
                (realCallingUid == launcherUid || requestRealUid == launcherUid)
        if (fromShortcut && !hasResultTo && target !in SKIP_TARGETS) {
            return "launcher_shortcut"
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
        }.onFailure { module.log(Log.WARN, TAG, "ZOOM_BANNER_COLLAPSE_FAILED", it) }
    }

    /** 同步收起通知面板/横幅：不排延迟，调用即发出收起请求。 */
    private fun collapseShadeImmediately() {
        runCatching {
            val statusBar = systemContext()?.getSystemService("statusbar")
            if (statusBar != null) {
                statusBar.javaClass.getMethod("collapsePanels").invoke(statusBar)
                ModuleLog.debug(TAG) { "ZOOM_SHADE_IMMEDIATE" }
            }
        }.onFailure { module.log(Log.WARN, TAG, "ZOOM_SHADE_IMMEDIATE_FAILED", it) }
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
                }.onFailure { module.log(Log.WARN, TAG, "ZOOM_SHADE_COLLAPSE_FAILED", it) }
                Unit
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

    /** 桌面 uid：用于识别经 LauncherApps 代发的桌面快捷方式。 */
    private fun launcherUid(): Int {
        cachedLauncherUid?.let { return it }
        val uid = runCatching {
            systemContext()?.packageManager?.getApplicationInfo(LAUNCHER_PACKAGE, 0)?.uid ?: 0
        }.getOrDefault(0)
        cachedLauncherUid = uid
        return uid
    }

    private fun optionsBundle(starter: Any, request: Any?): Bundle? {
        val options =
            readField(starter, "mOptions")
                ?: request?.let { readField(it, "activityOptions") }
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

    private fun readField(instance: Any, name: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (exception: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
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

        /** 控制中心启动应用时携带的字段。 */
        const val EXTRA_QUICK_SETTINGS = "start_from_systemui_qs"

        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 桌面进程：其发起的启动属于图标点击，不计入应用间跳转。 */
        const val LAUNCHER_PACKAGE = "com.android.launcher"

        /** 文件选择与文档选取类动作：始终全屏。 */
        val PICKER_ACTIONS =
            setOf(
                "android.intent.action.GET_CONTENT",
                "android.intent.action.OPEN_DOCUMENT",
                "android.intent.action.OPEN_DOCUMENT_TREE",
                "android.intent.action.PICK",
            )

        /**
         * 分享面板的组件：包名与类名片段。
         *
         * 分享面板由系统包承载，但在 manifest 的排除名单内；作为用户主动发起的跳转，
         * 此处按组件精确放行。文件选择与权限授权界面保持全屏，不在名单内。
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
         * 「第三方应用」判据，因此按实测落点单独登记。第三方应用由同一分支的
         * 另一条判据覆盖，无需列入此处。
         */
        val SYSTEM_NOTIFICATION_TARGETS = setOf("org.lsposed.manager", "com.android.shell")


        /** 桌面快捷菜单可进入的系统设置包：仅「应用详情」这类入口按小窗打开。 */
        val LAUNCHER_SETTINGS_TARGETS = setOf("com.android.settings", "com.coloros.settings")
        const val OPLUS_LIST_MANAGER = "com.android.server.OplusListManagerImpl"
        const val ZOOM_MANAGER_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"
        const val LAUNCH_AT_RIGHT = 14

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

        /** 保持全屏启动的目标包。 */
        val SKIP_TARGETS =
            setOf(
                "com.android.systemui",
                "com.android.settings",
                "com.coloros.settings",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "android",
            )

        /**
         * 桌面发起（卡片或长按菜单）时保持全屏的目标。
         *
         * 在 SKIP_TARGETS 之外再排除三类：
         * 1. 卸载确认与分享面板 —— 流程中的确认界面，放大到小窗不可用；
         * 2. 桌面自身的页面 —— 编辑模式、文件夹、桌面设置都属于桌面的一部分，
         *    脱离桌面单独开成小窗既无意义也无法返回原位置；
         * 3. 应用锁入口（安全中心）—— 它紧接着要拉起全屏的密码确认页，
         *    入口开成小窗会让同一流程前后窗口形态不一致。
         *
         * 该名单只作用于第六段的卡片分支，不影响桌面快捷项、应用详情、
         * 应用互跳、通知与控制中心。
         */
        val LAUNCHER_CARD_SKIP =
            SKIP_TARGETS +
                setOf(
                    "com.android.packageinstaller",
                    "com.google.android.packageinstaller",
                    "com.android.intentresolver",
                    "com.google.android.intentresolver",
                    LAUNCHER_PACKAGE,
                    "com.oplus.safecenter",
                    "com.coloros.safecenter",
                )


    }
}