package io.github.hoerire.flymefreeform.hook.launch

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration

/**
 * 解除系统对小窗的应用准入与数量限制。
 *
 * 判定分布于三个系统类：
 * - FlexibleWindowManagerService#isSupportFlexibleWindow 与 getMaxWinNum：服务端接口，
 *   数量上限由此返回；
 * - FlexibleTaskController#isSupportFlexibleWindow：决定任务能否进入小窗；
 * - FlexibleWindowUtils#isSupportFlexibleWindow：按 Intent 与 ActivityInfo 判定的底层实现，
 *   界面提示「当前应用未支持小窗」由该处产生。
 *
 * 三个类需同时放行，任一遗漏仍会按原厂黑名单拦截。
 */
internal class FlexibleWindowLimitHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    fun install(classLoader: ClassLoader) {
        var total = 0
        total += installOn(classLoader, MANAGER_CLASS)
        total += installOn(classLoader, CONTROLLER_CLASS)
        total += installOn(classLoader, UTILS_CLASS)
        module.log(Log.INFO, TAG, "ZOOM_LIMIT_HOOK_INSTALLED_" + total, null)
    }

    private fun installOn(classLoader: ClassLoader, className: String): Int {
        val target =
            try {
                classLoader.loadClass(className)
            } catch (exception: ClassNotFoundException) {
                module.log(Log.WARN, TAG, "ZOOM_LIMIT_CLASS_MISSING_" + shortName(className), exception)
                return 0
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "ZOOM_LIMIT_CLASS_LINKAGE_" + shortName(className), exception)
                return 0
            }
        var count = 0
        target.declaredMethods.forEach { method ->
            val isSupport =
                method.name == "isSupportFlexibleWindow" &&
                    method.returnType == Boolean::class.javaPrimitiveType
            val isMaxWinNum =
                method.name == "getMaxWinNum" && method.returnType == Int::class.javaPrimitiveType
            if (!isSupport && !isMaxWinNum) return@forEach
            method.isAccessible = true
            val hookId =
                "flymefreeform.limit." + shortName(className) + "." + method.name + "." + method.parameterCount
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(hookId)
                    .intercept { chain ->
                        // 两个子功能各自独立生效：数量上限与应用准入互不依赖。
                        val settings = configuration.snapshot
                        if (!settings.enabled) {
                            chain.proceed()
                        } else if (isMaxWinNum) {
                            // 直接作为上限值：这是用户在设置里明确指定的个数，不做放宽合并。
                            settings.zoomMaxWindowCount
                        } else if (settings.zoomAllowAnyApp) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                count++
            }.onFailure {
                module.log(Log.WARN, TAG, "ZOOM_LIMIT_HOOK_FAILED_" + hookId, it)
            }
        }
        io.github.hoerire.flymefreeform.log.ModuleLog.debug(
            TAG,
            "ZOOM_LIMIT_CLASS_DONE_" + shortName(className) + "_" + count,
        )
        return count
    }

    private fun shortName(className: String): String = className.substringAfterLast('.')

    private companion object {
        const val TAG = "FlymeFreeform"
        const val MANAGER_CLASS = "com.android.server.wm.FlexibleWindowManagerService"
        const val CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val UTILS_CLASS = "com.android.server.wm.FlexibleWindowUtils"
    }
}
