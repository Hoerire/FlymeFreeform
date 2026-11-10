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
    private val environment: io.github.hoerire.flymefreeform.hook.ModuleEnvironmentState? = null,
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
                        // 排除列表内的应用在前台时整体让行，保持系统原值。
                        val settings = configuration.snapshot
                        // 先让链继续（系统与其他模块都有机会参与），再决定是否覆盖返回值，
                        // 关闭本功能时原样返回，不与其它模块争抢。
                        val original = chain.proceed()
                        if (!settings.enabled || environment?.isForegroundExcluded() == true) {
                            original
                        } else if (isMaxWinNum) {
                            // 直接作为上限值：这是用户在设置里明确指定的个数，不做放宽合并。
                            settings.zoomMaxWindowCount
                        } else if (isSupport && original == true && shouldBlockSystemSheet(chain.args)) {
                            // 系统自己要把分享面板/选择器开成小窗：否掉，让它全屏。
                            // 我们注入的那条路带着 EXTRA_APPLIED 标记，不受影响。
                            false
                        } else if (settings.zoomAllowAnyApp) {
                            true
                        } else {
                            original
                        }
                    }
                count++
            }.onFailure {
                module.log(Log.WARN, TAG, "ZOOM_LIMIT_HOOK_FAILED_" + hookId, it)
            }
        }
        io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_LIMIT_CLASS_DONE_" + shortName(className) + "_" + count }
        return count
    }

    /**
     * 是否要否决这次"开成小窗"。
     *
     * 只针对分享面板 / 系统选择器，且只针对不是我们发起的那次（没有注入标记）。
     * ColorOS 17 会自己把分享页开成柔性窗口，用户关掉「小窗跳转」也拦不住；
     * 点选目标后那个窗口退到后台还会被收成贴边把手。这里直接让它全屏。
     */
    private fun shouldBlockSystemSheet(args: List<Any?>): Boolean {
        val intent = args.filterIsInstance<android.content.Intent>().firstOrNull()
            ?: args.firstNotNullOfOrNull { candidate ->
                candidate?.let { task ->
                    runCatching {
                        task.javaClass.getMethod("getBaseIntent").invoke(task) as? android.content.Intent
                    }.getOrNull()
                }
            }
            ?: return false
        if (intent.hasExtra(ZoomLaunchSpec.EXTRA_APPLIED)) return false
        val target = intent.component?.packageName
            ?: runCatching {
                intent.javaClass.getMethod("getPackage").invoke(intent) as? String
            }.getOrNull()
            ?: return false
        if (target !in SYSTEM_SHEET_PACKAGES) return false
        io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_BLOCK_SYSTEM_SHEET_" + target }
        return true
    }

    private fun shortName(className: String): String = className.substringAfterLast('.')

    private companion object {
        const val TAG = "FlymeFreeform"
        const val MANAGER_CLASS = "com.android.server.wm.FlexibleWindowManagerService"
        const val CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
        const val UTILS_CLASS = "com.android.server.wm.FlexibleWindowUtils"

        /** 系统分享面板与选择器所在的包：这些界面开成小窗必然出问题。 */
        val SYSTEM_SHEET_PACKAGES = setOf(
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            "android",
        )
    }
}
