package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsSystemUiApplicationTarget
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("PrivateApi")
internal class SystemUiHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        // 不在 onNotificationClicked / onStartFloatWindow 上接管通知点击。
        //
        // 这两个入口在 ColorOS 17 上回调不稳定：改写后一旦浮窗通道返回空值，原始点击即被丢弃，
        // 常驻通知与自动分组通知会表现为点击无响应。
        // 窗口形态改由 NotifBundleHook 组装启动参数，通知中心与通知横幅各自只受自己的开关约束。
        try {
            val target =
                ColorOsSystemUiApplicationTarget.resolve { className ->
                    classLoader.loadClass(className)
                } ?: throw NoSuchMethodException("SystemUI application onCreate")
            val onCreate = target.onCreate
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        // 通知点击小窗：改 Bundle（含剥离 remote 转场），让窗口不被横幅压住。
                        NotifBundleHook(module, configuration).install(classLoader)
                        installSettingsObserver(context)
                    }
                    result
                }
            io.github.hoerire.flymefreeform.log.ModuleLog.info("SYSTEMUI_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("SYSTEMUI_APPLICATION_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("SYSTEMUI_APPLICATION_TARGET_LINKAGE_FAILED", exception)
        }
    }

    /**
     * 接收设置变更广播并重读磁盘配置。
     *
     * SystemUI 不在服务端的实时推送范围内时，本进程的开关会停在开机那一刻的取值，
     * 表现为「通知横幅」改完要重启才生效。这里只做重读，不校验发起者：
     * 伪造广播最多换来一次读盘，不影响任何判定。
     */
    private fun installSettingsObserver(context: Context) {
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        ModulePreferences.ACTION_PINNED_TARGETS_CHANGED -> {
                            configuration.forceReload()
                            io.github.hoerire.flymefreeform.log.ModuleLog.info(
                                "SYSTEMUI_SETTINGS_RELOADED",
                            )
                        }
                        ModulePreferences.ACTION_SETTINGS_PUSHED -> {
                            // 框架配置存储不可写，设置以模块应用推送的整份快照为准。
                            if (sentFromUid < 0) return
                            val map = io.github.hoerire.flymefreeform.config.SettingsBridge
                                .readMap(intent.extras) ?: return
                            val next =
                                try {
                                    io.github.hoerire.flymefreeform.config.SettingsBridge
                                        .decode(map)
                                } catch (_: RuntimeException) {
                                    return
                                }
                            configuration.override(next)
                            io.github.hoerire.flymefreeform.log.ModuleLog.info(
                                "SYSTEMUI_SETTINGS_PUSHED",
                            )
                        }
                    }
                }
            }
        runCatching {
            context.registerReceiver(
                receiver,
                android.content.IntentFilter().apply {
                    addAction(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED)
                    addAction(ModulePreferences.ACTION_SETTINGS_PUSHED)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onSuccess {
            io.github.hoerire.flymefreeform.log.ModuleLog.info("SYSTEMUI_SETTINGS_OBSERVER_REGISTERED")
            // 主动索取一次当前设置：系统进程已落盘，本进程内存里可能还是旧值。
            runCatching {
                context.sendBroadcast(
                    android.content.Intent(ModulePreferences.ACTION_SETTINGS_REQUEST)
                        .setPackage("android")
                        .putExtra(
                            ModulePreferences.EXTRA_REQUESTER_PACKAGE,
                            "com.android.systemui",
                        ),
                )
            }
        }.onFailure {
            module.log(Log.WARN, TAG, "SYSTEMUI_SETTINGS_OBSERVER_FAILED", it as? RuntimeException)
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}
