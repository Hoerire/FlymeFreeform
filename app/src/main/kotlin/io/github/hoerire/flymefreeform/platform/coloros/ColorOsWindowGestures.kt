package io.github.hoerire.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.view.MotionEvent
import android.view.ViewConfiguration
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration
import io.github.hoerire.flymefreeform.log.ModuleLog
import java.lang.reflect.Method
import io.github.hoerire.flymefreeform.hook.OemReflection

/**
 * 长按浮动窗口静音/恢复（小窗、迷你窗都适用）。
 *
 * 命中条件：按住位置落在当前浮动任务（`getTopZoomTask()`）的可见边界内，持续超过系统
 * 长按阈值、位移不超过触摸 slop。
 *
 * 静音只作用于该窗口所属的应用：走 AudioService 的
 * `PlaybackActivityMonitor.mPamExt.setVolumeForUid(volume, uid, pkg, true)`，
 * 与 ColorOS 自己的单应用音量控制同一条路径，不会把媒体流整体静音、不影响别的应用。
 */
@SuppressLint("PrivateApi")
/** 放大前保存的原始可见区域与缩放。 */
internal class ColorOsWindowGestures(
    private val context: Context,
    private val controller: Any,
    private val configuration: ProcessConfiguration,
    private val handler: Handler,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var pressedTask: Any? = null
    private var armed = false
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    private val fire =
        Runnable {
            if (!armed) return@Runnable
            armed = false
            fired = true
            val task = pressedTask ?: return@Runnable
            runCatching { toggleMute(task) }
                .onFailure { ModuleLog.warn("WINDOW_MUTE_FAILED", it) }
        }

    /** 返回 true 表示这一下已经触发过静音，交给上层决定是否消费。 */
    fun onEvent(event: MotionEvent): Boolean {
        if (!configuration.snapshot.longPressMuteEffective) {
            cancel()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                fired = false
                downX = event.x
                downY = event.y
                val task = floatingTaskAt(event.x, event.y) ?: return false
                pressedTask = task
                armed = true
                handler.postDelayed(fire, longPressTimeout)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!armed) return fired
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > SLOP_PX * SLOP_PX) cancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasFired = fired
                cancel()
                fired = false
                return wasFired
            }
        }
        return false
    }

    private fun cancel() {
        armed = false
        handler.removeCallbacks(fire)
    }

    private fun taskIdOf(task: Any): Int {
        var type: Class<*>? = task.javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField("mTaskId") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return (runCatching { field.get(task) }.getOrNull() as? Int) ?: -1
            }
            type = type.superclass
        }
        return -1
    }

    /**
     * 命中的浮动任务：位置落在可见区域内即可。
     *
     * 小窗与迷你窗走同一判定：长按窗口内任意位置即静音该窗口所属应用，不弹菜单。
     */
    private fun floatingTaskAt(x: Float, y: Float): Any? {
        val task = OemReflection.call(controller, "getTopZoomTask") ?: return null
        val bounds = OemReflection.call(task, "getBounds") as? Rect ?: return null
        return if (bounds.contains(x.toInt(), y.toInt())) task else null
    }

    fun toggleMute(task: Any?) {
        val target = task ?: return
        val pkg = OemReflection.call(target, "getBasePackageName") as? String ?: return
        val uid =
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
                ?: return
        val restoring = mutedUids.contains(uid)
        if (!setVolumeForUid(if (restoring) 1f else 0f, uid, pkg)) {
            ModuleLog.warn("MINI_LONG_PRESS_MUTE_UNAVAILABLE_" + pkg)
            return
        }
        if (restoring) mutedUids.remove(uid) else mutedUids.add(uid)
        ModuleLog.info("MINI_LONG_PRESS_" + (if (restoring) "unmute" else "mute") + "_" + pkg)
    }

    /**
     * 只改这一个 uid 的播放音量：AudioService.mPlaybackMonitor.mPamExt.setVolumeForUid(...)。
     * 依次尝试 ext 与 monitor 本身，接口不存在就返回 false（由调用方记 WARN）。
     */
    private fun setVolumeForUid(volume: Float, uid: Int, pkg: String): Boolean {
        val audioService = audioService() ?: return false
        val monitor = OemReflection.readField(audioService, "mPlaybackMonitor") ?: return false
        val ext = OemReflection.readField(monitor, "mPamExt")
        for (target in listOfNotNull(ext, monitor)) {
            var type: Class<*>? = target.javaClass
            while (type != null) {
                val method =
                    runCatching {
                        type!!.declaredMethods.firstOrNull {
                            it.name == "setVolumeForUid" && it.parameterCount == 4
                        }
                    }.getOrNull()
                if (method != null) {
                    method.isAccessible = true
                    return runCatching { method.invoke(target, volume, uid, pkg, true) }.isSuccess
                }
                type = type.superclass
            }
        }
        return false
    }

    private fun audioService(): Any? {
        audioServiceCache?.let { return it }
        val binder =
            runCatching {
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "audio") as? android.os.IBinder
            }.getOrNull() ?: return null
        audioServiceCache = binder
        return binder
    }

    private var audioServiceCache: Any? = null

    private companion object {
        /** 位移超过这个像素数就不再算长按。 */
        const val SLOP_PX = 40f

        /** 已被本功能静音的 uid（内存态；重启即清空）。 */
        val mutedUids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    }
}
