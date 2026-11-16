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
import io.github.hoerire.flymefreeform.log.ModuleLog
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

    /**
     * 识屏工具的原生点击改由 system_server 代发。
     *
     * `BreenoScreenIdentifyTool.handle()` 直接对 `com.coloros.colordirectservice` 调
     * `startForegroundService`；侧边栏进程处于后台时这次启动被系统拒绝（ActivityManager:
     * "Background start not allowed"），表现为点了没反应。这里拦下它，改发一条广播，
     * 由 system_server（uid 1000，不受该限制）代发同一个 Intent。
     *
     * 取不到坐标时按 0 传：宿主只是用它定位识屏浮层，不影响能否拉起。
     */
    private fun installScreenIdentifyRedirect(loader: ClassLoader) {
        val handle = try {
            loader
                .loadClass("com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.tools.BreenoScreenIdentifyTool")
                .getDeclaredMethod("handle")
        } catch (_: Throwable) {
            // 该机型没有这个工具条目，不需要接管。
            return
        }
        module
            .hook(handle)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.sidebar.breeno_identify")
            .intercept { chain ->
                // 总开关关闭时走侧边栏自己的启动逻辑，不接管。
                if (!configuration.snapshot.enabled) return@intercept chain.proceed()
                val sent = runCatching {
                    val service = chain.thisObject
                    val context = readContext(service)
                    // 必须开启身份共享：系统侧的 receiver 要靠 getSentFromPackage/getSentFromUid
                    // 核对发起者；普通 sendBroadcast 这两项是 null/-1，会被当作伪造请求丢掉。
                    context?.sendBroadcast(
                        Intent(io.github.hoerire.flymefreeform.platform.coloros.ScreenIdentifyStarter.REQUEST_ACTION)
                            .setPackage("android")
                            .putExtra("first_point_down_x", 0f)
                            .putExtra("first_point_down_y", 0f),
                        null,
                        android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle(),
                    ) != null
                }.getOrDefault(false)
                if (sent) {
                    log(Log.DEBUG, "SIDEBAR_SCREEN_IDENTIFY_REDIRECTED", null)
                    null
                } else {
                    chain.proceed()
                }
            }
    }

    /** 从工具实例上取 Context（AbsTool.mContext）。 */
    private fun readContext(instance: Any?): android.content.Context? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type!!.getDeclaredField("mContext") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(instance) as? android.content.Context }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    fun install(loader: ClassLoader, applicationInfo: ApplicationInfo) {
        // 面板进程的诊断日志统一走框架日志，落到 /data/adb/lspd/log/modules_*.log。
        io.github.hoerire.flymefreeform.log.ModuleLog.attach { priority, code, throwable ->
            module.log(priority, "FlymeFreeform", code, throwable)
        }
        // 「最近」要排除扇形里固定的应用：侧边栏进程读不到模块配置，由这里按当前快照转交。
        io.github.hoerire.flymefreeform.platform.coloros.FavoriteGroupInjector.attachPinnedTargets {
            configuration.snapshot.pinnedTargets
                .map(io.github.hoerire.flymefreeform.platform.coloros.FavoriteGroupInjector::aliasOfTarget)
                .toSet()
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
                    // 总开关关闭时不接管 Binder，避免与侧边栏自身或其他模块的绑定冲突。
                    if (!configuration.snapshot.enabled) return@intercept original
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
            // 侧边栏进程就绪：通知 system_server 现在可以安全地读目录（事件驱动，不再轮询重试）。
            runCatching {
                val app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? android.content.Context
                // 不能 setPackage(模块包名)：接收方注册在 system_server，定向到模块 App 会收不到。
                app?.sendBroadcast(
                    Intent(io.github.hoerire.flymefreeform.config.ModulePreferences.ACTION_SIDEBAR_READY),
                )
                log(Log.INFO, "SIDEBAR_READY_BROADCAST_SENT", null)
            }.onFailure { log(Log.WARN, "SIDEBAR_READY_BROADCAST_FAILED", it) }
            // 识屏：把原生 handle() 的启动改由 system_server 代发，缺这个工具条目的机型自动跳过。
            runCatching { installScreenIdentifyRedirect(loader) }
                .onFailure { log(Log.WARN, "SCREEN_IDENTIFY_REDIRECT_FAILED", it) }
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
        // 侧边栏条目本身由系统以小窗打开，这里只处理「更多」面板里的工具条目，
        // 因此跟随小窗总闸即可，不再单独做一个开关。
        if (!configuration.snapshot.zoomInteractionActive) return
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