package io.github.hoerire.free.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * 固有尺寸固定的图标 drawable。
 *
 * `BitmapDrawable` 的固有尺寸来自位图自身的 density，与运行环境的 densityDpi 不一致时
 * 会被 ImageView 再缩放一次，最终渲染尺寸既不是位图尺寸也不是系统条目的图标尺寸。
 * 这里把固有尺寸写死成系统条目图标的像素尺寸，绘制时把位图铺满 bounds，
 * 渲染结果只由布局给的 bounds 决定。
 */
internal class IconDrawable(private val bitmap: Bitmap, private val sidePx: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val target = Rect()

    override fun getIntrinsicWidth(): Int = sidePx

    override fun getIntrinsicHeight(): Int = sidePx

    override fun draw(canvas: Canvas) {
        target.set(bounds)
        canvas.drawBitmap(bitmap, null, target, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
