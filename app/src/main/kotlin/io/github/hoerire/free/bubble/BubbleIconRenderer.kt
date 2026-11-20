package io.github.hoerire.free.bubble

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import java.util.WeakHashMap

/** 通过一层轻微凸起的玻璃透镜绘制通知图标。 */
internal object BubbleIconRenderer {
    private const val MESH = 8
    private val PAINT = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )
    private val CACHE = WeakHashMap<Drawable, MutableMap<Int, Bitmap>>()

    fun draw(
        canvas: Canvas,
        drawable: Drawable?,
        centerX: Float,
        centerY: Float,
        radius: Float,
        clip: Path,
    ) {
        if (drawable == null || radius <= 0f) return
        val size = maxOf(2, Math.round(radius * 2f))
        val bitmap = bitmapFor(drawable, size)
        val vertices = FloatArray((MESH + 1) * (MESH + 1) * 2)
        var offset = 0
        for (row in 0..MESH) {
            val ny = row * 2f / MESH - 1f
            for (column in 0..MESH) {
                val nx = column * 2f / MESH - 1f
                val distanceSquared = minOf(1f, nx * nx + ny * ny)
                val horizontalLens = 1f + 0.22f * (1f - distanceSquared)
                val verticalLens = 1f + 0.16f * (1f - distanceSquared)
                val centerBias = 1f - distanceSquared
                vertices[offset] = centerX + radius * nx * horizontalLens -
                    radius * 0.055f * centerBias
                offset += 1
                vertices[offset] = centerY + radius * ny * verticalLens -
                    radius * 0.035f * centerBias
                offset += 1
            }
        }

        clip.reset()
        clip.addCircle(centerX, centerY, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmapMesh(bitmap, MESH, MESH, vertices, 0, null, 0, PAINT)
        canvas.restoreToCount(save)
    }

    @Synchronized
    private fun bitmapFor(drawable: Drawable, size: Int): Bitmap {
        val sizes = CACHE.getOrPut(drawable) { HashMap() }
        val cached = sizes[size]
        if (cached != null && !cached.isRecycled) return cached
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        sizes[size] = bitmap
        return bitmap
    }
}
