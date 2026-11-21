package io.github.hoerire.free.bubble

import android.content.Intent
import android.os.Parcel
import android.util.Base64

/**
 * 分身通知「深链 Intent」的中转格式：system_server 写入，SystemUI 读取。
 *
 * 背景：分身通知的 `contentIntent` 由分身实例（user 998）签发，但 SystemUI 手里只有一个
 * 不透明的 `PendingIntent`；直接 `send()` 会按 `PendingIntentRecord.key.userId` 落到主用户
 * 应用上，拿不到聊天参数。唯一正确的做法是拿到记录里的原始 Intent，在目标用户上下文里
 * 重建一个 `PendingIntent` 再发送（见 `CLONE_HANDOFF.md` §4）。
 *
 * 通道沿用 `ModulePreferences.KEY_PRE_SLEEP_ARMED` 的先例：`Settings.Global` 单键多行。
 * 只做**序列化 + 混淆**，不做加密——任何应用都能读到 Global 设置，这里的定位是
 * 「短命缓存」：条目 10 分钟过期、写入侧顺手清理、最多 6 条，且只收录白名单内的分身通知。
 *
 * 序列化用 `Parcel.marshall`：Intent 里的 extras 在 SystemUI 侧保持惰性（不被反序列化成对象），
 * 因此应用自定义的 Parcelable 也不会在我们进程里炸掉。
 */
internal object CloneDeepLinkStore {
    /** 条目存活时间；过期条目不再被采用，写入侧顺手清掉。 */
    const val TTL_MS = 10 * 60 * 1000L

    /** 同时保留的条目上限：够覆盖几条并发的会话通知，又不至于把设置撑大。 */
    private const val MAX_ENTRIES = 6

    /** 单条 Intent 的序列化上限；超过就放弃这条（宁可退回旧行为，也不写一个巨型设置）。 */
    private const val MAX_PAYLOAD_BYTES = 8 * 1024

    private const val FIELD = '\t'
    private const val LINE = '\n'

    /** 混淆掩码：只防「设置里裸眼能读」，不是加密，见类注释。 */
    private val MASK = "free.clone.deeplink.v1".toByteArray(Charsets.UTF_8)

    /** 还原出的一条深链：原始 Intent + 它所属的用户。 */
    data class Entry(val intent: Intent, val userId: Int)

    /**
     * 取回结果。
     *
     * 失败原因单独带出来：这条通道跨两个进程，出问题时日志里必须能分清是「没写入」
     * 「key 对不上」「用户对不上」还是「载荷坏了」，否则排查只能靠猜。
     */
    data class Lookup(val entry: Entry?, val reason: String) {
        val hit: Boolean get() = entry != null
    }

    /**
     * 编码一条记录。
     *
     * @return 单行记录；Intent 无法序列化或过大时返回 null（调用方放弃本次捕获）。
     */
    fun encodeLine(notificationKey: String, intent: Intent, userId: Int, now: Long): String? {
        if (notificationKey.isEmpty()) return null
        if (notificationKey.contains(FIELD) || notificationKey.contains(LINE)) return null
        val payload = pack(intent) ?: return null
        return listOf(
            (now + TTL_MS).toString(),
            userId.toString(),
            notificationKey,
            payload,
        ).joinToString(FIELD.toString())
    }

    /** 同一通知同一个 Intent 已经记录过：不必重复写设置（应用常持续更新同一条通知）。 */
    fun containsIdentical(raw: String?, line: String, now: Long): Boolean {
        val row = Row.parse(line) ?: return false
        return rows(raw, now).any {
            it.notificationKey == row.notificationKey && it.payload == row.payload
        }
    }

    /** 合并一条新记录：替换同 key 的旧记录、丢掉过期项、裁剪到上限。 */
    fun merge(raw: String?, line: String, now: Long): String {
        val row = Row.parse(line) ?: return raw.orEmpty()
        val kept = rows(raw, now).filterNot { it.notificationKey == row.notificationKey }
        return (kept + row).takeLast(MAX_ENTRIES).joinToString(LINE.toString()) { it.join() }
    }

    /**
     * 按通知 key 取回深链。
     *
     * 只认来源用户一致的那条：记录里的 userId 与气泡身份不符时宁可不用（退回旧行为），
     * 也不能把别处的会话开进这个实例。
     */
    fun lookup(raw: String?, notificationKey: String, userId: Int, now: Long): Lookup {
        if (raw.isNullOrEmpty()) return Lookup(null, "EMPTY")
        val row = rows(raw, now).lastOrNull { it.notificationKey == notificationKey }
            ?: return Lookup(null, "NOENTRY")
        if (userId <= 0 || row.userId != userId) return Lookup(null, "USER")
        val intent = unpack(row.payload) ?: return Lookup(null, "DECODE")
        return Lookup(Entry(intent, row.userId), "OK")
    }

    private fun rows(raw: String?, now: Long): List<Row> =
        raw
            ?.split(LINE)
            ?.mapNotNull { Row.parse(it) }
            ?.filter { it.expiresAt > now }
            .orEmpty()

    private fun pack(intent: Intent): String? {
        val parcel = Parcel.obtain()
        return try {
            intent.writeToParcel(parcel, 0)
            val data = parcel.marshall()
            if (data.isEmpty() || data.size > MAX_PAYLOAD_BYTES) {
                null
            } else {
                Base64.encodeToString(mask(data), Base64.NO_WRAP)
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    private fun unpack(payload: String): Intent? {
        val parcel = Parcel.obtain()
        return try {
            val data = mask(Base64.decode(payload, Base64.NO_WRAP))
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            Intent.CREATOR.createFromParcel(parcel)
        } catch (_: RuntimeException) {
            null
        } finally {
            parcel.recycle()
        }
    }

    /** 对称掩码：同一份字节乘两次即还原。 */
    private fun mask(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (index in data.indices) {
            out[index] = (data[index].toInt() xor MASK[index % MASK.size].toInt()).toByte()
        }
        return out
    }

    /** 单行记录：过期时刻、所属用户、通知 key、序列化载荷。 */
    private class Row(
        val expiresAt: Long,
        val userId: Int,
        val notificationKey: String,
        val payload: String,
    ) {
        fun join(): String =
            listOf(expiresAt, userId, notificationKey, payload).joinToString(FIELD.toString())

        companion object {
            fun parse(line: String): Row? {
                if (line.isEmpty()) return null
                val parts = line.split(FIELD)
                if (parts.size != 4) return null
                val expiresAt = parts[0].toLongOrNull() ?: return null
                val userId = parts[1].toIntOrNull() ?: return null
                if (parts[2].isEmpty() || parts[3].isEmpty()) return null
                return Row(expiresAt, userId, parts[2], parts[3])
            }
        }
    }
}
