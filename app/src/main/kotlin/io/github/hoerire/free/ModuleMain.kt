package io.github.hoerire.free

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.hoerire.free.hook.LauncherHookInstaller
import io.github.hoerire.free.hook.PortalTapHookInstaller
import io.github.hoerire.free.hook.ProcessConfiguration
import io.github.hoerire.free.hook.SettingsHookInstaller
import io.github.hoerire.free.hook.SidebarHookInstaller
import io.github.hoerire.free.platform.coloros.ColorOsSidebarTarget
import io.github.hoerire.free.hook.SystemServerHookInstaller
import io.github.hoerire.free.hook.SystemUiHookInstaller
import io.github.hoerire.free.log.InstallReport

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
        io.github.hoerire.free.log.ModuleLog.attach { priority, code, throwable ->
            log(priority, TAG, code, throwable)
        }
        io.github.hoerire.free.settings.ModuleRuntime.module = this
        io.github.hoerire.free.log.ModuleLog.debug("Free") {
            "MODULE_LOG_ATTACHED_" + LogTag.of(param.processName)
        }

        try {
            val processConfiguration =
                ProcessConfiguration(
                    log = { priority, code, throwable -> log(priority, TAG, code, throwable) },
                )
            val preferences =
                getRemotePreferences(
                    io.github.hoerire.free.config.ModulePreferences.GROUP,
                )
            processConfiguration.start(preferences)
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
            // 配置摘要改为详细日志：关闭详细日志时这一行不再刷屏，
            // 每个进程只留一条安装汇总（见 InstallReport）。
            io.github.hoerire.free.log.ModuleLog.debug("Free") {
                "MODULE_CONFIG_READY_" + LogTag.of(processName) +
                    "_en" + settings.enabled +
                    "_zcount" + settings.zoomMaxWindowCount +
                    "_zapp" + settings.zoomAllowAnyApp +
                    // 小窗跳转各场景 + 手势，逐个列出来：
                    // 哪个功能不生效时，先看这一行就知道是开关没开还是 hook 没装上。
                    "_scnN" + settings.zoomLaunchNotice +
                    "_scnH" + settings.zoomLaunchHeadsUp +
                    "_scnC" + settings.zoomLaunchControlCenter +
                    "_scnI" + settings.zoomLaunchInterApp +
                    "_scnClip" + settings.zoomLaunchClipboard +
                    "_scnAc" + settings.autoCloseWindow +
                    "_scnSu" + settings.handleSwipeUpToMiniEnabled +
                    // 底角手势总闸：关掉后抢流/防穿透/桌面接管都不参与，排查时先看这一位。
                    "_radial" + settings.cornerInteractionActive +
                    "_keep" + settings.radialKeepOpen +
                    "_tap" + settings.outsideTapCloseMode.storedValue +
                    "_vlog" + settings.verboseLogEnabled +
                    "_pins" + settings.pinnedTargets.size +
                    "_bl" + settings.blacklistPackages.size +
                    // 「禁用浮窗」的条目数。
                    "_zx" + settings.zoomExcludedPackages.size +
                    // 改写系统拖拽准入（解除限制）与接管面板（快速分享）的两个开关。
                    "_fd" + settings.freeDragActive +
                    "_qs" + settings.quickShareActive
            }
        }
        // 从这里开始进入安装阶段：关闭详细日志时，每一步安装只累计数量，结束时汇总一行；
        // 失败项不受开关影响，始终带项目名与异常单独输出。见 [InstallReport]。
        InstallReport.begin(processName)
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        val settings = configuration ?: return
        if (hooksInstalled) return
        hooksInstalled = true
        SystemServerHookInstaller(
            module = this,
            configuration = settings,
            frameworkName = getFrameworkName(),
            frameworkVersion = getFrameworkVersion(),
            apiVersion = getApiVersion(),
        ).install(param.classLoader)
        finishInstall()
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val settings = configuration ?: return
        if (hooksInstalled) return
        when {
            isSidebarProcess(processName) && param.packageName == ColorOsSidebarTarget.PACKAGE_NAME -> {
                hooksInstalled = true
                SidebarHookInstaller(this, settings).install(param.classLoader, param.applicationInfo)
            }

            processName == PortalTapHookInstaller.PACKAGE_NAME &&
                param.packageName == PortalTapHookInstaller.PACKAGE_NAME -> {
                hooksInstalled = true
                PortalTapHookInstaller(this, settings).install(param.classLoader, param.applicationInfo?.sourceDir)
            }

            processName == PROCESS_SYSTEM_UI && param.packageName == PROCESS_SYSTEM_UI -> {
                hooksInstalled = true
                SystemUiHookInstaller(this, settings).install(param.classLoader)
            }

            processName == PROCESS_LAUNCHER && param.packageName == PROCESS_LAUNCHER -> {
                hooksInstalled = true
                LauncherHookInstaller(this, settings).install(param.classLoader, param.applicationInfo)
            }

            // SettingActivity 配置了 android:process，页面跑在 ":FlexibleAppControlService" 子进程；
            // 按 packageName 匹配，主进程与子进程都挂 hook（主进程没有偏好页，装了也无害）。
            param.packageName == PROCESS_ZOOM_SETTINGS -> {
                hooksInstalled = true
                SettingsHookInstaller(this, settings).install(param.classLoader)
            }

        }
        if (hooksInstalled) finishInstall()
    }

    /**
     * 安装阶段收尾：**只在一个时间点汇总一次**。
     *
     * 同步装上的项与随后几秒异步挂上的 hook（桌面 dex 解析、气泡管线重试、
     * system_server 的 systemReady）合并成一个数字，避免 `ok32 → ok5 → ok3`
     * 这种前后打架的分段汇总。
     * 汇总后立刻结束安装阶段：之后的运行期日志既不计入数量，
     * 也不会被误当成「安装失败」写进汇总行（失败本身仍会单独打 WARN）。
     */
    private fun finishInstall() {
        val settle =
            Runnable {
                InstallReport.flush()
                InstallReport.end()
            }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(settle, INSTALL_REPORT_SETTLE_MS)
        }.onFailure { settle.run() }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        log(Log.WARN, TAG, "HOT_RELOAD_REJECTED_RESTART_REQUIRED")
        return false
    }

    private fun isExpectedProcess(param: XposedModuleInterface.ModuleLoadedParam): Boolean =
        param.isSystemServer ||
            param.processName == PROCESS_SYSTEM_UI ||
            param.processName == PROCESS_LAUNCHER ||
            param.processName.startsWith(PROCESS_ZOOM_SETTINGS) ||
            param.processName == PortalTapHookInstaller.PACKAGE_NAME ||
            isSidebarProcess(param.processName)

    /**
     * 是否侧边栏应用的进程。
     *
     * 不写死 `:ui`：不同机型/版本把面板服务放在哪个子进程并不一致（主进程、`:ui`
     * 或其它后缀）。按包名前缀匹配，具体是不是那个服务由安装时能否找到
     * `UIService` 决定 —— 找不到就当普通进程跳过，不会误挂。
     */
    private fun isSidebarProcess(name: String?): Boolean =
        name != null &&
            (
                name == ColorOsSidebarTarget.PACKAGE_NAME ||
                    name.startsWith(ColorOsSidebarTarget.PACKAGE_NAME + ":")
            )

    private fun disableForConfigurationFailure(code: String, exception: RuntimeException) {
        configuration = null
        log(Log.ERROR, TAG, code, exception)
        detach()
    }

    private companion object {
        const val TAG = "Free"
        const val PROCESS_SYSTEM_UI = "com.android.systemui"
        const val PROCESS_LAUNCHER = "com.android.launcher"

        /**
         * 自由浮窗的设置页独立成包（辅助与关怀 > 自由浮窗 的宿主），
         * 入口注入与偏好树转储都挂在这个进程，不再依赖设置应用。
         */
        const val PROCESS_ZOOM_SETTINGS = "com.oplus.pscanvas"

        /** 安装收尾的汇总点：等异步安装（dex 解析、systemReady 等）落定后再统计。 */
        const val INSTALL_REPORT_SETTLE_MS = 10_000L
    }

    /** 进程名转成日志里可安全拼接的短标记。 */
    private object LogTag {
        fun of(name: String?): String =
            (name ?: "unknown").replace(':', '_').replace('.', '_')
    }
}
