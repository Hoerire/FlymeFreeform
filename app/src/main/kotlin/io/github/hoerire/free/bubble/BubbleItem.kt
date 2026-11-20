package io.github.hoerire.free.bubble

import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.os.SystemClock

/** 一条通知在气泡里需要的全部内容；由 [BubblePayloadFactory] 一次算好。 */
internal data class BubblePayload(
    val icon: Drawable?,
    val appIcon: Drawable?,
    val appName: String,
    val title: String,
    val body: String,
) {
    val hasContent: Boolean get() = title.isNotEmpty() || body.isNotEmpty()
}

/** 队列条目：身份 + 内容 + 打开入口。 */
internal data class BubbleItem(
    val identity: BubbleIdentity,
    val payload: BubblePayload,
    val contentIntent: PendingIntent?,
    /** 即时建议：点气泡时直接执行，不走通知/启动。 */
    val directAction: (() -> Unit)? = null,
    /** 即时建议自带的停留秒数；非空时覆盖全局气泡停留设置。 */
    val timeoutOverride: Int? = null,
    /** 建议 id（如 flashlight / clipboard:phone）；普通通知为 null。 */
    val suggestionId: String? = null,
    val postedAt: Long = SystemClock.elapsedRealtime(),
) {
    val notificationKey: String? get() = identity.notificationKey
    val packageName: String get() = identity.packageName
    val userId: Int get() = identity.userId
}

/**
 * 通知被系统移除时，气泡要不要一起收掉。
 *
 * 只认「用户主动清除」这一类原因；应用自己 cancel 掉一条刚发出的通知（进度、临时提示）
 * 不代表用户看过了，气泡留着。这段策略是纯函数，可单测。
 */
internal object BubbleRemovalPolicy {
    fun shouldDrop(reason: Int): Boolean =
        when (reason) {
            // 用户主动清除类原因码：REASON_CLICK / CANCEL / CANCEL_ALL / USER_STOPPED / PACKAGE_BANNED /
            // 其余清除类原因码：LISTENER_CANCEL(_ALL) / GROUP_SUMMARY_CANCELED / CLEAR_DATA / CHANNEL_BANNED /
            // SNOOZED / TIMEOUT / OEM 变体
            1, 2, 3, 6, 7, 10, 11, 12, 15, 16, 18, 19, 20, 21 -> true
            // 负数来自没有 reason 的回调（原生流体云那条），按用户清除处理。
            else -> reason < 0
        }
}
