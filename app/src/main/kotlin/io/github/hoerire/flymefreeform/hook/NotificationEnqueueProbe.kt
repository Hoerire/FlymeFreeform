package io.github.hoerire.flymefreeform.hook

import android.app.Notification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 通知入队探针：只读，记录通知携带的 action 与全屏意图。
 *
 * 微信 / QQ 的消息通知带「浮窗回复」按钮，点击横幅时若直接发主 PendingIntent，
 * 横幅需要等系统自己收；改发 action 的 PendingIntent 则走系统浮窗回复通道，
 * 横幅必定被带走。这里先确认通知实际带了哪些 action。
 */
internal class NotificationEnqueueProbe(private val module: XposedModule) {
    fun install(classLoader: ClassLoader) {
        val target =
            runCatching { classLoader.loadClass(NMS_CLASS) }.getOrNull()
                ?: run {
                    module.log(Log.WARN, TAG, "NOTIF_ENQUEUE_PROBE_CLASS_MISSING", null)
                    return
                }
        val method =
            target.declaredMethods.firstOrNull { candidate ->
                candidate.name == "enqueueNotificationInternal" && candidate.parameterCount == 10
            } ?: run {
                module.log(Log.WARN, TAG, "NOTIF_ENQUEUE_PROBE_METHOD_MISSING", null)
                return
            }
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.notif.enqueue_probe")
            .intercept { chain ->
                runCatching { describe(chain.getArg(0) as? String, chain.getArg(6) as? Notification) }
                    .onFailure { module.log(Log.WARN, TAG, "NOTIF_ENQUEUE_PROBE_FAILED", it) }
                chain.proceed()
            }
        module.log(Log.INFO, TAG, "NOTIF_ENQUEUE_PROBE_INSTALLED")
    }

    /** 探针只读取通知携带的字��，关闭「详细日志」时不产生任何提取开销。 */
    private fun describe(packageName: String?, notification: Notification?) {
        if (notification == null) return
        ModuleLog.debug(TAG) {
            val actions = notification.actions ?: emptyArray()
            val titles = actions.map { action -> action.title?.toString().orEmpty() }
            val fullScreen = if (notification.fullScreenIntent != null) 1 else 0
            "NOTIF_ENQUEUE_" + packageName +
                "_n" + actions.size +
                "_fs" + fullScreen +
                "_" + titles.joinToString("|")
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val NMS_CLASS = "com.android.server.notification.NotificationManagerService"
    }
}
