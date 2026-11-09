package io.github.hoerire.flymefreeform.hook

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import java.util.concurrent.CopyOnWriteArraySet

/** 进程内共享的运行环境；设置与显示尺寸只在启动和系统通知时读取。 */
internal class ModuleEnvironmentState(
    private val configuration: ProcessConfiguration,
    private val onFailure: (String, RuntimeException) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private var context: Context? = null
    private var displays: DisplayManager? = null
    private var keyguard: KeyguardManager? = null
    private var watchingSettings = false
    private var watchingDisplay = false
    private var watchingLock = false

    @Volatile private var monitoring = false
    @Volatile private var displayAvailable = false
    @Volatile private var portrait = false
    @Volatile private var gameModeActive = true
    @Volatile private var gestureNavigation = false
    @Volatile private var keyguardLocked = true
    @Volatile private var foregroundPackage: String? = null

    /** 当前前台 Activity 的类名，用于识别多任务 / 最近任务界面。 */
    @Volatile private var foregroundActivity: String? = null
    @Volatile private var keyguardRecheckNeeded = true
    private var lastObservation: Observation? = null
    private var lastFailureAt = -10_000L

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshSettings()
            notifyIfChanged()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateDisplay(displayId)
        override fun onDisplayChanged(displayId: Int) = updateDisplay(displayId)
        override fun onDisplayRemoved(displayId: Int) = updateDisplay(displayId)
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    keyguardLocked = true
                    keyguardRecheckNeeded = true
                }
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_USER_UNLOCKED -> refreshKeyguardState()
                else -> return
            }
            notifyIfChanged()
        }
    }

    /** 长期 Hook 所有者与进程同寿命；Service 所有者必须在销毁时调用 close。 */
    fun start(owner: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (context != null) return
        val appContext = owner.applicationContext ?: owner
        context = appContext
        try {
            displays = appContext.getSystemService(DisplayManager::class.java)
            keyguard = appContext.getSystemService(KeyguardManager::class.java)
            val displayManager = displays ?: throw IllegalStateException("DisplayManager unavailable")
            appContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(GAME_MODE_KEY), false, settingsObserver,
            )
            watchingSettings = true
            appContext.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(NAVIGATION_MODE_KEY), false, settingsObserver,
            )
            displayManager.registerDisplayListener(displayListener, handler)
            watchingDisplay = true
            appContext.registerReceiver(lockReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_USER_UNLOCKED)
            // USER_PRESENT 由独立 UID 的 SystemUI 发送；非导出接收器会漏掉解锁恢复。
            // 这里只接收上述受系统保护的 action，并重新查询 KeyguardManager，不信任广播载荷。
            }, Context.RECEIVER_EXPORTED)
            watchingLock = true
            refreshSettings()
            refreshDisplay()
            refreshKeyguardState()
            monitoring = true
        } catch (exception: RuntimeException) {
            releaseObservers()
            reportFailure("MODULE_ENVIRONMENT_MONITOR_FAILED", exception)
        }
        notifyIfChanged()
    }

    fun observe(observer: () -> Unit) { observers += observer }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        monitoring = false
        releaseObservers()
        observers.clear()
        handler.removeCallbacksAndMessages(null)
        context = null
        displays = null
        keyguard = null
    }

    fun isModuleAllowed(): Boolean {
        val settings = configuration.snapshot
        return monitoring && displayAvailable && configuration.isAvailable && settings.enabled &&
            !settings.isPausedByEnvironment(landscape = !portrait, gameMode = gameModeActive)
    }

    /**
     * 手势被拦截的具体原因，供详细日志使用；返回 null 表示允许。
     *
     * 与 [isGestureAllowed] 判定顺序一致，只是把布尔结果拆成可读原因：
     * MODULE_OFF / ENV_UNAVAILABLE / NO_DISPLAY / GESTURE_NAV_OFF / KEYGUARD /
     * PAUSED_LANDSCAPE / PAUSED_GAME_MODE。
     */
    fun gestureBlockReason(refreshKeyguard: Boolean = false): String? {
        if (refreshKeyguard && keyguardRecheckNeeded) refreshKeyguardState()
        val settings = configuration.snapshot
        return when {
            !settings.enabled -> "MODULE_OFF"
            !monitoring || !configuration.isAvailable -> "ENV_UNAVAILABLE"
            !displayAvailable -> "NO_DISPLAY"
            settings.pauseInLandscape && !portrait -> "PAUSED_LANDSCAPE"
            settings.pauseInGameMode && gameModeActive -> "PAUSED_GAME_MODE"
            !gestureNavigation -> "GESTURE_NAV_OFF"
            keyguardLocked -> "KEYGUARD"
            else -> null
        }
    }

    fun isGestureAllowed(refreshKeyguard: Boolean = false): Boolean {
        if (refreshKeyguard && keyguardRecheckNeeded) refreshKeyguardState()
        // 多任务 / 负一屏等系统页面不响应呼出手势。
        if (isSystemPanelForeground()) return false
        return isModuleAllowed() && gestureNavigation && !keyguardLocked
    }

    /** 记录当前前台应用，供排除列表判定使用。仅在 system_server 内更新。 */
    fun updateForeground(packageName: String?, activityName: String? = null) {
        foregroundPackage = packageName
        foregroundActivity = activityName
    }

    /**
     * 指定包是否在排除列表内（闸门 2）。
     *
     * 判定口径统一为**前台包名**：该应用在前台时，模块的全部增强都不施加。
     * 「某个应用不被浮窗打开」是另一层语义，见 [isZoomExcluded]。
     */
    fun isExcluded(packageName: String?): Boolean =
        configuration.snapshot.isBlacklisted(packageName)

    /** 指定包是否在「禁用浮窗」列表内（闸门 3）：命中即不以任何形式的自由浮窗打开。 */
    fun isZoomExcluded(packageName: String?): Boolean =
        configuration.snapshot.isZoomLaunchExcluded(packageName)

    /** 当前前台应用是否在排除列表内。 */
    fun isForegroundExcluded(): Boolean = isExcluded(foregroundPackage)

    /**
     * 当前是否处于不该响应扇形手势的系统页面。
     *
     * 多任务（Recents/Overview）与负一屏（智能助理）都是系统页面：
     * 在其中呼出扇形菜单会盖住内容，也没有「打开应用」的语义，因此直接禁止。
     */
    fun isSystemPanelForeground(): Boolean {
        val name = foregroundActivity ?: return false
        val pkg = foregroundPackage
        if (pkg == "com.coloros.assistantscreen") return true
        val fromLauncher = pkg != null &&
            (pkg == "com.android.launcher" || pkg == "com.oplus.launcher")
        return name.contains("Recents", ignoreCase = true) ||
            name.contains("Overview", ignoreCase = true) ||
            (fromLauncher &&
                (name.contains("Assistant", ignoreCase = true) ||
                    name.contains("MinusScreen", ignoreCase = true)))
    }

    /**
     * 当前前台应用是否在「小窗跳转」的排除列表内。
     *
     * 语义等同把「小窗跳转」开关关掉，只是范围限定为这些应用在前台的时候；
     * 因此判定点与全局开关同一处，通知点击、磁贴、中转页抢占一并失效。
     */
    fun isForegroundZoomExcluded(): Boolean =
        configuration.snapshot.isZoomLaunchExcluded(foregroundPackage)

    /**
     * 模块是否处于可工作状态，不受暂停策略影响。
     *
     * 横屏与游戏模式的暂停针对扇形菜单的呼出手势；作用于已打开小窗的能力
     * （窗外关闭、快速上滑转迷你窗）与之无关，使用本判定。
     */
    fun isModuleActive(refreshKeyguard: Boolean = false): Boolean {
        if (refreshKeyguard && keyguardRecheckNeeded) refreshKeyguardState()
        val settings = configuration.snapshot
        return monitoring &&
            displayAvailable &&
            configuration.isAvailable &&
            settings.enabled &&
            !keyguardLocked
    }

    /**
     * 设备是否处于「亮屏且已解锁」。
     *
     * 锁屏与息屏都会把 keyguardLocked 置为 true（熄屏广播里直接置位，
     * 亮屏后重新读一次 KeyguardManager），所以这里同时覆盖两种状态。
     * 供不受手势导航开关影响的入口使用：窗外点击关闭、通知点击小窗。
     */
    fun isDeviceUnlocked(refreshKeyguard: Boolean = false): Boolean {
        if (refreshKeyguard && keyguardRecheckNeeded) refreshKeyguardState()
        return !keyguardLocked
    }

    private fun updateDisplay(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        refreshDisplay()
        notifyIfChanged()
    }

    @Suppress("DEPRECATION")
    private fun refreshDisplay() {
        portrait = try {
            // 使用默认物理显示的当前尺寸，避免桌面或小窗的固定竖屏 Configuration 掩盖横屏。
            val size = Point()
            displays?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(size)
            displayAvailable = size.x > 0 && size.y > 0
            size.x > 0 && size.y > size.x
        } catch (exception: RuntimeException) {
            displayAvailable = false
            reportFailure("MODULE_ENVIRONMENT_DISPLAY_FAILED", exception)
            false
        }
    }

    private fun refreshSettings() {
        val resolver = context?.contentResolver ?: return
        try {
            gameModeActive = Settings.Global.getInt(resolver, GAME_MODE_KEY, 0) == 1
            gestureNavigation = Settings.Secure.getInt(resolver, NAVIGATION_MODE_KEY, -1) == 2
        } catch (exception: RuntimeException) {
            gameModeActive = true
            gestureNavigation = false
            reportFailure("MODULE_ENVIRONMENT_SETTINGS_FAILED", exception)
        }
    }

    private fun refreshKeyguardState() {
        keyguardLocked = try {
            keyguard?.isKeyguardLocked != false
        } catch (_: RuntimeException) {
            true
        }
        keyguardRecheckNeeded = keyguardLocked
    }

    private fun notifyIfChanged() {
        val observation = Observation(monitoring, displayAvailable, portrait, gameModeActive, gestureNavigation, keyguardLocked)
        if (observation == lastObservation) return
        lastObservation = observation
        observers.forEach { observer ->
            try { observer() } catch (exception: RuntimeException) {
                reportFailure("MODULE_ENVIRONMENT_CALLBACK_FAILED", exception)
            }
        }
    }

    private fun releaseObservers() {
        fun release(action: () -> Unit) {
            try { action() } catch (exception: RuntimeException) {
                reportFailure("MODULE_ENVIRONMENT_CLEANUP_FAILED", exception)
            }
        }
        if (watchingSettings) {
            watchingSettings = false
            release { context?.contentResolver?.unregisterContentObserver(settingsObserver) }
        }
        if (watchingDisplay) {
            watchingDisplay = false
            release { displays?.unregisterDisplayListener(displayListener) }
        }
        if (watchingLock) {
            watchingLock = false
            release { context?.unregisterReceiver(lockReceiver) }
        }
    }

    private fun reportFailure(code: String, exception: RuntimeException) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt < 10_000L) return
        lastFailureAt = now
        onFailure(code, exception)
    }

    private data class Observation(
        val monitoring: Boolean,
        val displayAvailable: Boolean,
        val portrait: Boolean,
        val gameModeActive: Boolean,
        val gestureNavigation: Boolean,
        val keyguardLocked: Boolean,
    )

    private companion object {
        const val GAME_MODE_KEY = "debug_gamemode_value"
        const val NAVIGATION_MODE_KEY = "navigation_mode"
    }
}
