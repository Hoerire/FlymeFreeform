package io.github.hoerire.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.InputChannel
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * system_server 进程内的 gesture monitor。
 *
 * 为什么必须在本进程：`pilferPointers()` 抢下的只是"这一个 monitor 通道"，事件继续投递给
 * 本进程。monitor 如果建在 SystemUI，抢完之后 MOVE 全落在另一个进程，system_server 里的
 * [ColorOsFreeformCoordinator] 只收到 DOWN，`gestureEngine` 停在 Armed，扇形永远不激活
 * （实机表现就是"经常拉不出来"）。
 *
 * 硬性约束（见 CONSTRAINTS.md 第 7、8 条）：
 * 1. 每个事件分支都必须 finishInputEvent，否则这条通道会被判 not responsive；
 * 2. 建立失败必须把系统侧通道 dispose 掉，绝不留一条没人读的活通道；
 * 3. 任何异常都不许往外抛，system_server 里崩一次代价太大。
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
internal class SystemServerGestureMonitor(
    private val context: Context,
    private val onEvent: (MotionEvent) -> Boolean,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private val inputManager: InputManager? =
        runCatching { context.getSystemService(InputManager::class.java) }.getOrNull()

    private val monitorGestureInput: Method? =
        runCatching {
            InputManager::class.java
                .getMethod("monitorGestureInput", String::class.java, Integer.TYPE)
                .apply { isAccessible = true }
        }.getOrNull()

    private val getInputChannel: Method? =
        runCatching {
            INPUT_MONITOR_CLASS.getMethod("getInputChannel").apply { isAccessible = true }
        }.getOrNull()

    private val pilferPointers: Method? =
        runCatching {
            INPUT_MONITOR_CLASS.getMethod("pilferPointers").apply { isAccessible = true }
        }.getOrNull()

    private var thread: HandlerThread? = null
    private var monitor: Any? = null
    private var receiver: InputEventReceiver? = null
    private var pilferCount = 0L
    private var pilferActive = false
    private var downCount = 0L
    private var moveCount = 0L

    /** 建 monitor；返回 false 表示没建起来，调用方自行回退。 */
    fun start(name: String): Boolean {
        if (receiver != null) return true
        val manager = inputManager ?: return fail("MONITOR_INPUT_MANAGER_MISSING")
        val monitorMethod = monitorGestureInput ?: return fail("MONITOR_API_MISSING")
        val channelMethod = getInputChannel ?: return fail("MONITOR_CHANNEL_API_MISSING")
        val pilferMethod = pilferPointers ?: return fail("MONITOR_PILFER_API_MISSING")

        var worker: HandlerThread? = null
        return try {
            val started = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
            worker = started
            val created = monitorMethod.invoke(manager, name, Display.DEFAULT_DISPLAY)
            val rawChannel = channelMethod.invoke(created)
            val channel = rawChannel as? InputChannel
            if (channel == null) {
                releaseQuietly(rawChannel)
                started.quitSafely()
                fail("MONITOR_CHANNEL_MISSING")
            } else {
                val createdReceiver =
                    object : InputEventReceiver(channel, started.looper) {
                        override fun onInputEvent(event: InputEvent) {
                            var handled = false
                            try {
                                val motion = event as? MotionEvent
                                if (motion != null) handled = dispatch(motion, pilferMethod)
                            } catch (error: RuntimeException) {
                                logger(Log.WARN, MONITOR_TAG, error)
                            } catch (error: LinkageError) {
                                logger(Log.WARN, MONITOR_TAG, error)
                            } finally {
                                try {
                                    finishInputEvent(event, handled)
                                } catch (_: IllegalArgumentException) {
                                    // 通道已释放。
                                }
                            }
                        }
                    }
                thread = worker
                monitor = created
                receiver = createdReceiver
                logger(Log.INFO, "MONITOR_READY_" + name, null)
                true
            }
        } catch (error: Throwable) {
            runCatching { worker?.quitSafely() }
            thread = null
            monitor = null
            receiver = null
            logger(Log.WARN, MONITOR_TAG, error)
            false
        }
    }

    fun stop() {
        val current = receiver
        receiver = null
        monitor = null
        if (current != null) {
            runCatching { current.javaClass.getMethod("dispose").invoke(current) }
        }
        val worker = thread
        thread = null
        pilferActive = false
        runCatching { worker?.quitSafely() }
    }

    /**
     * 交给协调器处理，并在"确认是角落斜推"的那一帧把流抢过来。
     *
     * 抢流不可逆：DOWN 就抢会把普通点击一起吞掉（1081 的毛病）。所以只有协调器判定
     * 引擎已经 Activate 的那一帧才抢，抬手/取消时复位。
     */
    private fun dispatch(event: MotionEvent, pilferMethod: Method): Boolean {
        // 诊断：只记数量，不改行为。用来判断 pilfer 之后 monitor 到底还收不收得到 MOVE。
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCount++
                logger(Log.INFO, "MONITOR_DOWN_" + downCount, null)
            }
            MotionEvent.ACTION_MOVE -> {
                moveCount++
                if (moveCount % MOVE_TRACE_INTERVAL == 1L) {
                    logger(Log.INFO, "MONITOR_MOVE_ALIVE_" + moveCount, null)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                logger(
                    Log.INFO,
                    "MONITOR_SESSION_END_" + (if (pilferActive) "PILFERED" else "PASSIVE") +
                        "_down=" + downCount + "_move=" + moveCount,
                    null,
                )
            }
        }
        val wantsPilfer = onEvent(event)
        if (wantsPilfer && !pilferActive) {
            val instance = monitor ?: return false
            try {
                pilferMethod.invoke(instance)
                pilferActive = true
                pilferCount++
                logger(Log.INFO, "MONITOR_PILFER_OK_count=" + pilferCount, null)
            } catch (error: ReflectiveOperationException) {
                logger(Log.WARN, MONITOR_TAG, error)
                return false
            } catch (error: RuntimeException) {
                logger(Log.WARN, MONITOR_TAG, error)
                return false
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            pilferActive = false
        }
        return pilferActive
    }

    private fun releaseQuietly(channel: Any?) {
        if (channel == null) return
        runCatching { channel.javaClass.getMethod("dispose").invoke(channel) }
    }

    private fun fail(code: String): Boolean {
        logger(Log.WARN, code, null)
        return false
    }

    private companion object {
        const val MONITOR_TAG = "FlymeFreeform"
        const val THREAD_NAME = "FlymeFreeform-corner-system"
        const val MOVE_TRACE_INTERVAL = 30L
        val INPUT_MONITOR_CLASS: Class<*> = Class.forName("android.view.InputMonitor")
    }
}
