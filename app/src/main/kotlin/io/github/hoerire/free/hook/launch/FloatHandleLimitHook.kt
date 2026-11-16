package io.github.hoerire.free.hook.launch

import io.github.hoerire.free.hook.ModuleEnvironmentState
import io.github.hoerire.free.hook.OemClasses
import io.github.hoerire.free.hook.ProcessConfiguration
import io.github.hoerire.free.log.ModuleLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 贴边浮标同时保留的个数（设置项「贴边数量」）。
 *
 * 上限由 `FloatHandleController#getLimitMaxFloatNum()` 返回，字段 `mLimitMaxFloatNum`
 * 在 `loadResource()` 里按机型定死：手机 2 个。该 getter 很可能被内联，因此除了改返回值，
 * 还在加浮标入口 `updateInfoMapForAdd` 里先把缓存字段改成设置值，两条路任一生效即可。
 */
internal class FloatHandleLimitHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState? = null,
) {
    private var limitField: Field? = null
    private val reported = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        val controller =
            runCatching { classLoader.loadClass(OemClasses.FLOAT_HANDLE_CONTROLLER) }.getOrNull()
        if (controller == null) {
            ModuleLog.warn("FLOAT_HANDLE_CLASS_MISSING", null)
            return
        }
        val getter =
            controller.declaredMethods.firstOrNull {
                it.name == "getLimitMaxFloatNum" && it.parameterCount == 0 &&
                    it.returnType == Int::class.javaPrimitiveType
            }
        val addPath =
            controller.declaredMethods.firstOrNull {
                it.name.contains("updateInfoMapForAdd") && it.parameterCount == 1
            }
        if (getter == null && addPath == null) {
            ModuleLog.warn("FLOAT_HANDLE_NO_ENTRY_" + controller.declaredMethods.size, null)
            return
        }
        if (getter != null) {
            runCatching {
                getter.isAccessible = true
                module.hook(getter)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.floathandle.max")
                    .intercept { chain ->
                        val original = chain.proceed()
                        val wanted = wantedLimit() ?: return@intercept original
                        if (original is Int && original != wanted) wanted else original
                    }
            }.onFailure { ModuleLog.warn("FLOAT_HANDLE_HOOK_FAILED_getter", it) }
        }
        if (addPath != null) {
            runCatching {
                addPath.isAccessible = true
                module.hook(addPath)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.floathandle.add")
                    .intercept { chain ->
                        val wanted = wantedLimit()
                        if (wanted != null) {
                            val field = limitFieldOf(chain.thisObject)
                            if (field != null) {
                                val current = runCatching { field.getInt(chain.thisObject) }.getOrNull()
                                if (current != null && current != wanted) {
                                    runCatching { field.setInt(chain.thisObject, wanted) }
                                        .onSuccess {
                                            if (reported.compareAndSet(false, true)) {
                                                ModuleLog.info("FLOAT_HANDLE_LIMIT_PATCH_" + current + "_TO_" + wanted)
                                            }
                                        }
                                }
                            }
                        }
                        chain.proceed()
                    }
            }.onFailure { ModuleLog.warn("FLOAT_HANDLE_HOOK_FAILED_add", it) }
        }
        ModuleLog.install("FLOAT_HANDLE_LIMIT_INSTALLED_" + (if (getter != null) 1 else 0) + (if (addPath != null) 1 else 0))
    }


    /** 当前应当生效的上限；模块关闭或前台被排除时保持系统原值。 */
    private fun wantedLimit(): Int? {
        val settings = configuration.snapshot
        if (!settings.zoomInteractionActive) return null
        if (environment?.isForegroundExcluded() == true) return null
        return settings.floatHandleMaxCountEffective
    }

    private fun limitFieldOf(instance: Any?): Field? {
        limitField?.let { return it }
        val clazz = instance?.javaClass ?: return null
        var type: Class<*>? = clazz
        var found: Field? = null
        while (type != null && type != Any::class.java && found == null) {
            val candidates = type.declaredFields.filter {
                it.type == Int::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
            found =
                candidates.firstOrNull { it.name.equals("mLimitMaxFloatNum", ignoreCase = true) }
                    ?: candidates.firstOrNull {
                        val name = it.name.lowercase()
                        name.contains("limit") && name.contains("max") && name.contains("float")
                    }
            type = type.superclass
        }
        val resolved = found ?: return null
        if (runCatching { resolved.isAccessible = true }.isFailure) return null
        limitField = resolved
        return resolved
    }
}
