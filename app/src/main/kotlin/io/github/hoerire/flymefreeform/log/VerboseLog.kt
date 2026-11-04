package io.github.hoerire.flymefreeform.log

/**
 * 详细日志开关的运行时状态。设置项一变，各 hook 进程通过 ProcessConfiguration 同步，
 * 未打开时所有诊断日志都不产生输出，只保留 hook 安装与异常。
 */
internal object VerboseLog {
    @Volatile
    var enabled: Boolean = false
        private set

    fun update(next: Boolean) {
        if (enabled == next) return
        enabled = next
    }
}
