package io.github.hoerire.free.bubble

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.hoerire.free.config.ModulePreferences

/**
 * 常亮建议：屏幕进入「即将变暗」的半暗阶段时，点一下弹一颗气泡，点气泡把屏幕钉住。
 *
 * 半暗状态由 system_server 侧写入 [ModulePreferences.KEY_PRE_SLEEP_ARMED]（见
 * `hook/PreSleepDetectionHook`），这里按固定间隔轮询该标志：
 *
 * 1. 半暗且屏幕仍可交互 → 铺一层透明点按层，接住这一下点按（同时唤醒屏幕）；
 * 2. 点中 → 弹「保持常亮」气泡，气泡带 FLAG_KEEP_SCREEN_ON 的窗口；
 * 3. 手动灭屏或切换应用 → 释放，回到系统默认休眠行为。
 *
 * 点按层只在半暗窗口期内存在；没有半暗信号时不铺任何窗口。
 */
internal class PreSleepKeepAwakeController(
    context: Context,
    private val host: Host,
) {
    interface Host {
        fun isEnabled(): Boolean

        fun foregroundPackage(): String?

        /** 弹一颗建议气泡；用户点它时执行 [engage]。 */
        fun showKeepAwakeSuggestion(engage: () -> Unit)

        fun dismissSuggestion(id: String)

        fun log(code: String, error: Throwable?)
    }

    private val appContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)
    private val powerManager: PowerManager? =
        context.getSystemService(PowerManager::class.java)
    private val keyguardManager: KeyguardManager? =
        context.getSystemService(KeyguardManager::class.java)

    /** 上一次因变暗弹出建议的时刻；同一轮变暗只弹一次。 */
    private var lastSuggestionAt = 0L
    private var keepWindow: View? = null
    private var keepAwake = false
    private var baselineForeground: String? = null
    private var lastForegroundCheck = 0L
    private var polling = false
    private var receiverRegistered = false

    private val pollAction = Runnable { poll() }

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                handler.post {
                    clearArmed()
                    release()
                }
            }
        }

    /** 设置变化时调用：总开关或常亮建议关掉就立即停手，不留半暗层。 */
    fun setEnabled(enabled: Boolean) {
        if (enabled == polling) return
        if (enabled) {
            registerReceiver()
            clearArmed()
            polling = true
            handler.post(pollAction)
            host.log("PRE_SLEEP_WATCH_STARTED", null)
        } else {
            polling = false
            handler.removeCallbacks(pollAction)
            release()
            clearArmed()
            host.log("PRE_SLEEP_WATCH_STOPPED", null)
        }
    }

    fun stop() = setEnabled(false)

    private fun poll() {
        if (!polling) return
        runCatching {
            val interactive = powerManager?.isInteractive ?: true
            if (!interactive) {
                release()
            } else if (keepAwake) {
                checkAppSwitch()
            } else if (isArmed() && !isLocked()) {
                onDimDetected()
            }
        }.onFailure { host.log("PRE_SLEEP_POLL_FAILED", it) }
        handler.postDelayed(pollAction, POLL_MS)
    }

    /**
     * 进入变暗阶段：先把屏幕点亮回去，再直接给一颗「保持常亮」气泡。
     *
     * 早先的实现是铺一层透明点按层、等用户点一下再弹气泡；实际使用里用户会等气泡自己出现，
     * 反而以为功能没生效。现在改为进入变暗就弹气泡：气泡本身开着不自动消失，
     * 用户点它才把屏幕钉住，不点就照常熄灭。
     */
    private fun onDimDetected() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSuggestionAt < SUGGESTION_COOLDOWN_MS) return
        lastSuggestionAt = now
        // 标志只用一次：清掉之后由系统在下一轮变暗时重新置位，避免同一轮反复触发。
        clearArmed()
        pokeUserActivity()
        host.log("PRE_SLEEP_DIM_DETECTED", null)
        if (!host.isEnabled()) return
        runCatching { host.showKeepAwakeSuggestion { engage() } }
            .onFailure { host.log("PRE_SLEEP_SUGGESTION_FAILED", it) }
    }

    private fun engage() {
        if (keepAwake) return
        runCatching {
            keepAwake = true
            baselineForeground = host.foregroundPackage()
            lastForegroundCheck = 0L
            pokeUserActivity()
            attachKeepWindow()
            // 气泡的使命已经完成：常亮由窗口维持，气泡留着只会挡视线。
            host.dismissSuggestion(ImmediateSuggestionController.TYPE_KEEP_AWAKE)
            host.log("PRE_SLEEP_KEEP_AWAKE_ENGAGED", null)
        }.onFailure {
            keepAwake = false
            host.log("PRE_SLEEP_KEEP_AWAKE_FAILED", it)
        }
    }

    /** 1×1 的不可触摸窗口：FLAG_KEEP_SCREEN_ON 靠窗口存在维持。 */
    private fun attachKeepWindow() {
        val manager = windowManager ?: return
        if (keepWindow != null) return
        runCatching {
            val view = View(appContext)
            val params =
                WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = -10
                    y = -10
                    title = KEEP_WINDOW_TITLE
                }
            manager.addView(view, params)
            keepWindow = view
        }.onFailure { host.log("PRE_SLEEP_KEEP_WINDOW_FAILED", it) }
    }

    private fun releaseKeepWindow() {
        val view = keepWindow ?: return
        keepWindow = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun release() {
        if (!keepAwake) return
        keepAwake = false
        baselineForeground = null
        releaseKeepWindow()
        host.dismissSuggestion(ImmediateSuggestionController.TYPE_KEEP_AWAKE)
        host.log("PRE_SLEEP_KEEP_AWAKE_RELEASED", null)
    }

    /** 只认「手动灭屏」与「切换应用」两种释放条件。 */
    private fun checkAppSwitch() {
        if (!keepAwake) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastForegroundCheck < APP_SWITCH_POLL_MS) return
        lastForegroundCheck = now
        val current = host.foregroundPackage()
        val baseline = baselineForeground
        if (baseline == null) {
            baselineForeground = current
            return
        }
        if (current != null && current != baseline) {
            host.log("PRE_SLEEP_FOREGROUND_SWITCHED_" + baseline + "_" + current, null)
            release()
        }
    }

    private fun isLocked(): Boolean =
        runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)

    /** 半暗阶段也算一次用户活动：不唤醒的话屏幕几步之后就真的睡了。 */
    private fun pokeUserActivity() {
        val power = powerManager ?: return
        runCatching {
            val method =
                PowerManager::class.java.getDeclaredMethod(
                    "userActivity",
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            method.isAccessible = true
            method.invoke(power, SystemClock.uptimeMillis(), USER_ACTIVITY_EVENT_TOUCH, 0)
        }.onFailure { host.log("PRE_SLEEP_POKE_FAILED", it) }
    }

    private fun isArmed(): Boolean =
        runCatching {
            Settings.Global.getInt(
                appContext.contentResolver,
                ModulePreferences.KEY_PRE_SLEEP_ARMED,
                0,
            ) == 1
        }.getOrDefault(false)

    private fun clearArmed() {
        runCatching {
            if (isArmed()) {
                Settings.Global.putInt(
                    appContext.contentResolver,
                    ModulePreferences.KEY_PRE_SLEEP_ARMED,
                    0,
                )
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            appContext.registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            receiverRegistered = false
            host.log("PRE_SLEEP_RECEIVER_FAILED", it)
        }
    }

    private companion object {
        const val POLL_MS = 250L
        const val APP_SWITCH_POLL_MS = 1_000L

        const val KEEP_WINDOW_TITLE = "FreeKeepScreenOn"

        /** 同一轮变暗重复触发的最小间隔。 */
        const val SUGGESTION_COOLDOWN_MS = 20_000L

        /** PowerManager.USER_ACTIVITY_EVENT_TOUCH */
        const val USER_ACTIVITY_EVENT_TOUCH = 2
    }
}
