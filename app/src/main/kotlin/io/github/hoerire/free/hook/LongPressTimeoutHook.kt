package io.github.hoerire.free.hook

import android.content.ContentResolver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.log.ModuleLog

/**
 * 把「触发延迟」写入系统长按阈值。
 *
 * 拖拽由应用侧的 `View` 长按判定启动，阈值取自 `Settings.Secure.LONG_PRESS_TIMEOUT`（默认 400ms）；
 * 模块在 system_server 内以系统身份写入该设置，即可让拖拽更早触发。
 * 模块关闭时写回默认值，避免把改动遗留在系统里。
 *
 * 观察者在 system_server 刚起来时就注册，此时 `SettingsProvider` 往往还没就绪，
 * `putInt` 会因为底层 `IActivityManager` 尚不可用而抛空指针。
 * 所以写入失败不当成错误：按退避隔一段时间重试，全部尝试都失败才记一条带原因的 WARN。
 */
internal object LongPressTimeoutHook {
    private const val LONG_PRESS_TIMEOUT = "long_press_timeout"

    /** 重试间隔：开机早期设置提供者逐步可用，前几次失败属正常现象。 */
    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 8_000L, 20_000L)

    private val handler by lazy { Looper.getMainLooper()?.let { Handler(it) } }

    fun install(configuration: ProcessConfiguration) {
        configuration.observe { settings ->
            val target =
                if (settings.enabled) {
                    ModulePreferences.coerceDragLongPressMs(settings.dragLongPressMs)
                } else {
                    ModulePreferences.DEFAULT_DRAG_LONG_PRESS_MS
                }
            // 设置又变了：撤销还在排队的重试，按新目标重新来一轮。
            handler?.removeCallbacksAndMessages(null)
            schedule(target, 0)
        }
    }

    private fun schedule(millis: Int, attempt: Int) {
        val error = attempt(millis)
        if (error == null) return
        if (attempt >= RETRY_DELAYS_MS.size) {
            // 到这里说明系统已经跑了一阵还是写不进去，才值得人看。
            ModuleLog.warn("LONG_PRESS_TIMEOUT_WRITE_FAILED", error)
            return
        }
        val delay = RETRY_DELAYS_MS[attempt]
        val next = handler
        if (next == null) {
            ModuleLog.warn("LONG_PRESS_TIMEOUT_WRITE_FAILED", error)
            return
        }
        next.postDelayed({ schedule(millis, attempt + 1) }, delay)
    }

    /** 写入一次；返回 null 表示写入成功或本来就是这个值，否则返回本次失败原因。 */
    private fun attempt(millis: Int): Throwable? {
        val resolver = systemResolver() ?: return IllegalStateException("系统上下文尚未就绪")
        val current =
            runCatching { Settings.Secure.getInt(resolver, LONG_PRESS_TIMEOUT, -1) }.getOrDefault(-1)
        if (current == millis) return null
        return runCatching { Settings.Secure.putInt(resolver, LONG_PRESS_TIMEOUT, millis) }
            .exceptionOrNull()
    }

    /** system_server 内取系统上下文：`ActivityThread.systemMain()` 的 context 太重，这里取已有实例。 */
    private fun systemResolver(): ContentResolver? =
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .invoke(null) ?: return null
            val field = thread.javaClass.getDeclaredField("mSystemContext").apply { isAccessible = true }
            (field.get(thread) as? android.content.Context)?.contentResolver
        }.getOrNull()
}
