package io.github.hoerire.free.apps

internal object AppSelectionPolicy {
    /**
     * 扇形菜单的条目：只取用户添加的固定项。
     *
     * 添加几个显示几个；没有固定项时返回空列表，由调用方决定是否只显示「更多」入口。
     */
    fun <T> radialItems(
        availablePins: List<T>,
        identity: (T) -> Any,
        limit: Int,
    ): List<T> = availablePins.distinctBy(identity).take(limit)

    fun <T> panelItems(
        recent: List<T>,
        all: List<T>,
        excluded: Set<Any>,
        identity: (T) -> Any,
    ): List<T> =
        (recent + all).distinctBy(identity).filterNot { identity(it) in excluded }
}
