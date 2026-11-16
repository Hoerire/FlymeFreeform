package io.github.hoerire.free.logic

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标的显示状态。
 *
 * 桌面入口由清单里的 `activity-alias`（[ALIAS]）提供，主 Activity 本身不带 LAUNCHER 过滤器：
 * 禁用别名只会让桌面和应用列表里少一个入口，主 Activity 仍然可以被系统设置里的
 * 「Free」条目用显式组件拉起。用组件状态而不是自存配置，避免出现「配置说隐藏、图标还在」。
 */
internal object LauncherIcon {
    private const val ALIAS = "io.github.hoerire.free.LauncherAlias"

    fun isHidden(context: Context): Boolean =
        runCatching {
            context.packageManager.getComponentEnabledSetting(alias(context)) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(false)

    fun setHidden(context: Context, hidden: Boolean): Boolean =
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                alias(context),
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                },
                PackageManager.DONT_KILL_APP,
            )
            true
        }.getOrDefault(false)

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS)
}
