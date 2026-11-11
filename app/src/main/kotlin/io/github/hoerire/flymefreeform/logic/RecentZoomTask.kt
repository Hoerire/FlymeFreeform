package io.github.hoerire.flymefreeform.logic

/**
 * 记录最近创建的小窗 task。
 *
 * 用来区分两种「系统要求某个窗口退出」的情况：
 *
 * - 旧窗口被新应用顶掉——这时该退出的不是刚建的那个，属于「自动收窗」要处理的对象；
 * - 应用自己在变小窗——ColorOS 17 把这条也并进了统一的退场裁决，
 *   而退出对象正是刚创建的那个窗口，不能把它关掉。
 *
 * 只记最新一个：记成一串的话，快速连开两个窗口时两个都算「刚创建」，
 * 系统让早期那个退出时就会被误放行，自动收窗反而失效。
 */
internal object RecentZoomTask {
    /** 判定的时间窗；超出后不再算「刚创建」，按普通窗口处理。 */
    private const val WINDOW_MS = 2000L

    @Volatile private var marked: Any? = null

    @Volatile private var markedAt = 0L

    fun mark(created: Any) {
        marked = created
        markedAt = android.os.SystemClock.uptimeMillis()
    }

    /** 这个 task 是否就是刚创建的那个窗口。 */
    fun isRecent(candidate: Any?): Boolean {
        if (candidate == null) return false
        val current = marked ?: return false
        if (current !== candidate) return false
        return android.os.SystemClock.uptimeMillis() - markedAt <= WINDOW_MS
    }
}
