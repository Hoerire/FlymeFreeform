package io.github.hoerire.flymefreeform

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.hoerire.flymefreeform.hook.ProcessConfiguration
import io.github.hoerire.flymefreeform.hook.SidebarHookInstaller
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsSidebarTarget
import io.github.hoerire.flymefreeform.hook.SystemServerHookInstaller
import io.github.hoerire.flymefreeform.hook.SystemUiHookInstaller

/** libxposed API 102 入口；具体手势、窗口和厂商接口分别由对应职责实现。 */
class ModuleMain : XposedModule() {
    private var processName: String? = null
    private var configuration: ProcessConfiguration? = null
    private var hooksInstalled = false

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        if (!isExpectedProcess(param)) {
            // 侧边栏会派生 edgepanel 等子进程，它们不在作用域内；跳过属于预期行为，
            // 因此按调试级别记录进程名，避免在日志里造成「出错」的错觉。
            log(Log.DEBUG, TAG, "MODULE_SKIPPED_UNEXPECTED_PROCESS_" + LogTag.of(param.processName))
            detach()
            return
        }

        val properties = getFrameworkProperties()
        if (properties and XposedInterface.PROP_CAP_REMOTE == 0L) {
            log(Log.WARN, TAG, "MODULE_CONFIG_REMOTE_UNAVAILABLE")
            detach()
            return
        }
        if (param.isSystemServer && properties and XposedInterface.PROP_CAP_SYSTEM == 0L) {
            log(Log.WARN, TAG, "MODULE_SYSTEM_CAPABILITY_UNAVAILABLE")
            detach()
            return
        }

        // 所有作用域进程统一接管诊断日志出口。仅在 system_server 与侧边栏进程 attach 时，
        // 其余进程的 ModuleLog 只写 logcat，排障时看不到记录。
        io.github.hoerire.flymefreeform.log.ModuleLog.attach { priority, code, throwable ->
            log(priority, TAG, code, throwable)
        }
        log(Log.INFO, TAG, "MODULE_LOG_ATTACHED_" + LogTag.of(param.processName))

        try {
            val processConfiguration =
                ProcessConfiguration(
                    log = { priority, code, throwable -> log(priority, TAG, code, throwable) },
                )
            processConfiguration.start(
                getRemotePreferences(io.github.hoerire.flymefreeform.config.ModulePreferences.GROUP),
            )
            configuration = processConfiguration
        } catch (exception: UnsupportedOperationException) {
            disableForConfigurationFailure("MODULE_CONFIG_UNSUPPORTED", exception)
        } catch (exception: SecurityException) {
            disableForConfigurationFailure("MODULE_CONFIG_ACCESS_DENIED", exception)
        } catch (exception: IllegalStateException) {
            disableForConfigurationFailure("MODULE_CONFIG_NOT_READY", exception)
        } catch (exception: RuntimeException) {
            disableForConfigurationFailure("MODULE_CONFIG_FAILURE", exception)
        }
        configuration?.snapshot?.let { settings ->
            // 配置摘要常开一条：排障时先确认各进程读到的开关状态是否一致。
            log(
                Log.INFO,
                TAG,
                "MODULE_CONFIG_READY_" + LogTag.of(processName) +
                    "_en" + settings.enabled +
                    "_zcount" + settings.zoomMaxWindowCount +
                    "_zapp" + settings.zoomAllowAnyApp +
                    // 小窗跳转四个场景 + 手势，逐个列出来：
                    // 哪个功能不生效时，先看这一行就知道是开关没开还是 hook 没装上。
                    "_scnN" + settings.zoomLaunchNotice +
                    "_scnH" + settings.zoomLaunchHeadsUp +
                    "_scnC" + settings.zoomLaunchControlCenter +
                    "_scnI" + settings.zoomLaunchInterApp +
                    "_scnIr" + settings.zoomLaunchInterAppResult +
                    "_scnSh" + settings.zoomLaunchShareRelay +
                    "_scnSys" + settings.zoomLaunchSystem +
                    "_scnClip" + settings.zoomLaunchClipboard +
                    "_scnAc" + settings.autoCloseWindow +
                    "_scnSu" + settings.handleSwipeUpToMiniEnabled +
                    "_keep" + settings.radialKeepOpen +
                    "_tap" + settings.outsideTapCloseMode.storedValue +
                    "_vlog" + settings.verboseLogEnabled +
                    "_pins" + settings.pinnedTargets.size +
                    "_bl" + settings.blacklistPackages.size +
                    // 「禁用浮窗」的条目数：常开 INFO 一行里带上，详细日志关掉也能确认配置读到了。
                    "_zx" + settings.zoomExcludedPackages.size +
                    // 自由拖拽是唯一一条改写系统拖拽准入的开关，常开一行便于确认它被读到了。
                    "_fd" + settings.freeDragActive,
            )
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val settings = configuration ?: return
        if (hooksInstalled) return
        hooksInstalled = true
        SystemServerHookInstaller(this, settings).install(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val settings = configuration ?: return
        if (hooksInstalled) return
        when {
            processName == ColorOsSidebarTarget.PROCESS_NAME && param.packageName == ColorOsSidebarTarget.PACKAGE_NAME -> {
                hooksInstalled = true
                SidebarHookInstaller(this, settings).install(param.classLoader, param.applicationInfo)
            }

            processName == PROCESS_SYSTEM_UI && param.packageName == PROCESS_SYSTEM_UI -> {
                hooksInstalled = true
                SystemUiHookInstaller(this, settings).install(param.classLoader)
            }

        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.WARN, TAG, "HOT_RELOAD_REJECTED_RESTART_REQUIRED")
        return false
    }

    private fun isExpectedProcess(param: XposedModuleInterface.ModuleLoadedParam): Boolean =
        param.isSystemServer ||
            param.processName == PROCESS_SYSTEM_UI ||
            param.processName == ColorOsSidebarTarget.PROCESS_NAME

    private fun disableForConfigurationFailure(code: String, exception: RuntimeException) {
        configuration = null
        log(Log.ERROR, TAG, code, exception)
        detach()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val PROCESS_SYSTEM_UI = "com.android.systemui"
    }

    /** 进程名转成日志里可安全拼接的短标记。 */
    private object LogTag {
        fun of(name: String?): String =
            (name ?: "unknown").replace(':', '_').replace('.', '_')
    }
}
