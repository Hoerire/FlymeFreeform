package io.github.hoerire.free.log

import android.util.Log

/**
 * 模块日志出口。
 *
 * Hook 进程在安装时通过 [attach] 注入框架的日志实现，所有诊断日志随之写入
 * LSPosed 的 `/data/adb/lspd/log/modules_*.log`（设置里的「详细日志」可在 LSPosed 中导出）。
 * 未注入（例如纯应用进程）时退回 android.util.Log。
 *
 * 输出受 [VerboseLog] 控制：开关关闭时不产生诊断日志。
 * 安装阶段是个例外：成功项不逐条打印，由 [InstallReport] 汇总成一行数量；
 * 失败项始终带项目名与异常输出，不受开关影响。
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
     * 安装/初始化成功的一步。
     *
     * 安装阶段并入 [InstallReport] 汇总（关闭详细日志时只累计数量），
     * 详细日志打开时同时逐条输出；安装阶段之外的调用只在详细日志打开时输出。
     */
    fun install(code: String) = InstallReport.mark(code)

    /** 携带异常对象的诊断日志：同样受开关控制，消息惰性求值。 */
    fun debug(tag: String, throwable: Throwable?, message: () -> String) {
        if (!VerboseLog.enabled) return
        emit(Log.DEBUG, tag + " " + message(), throwable)
    }

    /**
     * 状态日志：受「详细日志」控制。
     *
     * 关闭详细日志时模块应当完全静默——一次操作里会打出十几行状态点
     * （挖角、启动判定、浮层起落、手势生命周期），逐条判断哪条"重要"必然遗漏，
     * 所以统一收口：只有异常（[warn]）无条件输出。
     * 安装阶段例外：这一步按安装项计数，交给 [InstallReport] 汇总。
     */
    fun info(code: String, throwable: Throwable? = null) {
        if (InstallReport.recording) {
            InstallReport.mark(code)
            return
        }
        if (!VerboseLog.enabled) return
        // 同一个 code 在进程内只输出一次：正常路径重复触发没有信息量，
        // 真正要看的是"出现过没有"，以及出现异常时的 WARN（那条不去重）。
        if (!emitted.add(stableKey(code))) return
        emit(Log.INFO, code, throwable)
    }

    /**
     * 转交框架日志级别的一步（INFO 算安装成功，WARN 及以上算失败并保留细节）。
     *
     * 各 Hook 安装器原先直接调 `module.log(...)`，会绕过「详细日志」开关；
     * 统一走这里之后，关闭详细日志时只剩安装汇总与失败项。
     */
    fun forward(priority: Int, code: String, throwable: Throwable? = null) {
        if (throwable != null || priority >= Log.WARN) {
            InstallReport.fail(code, throwable)
            return
        }
        if (priority == Log.DEBUG || priority == Log.VERBOSE) {
            if (VerboseLog.enabled) emit(priority, code, null)
            return
        }
        info(code, throwable)
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

    fun warn(code: String, throwable: Throwable? = null) {
        if (InstallReport.recording) {
            InstallReport.fail(code, throwable)
            return
        }
        emit(Log.WARN, code, throwable)
    }

    /** 真正写入框架日志的出口；[InstallReport] 汇总也走这里，跳过开关与去重。 */
    internal fun raw(priority: Int, code: String, throwable: Throwable?) = emit(priority, code, throwable)

    private fun emit(priority: Int, code: String, throwable: Throwable?) {
        val target = sink
        if (target != null) {
            target(priority, code, throwable)
        } else {
            Log.println(priority, "Free", code + (throwable?.let { " | " + it } ?: ""))
        }
    }
}
