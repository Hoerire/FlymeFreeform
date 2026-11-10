package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsSystemUiApplicationTarget
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi")
internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private var inputMonitor: SystemUiCornerInputMonitor? = null
    private var gestureMonitor: SystemUiGestureMonitor? = null

    fun install(classLoader: ClassLoader) {
        // SystemUI 侧不改写通知点击入口。
        //
        // onNotificationClicked / onStartFloatWindow 这一组入口在 ColorOS 17 上回调不稳定。
        // 改写后一旦命中且浮窗通道启动返回空值，原始点击事件即被丢弃，
        // 常驻通知与自动分组通知会表现为点击无响应，因此不在此处接管。
        // 通知小窗由 system_server 的 ActivityStarter 统一处理，本进程仅负责角落手势。
        try {
            val target =
                ColorOsSystemUiApplicationTarget.resolve { className ->
                    classLoader.loadClass(className)
                } ?: throw NoSuchMethodException("SystemUI application onCreate")
            val onCreate = target.onCreate
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        // 通知点击小窗：改 Bundle（含剥离 remote 转场），让窗口不被横幅压住。
                        NotifBundleHook(module, configuration).install(classLoader)
                        startInputMonitor(context)
                        startGestureMonitor(context)
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * 实验（1089）：在 SystemUI 里重建 gesture monitor，只做诊断计数，不驱动 UI。
     *
     * 目的：确认 pilferPointers() 之后，普通应用进程的通道还能不能持续收到 MOVE。
     * system_server 侧已实测"抢完 10ms 内就被 CANCEL，move=0"，这一版拿来对照。
     * 同时 system_server 侧暂时不抢流（shouldStartPilfer 恒 false），避免两处互抢。
     */
    private fun startGestureMonitor(context: Context) {
        try {
            val monitor = SystemUiGestureMonitor(context, module, configuration)
            gestureMonitor = monitor
            monitor.start()
            module.log(Log.INFO, TAG, "SUI_GESTURE_MONITOR_DIAGNOSTIC_ENABLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_LINKAGE_FAILED", exception)
        }
    }

    /**
     * SystemUI 侧的角区 SPY 窗口已停用。
     *
     * 那是两个真的接收触摸的 size×size 窗口（INPUT_FEATURE_SPY，通道名
     * FlymeFreeform-corner-input-Left/Right），覆盖在屏幕下角，pilfer 之后点击就被它吃掉。
     * 抢流与判定已全部移到 system_server 的 gesture monitor，这里不再需要任何窗口。
     */
    private fun startInputMonitor(context: Context) {
        try {
            module.log(Log.INFO, TAG, "SYSTEMUI_CORNER_SPY_DISABLED_NO_TOUCH_WINDOW")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_LINKAGE_FAILED", exception)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}
