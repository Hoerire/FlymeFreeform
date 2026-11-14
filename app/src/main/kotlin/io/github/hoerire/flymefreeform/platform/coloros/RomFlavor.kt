package io.github.hoerire.flymefreeform.platform.coloros

import android.os.Build

/**
 * ROM 世代判定。
 *
 * ColorOS 17（Android 17 / API 37 起）改了两处浮动窗口行为：
 *   1. 启动新窗口时会"避让"已有窗口（avoidOverlappingAreas），落点被推走；
 *   2. 迷你窗/贴边浮标改由状态机 switchState 恢复，toggleFlexibleWindow(包名) 为空调用。
 * 这些补丁只在 17 及以上启用；16 及更早保持模块原有逻辑，不做任何接管。
 */
internal object RomFlavor {
    /**
     * ColorOS 17 对应 Android 17（API 37）。
     * API 36 是 ColorOS 16，属于"原厂行为仍然成立"的一代，必须排除在外。
     */
    val isColorOs17OrNewer: Boolean
        get() = Build.VERSION.SDK_INT >= 37
}
