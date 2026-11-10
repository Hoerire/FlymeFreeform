package io.github.hoerire.flymefreeform.logic

/**
 * 记录最近一次「从桌面点开」的启动时刻。
 *
 * 桌面点开的应用一定是全屏；此时系统若把旁边的迷你窗判成退场，我们不应该跟着关闭它。
 * 两个钩子都跑在 system_server，用进程内的时刻标记即可，不需要跨进程通道。
 */
internal object LauncherLaunchMarker {
    private const val WINDOW_MS = 2000L

    @Volatile private var lastAt = 0L

    fun mark() {
        lastAt = android.os.SystemClock.uptimeMillis()
    }

    fun recentlyLaunched(): Boolean {
        val at = lastAt
        return at > 0 && android.os.SystemClock.uptimeMillis() - at <= WINDOW_MS
    }
}