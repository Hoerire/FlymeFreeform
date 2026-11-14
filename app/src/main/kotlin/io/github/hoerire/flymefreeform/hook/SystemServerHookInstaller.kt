package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.util.Log
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.platform.coloros.ColorOsFreeformCoordinator
import java.util.concurrent.atomic.AtomicBoolean

internal class SystemServerHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val bound = AtomicBoolean(false)
    private val interAppTracker = io.github.hoerire.flymefreeform.hook.launch.InterAppLaunchTracker()
    private val environment = ModuleEnvironmentState(configuration) { code, exception ->
        ModuleLog.warn(code, exception)
    }

    fun install(classLoader: ClassLoader) {
        // 本进程内其他类（如 ColorOsFreeformLauncher）的诊断日志同样写入 LSPosed 日志文件。
        io.github.hoerire.flymefreeform.log.ModuleLog.attach { priority, code, throwable ->
            module.log(priority, TAG, code, throwable)
        }
        OutsideTapCloseHookInstaller(module, configuration, environment).install(classLoader)
        FloatKeepAliveHook(module, configuration).install(classLoader)
        HandleSwipeUpHookInstaller(module, configuration, environment).install(classLoader)
        io.github.hoerire.flymefreeform.hook.launch.ActivityStarterZoomInjector(
            module,
            configuration,
            environment,
            interAppTracker,
        ).install(classLoader)
        io.github.hoerire.flymefreeform.hook.launch.FlexibleWindowLimitHook(
            module,
            configuration,
            environment,
        ).install(classLoader)
        UahInputSuppressor(module, configuration, environment).install(classLoader)
        // 主路径：把底部角落从 ColorOS 的导航拦截区域里挖掉，从源头上避免和系统手势抢流。
        NavigationInterceptionHook(module, configuration, environment).install(classLoader)

        CenterZoomWindowHook(module, configuration).install(classLoader)
        // 内容传送门的长按拖拽准入：给系统没登记的应用补通用白名单（「自由拖拽」开关）。
        FreeDragHookInstaller(module, configuration, environment).install(classLoader)
        // 系统 Home 手势的指针监听器不认挖角，也不受抢流影响，单独在入口吞掉角区按下。
        SystemGestureSuppressor(module, configuration).install(classLoader)

        installForegroundObserver(classLoader)
        try {
            // 厂商初始化入口只能反射定位；缺失时保留系统启动流程。
            @SuppressLint("PrivateApi")
            val controllerClass = classLoader.loadClass(FLEXIBLE_TASK_CONTROLLER_CLASS)
            val systemReady = controllerClass.getDeclaredMethod("systemReady", Boolean::class.javaPrimitiveType)
            module
                .hook(systemReady)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.flexible_ready")
                .intercept { chain ->
                    val result = chain.proceed()
                    val controller = chain.thisObject
                    if (controller != null && bound.compareAndSet(false, true)) {
                        ColorOsFreeformCoordinator(
                            controller = controller,
                            classLoader = classLoader,
                            configuration = configuration,
                            environmentState = environment,
                            logger = { priority, code, throwable ->
                                module.log(priority, TAG, code, throwable)
                            },
                        ).start()
                    }
                    result
                }
            io.github.hoerire.flymefreeform.log.ModuleLog.info("SYSTEM_FREEFORM_READY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("SYSTEM_FREEFORM_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("SYSTEM_FREEFORM_TARGET_LINKAGE_FAILED", exception)
        }
    }


    private fun installForegroundObserver(classLoader: ClassLoader) {
        try {
            val recordClass = classLoader.loadClass(OemClasses.ACTIVITY_RECORD)
            val managerClass = classLoader.loadClass(ACTIVITY_TASK_MANAGER_CLASS)
            val method =
                managerClass.getDeclaredMethod(
                    "setLastResumedActivityUncheckLocked",
                    recordClass,
                    String::class.java,
                )
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.system.foreground")
                .intercept { chain ->
                    runCatching {
                        val record = chain.getArg(0)
                        val component = OemReflection.readField(record, "mActivityComponent") as? android.content.ComponentName
                        val foregroundPackage =
                            (OemReflection.readField(record, "packageName") as? String) ?: component?.packageName
                        environment.updateForeground(foregroundPackage, component?.className)
                    }
                    chain.proceed()
                }
            io.github.hoerire.flymefreeform.log.ModuleLog.info("SYSTEM_FOREGROUND_OBSERVER_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            ModuleLog.warn("SYSTEM_FOREGROUND_OBSERVER_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            ModuleLog.warn("SYSTEM_FOREGROUND_OBSERVER_LINKAGE_FAILED", exception)
        }
    }


    private companion object {
        const val TAG = "FlymeFreeform"
        const val ACTIVITY_TASK_MANAGER_CLASS = "com.android.server.wm.ActivityTaskManagerService"
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = OemClasses.FLEXIBLE_TASK_CONTROLLER
    }
}