package io.github.hoerire.free.settings

import io.github.hoerire.free.config.ModulePreferences
import io.github.libxposed.api.XposedModule
import android.util.Log

/**
 * 设置页与模块配置之间的桥。
 *
 * 设置页跑在设置进程里，由模块代码直接实例化，因此可以复用框架下发的远程偏好
 * （`getRemotePreferences`）——不需要额外的文件桥，也不会出现两份配置。
 * 模块实例在 `onModuleLoaded` 时登记到这里。
 */
internal object ModuleRuntime {
    @Volatile
    var module: XposedModule? = null

    fun readBoolean(key: String, fallback: Boolean): Boolean =
        runCatching {
            module
                ?.getRemotePreferences(ModulePreferences.GROUP)
                ?.getBoolean(key, fallback)
        }.getOrNull() ?: fallback

    fun readString(key: String, fallback: String): String =
        runCatching {
            module
                ?.getRemotePreferences(ModulePreferences.GROUP)
                ?.getString(key, fallback)
        }.getOrNull() ?: fallback

    fun writeBoolean(key: String, value: Boolean): Boolean =
        runCatching {
            val preferences =
                module?.getRemotePreferences(ModulePreferences.GROUP) ?: return false
            preferences.edit().putBoolean(key, value).commit()
        }.getOrNull() ?: false

    fun log(code: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                module?.log(Log.INFO, "Free", code)
            } else {
                module?.log(Log.WARN, "Free", code, throwable)
            }
        }
    }
}
