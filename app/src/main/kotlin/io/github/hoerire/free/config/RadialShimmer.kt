package io.github.hoerire.free.config

/**
 * 选中流光的可调参数。
 *
 * 全部以整数存盘，含义见各字段；界面上按区间滑杆调。
 * 存成一整串而不是每个一个键：这组参数只在「选中流光」展开时才会改，
 * 拆开会让设置快照、读写与界面各多七份样板代码。
 */
internal data class RadialShimmer(
    /** 高光弧的扫掠速度（度/秒）。0 表示不转。 */
    val speedDegPerSecond: Int = DEFAULT_SPEED,
    /** 高光弧的亮度（百分比）。 */
    val arcAlphaPercent: Int = DEFAULT_ARC_ALPHA,
    /** 高光弧的宽度（内边距的百分比，100 即一个内边距宽）。 */
    val arcWidthPercent: Int = DEFAULT_ARC_WIDTH,
    /** 高光弧离图标外沿的间隙（内边距的百分比）。 */
    val arcGapPercent: Int = DEFAULT_ARC_GAP,
    /** 外发光亮度（百分比）。 */
    val glowAlphaPercent: Int = DEFAULT_GLOW_ALPHA,
    /** 外发光半径（图标直径的百分比）。 */
    val glowRadiusPercent: Int = DEFAULT_GLOW_RADIUS,
    /** 选中时图标的放大比例（百分比，0 表示不放大）。 */
    val selectionScalePercent: Int = DEFAULT_SELECTION_SCALE,
    /** 选中光圈的大小（百分比，相对图标外沿；100 即正好贴边）。 */
    val ringSizePercent: Int = DEFAULT_RING_SIZE,
) {
    fun encode(): String =
        listOf(
            speedDegPerSecond,
            arcAlphaPercent,
            arcWidthPercent,
            arcGapPercent,
            glowAlphaPercent,
            glowRadiusPercent,
            selectionScalePercent,
            ringSizePercent,
        ).joinToString(SEPARATOR)

    companion object {
        val SPEED_RANGE = 0..600
        val PERCENT_RANGE = 0..100
        val ARC_WIDTH_RANGE = 20..200
        val ARC_GAP_RANGE = 0..200
        val GLOW_RADIUS_RANGE = 40..160
        val SELECTION_SCALE_RANGE = 0..40
        /** 光圈大小：60%（贴图标里面）到 160%（明显外扩）。 */
        val RING_SIZE_RANGE = 60..160

        const val DEFAULT_SPEED = 210
        const val DEFAULT_ARC_ALPHA = 55
        const val DEFAULT_ARC_WIDTH = 90
        const val DEFAULT_ARC_GAP = 55
        const val DEFAULT_GLOW_ALPHA = 18
        const val DEFAULT_GLOW_RADIUS = 85
        const val DEFAULT_SELECTION_SCALE = 20
        const val DEFAULT_RING_SIZE = 100

        private const val SEPARATOR = ","

        fun decode(raw: String?): RadialShimmer {
            if (raw.isNullOrBlank()) return RadialShimmer()
            val parts = raw.split(SEPARATOR)
            // 老存档是 7 段（没有光圈大小），新存档 8 段；两种都要能读。
            if (parts.size !in 7..8) return RadialShimmer()
            val values = parts.map { it.trim().toIntOrNull() }
            if (values.any { it == null }) return RadialShimmer()
            fun at(index: Int, range: IntRange, fallback: Int): Int =
                values.getOrNull(index)?.let { coerce(it, range) } ?: fallback
            return RadialShimmer(
                speedDegPerSecond = at(0, SPEED_RANGE, DEFAULT_SPEED),
                arcAlphaPercent = at(1, PERCENT_RANGE, DEFAULT_ARC_ALPHA),
                arcWidthPercent = at(2, ARC_WIDTH_RANGE, DEFAULT_ARC_WIDTH),
                arcGapPercent = at(3, ARC_GAP_RANGE, DEFAULT_ARC_GAP),
                glowAlphaPercent = at(4, PERCENT_RANGE, DEFAULT_GLOW_ALPHA),
                glowRadiusPercent = at(5, GLOW_RADIUS_RANGE, DEFAULT_GLOW_RADIUS),
                selectionScalePercent = at(6, SELECTION_SCALE_RANGE, DEFAULT_SELECTION_SCALE),
                ringSizePercent = at(7, RING_SIZE_RANGE, DEFAULT_RING_SIZE),
            )
        }

        private fun coerce(value: Int, range: IntRange): Int = value.coerceIn(range.first, range.last)
    }
}
