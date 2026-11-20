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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** A compact Material notification panel that expands from and collapses into its bubble. */
@SuppressLint("ViewConstructor")
internal class NotificationPreviewView(
    context: Context,
    icon: Drawable?,
    appName: String?,
    title: String?,
    body: String?,
    alphaPercent: Int,
    autoWidth: Boolean,
    configuredWidthDp: Int,
    overlayAlphaPercent: Int,
) : LinearLayout(context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val panelDrawable: GradientDrawable
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeClip = Path()
    private val appView: TextView
    private val contentView: TextView
    private val autoWidth: Boolean = autoWidth
    private val configuredWidthDp: Int = configuredWidthDp
    private var overlayAlphaPercent: Int = Math.max(0, Math.min(70, overlayAlphaPercent))
    private val horizontalPadding: Int
    private var iconView: ImageView? = null

    /** Icon column width; recalculated per show so it follows the bubble-sized panel. */
    private var iconSlotWidth: Int = 0

    private val darkMode: Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    private val overlayBaseColor: Int
    private var finalAlpha: Float = 0f
    private var attached = false
    private var dismissing = false
    private var preferredPosition = BubbleMetrics.CONTENT_AUTO
    private var entranceAnimator: ValueAnimator? = null
    private var collapseAnimator: ValueAnimator? = null
    private var anchorX = 0
    private var anchorY = 0
    private var anchorSide = 0
    private val lastBubbleBounds = Rect()
    private val fluidCloudBounds = Rect()
    private val fluidCloudPollAction = Runnable { pollFluidCloudPosition() }
    private var currentCornerRadius = 0f
    private var shapeProgress = 1f
    private var particleProgress = 0f
    private var particleBurst = false
    private var dismissedAction: (() -> Unit)? = null

    /**
     * Java 字段名为 showError；Kotlin 里同时保留了 getShowError()，
     * 私有字段改名以免属性的访问器与函数在 JVM 上同名。
     */
    private var lastShowError: Throwable? = null

    private val timeoutAction = Runnable { dismiss() }

    init {
        overlayBaseColor = MaterialPalette.resolve(context).surfaceContainerHigh
        horizontalPadding = dp(10f)
        iconSlotWidth = if (icon == null) 0 else dp(44f)

        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(horizontalPadding, dp(7f), horizontalPadding, dp(7f))
        elevation = dp(8f).toFloat()
        setClickable(true)
        setFocusable(false)
        finalAlpha = clamp(alphaPercent, 35, 100) / 100f
        panelDrawable = panelBackground()
        background = panelDrawable
        highlightPaint.color = if (darkMode) {
            Color.argb(34, 255, 255, 255)
        } else {
            Color.argb(62, 255, 255, 255)
        }
        glassEdgePaint.style = Paint.Style.STROKE
        glassEdgePaint.strokeWidth = dp(1.05f).toFloat()
        glassEdgePaint.color = if (darkMode) {
            Color.argb(86, 205, 231, 242)
        } else {
            Color.argb(108, 255, 255, 255)
        }
        particlePaint.color = if (darkMode) {
            Color.argb(168, 153, 216, 242)
        } else {
            Color.argb(182, 230, 248, 255)
        }

        if (icon != null) {
            val createdIconView = ImageView(context)
            iconView = createdIconView
            var previewDrawable: Drawable = icon
            try {
                val constantState = icon.constantState
                if (constantState != null) {
                    previewDrawable = constantState.newDrawable().mutate()
                }
            } catch (ignored: Throwable) {
            }
            previewDrawable.alpha = 255
            createdIconView.setImageDrawable(previewDrawable)
            createdIconView.alpha = 1f
            createdIconView.scaleType = ImageView.ScaleType.CENTER_CROP
            val iconParams = LinearLayout.LayoutParams(dp(34f), dp(34f))
            iconParams.rightMargin = dp(10f)
            addView(createdIconView, iconParams)
        }

        val textColumn = LinearLayout(context)
        textColumn.orientation = LinearLayout.VERTICAL
        textColumn.gravity = Gravity.CENTER_VERTICAL
        appView = text(
            joinSenderAndApp(title, appName), 10f,
            if (darkMode) Color.rgb(183, 198, 210) else Color.rgb(80, 88, 96), true,
        )
        contentView = text(
            body ?: "", 13f,
            if (darkMode) Color.rgb(241, 245, 248) else Color.rgb(24, 28, 32), true,
        )
        val appParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        textColumn.addView(appView, appParams)
        val contentParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        contentParams.topMargin = dp(2f)
        textColumn.addView(contentView, contentParams)
        addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        params = WindowManager.LayoutParams(
            dp(54f), dp(54f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        // Position in display coordinates, like the bubble. Apply safe insets once,
        // in the placement solver, rather than letting WM offset this window again.
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.setTitle("Bubble notification content")
    }

    /** 配置变化时实时更新已显示浮层的不透明度（不透明度更低则更透出下层）。 */
    fun updateFinalAlpha(percent: Int) {
        finalAlpha = clamp(percent, 35, 100) / 100f
        if (dismissing || !attached) return
        params.alpha = finalAlpha
        updateLayout()
        if (entranceAnimator?.isRunning == true) return // 入场动画会自然收敛到新值
    }

    internal fun updateOverlayAlpha(percent: Int) {
        overlayAlphaPercent = Math.max(0, Math.min(70, percent))
        if (attached) invalidate()
    }

    /** Fit the contents without changing the height chosen by the placement solver. */
    private fun applyPanelMetrics(finalWidth: Int, finalHeight: Int) {
        val iconSize = if (iconView == null) {
            0
        } else {
            Math.min(dp(34f), Math.max(dp(16f), finalHeight - dp(10f)))
        }
        val icon = iconView
        if (icon != null) {
            val lp = icon.layoutParams as LinearLayout.LayoutParams
            if (lp.width != iconSize || lp.height != iconSize) {
                lp.width = iconSize
                lp.height = iconSize
                icon.layoutParams = lp
            }
        }
        iconSlotWidth = if (icon == null) 0 else iconSize + dp(10f)
        val available = Math.max(
            1,
            finalWidth - horizontalPadding * 2 - iconSlotWidth - dp(2f),
        )
        var appHeight = configureText(appView, appView.text.toString(), 10f, 10f, available, false)
        var contentHeight =
            configureText(contentView, contentView.text.toString(), 13f, 12f, available, true)
        // Keep the text block inside the bubble-sized panel: fall back to one line,
        // then proportionally shrink both lines if the content is still too tall.
        val innerHeight = Math.max(dp(18f), finalHeight - dp(4f))
        if (appHeight + dp(2f) + contentHeight > innerHeight) {
            contentHeight =
                configureText(contentView, contentView.text.toString(), 13f, 11f, available, false)
        }
        if (appHeight + dp(2f) + contentHeight > innerHeight) {
            val ratio = Math.max(
                0.78f,
                innerHeight.toFloat() / (appHeight + dp(2f) + contentHeight),
            )
            appView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * ratio)
            contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * ratio)
            appView.setSingleLine(true)
            contentView.setSingleLine(true)
            contentView.maxLines = 1
            contentView.ellipsize = TextUtils.TruncateAt.END
            appHeight = appView.lineHeight
            contentHeight = contentView.lineHeight
        }
        val verticalPadding = Math.max(
            0,
            Math.min(dp(7f), (finalHeight - appHeight - dp(2f) - contentHeight) / 2),
        )
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
    }

    fun show(bubbleBounds: Rect, preferredPosition: Int, timeoutSeconds: Int): Boolean {
        if (attached || windowManager == null) return false
        this.preferredPosition = preferredPosition
        val placement = resolvePlacement(bubbleBounds) ?: return false
        val finalRect = placement.bounds
        applyPanelMetrics(finalRect.width(), finalRect.height())
        lastBubbleBounds.set(bubbleBounds)
        val startSize = dp(6f)
        anchorX = bubbleBounds.centerX()
        anchorY = bubbleBounds.centerY()
        anchorSide = placement.side
        params.x = anchorX - startSize / 2
        params.y = anchorY - startSize / 2
        params.width = startSize
        params.height = startSize
        currentCornerRadius = startSize / 2f
        panelDrawable.setCornerRadius(currentCornerRadius)
        setChildrenAlpha(0f)
        alpha = 0f
        params.alpha = 1f
        try {
            windowManager.addView(this, params)
            attached = true
        } catch (error: Throwable) {
            lastShowError = error
            return false
        }

        val entrance = ValueAnimator.ofFloat(0f, 1f)
        entrance.duration = 330L
        entranceAnimator = entrance
        entrance.addUpdateListener { animator ->
            val progress = animator.animatedValue as Float
            val farProgress = easeOutCubic(clamp(progress / 0.72f, 0f, 1f))
            val nearProgress = easeOutCubic(clamp((progress - 0.16f) / 0.84f, 0f, 1f))
            val secondaryProgress = easeOutCubic(clamp(progress / 0.86f, 0f, 1f))
            applyAnchoredRect(finalRect, farProgress, nearProgress, secondaryProgress, true)
            val heightProgress = easeOutCubic(clamp(progress / 0.82f, 0f, 1f))
            currentCornerRadius = lerp(
                startSize / 2f, dp(PANEL_RADIUS_DP).toFloat(), heightProgress,
            )
            shapeProgress = clamp(progress / 0.34f, 0f, 1f)
            panelDrawable.setCornerRadius(currentCornerRadius)
            val stretch = Math.sin(Math.PI * Math.min(1f, progress * 1.12f)).toFloat()
            scaleX = 1f + 0.045f * stretch
            scaleY = 1f - 0.035f * stretch
            alpha = finalAlpha * clamp(progress / 0.38f, 0f, 1f)
            params.alpha = 1f
            setChildrenAlpha(clamp((progress - 0.2f) / 0.58f, 0f, 1f))
            updateLayout()
        }
        entrance.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    entranceAnimator = null
                    if (!attached || dismissing) return
                    scaleX = 1f
                    scaleY = 1f
                    updateRestingPlacement()
                }
            },
        )
        entrance.start()
        if (timeoutSeconds > 0) {
            handler.postDelayed(timeoutAction, timeoutSeconds * 1000L)
        }
        handler.removeCallbacks(fluidCloudPollAction)
        handler.postDelayed(fluidCloudPollAction, 320L)
        return true
    }

    /** Keeps the panel anchored to a moving bubble while the IME changes its Y. */
    fun updateAnchor(bubbleBounds: Rect?) {
        if (!attached || dismissing || bubbleBounds == null) return
        val changed = lastBubbleBounds != bubbleBounds
        lastBubbleBounds.set(bubbleBounds)
        if (changed && entranceAnimator == null) updateRestingPlacement()
    }

    private fun updateRestingPlacement() {
        val placement = resolvePlacement(lastBubbleBounds)
        visibility = if (placement == null) View.INVISIBLE else View.VISIBLE
        if (placement == null) return
        val bounds = placement.bounds
        anchorSide = placement.side
        anchorX = lastBubbleBounds.centerX()
        anchorY = lastBubbleBounds.centerY()
        val resized = params.width != bounds.width() || params.height != bounds.height()
        if (!resized && params.x == bounds.left && params.y == bounds.top) {
            // The entrance animation already delivered the final window bounds.
            currentCornerRadius =
                Math.min(dp(PANEL_RADIUS_DP).toFloat(), bounds.height() / 2f)
            panelDrawable.setCornerRadius(currentCornerRadius)
            return
        }
        if (resized) applyPanelMetrics(bounds.width(), bounds.height())
        currentCornerRadius = Math.min(dp(PANEL_RADIUS_DP).toFloat(), bounds.height() / 2f)
        panelDrawable.setCornerRadius(currentCornerRadius)
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        updateLayout()
    }

    fun getShowError(): Throwable? = lastShowError

    fun setOnPreviewClickListener(action: (() -> Unit)?) {
        setOnClickListener {
            action?.invoke()
        }
    }

    fun setOnDismissedListener(action: (() -> Unit)?) {
        dismissedAction = action
    }

    fun dismiss() {
        if (!attached || dismissing) return
        dismissing = true
        handler.removeCallbacks(timeoutAction)
        entranceAnimator?.cancel()
        val startChildrenAlpha = if (childCount == 0) 1f else getChildAt(0).alpha
        val startAlpha = alpha
        val startRect = Rect(
            params.x, params.y,
            params.x + params.width, params.y + params.height,
        )
        val startRadius = currentCornerRadius

        val collapse = ValueAnimator.ofFloat(0f, 1f)
        collapse.duration = 270L
        collapseAnimator = collapse
        collapse.addUpdateListener { animator ->
            val progress = animator.animatedValue as Float
            val nearSuction = easeInOut(clamp(progress / 0.60f, 0f, 1f))
            val farSuction = easeInOut(clamp((progress - 0.16f) / 0.84f, 0f, 1f))
            val secondarySuction = easeInOut(progress)
            applyAnchoredRect(startRect, farSuction, nearSuction, secondarySuction, false)
            val shape = Math.pow(progress.toDouble(), 1.12f.toDouble()).toFloat()
            currentCornerRadius = lerp(startRadius, dp(6f) / 2f, shape)
            shapeProgress = 1f - clamp(progress / 0.32f, 0f, 1f)
            panelDrawable.setCornerRadius(currentCornerRadius)
            val recoil = Math.sin(Math.PI * Math.min(1f, progress * 1.15f)).toFloat()
            scaleX = 1f - 0.04f * recoil
            scaleY = 1f + 0.05f * recoil
            setChildrenAlpha(startChildrenAlpha * (1f - clamp(progress / 0.56f, 0f, 1f)))
            alpha = startAlpha * (1f - clamp((progress - 0.18f) / 0.82f, 0f, 1f))
            updateLayout()
        }
        collapse.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    scaleX = 1f
                    scaleY = 1f
                    removeImmediately()
                }
            },
        )
        collapse.start()
    }

    fun removeImmediately() {
        handler.removeCallbacks(timeoutAction)
        handler.removeCallbacks(fluidCloudPollAction)
        val entrance = entranceAnimator
        if (entrance != null) {
            entrance.removeAllListeners()
            entrance.cancel()
            entranceAnimator = null
        }
        val collapse = collapseAnimator
        if (collapse != null) {
            collapse.removeAllListeners()
            collapse.cancel()
            collapseAnimator = null
        }
        if (!attached) return
        try {
            windowManager.removeViewImmediate(this)
        } catch (ignored: Throwable) {
        }
        attached = false
        scaleX = 1f
        scaleY = 1f
        val callback = dismissedAction
        dismissedAction = null
        callback?.invoke()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        applyMorphClip(canvas)
        if (overlayAlphaPercent > 0 && width > 1 && height > 1) {
            overlayPaint.style = Paint.Style.FILL
            val color = overlayBaseColor
            overlayPaint.color = Color.argb(
                Math.round(255f * overlayAlphaPercent / 100f),
                Color.red(color), Color.green(color), Color.blue(color),
            )
            val inset = dp(1.2f).toFloat()
            val radius = Math.max(1f, currentCornerRadius - inset)
            canvas.drawRoundRect(
                inset, inset, width - inset,
                height - inset, radius, radius, overlayPaint,
            )
        }
        super.dispatchDraw(canvas)
        if (width > dp(24f) && height > dp(20f)) {
            val inset = dp(1.05f).toFloat()
            val radius = Math.max(1f, currentCornerRadius - inset)
            // One restrained glass edge reflection replaces the old separated
            // red/cyan strokes and the distant white sheen.
            canvas.drawRoundRect(
                inset, inset, width - inset,
                height - inset, radius, radius, glassEdgePaint,
            )
        }
        canvas.restoreToCount(save)
    }

    /** Morphs the near edge from a pointed, horizontal wedge into the full panel. */
    private fun applyMorphClip(canvas: Canvas) {
        if (shapeProgress >= 0.999f || width <= 1 || height <= 1) return
        val halfW = width * 0.5f * shapeProgress
        val halfH = height * 0.5f * shapeProgress
        val cx = width * 0.5f
        val cy = height * 0.5f
        shapeClip.reset()
        if (anchorSide == SIDE_LEFT) {
            shapeClip.moveTo(0f, 0f)
            shapeClip.lineTo(0f, height.toFloat())
            shapeClip.lineTo(width - halfH, cy)
        } else if (anchorSide == SIDE_RIGHT) {
            shapeClip.moveTo(width.toFloat(), 0f)
            shapeClip.lineTo(width.toFloat(), height.toFloat())
            shapeClip.lineTo(halfH, cy)
        } else if (anchorSide == SIDE_ABOVE) {
            shapeClip.moveTo(0f, 0f)
            shapeClip.lineTo(width.toFloat(), 0f)
            shapeClip.lineTo(cx, height - halfW)
        } else {
            shapeClip.moveTo(0f, height.toFloat())
            shapeClip.lineTo(cx, halfW)
            shapeClip.lineTo(width.toFloat(), height.toFloat())
        }
        shapeClip.close()
        canvas.clipPath(shapeClip)
    }

    private fun drawMicroBubbles(canvas: Canvas) {
        if (particleProgress <= 0f || (particleProgress >= 1f && !particleBurst)) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        for (i in 0 until 4) {
            val angle = (i * Math.PI * 0.5 + 0.35).toFloat()
            val startRadius = Math.min(width, height) * 0.28f
            val t = if (particleBurst) particleProgress else 1f - particleProgress
            val distance = if (particleBurst) startRadius * t else startRadius * t
            val x = cx + Math.cos(angle.toDouble()).toFloat() * distance
            val y = cy + Math.sin(angle.toDouble()).toFloat() * distance
            val radius = dp(4.2f + i * 0.9f) * (if (particleBurst) {
                1f - particleProgress * 0.35f
            } else {
                0.72f + particleProgress * 0.45f
            })
            particlePaint.alpha = Math.round(
                if (particleBurst) {
                    235f * (1f - particleProgress)
                } else {
                    220f * (1f - particleProgress)
                },
            )
            canvas.drawCircle(x, y, radius, particlePaint)
        }
    }

    private fun desiredPanelWidth(): Int {
        if (!autoWidth) return dp(configuredWidthDp.toFloat())
        // Measure at the original type sizes, independent of the previous fitted layout.
        val textPaint = Paint(appView.paint)
        textPaint.setTextSize(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                10f, resources.displayMetrics,
            ),
        )
        val appWidth = textPaint.measureText(appView.text.toString())
        textPaint.set(contentView.paint)
        textPaint.setTextSize(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                13f, resources.displayMetrics,
            ),
        )
        val bodyWidth = textPaint.measureText(contentView.text.toString())
        return clamp(
            Math.round(Math.max(appWidth, bodyWidth)) + horizontalPadding * 2 +
                (if (iconView == null) 0 else dp(44f)) + dp(8f),
            dp(180f), dp(360f),
        )
    }

    /**
     * 配置浮层文字：
     * 1. 字号不会小于 minimumSp，避免过小；
     * 2. 一行放不下且允许扩展时，自动增加至两行；
     * 3. 仍放不下时使用省略号；
     * 4. 返回实际需要的行高（dp）。
     */
    private fun configureText(
        view: TextView,
        text: String,
        preferredSp: Float,
        minimumSp: Float,
        availableWidth: Int,
        canExpand: Boolean,
    ): Int {
        view.text = text
        view.textScaleX = 1f
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferredSp)
        view.setSingleLine(true)
        view.maxLines = 1
        view.ellipsize = null

        var measured = view.paint.measureText(text)
        val oneLineHeight = view.lineHeight

        if (measured <= availableWidth.toFloat() || measured <= 0f) {
            return oneLineHeight
        }

        if (canExpand) {
            // 尝试两行显示，字号不压缩
            view.setSingleLine(false)
            view.maxLines = 2
            view.ellipsize = TextUtils.TruncateAt.END
            view.measure(
                View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return view.measuredHeight
        }

        // 不允许扩展：仅按最小字号压缩，仍超长则省略
        val scaledSp = Math.max(minimumSp, preferredSp * availableWidth / measured)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp)
        measured = view.paint.measureText(text)
        if (measured > availableWidth.toFloat()) {
            view.ellipsize = TextUtils.TruncateAt.END
        }
        return view.lineHeight
    }

    private class Placement(val bounds: Rect, val side: Int)

    /** Choose a complete candidate; never clamp away alignment or the rim gap. */
    private fun resolvePlacement(anchor: Rect): Placement? {
        if (anchor.isEmpty) return null
        val metrics = windowManager.currentWindowMetrics
        val screen = metrics.bounds
        val insets = metrics.windowInsets
        val bars = insets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
        val margin = dp(10f)
        val safe = Rect(
            screen.left + bars.left + margin, screen.top + bars.top + margin,
            screen.right - bars.right - margin, screen.bottom - bottom - margin,
        )
        if (safe.isEmpty) return null
        val height = anchor.height()
        val gap = Math.round(height / 4f)
        val inward = if (anchor.centerX() < safe.centerX()) SIDE_RIGHT else SIDE_LEFT
        val outward = if (inward == SIDE_RIGHT) SIDE_LEFT else SIDE_RIGHT
        val order = when (preferredPosition) {
            BubbleMetrics.CONTENT_ABOVE ->
                intArrayOf(SIDE_ABOVE, SIDE_BELOW, inward, outward)
            BubbleMetrics.CONTENT_BELOW ->
                intArrayOf(SIDE_BELOW, SIDE_ABOVE, inward, outward)
            else -> intArrayOf(inward, outward, SIDE_ABOVE, SIDE_BELOW)
        }
        val cloud = findFluidCloudBounds()
        val desiredWidth = desiredPanelWidth()
        val minimumWidth = dp(80f)
        for (side in order) {
            val sideBySide = side == SIDE_LEFT || side == SIDE_RIGHT
            val y: Int
            val room: Int
            if (sideBySide) {
                y = anchor.top
                room = if (side == SIDE_RIGHT) {
                    safe.right - anchor.right - gap
                } else {
                    anchor.left - gap - safe.left
                }
            } else {
                y = if (side == SIDE_ABOVE) anchor.top - gap - height else anchor.bottom + gap
                room = safe.width()
            }
            if (room < minimumWidth || y < safe.top || y + height > safe.bottom) continue
            val width = Math.min(desiredWidth, room)
            val x = if (side == SIDE_RIGHT) {
                anchor.right + gap
            } else if (side == SIDE_LEFT) {
                anchor.left - gap - width
            } else {
                clamp(anchor.centerX() - width / 2, safe.left, safe.right - width)
            }
            val bounds = Rect(x, y, x + width, y + height)
            if (!safe.contains(bounds) || (cloud != null && Rect.intersects(bounds, cloud))) {
                continue
            }
            return Placement(bounds, side)
        }
        return null
    }

    /** Fluid Cloud or the IME can change without moving the bubble itself. */
    private fun pollFluidCloudPosition() {
        if (!attached || dismissing) return
        if (entranceAnimator == null) updateRestingPlacement()
        handler.postDelayed(fluidCloudPollAction, 360L)
    }

    /**
     * ColorOS fluid-cloud implementations vary by release and plugin. They
     * are all hosted as visible SystemUI views with capsule/fluid/seedling
     * names, so use their real screen bounds instead of a hard-coded notch
     * height. Hidden or tiny helper views are ignored.
     */
    private fun findFluidCloudBounds(): Rect? {
        fluidCloudBounds.setEmpty()
        try {
            val globalClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstance = globalClass.getMethod("getInstance")
            val getWindowViews = globalClass.getMethod("getWindowViews")
            val global = getInstance.invoke(null)
            val roots = if (global == null) null else getWindowViews.invoke(global)
            if (roots !is List<*>) return null
            for (root in roots) {
                if (root !is View) continue
                val found = findFluidCloudInView(root)
                if (found != null && found.width() * found.height() >
                    fluidCloudBounds.width() * fluidCloudBounds.height()
                ) {
                    fluidCloudBounds.set(found)
                }
            }
        } catch (ignored: Throwable) {
        }
        return if (fluidCloudBounds.isEmpty) null else Rect(fluidCloudBounds)
    }

    private fun findFluidCloudInView(view: View?): Rect? {
        if (view == null || view === this || view.visibility != View.VISIBLE ||
            view.alpha < 0.05f || view.width <= 0 || view.height <= 0
        ) {
            return null
        }
        var own: Rect? = null
        var identity = view.javaClass.name.lowercase(Locale.ROOT)
        try {
            val id = view.id
            if (id != View.NO_ID && id != 0) {
                val entry = resources.getResourceEntryName(id)
                if (entry != null) identity += " " + entry.lowercase(Locale.ROOT)
            }
        } catch (ignored: Throwable) {
        }
        val tag = view.tag
        if (tag != null) identity += " " + tag.toString().lowercase(Locale.ROOT)
        val description = view.contentDescription
        if (description != null) {
            identity += " " + description.toString().lowercase(Locale.ROOT)
        }
        val capsuleName = identity.contains("fluid") || identity.contains("capsule") ||
            identity.contains("seedling") || identity.contains("mini_capsule") ||
            identity.contains("smartcapsule")
        if (capsuleName && view.height <= dp(180f)) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            own = Rect(
                location[0], location[1],
                location[0] + view.width, location[1] + view.height,
            )
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = findFluidCloudInView(view.getChildAt(i))
                val best = own
                if (child != null && (best == null ||
                        child.width() * child.height() > best.width() * best.height())
                ) {
                    own = child
                }
            }
        }
        return own
    }

    private fun applyAnchoredRect(
        target: Rect,
        farProgress: Float,
        nearProgress: Float,
        secondaryProgress: Float,
        releasing: Boolean,
    ) {
        val left: Int
        val right: Int
        val top: Int
        val bottom: Int
        if (releasing) {
            left = lerp(
                anchorX, target.left,
                if (anchorSide == SIDE_LEFT) {
                    farProgress
                } else if (anchorSide == SIDE_RIGHT) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
            right = lerp(
                anchorX, target.right,
                if (anchorSide == SIDE_RIGHT) {
                    farProgress
                } else if (anchorSide == SIDE_LEFT) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
            top = lerp(
                anchorY, target.top,
                if (anchorSide == SIDE_ABOVE) {
                    farProgress
                } else if (anchorSide == SIDE_BELOW) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
            bottom = lerp(
                anchorY, target.bottom,
                if (anchorSide == SIDE_BELOW) {
                    farProgress
                } else if (anchorSide == SIDE_ABOVE) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
        } else {
            left = lerp(
                target.left, anchorX,
                if (anchorSide == SIDE_LEFT) {
                    farProgress
                } else if (anchorSide == SIDE_RIGHT) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
            right = lerp(
                target.right, anchorX,
                if (anchorSide == SIDE_RIGHT) {
                    farProgress
                } else if (anchorSide == SIDE_LEFT) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
            top = lerp(
                target.top, anchorY,
                if (anchorSide == SIDE_ABOVE) {
                    farProgress
                } else if (anchorSide == SIDE_BELOW) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
            bottom = lerp(
                target.bottom, anchorY,
                if (anchorSide == SIDE_BELOW) {
                    farProgress
                } else if (anchorSide == SIDE_ABOVE) {
                    nearProgress
                } else {
                    secondaryProgress
                },
            )
        }
        params.x = Math.min(left, right)
        params.y = Math.min(top, bottom)
        params.width = Math.max(1, Math.abs(right - left))
        params.height = Math.max(1, Math.abs(bottom - top))
    }

    private fun updateLayout() {
        try {
            windowManager.updateViewLayout(this, params)
        } catch (ignored: Throwable) {
        }
    }

    private fun setChildrenAlpha(alpha: Float) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.alpha = alpha
            if (child is ImageView) {
                val drawable = child.drawable
                if (drawable != null) drawable.alpha = 255
            }
        }
    }

    private fun text(value: String?, size: Float, color: Int, medium: Boolean): TextView {
        val view = TextView(context)
        view.text = value ?: ""
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        view.setTextColor(color)
        view.typeface = Typeface.create(
            if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL,
        )
        view.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        view.setSingleLine(true)
        view.includeFontPadding = false
        return view
    }

    private fun joinSenderAndApp(sender: String?, appName: String?): String {
        if (TextUtils.isEmpty(sender)) return appName ?: ""
        if (TextUtils.isEmpty(appName)) return sender ?: ""
        return "$sender · $appName"
    }

    private fun panelBackground(): GradientDrawable {
        val background = GradientDrawable()
        background.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        if (darkMode) {
            background.setColors(
                intArrayOf(
                    Color.rgb(57, 72, 84),
                    Color.rgb(38, 52, 64),
                    Color.rgb(27, 39, 49),
                ),
            )
        } else {
            background.setColors(
                intArrayOf(
                    Color.rgb(255, 255, 255),
                    Color.rgb(235, 247, 252),
                    Color.rgb(196, 222, 240),
                ),
            )
        }
        background.setCornerRadius(dp(PANEL_RADIUS_DP).toFloat())
        background.setStroke(
            dp(1f),
            if (darkMode) {
                Color.argb(200, 104, 133, 151)
            } else {
                Color.argb(210, 205, 225, 235)
            },
        )
        return background
    }

    private fun clamp(value: Int, min: Int, max: Int): Int =
        Math.max(min, Math.min(max, value))

    private fun clamp(value: Float, min: Float, max: Float): Float =
        Math.max(min, Math.min(max, value))

    private fun lerp(start: Int, end: Int, progress: Float): Int =
        Math.round(start + (end - start) * progress)

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private fun easeOutCubic(progress: Float): Float {
        val inverse = 1f - progress
        return 1f - inverse * inverse * inverse
    }

    private fun easeInOut(progress: Float): Float =
        if (progress < 0.5f) {
            4f * progress * progress * progress
        } else {
            1f - Math.pow((-2f * progress + 2f).toDouble(), 3.0).toFloat() / 2f
        }

    private fun overshoot(progress: Float): Float {
        val tension = 0.72f
        val shifted = progress - 1f
        return 1f + (tension + 1f) * shifted * shifted * shifted +
            tension * shifted * shifted
    }

    private fun dp(value: Float): Int =
        Math.round(value * resources.displayMetrics.density)

    private companion object {
        const val SIDE_LEFT = 0
        const val SIDE_RIGHT = 1
        const val SIDE_ABOVE = 2
        const val SIDE_BELOW = 3
        const val PANEL_RADIUS_DP = 18f
    }
}
