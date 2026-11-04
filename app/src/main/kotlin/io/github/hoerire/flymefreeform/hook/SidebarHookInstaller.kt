package io.github.hoerire.flymefreeform.hook

import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsAllAppsContent
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsAllAppsEndpoint
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsSidebarEntryEndpoint
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsSidebarTarget
import io.github.hoerire.flymefreeform.platform.coloros.SidebarEntryProtocol
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsSidebarVisualTarget
import io.github.hoerire.flymefreeform.platform.coloros.PanelToolLaunchBridge
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 只注入侧边栏 UI 进程；服务方法照常执行一次，应用/工具的启动仍交给原厂路由。 */
internal class SidebarHookInstaller(private val module: XposedModule, private val configuration: ProcessConfiguration) {
    private var entryEndpoint: ColorOsSidebarEntryEndpoint? = null
    private var endpoint: ColorOsAllAppsEndpoint? = null

    private var visualTarget: ColorOsSidebarVisualTarget? = null
    private val resolutionStarted = AtomicBoolean(false)

    fun install(loader: ClassLoader, applicationInfo: ApplicationInfo) {
        // 面板进程的诊断日志统一走框架日志，落到 /data/adb/lspd/log/modules_*.log。
        io.github.hoerire.flymefreeform.log.ModuleLog.attach { priority, code, throwable ->
            module.log(priority, "FlymeFreeform", code, throwable)
        }
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val serviceClass = loader.loadClass(ColorOsSidebarTarget.SERVICE_CLASS)
            require(Service::class.java.isAssignableFrom(serviceClass))
            val bind = serviceClass.getDeclaredMethod("onBind", Intent::class.java)
            val unbind = serviceClass.getDeclaredMethod("onUnbind", Intent::class.java)
            val destroy = serviceClass.getDeclaredMethod("onDestroy")
            require(bind.returnType == IBinder::class.java)
            val allClass = loader.loadClass(ColorOsAllAppsContent.ALL_CLASS)
            val adapter = allClass.getDeclaredMethod("getAllAppAdapter")
            require(adapter.returnType.name == ColorOsAllAppsContent.ADAPTER_CLASS)
            val mainClass = loader.loadClass(ColorOsAllAppsContent.MAIN_CLASS)
            val changeState = mainClass.getDeclaredMethod("changeState", loader.loadClass("${ColorOsAllAppsContent.MAIN_CLASS}\$State"))

            handles += module.hook(bind).setId("flymefreeform.allapps.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val service = chain.thisObject as? Service
                    val intent = chain.getArg(0) as? Intent
                    if (service != null && intent?.action == ColorOsSidebarTarget.BIND_ACTION) {
                        if (endpoint == null && ColorOsSidebarTarget.supportedUid(service) == Process.myUid()) {
                            endpoint = ColorOsAllAppsEndpoint(service, loader, configuration, { visualTarget }, ::log)
                        }
                        endpoint?.takeIf { it.service === service }?.binder ?: original
                    } else if (service != null && intent?.action == SidebarEntryProtocol.BIND_ACTION) {
                        if (entryEndpoint == null && ColorOsSidebarTarget.supportedUid(service) == Process.myUid()) {
                            entryEndpoint = ColorOsSidebarEntryEndpoint(service, loader, configuration, ::log)
                        }
                        entryEndpoint?.takeIf { it.service === service }?.binder ?: original
                    } else original
                }
            handles += module.hook(unbind).setId("flymefreeform.allapps.unbind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val intent = chain.getArg(0) as? Intent
                    if (intent?.action == ColorOsSidebarTarget.BIND_ACTION) endpoint?.onUnbound(intent.identifier)
                    if (intent?.action == SidebarEntryProtocol.BIND_ACTION) entryEndpoint?.onUnbound(intent.identifier)
                    original
                }
            handles += module.hook(destroy).setId("flymefreeform.allapps.destroy")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (entryEndpoint?.service === chain.thisObject) {
                        val previous = entryEndpoint
                        entryEndpoint = null
                        previous?.dispose()
                    }
                    if (endpoint?.service === chain.thisObject) {
                        val previous = endpoint
                        endpoint = null
                        previous?.dispose()
                    }
                    chain.proceed()
                }
            handles += module.hook(adapter).setId("flymefreeform.allapps.adapter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    endpoint?.onAdapterCreated(chain.thisObject, original)
                    original
                }
            handles += module.hook(changeState).setId("flymefreeform.allapps.native_state")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    endpoint?.onNativeSidebarState((chain.getArg(0) as? Enum<*>)?.name)
                    original
                }
            log(Log.INFO, "ALL_APPS_BRIDGE_INSTALLED", null)
            // 工具（付款码、扫一扫这类）的启动经系统 uid 代发，侧边栏进程只负责递参数，
            // 小窗参数必须在这一步并入，理由见 PanelToolLaunchBridge 的说明。
            // 厂商版本缺少该入口时只跳过本功能，面板接管不受影响。
            runCatching {
                val platform = loader.loadClass(APP_PLATFORM_UTILS_CLASS)
                val startActivityAsUser =
                    platform.getDeclaredMethod(
                        "n",
                        Intent::class.java,
                        Bundle::class.java,
                        android.os.UserHandle::class.java,
                    )
                handles +=
                    module.hook(startActivityAsUser).setId("flymefreeform.panel.tool_zoom")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            runCatching {
                                applyToolLaunchZoom(chain.getArg(0) as? Intent, chain.getArg(1) as? Bundle)
                            }
                            chain.proceed()
                        }
                log(Log.INFO, "PANEL_TOOL_ZOOM_INSTALLED", null)
            }.onFailure { log(Log.WARN, "PANEL_TOOL_ZOOM_UNAVAILABLE", it) }
            resolveVisualTarget(loader, applicationInfo)
        } catch (exception: ReflectiveOperationException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_SIGNATURE_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_HOOK_INSTALL_FAILED", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_LINKAGE_FAILED", error)
        }
    }

    private fun resolveVisualTarget(loader: ClassLoader, info: ApplicationInfo) {
        if (!resolutionStarted.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlymeFreeform-SidebarDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                log(Log.WARN, "SIDEBAR_DEX_RESOLUTION_TIMEOUT", null)
            }
        }
        handler.postDelayed(timeout, 10_000L)
        executor.execute {
            try {
                val target = ColorOsSidebarVisualTarget.resolve(
                    loader, File(info.sourceDir), File(info.dataDir, "code_cache/flymefreeform/sidebar-symbols"),
                ) { code, exception -> log(if (exception == null) Log.INFO else Log.WARN, code, exception) }
                handler.post {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout)
                        visualTarget = target
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                log(Log.WARN, "SIDEBAR_DEX_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                log(Log.WARN, "SIDEBAR_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                log(Log.WARN, "SIDEBAR_DEX_RESOLUTION_FAILED", exception)
            } catch (error: LinkageError) {
                log(Log.WARN, "SIDEBAR_DEX_LINKAGE_FAILED", error)
            } finally {
                handler.post {
                    if (finished.compareAndSet(false, true)) handler.removeCallbacks(timeout)
                }
            }
        }
        executor.shutdown()
    }

    /**
     * 面板工具的启动改写：只处理已登记的点击目标，参数就地并入系统已建好的 Bundle。
     *
     * 未登记、目标不符或模块未启用时完全放行，面板内其它启动不受影响。
     */
    private fun applyToolLaunchZoom(intent: Intent?, options: Bundle?) {
        if (intent == null || options == null) return
        if (!configuration.snapshot.enabled) return
        val target = intent.component?.packageName ?: intent.getPackage() ?: return
        if (!PanelToolLaunchBridge.apply(target, options)) return
        io.github.hoerire.flymefreeform.log.ModuleLog.debug("FFLAUNCH") { "PANEL_TOOL_ZOOM_" + target }
    }

    private fun log(priority: Int, code: String, throwable: Throwable?) = module.log(priority, "FlymeFreeform", code, throwable)

    private companion object {
        /** 侧边栏的参数转交入口：Intent 与小窗参数在这里合并后交给系统 uid 发起。 */
        const val APP_PLATFORM_UTILS_CLASS = "com.oplus.smartsidebar.utils.AppPlatformUtils"
    }
}
