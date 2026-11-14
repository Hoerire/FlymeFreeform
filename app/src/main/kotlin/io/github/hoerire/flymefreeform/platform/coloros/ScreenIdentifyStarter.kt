package io.github.hoerire.flymefreeform.platform.coloros

import android.content.Context
import android.content.Intent
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 小布识屏的系统侧启动。
 *
 * 侧边栏点「识屏」时，原生代码是这样起来的（`BreenoScreenIdentifyTool.handle()`）：
 * `startForegroundService(Intent("oplus.intent.action.DIRECT_SIDEBAR_SERVICE").setPackage("com.coloros.colordirectservice"))`。
 * 侧边栏进程处于后台时，这次前台服务启动会被系统直接拒绝——
 *
 * ```
 * W ActivityManager: Background start not allowed: service Intent { act=oplus.intent.action.DIRECT_SIDEBAR_SERVICE }
 *   to com.coloros.colordirectservice/.InvokeEntranceService from pkg=com.coloros.smartsidebar startFg?=true
 * ```
 *
 * 所以改由 system_server（uid 1000，不受后台启动限制）代发同一个 Intent。
 * 任何路径失败都返回 false，调用方可以退回原生路径。
 */
internal object ScreenIdentifyStarter {
    /** 侧边栏里「识屏」这个工具的 alias，原生 `BreenoScreenIdentifyTool` 与之一一对应。 */
    const val ALIAS = "breeno_screen_identify"

    /** 承载识屏的宿主包，取自 `BreenoScreenIdentifyTool.getValidPkg()`。 */
    const val HOST_PACKAGE = "com.coloros.colordirectservice"

    private const val ACTION = "oplus.intent.action.DIRECT_SIDEBAR_SERVICE"
    private const val EXTRA_ENTRANCE = "extra_entrance_function"
    private const val ENTRANCE_FULL_SCREEN_OCR = "full_screen_ocr"
    private const val EXTRA_TRIGGERED_APP = "triggered_app"
    private const val EXTRA_X = "first_point_down_x"
    private const val EXTRA_Y = "first_point_down_y"

    /** 原生路径里 `triggered_app` 记的是发起方包名，这里保持同值以免宿主侧分支不同。 */
    private const val TRIGGERED_APP = "com.coloros.smartsidebar"

    /** 由侧边栏进程发来的代发请求。 */
    const val REQUEST_ACTION = "io.github.hoerire.flymefreeform.action.START_SCREEN_IDENTIFY"

    const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"

    fun intent(x: Float, y: Float): Intent =
        Intent(ACTION).setPackage(HOST_PACKAGE).apply {
            putExtra(EXTRA_ENTRANCE, ENTRANCE_FULL_SCREEN_OCR)
            putExtra(EXTRA_TRIGGERED_APP, TRIGGERED_APP)
            putExtra(EXTRA_X, x)
            putExtra(EXTRA_Y, y)
        }

    /** 以 system_server 身份启动宿主的前台服务；调用方必须是系统进程。 */
    fun start(context: Context, x: Float, y: Float): Boolean = try {
        context.startForegroundService(intent(x, y))
        ModuleLog.debug("FlymeFreeform") { "SCREEN_IDENTIFY_STARTED" }
        true
    } catch (t: Throwable) {
        ModuleLog.debug("FlymeFreeform") {
            "SCREEN_IDENTIFY_START_FAILED_" + t.javaClass.simpleName + "_" + (t.message ?: "-")
        }
        false
    }
}
