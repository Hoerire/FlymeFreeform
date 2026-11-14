package io.github.hoerire.flymefreeform.platform.coloros

import android.app.AppOpsManager
import android.content.Context
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 让识屏宿主能被启动。
 *
 * `com.coloros.colordirectservice` 一旦被系统判成 `RUN_ANY_IN_BACKGROUND: ignore`，
 * 连 uid 1000 的 `startForegroundService` 都会被拒（AMS 日志 "Background start not allowed"），
 * 侧边栏、更多面板、扇形三条路径同时失效。出厂状态通常是 allow，被省电策略、清理工具
 * 或用户手动限制过之后才会变成 ignore。
 *
 * 模块跑在 system_server 内，持有 `MANAGE_APP_OPS_MODES`，每次开机纠正一次，
 * 免去逐台设备手动执行 `appops set`。宿主不存在时直接跳过。
 *
 * `setMode` 在公开 SDK 里是系统接口，编译期不可见，因此走反射。
 */
internal object ScreenIdentifyAppOps {
    private const val TAG = "FlymeFreeform"
    private const val HOST_PACKAGE = ScreenIdentifyStarter.HOST_PACKAGE
    private const val OP_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

    fun ensure(context: Context?) {
        if (context == null) return
        val manager = runCatching { context.getSystemService(AppOpsManager::class.java) }.getOrNull() ?: return
        val uid = runCatching {
            context.packageManager.getApplicationInfo(HOST_PACKAGE, 0).uid
        }.getOrNull()
        if (uid == null) {
            // 这台设备没有识屏宿主，什么都不做。
            ModuleLog.debug(TAG) { "SCREEN_IDENTIFY_APPOPS_NO_HOST" }
            return
        }
        val before = readMode(manager, uid)
        if (before == AppOpsManager.MODE_ALLOWED) {
            ModuleLog.debug(TAG) { "SCREEN_IDENTIFY_APPOPS_ALREADY_ALLOWED" }
            return
        }
        val failure = writeAllowed(manager, uid)
        if (failure != null) {
            ModuleLog.debug(TAG) {
                "SCREEN_IDENTIFY_APPOPS_WRITE_FAILED_" + failure.javaClass.simpleName
            }
            return
        }
        ModuleLog.info("SCREEN_IDENTIFY_APPOPS_ALLOWED_" + (before ?: -1) + "_to_" + (readMode(manager, uid) ?: -1))
    }

    @Suppress("DEPRECATION")
    private fun readMode(manager: AppOpsManager, uid: Int): Int? =
        runCatching { manager.checkOpNoThrow(OP_RUN_ANY_IN_BACKGROUND, uid, HOST_PACKAGE) }.getOrNull()

    /** 先试字符串重载，再退到 int 重载；两者都是系统接口，只能反射调用。 */
    private fun writeAllowed(manager: AppOpsManager, uid: Int): Throwable? =
        try {
            val byName = runCatching {
                AppOpsManager::class.java.getMethod(
                    "setMode",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            if (byName != null) {
                byName.invoke(manager, OP_RUN_ANY_IN_BACKGROUND, uid, HOST_PACKAGE, AppOpsManager.MODE_ALLOWED)
            } else {
                val op = AppOpsManager::class.java
                    .getField("OP_RUN_ANY_IN_BACKGROUND")
                    .getInt(null)
                AppOpsManager::class.java
                    .getMethod(
                        "setMode",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                    )
                    .invoke(manager, op, uid, HOST_PACKAGE, AppOpsManager.MODE_ALLOWED)
            }
            null
        } catch (t: Throwable) {
            (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        }
}
