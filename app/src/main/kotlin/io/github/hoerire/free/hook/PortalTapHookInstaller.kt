package io.github.hoerire.free.hook

import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.PinnedTargetCodec
import io.github.hoerire.free.config.SettingsBridge
import io.github.hoerire.free.log.ModuleLog
import io.github.hoerire.free.platform.coloros.SidebarEntryProtocol
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 内容传送门面板接管入口。
 *
 * 一个系统版本一份实现与一份配置，混淆名不跨版本复用：
 *
 * | 版本 | 实现 | 面板窗口 | 面板/列表/截图 | 状态回调 | 显示/收起 |
 * |---|---|---|---|---|---|
 * | 17.3.18 | [PortalTapLegacy] | `d6.x0` | `m` / `n` / `p` | `DragEventReceiver.l` | `H(1)` / `U0()` |
 * | 17.5.4 | [PortalTapModern] | `u61` | `k` / `l` / `n` | 窗口 `c` → `zd.i`（`ml0`），回调在热区 `sz.b` | `s(String)`、`g()` / `r(Z)` |
 *
 * 旧版的完整做法（[PortalTapLegacy]，17.3.18 实测名）：
 *   · 绑定：`ContentPortalService.onBind` 建立会话，窗口取服务字段 `c`；
 *   · 长按即弹：钩 `DragEventReceiver.g(4)` 拿到拖动坐标，再调 `l(4)` 派发 ENTER，
 *     原厂收到 ENTER 才会把面板升到手指位置；
 *   · 接管呈现：钩窗口 `H(1)`，自己造一个 DROP 交给 `B(1)`（记录落点），
 *     再调 `O()` 显示列表，然后一次性设窗口参数与五列网格；
 *   · 防回弹：钩窗口 `U0()`（入场动画）跑完复位参数；
 *   · 生命周期：钩 `onDragEvent` 吞掉拖动事件，关闭只走点窗外与点条目。
 *
 * 新版按上表换成同义的类与字段，流程保持一致。
 */
internal class PortalTapHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val settingsReceiverInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 设置实时同步。
     *
     * 本进程只在长按拖起时读设置（「自由传送」「黑名单」），2.7.2 里没有接收器，
     * 改完开关要等传送门进程重启才生效。这里挂 Application.onCreate 拿 Context，
     * 注册与其它作用域进程相同的推送接收器，并在启动时主动索取一次当前快照。
     */
    private fun installSettingsReceiver(loader: ClassLoader) {
        val existing =
            runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? android.content.Context
            }.getOrNull()
        if (existing != null) bindSettingsReceiver(existing)
        if (settingsReceiverInstalled.get()) return
        runCatching {
            val application = Class.forName("android.app.Application", false, loader)
            val onCreate = application.getDeclaredMethod("onCreate")
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.portal.settings_context")
                .intercept { chain ->
                    val result = chain.proceed()
                    (chain.thisObject as? android.content.Context)?.let(::bindSettingsReceiver)
                    result
                }
        }.onFailure { ModuleLog.warn("PORTAL_SETTINGS_CONTEXT_UNAVAILABLE", it) }
    }

    private fun bindSettingsReceiver(context: android.content.Context) {
        if (!settingsReceiverInstalled.compareAndSet(false, true)) return
        runCatching {
            val moduleUid =
                context.packageManager
                    .getApplicationInfo(SidebarEntryProtocol.MODULE_PACKAGE, 0)
                    .uid
            val receiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(receiverContext: android.content.Context?, intent: android.content.Intent?) {
                        when (intent?.action) {
                            ModulePreferences.ACTION_SETTINGS_PUSHED -> {
                                if (sentFromUid != moduleUid) return
                                val map = SettingsBridge.readMap(intent.extras) ?: return
                                val next = runCatching { SettingsBridge.decode(map) }.getOrNull() ?: return
                                configuration.override(next)
                            }
                            ModulePreferences.ACTION_SETTINGS_SNAPSHOT -> {
                                val map = SettingsBridge.readMap(intent.extras) ?: return
                                val next = runCatching { SettingsBridge.decode(map) }.getOrNull() ?: return
                                configuration.override(next)
                            }
                            // 2.2 的单一路径：App 每次改设置都发这个广播。传送门进程晚于
                            // 设置变更启动时，只靠 SETTINGS_PUSHED/SNAPSHOT 会永远停在旧快照，
                            // 表现为「自由传送」打开后长按仍只有系统原版面板。
                            ModulePreferences.ACTION_PINNED_TARGETS_CHANGED -> {
                                if (sentFromUid != moduleUid) return
                                val encoded =
                                    intent.getStringExtra(ModulePreferences.EXTRA_PINNED_TARGETS)
                                        ?: return
                                configuration.forceReload()
                                configuration.override(
                                    configuration.snapshot.copy(
                                        enabled = intent.getBooleanExtra(
                                            ModulePreferences.EXTRA_MODULE_ENABLED,
                                            configuration.snapshot.enabled,
                                        ),
                                        pinsSaved = intent.getBooleanExtra(
                                            ModulePreferences.EXTRA_PINS_SAVED,
                                            configuration.snapshot.pinsSaved,
                                        ),
                                        pinnedTargets = PinnedTargetCodec.decodeList(encoded),
                                    ),
                                )
                            }
                        }
                    }
                }
            context.registerReceiver(
                receiver,
                android.content.IntentFilter().apply {
                    addAction(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED)
                    addAction(ModulePreferences.ACTION_SETTINGS_PUSHED)
                    addAction(ModulePreferences.ACTION_SETTINGS_SNAPSHOT)
                },
                android.content.Context.RECEIVER_EXPORTED,
            )
            // 进程晚于设置变更启动时，主动要一份当前快照。
            runCatching {
                context.sendBroadcast(
                    android.content.Intent(ModulePreferences.ACTION_SETTINGS_REQUEST)
                        .setPackage("android")
                        .putExtra(ModulePreferences.EXTRA_REQUESTER_PACKAGE, PACKAGE_NAME),
                )
            }
            ModuleLog.install("PORTAL_SETTINGS_RECEIVER_INSTALLED")
        }.onFailure {
            settingsReceiverInstalled.set(false)
            ModuleLog.warn("PORTAL_SETTINGS_RECEIVER_FAILED", it)
        }
    }

    fun install(loader: ClassLoader, apkPath: String?) {
        installSettingsReceiver(loader)
        if (PortalTapLegacy(module, configuration).install(loader)) {
            ModuleLog.install("PORTAL_MODE_LEGACY")
            return
        }
        val profile = PROFILES.firstOrNull {
            runCatching { loader.loadClass(it.windowClass) }.isSuccess
        }
        if (profile == null) {
            ModuleLog.install("PORTAL_MODE_UNSUPPORTED")
            return
        }
        if (PortalTapModern(module, configuration).install(loader, profile)) {
            ModuleLog.install("PORTAL_MODE_MODERN_" + profile.portalVersion)
        } else {
            ModuleLog.install("PORTAL_MODE_MODERN_FAILED_" + profile.portalVersion)
        }
    }

    companion object {
        const val PACKAGE_NAME = "com.oplus.contentportal"

        /** 版本 → 混淆名映射。字段含义见 [PortalProfile]。 */
        private val PROFILES = listOf(
            PortalProfile(
                portalVersion = "17.5.4",
                serviceClass = "com.oplus.contentportal.service.ContentPortalService",
                windowClass = "u61",
                receiverClass = "ml0",
                stateClass = "com.oplus.contentportal.drag.DragEventReceiver\$DragState",
                enterStateField = "d",
                dragEntry = "onDragEvent",
                panelHideMethod = "r",
                animMethod = "g",
                controllerField = "c",
                receiverField = "i",
                hotViewField = "c",
                listenerField = "mOnDragListener",
                callbackField = "b",
                serviceInfoClass = "com.oplus.contentportal.data.ServiceInfo",
                launchMethod = "b",
                panelField = "k",
                listField = "l",
                screenshotField = "n",
                listDataField = "q",
                eventField = "o",
                clipField = "p",
            ),
        )
    }
}
