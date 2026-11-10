package io.github.hoerire.flymefreeform.logic

/**
 * 记录最近一次界面启动（ActivityStarter.execute）的时刻。
 *
 * 收窗只应对「被新应用顶掉」的窗口生效；ColorOS 17 把迷你窗变形也并进了同一套退场裁决，
 * 那种情形没有启动发生，用这个标记即可放过。两个钩子同处 system_server，进程内标记即可。
 */
internal object LaunchInFlight {
    private const val WINDOW_MS = 1500L

    @Volatile private var lastAt = 0L

    fun mark() {
        lastAt = android.os.SystemClock.uptimeMillis()
    }

    fun active(): Boolean {
        val at = lastAt
        return at > 0 && android.os.SystemClock.uptimeMillis() - at <= WINDOW_MS
    }

    /**
     * 取用一次启动标记：命中后立即失效。
     *
     * 一次启动只允许收掉一个窗口。否则"打开应用后立刻划成迷你窗"这种连续退出会反复
     * 命中同一个标记，把迷你窗也当成被顶掉的窗口收掉（实测 16:32:02–04 连续三次）。
     */
    fun consume(): Boolean {
        val at = lastAt
        if (at <= 0) return false
        if (android.os.SystemClock.uptimeMillis() - at > WINDOW_MS) return false
        lastAt = 0
        return true
    }
}