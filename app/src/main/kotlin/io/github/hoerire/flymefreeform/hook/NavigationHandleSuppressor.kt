package io.github.hoerire.flymefreeform.hook

import android.content.Context
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import io.github.hoerire.flymefreeform.gesture.CornerTriggerRegion
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 在 SystemUI 的导航手柄入口吞掉底角触摸。
 *
 * ColorOS 16 是在桌面的 OplusBaseTouchInteractionService.onInputEvent 里吞事件；
 * 17 删掉了那个类。这条链路之前还有一个按钮：底部手柄的
 * OplusNavigationHandle.handleValidTouchEvent(MotionEvent)，17 上仍然存在（已核对 17.99.02）。
 * 在这里返回 null（不执行原方法），系统后续的 swipe-up 消费链就收不到这次触摸。
 */
internal class NavigationHandleSuppressor(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val context: Context,
) {
    @Volatile private var suppressing = false

    fun install(loader: ClassLoader) {
        CANDIDATES.forEach { className ->
            runCatching {
                val type = loader.loadClass(className)
                val method = type.getDeclaredMethod("handleValidTouchEvent", MotionEvent::class.java)
                method.isAccessible = true
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flymefreeform.systemui.nav_handle")
                    .intercept { chain ->
                        val event = chain.getArg(0) as? MotionEvent
                            ?: return@intercept chain.proceed()
                        if (!configuration.snapshot.enabled) {
                            suppressing = false
                            return@intercept chain.proceed()
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                if (!insideTrigger(event)) {
                                    suppressing = false
                                    return@intercept chain.proceed()
                                }
                                suppressing = true
                                ModuleLog.debug(TAG) { "NAV_HANDLE_SUPPRESS" }
                                null
                            }
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL -> {
                                val holding = suppressing
                                suppressing = false
                                if (holding) null else chain.proceed()
                            }
                            else -> if (suppressing) null else chain.proceed()
                        }
                    }
                module.log(Log.INFO, TAG, "NAV_HANDLE_HOOK_INSTALLED_" + type.simpleName, null)
            }.onFailure { module.log(Log.WARN, TAG, "NAV_HANDLE_HOOK_FAILED_" + className, it) }
        }
    }

    private fun insideTrigger(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
        if (event.pointerCount != 1) return false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
        val settings = configuration.snapshot
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return false
        val radius = settings.cornerTriggerRangeDp * metrics.density
        return CornerTriggerRegion.detectSide(
            x = event.rawX,
            y = event.rawY,
            displayWidth = width,
            displayHeight = height,
            radius = radius,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        val CANDIDATES = listOf(
            "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
            "com.oplus.systemui.navigationbar.gesture.OplusNavigationHandle",
        )
    }
}