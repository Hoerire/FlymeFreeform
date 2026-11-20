package io.github.hoerire.free.bubble

import android.os.Handler
import android.service.notification.StatusBarNotification
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 通知管线的安装器：只负责挂监听、把事件投到主线程。
 *
 * 与队列/窗口分开后，这里可以整段跳过（例如白名单为空时）而不影响其它部分；
 * 出问题也能在一屏内读完。
 */
internal class BubblePipeline(
    private val module: XposedModule,
    private val handler: Handler,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    interface Sink {
        fun onPosted(sbn: StatusBarNotification)

        fun onRemoved(sbn: StatusBarNotification, reason: Int)
    }

    fun install(classLoader: ClassLoader, sink: Sink): Int {
        var count = 0
        for (className in LISTENER_CLASSES) {
            val listenerClass =
                runCatching { Class.forName(className, false, classLoader) }.getOrNull() ?: continue
            count +=
                installPosted(listenerClass, sink) +
                    installConnected(listenerClass, sink) +
                    installRemoved(listenerClass, sink)
            if (count > 0) break
        }
        logger("BUBBLE_PIPELINE_HOOKS_" + count, null)
        return count
    }

    private fun installPosted(listenerClass: Class<*>, sink: Sink): Int {
        var count = 0
        for (method in listenerClass.declaredMethods) {
            if (method.name != "onNotificationPosted") continue
            val parameters = method.parameterTypes
            if (parameters.isEmpty()) continue
            if (!StatusBarNotification::class.java.isAssignableFrom(parameters[0])) continue
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.bubble.posted." + method.parameterCount)
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.args?.firstOrNull() as? StatusBarNotification)?.let { sbn ->
                        handler.post { sink.onPosted(sbn) }
                    }
                    result
                }
            count++
        }
        return count
    }

    private fun installRemoved(listenerClass: Class<*>, sink: Sink): Int {
        // ColorOS 会同时声明多个 onNotificationRemoved 重载；取参数最多的那个（reason 在最后）。
        val method =
            listenerClass.declaredMethods
                .filter {
                    it.name == "onNotificationRemoved" &&
                        it.parameterTypes.isNotEmpty() &&
                        StatusBarNotification::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                .maxByOrNull { it.parameterCount }
                ?: return 0
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.bubble.removed")
            .intercept { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? StatusBarNotification)?.let { sbn ->
                    val reason = chain.args?.filterIsInstance<Int>()?.lastOrNull() ?: -1
                    handler.post { sink.onRemoved(sbn, reason) }
                }
                result
            }
        return 1
    }

    private fun installConnected(listenerClass: Class<*>, sink: Sink): Int {
        val method = findMethod(listenerClass, "onListenerConnected", 0) ?: return 0
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.bubble.connected")
            .intercept { chain ->
                val result = chain.proceed()
                val listener = chain.thisObject
                handler.post { rescan(listener, sink) }
                result
            }
        return 1
    }

    /** 进程晚于通知到达时补扫一次，避免错过开机后已经挂着的通知。 */
    private fun rescan(listener: Any?, sink: Sink) {
        if (listener == null) return
        runCatching {
            val method = findMethod(listener.javaClass, "getActiveNotifications", 0) ?: return
            method.isAccessible = true
            val active = method.invoke(listener) as? Array<*> ?: return
            active.forEach { item ->
                (item as? StatusBarNotification)?.let(sink::onPosted)
            }
            logger("BUBBLE_RESCAN_" + active.size, null)
        }.onFailure { logger("BUBBLE_RESCAN_FAILED", it) }
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == parameterCount
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private companion object {
        val LISTENER_CLASSES =
            listOf(
                "com.android.systemui.statusbar.NotificationListener",
                "com.android.systemui.statusbar.phone.NotificationListenerWithPlugins",
                "com.oplus.systemui.statusbar.NotificationListener",
                "com.oplus.systemui.statusbar.phone.NotificationListenerWithPlugins",
                "com.oplus.systemui.statusbar.notification.NotificationListener",
            )
    }
}
