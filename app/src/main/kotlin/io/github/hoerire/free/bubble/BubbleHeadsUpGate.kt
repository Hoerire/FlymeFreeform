package io.github.hoerire.free.bubble

import android.app.KeyguardManager
import android.app.Notification
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 横幅裁决：命中气泡白名单时，重建一个 `shouldInterrupt=false` 的决策对象。
 *
 * 只做这一件事，不认识气泡、不认识窗口。判定顺序与原实现一致：
 * 屏幕/锁屏 → 前台应用 → 白名单 → 有内容 → 非分组摘要/常驻。
 */
internal class BubbleHeadsUpGate(
    private val module: XposedModule,
    private val context: android.content.Context,
    private val settings: () -> BubbleSettings,
    private val logger: (code: String, throwable: Throwable?) -> Unit,
) {
    private val installed = AtomicBoolean(false)
    private val environment = BubbleEnvironmentGate(context)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val modern = installModern(classLoader)
        val legacy = if (modern == 0) installLegacy(classLoader) else 0
        logger(
            if (modern + legacy > 0) "BUBBLE_HEADSUP_HOOKS_" + (modern + legacy)
            else "BUBBLE_HEADSUP_HOOKS_NONE",
            null,
        )
    }

    private fun installModern(classLoader: ClassLoader): Int =
        runCatching {
            val provider =
                Class.forName(PROVIDER_CLASS, false, classLoader)
            var count = 0
            for (method in provider.declaredMethods) {
                if (method.name != "makeAndLogHeadsUpDecision" &&
                    method.name != "makeUnloggedHeadsUpDecision"
                ) {
                    continue
                }
                method.isAccessible = true
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.bubble.headsup." + method.name)
                    .intercept { chain ->
                        var result = chain.proceed()
                        val entry = findEntry(chain.args)
                        if (entry != null && shouldSuppress(entry)) {
                            logger("BUBBLE_HEADSUP_SUPPRESSED", null)
                            result = suppress(chain.thisObject, result)
                        }
                        result
                    }
                count++
            }
            count
        }.getOrElse {
            logger("BUBBLE_HEADSUP_MODERN_FAILED", it)
            0
        }

    /** 旧版 SystemUI 只有 boolean 返回值的 interrupt provider。 */
    private fun installLegacy(classLoader: ClassLoader): Int {
        var count = 0
        for (name in LEGACY_PROVIDERS) {
            val provider =
                runCatching { Class.forName(name, false, classLoader) }.getOrNull() ?: continue
            for (method in provider.declaredMethods) {
                val lower = method.name.lowercase()
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (!lower.contains("headsup") && !lower.contains("interrupt")) continue
                val acceptsEntry =
                    method.parameterTypes.any { type ->
                        !type.isPrimitive && type != String::class.java && type != Bundle::class.java
                    }
                if (!acceptsEntry) continue
                method.isAccessible = true
                val isHeadsUp = lower.contains("headsup")
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.bubble.headsup.legacy." + name + "." + method.name)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val entry = findEntry(chain.args)
                        if (isHeadsUp && entry != null && shouldSuppress(entry)) {
                            false
                        } else {
                            result
                        }
                    }
                count++
            }
            if (count > 0) break
        }
        return count
    }

    fun shouldSuppress(entry: Any?): Boolean {
        return runCatching {
            val config = settings()
            if (!config.active || !config.previewSuppressBanner) return false
            val sbn = sbnOf(entry) ?: return false
            val notification = sbn.notification ?: return false
            if (isGroupSummaryOrOngoing(notification)) return false
            val identity = BubbleIdentity(sbn.packageName, sbn.userId, sbn.key)
            if (!config.accepts(identity)) return false
            // 锁屏时气泡在锁屏后面，保留系统横幅作为唯一可见入口。
            if (isDeviceLocked()) return false
            // 免打扰场景下不出气泡，横幅必须继续走系统：否则这条通知会彻底没有提示。
            environment.suppressionReason(sbn.packageName, sbn.userId, config)?.let { return false }
            true
        }.getOrElse {
            logger("BUBBLE_HEADSUP_DECISION_FAILED", it)
            false
        }
    }

    private fun isDeviceLocked(): Boolean =
        runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrElse { false }

    private fun isGroupSummaryOrOngoing(notification: Notification): Boolean =
        (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0 ||
            (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

    /** 重建一个不打断的决策对象；构造器签名随 SystemUI 版本变化，逐个试。 */
    private fun suppress(provider: Any?, decision: Any?): Any? {
        if (decision == null) return null
        runCatching {
            for (constructor in decision.javaClass.declaredConstructors) {
                constructor.isAccessible = true
                val types = constructor.parameterTypes
                when {
                    types.size == 3 &&
                        types[0].isInstance(provider) &&
                        types[1] == Boolean::class.javaPrimitiveType &&
                        types[2] == String::class.java ->
                        return constructor.newInstance(provider, false, SUPPRESS_REASON)
                    types.size == 2 &&
                        types[0] == Boolean::class.javaPrimitiveType &&
                        types[1] == String::class.java ->
                        return constructor.newInstance(false, SUPPRESS_REASON)
                }
            }
        }.onFailure { logger("BUBBLE_HEADSUP_REBUILD_FAILED", it) }
        // 有些版本把标志放在基类：直接改字段，返回同一个决策对象。
        if (setBooleanField(decision, "shouldInterrupt", false)) return decision
        logger("BUBBLE_HEADSUP_UNSUPPORTED_DECISION", null)
        return decision
    }

    private fun setBooleanField(target: Any, name: String, value: Boolean): Boolean {
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            runCatching {
                val field: Field = type.getDeclaredField(name)
                field.isAccessible = true
                field.setBoolean(target, value)
                return true
            }
            type = type.superclass
        }
        return false
    }

    private fun findEntry(args: List<Any?>?): Any? {
        if (args == null) return null
        for (arg in args) {
            if (arg == null) continue
            if (sbnOf(arg) != null) return arg
        }
        return null
    }

    /** NotificationEntry 的 getSbn 是包内可见方法，只能沿声明层级反射。 */
    private fun sbnOf(entry: Any?): StatusBarNotification? {
        if (entry == null) return null
        (entry as? StatusBarNotification)?.let { return it }
        var type: Class<*>? = entry.javaClass
        while (type != null && type != Any::class.java) {
            for (method in type.declaredMethods) {
                if (method.parameterCount != 0) continue
                if (method.name != "getSbn" && method.name != "getStatusBarNotification") continue
                if (!StatusBarNotification::class.java.isAssignableFrom(method.returnType)) continue
                runCatching {
                    method.isAccessible = true
                    (method.invoke(entry) as? StatusBarNotification)?.let { return it }
                }
            }
            type = type.superclass
        }
        type = entry.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (!StatusBarNotification::class.java.isAssignableFrom(field.type)) continue
                runCatching {
                    field.isAccessible = true
                    (field.get(entry) as? StatusBarNotification)?.let { return it }
                }
            }
            type = type.superclass
        }
        return null
    }

    private companion object {
        const val PROVIDER_CLASS =
            "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl"
        const val SUPPRESS_REASON = "suppressed by Free bubble notification"
        val LEGACY_PROVIDERS =
            listOf(
                "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
                "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider",
                "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl",
                "com.oplus.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
                "com.oplus.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl",
            )
    }
}
