package io.github.hoerire.flymefreeform.hook

import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 通知点击小窗：ColorOS 在 SystemUI 侧用 setNotificationTypeStarterLog(Bundle, NotificationEntry)
 * 组装启动参数，随后把 Bundle 交给 system_server 的 ActivityStarter。
 *
 * 在方法执行前改写这个 Bundle 即可决定窗口形态：
 *  - extra_window_mode / android:activity.windowingMode = 100（自由窗口）
 *  - android:activity.mZoomLaunchFlags = 5
 *  - androidx.activity.LaunchScenario = 1
 *
 * 同时必须删掉 Bundle 里所有 remote + animation/transition 键。这些键让 SystemUI
 * 把本次启动的转场交给横幅那一层做，删掉后横幅不再参与转场，窗口直接起在自由窗层，
 * 因此不会被横幅压住 —— 这是「点开不被横幅盖住」的关键。
 */
internal class NotifBundleHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    @Volatile
    private var headsUpManager: Any? = null

    fun install(classLoader: ClassLoader) {
        hookHeadsUpManager(classLoader)
        for (name in CANDIDATES) {
            val target = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            val method =
                target.declaredMethods.firstOrNull { candidate ->
                    candidate.name == METHOD_NAME && candidate.parameterCount == 2
                } ?: continue
            method.isAccessible = true
            val hooked = runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.notif.bundle." + target.simpleName)
                    .intercept { chain ->
                        runCatching { rewriteBundle(chain.args) }
                        chain.proceed()
                    }
            }.isSuccess
            if (hooked) {
                module.log(Log.INFO, TAG, "NOTIF_BUNDLE_HOOKED_" + target.simpleName)
                return
            }
        }
        module.log(Log.WARN, TAG, "NOTIF_BUNDLE_HOOK_UNAVAILABLE", null)
    }

    private fun rewriteBundle(args: List<Any?>) {
        val settings = configuration.snapshot
        if (!settings.enabled || !settings.zoomLaunchNotification) return
        val bundle = args.getOrNull(0) as? Bundle ?: return
        val entry = args.getOrNull(1)
        val pkg = packageOf(entry)
        if (pkg != null && pkg in SKIP_PACKAGES) return

        // 启动前先把横幅收掉：窗口起来时它已经不在了，才不会被压住。
        dismissHeadsUp(entry)

        val dropped = dropRemoteTransition(bundle)
        bundle.putInt(KEY_EXTRA_WINDOW_MODE, VALUE_FREEFORM)
        bundle.putInt(KEY_ZOOM_LAUNCH_FLAGS, 5)
        bundle.putInt(KEY_LAUNCH_SCENARIO, 1)
        bundle.putInt(KEY_WINDOWING_MODE, VALUE_FREEFORM)

        ModuleLog.debug(TAG, "NOTIF_BUNDLE_ZOOM pkg=" + pkg + " dropped=" + dropped)
    }

    /**
     * HeadsUpManagerImpl 的实例只在构造时拿得到，这里挂构造函数保存引用。
     * 有了它才能在启动前把横幅移除（isHeadsUpEntry → removeNotification）。
     */
    private fun hookHeadsUpManager(classLoader: ClassLoader) {
        val target =
            runCatching { classLoader.loadClass(HEADS_UP_CLASS) }.getOrNull()
                ?: run {
                    module.log(Log.WARN, TAG, "NOTIF_HEADS_UP_CLASS_MISSING", null)
                    return
                }
        var hooks = 0
        target.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            runCatching {
                module
                    .hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.notif.headsup." + constructor.parameterCount)
                    .intercept { chain ->
                        val result = chain.proceed()
                        headsUpManager = chain.thisObject
                        result
                    }
                hooks++
            }
        }
        // 实例通常早于本钩子被 Dagger 创建，构造函数不会再触发；
        // showNotification 每次横幅弹出都会走，用它把实例抓下来。
        var showHooked = false
        runCatching {
            val show =
                target.declaredMethods.firstOrNull {
                    it.name == "showNotification" && it.parameterCount == 2
                } ?: return@runCatching
            show.isAccessible = true
            module
                .hook(show)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.notif.headsup.show")
                .intercept { chain ->
                    headsUpManager = chain.thisObject
                    chain.proceed()
                }
            showHooked = true
        }.onFailure { module.log(Log.WARN, TAG, "NOTIF_HEADS_UP_SHOW_HOOK_FAILED", it) }
        module.log(Log.INFO, TAG, "NOTIF_HEADS_UP_CTOR_HOOKS_" + hooks + "_SHOW_" + showHooked)
    }

    private fun dismissHeadsUp(entry: Any?) {
        val manager = headsUpManager
        if (manager == null) {
            ModuleLog.debug(TAG, "NOTIF_HEADS_UP_MANAGER_NULL")
            return
        }
        val key = keyOf(entry)
        if (key == null) {
            ModuleLog.debug(TAG, "NOTIF_HEADS_UP_KEY_NULL")
            return
        }
        runCatching {
            val isHeadsUp =
                manager.javaClass.methods
                    .firstOrNull { it.name == "isHeadsUpEntry" && it.parameterCount == 1 }
                    ?.invoke(manager, key) as? Boolean
            if (isHeadsUp != true) {
                ModuleLog.debug(TAG, "NOTIF_HEADS_UP_NOT_HEADSUP " + key)
                return
            }
            val removed =
                manager.javaClass.methods
                    .firstOrNull { it.name == "removeNotification" && it.parameterCount == 3 }
                    ?.invoke(manager, key, true, "flymefreeform")
            ModuleLog.debug(TAG, "NOTIF_HEADS_UP_DISMISSED " + key + " -> " + (removed ?: "void"))
        }.onFailure { module.log(Log.WARN, TAG, "NOTIF_HEADS_UP_DISMISS_FAILED", it) }
    }

    private fun keyOf(entry: Any?): String? {
        if (entry == null) return null
        return runCatching {
            entry.javaClass.methods
                .firstOrNull { it.name == "getKey" && it.parameterCount == 0 }
                ?.invoke(entry) as? String
        }.getOrNull()
    }

    /** 返回被删掉的键，便于在日志里确认转场确实被剥掉。 */
    private fun dropRemoteTransition(bundle: Bundle): String {
        val keys = runCatching { bundle.keySet().toTypedArray() }.getOrDefault(emptyArray())
        val removed = StringBuilder()
        for (key in keys) {
            val lower = key.lowercase()
            if (!lower.contains("remote")) continue
            if (!lower.contains("animation") && !lower.contains("transition")) continue
            runCatching { bundle.remove(key) }
            if (removed.isNotEmpty()) removed.append(',')
            removed.append(key)
        }
        return if (removed.isEmpty()) "-" else removed.toString()
    }

    private fun packageOf(entry: Any?): String? {
        if (entry == null) return null
        return runCatching {
            val sbn = entry.javaClass.getMethod("getSbn").invoke(entry)
            sbn?.javaClass?.getMethod("getPackageName")?.invoke(sbn) as? String
        }.getOrNull()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val METHOD_NAME = "setNotificationTypeStarterLog"
        const val HEADS_UP_CLASS =
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl"
        const val KEY_EXTRA_WINDOW_MODE = "extra_window_mode"
        const val KEY_ZOOM_LAUNCH_FLAGS = "android:activity.mZoomLaunchFlags"
        const val KEY_LAUNCH_SCENARIO = "androidx.activity.LaunchScenario"
        const val KEY_WINDOWING_MODE = "android:activity.windowingMode"
        const val VALUE_FREEFORM = 100
        val SKIP_PACKAGES = setOf("com.android.systemui", "android")
        val CANDIDATES = listOf(
            "com.oplus.systemui.statusbar.phone.OplusStatusBarNotificationActivityStarterExImpl",
            "com.android.systemui.statusbar.phone.OplusStatusBarNotificationActivityStarterExImpl",
            "com.oplus.systemui.statusbar.phone.StatusBarNotificationActivityStarterExImpl",
        )
    }
}
