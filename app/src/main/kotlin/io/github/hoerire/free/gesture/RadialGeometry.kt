package io.github.hoerire.free.gesture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class GesturePoint(val x: Float, val y: Float)

internal data class RadialLayout(
    val side: CornerSide,
    val origin: GesturePoint,
    val radius: Float,
    val itemCenters: List<GesturePoint>,
)

internal object RadialGeometry {
    /** 扇形张开的总角度。图标半径的间距也按它反推（见 `RadialIconGeometry`）。 */
    internal const val SPAN_DEGREES = 84f
    /** 圆弧两端各让出的角度：布局与设置页预览共用。 */
    internal const val SAFE_DEGREES = 3f

    fun layout(
        side: CornerSide,
        width: Float,
        height: Float,
        radius: Float,
        itemCount: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): RadialLayout {
        val origin = GesturePoint(offsetX + if (side == CornerSide.Left) 0f else width, offsetY + height)
        if (itemCount <= 0) return RadialLayout(side, origin, radius, emptyList())
        val step = SPAN_DEGREES / itemCount
        val centers =
            List(itemCount) { index ->
                // “更多”占最靠近角落的 0 号槽；应用按列表顺序从外侧向角落排列，
                // 因此列表第一个应用使用最外侧的槽位，避免设置页与扇形视觉顺序相反。
                val slot = itemCount - index - 1
                val angle =
                    if (side == CornerSide.Left) {
                        -SAFE_DEGREES - (slot + 0.5f) * step
                    } else {
                        -180f + SAFE_DEGREES + (slot + 0.5f) * step
                    }
                val radians = angle * PI.toFloat() / 180f
                GesturePoint(
                    x = origin.x + cos(radians) * radius,
                    y = origin.y + sin(radians) * radius,
                )
            }
        return RadialLayout(side, origin, radius, centers)
    }

    fun selection(
        layout: RadialLayout,
        x: Float,
        y: Float,
        previous: Int?,
        enterRadius: Float,
        keepRadius: Float,
    ): Int? {
        if (layout.radius <= 0f) return null
        // 不再做弧线收边：只按「离哪个图标最近」判定，手指偏离弧线也能接着选。
        // keepRadius <= 0 表示没有保持圈：换格只看谁更近（两格中线处切换），不做迟滞。
        if (previous != null && keepRadius > 0f && previous in layout.itemCenters.indices) {
            val center = layout.itemCenters[previous]
            if (hypot(x - center.x, y - center.y) <= keepRadius) return previous
        }
        // 命中范围以「屏幕直角（扇形原点）」为基准：离原点的距离落在圆弧半径
        // ±enterRadius 的环带内就算命中，再按角度取最近的那一格。
        // 之前按「图标圆心」判距离，手指必须先精确滑到某个图标附近才响应，
        // 圈看起来很大、实际却要越过圈才选中。
        val fromOrigin = hypot(x - layout.origin.x, y - layout.origin.y)
        if (kotlin.math.abs(fromOrigin - layout.radius) > enterRadius) return null
        return layout.itemCenters
            .indices
            .minByOrNull { index ->
                val center = layout.itemCenters[index]
                hypot(x - center.x, y - center.y)
            }
    }

    fun polarAngle(layout: RadialLayout, point: GesturePoint): Float =
        atan2(point.y - layout.origin.y, point.x - layout.origin.x)
}
