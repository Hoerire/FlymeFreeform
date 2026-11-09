package io.github.hoerire.flymefreeform.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot
import io.github.hoerire.flymefreeform.log.ModuleLog
import java.util.concurrent.CopyOnWriteArrayList

/** Hook 进程内只读配置快照；任何类型损坏都会让该进程整体失败关闭。 */
internal class ProcessConfiguration(
    private val log: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    @Volatile
    var snapshot: ModuleSettingsSnapshot = FAIL_CLOSED
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    private val listeners = CopyOnWriteArrayList<(ModuleSettingsSnapshot) -> Unit>()
    private var preferences: SharedPreferences? = null

    /**
     * 记录配置来源并读一次快照。
     *
     * 不再注册跨进程的 OnSharedPreferenceChangeListener：它在 hook 进程里并不可靠，
     * 设置变化的实时同步改由模块应用广播 → 接收方 forceReload + override 完成。
     */
    fun start(remotePreferences: SharedPreferences) {
        preferences = remotePreferences
        refresh(remotePreferences, true)
    }

    fun observe(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeObserver(listener: (ModuleSettingsSnapshot) -> Unit) {
        listeners -= listener
    }

    /**
     * 强制重读一次磁盘配置。
     *
     * 两个调用点：设置广播的接收方（实时同步），以及拦截路径上"开关看起来还是旧值"时的兜底。
     */
    fun forceReload() {
        val current = preferences ?: return
        runCatching { current.javaClass.getMethod("reload").invoke(current) }
        refresh(current, false)
    }

    /** 接收经调用方 UID 校验的运行时推送，避免等待目标进程重新加载。 */
    fun override(next: ModuleSettingsSnapshot) {
        isAvailable = true
        io.github.hoerire.flymefreeform.log.VerboseLog.update(next.verboseLogEnabled)
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { listener -> listener(next) }
    }

    private fun refresh(preferences: SharedPreferences, initial: Boolean) {
        val next =
            try {
                ModuleSettingsSnapshot.readFrom(preferences).also { isAvailable = true }
            } catch (exception: ClassCastException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_TYPE_MISMATCH", exception)
                FAIL_CLOSED
            } catch (exception: RuntimeException) {
                isAvailable = false
                log(Log.ERROR, "MODULE_CONFIG_READ_FAILED", exception)
                FAIL_CLOSED
            }
        io.github.hoerire.flymefreeform.log.VerboseLog.update(next.verboseLogEnabled)
        val changed = next != snapshot
        snapshot = next
        // 仅在进程启动时记录一次状态。此后每次配置变更都会进入本方法，
        // 以 INFO 级别输出会产生大量重复记录，故降为详细日志。
        if (initial) {
            log(
                Log.INFO,
                if (next.enabled && isAvailable) "MODULE_STATE_ENABLED" else "MODULE_STATE_DISABLED",
                null,
            )
        } else if (changed) {
            ModuleLog.debug("FlymeFreeform") { "MODULE_STATE_CHANGED_" + (if (next.enabled && isAvailable) "ON" else "OFF") }
        }
        if (initial || changed) {
            listeners.forEach { listener -> listener(next) }
        }
    }

    private companion object {
        val FAIL_CLOSED =
            ModuleSettingsSnapshot(
                enabled = false,
                leftCornerEnabled = false,
                rightCornerEnabled = false,
            )
    }
}
