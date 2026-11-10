package io.github.hoerire.flymefreeform.hook

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.RectF
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot
import io.github.hoerire.flymefreeform.log.ModuleLog
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.math.max
import kotlin.math.min

/**
 * 把底部两个角落从 ColorOS 的「导航拦截区域」里挖掉。
 *
 * 这是 FlymeSystemUITools（v744）在 ColorOS 15–17 上的**主路径**，不是它脚本里的
 * InputMonitor/pilfer 降级方案。它 hook 系统里名字含 invalid + region 的方法
 * （典型是 updateInvalidRegion），在参数中的区域数组里减去底部角落，再原样写回：
 *
 *     key    = 参数里的 display 键字符串
 *     list   = 参数里的矩形数组/集合
 *     carved = carveFlymeBottomCorners(list)   // 减去左右两个角
 *     args[n] = 写回
 *
 * 为什么这条路径重要：角落一旦被系统排除在导航拦截之外，那些触摸**根本不会进入
 * 系统手势识别**，我们自己的 gesture monitor 与浮层就能正常拿到；也就不需要
 * pilferPointers 去"抢流"。我们此前一直用的是它的降级方案，抢流导致系统重发
 * DOWN + CANCEL、之后收不到 MOVE，才有反复出现的"扇形卡住"。
 *
 * 候选类与它的 collectInvalidRegionMethods 一致：类名硬编码，方法按名字规则匹配，
 * 非 native、且参数里带数组，才 hook。
 */
@SuppressLint("PrivateApi")
internal class NavigationInterceptionHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private var firstHitReported = false

    fun install(classLoader: ClassLoader) {
        var installed = 0
        for (className in CANDIDATE_CLASSES) {
            val clazz =
                try {
                    classLoader.loadClass(className)
                } catch (throwable: Throwable) {
                    // 加载不到时把类名和异常类型都写出来，否则只有一句笼统的"不可用"。
                    ModuleLog.info(
                        "NAV_REGION_CLASS_MISSING_" + className +
                            "_" + throwable.javaClass.simpleName,
                    )
                    null
                } ?: continue
            for (method in clazz.declaredMethods) {
                if (!isRegionMethod(method)) continue
                val hooked =
                    try {
                        hook(method)
                        true
                    } catch (_: Throwable) {
                        false
                    }
                if (hooked) {
                    installed++
                    ModuleLog.info("NAV_REGION_HOOKED_" + className + "_" + method.name)
                }
            }
        }
        if (installed == 0) {
            // 与 v744 同样的降级语义：找不到目标就退回原来的 pilfer 路径。
            ModuleLog.info("NAV_REGION_TARGET_UNAVAILABLE_PILFER_REMAINS")
        }
    }

    private fun hook(method: Method) {
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.nav_region_" + method.name)
            .intercept { chain ->
                if (!firstHitReported) {
                    firstHitReported = true
                    ModuleLog.info("NAV_REGION_FIRST_HIT_" + method.name)
                }
                runCatching { carve(chain.args) }
                    .onFailure { ModuleLog.warn("NAV_REGION_CARVE_FAILED", it) }
                chain.proceed()
            }
    }

    /** 方法名规则与 v744 的 collectInvalidRegionMethods 一致。 */
    private fun isRegionMethod(method: Method): Boolean {
        if (Modifier.isNative(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        val name = method.name.lowercase()
        val byName =
            name == "updateinvalidregion" || (name.contains("invalid") && name.contains("region"))
        if (!byName) return false
        // ColorOS 17 的实际签名是 updateInvalidRegion(String, List, boolean, boolean, Bundle)：
        // 区域是 java.util.List，不是数组。只认 isArray 会导致一个方法都匹配不上。
        return method.parameterTypes.any { isRegionContainer(it) }
    }

    /** 承载矩形的参数类型：数组、List/Collection、Iterable。 */
    private fun isRegionContainer(type: Class<*>): Boolean =
        type.isArray ||
            Collection::class.java.isAssignableFrom(type) ||
            Iterable::class.java.isAssignableFrom(type)

    /** 在参数里找承载矩形的那一项，挖角后写回；找不到就原样放行。 */
    private fun carve(args: List<Any?>) {
        val settings = configuration.snapshot
        if (!settings.enabled) return
        if (!settings.leftCornerEnabled && !settings.rightCornerEnabled) return
        val index = args.indexOfFirst { it is Array<*> || it is Collection<*> }
        if (index < 0) return
        val original = args[index] ?: return
        val rects = readRectangles(original) ?: return
        if (rects.isEmpty()) return
        val corners = cornerRects(rects, settings)
        if (corners.isEmpty()) return
        var carved = rects
        for (corner in corners) carved = subtract(carved, corner)
        // 没有真的变少，说明这块区域本来就不覆盖角落：不写回，避免无谓扰动。
        if (carved.size == rects.size) return
        val restored = writeRectangles(original, carved) ?: return
        // chain.args 是只读视图时放弃写回，交给系统原样处理。
        val mutable = args as? MutableList<Any?> ?: return
        mutable[index] = restored
        ModuleLog.info("NAV_REGION_CARVED_" + rects.size + "_TO_" + carved.size)
    }

    private fun readRectangles(value: Any?): List<RectF>? =
        when (value) {
            is Array<*> -> value.mapNotNull(::toRect)
            is Collection<*> -> value.mapNotNull(::toRect)
            else -> null
        }

    private fun toRect(value: Any?): RectF? =
        when (value) {
            is RectF -> RectF(value)
            is Rect -> RectF(value)
            else -> null
        }

    /** 按原始容器类型写回，保持系统侧的类型假设。 */
    private fun writeRectangles(original: Any?, rects: List<RectF>): Any? =
        when (original) {
            is Array<*> -> {
                val component = original.javaClass.componentType ?: return null
                val array = java.lang.reflect.Array.newInstance(component, rects.size)
                rects.forEachIndexed { index, rect ->
                    val element =
                        if (Rect::class.java.isAssignableFrom(component)) {
                            Rect().also { rect.roundOut(it) }
                        } else {
                            RectF(rect)
                        }
                    java.lang.reflect.Array.set(array, index, element)
                }
                array
            }
            is Collection<*> -> {
                val preferRect = original.firstOrNull() is Rect
                val list = ArrayList<Any?>(rects.size)
                for (rect in rects) {
                    list.add(if (preferRect) Rect().also { rect.roundOut(it) } else RectF(rect))
                }
                list
            }
            else -> null
        }

    /**
     * 要挖掉的两个角落。
     *
     * 尺寸直接取模块自己的「角落触发范围」，和手势判定用同一套参数：
     * 判定认为属于角落的区域，就不该被系统导航手势拦截。
     */
    private fun cornerRects(
        rects: List<RectF>,
        settings: ModuleSettingsSnapshot,
    ): List<RectF> {
        val metrics = android.content.res.Resources.getSystem().displayMetrics
        var width = metrics.widthPixels.toFloat()
        var height = metrics.heightPixels.toFloat()
        for (rect in rects) {
            width = max(width, rect.right)
            height = max(height, rect.bottom)
        }
        if (width < 1f || height < 1f) return emptyList()
        // 挖角尺寸跟随 v744 的默认值（44dp，可配置范围 24–120）。
        // 不能用模块的 84dp 触发范围：那会把屏幕底部两侧挖得太宽，正常上滑回桌面也会失效。
        // 这里取 44，触发判定仍然是 84，两者不必相等——只需要让"在角落起手"不落进
        // 系统的 home 手势区即可。
        val sizeDp = CARVE_SIZE_DP.coerceIn(24, 120)
        val sizePx = (sizeDp * metrics.density).coerceAtLeast(1f)
        val widthPx = sizePx.coerceAtMost(width / 2f)
        val heightPx = sizePx.coerceAtMost(height / 2f)
        val bottom = height
        val top = height - heightPx
        val corners = ArrayList<RectF>(2)
        if (settings.leftCornerEnabled) corners.add(RectF(0f, top, widthPx, bottom))
        if (settings.rightCornerEnabled) corners.add(RectF(width - widthPx, top, width, bottom))
        return corners
    }

    /** 从一组矩形里减去一块，返回剩下的部分（最多裂成四块）。 */
    private fun subtract(source: List<RectF>, cut: RectF): List<RectF> {
        val result = ArrayList<RectF>(source.size + 2)
        for (rect in source) {
            val left = max(rect.left, cut.left)
            val top = max(rect.top, cut.top)
            val right = min(rect.right, cut.right)
            val bottom = min(rect.bottom, cut.bottom)
            if (left >= right || top >= bottom) {
                result.add(rect)
                continue
            }
            if (rect.top < top) result.add(RectF(rect.left, rect.top, rect.right, top))
            if (rect.bottom > bottom) result.add(RectF(rect.left, bottom, rect.right, rect.bottom))
            if (rect.left < left) result.add(RectF(rect.left, top, left, bottom))
            if (rect.right > right) result.add(RectF(right, top, rect.right, bottom))
        }
        return result
    }

    private companion object {
        /** 挖角尺寸（dp）；v744 的 flyme_coloros_gesture_range_width_dp 默认值。 */
        const val CARVE_SIZE_DP = 44

        /** 与 v744 的 hookNavigationInterceptionRegions 完全一致。 */
        val CANDIDATE_CLASSES =
            listOf(
                "com.android.server.wm.OplusWindowManagerService",
                "com.android.server.wm.OplusWindowManagerServiceEnhance",
                "com.android.server.input.InputManagerServiceExtImpl",
                "com.oplus.server.input.InputManagerServiceExtImpl",
            )
    }
}
