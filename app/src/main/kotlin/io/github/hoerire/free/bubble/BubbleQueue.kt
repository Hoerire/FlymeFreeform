package io.github.hoerire.free.bubble

/**
 * 气泡队列：纯数据结构，不碰 View、不碰窗口。
 *
 * 队首是屏幕上那一颗气泡；其余在后面排队。[add] 会先把同 key 的旧条目摘掉，
 * 因此「同一条通知更新」不会叠出第二颗气泡。上限 64 条。
 *
 * 同 key 判定只用 `notificationKey`：同一条通知的更新与移除都靠它定位，
 * 白名单/分身判定则在 [BubbleSettings.accepts] 里按身份做，两件事不混在队列里。
 */
internal class BubbleQueue(private val capacity: Int = 64) {
    private val entries = ArrayDeque<BubbleItem>()

    val size: Int get() = entries.size
    val head: BubbleItem? get() = entries.firstOrNull()

    fun snapshot(): List<BubbleItem> = entries.toList()

    /** 同 key 去重后放到队首。 */
    fun add(item: BubbleItem) {
        item.notificationKey?.let { key -> entries.removeAll { it.notificationKey == key } }
        entries.addFirst(item)
        while (entries.size > capacity) entries.removeLast()
    }

    fun removeHead(): BubbleItem? = entries.removeFirstOrNull()

    fun remove(key: String?): BubbleItem? {
        if (key == null) return null
        val removed = entries.firstOrNull { it.notificationKey == key } ?: return null
        entries.remove(removed)
        return removed
    }

    fun clear() = entries.clear()

    fun prune(keep: (BubbleItem) -> Boolean) {
        entries.removeAll { !keep(it) }
    }
}
