package io.github.hoerire.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 厂商小窗机制的关键节点日志。只记日志，不改任何行为。
 *
 * 起因：ColorOS 17 上出现"分享面板变贴边""窗口自己缩小"这类现象时，模块日志只看得到自己做了
 * 什么，看不到厂商在什么时候创建、裁决、销毁了哪个窗口，定位只能靠猜。这里把四个必经点挂上：
 * 窗口建立、窗口移除、"谁该退出"的裁决、悬浮把手的增删。全部走 VerboseLog 门禁。
 */
internal class FlexibleWindowDiagnostics(private val module: XposedModule) {

    fun install(classLoader: ClassLoader) {
        val controller = runCatching { classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER) }.getOrNull()
        val floatHandle = runCatching { classLoader.loadClass(FLOAT_HANDLE_CONTROLLER) }.getOrNull()
        val installed = mutableListOf<String>()
        controller?.let { type ->
            hook(type, "addFlexibleTask", 1, installed) { args ->
                "DIAG_FLEX_ADD_" + taskOf(args.getOrNull(0))
            }
            hook(type, "removeFlexibleTask", 1, installed) { args ->
                "DIAG_FLEX_REMOVE_" + taskOf(args.getOrNull(0))
            }
            hook(type, "determineFlexibleTaskToExit", 4, installed) { args ->
                "DIAG_FLEX_VERDICT_" + taskOf(args.getOrNull(0)) +
                    "_source" + args.getOrNull(1) + "_count" + (args.getOrNull(2) as? Collection<*>)?.size
            }
        }
        floatHandle?.let { type ->
            hook(type, "addFloatHandle", 2, installed) { args ->
                "DIAG_FLOAT_ADD_type" + args.getOrNull(1) + "_task" + taskIdOf(args.getOrNull(0))
            }
            hook(type, "removeFloatHandle", 4, installed) { args ->
                "DIAG_FLOAT_REMOVE_task" + taskIdOf(args.getOrNull(1)) + "_from" + args.getOrNull(3)
            }
        }
        module.log(Log.INFO, TAG, "FLEX_DIAGNOSTICS_INSTALLED_" + installed.size, null)
    }

    private fun hook(
        type: Class<*>,
        name: String,
        parameterCount: Int,
        installed: MutableList<String>,
        line: (List<Any?>) -> String,
    ) {
        val method = type.declaredMethods.firstOrNull {
            it.name == name && it.parameterCount == parameterCount
        } ?: run {
            module.log(Log.WARN, TAG, "FLEX_DIAGNOSTICS_MISSING_" + name, null)
            return
        }
        method.isAccessible = true
        runCatching {
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.diag." + name)
                .intercept { chain ->
                    if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
                        // 先记日志再执行：即使原方法抛异常，也能看到它被调用过。
                        ModuleLog.debug("FFDIAG") { runCatching { line(chain.args) }.getOrDefault("?") }
                    }
                    chain.proceed()
                }
        }.onFailure { module.log(Log.WARN, TAG, "FLEX_DIAGNOSTICS_FAILED_" + name, it) }
        installed += name
    }

    /** 任务的包名与 id，形如 com.tencent.mm#123。 */
    private fun taskOf(task: Any?): String {
        if (task == null) return "null"
        val pkg = runCatching {
            val intent = task.javaClass.getMethod("getBaseIntent").invoke(task) as? android.content.Intent
            intent?.component?.packageName
        }.getOrNull() ?: runCatching {
            (task.javaClass.getMethod("getRealActivity").invoke(task)
                as? android.content.ComponentName)?.packageName
        }.getOrNull()
        return (pkg ?: "?") + "#" + taskIdOf(task)
    }

    private fun taskIdOf(anything: Any?): String {
        if (anything == null) return "?"
        val id = runCatching {
            anything.javaClass.getField("mTaskId").getInt(anything)
        }.getOrNull() ?: runCatching {
            anything.javaClass.getMethod("getTaskId").invoke(anything) as? Int
        }.getOrNull()
        return id?.toString() ?: "?"
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val FLEXIBLE_TASK_CONTROLLER = "com.android.server.wm.FlexibleTaskController"
        const val FLOAT_HANDLE_CONTROLLER = "com.android.server.wm.FloatHandleController"
    }
}
