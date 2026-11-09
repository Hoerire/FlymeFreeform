package io.github.hoerire.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * 以 ColorOS 自由窗（zoom window）方式启动应用。
 *
 * 参数逐项对照系统智能侧边栏的 `PageRoutUtils.startActivityAsZoomWindow`：
 * - `extra_window_mode = 100`：小窗/自由窗窗口模式；
 * - `android.activity.splashScreenStyle = 1`：允许应用自己的起始窗口立即上屏，
 *   否则要等应用画出首帧才出现（实测相差约 1 秒）；
 * - `start_zoom_at_other_side`：13/14 指定窗口从左侧/右侧出现，避免系统再算一次落点。
 *
 * 启动顺序：先尝试厂商 `OplusZoomWindowManager.startZoomWindow`（与侧边栏同一条路径，
 * 由侧边栏 uid 发起），失败则退化为携带同一 Bundle 的 `startActivity`。
 * 每次启动前重新校验组件可用性，避免清单变化后启动到不存在的 Activity。
 */
internal class ColorOsFreeformLauncher(
    private val context: Context,
    /**
     * 该包是否禁止以浮窗打开。
     *
     * 扇形走厂商 zoom 入口，不经过 ActivityStarter，那边的三道闸门管不到这里，
     * 所以启动源头必须自己判一次；命中就退化成普通全屏启动。
     */
    private val isZoomBlocked: (String) -> Boolean = { false },
    /**
     * 该包是否已经是屏幕上的顶层应用。
     *
     * 前台应用再走一次小窗启动，会命中系统的「替换已有窗口」分支：任务原地从全屏改成
     * 小窗，而且没有过渡动画（实测是突然缩小）。扇形对当前应用没有「再打开一次」的语义，
     * 因此直接拦掉，什么都不做。
     */
    private val isForeground: (String) -> Boolean = { false },
    /**
     * 该包是否已经有一个正常形态的小窗开着。
     *
     * 重复点同一个应用只会把已有窗口再走一遍启动链路，没有「再打开一个」的语义；
     * 只有贴边浮标（迷你窗）不算，那种情况仍走 [io.github.hoerire.flymefreeform.hook.FloatingTaskRestorer]
     * 恢复成小窗。
     */
    private val hasOpenWindow: (String) -> Boolean = { false },
) {
    /** 详细日志：仅在「详细日志」开启时输出，与其他模块日志同一出口。 */
    private fun debug(code: String) {
        io.github.hoerire.flymefreeform.log.ModuleLog.debug("FlymeFreeform") { code }
    }

    fun launch(requested: ComponentName, atLeft: Boolean = true): FreeformLaunchResult {
        if (isForeground(requested.packageName)) {
            debug("SYSTEM_LAUNCH_SKIP_FOREGROUND_" + requested.packageName)
            return FreeformLaunchResult.AlreadyForeground
        }
        if (hasOpenWindow(requested.packageName)) {
            debug("SYSTEM_LAUNCH_SKIP_ALREADY_OPEN_" + requested.packageName)
            return FreeformLaunchResult.AlreadyOpen
        }
        var component = requested
        var reason = unavailableReason(component)
        if (reason != null) {
            // 图标包类应用（Telegram 系常见）把启动 Activity 做成多个 activity-alias，
            // 未启用的别名会在清单里 disabled；此时改用它当前的启动入口。
            val fallback = enabledLauncherComponent(requested.packageName, requested)
            if (fallback != null) {
                component = fallback
                reason = null
                debug("SYSTEM_LAUNCH_ALIAS_SWITCHED_" + requested.className + "_TO_" + fallback.className)
            }
        }
        // 最后一级兜底：别名/组件都对不上时，交给系统按包名解析当前入口。
        // 系统会自行挑选启用中的 activity-alias，避免按过期的类名硬启动。
        val byPackage = reason != null
        if (reason != null) {
            debug("SYSTEM_LAUNCH_RESOLVE_BY_PACKAGE_" + requested.packageName + "_" + reason)
        }
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply {
                    if (byPackage) {
                        setPackage(requested.packageName)
                    } else {
                        setComponent(component)
                    }
                }
        // 命中「禁用浮窗」的目标一律全屏：不带任何 window mode 参数启动。
        if (isZoomBlocked(intent.component?.packageName ?: requested.packageName)) {
            val target = intent.component?.packageName ?: requested.packageName
            debug("SYSTEM_LAUNCH_FULLSCREEN_EXCLUDED_" + target)
            return try {
                context.startActivity(intent)
                FreeformLaunchResult.Started
            } catch (exception: SecurityException) {
                FreeformLaunchResult.Failed("FREEFORM_LAUNCH_SECURITY_REJECTED", exception)
            } catch (exception: RuntimeException) {
                FreeformLaunchResult.Failed("FREEFORM_LAUNCH_START_FAILED", exception)
            }
        }
        val options = createLaunchOptions(atLeft)

        // 目标已在浮标里：按"点浮标"的方式恢复成小窗，而不是新开窗口
        // （新开会沿用该任务的旧非全屏尺寸，表现成迷你窗）。
        if (io.github.hoerire.flymefreeform.hook.FloatingTaskRestorer.restore(requested.packageName)) {
            debug("SYSTEM_LAUNCH_FLOAT_RESTORED_" + requested.packageName)
            return FreeformLaunchResult.Started
        }

        if (startAsZoomWindow(intent, options)) {
            debug("SYSTEM_LAUNCH_ZOOM_OK_" + requested.packageName)
            return FreeformLaunchResult.Started
        }
        debug("SYSTEM_LAUNCH_ZOOM_FALLBACK_" + requested.packageName)
        return try {
            context.startActivity(intent, options)
            FreeformLaunchResult.Started
        } catch (exception: SecurityException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_SECURITY_REJECTED", exception)
        } catch (exception: RuntimeException) {
            FreeformLaunchResult.Failed("FREEFORM_LAUNCH_START_FAILED", exception)
        }
    }

    /**
     * 解析该包当前真正可用的启动入口。
     *
     * 图标包类应用（Telegram 系常见）把启动 Activity 做成多个 activity-alias，
     * 未启用的别名在清单里是 disabled，直接按记录的别名启动会失败。
     * 顺序：系统解析的启动入口 → LauncherApps 里该包当前可启动的入口。
     */
    private fun enabledLauncherComponent(packageName: String, requested: ComponentName): ComponentName? {
        try {
            context.packageManager.getLaunchIntentForPackage(packageName)?.component?.let { resolved ->
                if (resolved != requested && unavailableReason(resolved) == null) return resolved
            }
        } catch (_: RuntimeException) {
            // 继续走枚举兜底。
        }
        // LauncherApps 只返回当前启用、可被桌面启动的入口，正好用于别名切换后的兜底。
        val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java) ?: return null
        return try {
            launcherApps
                .getActivityList(packageName, android.os.Process.myUserHandle())
                .firstOrNull { it.componentName != requested }
                ?.componentName
                ?.takeIf { unavailableReason(it) == null }
        } catch (_: RuntimeException) {
            null
        }
    }

    /** 目标不可用的具体原因；null 表示可用。 */
    private fun unavailableReason(component: ComponentName): String? =
        try {
            val info = context.packageManager
                .getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
            // 不再要求 exported：本模块在 system_server（uid 1000）内启动，
            // 具备 START_ANY_ACTIVITY，系统应用把启动 Activity 标成 exported=false 也能拉起；
            // 之前按 exported 拦截，会把这类应用一律判成"目标不可用"而根本不去启动。
            when {
                !info.applicationInfo.enabled -> "APP_DISABLED"
                !info.enabled -> "ACTIVITY_DISABLED"
                else -> null
            }
        } catch (_: PackageManager.NameNotFoundException) {
            "NOT_FOUND"
        } catch (_: RuntimeException) {
            "QUERY_FAILED"
        }

    /**
     * 启动参数，逐项对齐原生侧边栏（实测抓包）：
     * `launchDisplayId=0`、`splashScreenStyle=1`、`mZoomLaunchFlags=0`、
     * `extra_window_mode=100`、`start_zoom_at_other_side`（13 左 / 14 右）。
     *
     * 关键：原生**不传** `android.activity.windowingMode`。之前我们多塞了这一项，
     * 系统因此不走"替换已有窗口"的分支，多出来的窗口被收成浮标。
     * 另外 splashScreenStyle 必须放在平台 options 之后写，否则会被覆盖成 -1。
     */
    private fun createLaunchOptions(atLeft: Boolean): Bundle =
        Bundle().apply {
            createPlatformOptions()?.let(::putAll)
            putInt(ZOOM_FLAGS_KEY, 0)
            putInt(LAUNCH_DISPLAY_KEY, 0)
            putInt(SPLASH_SCREEN_STYLE_KEY, 1)
            putInt(EXTRA_WINDOW_MODE_KEY, FLEXIBLE_WINDOWING_MODE)
            putInt(LAUNCH_SIDE_KEY, if (atLeft) LAUNCH_AT_LEFT else LAUNCH_AT_RIGHT)
        }

    /** 走厂商 zoom 入口；不可用或返回失败码时回落到普通启动。 */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun startAsZoomWindow(intent: Intent, options: Bundle): Boolean =
        try {
            val managerClass = Class.forName(ZOOM_MANAGER_CLASS)
            val instance = managerClass.getMethod("getInstance").invoke(null) ?: return false
            val start = managerClass.getMethod(
                "startZoomWindow",
                Intent::class.java,
                Bundle::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val result = start.invoke(instance, intent, options, currentUserId(), context.packageName) as? Int
            result != null && result >= 0
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }

    /** `UserHandle.myUserId` 不在公开 SDK，交给反射取；取不到就按 0 号用户。 */
    private fun currentUserId(): Int =
        try {
            (Class.forName("android.os.UserHandle").getMethod("myUserId").invoke(null) as? Int) ?: 0
        } catch (_: ReflectiveOperationException) {
            0
        } catch (_: RuntimeException) {
            0
        }

    /** 平台侧 options：不再设置 windowingMode，原生只靠 `extra_window_mode` 表达小窗。 */
    @SuppressLint("BlockedPrivateApi")
    private fun createPlatformOptions(): Bundle? =
        try {
            ActivityOptions.makeBasic().toBundle()
        } catch (_: RuntimeException) {
            null
        }
    private companion object {
        const val FLEXIBLE_WINDOWING_MODE = 100
        const val ZOOM_LAUNCH_FLAG = 4
        const val LAUNCH_DISPLAY_KEY = "android.activity.launchDisplayId"
        const val ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"
        const val EXTRA_WINDOW_MODE_KEY = "extra_window_mode"
        const val SPLASH_SCREEN_STYLE_KEY = "android.activity.splashScreenStyle"
        const val LAUNCH_SIDE_KEY = "start_zoom_at_other_side"
        const val LAUNCH_AT_LEFT = 13
        const val LAUNCH_AT_RIGHT = 14
        const val ZOOM_MANAGER_CLASS = "com.oplus.zoomwindow.OplusZoomWindowManager"
    }
}

internal sealed interface FreeformLaunchResult {
    data object Started : FreeformLaunchResult

    /** 目标已经是顶层应用，不再重新启动。 */
    data object AlreadyForeground : FreeformLaunchResult

    /** 目标已经有一个小窗开着，不再重新启动。 */
    data object AlreadyOpen : FreeformLaunchResult

    data class TargetUnavailable(
        val component: ComponentName,
        val reason: String,
    ) : FreeformLaunchResult

    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable,
    ) : FreeformLaunchResult
}