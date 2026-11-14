package io.github.hoerire.flymefreeform.hook

import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.gesture.CornerTriggerRegion
import io.github.hoerire.flymefreeform.log.ModuleLog

/**
 * 让「从角落上滑」不再被系统的 Home 手势接走。
 *
 * 系统手势的入口是 com.android.server.wm.SystemGesturesPointerEventListener：
 * 它是 WMS 的指针监听器，既不受 pilferPointers 影响，也不吃 updateInvalidRegion
 * 挖出来的那块洞，所以在角落上滑时它照样把整屏缩成最近任务
 * （实机 03:26:49：detectSwipe: 2 → detachNavigationBarFromApp → launcher exit gesture）。
 *
 * 这里在它的 onPointerEvent 入口把「落在扇形角区里的按下」整条吞掉：拿不到 DOWN，
 * 它就记不下起点，后续 MOVE/UP 也不会触发任何系统手势。扇形本身走的是模块自己的
 * gesture monitor，不经过这里，行为和之前一致。
 */
internal class SystemGestureSuppressor(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    @Volatile private var suppressing = false

    fun install(loader: ClassLoader) {
        val target =
            runCatching { loader.loadClass(CLASS_NAME) }.getOrNull()
                ?: run {
                    ModuleLog.warn("SYSTEM_GESTURE_SUPPRESS_TARGET_MISSING")
                    return
                }
        val method =
            target.declaredMethods.firstOrNull { it.name == "onPointerEvent" && it.parameterCount == 1 }
                ?: run {
                    ModuleLog.warn("SYSTEM_GESTURE_SUPPRESS_METHOD_MISSING")
                    return
                }
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.system.gesture_suppress")
            .intercept { chain ->
                val event = chain.getArg(0) as? MotionEvent
                if (event == null) return@intercept chain.proceed()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        suppressing = isInCorner(event.x, event.y)
                        if (suppressing) return@intercept null
                    }
                    MotionEvent.ACTION_MOVE -> if (suppressing) return@intercept null
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (suppressing) {
                            suppressing = false
                            return@intercept null
                        }
                    else -> Unit
                }
                chain.proceed()
            }
        ModuleLog.info("SYSTEM_GESTURE_SUPPRESS_INSTALLED")
    }

    private fun isInCorner(x: Float, y: Float): Boolean {
        val settings = configuration.snapshot
        if (!settings.enabled) return false
        if (!settings.leftCornerEnabled && !settings.rightCornerEnabled) return false
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        return CornerTriggerRegion.detectSide(
            x = x,
            y = y,
            displayWidth = metrics.widthPixels.toFloat(),
            displayHeight = metrics.heightPixels.toFloat(),
            radius = CornerTriggerRegion.radiusPx(settings.cornerTriggerRangeDp, metrics.density),
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
    }

    private companion object {
        const val CLASS_NAME = "com.android.server.wm.SystemGesturesPointerEventListener"
    }
}
