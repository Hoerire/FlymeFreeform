package io.github.hoerire.flymefreeform.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 只用来"接住"触摸的透明窗口。
 *
 * 主窗口 [CornerRadialOverlayView] 现在是 FLAG_NOT_TOUCHABLE，只负责画；点选必须有人接，
 * 于是单独开这一个窗口，尺寸＝菜单区域（屏幕下方一块，底部再让出手势条）。
 *
 * 为什么不用 touchableRegion 裁主窗口：那两个成员都是 @hide，实测反射赋值不生效
 * （final 字段 + 系统侧不重算），与其赌它，不如让窗口本身就只有菜单那么大——
 * 窗口之外天然不属于模块，底部手势也就不可能被吃掉。
 *
 * 这个 View 不绘制任何东西，只把触摸坐标转成屏幕坐标回调出去。
 */
@SuppressLint("ViewConstructor")
internal class CornerTouchRegionView(
    context: Context,
    private val onTouch: (event: MotionEvent, screenOffsetY: Int) -> Unit,
) : View(context) {
    var screenOffsetY: Int = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onTouch(event, screenOffsetY)
        return true
    }

    companion object {
        /** 菜单区域占屏幕高度的比例；扇形整体落在这块之内。 */
        const val HEIGHT_FRACTION = 0.5f

        /** 创建这个触摸窗口的布局参数：只覆盖下方一块，底部让出手势条。 */
        fun layoutParams(
            context: Context,
            type: Int,
            gestureInsetPx: Int,
        ): WindowManager.LayoutParams {
            val metrics = context.resources.displayMetrics
            val height =
                (metrics.heightPixels * HEIGHT_FRACTION).toInt()
                    .coerceAtLeast(1)
                    .coerceAtMost((metrics.heightPixels - gestureInsetPx).coerceAtLeast(1))
            return WindowManager.LayoutParams(
                metrics.widthPixels,
                height,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT,
            ).apply {
                gravity = Gravity.BOTTOM
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "FlymeFreeformCornerTouch"
            }
        }
    }
}
