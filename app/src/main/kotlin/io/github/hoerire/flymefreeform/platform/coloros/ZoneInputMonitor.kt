package io.github.hoerire.flymefreeform.platform.coloros

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration
import io.github.hoerire.flymefreeform.gesture.CornerTriggerRegion

/**
 * 在 system_server 侧注册自己的 gesture monitor，落点落在触发区内就抢走指针流。
 *
 * ColorOS 17 上桌面与 SystemUI 都没有可用的输入入口（已逐一验证），但 system_server 是输入
 * 分发所在进程：这里用 InputManager#monitorGestureInput 注册的 monitor 比应用侧更靠前，
 * pilferPointers() 之后系统的 swipe-up 收不到后续事件，多任务不会被拉起。
 */
internal class ZoneInputMonitor(
    private val configuration: ProcessConfiguration,
    private val report: (Int, String, Throwable?) -> Unit,
) {
    private var monitor: InputMonitor? = null
    private var inputThread: android.os.HandlerThread? = null
    private var receiver: InputEventReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        runCatching {
            val managerClass = Class.forName("android.hardware.input.InputManager")
            val instance = managerClass.getMethod("getInstance").invoke(null)
            val method = managerClass.getMethod(
                "monitorGestureInput",
                String::class.java,
                Int::class.javaPrimitiveType,
            )
            val created = method.invoke(instance, MONITOR_NAME, 0) as? InputMonitor
                ?: error("monitor is null")
            monitor = created
            val channel = created.getInputChannel() ?: error("no input channel")
            // 独立线程：主线程在开机/切换瞬间会被占住，挂在主 Looper 上的 receiver 会被
            // InputDispatcher 判为 not responsive（实测日志）。
            val thread = android.os.HandlerThread("FlymeFreeform-zone-input").apply { start() }
            inputThread = thread
            receiver = object : InputEventReceiver(channel, thread.looper) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        handle(event)
                    } finally {
                        runCatching { finishInputEvent(event, false) }
                    }
                }
            }
            report(Log.INFO, "SYSTEM_ZONE_MONITOR_READY", null)
        }.onFailure { report(Log.WARN, "SYSTEM_ZONE_MONITOR_FAILED", it) }
    }

    private fun handle(event: InputEvent) {
        val motion = event as? MotionEvent ?: return
        if (motion.actionMasked != MotionEvent.ACTION_DOWN) return
        if (!configuration.snapshot.enabled) return
        val settings = configuration.snapshot
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val hit = CornerTriggerRegion.detectSide(
            x = motion.rawX,
            y = motion.rawY,
            displayWidth = metrics.widthPixels.toFloat(),
            displayHeight = metrics.heightPixels.toFloat(),
            radius = settings.cornerTriggerRangeDp * metrics.density,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
        if (!hit) return
        val ok = runCatching { monitor?.pilferPointers() }.isSuccess
        report(if (ok) Log.INFO else Log.WARN, if (ok) "SYSTEM_ZONE_PILFER_OK" else "SYSTEM_ZONE_PILFER_FAILED", null)
    }

    fun stop() {
        runCatching { receiver?.dispose() }
        runCatching { monitor?.dispose() }
        receiver = null
        monitor = null
        runCatching { inputThread?.quitSafely() }
        inputThread = null
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val MONITOR_NAME = "FlymeFreeform-zone"
    }
}