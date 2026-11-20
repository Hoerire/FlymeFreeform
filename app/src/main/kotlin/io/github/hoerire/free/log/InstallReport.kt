package io.github.hoerire.free.log

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hook 安装汇总。
 *
 * 「详细日志」打开时每装一项都逐条记录，方便定位是哪个目标没挂上；
 * 关闭时安装阶段只累加计数，由 [flush] 统一打一行：
 * 全部成功只给数量（`HOOKS_<进程>_ok<数量>`），
 * 有失败时在汇总里点出项目名（`_fail<数量>_<项目>_<项目>`），
 * 每一项失败本身仍带异常与堆栈单独输出。
 *
 * 汇总只统计安装阶段：之后运行期的零散日志不并入，避免把「后来发生的次数」算进安装数量。
 */
internal object InstallReport {
    /** 失败行上限：异常信息可能很长，日志里只留能看清的那一段。 */
    private const val MAX_FAILURE_CHARS = 240

    private class Failure(val name: String, val error: Throwable?)

    private val installed = AtomicInteger()
    private val failures = CopyOnWriteArrayList<Failure>()

    @Volatile
    private var process = "unknown"

    /** 是否处于安装阶段：只有这一阶段才累计并汇总。 */
    @Volatile
    var recording: Boolean = false
        private set

    fun begin(processName: String?) {
        process = (processName ?: "unknown").replace(':', '_').replace('.', '_')
        installed.set(0)
        failures.clear()
        recording = true
    }

    /** 安装阶段结束：之后的日志按单条输出，不再并入汇总。 */
    fun end() {
        recording = false
    }

    /**
     * 记下一次成功。
     *
     * 安装阶段累加计数；详细日志打开时同时逐条输出。
     * 安装阶段之外的调用（例如运行期才会触发的状态标记）不受汇总影响，
     * 只在详细日志打开时输出。
     */
    fun mark(name: String) {
        if (recording) installed.incrementAndGet()
        if (VerboseLog.enabled) ModuleLog.raw(Log.INFO, name, null)
    }

    /**
     * 记下一次失败：失败不受「详细日志」开关影响，始终输出。
     *
     * LSPosed 的模块日志只写消息、不展开异常对象，所以这里把异常类型与信息
     * 直接拼进这一行，看到就能知道是哪一项、为什么失败。
     */
    fun fail(name: String, error: Throwable?) {
        if (recording) failures += Failure(name, error)
        val line =
            if (error == null) {
                name
            } else {
                name + "_" + error.javaClass.simpleName + "_" + (error.message ?: "无消息")
            }
        ModuleLog.raw(Log.WARN, line.replace('\n', ' ').take(MAX_FAILURE_CHARS), error)
    }

    /**
     * 输出自上次汇总以来的安装结果。
     *
     * 详细日志打开时成功项已经逐条打过，这里只清空计数，不重复刷屏。
     * 失败项的异常已在 [fail] 里单独输出，汇总行只补项目名，方便一眼看全。
     */
    fun flush() {
        val count = installed.getAndSet(0)
        val failed = ArrayList<Failure>(failures)
        failures.clear()
        if (VerboseLog.enabled) return
        if (count == 0 && failed.isEmpty()) return
        val builder = StringBuilder("HOOKS_")
        builder.append(process).append("_ok").append(count)
        if (failed.isNotEmpty()) {
            builder.append("_fail").append(failed.size)
            for (entry in failed) {
                builder.append('_').append(entry.name)
            }
        }
        ModuleLog.raw(if (failed.isEmpty()) Log.INFO else Log.WARN, builder.toString(), null)
    }
}
