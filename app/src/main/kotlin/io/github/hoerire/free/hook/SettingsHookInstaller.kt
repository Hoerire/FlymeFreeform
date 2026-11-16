package io.github.hoerire.free.hook

import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统设置首页入口。
 *
 * 设置首页是 androidx 偏好树：`res/xml/top_level_settings_oplus.xml` 展开成
 * `PreferenceScreen`，下面挂若干 `SettingsPreferenceCategory`，每类再挂条目。
 * 模块入口作为一条普通偏好插进「OPPO AI」所在的组，外观由系统条目自己的布局决定。
 *
 * 挂点分两层：基类 `PreferenceFragmentCompat` 收不到回调（首页 Fragment 覆盖了生命周期
 * 且不调基类），因此同时按具体类名挂 `OplusTopLevelSettings` 的 `onCreate` /
 * `onCreatePreferences`，在视图创建之前完成插入。
 */
internal class SettingsHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val dumped = ConcurrentHashMap.newKeySet<String>()
    private val emitted = AtomicInteger(0)
    private val injector = SettingsEntryInjector(module)

    fun install(classLoader: ClassLoader) {
        val fragmentClass =
            OemReflection.loadOrNull(classLoader, OemClasses.PREFERENCE_FRAGMENT_COMPAT)
        if (fragmentClass == null) {
            emit("SETTINGS_PROBE_FRAGMENT_CLASS_MISSING")
            return
        }
        emit("SETTINGS_HOOK_ENTER")
        hookLifecycle(fragmentClass, "onCreate", Bundle::class.java, "create")
        hookLifecycle(fragmentClass, "onResume", null, "resume")
        hookCreatePreferences(fragmentClass)
        hookAddPreferencesFromResource(fragmentClass)
        hookConcreteHomeFragment(classLoader)
        emit("SETTINGS_HOOK_DONE")
    }

    /**
     * 在首页 Fragment 自己的生命周期里插入条目。
     *
     * 基类挂点收不到回调：`OplusTopLevelSettings` 覆盖了 `onCreate` / `onCreatePreferences`
     * 且不调用基类实现。等到 `onResume` 才插入时列表已完成绑定与布局，后插入的条目
     * 画得出内容却接不到自己的点击，命中会落到下一行（日志：点在「Free」但
     * `SETTINGS_PERFORM_CLICK_kpersonal_assist`）。
     */
    private fun hookConcreteHomeFragment(fragmentLoader: ClassLoader) {
        val type =
            OemReflection.loadOrNull(fragmentLoader, OemClasses.SETTINGS_HOME_FRAGMENT)
                ?: return emit("SETTINGS_PROBE_HOME_FRAGMENT_MISSING")
        listOf(
            "onCreate" to arrayOf<Class<*>>(Bundle::class.java),
            "onCreatePreferences" to arrayOf<Class<*>>(Bundle::class.java, String::class.java),
        ).forEach { (name, parameters) ->
            val method = OemReflection.findMethod(type, name, *parameters) ?: return@forEach
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.settings.entry_before_view_" + name)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { handleFragment(chain.thisObject) }
                            .onFailure {
                                emit("SETTINGS_ENTRY_INJECT_FAILED_" + it.javaClass.simpleName)
                            }
                        result
                    }
                emit("SETTINGS_HOOK_CONCRETE_" + name)
            }.onFailure {
                emit("SETTINGS_HOOK_CONCRETE_FAIL_" + name + "_" + it.javaClass.simpleName)
            }
        }
    }

    private fun hookLifecycle(
        fragmentClass: Class<*>,
        name: String,
        parameterType: Class<*>?,
        idSuffix: String,
    ) {
        val method =
            if (parameterType == null) {
                OemReflection.findMethod(fragmentClass, name)
            } else {
                OemReflection.findMethod(fragmentClass, name, parameterType)
            }
        if (method == null) {
            emit("SETTINGS_PROBE_" + name + "_MISSING")
            return
        }
        runCatching {
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.settings.entry." + idSuffix)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { handleFragment(chain.thisObject) }
                        .onFailure { emit("SETTINGS_ENTRY_INJECT_FAILED_" + it.javaClass.simpleName) }
                    result
                }
        }.onFailure { emit("SETTINGS_HOOK_FAILED_" + idSuffix) }
    }

    /** 偏好树在 super 调用里构建完成，因此插桩点取在 proceed 之后。 */
    private fun hookCreatePreferences(fragmentClass: Class<*>) {
        val method =
            OemReflection.findMethod(
                fragmentClass,
                "onCreatePreferences",
                Bundle::class.java,
                String::class.java,
            ) ?: return
        runCatching {
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.settings.probe.create_preferences")
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching { handleFragment(chain.thisObject) }
                        .onFailure { emit("SETTINGS_ENTRY_INJECT_FAILED_" + it.javaClass.simpleName) }
                    result
                }
        }.onFailure { emit("SETTINGS_HOOK_FAILED_create_preferences") }
    }

    /** XML 资源名直接给出首页用的是哪一份布局，省去按 key 反推。 */
    private fun hookAddPreferencesFromResource(fragmentClass: Class<*>) {
        val method =
            OemReflection.findMethod(
                fragmentClass,
                "addPreferencesFromResource",
                Int::class.javaObjectType,
            ) ?: return
        runCatching {
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.settings.probe.resource")
                .intercept { chain ->
                    val resourceId = chain.args.getOrNull(0) as? Int
                    if (resourceId != null) {
                        emit("SETTINGS_XML_" + resourceName(chain.thisObject, resourceId))
                    }
                    chain.proceed()
                }
        }.onFailure { emit("SETTINGS_HOOK_FAILED_resource") }
    }

    private fun handleFragment(fragment: Any?) {
        val fragmentName = fragment?.javaClass?.name ?: return
        // 偏好树只在详细日志开启时展开：正常使用时不在设置进程里产生诊断输出。
        if (configuration.snapshot.verboseLogEnabled && dumped.add(fragmentName)) {
            dumpTree(fragment)
        }
        injector.inject(fragment)
    }

    private fun resourceName(fragment: Any?, resourceId: Int): String {
        val resources =
            OemReflection.callNoArg(fragment, "getResources") as? android.content.res.Resources
        return resources?.let { runCatching { it.getResourceName(resourceId) }.getOrNull() }
            ?: ("0x" + Integer.toHexString(resourceId))
    }

    private fun dumpTree(fragment: Any?) {
        val screen = OemReflection.callNoArg(fragment, "getPreferenceScreen")
        if (screen == null) {
            emit("SETTINGS_SCREEN_ABSENT_" + shortName(fragment?.javaClass?.name))
            return
        }
        emit("SETTINGS_SCREEN_k" + keyOf(screen))
        walk(screen, 0)
    }

    private fun walk(group: Any?, depth: Int) {
        if (group == null || depth > MAX_DEPTH) return
        val count = OemReflection.callNoArg(group, "getPreferenceCount") as? Int ?: return
        for (index in 0 until count) {
            val child = OemReflection.call(group, "getPreference", index) ?: continue
            emit(
                "SETTINGS_PREF_" + depth +
                    "_k" + keyOf(child) +
                    "_t" + titleOf(child) +
                    "_c" + shortName(child.javaClass.name),
            )
            walk(child, depth + 1)
        }
    }

    private fun keyOf(preference: Any?): String =
        (OemReflection.callNoArg(preference, "getKey") as? String)?.let(::clean) ?: "null"

    private fun titleOf(preference: Any?): String =
        (OemReflection.callNoArg(preference, "getTitle") as? CharSequence)?.let {
            clean(it.toString())
        } ?: "null"

    /** 日志按行写，标题里的换行会打断字段解析；统一压成单行并截断。 */
    private fun clean(value: String): String {
        val single = value.replace('\n', ' ').replace('\r', ' ').trim()
        return if (single.length <= MAX_TITLE_LENGTH) single else single.take(MAX_TITLE_LENGTH)
    }

    private fun shortName(className: String?): String =
        className?.substringAfterLast('.') ?: "unknown"

    private fun emit(code: String) {
        if (emitted.incrementAndGet() > MAX_ENTRIES) return
        module.log(Log.INFO, TAG, code)
    }

    private companion object {
        const val TAG = "Free"
        const val MAX_DEPTH = 3
        const val MAX_ENTRIES = 400
        const val MAX_TITLE_LENGTH = 60
    }
}
