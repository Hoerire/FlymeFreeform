package io.github.hoerire.free.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.hoerire.free.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.platform.coloros.ColorOsSystemUiApplicationTarget
import io.github.hoerire.free.platform.coloros.RomFlavor
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
                .setId("free.systemui.corner_spy_owner")
                .intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null && bound.compareAndSet(false, true)) {
                        // 通知点击小窗：改 Bundle（含剥离 remote 转场），让窗口不被横幅压住。
                        NotifBundleHook(module, configuration, context).install(classLoader)
                        installSettingsObserver(context)
                        // 角区抢流（SystemUI 侧 InputMonitor + pilferPointers）**只在 ColorOS 17+ 启用**。
                        //
                        // 这条通道是 3.1.3 引入的（3.1.2 没有它），之后低版系统陆续出现 SystemUI
                        // 崩溃循环；而 17 上的角区抢流已经改由 system_server 内建 monitor 承担
                        // （本通道在 17 上整场收不到事件，实测 0 条 CORNER_TRACK）。所以低版直接不启动它：
                        // 角区手势退回 WMS 旁观者（= 3.1.2 行为：扇形可用、左滑穿透存在）——
                        // 宁可保留这个旧缺陷，也不能让低版用户的系统界面崩。
                        if (RomFlavor.isColorOs17OrNewer) {
                            runCatching {
                                CornerInputPilferMonitor(module, configuration).start(context)
                            }.onSuccess {
                                io.github.hoerire.free.log.ModuleLog.info(
                                    "SYSTEMUI_CORNER_SPY_STARTED",
                                )
                            }.onFailure {
                                ModuleLog.warn("SYSTEMUI_CORNER_SPY_FAILED", it)
                            }
                        } else {
                            ModuleLog.info(
                                "SYSTEMUI_CORNER_SPY_SKIPPED_LEGACY_ROM_" +
                                    android.os.Build.VERSION.SDK_INT,
                            )
                        }
                    }
                    result
                }
            io.github.hoerire.free.log.ModuleLog.info("SYSTEMUI_HOOK_INSTALLED")
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
                            // 这条广播带的是整份设置快照：直接覆盖内存快照，改动即时生效。
                            // 只 reload 远程偏好不够——模块应用刚写进去的值，本进程的
                            // SharedPreferences 句柄未必看得到（「屏蔽键盘」等新开关就卡在这里）。
                            val pushed =
                                io.github.hoerire.free.config.SettingsBridge
                                    .readMap(intent.extras)
                                    ?.let { map ->
                                        runCatching {
                                            io.github.hoerire.free.config.SettingsBridge.decode(map)
                                        }.getOrNull()
                                    }
                            if (pushed != null) {
                                configuration.override(pushed)
                            } else {
                                configuration.forceReload()
                            }
                            io.github.hoerire.free.log.ModuleLog.info(
                                "SYSTEMUI_SETTINGS_RELOADED",
                            )
                        }
                        ModulePreferences.ACTION_SETTINGS_PUSHED -> {
                            // 框架配置存储不可写，设置以模块应用推送的整份快照为准。
                            if (sentFromUid < 0) return
                            val map = io.github.hoerire.free.config.SettingsBridge
                                .readMap(intent.extras) ?: return
                            val next =
                                try {
                                    io.github.hoerire.free.config.SettingsBridge
                                        .decode(map)
                                } catch (_: RuntimeException) {
                                    return
                                }
                            configuration.override(next)
                            io.github.hoerire.free.log.ModuleLog.info(
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
            io.github.hoerire.free.log.ModuleLog.info("SYSTEMUI_SETTINGS_OBSERVER_REGISTERED")
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
        const val TAG = "Free"
    }
}
