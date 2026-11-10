package io.github.hoerire.flymefreeform.hook.launch

import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration

/**
 * 通知点击入口。
 *
 * 采用系统浮窗通道：OplusStatusBarNotificationActivityStarterExImpl.onStartFloatWindow
 * 携带 RemoteAnimationAdapter 进入 setZoomParamsForStart，写入窗口模式 100、
 * zoomFlags 5 与 LaunchScenario 1，并同时收起通知面板、播放窗口动画。
 *
 * 普通点击经 PendingIntent.send 下发，不经过该链路，因此在此改道。
 * 改道条件不成立时不作处理，由 [ActivityStarterZoomInjector] 按来源标记补参数。
 */
internal class NotificationZoomSourceHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    fun install(classLoader: ClassLoader) {
        val target =
            try {
                classLoader.loadClass(STARTER_CLASS)
            } catch (exception: ClassNotFoundException) {
                module.log(Log.WARN, TAG, "ZOOM_NOTIFICATION_TARGET_UNAVAILABLE", exception)
                return
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "ZOOM_NOTIFICATION_TARGET_LINKAGE_FAILED", exception)
                return
            }
        val methods =
            target.declaredMethods.filter { method ->
                method.name == "startNotificationIntent" && method.parameterCount == 7
            }
        if (methods.isEmpty()) {
            module.log(Log.WARN, TAG, "ZOOM_NOTIFICATION_METHOD_MISSING", null)
            return
        }
        methods.forEach { method ->
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.zoom.notification." + method.parameterCount)
                .intercept { chain ->
                    if (enabled()) {
                        runCatching {
                            (chain.getArg(1) as? Intent)?.putExtra(
                                ZoomLaunchSpec.EXTRA_SOURCE,
                                ZoomLaunchSpec.SOURCE_NOTIFICATION,
                            )
                        }.onFailure {
                            module.log(Log.WARN, TAG, "ZOOM_NOTIFICATION_MARK_FAILED", it)
                        }
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, TAG, "ZOOM_NOTIFICATION_HOOK_INSTALLED", null)
        }
        installFloatWindowClick(target)
        installEntryProbe(classLoader)
    }

    /** 诊断用：确认通知中心点击实际经过哪个入口。 */
    private fun installEntryProbe(classLoader: ClassLoader) {
        val exClass =
            runCatching { classLoader.loadClass(EX_IMPL_CLASS) }.getOrNull()
                ?: run {
                    io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_PROBE_EX_MISSING" }
                    return
                }
        // 通知点击在部分机型上由 NotificationClickNotifier 统一广播，先确认该入口是否被调用。
        runCatching {
            val notifier = classLoader.loadClass(NOTIFIER_CLASS)
            notifier.declaredMethods
                .filter { it.name == "onNotificationClick" }
                .forEach { method ->
                    method.isAccessible = true
                    module
                        .hook(method)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("flymefreeform.zoom.probe.notifier." + method.parameterCount)
                        .intercept { chain ->
                            io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_PROBE_notifier_" + method.parameterCount }
                            chain.proceed()
                        }
                }
        }.onFailure {
            io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_PROBE_NOTIFIER_MISSING" }
        }
        listOf("startNotificationIntent", "sendAndReturnResult", "onStartFloatWindow").forEach { name ->
            exClass.declaredMethods.filter { it.name == name }.forEach { method ->
                method.isAccessible = true
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.zoom.probe." + name + "." + method.parameterCount)
                    .intercept { chain ->
                        io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_PROBE_" + name + "_" + method.parameterCount }
                        chain.proceed()
                    }
            }
        }
    }

    /**
     * 普通点击入口为 onNotificationClicked(entry, row)，其中 row 是浮窗链路必需参数。
     * 转调 onStartFloatWindow 等价于系统长按菜单中的浮窗打开入口。
     */
    private fun installFloatWindowClick(starter: Class<*>) {
        val method =
            starter.declaredMethods.firstOrNull { candidate ->
                candidate.name == "onNotificationClicked" && candidate.parameterCount == 2
            } ?: run {
                module.log(Log.WARN, TAG, "ZOOM_FLOAT_CLICK_METHOD_MISSING", null)
                return
            }
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.zoom.float_click")
            .intercept { chain ->
                if (!enabled()) return@intercept chain.proceed()
                val starter = chain.thisObject
                val row = chain.getArg(1)
                // 面板展开时先收起，再进入浮窗通道：系统在面板未收起时不会构建
                // RemoteAnimationAdapter，窗口会先黑屏再突然出现。
                val expanded = runCatching { isShadeExpanded(starter) }.getOrDefault(false)
                io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_FLOAT_CLICK_expanded_" + expanded }
                if (expanded) {
                    runCatching { collapseShade(starter) }
                    mainHandler.postDelayed(
                        {
                            val handled =
                                runCatching { startFloatWindow(starter, row) }
                                    .onFailure {
                                        module.log(Log.WARN, TAG, "ZOOM_FLOAT_CLICK_FAILED", it)
                                    }
                                    .getOrDefault(false)
                            io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_FLOAT_CHANNEL_DELAYED_" + handled }
                            Unit
                        },
                        SHADE_COLLAPSE_DELAY_MS,
                    )
                    return@intercept null
                }
                val handled =
                    runCatching { startFloatWindow(starter, row) }
                        .onFailure { module.log(Log.WARN, TAG, "ZOOM_FLOAT_CLICK_FAILED", it) }
                        .getOrDefault(false)
                if (handled) null else chain.proceed()
            }
        module.log(Log.INFO, TAG, "ZOOM_FLOAT_CLICK_HOOK_INSTALLED", null)
    }

    private fun startFloatWindow(starter: Any?, row: Any?): Boolean {
        if (starter == null || row == null) return false
        val ex = readField(starter, "mActivityStarterEx") ?: return false
        val method =
            ex.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "onStartFloatWindow" && candidate.parameterCount == 3
            } ?: return false
        method.isAccessible = true
        val handled = method.invoke(ex, starter, row, 0f) as? Boolean ?: false
        if (handled) io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_FLOAT_CHANNEL_USED" }
        return handled
    }

    /** 面板是否处于展开状态。 */
    private fun isShadeExpanded(starter: Any?): Boolean {
        if (starter == null) return false
        val interactor = readField(starter, "mPanelExpansionInteractor") ?: return false
        val method =
            interactor.javaClass.methods.firstOrNull { candidate ->
                candidate.name in EXPANSION_QUERIES && candidate.parameterCount == 0
            } ?: return false
        return method.invoke(interactor) as? Boolean ?: false
    }

    /** 收起通知面板，使浮窗通道能够构建过渡动画。 */
    private fun collapseShade(starter: Any?): Boolean {
        if (starter == null) return false
        val context = readField(starter, "mContext") as? android.content.Context ?: return false
        return runCatching {
            val statusBar = context.getSystemService("statusbar")
            if (statusBar != null) {
                statusBar.javaClass.getMethod("collapsePanels").invoke(statusBar)
                true
            } else {
                false
            }
        }.getOrDefault(false)
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

    private fun enabled(): Boolean {
        val settings = configuration.snapshot
        return settings.zoomLaunchNoticeEffective
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private companion object {
        const val TAG = "FlymeFreeform"

        /** 面板收起后到进入浮窗通道的间隔。 */
        const val SHADE_COLLAPSE_DELAY_MS = 220L

        val EXPANSION_QUERIES = setOf("isPanelExpanded", "isExpanded")

        const val EX_IMPL_CLASS =
            "com.oplus.systemui.statusbar.phone.OplusStatusBarNotificationActivityStarterExImpl"
        const val NOTIFIER_CLASS = "com.android.systemui.statusbar.NotificationClickNotifier"
        const val STARTER_CLASS = "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter"
    }
}
