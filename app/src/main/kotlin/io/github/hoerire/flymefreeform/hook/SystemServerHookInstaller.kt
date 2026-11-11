package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.util.Log
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
        module.log(Log.WARN, TAG, code, exception)
    }

    fun install(classLoader: ClassLoader) {
        // 本进程内其他类（如 ColorOsFreeformLauncher）的诊断日志同样写入 LSPosed 日志文件。
        io.github.hoerire.flymefreeform.log.ModuleLog.attach { priority, code, throwable ->
            module.log(priority, TAG, code, throwable)
        }
        OutsideTapCloseHookInstaller(module, configuration, environment).install(classLoader)
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
        NotificationEnqueueProbe(module).install(classLoader)
        UahInputSuppressor(module, configuration, environment).install(classLoader)
        // 主路径：把底部角落从 ColorOS 的导航拦截区域里挖掉，从源头上避免和系统手势抢流。
        NavigationInterceptionHook(module, configuration).install(classLoader)
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
                        // 用 controller 实例的加载器：services.jar 的类只在这个加载器可见
                        // （LocalServices 自身的 classLoader 反而取不到 InputManagerService）。
                        installInputFilter(controller.javaClass.classLoader)
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
            module.log(Log.INFO, TAG, "SYSTEM_FREEFORM_READY_HOOK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEM_FREEFORM_TARGET_LINKAGE_FAILED", exception)
        }
    }


    /**
     * 安装全局输入过滤器（第一阶段：只透传）。
     *
     * InputFilter 通过 LocalServices 拿到 system_server 内的 InputManagerService 实例再反射设置；
     * 拿不到就只记日志，不影响系统。
     */
    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun installInputFilter(classLoader: ClassLoader) {
        installInputFilterAttempt(classLoader, ATTEMPT_LIMIT)
    }

    /**
     * 找到 system_server 里的 InputManagerService 实例。
     *
     * 三条路依次尝试，并把走过的路记进日志——这个实例在不同 ROM 上暴露的位置不一样，
     * 之前只按 LocalServices 找，ColorOS 17 上一直取不到：
     *
     * 1. LocalServices 的 Map 静态字段（AOSP 的标准位置）；
     * 2. InputManagerService 自身的静态字段（部分厂商把它挂成单例）；
     * 3. 从 WindowManagerService 的字段里找（WMS 持有输入管理器引用）。
     *
     * 全都拿不到时，把 LocalServices 里实际有哪些实例写进日志，便于一次定位。
     */
    private fun resolveInputManagerService(classLoader: ClassLoader): Any? {
        val byName = { value: Any? ->
            value?.javaClass?.name?.contains("InputManagerService") == true
        }
        // 1. LocalServices
        val localServices =
            runCatching { Class.forName("com.android.server.LocalServices", false, classLoader) }
                .getOrNull()
        if (localServices != null) {
            for (field in localServices.declaredFields) {
                if (!Map::class.java.isAssignableFrom(field.type)) continue
                val map = runCatching {
                    field.isAccessible = true
                    field.get(null) as? Map<*, *>
                }.getOrNull() ?: continue
                map.values.firstOrNull(byName)?.let {
                    module.log(Log.INFO, TAG, "INPUT_FILTER_IMS_FROM_LOCAL_SERVICES", null)
                    return it
                }
                runCatching {
                    val names = map.values.map { it?.javaClass?.simpleName ?: "?" }.take(12)
                    module.log(Log.INFO, TAG, "LOCAL_SERVICES_DUMP_" + names.joinToString(","), null)
                }
            }
        }
        // 2. InputManagerService 自身的静态字段
        runCatching {
            val cls = Class.forName("com.android.server.input.InputManagerService", false, classLoader)
            for (field in cls.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(null)
                }.getOrNull()
                if (byName(value)) {
                    module.log(Log.INFO, TAG, "INPUT_FILTER_IMS_FROM_STATIC", null)
                    return value
                }
            }
        }
        // 3. 从 WindowManagerService 的字段里找
        runCatching {
            val wmsClass = Class.forName("com.android.server.wm.WindowManagerService", false, classLoader)
            for (field in wmsClass.declaredFields) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(null)
                }.getOrNull()
                if (byName(value)) {
                    module.log(Log.INFO, TAG, "INPUT_FILTER_IMS_FROM_WMS", null)
                    return value
                }
            }
        }
        return null
    }

    /**
     * 尝试注册输入过滤器；InputManagerService 由 system_server 稍后注册，
     * 因此找不到时按固定间隔重试，直到成功或用尽次数。
     */
    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun installInputFilterAttempt(classLoader: ClassLoader, remaining: Int) {
        val resolved = runCatching {
            val ims = resolveInputManagerService(classLoader)
                ?: error("InputManagerService not registered yet")
            val setter = ims.javaClass.methods.firstOrNull {
                it.name == "setInputFilter" && it.parameterTypes.size == 1
            } ?: error("no setInputFilter")
            val filter = ZoneInputFilter(
                module,
                android.view.ViewConfiguration.getDoubleTapTimeout().toLong(),
            )
            val iface = setter.parameterTypes[0]
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
            ) { _, method, args ->
                when (method.name) {
                    "filterInputEvent" -> {
                        filter.filterFromProxy(args)
                        null
                    }
                    "asBinder" -> filter
                    else -> null
                }
            }
            setter.invoke(ims, proxy)
            true
        }
        resolved.onSuccess { module.log(Log.INFO, TAG, "INPUT_FILTER_REGISTERED", null) }
        resolved.onFailure { error ->
            if (remaining <= 1) {
                module.log(Log.WARN, TAG, "INPUT_FILTER_REGISTER_FAILED", error)
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { installInputFilterAttempt(classLoader, remaining - 1) },
                    FILTER_RETRY_MS,
                )
            }
        }
    }
    /**
     * 跟踪前台应用，供排除列表判定。
     *
     * 排除列表的语义是「该应用在前台时不施加任何修改」，需要知道当前前台是哪个包。
     * 恢复回调每次活动切换都会触发，这里只做一次字段读取与赋值。
     */
    private fun installForegroundObserver(classLoader: ClassLoader) {
        try {
            val recordClass = classLoader.loadClass("com.android.server.wm.ActivityRecord")
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
                        val component = readField(record, "mActivityComponent") as? android.content.ComponentName
                        environment.updateForeground(
                            (readField(record, "packageName") as? String) ?: component?.packageName,
                            component?.className,
                        )
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, TAG, "SYSTEM_FOREGROUND_OBSERVER_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEM_FOREGROUND_OBSERVER_UNAVAILABLE", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "SYSTEM_FOREGROUND_OBSERVER_LINKAGE_FAILED", exception)
        }
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            try {
                return current.getDeclaredField(name).also { it.isAccessible = true }.get(instance)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val ACTIVITY_TASK_MANAGER_CLASS = "com.android.server.wm.ActivityTaskManagerService"
        const val ATTEMPT_LIMIT = 20
        const val FILTER_RETRY_MS = 3000L
        const val FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"
    }
}