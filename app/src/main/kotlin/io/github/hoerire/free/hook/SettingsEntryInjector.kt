package io.github.hoerire.free.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.AttributeSet

/**
 * 在自由浮窗设置页（com.oplus.pscanvas）的「浮窗模式」分组里插入模块入口。
 *
 * 该页的偏好树由 App 代码逐条 addPreference 拼装（pscanvas 打包的 androidx preference
 * 是精简版，没有 setPreferenceScreen 一类装填 API），所以注入也走同一入口：
 * 拿到目标分组的 PreferenceGroup 实例后 addPreference 追加到组尾。
 *
 * 不设图标：分组内条目本身不带图标（SettingsNoIconJumpPreference 风格）。
 */
internal class SettingsEntryInjector {
    /** 每个分组实例只注入一次：页面重复打开会新建整棵树，按实例判重。 */
    private val injectedGroups = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /**
     * 向目标分组追加入口条目。重复调用安全：同一分组实例只注入一次。
     * [group] 是 pscanvas classloader 里的 PreferenceGroup 实例。
     */
    fun addEntryTo(group: Any): Boolean {
        if (injectedGroups.contains(group)) return false
        val context = OemReflection.callNoArg(group, "getContext") as? Context ?: return false
        val preference = createPreference(context) ?: return false
        invoke(preference, "setKey", String::class.java, ENTRY_KEY)
        invoke(preference, "setTitle", CharSequence::class.java, TITLE)
        invoke(preference, "setPersistent", Boolean::class.javaPrimitiveType, false)
        launchIntentOf()?.let {
            invoke(preference, "setIntent", Intent::class.java, it)
        }
        val added = invoke(group, "addPreference", null, preference)
        if (added) injectedGroups.add(group)
        return added
    }

    /**
     * 构造偏好实例。
     *
     * 优先用 (Context, AttributeSet) 构造（COUI 风格条目靠 AttributeSet 决定布局类别，
     * 属性为空时退到默认布局）；再退到基类单参构造。都不可用返回 null，宁可不注入。
     * 类从 [context.classLoader] 取：pscanvas 的 preference 库与 App 的不是同一份。
     */
    private fun createPreference(context: Context): Any? {
        val loader = context.classLoader
        val type = OemReflection.loadOrNull(loader, BASE_PREFERENCE_CLASS) ?: return null
        runCatching {
            type.getConstructor(Context::class.java, AttributeSet::class.java)
                .newInstance(context, null)
        }.getOrNull()?.let { return it }
        return runCatching {
            type.getConstructor(Context::class.java).newInstance(context)
        }.getOrNull()
    }

    /**
     * 显式组件启动，不依赖桌面入口。
     *
     * 模块不在桌面露出时没有 LAUNCHER 过滤器，`getLaunchIntentForPackage` 返回 null，
     * 因此写死组件名。
     */
    private fun launchIntentOf(): Any? =
        runCatching {
            Intent()
                .setComponent(ComponentName(MODULE_PACKAGE, MAIN_ACTIVITY_CLASS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()

    /**
     * 按精确参数类型置值。
     *
     * 不能用按参数个数匹配：`Preference` 的 `setTitle` 有 `int` 重载，
     * 按个数取会撞上资源 id 版本，传字符串只会抛 IllegalArgumentException。
     * [parameterType] 为 null 时按参数个数匹配，用于只有唯一重载的 `addPreference`。
     */
    private fun invoke(instance: Any, name: String, parameterType: Class<*>?, value: Any?): Boolean {
        val method =
            if (parameterType == null) {
                OemReflection.findMethodByArity(instance.javaClass, name, 1)
            } else {
                OemReflection.findMethod(instance.javaClass, name, parameterType)
            } ?: return false
        return runCatching { method.invoke(instance, value) }.isSuccess
    }

    private companion object {
        const val ENTRY_KEY = "free_module_entry_zoom"
        const val MODULE_PACKAGE = "io.github.hoerire.free"
        const val MAIN_ACTIVITY_CLASS = "io.github.hoerire.free.MainActivity"
        const val TITLE = "Free"
        const val BASE_PREFERENCE_CLASS = "androidx.preference.Preference"
    }
}
