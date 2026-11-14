package io.github.hoerire.flymefreeform.hook.launch

import android.util.Log
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration
import io.github.hoerire.flymefreeform.hook.OemClasses

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
 *
 * ColorOS 17 另有一处缓存：FlexibleTaskController 在构造时把上限读进整数字段
 * （字段名 maxFlexibleTaskNum，本机取值为 2），之后超限裁决直接用这个字段，不再回头问 getMaxWinNum。
 * 模块注入发生在服务构造之后，改返回值已经晚了，所以还要在裁决入口把字段改掉，
 * 见 installCachedLimitSync。
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
        val cached = installCachedLimitSync(classLoader)
        // 用 ModuleLog.install：不受「详细日志」开关影响，开机每个进程只留这一行，
        // 厂商改了接口名也能从这里看出来。
        ModuleLog.install("ZOOM_LIMIT_INSTALLED_" + total + "_" + cached)
        // 开机那行常常随缓冲区轮转丢掉，第一次开小窗时再补一份完整小结。
        pendingReport =
            "ZOOM_LIMIT_SUMMARY_sdk" + android.os.Build.VERSION.SDK_INT +
                "_hooks" + total + "_cache" + cached + "_" + installedSummary
    }

    /** 待补打的小结；首次命中任意小窗钩子时输出一次。 */
    private var pendingReport: String? = null

    /** 各类的挂载情况，拼进小结。 */
    private var installedSummary: String = ""

    private fun flushPendingReport() {
        val report = pendingReport ?: return
        pendingReport = null
        ModuleLog.info(report)
    }

    private fun installOn(classLoader: ClassLoader, className: String): Int {
        val target =
            try {
                classLoader.loadClass(className)
            } catch (exception: ClassNotFoundException) {
                ModuleLog.warn("ZOOM_LIMIT_CLASS_MISSING_" + shortName(className), exception)
                return 0
            } catch (exception: LinkageError) {
                ModuleLog.warn("ZOOM_LIMIT_CLASS_LINKAGE_" + shortName(className), exception)
                return 0
            }
        var count = 0
        val matched = ArrayList<String>(6)
        target.declaredMethods.forEach { method ->
            val isSupport =
                method.name == "isSupportFlexibleWindow" &&
                    method.returnType == Boolean::class.javaPrimitiveType
            // 注意：getMaxWinNum 是"总可见窗口上限"，系统的超限判定里
            // shouldChangeToFloatSize = max(0, allVisibleWindowSize - maxNum) 即由它计算。
            // 绝不能把它改成用户设置值——一个全屏应用 + 一个合规小窗就会立刻判定超限。
            // 该路径不能单独点名挂载它：isMaxNumGetter 的排除会因此失效。
            val isMaxNumLike = isMaxNumGetter(method)
            if (!isSupport && !isMaxNumLike) return@forEach
            matched.add(method.name + "/" + method.parameterCount)
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
                        flushPendingReport()
                        val original = chain.proceed()
                        if (!settings.zoomInteractionActive || environment?.isForegroundExcluded() == true) {
                            original
                        } else if (isMaxNumLike) {
                            // 上限取用户设置值：让系统自己在超限时触发退场事务。
                            // 系统把全屏任务也算进可见数，会误判超限——那个误判在
                            // OutsideTapCloseHookInstaller 的 determineFlexibleTaskToExit 闸门里
                            // 按真实浮动窗口条数拦掉；真超限时再把被踢对象换成台账最旧的窗口。
                            // 模块不再自行关窗：自行关闭脱离系统 transition，做不到同帧。
                            val override = settings.zoomMaxWindowCountEffective
                            if (maxWinNumReported.compareAndSet(false, true)) {
                                ModuleLog.info("ZOOM_LIMIT_MAXNUM_FIRST_" + shortName(target.name) + "." +
                                        method.name + "/" + method.parameterCount +
                                        "_" + original + "_TO_" + override)
                                ModuleLog.info("ZOOM_LIMIT_MAXNUM_FIELDS_" + reportIntFields(chain.thisObject))
                            }
                            override
                        } else if (settings.zoomAllowAnyAppEffective) {
                            // 解除限制只对应用负责：系统分享面板 / 选择器保持系统原判，
                            // 否则系统会认为"这个界面支持小窗"，把分享开成小窗。
                            if (isSystemSheet(chain.args)) original else true
                        } else {
                            original
                        }
                    }
                count++
            }.onFailure {
                ModuleLog.warn("ZOOM_LIMIT_HOOK_FAILED_" + hookId, it)
            }
        }
        if (count > 0) {
            // 命中列表常开一行：厂商换接口名后，据此确认改动到底挂到了哪些方法。
            ModuleLog.info("ZOOM_LIMIT_METHODS_" + shortName(className) + "_" + matched.joinToString(","))
        }
        if (count == 0) {
            // 零命中必须常开一行：厂商改了接口名时，只有这里能看出来。
            ModuleLog.warn("ZOOM_LIMIT_NO_METHOD_" + shortName(className) + "_" + candidateNames(target))
        }
        io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) { "ZOOM_LIMIT_CLASS_DONE_" + shortName(className) + "_" + count }
        return count
    }

    /**
     * 把「上限」写进 FlexibleTaskController 的缓存字段。
     *
     * 挂在超限裁决入口（updateTaskMaxNum*）上：进方法前改字段，方法里读到的新值就是
     * 用户设置的个数。ColorOS 16 若没有这条路径则一个都不挂，行为不受影响。
     */
    private fun installCachedLimitSync(classLoader: ClassLoader): Int {
        val target =
            try {
                classLoader.loadClass(CONTROLLER_CLASS)
            } catch (_: ClassNotFoundException) {
                return 0
            } catch (_: LinkageError) {
                return 0
            }
        var count = 0
        target.declaredMethods.forEach { method ->
            if (!method.name.contains("updateTaskMaxNum")) return@forEach
            method.isAccessible = true
            val hookId = "flymefreeform.limit.cache." + method.name + "." + method.parameterCount
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId(hookId)
                    .intercept { chain ->
                        val settings = configuration.snapshot
                        if (settings.zoomInteractionActive && environment?.isForegroundExcluded() != true) {
                            if (cacheReported.compareAndSet(false, true)) {
                                reportCachedFields(
                                    chain.thisObject,
                                    settings.zoomMaxWindowCountEffective,
                                )
                            }
                            applyCachedLimit(chain.thisObject, settings.zoomMaxWindowCountEffective)
                        }
                        chain.proceed()
                    }
                count++
            }.onFailure {
                ModuleLog.warn("ZOOM_LIMIT_CACHE_HOOK_FAILED_" + hookId, it)
            }
        }
        if (count == 0 && android.os.Build.VERSION.SDK_INT >= 37) {
            // 只有 17 才需要这条路；16 上找不到是正常的，不刷日志。
            ModuleLog.warn("ZOOM_LIMIT_CACHE_NO_ENTRY_" + shortName(CONTROLLER_CLASS))
        }
        return count
    }

    /**
     * ColorOS 17 的全屏占位补偿。
     *
     * 17 的计数把当前全屏应用也算进可见窗口（系统日志里 allVisibleWindowSize = flexibleTasks +
     * fullScreenList），于是用户把上限设成 1 时，底下压着一个全屏应用再挂一个小窗就会被判超限、
     * 立刻收掉。这里在 17 上统一 +1 抵消那一个占位，16 上计数不含全屏，维持原值不动。
     */
    /**
     * 上限类 getter：无参、返回 int、名字里带 max 且带 num/count。
     *
     * 排除宽高尺寸类（MAX_FLEXIBLE_TASK_WIDTH 那种），避免误改绘制参数。
     */
    private fun isMaxNumGetter(method: java.lang.reflect.Method): Boolean {
        if (method.returnType != Int::class.javaPrimitiveType) return false
        // 0 参是 Utils 上的静态入口，1 参是服务上按场景取值的实例入口
        // （ColorOS 16 的 FlexibleWindowManagerService#getMaxWinNum(int scenario)）。
        // 16 的调用点用的是静态入口，但那个方法很小、很可能被 ART 内联，钩子拦不到；
        // 它内部必然要调实例这个，两个都挂上才稳。2.2 版就是按名字挂、不看参数个数。
        if (method.parameterCount > 1) return false
        val name = method.name.lowercase()
        if (!name.startsWith("get")) return false
        // getMaxWinNum 是"总可见窗口上限"（全屏任务也算），系统的超限判定里
        // shouldChangeToFloatSize = max(0, allVisibleWindowSize - maxNum) 即由它计算。
        // 把它改成设置值会让「一个全屏应用 + 一个合规小窗」立刻判定超限，
        // 于是扇形菜单一出现就收掉小窗——之前那个"无条件加一"用于抵消该偏差。
        // 这里只改浮动上限，总可见上限保持系统默认。
        // 16 的数量判据就是 getMaxWinNum（2.2 版正是接管它才生效）；
        // 17 改成「总可见窗口上限」，把全屏任务也算进去，改它会让
        // 「一个全屏应用 + 一个合规小窗」立刻判超限，所以 17 上必须放行。
        if (name == "getmaxwinnum") return android.os.Build.VERSION.SDK_INT < 37
        if (!name.contains("max")) return false
        if (!name.contains("num") && !name.contains("count")) return false
        if (name.contains("width") || name.contains("height") || name.contains("size")) return false
        return true
    }

    /** 一次性列出实例上的整数字段，用来找 maxFlexibleTaskNum 这类缓存值。 */
    private fun reportIntFields(target: Any?): String {
        if (target == null) return "NO_TARGET"
        val report = StringBuilder()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (field.type != Int::class.javaPrimitiveType) continue
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val accessible = runCatching { field.isAccessible = true }.isSuccess
                if (!accessible) continue
                val value = runCatching { field.getInt(target) }.getOrNull() ?: continue
                if (report.isNotEmpty()) report.append(',')
                report.append(field.name).append('=').append(value)
                if (report.length > 380) return report.toString()
            }
            type = type.superclass
        }
        return target.javaClass.simpleName + "_" + report
    }

    /** 每个进程只报一次，避免每次裁决都刷日志。 */
    private val maxWinNumReported = java.util.concurrent.atomic.AtomicBoolean(false)
    private val cacheReported = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 一次性把控制器里所有 max/num 相关的整数字段打出来。
     *
     * 17 上窗口上限被缓存在某个字段里，只有看到真实字段名和值，才能确认该改哪一个。
     */
    private fun reportCachedFields(target: Any?, wanted: Int) {
        if (target == null) {
            ModuleLog.info("ZOOM_LIMIT_CACHE_FIELDS_NO_TARGET")
            return
        }
        val report = StringBuilder()
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (field.type != Int::class.javaPrimitiveType) continue
                val name = field.name
                val lowered = name.lowercase()
                if (!lowered.contains("max") && !lowered.contains("num")) continue
                val accessible = runCatching { field.isAccessible = true }.isSuccess
                if (!accessible) continue
                val value = runCatching { field.getInt(target) }.getOrNull() ?: continue
                if (report.isNotEmpty()) report.append(',')
                report.append(name).append('=').append(value)
                if (report.length > 400) break
            }
            type = type.superclass
        }
        ModuleLog.info("ZOOM_LIMIT_CACHE_FIELDS_" + target.javaClass.simpleName + "_WANT_" + wanted + "_" + report)
    }

    /** 按类缓存候选字段，避免每次裁决都重新扫描反射。 */
    private val limitFields = java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Field>>()

    private fun applyCachedLimit(target: Any?, value: Int) {
        if (target == null) return
        val fields =
            limitFields.getOrPut(target.javaClass) { collectLimitFields(target.javaClass) }
        for (field in fields) {
            val current = runCatching { field.getInt(target) }.getOrNull() ?: continue
            if (current == value) continue
            if (runCatching { field.setInt(target, value) }.isSuccess) {
                io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG) {
                    "ZOOM_LIMIT_CACHE_PATCHED_" + field.name + "_" + current + "_TO_" + value
                }
            }
        }
    }

    /** 找出控制器里缓存上限的整数字段（ColorOS 17 上名为 maxFlexibleTaskNum）。 */
    private fun collectLimitFields(start: Class<*>): List<java.lang.reflect.Field> {
        val result = ArrayList<java.lang.reflect.Field>(2)
        var type: Class<*>? = start
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (field.isSynthetic) continue
                if (field.type != Int::class.javaPrimitiveType) continue
                val name = field.name.lowercase()
                if (!name.contains("max") || !name.contains("num") || !name.contains("flexible")) continue
                val accessible = runCatching { field.isAccessible = true }.isSuccess
                if (!accessible) continue
                result.add(field)
            }
            type = type.superclass
        }
        return result
    }

    /** 零命中时列出可疑方法名，用于确认厂商是否改了接口名。 */
    private fun candidateNames(target: Class<*>): String =
        runCatching {
            target.declaredMethods
                .filter {
                    it.returnType == Int::class.javaPrimitiveType ||
                        it.returnType == Boolean::class.javaPrimitiveType
                }
                .map { it.name + "/" + it.parameterCount }
                .filter { name ->
                    name.contains("num", true) ||
                        name.contains("max", true) ||
                        name.contains("support", true)
                }
                .distinct()
                .take(12)
                .joinToString(",")
        }.getOrDefault("")

    /** 系统分享面板 / 选择器所在的包。 */
    private fun isSystemSheet(args: List<Any?>): Boolean {
        val intent = args.filterIsInstance<android.content.Intent>().firstOrNull()
            ?: args.firstNotNullOfOrNull { candidate ->
                candidate?.let { task ->
                    runCatching {
                        task.javaClass.getMethod("getBaseIntent").invoke(task) as? android.content.Intent
                    }.getOrNull()
                }
            }
            ?: return false
        val target = intent.component?.packageName
            ?: runCatching {
                intent.javaClass.getMethod("getPackage").invoke(intent) as? String
            }.getOrNull()
            ?: return false
        return target in SYSTEM_SHEET_PACKAGES
    }

    private fun shortName(className: String): String = className.substringAfterLast('.')

    private companion object {
        const val TAG = "FlymeFreeform"
        const val MANAGER_CLASS = OemClasses.FLEXIBLE_WINDOW_SERVICE
        const val CONTROLLER_CLASS = OemClasses.FLEXIBLE_TASK_CONTROLLER
        const val UTILS_CLASS = "com.android.server.wm.FlexibleWindowUtils"


        /** 系统分享面板与选择器所在的包；解除限制不为它们背书。 */
        val SYSTEM_SHEET_PACKAGES = setOf(
            "com.android.intentresolver",
            "com.google.android.intentresolver",
        )
    }
}