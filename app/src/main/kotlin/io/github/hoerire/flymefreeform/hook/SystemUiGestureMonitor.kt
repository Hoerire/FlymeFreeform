package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot
import io.github.hoerire.flymefreeform.gesture.CornerTriggerRegion
import java.lang.reflect.Method

/**
 * SystemUI 侧的手势监视器。
 *
 * 与系统自带的 swipe-up / edge-swipe 同级：通过 InputManager.monitorGestureInput 注册一条
 * Gesture Monitor，拿到全屏事件流的副本；角落按下当帧调用 InputMonitor.pilferPointers()
 * 把这条流独占过来，InputDispatcher 会给其它所有通道（系统上滑、UAH、应用窗口）发
 * ACTION_CANCEL 并停止投递，于是上滑在热区内根本不会发生。
 *
 * 两条硬性约束：
 * 1. 每个事件分支都必须 finishInputEvent，否则该通道会被判 not responsive 而停投；
 * 2. pilfer 只在角落 DOWN 做一次，中途做会让扇形拿不到后续 MOVE。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class SystemUiGestureMonitor(
    private val context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val inputManager =
        context.getSystemService(InputManager::class.java)
            ?: error("InputManager unavailable")

    private val monitorGestureInput: Method =
        InputManager::class.java
            .getMethod("monitorGestureInput", String::class.java, Integer.TYPE)
            .apply { isAccessible = true }

    private val getInputChannel: Method =
        INPUT_MONITOR_CLASS.getMethod("getInputChannel").apply { isAccessible = true }

    private val pilferPointers: Method =
        INPUT_MONITOR_CLASS.getMethod("pilferPointers").apply { isAccessible = true }

    @Volatile private var settings = ModuleSettingsSnapshot(enabled = false)
    @Volatile private var streamSeen = false

    private var thread: HandlerThread? = null
    private var monitor: Any? = null
    private var receiver: InputEventReceiver? = null
    private var lastPilferFailureAt = -PILFER_LOG_INTERVAL_MS
    private var pilferCount = 0L

    fun start() {
        settings = configuration.snapshot
        configuration.observe { snapshot -> settings = snapshot }
        try {
            install()
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_API_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_START_FAILED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_LINKAGE_FAILED", exception)
        }
    }

    private fun install() {
        val displayId = resolveDisplayId()
        val worker =
            HandlerThread("FlymeFreeform-corner-monitor", Process.THREAD_PRIORITY_DISPLAY).apply {
                start()
            }
        val created =
            monitorGestureInput.invoke(
                inputManager,
                MONITOR_NAME + ":" + context.packageName,
                displayId,
            )
        val channel = getInputChannel.invoke(created) as? InputChannel
        if (channel == null) {
            worker.quitSafely()
            module.log(Log.WARN, TAG, "CORNER_MONITOR_CHANNEL_MISSING")
            return
        }
        thread = worker
        monitor = created
        receiver =
            object : InputEventReceiver(channel, worker.looper) {
                override fun onInputEvent(event: InputEvent) {
                    dispatch(event)
                }
            }
        module.log(Log.INFO, TAG, "CORNER_MONITOR_READY_display=" + displayId)
    }

    /** 监视器通道的回调；无论走哪条分支都必须 finishInputEvent。 */
    private fun dispatch(event: InputEvent) {
        var handled = false
        try {
            handled = handle(event)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "CORNER_MONITOR_EVENT_FAILED", exception)
        } finally {
            try {
                receiver?.finishInputEvent(event, handled)
            } catch (_: IllegalArgumentException) {
                // 通道已释放；忽略。
            }
        }
    }

    private fun handle(event: InputEvent): Boolean {
        val motion = event as? MotionEvent ?: return false
        if (motion.actionMasked != MotionEvent.ACTION_DOWN) return true
        if (!streamSeen) {
            streamSeen = true
            module.log(Log.INFO, TAG, "CORNER_MONITOR_STREAM_ALIVE")
        }
        return claimCorner(motion)
    }

    /** 角落按下当帧抢流；返回 true 表示这条流归我们。 */
    private fun claimCorner(event: MotionEvent): Boolean {
        val snapshot = settings
        if (!snapshot.enabled) return false
        if (event.pointerCount != 1) return false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false

        val rawX = event.rawX
        val rawY = event.rawY
        val metrics = context.resources.displayMetrics
        val side =
            CornerTriggerRegion.detectSide(
                x = rawX,
                y = rawY,
                displayWidth = metrics.widthPixels.toFloat(),
                displayHeight = metrics.heightPixels.toFloat(),
                radius = CornerTriggerRegion.radiusPx(snapshot.cornerTriggerRangeDp, metrics.density),
                leftEnabled = snapshot.leftCornerEnabled,
                rightEnabled = snapshot.rightCornerEnabled,
            ) ?: return false

        val monitorInstance = monitor ?: return false
        val eventAgeMs = SystemClock.uptimeMillis() - event.eventTime
        val startedAt = SystemClock.uptimeMillis()
        return try {
            pilferPointers.invoke(monitorInstance)
            pilferCount++
            module.log(
                Log.INFO,
                TAG,
                "CORNER_MONITOR_PILFER_OK_side=" + side.name +
                    "_raw=" + rawX.toInt() + "," + rawY.toInt() +
                    "_eventAgeMs=" + eventAgeMs +
                    "_pilferMs=" + (SystemClock.uptimeMillis() - startedAt) +
                    "_count=" + pilferCount,
            )
            true
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
            false
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
            false
        }
    }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        module.log(Log.WARN, TAG, "CORNER_MONITOR_PILFER_FAILED", exception)
    }

    private fun resolveDisplayId(): Int =
        try {
            context.display?.displayId ?: Display.DEFAULT_DISPLAY
        } catch (_: RuntimeException) {
            Display.DEFAULT_DISPLAY
        }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val MONITOR_NAME = "flymefreeform-corner"
        const val PILFER_LOG_INTERVAL_MS = 10_000L
        val INPUT_MONITOR_CLASS: Class<*> = Class.forName("android.view.InputMonitor")
    }
}
