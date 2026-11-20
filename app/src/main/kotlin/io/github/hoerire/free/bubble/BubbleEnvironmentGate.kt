package io.github.hoerire.free.bubble

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * 显示环境的统一判定：前台应用、锁屏、息屏、游戏模式。
 *
 * 气泡、状态栏滚动与即时建议共用同一份结论，避免各写一套。
 * 结论做短缓存：通知密集到达时不必每条都过一次 binder；游戏与锁屏变化不频繁，
 * 缓存时间取得比前台应用长一些。
 *
 * 「前台应用」是**包名 + 用户**：分身应用跑在另一个 Android 用户下，包名与主应用相同，
 * 只比包名会把「主应用在前台」误当成「分身也在前台」，于是分身通知被错误豁免。
 */
internal class BubbleEnvironmentGate(context: Context) {
    private val appContext = context.applicationContext ?: context

    /** 前台应用：包名可能取不到，userId 取不到时按主用户（0）处理。 */
    data class ForegroundApp(val packageName: String?, val userId: Int)

    private var foregroundAt = 0L
    private var foreground = ForegroundApp(null, 0)

    private var gameAt = 0L
    private var game: Boolean = false

    private var lockedAt = 0L
    private var locked: Boolean = false

    /** 该包此刻是否应当被抑制；返回原因码，null 表示可以显示。 */
    fun suppressionReason(packageName: String, userId: Int, settings: BubbleSettings): String? {
        if (settings.skipLocked && (isLocked() || !isScreenInteractive())) return "LOCKED"
        if (settings.skipGame && isGameMode()) return "GAME"
        if (settings.skipForeground && matchesForeground(foreground(), packageName, userId)) {
            return "FOREGROUND"
        }
        return null
    }

    fun foreground(): ForegroundApp {
        val now = SystemClock.elapsedRealtime()
        if (now - foregroundAt < FOREGROUND_TTL_MS) return foreground
        foregroundAt = now
        foreground = readForeground()
        return foreground
    }

    fun foregroundPackage(): String? = foreground().packageName

    private fun readForeground(): ForegroundApp =
        runCatching {
            val manager = appContext.getSystemService(ActivityManager::class.java)
                ?: return ForegroundApp(null, 0)
            val task = manager.getRunningTasks(1).firstOrNull()
                ?: return ForegroundApp(null, 0)
            val packageName = task.topActivity?.packageName
            // TaskInfo.userId 在 SDK 里是隐藏字段，运行时存在；取不到按主用户处理。
            val userId =
                runCatching {
                    task.javaClass.getField("userId").getInt(task)
                }.getOrDefault(0)
            ForegroundApp(packageName, userId)
        }.getOrDefault(ForegroundApp(null, 0))

    /**
     * 游戏模式：ColorOS 在游戏空间/游戏模式下把 `debug_gamemode_value` 置 1；
     * 读不到时退回「前台应用的 appCategory 是 game」。
     */
    fun isGameMode(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - gameAt < GAME_TTL_MS) return game
        gameAt = now
        game = readGameMode()
        return game
    }

    /** 游戏模式开关变化时清掉缓存，下一次判定立刻读到新值。 */
    fun invalidateGameCache() {
        gameAt = 0L
    }

    private fun readGameMode(): Boolean =
        runCatching {
            Settings.Global.getInt(appContext.contentResolver, GAME_MODE_KEY, 0) == 1
        }.getOrDefault(false) || isGameCategory(foregroundPackage())

    fun isLocked(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lockedAt < LOCKED_TTL_MS) return locked
        lockedAt = now
        locked = runCatching {
            appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)
        return locked
    }

    fun isScreenInteractive(): Boolean =
        runCatching {
            appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        }.getOrDefault(true)

    private fun isGameCategory(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return runCatching {
            val info =
                appContext.packageManager.getApplicationInfo(packageName, 0)
            info.category == ApplicationInfo.CATEGORY_GAME
        }.getOrDefault(false)
    }

    companion object {
        /**
         * 前台应用是否就是这条通知的来源。
         *
         * 包名相同还不够：主应用与分身包名一致、用户不同，只看包名会把分身的通知
         * 在主应用前台时错误豁免，反之亦然。
         */
        fun matchesForeground(fg: ForegroundApp, packageName: String, userId: Int): Boolean =
            fg.packageName == packageName && fg.userId == userId

        /** 供监听方使用：游戏模式开关的 `Settings.Global` URI。 */
        fun gameModeUri(context: Context): android.net.Uri =
            Settings.Global.getUriFor(GAME_MODE_KEY)

        private const val FOREGROUND_TTL_MS = 500L
        private const val GAME_TTL_MS = 1_000L
        private const val LOCKED_TTL_MS = 200L

        /** ColorOS 游戏模式开关；游戏空间内为 1。 */
        private const val GAME_MODE_KEY = "debug_gamemode_value"
    }
}
