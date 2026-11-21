package io.github.hoerire.free.hook

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import io.github.hoerire.free.bubble.CloneDeepLinkStore
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 分身通知深链的**捕获端**（system_server）。
 *
 * 要解决的问题：分身通知的 `contentIntent` 由分身实例签发，SystemUI 拿到的只是一个
 * 不透明的 `PendingIntent`；直接 `send()` 会按 `PendingIntentRecord.key.userId` 落到主用户，
 * 拿不到会话参数（详见 `CLONE_HANDOFF.md`）。唯一可靠的路径是读出记录里的原始 Intent，
 * 在目标用户上下文里重建 PendingIntent 再发送——那需要把 Intent 送到 SystemUI。
 *
 * 捕获点选在**通知入库**（`NotificationRecord` 构造），而不是发送时：
 * 发送时捕获意味着必须先把原始 `contentIntent` 发出去（落到主应用），再靠「压掉那次启动」
 * 纠正，代价是一次错误的启动。入库时捕获则完全不改变任何启动行为，点气泡时直接发正确的那个。
 *
 * 死锁约束（3.2.2 教训，见 `BUBBLE_HANDOFF.md` §2.1）：本钩子处在 NotificationManagerService
 * 的持锁路径上，函数内**只做对象字段读取**，不调用任何跨进程方法；`Parcel.marshall` 与
 * `Settings.Global` 写入全部丢到主线程下一轮执行，那时锁已释放。
 */
internal class NotificationCloneCaptureHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var installedHooks = 0

    fun install(classLoader: ClassLoader) {
        val record =
            runCatching { Class.forName(RECORD_CLASS, false, classLoader) }.getOrNull()
                ?: run {
                    ModuleLog.warn("CLONE_DEEPLINK_CLASS_MISSING", null)
                    return
                }
        // 构造函数签名随版本变过（渠道参数增删过几次），只按「有没有 SBN 参数」筛，
        // 不写死参数表；拿到 SBN 就等于拿到了通知 key 与 contentIntent。
        val targets =
            record.declaredConstructors.filter { constructor ->
                constructor.parameterTypes.any { StatusBarNotification::class.java.isAssignableFrom(it) }
            }
        if (targets.isEmpty()) {
            ModuleLog.warn("CLONE_DEEPLINK_CTOR_MISSING", null)
            return
        }
        targets.forEachIndexed { index, constructor ->
            constructor.isAccessible = true
            module
                .hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.clone.deeplink." + index)
                .intercept { chain ->
                    runCatching { capture(chain.args) }
                    chain.proceed()
                }
            installedHooks++
        }
        ModuleLog.info("CLONE_DEEPLINK_HOOKS_INSTALLED")
    }

    /** 持锁钩子内：只读字段、只取引用，不做任何序列化或设置写入。 */
    private fun capture(args: List<Any?>?) {
        val sbn = args?.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
            ?: return
        // 主用户的通知不需要这条通道：直接发送 contentIntent 本来就在正确的用户里。
        val userId = sbn.userId
        if (userId <= 0) return
        val packageName = sbn.packageName ?: return
        if (!configuration.snapshot.acceptsBubble(packageName, userId)) {
            // 只有「气泡应用范围」里的分身才需要这条通道：其余分身通知按原样放行，
            // 不写设置也就不占用设置项。详细日志下留一行，便于确认是闸门还是钩子没上。
            ModuleLog.debug(TAG) { "CLONE_DEEPLINK_SKIP_GATE_" + packageName + "_u" + userId }
            return
        }
        val notification = sbn.notification ?: return
        val contentIntent = notification.contentIntent ?: run {
            ModuleLog.debug(TAG) { "CLONE_DEEPLINK_SKIP_NO_CONTENT_" + packageName + "_u" + userId }
            return
        }
        // 系统进程里这个 PendingIntent 的 mTarget 就是本进程的 PendingIntentRecord（本地 binder
        // 反序列化回来还是同一个对象），因此可以纯反射读字段；若是跨进程代理则字段读不到，放弃。
        val record = OemReflection.readField(contentIntent, "mTarget")
        if (record == null) {
            ModuleLog.info("CLONE_DEEPLINK_NO_RECORD")
            return
        }
        val key = OemReflection.readField(record, "key")
        if (key == null) {
            ModuleLog.info("CLONE_DEEPLINK_NO_KEY")
            return
        }
        val requestIntent = OemReflection.readField(key, "requestIntent") as? Intent
        if (requestIntent == null) {
            ModuleLog.info("CLONE_DEEPLINK_NO_INTENT")
            return
        }
        val ownerUserId = OemReflection.readField(key, "userId") as? Int ?: userId
        if (ownerUserId <= 0) return
        val notificationKey = sbn.key ?: return
        // 拷贝一份再交出去：原对象仍归 NotificationManagerService 所有。
        val snapshot = Intent(requestIntent)
        handler.post { publish(notificationKey, packageName, ownerUserId, snapshot) }
    }

    /** 主线程下一轮：此时 NMS 的锁已释放，可以序列化并写设置。 */
    private fun publish(
        notificationKey: String,
        packageName: String,
        userId: Int,
        intent: Intent,
    ) {
        val resolver = systemContext()?.contentResolver ?: return
        val now = System.currentTimeMillis()
        val line = CloneDeepLinkStore.encodeLine(notificationKey, intent, userId, now) ?: run {
            ModuleLog.debug(TAG) { "CLONE_DEEPLINK_PACK_SKIPPED_" + packageName + "_u" + userId }
            return
        }
        val raw = runCatching {
            Settings.Global.getString(resolver, ModulePreferences.KEY_CLONE_DEEP_LINK)
        }.getOrNull()
        if (CloneDeepLinkStore.containsIdentical(raw, line, now)) return
        runCatching {
            val merged = CloneDeepLinkStore.merge(raw, line, now)
            Settings.Global.putString(
                resolver,
                ModulePreferences.KEY_CLONE_DEEP_LINK,
                merged,
            )
            ModuleLog.info("CLONE_DEEPLINK_CAPTURED_" + packageName + "_u" + userId)
            ModuleLog.debug(TAG) {
                "CLONE_DEEPLINK_CAPTURED_DETAIL_" + packageName +
                    "_u" + userId +
                    "_c" + (intent.component?.className ?: "-") +
                    "_ac" + intent.action.orEmpty() +
                    "_l" + line.length +
                    "_n" + (merged.count { it == '\n' } + 1)
            }
        }.onFailure { ModuleLog.warn("CLONE_DEEPLINK_WRITE_FAILED", it) }
    }

    /** system_server 里没有现成 Context，ActivityThread 的系统上下文是唯一稳定来源。 */
    private fun systemContext(): Context? =
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null) ?: return null
            threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
        }.getOrNull()

    private companion object {
        const val TAG = "Free"
        const val RECORD_CLASS = "com.android.server.notification.NotificationRecord"
    }
}
