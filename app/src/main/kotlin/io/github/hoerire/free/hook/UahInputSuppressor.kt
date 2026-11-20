package io.github.hoerire.free.hook

import android.util.Log
import io.github.hoerire.free.log.VerboseLog
import android.view.InputDevice
import android.view.MotionEvent
import io.github.hoerire.free.gesture.CornerTriggerRegion
import io.github.hoerire.free.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 在 system_server 的 UAH 输入通道上拦截底角触摸。
 *
 * ColorOS 17.5.4 起，桌面侧的 OplusBaseTouchInteractionService 被移除，手势判定搬到：
 * com.android.server.oplus.uah.util.OplusUAwareInputHelper$UahExInputEventReceiver
 * → dealWithInputEvent(InputEvent)。这个类在 16 上也存在，一套实现可覆盖两版。
 * 落点在触发区内时跳过原方法，UAH 收不到这次触摸，系统的上滑/返回就不会启动。
 */
internal class UahInputSuppressor(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    @Volatile private var suppressing = false
    @Volatile private var lastProbeAt = 0L

    fun install(loader: ClassLoader) {
        val type = runCatching { loader.loadClass(RECEIVER_CLASS) }.getOrNull()
        if (type == null) {
            ModuleLog.warn("UAH_RECEIVER_UNAVAILABLE")
            return
        }
        // dealWithInputEvent 与 onInputEvent 都钩：前者可能是内部转发，后者才是接收器回调。
        val targets = type.declaredMethods.filter { candidate ->
            candidate.name in METHOD_NAMES &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].name == "android.view.InputEvent"
        }
        if (targets.isEmpty()) {
            ModuleLog.warn("UAH_METHOD_UNAVAILABLE")
            return
        }
        targets.forEach { target ->
        val method = target
        method.isAccessible = true
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("free.system.uah_input")
            .intercept { chain ->
                val event = chain.args.firstNotNullOfOrNull { it as? MotionEvent }
                if (event != null) {
                    // 探针：确认这个接收器在真机上到底有没有收到事件（限流，避免刷屏）。
                    if (VerboseLog.enabled) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastProbeAt > PROBE_INTERVAL_MS) {
                            lastProbeAt = now
                            io.github.hoerire.free.log.ModuleLog.info("UAH_EVENT_" + event.actionMasked, null)
                        }
                    }
                }
                if (event == null) {
                    suppressing = false
                    return@intercept chain.proceed()
                }
                if (!configuration.snapshot.enabled || !environment.isGestureAllowed()) {
                    suppressing = false
                    return@intercept chain.proceed()
                }
                // 吞掉事件时必须自己把事件结算掉：这个接收器是输入监控通道，
                // 跳过 onInputEvent 就等于永不 finishInputEvent，系统等 5 秒后判
                // 「OplusUAHInputChannel is not responding」，整条输入流水线随之卡死
                // （触发条件：按下点落在底角触发区与 home 手势条的交叠处）。
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!insideTrigger(event)) {
                            suppressing = false
                            return@intercept chain.proceed()
                        }
                        suppressing = true
                        if (VerboseLog.enabled) {
                            io.github.hoerire.free.log.ModuleLog.info("UAH_SUPPRESS_DOWN")
                        }
                        settle(method.name, chain.thisObject, event)
                        null
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        val holding = suppressing
                        suppressing = false
                        if (holding) {
                            settle(method.name, chain.thisObject, event)
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    else -> {
                        if (suppressing) {
                            settle(method.name, chain.thisObject, event)
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
        io.github.hoerire.free.log.ModuleLog.info("UAH_INPUT_HOOK_INSTALLED_" + method.name, null)
        }
    }

    /**
     * 只在外层接收器回调里结算一次。
     *
     * 同一批事件会同时经过 dealWithInputEvent 与 onInputEvent，两层都结算会二次 finish
     * 抛异常；这里按方法名过滤，只让接收器回调负责。
     */
    private fun settle(methodName: String, receiver: Any?, event: MotionEvent) {
        if (methodName != "onInputEvent" || receiver == null) return
        val method =
            runCatching {
                var type: Class<*>? = receiver.javaClass
                while (type != null) {
                    type.declaredMethods.firstOrNull {
                        it.name == "finishInputEvent" &&
                            it.parameterCount == 2 &&
                            it.parameterTypes[0].name == "android.view.InputEvent"
                    }?.let { return@runCatching it.apply { isAccessible = true } }
                    type = type.superclass
                }
                null
            }.getOrNull() ?: return
        runCatching { method.invoke(receiver, event, true) }
    }

    private fun insideTrigger(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return false
        if (event.pointerCount != 1) return false
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
        val settings = configuration.snapshot
        // 「启用交互」关掉时不再吞角区触摸，直接放行给下层。
        if (!settings.cornerInteractionActive) return false
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return false
        return CornerTriggerRegion.detectSide(
            x = event.rawX,
            y = event.rawY,
            displayWidth = width,
            displayHeight = height,
            radius = settings.cornerTriggerRangeDp * metrics.density,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ) != null
    }

    private companion object {
        const val TAG = "Free"
        const val RECEIVER_CLASS =
            "com.android.server.oplus.uah.util.OplusUAwareInputHelper\$UahExInputEventReceiver"
        val METHOD_NAMES = setOf("dealWithInputEvent", "onInputEvent")
        const val PROBE_INTERVAL_MS = 3000L
    }
}