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
}