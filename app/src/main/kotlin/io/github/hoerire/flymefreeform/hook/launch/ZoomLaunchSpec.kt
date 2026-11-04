package io.github.hoerire.flymefreeform.hook.launch

import android.os.Bundle

/**
 * ColorOS 自由小窗的启动参数契约。
 *
 * 系统 zoom 启动由三项参数共同成立：窗口模式 100、zoomFlags 5、LaunchScenario 1。
 * 任意一项缺失，ActivityStarter 会退回窗口模式 0（全屏）。
 */
internal object ZoomLaunchSpec {
    /** 跨进程来源标记：SystemUI 写入，system_server 读取。 */
    const val EXTRA_SOURCE = "flymefreeform_zoom_source"

    /** 通知点击来源的取值。 */
    const val SOURCE_NOTIFICATION = 1

    /** 已注入标记，避免同一次启动被重复改写。 */
    const val EXTRA_APPLIED = "flymefreeform_zoom_applied"

    const val KEY_WINDOW_MODE = "extra_window_mode"
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val KEY_ZOOM_FLAGS = "android:activity.mZoomLaunchFlags"
    private const val KEY_ADJUST_IME = "androidx.activity.AdjustInputMethod"
    private const val KEY_LAUNCH_SCENARIO = "androidx.activity.LaunchScenario"
    private const val WINDOW_MODE_ZOOM = 100
    private const val ZOOM_FLAGS = 5
    private const val LAUNCH_SCENARIO = 1

    /** 判定原始 options 是否已携带系统写入的小窗参数；已存在时不重复写入。 */
    fun alreadyZoom(original: Bundle?): Boolean =
        original != null && original.getInt(KEY_WINDOW_MODE, 0) == WINDOW_MODE_ZOOM

    /** 在原始 options 之上写入小窗参数，其余键保持原值。 */
    fun bundle(original: Bundle?): Bundle {
        val result = Bundle()
        if (original != null) result.putAll(original)
        result.putInt(KEY_ZOOM_FLAGS, ZOOM_FLAGS)
        result.putInt(KEY_WINDOW_MODE, WINDOW_MODE_ZOOM)
        result.putInt(KEY_WINDOWING_MODE, WINDOW_MODE_ZOOM)
        result.putBoolean(KEY_ADJUST_IME, true)
        result.putInt(KEY_LAUNCH_SCENARIO, LAUNCH_SCENARIO)
        return result
    }
}
