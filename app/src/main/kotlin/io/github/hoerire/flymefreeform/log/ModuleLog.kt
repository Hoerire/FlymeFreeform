package io.github.hoerire.flymefreeform.log

import android.util.Log

/**
 * 模块日志出口。
 *
 * Hook 进程在安装时通过 [attach] 注入框架的日志实现，所有诊断日志随之写入
 * LSPosed 的 `/data/adb/lspd/log/modules_*.log`（设置里的「详细日志」可在 LSPosed 中导出）。
 * 未注入（例如纯应用进程）时退回 android.util.Log。
 *
 * 输出受 [VerboseLog] 控制：开关关闭时不产生任何诊断日志，仅保留 hook 安装与异常。
 */
internal object ModuleLog {
    @Volatile
    private var sink: ((priority: Int, code: String, throwable: Throwable?) -> Unit)? = null

    fun attach(next: (priority: Int, code: String, throwable: Throwable?) -> Unit) {
        sink = next
    }

    /**
     * 诊断日志：仅「详细日志」开启时求值并输出。
     *
     * 消息以 lambda 形式传入，开关关闭时连字符串拼接与状态读取一并跳过，
     * 不留任何诊断开销。
     */
    fun debug(tag: String, message: () -> String) {
        if (!VerboseLog.enabled) return
        emit(Log.DEBUG, tag + " " + message(), null)
    }

    /** 携带异常对象的诊断日志：同样受开关控制，消息惰性求值。 */
    fun debug(tag: String, throwable: Throwable?, message: () -> String) {
        if (!VerboseLog.enabled) return
        emit(Log.DEBUG, tag + " " + message(), throwable)
    }

    /** 关键日志：始终输出（hook 安装、状态切换等）。 */
    fun info(code: String, throwable: Throwable? = null) = emit(Log.INFO, code, throwable)

    fun warn(code: String, throwable: Throwable? = null) = emit(Log.WARN, code, throwable)

    private fun emit(priority: Int, code: String, throwable: Throwable?) {
        val target = sink
        if (target != null) {
            target(priority, code, throwable)
        } else {
            Log.println(priority, "FlymeFreeform", code + (throwable?.let { " | " + it } ?: ""))
        }
    }
}
