package io.github.hoerire.flymefreeform.hook

import android.content.ContentResolver
import android.provider.Settings
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 把「触发延迟」写入系统长按阈值。
 *
 * 拖拽由应用侧的 `View` 长按判定启动，阈值取自 `Settings.Secure.LONG_PRESS_TIMEOUT`（默认 400ms）；
 * 模块在 system_server 内以系统身份写入该设置，即可让拖拽更早触发。
 * 模块关闭时写回默认值，避免把改动遗留在系统里。
 */
internal object LongPressTimeoutHook {
    private const val LONG_PRESS_TIMEOUT = "long_press_timeout"

    fun install(configuration: ProcessConfiguration) {
        configuration.observe { settings ->
            val target =
                if (settings.enabled) {
                    ModulePreferences.coerceDragLongPressMs(settings.dragLongPressMs)
                } else {
                    ModulePreferences.DEFAULT_DRAG_LONG_PRESS_MS
                }
            apply(target)
        }
    }

    private fun apply(millis: Int) {
        val resolver = systemResolver() ?: return
        val current =
            runCatching { Settings.Secure.getInt(resolver, LONG_PRESS_TIMEOUT, -1) }.getOrDefault(-1)
        if (current == millis) return
        runCatching { Settings.Secure.putInt(resolver, LONG_PRESS_TIMEOUT, millis) }
            .onFailure { ModuleLog.warn("LONG_PRESS_TIMEOUT_WRITE_FAILED", it) }
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
