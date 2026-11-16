package io.github.hoerire.free.platform.coloros

import android.content.Context

/**
 * 自由传送的能力探测。
 *
 * 传送门面板依赖 Content Portal 17 的窗口结构与网格实现，旧版本（ColorOS 16 及以下）
 * 既没有对应的 Hook 目标，面板也拉不起来；这类机型上开关必须保持灰色，
 * 避免用户打开后得不到任何效果。
 *
 * 判定只看传送门自身的版本号：它随系统版本一起推，比系统属性可靠，也不需要隐藏 API。
 */
internal object PortalSupport {
    private const val PORTAL_PACKAGE = "com.oplus.contentportal"

    /** 支持自由传送的最低传送门主版本。 */
    private const val MIN_MAJOR_VERSION = 17

    fun isSupported(context: Context): Boolean =
        try {
            val info = context.packageManager.getPackageInfo(PORTAL_PACKAGE, 0)
            (info.versionName?.substringBefore('.')?.toIntOrNull() ?: 0) >= MIN_MAJOR_VERSION
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
}
