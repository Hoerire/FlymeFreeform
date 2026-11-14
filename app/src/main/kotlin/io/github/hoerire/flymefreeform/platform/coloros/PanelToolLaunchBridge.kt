package io.github.hoerire.flymefreeform.platform.coloros

import android.os.Bundle
import android.os.SystemClock
import io.github.hoerire.flymefreeform.hook.launch.ZoomLaunchSpec

/**
 * 「更多」面板工具点击与小窗启动参数之间的桥。
 *
 * 面板内的普通应用由 `PageRoutUtils.startActivityAsZoomWindow` 启动，
 * 系统在该路径上自行写入小窗参数；工具（付款码、扫一扫这类）走的是另一条路径：
 * `handle()` 自建 Intent，经 `PageRoutUtils.startActivity` →
 * `AppPlatformUtils.n(Intent, Bundle, UserHandle)` 交由系统 uid 代发，
 * Bundle 内只有 `launchDisplayId`，因此按全屏打开。
 *
 * 该启动不能由侧边栏进程重发：工具的目标是第三方应用中未导出的 Activity，
 * 仅系统 uid 代发能通过权限校验。故此处只登记本次点击的目标包，在发起瞬间把
 * [ZoomLaunchSpec] 的参数并入系统已经建好的 Bundle，启动链路与发起方保持原样。
 *
 * 工具的派发经 `ThreadUtilKt.l` 投递到主线程，跨线程的布尔标志不可靠，
 * 因此登记带有效期，命中即消费。
 */
internal object PanelToolLaunchBridge {
    /** 登记有效期：点击到系统代发之间只隔一次主线程投递与一次参数组装。 */
    private const val VALID_MS = 5_000L

    private const val KEY_SPLASH_STYLE = "android.activity.splashScreenStyle"
    private const val KEY_LAUNCH_SIDE = "start_zoom_at_other_side"
    private const val SPLASH_STYLE = 1
    private const val LAUNCH_AT_RIGHT = 14

    // 登记在界面线程写入、在派发线程读取，故按可见性要求声明为 volatile。
    @Volatile
    private var targetPackage: String? = null

    @Volatile
    private var armedAt = 0L

    /** 登记一次工具点击。目标包未知时不登记，避免改写其它不相干的启动。 */
    fun arm(packageName: String?) {
        val target = packageName?.takeIf { it.isNotEmpty() }
        targetPackage = target
        armedAt = if (target == null) 0L else SystemClock.uptimeMillis()
    }

    fun cancel() {
        targetPackage = null
        armedAt = 0L
    }

    /**
     * 命中登记时把参数就地并入 [options]，返回是否发生改写。
     *
     * 就地写入而非替换对象：系统已在该 Bundle 上设置了 `launchDisplayId`，
     * 换对象会丢失原值；[ZoomLaunchSpec.bundle] 只补齐模块需要的键。
     */
    fun apply(packageName: String?, options: Bundle?): Boolean {
        val target = targetPackage ?: return false
        if (options == null || packageName == null || packageName != target) return false
        val expired = SystemClock.uptimeMillis() - armedAt > VALID_MS
        cancel()
        if (expired) return false
        if (ZoomLaunchSpec.alreadyZoom(options)) return false
        options.putAll(ZoomLaunchSpec.bundle(options))
        options.putInt(KEY_SPLASH_STYLE, SPLASH_STYLE)
        options.putInt(KEY_LAUNCH_SIDE, LAUNCH_AT_RIGHT)
        return true
    }
}
