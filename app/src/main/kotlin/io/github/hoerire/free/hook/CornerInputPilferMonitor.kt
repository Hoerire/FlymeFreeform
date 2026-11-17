package io.github.hoerire.free.hook

import android.content.Context
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.InputChannel
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.gesture.CornerTriggerRegion

/**
 * 角区“抢流”监听者：用 InputMonitor 取代原来的 SystemUI SPY 窗口。
 *
 * 原来的做法是在底角放两个 294×294 的可触摸窗口，靠 `pilferPointers` 抢流。
 * 窗口会盖住应用底部的按钮（语音、加号），在这台 ColorOS 上 `INPUT_FEATURE_SPY`
 * 也没有做到“只旁听不消费”，点按会被窗口吃掉。
 *
 * InputMonitor 不需要窗口：它收到所有触摸的副本、不消费事件，点击照常落到应用；
 * 只在位移超过 [PILFER_SLOP_DP]（确认是滑动）时调 `pilferPointers`，把流抢过来，
 * 从而保留“滑动不穿透到下层应用”的能力。
 *
 * 需要 `android.permission.MONITOR_INPUT`（SystemUI 已授予）。
 */
internal class CornerInputPilferMonitor(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }

    @Volatile private var settings = ModuleSettingsSnapshot(enabled = false)
    private var monitor: Any? = null
    private var pilferMethod: java.lang.reflect.Method? = null
    private var receiver: InputEventReceiver? = null
    private var thread: HandlerThread? = null

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var pilfered = false

    fun start(context: Context) {
        environmentState.start(context)
        // 设置一变就换快照：关掉「启用交互」必须**立刻**停止抢流。
        // 之前只在 environmentState 变化（锁屏/横竖屏/游戏模式）时才刷新，
        // 结果开关关了、哨兵还拿着旧快照继续吞底角触摸。
        settings = configuration.snapshot
        configuration.observe { next -> settings = next }

        val global =
            runCatching {
                Class.forName("android.hardware.input.InputManagerGlobal")
                    .getMethod("getInstance")
                    .invoke(null)
            }.getOrNull() ?: throw IllegalStateException("InputManagerGlobal unavailable")

        val errors = StringBuilder()
        fun trace(label: String, t: Throwable?) { errors.append(label).append("=").append(t?.toString()?.take(120)).append("; ") }
        // API 37 上 InputManagerGlobal 只有 monitorGestureInput(String, int)。
        val two = runCatching {
            global.javaClass
                .getMethod("monitorGestureInput", String::class.java, Int::class.javaPrimitiveType)
                .invoke(global, MONITOR_NAME, Display.DEFAULT_DISPLAY)
        }.onFailure { trace("two", it) }.getOrNull()
        val one = two ?: runCatching {
            global.javaClass
                .getMethod("monitorInput", String::class.java)
                .invoke(global, MONITOR_NAME)
        }.onFailure { trace("one", it) }.getOrNull()
        val created = one ?: throw IllegalStateException("monitorInput failed: " + errors + " methods=" +
            runCatching { global.javaClass.declaredMethods.filter { it.name.contains("monitor") }.joinToString { it.name + "/" + it.parameterTypes.size } }.getOrDefault("?"))

        monitor = created
        val channel =
            created.javaClass.getMethod("getInputChannel").invoke(created) as InputChannel
        pilferMethod = created.javaClass.getMethod("pilferPointers").apply { isAccessible = true }

        val looperThread = HandlerThread("Free-corner-monitor").also { it.start() }
        thread = looperThread
        receiver =
            object : InputEventReceiver(channel, looperThread.looper) {
                override fun onInputEvent(event: InputEvent) {
                    try {
                        (event as? MotionEvent)?.let(::handle)
                    } catch (exception: Throwable) {
                        module.log(Log.WARN, TAG, "CORNER_MONITOR_EVENT_FAILED", exception)
                    } finally {
                        finishInputEvent(event, false)
                    }
                }
            }
        module.log(Log.INFO, TAG, "CORNER_MONITOR_STARTED")
    }

    private fun handle(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val radius = CornerTriggerRegion.radiusPx(settings.cornerTriggerRangeDp, metrics.density)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                pilfered = false
                // 关掉「启用交互」等于整套路手势关掉：连抢流都不做，事件原样留给系统。
                if (!settings.cornerInteractionActive || event.pointerCount != 1) return
                if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
                val side =
                    CornerTriggerRegion.detectSide(
                        x = event.rawX,
                        y = event.rawY,
                        displayWidth = metrics.widthPixels.toFloat(),
                        displayHeight = metrics.heightPixels.toFloat(),
                        radius = radius,
                        leftEnabled = settings.leftCornerEnabled,
                        rightEnabled = settings.rightCornerEnabled,
                    ) ?: return
                if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return
                downX = event.rawX
                downY = event.rawY
                tracking = true
            }

            MotionEvent.ACTION_MOVE -> {
                // 手势进行中被关掉「启用交互」也要立刻停手，不能再抢一次。
                if (!settings.cornerInteractionActive) {
                    tracking = false
                    pilfered = false
                    return
                }
                if (!tracking || pilfered) return
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val slop = PILFER_SLOP_DP * metrics.density
                if (dx * dx + dy * dy >= slop * slop) {
                    pilfered = true
                    val monitorRef = monitor ?: return
                    runCatching { pilferMethod?.invoke(monitorRef) }
                        .onFailure {
                            module.log(Log.WARN, TAG, "CORNER_MONITOR_PILFER_FAILED", it)
                        }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                pilfered = false
            }
        }
    }

    private companion object {
        const val TAG = "Free"
        const val MONITOR_NAME = "Free-corner-pilfer"

        /** 与原先窗口方案一致：位移到 3dp 就抢流，点按（更小抖动）一律放行。 */
        const val PILFER_SLOP_DP = 3f
    }
}
