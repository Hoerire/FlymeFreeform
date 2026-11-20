package io.github.hoerire.free.window

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
 * 于是单独开这一个窗口：**覆盖整屏，只让出底部手势条**。扇形激活后整屏都属于模块——
 * 落在扇形上选格，落在扇形之外关闭扇形，都不会再穿透到下层应用。
 *
 * 不用 touchableRegion 裁主窗口的原因：两个成员都是 @hide，反射赋值不生效
 * （final 字段 + 系统侧不重算），所以直接用窗口尺寸来表达可触摸范围。
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
        /** 菜单整体落在屏幕下方这一块之内；只用于几何计算，与触摸窗口大小无关。 */
        const val HEIGHT_FRACTION = 0.5f

        /**
         * 创建这个触摸窗口的布局参数：从屏幕顶部一直到手势条上沿。
         *
         * 扇形一旦激活就等于进入模态：屏幕上的触摸先由模块接住，
         *   - 落在扇形上 → 选格；
         *   - 落在扇形之外 → 关闭扇形（见 [ColorOsFreeformCoordinator.handleTouchRegionEvent]），
         *     **不再落到下层应用**（只盖下半屏时，点上半屏会把点击/tap 送给应用）。
         * 底部手势条仍然让给系统，从底部上滑回桌面/进多任务照常可用。
         */
        fun layoutParams(
            context: Context,
            type: Int,
            gestureInsetPx: Int,
        ): WindowManager.LayoutParams {
            val metrics = context.resources.displayMetrics
            val height = (metrics.heightPixels - gestureInsetPx).coerceAtLeast(1)
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
                gravity = Gravity.TOP
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
                title = "FreeCornerTouch"
            }
        }
    }
}
