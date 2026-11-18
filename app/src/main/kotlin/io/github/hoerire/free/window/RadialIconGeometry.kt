package io.github.hoerire.free.window

import io.github.hoerire.free.gesture.RadialGeometry
import kotlin.math.PI
import kotlin.math.sin

/**
 * 扇形与图标共享屏幕比例。
 *
 * 条目数量主要改变角度；当槽位多到 84° 弧上排不下时（8 格），半径按弦长自动放大，
 * 保证图标之间始终留有 [ITEM_MIN_GAP_DP] 的缝隙。
 */
internal object RadialIconGeometry {
    private const val BASE_SHORT_EDGE_DP = 400f
    /** 基准半径：扇形布局与设置页预览共用。 */
    internal const val BASE_RADIUS_DP = 242f
    /** 44 dp 基准；设置页「图标大小」默认值与面板图标缩放的比例基准。 */
    const val BASE_ICON_DIAMETER_DP = 44f
    private const val BASE_ITEM_PADDING_DP = 3.25f
    /** 相邻两格在圆弧上至少留这么多空隙（dp），用来反推最小半径。 */
    private const val ITEM_MIN_GAP_DP = 5f

    /**
     * 命中半径的兜底倍数（设置页没给值时才用）：2.5 倍图标直径。
     * 不做弧线收边，只要离某个图标最近且在命中半径内就选中，手指偏出弧线也能接着选。
     */
    private const val SELECTION_ENTER_FACTOR = 2.5f

    /**
     * 保持半径。0 = 没有保持圈：只要手指离「当前格圆心」比离别的格更远，立刻换格，
     * 即真正意义上的「离开圆心就换」，不做任何迟滞。
     */
    private const val SELECTION_KEEP_RADIUS_PX = 0f

    /**
     * [hitRadiusDp] 是设置页「命中范围」的物理 dp 值（40..140），为空时回落到
     * [SELECTION_ENTER_FACTOR] 倍图标直径的缺省口径。
     *
     * 命中半径换算用 [unit]（随窗口缩放后的每 dp 像素数）而不是原始 density：
     * 圆环与图标都按 unit 排布，只有命中圈用 density 会让两者比例随屏幕尺寸漂移。
     */
    fun fit(
        width: Float,
        height: Float,
        density: Float,
        safeInsets: OverlaySafeInsets,
        itemCount: Int,
        hitRadiusDp: Float? = null,
        /** 设置页「图标大小」；缺省用 44 dp 基准。 */
        iconSizeDp: Float = BASE_ICON_DIAMETER_DP,
        /** 设置页「扇形大小」（百分比）：左右两侧共用，100 为基准半径。 */
        radiusPercent: Float = 100f,
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount in 1..8)
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val windowScale = (minOf(width, height) / density) / BASE_SHORT_EDGE_DP
        val pixelsPerBaseDp = density * windowScale
        // 为整个四分之一圆弧保留同一外缘，避免增删条目时安全区适配改变半径。
        // 外缘包含入场回摆和选中外圈；选中不改变图标大小。
        val iconDp = iconSizeDp.coerceIn(24f, 72f)
        // 相邻两格在圆弧上的弦长必须 ≥「图标直径 + 缝隙」，据此反推所需半径：
        // 图标变大时半径同比外推；条目变多（最多 7 个应用 + 「更多」= 8 格）时
        // 半径自动放大，否则 84° 弧上 8 个 44dp 图标会互相贴住。
        val stepRadians = RadialGeometry.SPAN_DEGREES / itemCount * PI.toFloat() / 180f
        val minRadiusDp = (iconDp + ITEM_MIN_GAP_DP) / (2f * sin(stepRadians / 2f))
        val baseRadiusDp =
            maxOf(BASE_RADIUS_DP + (iconDp - BASE_ICON_DIAMETER_DP) / 2f, minRadiusDp)
        // 「扇形大小」可以缩小，但不能小到格与格重叠。
        val radiusDp =
            maxOf(
                baseRadiusDp * (radiusPercent.coerceIn(60f, 150f) / 100f),
                minRadiusDp,
            )
        val requestedExtent =
            (radiusDp + iconDp * 1.05f / 2f + BASE_ITEM_PADDING_DP +
                RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP) * pixelsPerBaseDp
        val fitScale = minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
        val unit = pixelsPerBaseDp * fitScale
        val diameter = iconDp * unit
        val radius = radiusDp * unit
        val enterRadius =
            if (hitRadiusDp != null && hitRadiusDp > 0f) {
                // 命中圈按用户设定的 dp 直接换算成屏幕像素（density），不要跟着布局的
                // fitScale 一起缩：否则大屏/有安全区时会明显小于设置值，手指要越过圈才选中。
                hitRadiusDp * density
            } else {
                diameter * SELECTION_ENTER_FACTOR
            }
        val keepRadius = SELECTION_KEEP_RADIUS_PX
        return RadialVisualMetrics(
            radius = radius,
            plateDiameter = diameter,
            iconDiameter = diameter,
            selectionEnterRadius = enterRadius,
            selectionKeepRadius = keepRadius,
            itemPadding = BASE_ITEM_PADDING_DP * unit,
            pixelsPerBaseDp = unit,
        )
    }
}
