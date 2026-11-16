package io.github.hoerire.free.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 把模块入口插进设置首页的「OPPO AI」之上。
 *
 * 首页由 `res/xml/top_level_settings_oplus.xml` 展开，条目 key 与标题的对应关系已按资源表
 * 核对：`personal_assist` 的标题资源是 `string/breeno_ai_new`，运行期由
 * `TopLevelSmartServicePreferenceController` 补成「OPPO AI」；同组的 `game_settings` /
 * `oplus_cloud_service` / `wallet_and_payment` 与首页顺序一致，插入点据此确定。
 *
 * 条目实例用同组条目的类构造，但**不拷贝属性**：拷贝过来的字段里含原条目的行为状态，
 * 实测会导致点击被路由到「OPPO AI」。因此只设置标题、图标、顺序与显式启动 Intent。
 */
internal class SettingsEntryInjector(private val module: XposedModule) {
    private val traced = ConcurrentHashMap.newKeySet<String>()

    fun inject(fragment: Any?) {
        val screen = OemReflection.callNoArg(fragment, "getPreferenceScreen")
            ?: return trace("NO_SCREEN")
        if (OemReflection.callNoArg(screen, "getKey") != SCREEN_KEY) return trace("OTHER_SCREEN")
        val target = OemReflection.call(screen, "findPreference", TARGET_KEY)
            ?: return trace("TARGET_MISSING")
        val parent = OemReflection.callNoArg(target, "getParent") ?: return trace("NO_PARENT")
        if (OemReflection.call(parent, "findPreference", ENTRY_KEY) != null) {
            return trace("DUPLICATE")
        }
        val context = OemReflection.callNoArg(fragment, "getContext") as? Context
            ?: return trace("NO_CONTEXT")
        val preference = createPreference(context, target) ?: return trace("CONSTRUCT_FAILED")
        val order = ((OemReflection.callNoArg(target, "getOrder") as? Int) ?: 0) - 1
        invoke(preference, "setKey", String::class.java, ENTRY_KEY)
        invoke(preference, "setTitle", CharSequence::class.java, TITLE)
        invoke(preference, "setOrder", Int::class.javaPrimitiveType, order)
        invoke(preference, "setPersistent", Boolean::class.javaPrimitiveType, false)
        iconOf(context, target)?.let {
            invoke(preference, "setIcon", Drawable::class.java, it)
        }
        launchIntentOf()?.let {
            invoke(preference, "setIntent", Intent::class.java, it)
        }
        val added = invoke(parent, "addPreference", null, preference)
        module.log(
            Log.INFO,
            TAG,
            "SETTINGS_ENTRY_" + (if (added) "ADDED" else "ADD_FAILED") +
                "_c" + preference.javaClass.simpleName + "_ord" + order +
                "_p" + OemReflection.callNoArg(parent, "getKey"),
        )
    }

    /**
     * 构造同组条目的实例。
     *
     * 厂商偏好从 `AttributeSet` 读取布局类别与着色，属性为空时退到默认布局；
     * 两种构造都不可用时返回 null，宁可不插入也不把首页改成半残状态。
     */
    private fun createPreference(context: Context, target: Any): Any? {
        runCatching {
            target.javaClass
                .getConstructor(Context::class.java, AttributeSet::class.java)
                .newInstance(context, null)
        }.getOrNull()?.let { return it }
        return runCatching {
            Class
                .forName(BASE_PREFERENCE_CLASS, false, context.classLoader)
                .getConstructor(Context::class.java)
                .newInstance(context)
        }.getOrNull()
    }

    /**
     * 图标裁掉透明留白后缩到同组条目的图标尺寸。
     *
     * 应用图标是 108dp 自适应图标，四周带安全区留白；按目标尺寸直接栅格化会让可见图形
     * 只剩十几 dp。目标尺寸取同组条目 `getIcon().intrinsicWidth`，即系统 XML 里 `icon`
     * 属性的真实像素值，不靠本实现按 dp 猜。
     */
    private fun iconOf(context: Context, reference: Any?): Any? =
        runCatching {
            val density = context.resources.displayMetrics.density
            val raw = (ICON_RAW_DP * density).toInt()
            val drawn = Bitmap.createBitmap(raw, raw, Bitmap.Config.ARGB_8888)
            val source = context.packageManager.getApplicationIcon(MODULE_PACKAGE)
            source.setBounds(0, 0, raw, raw)
            source.draw(Canvas(drawn))
            val trimmed = trimTransparent(drawn) ?: drawn
            val size = referenceIconSize(context, reference)
            BitmapDrawable(context.resources, Bitmap.createScaledBitmap(trimmed, size, size, true))
        }.getOrNull()

    /** 同组条目图标的固有像素尺寸；读不到时退到 [ICON_SIZE_DP]。 */
    private fun referenceIconSize(context: Context, reference: Any?): Int {
        val icon = OemReflection.callNoArg(reference, "getIcon") as? Drawable
        val measured = icon?.intrinsicWidth ?: 0
        if (measured > 0) return measured
        return (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()
    }

    /** 非透明像素的包围盒；整张全透明时返回 null。 */
    private fun trimTransparent(bitmap: Bitmap): Bitmap? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) == 0) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < 0 || bottom < 0) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * 显式组件启动，不依赖桌面入口。
     *
     * 模块只在设置里出现时就不保留 LAUNCHER 过滤器，`getLaunchIntentForPackage`
     * 会返回 null，因此写死组件名。
     */
    private fun launchIntentOf(): Any? =
        runCatching {
            Intent()
                .setComponent(ComponentName(MODULE_PACKAGE, MAIN_ACTIVITY_CLASS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()

    /** 每个提前返回原因只记一次，便于定位插不进去的那一步。 */
    private fun trace(code: String) {
        if (traced.add(code)) module.log(Log.INFO, TAG, "SETTINGS_ENTRY_STOP_" + code)
    }

    /**
     * 按精确参数类型置值。
     *
     * 不能用按参数个数匹配：`Preference` 的 `setTitle` / `setIcon` 都有 `int` 重载，
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
        const val TAG = "Free"
        const val SCREEN_KEY = "oplus_top_level_settings"
        const val TARGET_KEY = "personal_assist"
        const val ENTRY_KEY = "free_module_entry"
        const val MODULE_PACKAGE = "io.github.hoerire.free"
        const val MAIN_ACTIVITY_CLASS = "io.github.hoerire.free.MainActivity"
        const val TITLE = "Free"

        /** 读不到系统图标尺寸时的兜底边长（dp）。 */
        const val ICON_SIZE_DP = 24

        /** 取包围盒前的绘制尺寸（dp），留足精度。 */
        const val ICON_RAW_DP = 96
        const val BASE_PREFERENCE_CLASS = "androidx.preference.Preference"
    }
}
