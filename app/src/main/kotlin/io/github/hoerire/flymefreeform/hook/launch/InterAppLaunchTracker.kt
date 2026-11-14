package io.github.hoerire.flymefreeform.hook.launch

import android.os.SystemClock
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 跨应用跳转关系记录。
 *
 * 应用之间互相打开时，模块以自由浮窗呈现目标应用；用户在目标应用内完成操作后，
 * 通常由目标应用重新唤起发起方。这一次唤起的特征与主动跳转完全相同（跨包、携带
 * resultTo），若不加区分会把发起方也送入浮窗。
 *
 * 这里只保留最近一次跳转的发起方与目标，用于识别原路返回；关闭浮窗交由系统提供的
 * 返回入口处理，模块不介入。
 */
internal class InterAppLaunchTracker {
    private var caller: String? = null
    private var target: String? = null
    private var recordedAt = 0L

    /** 已判定为返回的调用指纹；覆盖同一次启动的多次回调。 */
    private var returnCaller: String? = null
    private var returnTarget: String? = null
    private var returnUntil = 0L

    /** 记录一次跨应用跳转；同一时刻只保留最近一条。 */
    fun record(callerPackage: String, targetPackage: String) {
        caller = callerPackage
        target = targetPackage
        recordedAt = SystemClock.uptimeMillis()
        ModuleLog.debug(TAG) { "INTER_APP_RECORD " + callerPackage + "->" + targetPackage }
    }

    /**
     * 当前启动是否为上一次跳转的原路返回。
     *
     * 命中后即清除记录，避免后续同方向启动被反复当作返回。
     * 超过 [PENDING_TTL_MS] 未出现返回则自动失效。
     */
    fun consumeIfReturning(callingPackage: String?, targetPackage: String): Boolean {
        val now = SystemClock.uptimeMillis()
        // 同一次启动会分别经过 execute 与 executeRequest 两次判定，
        // 命中后在一段窗口内持续返回 true，避免第二次回调被当成新的跳转。
        if (now < returnUntil &&
            callingPackage == returnTarget &&
            targetPackage == returnCaller
        ) {
            return true
        }
        val pendingCaller = caller ?: return false
        val pendingTarget = target ?: return false
        if (now - recordedAt > PENDING_TTL_MS) {
            clear("EXPIRED")
            return false
        }
        if (callingPackage != pendingTarget || targetPackage != pendingCaller) return false
        returnCaller = pendingCaller
        returnTarget = pendingTarget
        returnUntil = now + RETURN_WINDOW_MS
        clear(null)
        ModuleLog.debug(TAG) { "INTER_APP_RETURN_" + pendingTarget + "->" + pendingCaller }
        return true
    }

    private fun clear(reason: String?) {
        caller = null
        target = null
        recordedAt = 0L
        if (reason != null) ModuleLog.debug(TAG) { "INTER_APP_CLEAR_" + reason }
    }

    private companion object {
        const val TAG = "FFINTER"
        const val PENDING_TTL_MS = 120_000L

        /** 返回指纹的有效窗口：仅用于覆盖同一次启动的两次回调。 */
        const val RETURN_WINDOW_MS = 2_000L
    }
}
