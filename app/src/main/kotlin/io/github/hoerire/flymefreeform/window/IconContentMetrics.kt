package io.github.hoerire.flymefreeform.window

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader

/**
 * 图标位图处理。
 *
 * 只保留「裁圆」这一件事：绘制期用 clipPath 或 PorterDuff 蒙版都不稳定
 * （满底位图不裁、硬件位图会吞掉 Xfermode），因此在生成目录时直接把像素裁成圆，
 * 绘制层只负责缩放绘制。
 */
internal object IconContentMetrics {
    /** 裁圆结果缓存：同一位图只裁一次，避免每次呼出扇形都重做合成。 */
    private val circleCache = java.util.WeakHashMap<Bitmap, Bitmap>()

    fun circular(bitmap: Bitmap): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        circleCache[bitmap]?.let { return it }
        // 先放大到统一尺寸的软件位图，再用「圆当形状、位图当填充」裁圆。
        val size = maxOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(
                        size / bitmap.width.toFloat(),
                        size / bitmap.height.toFloat(),
                    )
                },
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        Canvas(output).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        circleCache[bitmap] = output
        return output
    }
}
