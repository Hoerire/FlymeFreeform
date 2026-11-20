package io.github.hoerire.free.bubble

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator

/**
 * 系统原生气泡展开样式。
 *
 * 对齐平台气泡栈的行为：主气泡后面折叠的那一叠会
 * 在屏幕上方展开成一行（不够时折成两行），
 * 背景压暗，动效按 Material 的错峰节奏推进。展开期间不再有
 * 持续动画；点某一颗打开对应通知/建议，点其它地方
 * 收回；一会儿不操作也会自动收回。
 */
@SuppressLint("ViewConstructor")
internal class BubbleStackOverlay(
    context: Context,
    icons: List<Drawable>,
    anchor: Rect,
    opacity: Float,
    private val clickListener: (Int) -> Unit,
    private val collapsedListener: () -> Unit,
) : View(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val icons: ArrayList<Drawable> = ArrayList(icons)

    private val scrim = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightBounds = RectF()
    private val clip = Path()

    private val anchorX: Float
    private val anchorY: Float
    private val mainRadius: Float
    private val mainOpacity: Float

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        setTitle("Bubble notification row")
    }

    private val idleCollapseAction = Runnable { dismiss() }
    private val insetPollAction = Runnable { updateInsetAvoidance() }

    private var animator: ValueAnimator? = null
    private var progress = 0f
    private var imeBottomInset = 0
    private var navigationBottomInset = 0
    private var attached = false
    private var collapsing = false
    private var pressedIndex = -1
    private var downX = 0f
    private var downY = 0f

    init {
        anchorX = anchor.centerX().toFloat()
        anchorY = anchor.centerY().toFloat()
        mainRadius = maxOf(dp(18f).toFloat(), minOf(anchor.width(), anchor.height()) / 2f)
        mainOpacity = maxOf(0.35f, minOf(1f, opacity))

        scrim.setColor(Color.BLACK)
        ring.setStyle(Paint.Style.STROKE)
        ring.setStrokeWidth(dp(1.5f).toFloat())
        highlight.setStyle(Paint.Style.STROKE)
        highlight.setStrokeWidth(dp(1.2f).toFloat())
        highlight.setStrokeCap(Paint.Cap.ROUND)
        pressPaint.setStyle(Paint.Style.FILL)
        pressPaint.setColor(Color.argb(40, 255, 255, 255))
    }

    fun show(): Boolean {
        val manager = windowManager
        if (icons.isEmpty() || manager == null) return false
        try {
            manager.addView(this, params)
            attached = true
        } catch (ignored: Throwable) {
            return false
        }
        animateTo(1f, ENTER_DURATION_MS)
        armIdleCollapse()
        updateInsetAvoidance()
        return true
    }

    /** 把这一行收回气泡堆（被替换时也走这里）。 */
    fun dismiss() {
        handler.removeCallbacks(idleCollapseAction)
        if (!attached || collapsing) return
        collapsing = true
        pressedIndex = -1
        animator?.cancel()
        val anim = ValueAnimator.ofFloat(progress, 0f)
        animator = anim
        anim.duration = EXIT_DURATION_MS
        anim.interpolator = MATERIAL_ACCELERATE
        anim.addUpdateListener { value ->
            progress = value.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                removeImmediately()
            }
        })
        anim.start()
    }

    private fun animateTo(target: Float, duration: Long) {
        animator?.cancel()
        val anim = ValueAnimator.ofFloat(progress, target)
        animator = anim
        anim.duration = duration
        anim.interpolator = if (target > progress) MATERIAL_DECELERATE else MATERIAL_ACCELERATE
        anim.addUpdateListener { value ->
            progress = value.animatedValue as Float
            invalidate()
        }
        anim.start()
    }

    private fun removeImmediately() {
        handler.removeCallbacksAndMessages(null)
        if (!attached) return
        attached = false
        try {
            windowManager!!.removeViewImmediate(this)
        } catch (ignored: Throwable) {
        }
        collapsedListener()
    }

    /**
     * 键盘（输入法）和导航条所占的高度。展开的一排气泡贴着屏幕下方，键盘弹出时必须整体上移，
     * 否则会被输入法挡住。
     */
    private fun updateInsetAvoidance() {
        if (!attached) return
        try {
            val metrics = windowManager!!.currentWindowMetrics
            val insets = metrics.windowInsets
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val imeVisible = insets.isVisible(WindowInsets.Type.ime()) && ime > dp(48f)
            val navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val changed = (if (imeVisible) ime else 0) != imeBottomInset ||
                navigation != navigationBottomInset
            imeBottomInset = if (imeVisible) ime else 0
            navigationBottomInset = navigation
            if (changed) invalidate()
        } catch (ignored: Throwable) {
        }
        handler.postDelayed(insetPollAction, 120L)
    }

    private fun armIdleCollapse() {
        handler.removeCallbacks(idleCollapseAction)
        handler.postDelayed(idleCollapseAction, IDLE_COLLAPSE_MS)
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = smooth(progress)
        // 压暗背景，与平台的展开视图一致。
        scrim.alpha = Math.round(112f * p)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)

        // 这一行接手时，源气泡淡出。
        drawSource(canvas, p)
        for (index in icons.size - 1 downTo 0) {
            drawItem(canvas, index)
        }
    }

    private fun drawSource(canvas: Canvas, p: Float) {
        val alpha = Math.round(255f * mainOpacity * (1f - p))
        if (alpha <= 4) return
        val radius = mainRadius * (1f - 0.16f * p)
        val layer = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
        fill.shader = null
        fill.color = surfaceColor()
        canvas.drawCircle(anchorX, anchorY, radius, fill)
        ring.color = outlineColor()
        canvas.drawCircle(anchorX, anchorY, radius - dp(1f), ring)
        canvas.restoreToCount(layer)
    }

    private fun drawItem(canvas: Canvas, index: Int) {
        val p = itemProgress(index)
        if (p <= 0.001f) return
        val target = dp(BUBBLE_DP) / 2f
        val radius = lerp(mainRadius, target, p)
        val x = lerp(anchorX, slotX(index), elastic(p))
        val y = lerp(anchorY, slotY(index), elastic(p))
        val pressed = index == pressedIndex
        val layer = canvas.saveLayerAlpha(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Math.round(255f * minOf(1f, p * 1.4f)),
        )
        val save = canvas.save()
        canvas.scale(if (pressed) 0.94f else 1f, if (pressed) 0.94f else 1f, x, y)

        shadow.shader = RadialGradient(
            x,
            y + dp(3f),
            radius + dp(8f),
            Color.argb(84, 0, 0, 0),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y + dp(3f), radius + dp(8f), shadow)
        shadow.shader = null
        fill.shader = null
        fill.color = surfaceColor()
        canvas.drawCircle(x, y, radius, fill)
        ring.color = outlineColor()
        canvas.drawCircle(x, y, radius - dp(1f), ring)
        if (pressed) canvas.drawCircle(x, y, radius, pressPaint)

        val icon: Drawable? = icons[index]
        if (icon != null) {
            BubbleIconRenderer.draw(canvas, icon, x, y, radius * 0.62f, clip)
        }
        highlightBounds.set(
            x - radius + dp(4f),
            y - radius + dp(4f),
            x + radius - dp(4f),
            y + radius - dp(4f),
        )
        highlight.color = Color.argb(145, 255, 255, 255)
        canvas.drawArc(highlightBounds, 205f, 74f, false, highlight)
        canvas.restoreToCount(save)
        canvas.restoreToCount(layer)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!attached || collapsing) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressedIndex = hitIndex(event.x, event.y)
                armIdleCollapse()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(event.x - downX) > dp(12f) ||
                    Math.abs(event.y - downY) > dp(12f)
                ) {
                    if (pressedIndex >= 0) {
                        pressedIndex = -1
                        invalidate()
                    }
                }
                armIdleCollapse()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val hit = hitIndex(event.x, event.y)
                val sameTarget = hit >= 0 && hit == pressedIndex
                pressedIndex = -1
                invalidate()
                if (sameTarget) {
                    clickListener(hit)
                }
                dismiss()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }

            else -> return true
        }
    }

    private fun hitIndex(x: Float, y: Float): Int {
        if (progress < 0.85f) return -1
        val radius = dp(BUBBLE_DP) / 2f + dp(4f)
        for (index in 0 until icons.size) {
            val dx = x - slotX(index)
            val dy = y - slotY(index)
            if (dx * dx + dy * dy <= radius * radius) return index
        }
        return -1
    }

    /** 错峰的条目进度：这一行从源气泡开始，一颗一颗地展开。 */
    private fun itemProgress(index: Int): Float {
        val delay = minOf(0.42f, 0.05f + index * 0.05f)
        val value = maxOf(0f, minOf(1f, (progress - delay) / (1f - delay)))
        return smooth(value)
    }

    private fun perRow(): Int {
        val step = (dp(BUBBLE_DP) + dp(GAP_DP)).toFloat()
        val fit = ((width - dp(20f)) / step).toInt()
        return maxOf(1, minOf(MAX_PER_ROW, fit))
    }

    /** 单行槽位的横向位置，与平台气泡行一样居中。 */
    private fun slotX(index: Int): Float {
        val per = perRow()
        val row = index / per
        val column = index % per
        val inRow = minOf(per, icons.size - row * per)
        val step = (dp(BUBBLE_DP) + dp(GAP_DP)).toFloat()
        return width / 2f + (column - (inRow - 1) / 2f) * step
    }

    private fun slotY(index: Int): Float {
        val per = perRow()
        val rows = maxOf(1, (icons.size + per - 1) / per)
        val row = index / per
        val step = (dp(BUBBLE_DP) + dp(12f)).toFloat()
        val height = if (this.height > 0) this.height.toFloat() else fallbackHeight()
        // 贴着导航栏上方；键盘弹起时整体上移，
        // 避免被输入法挡住。
        var bottom = height - navigationBottomInset - dp(ROW_BOTTOM_DP)
        if (imeBottomInset > 0) {
            bottom = minOf(bottom, height - imeBottomInset - dp(16f))
        }
        val lastCenter = bottom - dp(BUBBLE_DP) / 2f
        // 行从下往上叠：最后一行贴着屏幕底部。
        return lastCenter - (rows - 1 - row) * step
    }

    private fun fallbackHeight(): Float {
        return try {
            windowManager!!.currentWindowMetrics.bounds.height().toFloat()
        } catch (ignored: Throwable) {
            dp(800f).toFloat()
        }
    }

    private fun elastic(value: Float): Float {
        if (value <= 0f || value >= 1f) return value
        val recoil = 0.07f * Math.sin(Math.PI * value).toFloat() *
            Math.sin(Math.PI * 2.2f * value).toFloat()
        return maxOf(0f, minOf(1f, value + recoil))
    }

    private fun smooth(value: Float): Float {
        val t = maxOf(0f, minOf(1f, value))
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    private fun surfaceColor(): Int {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.rgb(49, 52, 61) else Color.rgb(247, 248, 252)
    }

    private fun outlineColor(): Int {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (dark) Color.rgb(84, 89, 102) else Color.rgb(225, 228, 235)
    }

    private fun dp(value: Float): Int {
        return Math.round(value * resources.displayMetrics.density)
    }

    companion object {
        private const val BUBBLE_DP = 58f
        private const val GAP_DP = 10f

        /** 这一行距屏幕底边的距离（在导航栏 / 键盘之上）。 */
        private const val ROW_BOTTOM_DP = 84f
        private const val MAX_PER_ROW = 5
        private const val ENTER_DURATION_MS = 400L
        private const val EXIT_DURATION_MS = 260L

        /** 这么久没有操作就把这一行收回气泡堆。 */
        private const val IDLE_COLLAPSE_MS = 4_200L
        private val MATERIAL_DECELERATE =
            PathInterpolator(0.05f, 0.70f, 0.10f, 1.00f)
        private val MATERIAL_ACCELERATE =
            PathInterpolator(0.30f, 0.00f, 0.80f, 0.15f)
    }
}
