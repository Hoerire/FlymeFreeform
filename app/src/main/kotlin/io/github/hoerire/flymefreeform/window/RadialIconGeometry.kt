package io.github.hoerire.flymefreeform.window

/** 扇形与图标共享屏幕比例；条目数量只改变角度，不改变半径。 */
internal object RadialIconGeometry {
    private const val BASE_SHORT_EDGE_DP = 400f
    private const val BASE_RADIUS_DP = 242f
    /** 44 dp 基准；设置页「图标大小」默认值与面板图标缩放的比例基准。 */
    const val BASE_ICON_DIAMETER_DP = 44f
    private const val BASE_ITEM_PADDING_DP = 3.25f

    /**
     * 命中半径的兜底倍数（设置页没给值时才用）：2.5 倍图标直径，本机约 350px。
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
     * [SELECTION_ENTER_FACTOR] 倍图标直径的历史行为（本机 350px）。
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
    ): RadialVisualMetrics {
        require(width.isFinite() && width > 0f && height.isFinite() && height > 0f)
        require(density.isFinite() && density > 0f)
        require(itemCount in 1..7)
        val safeWidth = (width - safeInsets.left - safeInsets.right).coerceAtLeast(0f)
        val safeHeight = (height - safeInsets.top - safeInsets.bottom).coerceAtLeast(0f)
        val windowScale = (minOf(width, height) / density) / BASE_SHORT_EDGE_DP
        val pixelsPerBaseDp = density * windowScale
        // 为整个四分之一圆弧保留同一外缘，避免增删条目时安全区适配改变半径。
        // 外缘包含入场回摆和选中外圈；选中不改变图标大小。
        val iconDp = iconSizeDp.coerceIn(24f, 72f)
        // 图标变大时半径同比外推，保证 6 格在圆弧上仍不互相贴住。
        val radiusDp = BASE_RADIUS_DP + (iconDp - BASE_ICON_DIAMETER_DP) / 2f
        val requestedExtent =
            (radiusDp + iconDp * 1.05f / 2f + BASE_ITEM_PADDING_DP +
                RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP) * pixelsPerBaseDp
        val fitScale = minOf(1f, safeWidth / requestedExtent, safeHeight / requestedExtent)
        val unit = pixelsPerBaseDp * fitScale
        val diameter = iconDp * unit
        val radius = radiusDp * unit
        val enterRadius =
            if (hitRadiusDp != null && hitRadiusDp > 0f) {
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
