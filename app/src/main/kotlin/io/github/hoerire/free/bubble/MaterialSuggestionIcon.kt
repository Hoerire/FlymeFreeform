package io.github.hoerire.free.bubble

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 即时建议用的 24dp 紧凑 Material 风格字形。
 *
 * 形状坐标固定在 24×24 的坐标系里，绘制时按 bounds 的最小边等比缩放并居中；
 * 颜色、路径、1.9f 描边与原实现逐项一致。
 */
internal class MaterialSuggestionIcon(val kind: Int) : Drawable() {
    companion object {
        const val FLASHLIGHT = 1
        const val MUSIC = 2
        const val INSTALL = 3
        const val LINK = 4
        const val PACKAGE = 5
        const val LOCATION = 6
        const val KEEP_AWAKE = 7
        const val PHONE = 8

        /** 字形设计坐标系的边长。 */
        private const val GLYPH_SIZE = 24f

        /** 淡色圆底半径。 */
        private const val BACKGROUND_RADIUS = 11.25f

        /** 描边宽度。 */
        private const val STROKE_WIDTH = 1.9f

        /** 圆底透明度系数（相对字形颜色）。 */
        private const val BACKGROUND_ALPHA = 0.16f

        /** 无固有尺寸：原实现继承 Drawable 的默认值，尺寸只由 bounds 决定。 */
        private const val NO_INTRINSIC_SIZE = -1

        private fun colorFor(kind: Int): Int = when (kind) {
            FLASHLIGHT -> Color.rgb(245, 166, 35)
            MUSIC -> Color.rgb(0, 150, 136)
            INSTALL -> Color.rgb(30, 136, 229)
            PACKAGE -> Color.rgb(239, 108, 0)
            LOCATION -> Color.rgb(229, 57, 53)
            KEEP_AWAKE -> Color.rgb(255, 179, 0)
            PHONE -> Color.rgb(67, 160, 71)
            LINK -> Color.rgb(25, 118, 210)
            else -> Color.rgb(25, 118, 210)
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private val color: Int = colorFor(kind)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    /** Java 侧字段名就是 alpha；这里改名，避开与 Drawable 的 setAlpha/getAlpha JVM 签名冲突。 */
    private var glyphAlpha = 255

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = STROKE_WIDTH
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        fillPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val bounds = this.bounds
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width(), bounds.height()) / GLYPH_SIZE
        val left = bounds.centerX() - 12f * scale
        val top = bounds.centerY() - 12f * scale
        val save = canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)

        backgroundPaint.color = withAlpha(color, Math.round(glyphAlpha * BACKGROUND_ALPHA))
        strokePaint.color = withAlpha(color, glyphAlpha)
        fillPaint.color = withAlpha(color, glyphAlpha)
        canvas.drawCircle(12f, 12f, BACKGROUND_RADIUS, backgroundPaint)

        when (kind) {
            FLASHLIGHT -> drawFlashlight(canvas)
            MUSIC -> drawMusic(canvas)
            INSTALL -> drawInstall(canvas)
            PACKAGE -> drawPackage(canvas)
            LOCATION -> drawLocation(canvas)
            KEEP_AWAKE -> drawKeepAwake(canvas)
            PHONE -> drawPhone(canvas)
            LINK -> drawLink(canvas)
            else -> drawLink(canvas)
        }
        canvas.restoreToCount(save)
    }

    private fun drawFlashlight(canvas: Canvas) {
        path.reset()
        path.moveTo(8.2f, 5.4f)
        path.lineTo(15.8f, 5.4f)
        path.lineTo(14.6f, 9.1f)
        path.lineTo(14.6f, 18.3f)
        path.quadTo(14.6f, 19.2f, 13.7f, 19.2f)
        path.lineTo(10.3f, 19.2f)
        path.quadTo(9.4f, 19.2f, 9.4f, 18.3f)
        path.lineTo(9.4f, 9.1f)
        path.close()
        canvas.drawPath(path, strokePaint)
        canvas.drawLine(9.4f, 9.1f, 14.6f, 9.1f, strokePaint)
    }

    private fun drawMusic(canvas: Canvas) {
        canvas.drawLine(10f, 7f, 17f, 5.5f, strokePaint)
        canvas.drawLine(10f, 7f, 10f, 16.1f, strokePaint)
        canvas.drawLine(17f, 5.5f, 17f, 14.4f, strokePaint)
        canvas.drawOval(RectF(6.3f, 15f, 10.1f, 18.2f), fillPaint)
        canvas.drawOval(RectF(13.3f, 13.3f, 17.1f, 16.5f), fillPaint)
    }

    private fun drawInstall(canvas: Canvas) {
        canvas.drawRoundRect(RectF(5.3f, 5.3f, 18.7f, 18.7f), 2.2f, 2.2f, strokePaint)
        canvas.drawLine(12f, 7.7f, 12f, 14f, strokePaint)
        canvas.drawLine(9.6f, 11.7f, 12f, 14.1f, strokePaint)
        canvas.drawLine(14.4f, 11.7f, 12f, 14.1f, strokePaint)
        canvas.drawLine(9.1f, 16.3f, 14.9f, 16.3f, strokePaint)
    }

    private fun drawLink(canvas: Canvas) {
        path.reset()
        path.moveTo(10.4f, 14.9f)
        path.lineTo(8.9f, 16.4f)
        path.cubicTo(7.5f, 17.8f, 5.3f, 17.8f, 3.9f, 16.4f)
        path.cubicTo(2.5f, 15f, 2.5f, 12.8f, 3.9f, 11.4f)
        path.lineTo(6.3f, 9f)
        path.cubicTo(7.7f, 7.6f, 9.9f, 7.6f, 11.3f, 9f)
        canvas.drawPath(path, strokePaint)
        path.reset()
        path.moveTo(13.6f, 9.1f)
        path.lineTo(15.1f, 7.6f)
        path.cubicTo(16.5f, 6.2f, 18.7f, 6.2f, 20.1f, 7.6f)
        path.cubicTo(21.5f, 9f, 21.5f, 11.2f, 20.1f, 12.6f)
        path.lineTo(17.7f, 15f)
        path.cubicTo(16.3f, 16.4f, 14.1f, 16.4f, 12.7f, 15f)
        canvas.drawPath(path, strokePaint)
        canvas.drawLine(8.7f, 12f, 15.3f, 12f, strokePaint)
    }

    private fun drawPackage(canvas: Canvas) {
        path.reset()
        path.moveTo(5.2f, 8.1f)
        path.lineTo(12f, 4.6f)
        path.lineTo(18.8f, 8.1f)
        path.lineTo(18.8f, 16f)
        path.lineTo(12f, 19.4f)
        path.lineTo(5.2f, 16f)
        path.close()
        canvas.drawPath(path, strokePaint)
        canvas.drawLine(5.5f, 8.2f, 12f, 11.5f, strokePaint)
        canvas.drawLine(18.5f, 8.2f, 12f, 11.5f, strokePaint)
        canvas.drawLine(12f, 11.5f, 12f, 19f, strokePaint)
        canvas.drawLine(8.5f, 6.4f, 15.3f, 9.9f, strokePaint)
    }

    private fun drawLocation(canvas: Canvas) {
        path.reset()
        path.moveTo(12f, 20f)
        path.cubicTo(10.6f, 18.2f, 6.6f, 14.2f, 6.6f, 10.5f)
        path.cubicTo(6.6f, 7.5f, 9f, 5.2f, 12f, 5.2f)
        path.cubicTo(15f, 5.2f, 17.4f, 7.5f, 17.4f, 10.5f)
        path.cubicTo(17.4f, 14.2f, 13.4f, 18.2f, 12f, 20f)
        path.close()
        canvas.drawPath(path, strokePaint)
        canvas.drawCircle(12f, 10.5f, 2f, strokePaint)
    }

    private fun drawKeepAwake(canvas: Canvas) {
        canvas.drawCircle(12f, 12f, 3.8f, strokePaint)
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val x1 = 12f + Math.cos(angle).toFloat() * 6.1f
            val y1 = 12f + Math.sin(angle).toFloat() * 6.1f
            val x2 = 12f + Math.cos(angle).toFloat() * 8.1f
            val y2 = 12f + Math.sin(angle).toFloat() * 8.1f
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
    }

    /** Material「call」听筒，实心。 */
    private fun drawPhone(canvas: Canvas) {
        path.reset()
        path.moveTo(6.62f, 10.79f)
        path.cubicTo(8.06f, 13.62f, 10.38f, 15.93f, 13.21f, 17.38f)
        path.lineTo(15.41f, 15.18f)
        path.cubicTo(15.68f, 14.91f, 16.08f, 14.82f, 16.43f, 14.94f)
        path.cubicTo(17.55f, 15.31f, 18.76f, 15.51f, 20f, 15.51f)
        path.cubicTo(20.55f, 15.51f, 21f, 15.96f, 21f, 16.51f)
        path.lineTo(21f, 20f)
        path.cubicTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
        path.cubicTo(10.61f, 21f, 3f, 13.39f, 3f, 4f)
        path.cubicTo(3f, 3.45f, 3.45f, 3f, 4f, 3f)
        path.lineTo(7.5f, 3f)
        path.cubicTo(8.05f, 3f, 8.5f, 3.45f, 8.5f, 4f)
        path.cubicTo(8.5f, 5.25f, 8.7f, 6.45f, 9.07f, 7.57f)
        path.cubicTo(9.18f, 7.92f, 9.1f, 8.31f, 8.82f, 8.59f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    override fun setAlpha(alpha: Int) {
        glyphAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        strokePaint.colorFilter = colorFilter
        fillPaint.colorFilter = colorFilter
        backgroundPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = NO_INTRINSIC_SIZE

    override fun getIntrinsicHeight(): Int = NO_INTRINSIC_SIZE
}
