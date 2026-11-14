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

    /** 已经输出过的 code 前缀，用于进程内去重。 */
    private val emitted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        emit(Log.DEBUG, if (tag.isEmpty()) message() else tag + " " + message(), null)
    }

    /** 不带 tag 的诊断日志。 */
    fun debug(message: () -> String) = debug("", message)

    /**
     * 安装日志：不受「详细日志」开关影响，每个进程只输出一次。
     *
     * 关闭详细日志时模块应完全静默，唯一保留的就是这一行，用来确认 hook 有没有装上。
     */
    fun install(code: String) = emit(Log.INFO, code, null)

    /** 携带异常对象的诊断日志：同样受开关控制，消息惰性求值。 */
    fun debug(tag: String, throwable: Throwable?, message: () -> String) {
        if (!VerboseLog.enabled) return
        emit(Log.DEBUG, tag + " " + message(), throwable)
    }

    /**
     * 状态日志：同样受「详细日志」控制。
     *
     * 关闭详细日志时模块应当完全静默——一次操作里会打出十几行状态点
     * （挖角、启动判定、浮层起落、手势生命周期），逐条判断哪条"重要"必然遗漏，
     * 所以统一收口：只有异常（[warn]）无条件输出。
     */
    fun info(code: String, throwable: Throwable? = null) {
        if (!VerboseLog.enabled) return
        // 同一个 code 在进程内只输出一次：正常路径重复触发没有信息量，
        // 真正要看的是"出现过没有"，以及出现异常时的 WARN（那条不去重）。
        if (!emitted.add(stableKey(code))) return
        emit(Log.INFO, code, throwable)
    }

    /** 去掉 code 里的变量部分（数字、包名、类名后缀），让同一类事件算作同一个。 */
    private fun stableKey(code: String): String {
        var end = code.length
        for (index in code.indices) {
            val ch = code[index]
            if (ch.isDigit()) {
                end = index
                break
            }
        }
        return code.substring(0, end)
    }

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
