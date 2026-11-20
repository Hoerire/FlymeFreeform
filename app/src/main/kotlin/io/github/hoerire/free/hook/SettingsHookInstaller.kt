package io.github.hoerire.free.hook

import android.os.Bundle
import android.util.Log
import io.github.hoerire.free.log.ModuleLog
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
    private val injector = SettingsEntryInjector()

    fun install(classLoader: ClassLoader) {
        // 首页（设置应用）用 Compat 版基类；自由浮窗设置（com.oplus.pscanvas）用
        // androidx.preference.PreferenceFragment（android.app.Fragment 系），
        // 两个类的方法签名一致，按存在性回退。
        val fragmentClass =
            OemReflection.loadOrNull(classLoader, OemClasses.PREFERENCE_FRAGMENT_COMPAT)
                ?: OemReflection.loadOrNull(classLoader, PREFERENCE_FRAGMENT_LEGACY)
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
        hookZoomSettingFragment(classLoader)
        hookOperationRedirect(classLoader)
        emit("SETTINGS_HOOK_DONE")
    }

    /**
     * 在首页 Fragment 自己的生命周期里插入条目。
     *
     * 基类挂点收不到回调：`OplusTopLevelSettings` 覆盖了 `onCreate` / `onCreatePreferences`
     * 且不调用基类实现。等到 `onResume` 才插入时列表已完成绑定与布局，后插入的条目
     * 画得出内容却接不到自己的点击，命中会落到下一行。
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

    /**
     * 自由浮窗设置页（com.oplus.pscanvas）的偏好 Fragment 是独立实现，
     * 既不继承 Compat 也不继承 legacy 基类，只能对具体类挂钩。
     * 找不到方法就跳过（该页可能改版），不影响其它注入点。
     */
    private fun hookZoomSettingFragment(fragmentLoader: ClassLoader) {
        val type =
            OemReflection.loadOrNull(fragmentLoader, ZOOM_SETTING_FRAGMENT)
                ?: return emit("SETTINGS_PROBE_ZOOM_FRAGMENT_MISSING")
        listOf(
            "onCreate" to arrayOf<Class<*>>(Bundle::class.java),
            "onCreatePreferences" to arrayOf<Class<*>>(
                Bundle::class.java,
                String::class.java,
            ),
            "onCreateView" to arrayOf<Class<*>>(
                android.view.LayoutInflater::class.java,
                android.view.ViewGroup::class.java,
                Bundle::class.java,
            ),
            "onViewCreated" to arrayOf<Class<*>>(
                android.view.View::class.java,
                Bundle::class.java,
            ),
            "onActivityCreated" to arrayOf<Class<*>>(Bundle::class.java),
            "onResume" to arrayOf<Class<*>>(),
        ).forEach { (name, parameters) ->
            val method = OemReflection.findMethod(type, name, *parameters)
            // 厂商自定义实现：偏好树建完的时机不确定，每个生命周期点各挂一次；
            // 找不到的方法打缺失点，避免「静默没挂上」排障困难。
            if (method == null) {
                emit("SETTINGS_PROBE_ZOOM_METHOD_MISSING_" + name)
                return@forEach
            }
            runCatching {
                module
                    .hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("free.settings.zoom." + name)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { handleFragment(chain.thisObject) }
                        result
                    }
            }
        }
        emit("SETTINGS_HOOK_ZOOM_DONE")
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
    }

    /**
     * 自由浮窗设置页的注入挂点：PreferenceGroup.addPreference。
     *
     * 该页的树由 App 代码逐条 addPreference 拼装（pscanvas 打包的 androidx preference
     * 是精简版，没有 setPreferenceScreen 一类装填 API，生命周期点上树始终为空）。
     * 在这里既能看到每个条目的归属分组，也是唯一可靠的注入时机：
     * 「浮窗模式」分组实例在它第一条子项进来时记下，组内「操作教程」落座后
     * 把模块入口追加到组尾（排在官方两项之后）。
     */
    /**
     * 「操作教程」入口改道：该条目点击后进入 OperationActivity（自由浮窗的操作引导页），
     * hook 它的 onCreate，改道启动 Free 模块，原引导页不再显示。
     *
     * 页面主体是 COUI 自绘列表，偏好框架只是壳（混淆方法表里没有 addPreference 链路），
     * 逐条目注入不可行；把「操作教程」作为模块入口是用户选定的方案。
     */
    private fun hookOperationRedirect(fragmentLoader: ClassLoader) {
        val activityClass =
            OemReflection.loadOrNull(fragmentLoader, OPERATION_ACTIVITY_CLASS)
                ?: return emit("SETTINGS_PROBE_OPERATION_ACTIVITY_MISSING")
        val onCreate = OemReflection.findMethod(activityClass, "onCreate", Bundle::class.java)
            ?: return emit("SETTINGS_PROBE_OPERATION_ONCREATE_MISSING")
        runCatching {
            module
                .hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("free.settings.zoom.operation_redirect")
                .intercept { chain ->
                    val activity = chain.thisObject as? android.app.Activity
                    if (activity == null) {
                        // thisObject 非 Activity（不应发生），保守放行原流程。
                        return@intercept chain.proceed()
                    }
                    runCatching {
                        val intent = android.content.Intent()
                            .setComponent(
                                android.content.ComponentName(MODULE_PACKAGE, MODULE_ACTIVITY_CLASS),
                            )
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        activity.startActivity(intent)
                        activity.finish()
                    }.onFailure {
                        // 启动失败时不吞掉原页面：放行原 onCreate，操作教程保持可用。
                        emit("SETTINGS_OPERATION_REDIRECT_FAILED_" + it.javaClass.simpleName)
                        return@intercept chain.proceed()
                    }
                    // 已改道：跳过原 onCreate，不再构建引导页。
                    null
                }
            emit("SETTINGS_HOOK_OPERATION_REDIRECT_OK")
        }.onFailure { emit("SETTINGS_HOOK_OPERATION_REDIRECT_FAILED_" + it.javaClass.simpleName) }
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
        ModuleLog.info(code)
    }

    private companion object {


        /** 自由浮窗的操作引导页：点「操作教程」进入，hook 其 onCreate 改道。 */
        const val OPERATION_ACTIVITY_CLASS = "com.oplus.flexibletask.setting.OperationActivity"
        const val MODULE_PACKAGE = "io.github.hoerire.free"
        const val MODULE_ACTIVITY_CLASS = "io.github.hoerire.free.MainActivity"
        /** 自由浮窗设置页的偏好 Fragment（com.oplus.pscanvas，独立实现）。 */
        const val ZOOM_SETTING_FRAGMENT = "com.oplus.flexibletask.setting.fragment.SettingFragment"
        /** pscanvas 侧的老版偏好 Fragment 基类（android.app.Fragment 系）。 */
        const val PREFERENCE_FRAGMENT_LEGACY = "androidx.preference.PreferenceFragment"
        const val TAG = "Free"
        const val MAX_DEPTH = 3
        const val MAX_ENTRIES = 400
        const val MAX_TITLE_LENGTH = 60
    }
}
