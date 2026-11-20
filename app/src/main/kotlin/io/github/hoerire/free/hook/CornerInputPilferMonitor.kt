package io.github.hoerire.free.hook

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.InputChannel
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.gesture.CornerGestureEngine
import io.github.hoerire.free.gesture.CornerSide
import io.github.hoerire.free.gesture.CornerTriggerRegion

/**
 * 角区“抢流”监听者：用 InputMonitor 替代 SystemUI SPY 窗口方案。
 *
 * SPY 窗口方案在底角放两个 294×294 的可触摸窗口，靠 `pilferPointers` 抢流；
 * 这些窗口会盖住应用底部的按钮（语音、加号），而 `INPUT_FEATURE_SPY` 在 ColorOS 上
 * 也无法做到“只旁听不消费”，点按会被窗口吃掉。
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
    /** 这一条流从哪一侧角区起手；方向判定用它。 */
    private var side: CornerSide? = null

    /** start() 传入的 SystemUI Context：取它对的实际显示参数（不能用 Resources.getSystem()）。 */
    private var appContext: Context? = null
    private var lastSkipLogAt = 0L
    /** 本条手势的显示参数，DOWN 时取一次。 */
    private var gestureMetrics: DisplayMetrics? = null

    fun start(context: Context) {
        appContext = context.applicationContext ?: context
        environmentState.start(context)
        // 设置一变就换快照：关掉「启用交互」必须**立刻**停止抢流。
        // 只在 environmentState 变化（锁屏/横竖屏/游戏模式）时刷新，会让开关失效：
        // 哨兵仍拿着旧快照继续吞底角触摸。
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

    /** 诊断：这次 DOWN 为什么没被接管。限频，避免每次触摸刷一行。 */
    private fun skip(reason: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSkipLogAt < SKIP_LOG_INTERVAL_MS) return
        lastSkipLogAt = now
        module.log(Log.INFO, TAG, "CORNER_TRACK_SKIP_" + reason)
    }

    /**
     * 当前显示的尺寸与密度。
     *
     * 角区判定全部用 dp × density 换算，因此不能假定某一块屏幕的分辨率：
     *   - 优先取默认显示屏的真实尺寸（含状态栏/导航条）；
     *   - 取不到退回本进程资源，再退回系统资源。
     * `Resources.getSystem()` 是系统默认配置，与实际显示可能不一致（分辨率、密度都可能
     * 是另一套），单独拿它算半径会把 DOWN 判成"不在角区"，表现为抢不到流、选格时漏到下层。
     */
    private fun displayMetrics(): DisplayMetrics {
        val fallback =
            appContext?.resources?.displayMetrics ?: android.content.res.Resources.getSystem().displayMetrics
        val manager = appContext?.getSystemService(DisplayManager::class.java) ?: return fallback
        val display =
            runCatching { manager.getDisplay(Display.DEFAULT_DISPLAY) }.getOrNull()
                ?: return fallback
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        return runCatching { display.getRealMetrics(metrics); metrics }.getOrDefault(fallback)
    }

    private fun handle(event: MotionEvent) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        // 每条手势只在 DOWN 取一次显示参数：别让 getRealMetrics 跟着每个 MOVE 走。
        val metrics =
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                displayMetrics().also { gestureMetrics = it }
            } else {
                gestureMetrics ?: displayMetrics()
            }
        val radius = CornerTriggerRegion.radiusPx(settings.cornerTriggerRangeDp, metrics.density)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = false
                pilfered = false
                side = null
                // 关掉「启用交互」等于整套路手势关掉：连抢流都不做，事件原样留给系统。
                if (!settings.cornerInteractionActive || event.pointerCount != 1) {
                    skip("inactive")
                    return
                }
                if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) {
                    skip("tool")
                    return
                }
                val detected =
                    CornerTriggerRegion.detectSide(
                        x = event.rawX,
                        y = event.rawY,
                        displayWidth = metrics.widthPixels.toFloat(),
                        displayHeight = metrics.heightPixels.toFloat(),
                        radius = radius,
                        leftEnabled = settings.leftCornerEnabled,
                        rightEnabled = settings.rightCornerEnabled,
                    ) ?: run {
                        skip("region")
                        return
                    }
                if (!environmentState.isGestureAllowed(refreshKeyguard = true)) {
                    skip("env")
                    return
                }
                side = detected
                downX = event.rawX
                downY = event.rawY
                tracking = true
                module.log(
                    Log.INFO,
                    TAG,
                    "CORNER_TRACK_DOWN_" + detected.name + "_" +
                        event.rawX.toInt() + "_" + event.rawY.toInt() +
                        "_r" + radius.toInt() +
                        "_w" + metrics.widthPixels + "_h" + metrics.heightPixels,
                )
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
                // 纯垂直上滑是系统手势（回桌面 / 最近任务），不能抢：
                // 判据与 CornerGestureEngine 的 VERTICAL_UPWARD_RATIO 一致。
                val activeSide = side
                if (activeSide != null) {
                    val inward = if (activeSide == CornerSide.Left) dx else -dx
                    val upward = -dy
                    if (upward > inward * VERTICAL_UPWARD_RATIO) {
                        tracking = false
                        module.log(Log.INFO, TAG, "CORNER_PILFER_SKIP_VERTICAL")
                        return
                    }
                    // 几乎纯水平的内向拖动（视频进度条、横向滑动的列表）不是角区手势，
                    // 不能抢流；只让这一帧、不放弃整条手势，手指转成斜向时仍会正常抢。
                    // 比例与 CornerGestureEngine.MIN_UPWARD_RATIO 共用，避免两处分叉造成穿透。
                    if (upward < inward * CornerGestureEngine.MIN_UPWARD_RATIO) {
                        skip("horizontal")
                        return
                    }
                }
                val slop = PILFER_SLOP_DP * metrics.density
                if (dx * dx + dy * dy >= slop * slop) {
                    pilfered = true
                    val monitorRef = monitor ?: return
                    runCatching { pilferMethod?.invoke(monitorRef) }
                        .onSuccess {
                            module.log(Log.INFO, TAG, "CORNER_PILFER_OK_" + (side?.name ?: "?"))
                        }
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

        /**
         * 位移到这个量就抢流。
         *
         * 取 3dp 时仍有概率漏到下层应用：抢流要过一轮 InputDispatcher 才生效，
         * 手指快的时候这几帧已经让应用越过自己的 touchSlop（约 8dp）开始滚动。
         * 1.5dp 把这段窗口压掉一半；点按（几乎不位移）仍然放行。
         */
        const val PILFER_SLOP_DP = 1.5f

        /** 与 CornerGestureEngine 一致：向上位移超过向内位移这么多倍，判为系统上滑。 */
        const val VERTICAL_UPWARD_RATIO = 3f

        /** DOWN 被跳过的诊断日志限频。 */
        const val SKIP_LOG_INTERVAL_MS = 2_000L
    }
}
