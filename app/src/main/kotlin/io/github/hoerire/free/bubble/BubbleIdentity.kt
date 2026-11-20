package io.github.hoerire.free.bubble

/**
 * 气泡通知的身份。
 *
 * 主应用与分身是**两个不同的 Android 用户**，同包名。任何「这是谁的消息」的判断都必须带
 * userId，否则点开分身会落回主应用。分身序号由调用方从 userId 派生
 *（设置页用 `cloneIndexByKey`），不放在身份里。
 *
 * 白名单键的拼装与拆解由 [BubbleKey] 负责：主应用 `pkg`，分身 `pkg#uid`。
 * 设置侧的 `ModulePreferences.decodeBubbleKeys` 只做格式校验与去重，不负责拼装规则。
 */
internal data class BubbleIdentity(
    val packageName: String,
    val userId: Int,
    /** 通知 key；只用于同一条通知的更新/移除，不参与身份比较。 */
    val notificationKey: String? = null,
) {
    val isClone: Boolean get() = userId > 0

    val whitelistKey: String
        get() = BubbleKey.key(packageName, userId)
}

/** 白名单键的唯一实现：`pkg` / `pkg#uid`。 */
internal object BubbleKey {
    fun key(packageName: String, userId: Int): String =
        if (userId > 0) "$packageName#$userId" else packageName

    fun parse(key: String): BubbleIdentity? {
        val hash = key.indexOf('#')
        if (hash < 0) {
            return key.takeIf { it.isNotEmpty() }?.let { BubbleIdentity(it, 0) }
        }
        val packageName = key.substring(0, hash)
        val userId = key.substring(hash + 1).toIntOrNull() ?: return null
        if (packageName.isEmpty() || userId <= 0) return null
        return BubbleIdentity(packageName, userId)
    }
}
