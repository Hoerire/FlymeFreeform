package io.github.hoerire.flymefreeform.hook

import android.os.SystemClock

/**
 * 最近一次「以小窗启动」的时间戳。
 *
 * 系统裁决谁该退出小窗时，可能换到别的线程执行：实测 23:39:52 那次，启动发生在
 * system_server 主线程，退出裁决发生在 5311 线程，调用栈里看不到发起启动的帧。
 * 只凭调用栈判断会把「新启动顶掉旧窗」误判成「与启动无关的退出」，于是原样放行，
 * 旧窗被收成贴边浮标。因此额外记录一次启动时间戳，作为调用栈判据的兜底。
 *
 * 写入点：扇形启动器与 ActivityStarter 注入器；读取点：窗外关闭/替换收窗钩子。
 * 三者同在 system_server 进程，不跨进程。
 */
internal object RecentLaunchClock {
    @Volatile
    private var markedAt = 0L

    @Volatile
    private var markedPackage: String? = null

    fun mark(packageName: String?) {
        markedAt = SystemClock.uptimeMillis()
        markedPackage = packageName
    }

    /** 最近一次小窗启动是否还在时间窗内。 */
    fun recent(windowMs: Long): Boolean {
        val last = markedAt
        return last != 0L && SystemClock.uptimeMillis() - last <= windowMs
    }

    fun lastPackage(): String? = markedPackage
}
