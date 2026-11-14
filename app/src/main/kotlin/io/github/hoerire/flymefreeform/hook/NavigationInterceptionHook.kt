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
 * 系统手势识别**，模块自己的 gesture monitor 与浮层就能正常拿到；也就不需要
 * pilferPointers 去"抢流"。本模块此前一直用的是它的降级方案，抢流导致系统重发
 * DOWN + CANCEL、之后收不到 MOVE，才有反复出现的"扇形卡住"。
 *
 * 候选类与它的 collectInvalidRegionMethods 一致：类名硬编码，方法按名字规则匹配，
 * 非 native、且参数里带数组，才 hook。
 */
@SuppressLint("PrivateApi")
internal class NavigationInterceptionHook(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private var firstHitReported = false

    /** 最近一次系统调用 updateInvalidRegion 的原始参数，用于设置变更后重放。 */
    @Volatile private var lastCall: LastCall? = null

    /** 重放期间置位：避免重放再一次进入自己的 hook。 */
    @Volatile private var replaying = false

    private data class LastCall(
        val method: Method,
        val target: Any?,
        val args: List<Any?>,
    )

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
            return
        }
        // 系统只在自己需要的时候才调 updateInvalidRegion；用户改「触发范围」不会触发它，
        // 挖角就会停在旧值。这里监听配置变化，用新范围重放一次。
        configuration.observe { settings ->
            if (settings.cornerTriggerRangeDp != lastAppliedRangeDp) {
                lastAppliedRangeDp = settings.cornerTriggerRangeDp
                ModuleLog.info("NAV_REGION_RANGE_CHANGED_" + lastAppliedRangeDp)
                replay()
            }
        }
    }

    @Volatile private var lastAppliedRangeDp = -1

    /** 矩形只打印一次。 */
    private val rectsLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 用当前设置重新算一遍挖角，并把结果交回系统。
     *
     * 调用的是系统自己的 updateInvalidRegion，参数用模块改过的列表——等价于
     * "让系统按新的范围再更新一次手势区域"。重放期间置 [replaying]，
     * 避免再次进入 hook 形成递归。
     */
    private fun replay() {
        val call = lastCall
        if (call == null) {
            ModuleLog.info("NAV_REGION_REPLAY_SKIPPED_NO_CALL")
            return
        }
        val patched =
            runCatching { carve(call.args) }
                .onFailure { ModuleLog.warn("NAV_REGION_REPLAY_CARVE_FAILED", it) }
                .getOrNull()
        // patched 为 null 有两种含义：范围没变（本来就不需要重放），
        // 或者当前环境不该挖角（横屏/游戏模式）——后者必须把原区域交回系统，
        // 否则挖角会一直留在旧值上，系统手势回不来。
        replaying = true
        try {
            if (patched != null) {
                call.method.invoke(call.target, *patched)
                ModuleLog.info("NAV_REGION_REPLAYED_" + lastAppliedRangeDp)
            } else {
                call.method.invoke(call.target, *call.args.toTypedArray())
                ModuleLog.info("NAV_REGION_RESTORED_" + lastAppliedRangeDp)
            }
        } catch (throwable: Throwable) {
            ModuleLog.warn("NAV_REGION_REPLAY_FAILED", throwable)
        } finally {
            replaying = false
        }
    }

    private fun hook(method: Method) {
        module
            .hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.nav_region_" + method.name)
            .intercept { chain ->
                if (replaying) return@intercept chain.proceed()
                if (!firstHitReported) {
                    firstHitReported = true
                    ModuleLog.info("NAV_REGION_FIRST_HIT_" + method.name)
                }
                // 记住原始参数：系统不会因为用户改设置而重新调用，重放时要用。
                lastCall = LastCall(method, chain.thisObject, chain.args)
                // libxposed 的 Chain.args 是只读 List：往里赋值会静默失败。
                // 改参数只能走 proceed(Object[]) 这个重载。
                val patched =
                    runCatching { carve(chain.args) }
                        .onFailure { ModuleLog.warn("NAV_REGION_CARVE_FAILED", it) }
                        .getOrNull()
                if (patched != null) chain.proceed(patched) else chain.proceed()
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

    /**
     * 在参数里找承载矩形的那一项，挖角后返回**新的参数数组**；没有变化时返回 null。
     *
     * 返回数组而不是改 args：Chain.args 只读，修改只能通过 proceed(newArgs) 生效。
     */
    private fun carve(args: List<Any?>): Array<Any?>? {
        val settings = configuration.snapshot
        if (!settings.enabled) return null
        if (!settings.leftCornerEnabled && !settings.rightCornerEnabled) return null
        val index = args.indexOfFirst { it is Array<*> || it is Collection<*> }
        if (index < 0) return null
        val original = args[index] ?: return null
        val rects = readRectangles(original) ?: return null
        if (rects.isEmpty()) return null
        val corners = cornerRects(rects, settings)
        if (corners.isEmpty()) return null
        var carved = rects
        for (corner in corners) carved = subtract(carved, corner)
        // 判"有没有真的改动"要比内容，不能比数量：
        // 一条竖长条减去底部角落之后还是一条，只是变短了，数量不变。
        // 之前用 size 比较，会把这种成功的挖角当成"没变化"直接丢掉。
        if (sameRects(carved, rects)) return null
        if (rectsLogged.compareAndSet(false, true)) {
            // 只打一次：确认系统交给模块的区域到底长什么样、挖完之后覆盖成什么样。
            ModuleLog.warn(
                "NAV_REGION_RECTS_" + rects.joinToString(";") { rect ->
                    rect.left.toInt().toString() + "," + rect.top.toInt() + "," +
                        rect.right.toInt() + "," + rect.bottom.toInt()
                } + "_TO_" + carved.joinToString(";") { rect ->
                    rect.left.toInt().toString() + "," + rect.top.toInt() + "," +
                        rect.right.toInt() + "," + rect.bottom.toInt()
                },
            )
        }
        val restored = writeRectangles(original, carved) ?: return null
        val patched = args.toTypedArray()
        patched[index] = restored
        ModuleLog.info("NAV_REGION_CARVED_" + rects.size + "_TO_" + carved.size)
        return patched
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
        // 挖角尺寸 = 模块的「角落触发范围」：判定认为属于角落的区域，就不该再交给
        // 系统导航手势处理，两者范围一致才不会出现"扇形起来了、最近任务也起来了"。
        //
        // 只影响系统导航手势（底部上滑、边缘返回），不影响点击、应用内滑动与拖拽：
        // 那些不经过 InputDispatcher 的手势判定表。代价是底部那一圈的边缘返回会失效。
        val sizeDp = settings.cornerTriggerRangeDp.coerceIn(24, 120)
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

    /** 两组矩形是否逐项相同（含尺寸）。 */
    private fun sameRects(first: List<RectF>, second: List<RectF>): Boolean {
        if (first.size != second.size) return false
        for (index in first.indices) {
            val a = first[index]
            val b = second[index]
            if (a.left != b.left || a.top != b.top || a.right != b.right || a.bottom != b.bottom) {
                return false
            }
        }
        return true
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
