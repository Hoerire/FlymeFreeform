package io.github.hoerire.free.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.hoerire.free.config.ModulePreferences
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * system_server 侧的「即将休眠」检测，供常亮建议使用。
 *
 * `PowerManagerService` 每次重算用户活动汇总时会给出屏幕所处的阶段：
 * 仅 DIMMED 位（2）或汇总为 0，表示屏幕正在变暗、马上要睡。这里把结论写进
 * [ModulePreferences.KEY_PRE_SLEEP_ARMED]，SystemUI 侧轮询该标志后铺点按层。
 *
 * 两条约束：
 * 1. 钩子运行在 PowerManagerService 持锁的路径上，函数内不做 binder 调用，
 *    一律把写入丢到主线程；
 * 2. 只在取值变化时写一次 `Settings.Global`，避免用户每动一下手指就写一次设置。
 */
internal class PreSleepDetectionHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastArmed = Int.MIN_VALUE

    fun install(classLoader: ClassLoader) {
        val powerClass =
            runCatching {
                Class.forName("com.android.server.power.PowerManagerService", false, classLoader)
            }.getOrNull()
        if (powerClass == null) {
            logger(Log.INFO, "PRESLEEP_HOOKS_NONE", null)
            return
        }
        val methods =
            powerClass.declaredMethods.filter {
                it.name == "updateUserActivitySummaryLocked" &&
                    it.parameterTypes.any { type -> type == Long::class.javaPrimitiveType }
            }
        if (methods.isEmpty()) {
            logger(Log.INFO, "PRESLEEP_HOOKS_NONE", null)
            return
        }
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.presleep.$index")
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { detect(chain.thisObject) }
                    result
                }
        }
        logger(Log.INFO, "PRESLEEP_HOOKS_" + methods.size, null)
        // 上一轮进程留下的标志是不准的，启动即清一次。
        handler.post { write(0) }
    }

    private fun detect(service: Any?) {
        val snapshot = configuration.snapshot
        if (!snapshot.suggestionsActive || !snapshot.suggestionKeepAwake) return
        val summary = OemReflection.readField(service, "mUserActivitySummary") as? Int ?: return
        // BRIGHT = 1，DIMMED = 2；0 表示没有可继续的屏幕状态，即即将息屏。
        val armed = if ((summary and DIMMED_BIT) != 0 || summary == 0) 1 else 0
        if (armed == lastArmed) return
        handler.post { write(armed) }
    }

    private fun write(armed: Int) {
        val context = systemContext() ?: return
        val resolver = context.contentResolver
        runCatching {
            val current =
                Settings.Global.getInt(
                    resolver,
                    ModulePreferences.KEY_PRE_SLEEP_ARMED,
                    0,
                )
            if (current != armed) {
                Settings.Global.putInt(
                    resolver,
                    ModulePreferences.KEY_PRE_SLEEP_ARMED,
                    armed,
                )
            }
            lastArmed = armed
        }.onFailure { logger(Log.WARN, "PRESLEEP_WRITE_FAILED", it) }
    }

    /** system_server 里没有现成 Context，ActivityThread 的系统上下文是唯一稳定来源。 */
    private fun systemContext(): Context? =
        runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
                ?: return null
            threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
        }.getOrNull()

    private companion object {
        /** PowerManagerService 用户活动汇总里的 DIMMED 位。 */
        const val DIMMED_BIT = 2
    }
}
