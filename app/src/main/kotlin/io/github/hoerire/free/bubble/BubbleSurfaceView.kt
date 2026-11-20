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
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

/** Full-screen-overlay hosted notification bubble with a soft landing animation. */
@SuppressLint("ViewConstructor")
internal class BubbleSurfaceView(
    context: Context,
    private val visual: BubbleVisualConfig,
) : View(context) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)
    private val params: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val childShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val childCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val childRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Static chromatic edges approximate glass dispersion without sampling the
    // screen behind the overlay or running a continuous blur effect.
    private val dispersionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val appBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val appBadgeRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconBounds = Rect()
    private val iconClipBounds = RectF()
    private val highlightArc = RectF()
    private val iconClip = Path()
    private val coreClip = Path()
    private val childClip = Path()
    private val badgeClip = Path()
    private val mainAnimationContinuous: Boolean
    private val darkMode: Boolean
    private val childIconDrawables = ArrayList<Drawable>()

    private var notificationIcon: Drawable? = null

    /** Scale applied to the notification icon radius inside the bubble (1.0 = 20dp). */
    private var iconScale = 1f

    /** Scale applied to the corner app badge (1.0 = 16dp). */
    private var badgeSizeScale = 1f
    private var clickAction: (() -> Unit)? = null
    private var dismissedAction: (() -> Unit)? = null
    private var dragStartedAction: (() -> Unit)? = null
    private var bottomDismissAction: (() -> Unit)? = null
    private var configurationChangedAction: (() -> Unit)? = null
    private var layoutChangedAction: (() -> Unit)? = null
    private var longPressCallback: (() -> Unit)? = null
    private var expandGestureAction: (() -> Unit)? = null
    private var longPressHandled = false
    private var dismissValidator: (() -> Boolean)? = null
    private var positionListener: PositionListener? = null
    private var swipeListener: SwipeListener? = null
    private var landingAnimator: ValueAnimator? = null
    private var breathingAnimator: ValueAnimator? = null
    private var orbitAnimator: ValueAnimator? = null
    private var burstAnimator: ValueAnimator? = null
    private var dockAnimator: ValueAnimator? = null
    private var dockBreathAnimator: ValueAnimator? = null
    private var imeAnimator: ValueAnimator? = null
    private var imeTargetY = Int.MIN_VALUE
    private var badgeAnimator: ValueAnimator? = null
    private var iconAnimator: ValueAnimator? = null
    private var attached = false
    private var dismissing = false
    private var bursting = false
    private var shadowGradient: RadialGradient? = null
    private var coreGradient: RadialGradient? = null
    private var glassEdgeGradient: SweepGradient? = null
    private var shownError: Throwable? = null
    private var viewSize = 0

    /** Whole-bubble scale (60..140 percent); all geometry is drawn in design space and scaled. */
    private var bubbleScale = 0f

    /** The unscaled 92dp design window; drawing always happens in this coordinate space. */
    private val designSize: Int
    private val touchSlop: Int
    private var screenWidth = 0
    /** 最近一次浮动位置；贴边是临时状态，弹回时用它。 */
    private var floatingX = 0
    private var floatingY = 0
    private var visibleLeft = 0
    private var visibleRight = 0
    private var safeTop = 0
    private var safeBottom = 0
    private var dockedEdge = 0

    /** When false the bubble never docks to the screen edge (temporary suggestions). */
    private var dockingAllowed = true
    private var timeoutSeconds = 0
    private var dimOnTimeout = false
    private var timeoutDimmed = false
    private var landingSuppressed = false
    private var landscape = false
    private var pointerDown = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var orbitPhase = 0f
    private var burstProgress = 0f
    private var landingFrameProgress = -1f
    private var badgeScale = 1f
    private var iconTransition = 1f
    private var stackedCount = 0
    private var appBadgeIcon: Drawable? = null
    private var appBadgeScale = 0f
    private var appBadgeAnimator: ValueAnimator? = null
    private var imeAvoiding = false
    private var imeRestoreY = 0
    private var outgoingIcon: Drawable? = null
    private var incomingIcon: Drawable? = null
    private var downWindowX = 0
    private var downWindowY = 0

    private val timeoutAction = Runnable { handleTimeout() }
    private val longPressAction = Runnable { handleLongPress() }
    private val repeatLandingAction = Runnable { startLandingAnimation() }
    private val imeAvoidanceAction = Runnable { updateImeAvoidance() }

    init {
        mainAnimationContinuous = visual.continuousMotion()
        darkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        iconScale = clampIconPercent(visual.iconSizePercent()) / 100f
        badgeSizeScale = clampBadgePercent(visual.badgeSizePercent()) / 100f
        bubbleScale = clampBubblePercent(visual.bubbleSizePercent()) / 100f
        designSize = dp(VIEW_DP)
        viewSize = Math.round(designSize * bubbleScale)
        isClickable = true
        isFocusable = false
        contentDescription = "打开通知小窗"
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        viewSize = dp(VIEW_DP)
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        params = WindowManager.LayoutParams(
            viewSize,
            viewSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.LEFT
        params.setFitInsetsTypes(0)
        params.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        params.setTitle("Bubble notification")
        resolvePosition(visual)

        ringPaint.style = Paint.Style.STROKE
        ringPaint.strokeWidth = dp(1.5f).toFloat()
        ringPaint.color = if (darkMode) {
            Color.argb(190, 154, 193, 214)
        } else {
            Color.argb(185, 224, 244, 255)
        }
        highlightPaint.style = Paint.Style.STROKE
        highlightPaint.strokeWidth = dp(1.2f).toFloat()
        highlightPaint.strokeCap = Paint.Cap.ROUND
        childCorePaint.color = if (darkMode) {
            Color.argb(215, 68, 88, 103)
        } else {
            Color.argb(205, 245, 250, 255)
        }
        childShadowPaint.color = if (darkMode) {
            Color.argb(75, 0, 0, 0)
        } else {
            Color.argb(44, 0, 0, 0)
        }
        childRingPaint.style = Paint.Style.STROKE
        childRingPaint.strokeWidth = dp(1f).toFloat()
        childRingPaint.color = if (darkMode) {
            Color.argb(200, 184, 213, 229)
        } else {
            Color.argb(190, 255, 255, 255)
        }
        badgeRingPaint.style = Paint.Style.STROKE
        badgeRingPaint.strokeWidth = dp(1f).toFloat()
        badgeRingPaint.color = Color.argb(215, 255, 255, 255)
        badgeTextPaint.textAlign = Paint.Align.CENTER
        badgeTextPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        badgeTextPaint.color = if (darkMode) {
            Color.rgb(238, 246, 250)
        } else {
            Color.rgb(37, 48, 58)
        }
        badgeTextOutlinePaint.textAlign = Paint.Align.CENTER
        badgeTextOutlinePaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        badgeTextOutlinePaint.style = Paint.Style.STROKE
        badgeTextOutlinePaint.strokeWidth = dp(1.4f).toFloat()
        badgeTextOutlinePaint.color = Color.argb(225, 255, 255, 255)
        appBadgePaint.color = if (darkMode) {
            Color.argb(235, 12, 20, 27)
        } else {
            Color.argb(220, 34, 48, 60)
        }
        appBadgeRingPaint.style = Paint.Style.STROKE
        appBadgeRingPaint.strokeWidth = dp(0.85f).toFloat()
        appBadgeRingPaint.color = if (darkMode) {
            Color.argb(230, 18, 24, 30)
        } else {
            Color.argb(235, 255, 255, 255)
        }
    }

    fun setIcon(icon: Drawable?) {
        iconAnimator?.cancel()
        outgoingIcon = null
        incomingIcon = null
        iconTransition = 1f
        notificationIcon = icon
        invalidate()
    }

    /**
     * Resizes the notification icon inside the bubble. [percent] is relative to the
     * historical 20dp radius, so 100 keeps the original look.
     */
    fun setIconScalePercent(percent: Int) {
        val next = clampIconPercent(percent) / 100f
        if (Math.abs(next - iconScale) < 0.001f) return
        iconScale = next
        invalidate()
    }

    private fun clampIconPercent(percent: Int): Int = Math.max(
        BubbleMetrics.MIN_ICON_SIZE_PERCENT,
        Math.min(BubbleMetrics.MAX_ICON_SIZE_PERCENT, percent),
    )

    private fun clampBubblePercent(percent: Int): Int = Math.max(
        BubbleMetrics.MIN_BUBBLE_SIZE_PERCENT,
        Math.min(BubbleMetrics.MAX_BUBBLE_SIZE_PERCENT, percent),
    )

    fun getBubbleSizePx(): Int = viewSize

    /**
     * Resizes the whole bubble (glass core, notification icon and app badge) around its
     * current center. 100 keeps the original 92dp window. Applied live when the bubble is
     * already attached, and used by the settings preview for an unattached view.
     */
    fun setBubbleSizePercent(percent: Int) {
        val next = clampBubblePercent(percent) / 100f
        if (Math.abs(next - bubbleScale) < 0.001f) return
        val oldSize = viewSize
        bubbleScale = next
        viewSize = Math.round(designSize * bubbleScale)
        params.width = viewSize
        params.height = viewSize
        val delta = Math.round((oldSize - viewSize) / 2f)
        if (dockedEdge < 0) {
            params.x = -viewSize / 2
        } else if (dockedEdge > 0) {
            params.x = screenWidth - viewSize / 2
        } else {
            params.x += delta
        }
        params.y += delta
        if (imeAvoiding) imeRestoreY += delta
        val wm = windowManager
        if (attached && wm != null) {
            refreshSafeBounds()
            try {
                wm.updateViewLayout(this, params)
                if (dockedEdge != 0) startDockBreathing()
            } catch (ignored: Throwable) {
                // The window may be between recreation passes; the next show re-applies it.
            }
        }
        requestLayout()
        invalidate()
        // Let attached overlays (the notification content panel) re-anchor and re-fit
        // themselves to the new diameter immediately.
        layoutChangedAction?.invoke()
    }

    /** Resizes the corner app badge; 100 keeps the original 16dp. */
    fun setBadgeScalePercent(percent: Int) {
        val next = clampBadgePercent(percent) / 100f
        if (Math.abs(next - badgeSizeScale) < 0.001f) return
        badgeSizeScale = next
        invalidate()
    }

    private fun clampBadgePercent(percent: Int): Int = Math.max(
        BubbleMetrics.MIN_BADGE_SIZE_PERCENT,
        Math.min(BubbleMetrics.MAX_BADGE_SIZE_PERCENT, percent),
    )

    fun setIconAnimated(nextIcon: Drawable?) {
        if (nextIcon == null) return
        if (notificationIcon == null || !attached) {
            setIcon(nextIcon)
            return
        }
        iconAnimator?.cancel()
        outgoingIcon = notificationIcon
        incomingIcon = nextIcon
        iconTransition = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 320L
        animator.interpolator = DecelerateInterpolator(1.35f)
        animator.addUpdateListener { a ->
            iconTransition = a.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!canceled) notificationIcon = nextIcon
                outgoingIcon = null
                incomingIcon = null
                iconTransition = 1f
                iconAnimator = null
                invalidate()
            }
        })
        iconAnimator = animator
        animator.start()
    }

    fun setAppBadge(nextIcon: Drawable?) {
        appBadgeAnimator?.cancel()
        appBadgeIcon = nextIcon
        val start = appBadgeScale
        val animator = ValueAnimator.ofFloat(start, if (nextIcon == null) 0f else 1f)
        animator.duration = 220L
        animator.interpolator = DecelerateInterpolator(1.5f)
        animator.addUpdateListener { a ->
            appBadgeScale = a.animatedValue as Float
            invalidate()
        }
        appBadgeAnimator = animator
        animator.start()
    }

    /** Small inward response as the notification preview is absorbed. */
    fun playPreviewAbsorb() {
        if (!attached || dismissing || bursting) return
        animate().cancel()
        restoreVisibleAlpha()
        animate().scaleX(0.92f).scaleY(1.07f).setDuration(130L)
            .withEndAction {
                animate().scaleX(1f).scaleY(1f)
                    .setDuration(190L).start()
            }.start()
    }

    private fun restoreVisibleAlpha() {
        if (!timeoutDimmed) {
            alpha = if (dockedEdge == 0) 1f else 0.5f
        }
    }

    fun setChildIcons(icons: List<Drawable>?) {
        childIconDrawables.clear()
        if (icons != null) childIconDrawables.addAll(icons)
        invalidate()
    }

    fun setStackCount(count: Int) {
        stackedCount = Math.max(0, count)
        invalidate()
    }

    fun getStackBadgeBounds(): Rect {
        val radius = Math.round(dp(12f) * bubbleScale)
        val centerX = params.x + Math.round(stackBadgeCenterX() * bubbleScale)
        val centerY = params.y + Math.round((designSize / 2f - dp(22f)) * bubbleScale)
        return Rect(
            centerX - radius, centerY - radius,
            centerX + radius, centerY + radius,
        )
    }

    fun setStackExpanded(expanded: Boolean) {
        badgeAnimator?.cancel()
        val start = badgeScale
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = if (expanded) 190L else 240L
        animator.addUpdateListener { a ->
            val progress = a.animatedValue as Float
            val curve = if (expanded) backIn(progress) else backOut(progress)
            badgeScale = clamp(lerp(start, if (expanded) 0f else 1f, curve), 0f, 1.18f)
            invalidate()
        }
        badgeAnimator = animator
        animator.start()
        // The expanded fan owns the screen, so the source bubble freezes: no landing bounce,
        // no orbit decoration and no auto-dismiss deadline while it is open.
        if (expanded) {
            stopLandingAnimation()
            stopOrbitAnimation()
            mainHandler.removeCallbacks(timeoutAction)
            return
        }
        mainHandler.removeCallbacks(timeoutAction)
        if (attached && !dismissing && dockedEdge == 0) {
            if (!landingSuppressed) mainHandler.postDelayed(repeatLandingAction, 700L)
            if (timeoutSeconds > 0) {
                mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L)
            }
        }
    }

    fun setOnExpandGestureAction(action: (() -> Unit)?) {
        expandGestureAction = action
    }

    fun setOnClickAction(action: (() -> Unit)?) {
        clickAction = action
    }

    fun setOnDismissedAction(action: (() -> Unit)?) {
        dismissedAction = action
    }

    fun setOnPositionChangedListener(listener: PositionListener?) {
        positionListener = listener
    }

    fun setOnDragStartedAction(action: (() -> Unit)?) {
        dragStartedAction = action
    }

    fun setOnBottomDismissAction(action: (() -> Unit)?) {
        bottomDismissAction = action
    }

    fun setOnConfigurationChangedAction(action: (() -> Unit)?) {
        configurationChangedAction = action
    }

    fun setOnLayoutChangedAction(action: (() -> Unit)?) {
        layoutChangedAction = action
    }

    fun setOnLongPressAction(action: (() -> Unit)?) {
        longPressCallback = action
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Window metrics and system-bar insets change with rotation. Re-resolve the
        // persisted position for the new orientation instead of retaining stale bounds.
        if (!attached || windowManager == null || dismissing) return
        val previousDock = dockedEdge
        mainHandler.postDelayed({
            if (!attached || dismissing) return@postDelayed
            try {
                resolvePosition(visual)
                // A dock is a physical edge choice, so carry it across orientation changes
                // for the currently attached window. Avoid writing remote preferences from
                // SystemUI's configuration callback, which can restart SystemUI on some ROMs.
                if (previousDock != 0) {
                    dockedEdge = previousDock
                    params.x = if (dockedEdge < 0) -viewSize / 2 else screenWidth - viewSize / 2
                }
                updateWindowLayout()
                if (dockedEdge != 0) startDockBreathing()
                configurationChangedAction?.invoke()
            } catch (ignored: Throwable) {
                // Rotation can briefly expose invalid WindowMetrics while SystemUI is
                // recreating its display context. Leave the existing window untouched.
            }
        }, 80L)
    }

    fun setAutoDismissValidator(validator: (() -> Boolean)?) {
        dismissValidator = validator
    }

    fun setOnSwipeListener(listener: SwipeListener?) {
        swipeListener = listener
    }

    fun getWindowBounds(): Rect {
        val logicalY = if (imeAvoiding) imeRestoreY else params.y
        return Rect(params.x, logicalY, params.x + viewSize, logicalY + viewSize)
    }

    /** Bounds of the visible main bubble, excluding the transparent overlay padding. */
    fun getCoreBounds(): Rect {
        val radius = Math.round(dp(CORE_RADIUS_DP) * bubbleScale)
        val centerX = params.x + viewSize / 2
        val centerY = params.y + viewSize / 2
        return Rect(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
    }

    /** Screen-space bounds of the drawn glass circle, not the requested window origin. */
    fun previewAnchorBounds(): Rect {
        if (!isLaidOut || !isAttachedToWindow) return getCoreBounds()
        val location = IntArray(2)
        getLocationOnScreen(location)
        val radius = dp(CORE_RADIUS_DP) * bubbleScale
        val center = designSize * bubbleScale / 2f
        val circle = RectF(
            center - radius, center - radius,
            center + radius, center + radius,
        )
        // The screen origin returned above already includes the View transform.
        // Map only the offset from that origin, including any animated scale.
        val origin = floatArrayOf(0f, 0f)
        matrix.mapPoints(origin)
        matrix.mapRect(circle)
        circle.offset(location[0] - origin[0], location[1] - origin[1])
        return Rect(
            Math.round(circle.left), Math.round(circle.top),
            Math.round(circle.right), Math.round(circle.bottom),
        )
    }

    fun isDocked(): Boolean = dockedEdge != 0

    fun getDockEdge(): Int = dockedEdge

    fun isAttached(): Boolean = attached

    fun applyRuntimePosition(x: Int, y: Int, edge: Int) {
        params.x = x
        params.y = y
        dockedEdge = Integer.compare(edge, 0)
        if (dockedEdge == 0) {
            floatingX = x
            floatingY = y
        }
    }

    fun getChildIcons(): List<Drawable> = ArrayList(childIconDrawables)

    /** Applies the persisted position before the overlay is attached. */
    fun applyPersistedPosition(xPercent: Int, yPercent: Int, edge: Int) {
        val horizontalRange = Math.max(0, visibleRight - visibleLeft)
        val verticalRange = Math.max(0, safeBottom - safeTop)
        dockedEdge = Integer.compare(edge, 0)
        params.x = visibleLeft +
            Math.round(horizontalRange * clamp(xPercent.toFloat(), 0f, 100f) / 100f)
        params.y = safeTop +
            Math.round(verticalRange * clamp(yPercent.toFloat(), 0f, 100f) / 100f)
        if (dockedEdge < 0) params.x = -viewSize / 2
        if (dockedEdge > 0) params.x = screenWidth - viewSize / 2
        if (dockedEdge == 0) {
            floatingX = params.x
            floatingY = params.y
        }
    }

    /** Recomputes the draggable bounds for the current window size without moving the bubble. */
    private fun refreshSafeBounds() {
        val bounds = windowManager!!.currentWindowMetrics.bounds
        val insets = windowManager!!.currentWindowMetrics.windowInsets
        val bars = insets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        screenWidth = bounds.width()
        visibleLeft = bars.left + dp(8f)
        visibleRight = bounds.width() - bars.right - viewSize - dp(8f)
        safeTop = bars.top + dp(8f)
        safeBottom = bounds.height() - bars.bottom - viewSize - dp(12f)
    }

    fun show(timeoutSeconds: Int, dimOnTimeout: Boolean): Boolean {
        val wm = windowManager
        if (attached || wm == null) return false
        try {
            wm.addView(this, params)
            attached = true
            mainHandler.post(imeAvoidanceAction)
        } catch (error: Throwable) {
            shownError = error
            return false
        }

        this.timeoutSeconds = timeoutSeconds
        this.dimOnTimeout = dimOnTimeout
        timeoutDimmed = false
        alpha = 0f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        animate()
            .alpha(if (dockedEdge == 0) 1f else 0.5f)
            .setDuration(180L)
            .start()
        if (dockedEdge != 0) mainHandler.postDelayed({ startDockBreathing() }, 190L)
        if (dockedEdge == 0 && !landingSuppressed) {
            startBreathingAnimation()
        }
        if (dockedEdge == 0 && timeoutSeconds > 0) {
            mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L)
        }
        return true
    }

    fun getShowError(): Throwable? = shownError

    fun updateTimeout(seconds: Int, dimOnTimeout: Boolean) {
        timeoutSeconds = Math.max(0, seconds)
        this.dimOnTimeout = dimOnTimeout
        timeoutDimmed = false
        mainHandler.removeCallbacks(timeoutAction)
        // Restore the resting alpha for both docked and free bubbles: the entrance animation
        // may never have completed while the display was off, which would otherwise leave the
        // bubble invisible when the screen wakes up.
        if (attached && !dismissing && !bursting) {
            animate().alpha(if (dockedEdge == 0) 1f else 0.5f).setDuration(160L).start()
        }
        if (attached && !dismissing && dockedEdge == 0 && timeoutSeconds > 0) {
            mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L)
        }
        if (attached && !dismissing && dockedEdge == 0 && !timeoutDimmed) {
            startBreathingAnimation()
        }
    }

    fun setLandingAnimationSuppressed(suppressed: Boolean) {
        landingSuppressed = suppressed
        if (suppressed) {
            animate().cancel()
            stopBreathingAnimation()
            stopLandingAnimation()
            stopDockBreathing()
            restoreVisibleAlpha()
        } else if (attached && !dismissing && dockedEdge != 0) {
            startDockBreathing()
        } else if (attached && !dismissing && !timeoutDimmed) {
            startBreathingAnimation()
        }
    }

    fun dismiss() {
        dismiss("requested")
    }

    fun playBurst(completion: (() -> Unit)?) {
        if (!attached || dismissing || bursting) {
            completion?.invoke()
            return
        }
        bursting = true
        pointerDown = false
        mainHandler.removeCallbacks(timeoutAction)
        mainHandler.removeCallbacks(longPressAction)
        mainHandler.removeCallbacks(imeAvoidanceAction)
        stopBreathingAnimation()
        stopLandingAnimation()
        animate().cancel()
        burstProgress = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 430L
        animator.addUpdateListener { a ->
            burstProgress = a.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                burstAnimator = null
                if (canceled) return
                removeImmediately()
                completion?.invoke()
            }
        })
        burstAnimator = animator
        animator.start()
    }

    private fun dismiss(reason: String) {
        if (!attached || dismissing || bursting) return
        android.util.Log.i(
            "BubbleNotify",
            "bubble dismiss: $reason; timeout=$timeoutSeconds",
        )
        dismissing = true
        mainHandler.removeCallbacks(timeoutAction)
        mainHandler.removeCallbacks(longPressAction)
        mainHandler.removeCallbacks(imeAvoidanceAction)
        stopBreathingAnimation()
        stopLandingAnimation()
        stopOrbitAnimation()
        burstAnimator?.cancel()
        burstAnimator = null
        animate().cancel()
        animate()
            .alpha(0f)
            .scaleX(0.78f)
            .scaleY(0.78f)
            .translationY(dp(12f) * bubbleScale)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeImmediately()
                }
            })
            .start()
    }

    fun removeImmediately() {
        mainHandler.removeCallbacks(timeoutAction)
        mainHandler.removeCallbacks(longPressAction)
        mainHandler.removeCallbacks(imeAvoidanceAction)
        pointerDown = false
        stopBreathingAnimation()
        stopLandingAnimation()
        stopOrbitAnimation()
        burstAnimator?.cancel()
        burstAnimator = null
        animate().cancel()
        if (attached) {
            try {
                windowManager!!.removeViewImmediate(this)
            } catch (ignored: Throwable) {
            }
            attached = false
        }
        val callback = dismissedAction
        dismissedAction = null
        callback?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bursting) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                restoreFromTimeoutDim()
                pointerDown = true
                dragging = false
                longPressHandled = false
                downRawX = event.rawX
                downRawY = event.rawY
                downWindowX = params.x
                downWindowY = params.y
                mainHandler.postDelayed(longPressAction, 450L)
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceX = event.rawX - downRawX
                val distanceY = event.rawY - downRawY
                if (longPressHandled && !dragging &&
                    distanceY < -dp(EXPAND_SWIPE_DP) &&
                    Math.abs(distanceY) > Math.abs(distanceX) * 1.15f
                ) {
                    // Long press, then swipe up: fan out the queued bubbles instead of
                    // dragging this one. Any other direction keeps the drag behaviour.
                    longPressHandled = false
                    pointerDown = false
                    mainHandler.removeCallbacks(longPressAction)
                    val expand = expandGestureAction
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    expand?.invoke()
                    return true
                }
                if (!dragging && distanceX * distanceX + distanceY * distanceY >
                    touchSlop * touchSlop
                ) {
                    mainHandler.removeCallbacks(longPressAction)
                    if (longPressHandled) beginDrag()
                }
                if (dragging) updateDrag(distanceX, distanceY)
            }
            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressAction)
                pointerDown = false
                if (dragging) {
                    finishDrag()
                } else if (longPressHandled) {
                    longPressHandled = false
                    val longPress = longPressCallback
                    if (longPress != null && !dismissing) longPress.invoke()
                    return true
                } else if (dispatchSwipe(event.rawX - downRawX, event.rawY - downRawY)) {
                    return true
                } else if (isCoreTouch(event)) {
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressAction)
                pointerDown = false
                if (dragging) finishDrag()
            }
            else -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        val action = clickAction
        if (action != null && !dismissing) action.invoke()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Everything is laid out in the fixed 92dp design space and scaled once here, so
        // the glass core, notification icon, app badge and decorations stay proportional
        // at every bubble size. The window itself is viewSize = designSize * bubbleScale.
        val cx = designSize / 2f
        val cy = designSize / 2f
        val mainAlpha = if (bursting) {
            clamp(1f - Math.max(0f, burstProgress - 0.18f) / 0.62f, 0f, 1f)
        } else {
            1f
        }
        val mainScale = if (bursting) {
            1f + 0.18f * Math.sin(
                Math.PI * Math.min(1f, burstProgress / 0.7f).toDouble(),
            ).toFloat()
        } else {
            1f
        }
        val outer = canvas.save()
        canvas.scale(bubbleScale, bubbleScale)
        val layer = canvas.saveLayerAlpha(
            0f, 0f, designSize.toFloat(), designSize.toFloat(),
            Math.round(mainAlpha * 255f),
        )
        canvas.scale(mainScale, mainScale, cx, cy)
        drawMainBubble(canvas, cx, cy)
        canvas.restoreToCount(layer)
        drawStackBadge(canvas, cx, cy)
        drawAppBadge(canvas, cx, cy)
        if (bursting) drawBurstFragments(canvas, cx, cy)
        canvas.restoreToCount(outer)
    }

    private fun drawStackBadge(canvas: Canvas, cx: Float, cy: Float) {
        if (stackedCount <= 0 || badgeScale <= 0.001f) return
        val x = stackBadgeCenterX()
        val y = cy - dp(22f)
        val radius = dp(12f).toFloat()
        val save = canvas.save()
        canvas.scale(badgeScale, badgeScale, x, y)
        badgeShadowPaint.shader = RadialGradient(
            x, y + dp(1.5f), radius + dp(4f),
            Color.argb(70, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        badgeCorePaint.shader = RadialGradient(
            x - dp(3f), y - dp(4f), radius * 1.45f,
            Color.argb(245, 255, 255, 255), Color.argb(220, 212, 235, 247),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y + dp(1.5f), radius + dp(4f), badgeShadowPaint)
        canvas.drawCircle(x, y, radius, badgeCorePaint)
        canvas.drawCircle(x, y, radius - dp(0.8f), badgeRingPaint)
        val label = if (stackedCount > 99) "99+" else stackedCount.toString()
        val textSize = dp(if (label.length >= 3) 8f else if (label.length == 2) 9f else 10f)
        badgeTextPaint.textSize = textSize.toFloat()
        badgeTextOutlinePaint.textSize = textSize.toFloat()
        val baseline = y - (badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
        canvas.drawText(label, x, baseline, badgeTextOutlinePaint)
        canvas.drawText(label, x, baseline, badgeTextPaint)
        canvas.restoreToCount(save)
    }

    private fun drawAppBadge(canvas: Canvas, cx: Float, cy: Float) {
        val badge = appBadgeIcon
        if (badge == null || appBadgeScale <= 0.001f) return
        val size = dp(BADGE_BASE_DP) * badgeSizeScale
        val half = size / 2f
        // The app badge's geometric centre sits exactly on the bubble rim instead of
        // floating inside the glass. The diagonal is kept from the historical offset,
        // normalised so half the badge pokes outside the circle at every badge size.
        val rim = dp(CORE_RADIUS_DP).toFloat()
        val dirLength = Math.hypot(
            BADGE_CENTER_X_DP.toDouble(),
            BADGE_CENTER_Y_DP.toDouble(),
        ).toFloat()
        val dirX = BADGE_CENTER_X_DP / dirLength
        val dirY = BADGE_CENTER_Y_DP / dirLength
        val x = if (dockedEdge > 0) cx - rim * dirX else cx + rim * dirX
        val y = cy + rim * dirY
        canvas.save()
        canvas.scale(appBadgeScale, appBadgeScale, x, y)
        // The app's own launcher icon is drawn as-is: no extra circle mask and no background
        // disc, so the badge looks exactly like the icon in the launcher / recent tasks.
        setBadgeBounds(badge, x - half, y - half, x + half, y + half)
        badge.alpha = 255
        badge.draw(canvas)
        canvas.restore()
    }

    /** Fits a drawable into the badge square while preserving its intrinsic aspect ratio. */
    private fun setBadgeBounds(
        drawable: Drawable,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        val boxWidth = right - left
        val boxHeight = bottom - top
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            iconBounds.set(
                Math.round(left), Math.round(top),
                Math.round(right), Math.round(bottom),
            )
        } else {
            val scale = Math.min(boxWidth / intrinsicWidth, boxHeight / intrinsicHeight)
            val drawWidth = intrinsicWidth * scale
            val drawHeight = intrinsicHeight * scale
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            iconBounds.set(
                Math.round(centerX - drawWidth / 2f),
                Math.round(centerY - drawHeight / 2f),
                Math.round(centerX + drawWidth / 2f),
                Math.round(centerY + drawHeight / 2f),
            )
        }
        drawable.bounds = iconBounds
    }

    private fun stackBadgeCenterX(): Float =
        // Design-space coordinate; the whole canvas is scaled by bubbleScale in onDraw.
        designSize / 2f + (if (dockedEdge > 0) -dp(22f) else dp(22f))

    private fun drawMainBubble(canvas: Canvas, cx: Float, cy: Float) {
        val coreRadius = dp(CORE_RADIUS_DP).toFloat()

        shadowPaint.shader = shadowGradient
        canvas.drawCircle(cx, cy + dp(3f), coreRadius + dp(7f), shadowPaint)

        // Translucent glass core: the radial gradient keeps the bubble see-through.
        corePaint.shader = coreGradient
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
        canvas.drawCircle(cx, cy, coreRadius - dp(1f), ringPaint)

        // Frosted refraction / edge highlight, the layer that reads as "glass".
        drawBackgroundRefraction(canvas, cx, cy, coreRadius)

        // The icon may be configured larger than the glass core (up to 150%); clip it to the
        // core so a full-bleed icon can never paint over the bubble rim.
        val iconSave = canvas.save()
        coreClip.reset()
        coreClip.addCircle(cx, cy, coreRadius - dp(0.6f), Path.Direction.CW)
        canvas.clipPath(coreClip)
        drawIcon(canvas, cx, cy)
        canvas.restoreToCount(iconSave)

        highlightPaint.color = Color.argb(145, 255, 255, 255)
        canvas.drawArc(highlightArc, 205f, 74f, false, highlightPaint)
    }

    /** Frosted-glass refraction and edge dispersion drawn on top of the translucent core. */
    private fun drawBackgroundRefraction(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val save = canvas.save()
        iconClip.reset()
        iconClip.addCircle(cx, cy, radius - dp(2f), Path.Direction.CW)
        canvas.clipPath(iconClip)
        val bulge = 1.13f
        canvas.translate(-dp(3.2f).toFloat(), -dp(2.4f).toFloat())
        canvas.scale(bulge, 0.91f, cx, cy)
        refractionPaint.shader = RadialGradient(
            cx - dp(9f), cy - dp(11f), radius * 1.28f,
            Color.argb(66, 255, 255, 255), Color.argb(18, 176, 216, 238),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, refractionPaint)
        refractionPaint.style = Paint.Style.STROKE
        refractionPaint.strokeWidth = dp(3.2f).toFloat()
        refractionPaint.shader = null
        refractionPaint.color = Color.argb(34, 255, 255, 255)
        canvas.drawOval(
            cx - radius * 0.78f, cy - radius * 0.68f,
            cx + radius * 0.82f, cy + radius * 0.72f, refractionPaint,
        )
        refractionPaint.style = Paint.Style.FILL
        dispersionPaint.style = Paint.Style.STROKE
        dispersionPaint.strokeWidth = dp(1.55f).toFloat()
        dispersionPaint.strokeCap = Paint.Cap.ROUND
        dispersionPaint.isAntiAlias = true
        dispersionPaint.shader = glassEdgeGradient
        dispersionPaint.color = Color.WHITE
        dispersionPaint.strokeWidth = dp(1.15f).toFloat()
        canvas.drawCircle(cx, cy, radius - dp(0.7f), dispersionPaint)
        dispersionPaint.shader = null
        canvas.restoreToCount(save)
    }

    private fun drawChildBubbles(canvas: Canvas, cx: Float, cy: Float) {
        val count = childIconDrawables.size
        if (count == 0 || landingFrameProgress < 0f) return
        // At rest there is a single clean bubble. During landing, mostly-covered
        // staggered rims briefly reveal that more notifications are queued.
        for (index in (Math.min(count, 3) - 1) downTo 0) {
            val phase = landingFrameProgress * Math.PI.toFloat() * 4f - index * 0.52f
            val pulse = Math.max(0f, Math.sin(phase.toDouble()).toFloat())
            val childRadius = dp(25f - index * 0.8f).toFloat()
            val side = if (index % 2 == 0) -1f else 1f
            val childX = cx + side * dp(3.2f + index * 1.1f) * pulse
            val childY = cy + dp(2.5f + index) - dp(2.4f + index) * pulse
            val alpha = Math.round((58f + 50f * pulse) * (1f - index * 0.13f))
            childCorePaint.shader = null
            childCorePaint.color = Color.argb(alpha, 236, 246, 252)
            childRingPaint.color = Color.argb(Math.min(180, alpha + 48), 255, 255, 255)
            canvas.drawCircle(childX, childY, childRadius, childCorePaint)
            canvas.drawCircle(childX, childY, childRadius - dp(1f), childRingPaint)
        }
    }

    private fun drawBurstFragments(canvas: Canvas, cx: Float, cy: Float) {
        val fragmentProgress = clamp((burstProgress - 0.12f) / 0.88f, 0f, 1f)
        val alpha = Math.round(210f * (1f - fragmentProgress))
        for (index in 0 until 12) {
            val angle = Math.toRadians((index * 30f + (index % 2) * 8f).toDouble())
            val distance = dp(18f + 24f * fragmentProgress + index % 3 * 2f).toFloat()
            val x = cx + distance * Math.cos(angle).toFloat()
            val y = cy + distance * Math.sin(angle).toFloat()
            burstPaint.color = Color.argb(alpha, 236, 248, 255)
            canvas.drawCircle(x, y, dp(1.8f + index % 3).toFloat(), burstPaint)
        }
    }

    private fun startOrbitAnimation() {
        if (orbitAnimator != null || !attached) return
        val animator = ValueAnimator.ofFloat(orbitPhase, orbitPhase + 360f)
        animator.duration = 7200L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { a ->
            orbitPhase = (a.animatedValue as Float) % 360f
            invalidate()
        }
        orbitAnimator = animator
        animator.start()
    }

    private fun stopOrbitAnimation() {
        orbitAnimator?.cancel()
        orbitAnimator = null
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // Shaders live in the design space; onDraw applies bubbleScale to the canvas.
        val cx = designSize / 2f
        val cy = designSize / 2f
        val coreRadius = dp(CORE_RADIUS_DP).toFloat()
        shadowGradient = RadialGradient(
            cx, cy + dp(3f), coreRadius + dp(7f),
            if (darkMode) Color.argb(105, 0, 0, 0) else Color.argb(76, 0, 0, 0),
            Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        coreGradient = RadialGradient(
            cx - dp(7f), cy - dp(8f), coreRadius * 1.35f,
            if (darkMode) Color.argb(125, 133, 171, 194) else Color.argb(122, 255, 255, 255),
            if (darkMode) Color.argb(90, 45, 65, 79) else Color.argb(68, 226, 234, 242),
            Shader.TileMode.CLAMP,
        )
        highlightArc.set(
            cx - coreRadius + dp(4f), cy - coreRadius + dp(4f),
            cx + coreRadius - dp(4f), cy + coreRadius - dp(4f),
        )
        glassEdgeGradient = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.argb(if (darkMode) 110 else 135, 255, 255, 255),
                Color.argb(if (darkMode) 72 else 92, 170, 220, 242),
                Color.argb(if (darkMode) 58 else 78, 225, 192, 220),
                Color.argb(if (darkMode) 110 else 135, 255, 255, 255),
            ),
            null,
        )
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float) {
        val radius = dp(ICON_RADIUS_DP) * iconScale
        if (incomingIcon == null || outgoingIcon == null) {
            val icon = notificationIcon
            if (icon != null) BubbleIconRenderer.draw(canvas, icon, cx, cy, radius, iconClip)
            return
        }
        val progress = smooth(iconTransition)
        drawTransitionIcon(
            canvas, outgoingIcon, cx, cy, radius,
            1f - progress, lerp(1f, 0.78f, progress),
        )
        drawTransitionIcon(
            canvas, incomingIcon, cx, cy, radius,
            progress, lerp(0.78f, 1f, progress),
        )
    }

    private fun drawTransitionIcon(
        canvas: Canvas,
        drawable: Drawable?,
        cx: Float,
        cy: Float,
        radius: Float,
        alpha: Float,
        scale: Float,
    ) {
        if (drawable == null || alpha <= 0.001f) return
        val layer = canvas.saveLayerAlpha(
            cx - radius, cy - radius,
            cx + radius, cy + radius, Math.round(alpha * 255f),
        )
        canvas.scale(scale, scale, cx, cy)
        BubbleIconRenderer.draw(canvas, drawable, cx, cy, radius, iconClip)
        canvas.restoreToCount(layer)
    }

    /**
     * 原地呼吸：持续播放动效的替代实现。
     *
     * 不再改变窗口位置或 translationY，只做 ±2% 的整体缩放，并驱动
     * landingFrameProgress 让背后小气泡/残影保持律动；这样既「持续」，又不会
     * 出现从上方掉落/上下位移的观感。
     */
    private fun startBreathingAnimation() {
        if (!mainAnimationContinuous || !attached || dockedEdge != 0 || dismissing ||
            landingSuppressed || timeoutDimmed || dragging || bursting
        ) {
            return
        }
        // 上下位移形变由落地回弹循环驱动；呼吸环在循环期间让位。
        if (LANDING_BOUNCE_ENABLED) {
            stopBreathingAnimation()
            mainHandler.removeCallbacks(repeatLandingAction)
            mainHandler.postDelayed(repeatLandingAction, 700L)
            return
        }
        if (breathingAnimator != null) return
        val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat())
        animator.duration = 4200L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { a ->
            val phase = a.animatedValue as Float
            landingFrameProgress = (phase / (Math.PI * 2.0).toFloat()) % 1f
            val scale = 1f + 0.02f * Math.sin(phase.toDouble()).toFloat()
            scaleX = scale
            scaleY = scale
            invalidate()
        }
        breathingAnimator = animator
        animator.start()
    }

    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        landingFrameProgress = -1f
        if (!dismissing && !bursting) {
            scaleX = 1f
            scaleY = 1f
        }
    }

    private fun startLandingAnimation() {
        // 落地回弹循环已由「原地呼吸」取代；布局刷新时仍可能调度到这里，直接忽略。
        if (!LANDING_BOUNCE_ENABLED) return
        stopLandingAnimation()
        if (!attached || dockedEdge != 0 || dismissing || landingSuppressed) return
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1480L
        animator.addUpdateListener { a ->
            val progress = a.animatedValue as Float
            landingFrameProgress = progress
            invalidate()
            // 特效（背后小气泡/残影/呼吸）仍然按进度绘制，但不再做上下位移与形变。
            // 这样既保留持续动效，又去掉了「从上往下掉」的观感。
            if (!LANDING_BOUNCE_ENABLED) {
                applyRestFrame()
            } else if (progress < 0.45f) {
                applyBounceFrame(progress / 0.45f, 1f)
            } else if (progress < 0.52f) {
                applyRestFrame()
            } else if (progress < 0.91f) {
                applyBounceFrame((progress - 0.52f) / 0.39f, 0.68f)
            } else {
                applyRestFrame()
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                landingAnimator = null
                landingFrameProgress = -1f
                scaleX = 1f
                scaleY = 1f
                translationY = 0f
                if (!canceled && mainAnimationContinuous && attached &&
                    dockedEdge == 0 && !dismissing && !timeoutDimmed &&
                    !landingSuppressed
                ) {
                    mainHandler.postDelayed(repeatLandingAction, 2600L)
                }
            }
        })
        landingAnimator = animator
        animator.start()
    }

    private fun applyBounceFrame(progress: Float, amplitude: Float) {
        if (progress < 0.16f) {
            val local = smooth(progress / 0.16f)
            translationY = lerp(0f, dp(0.8f) * bubbleScale * amplitude, local)
            scaleX = lerp(1f, deform(1.035f, amplitude), local)
            scaleY = lerp(1f, deform(0.965f, amplitude), local)
        } else if (progress < 0.46f) {
            val local = smooth((progress - 0.16f) / 0.30f)
            translationY = lerp(
                dp(0.8f) * bubbleScale * amplitude,
                -dp(4f) * bubbleScale * amplitude, local,
            )
            scaleX = lerp(deform(1.035f, amplitude), deform(0.975f, amplitude), local)
            scaleY = lerp(deform(0.965f, amplitude), deform(1.04f, amplitude), local)
        } else if (progress < 0.72f) {
            val local = smooth((progress - 0.46f) / 0.26f)
            translationY = lerp(
                -dp(4f) * bubbleScale * amplitude,
                dp(2f) * bubbleScale * amplitude, local,
            )
            scaleX = lerp(deform(0.975f, amplitude), deform(1.075f, amplitude), local)
            scaleY = lerp(deform(1.04f, amplitude), deform(0.92f, amplitude), local)
        } else if (progress < 0.88f) {
            val local = smooth((progress - 0.72f) / 0.16f)
            translationY = lerp(
                dp(2f) * bubbleScale * amplitude,
                -dp(0.8f) * bubbleScale * amplitude, local,
            )
            scaleX = lerp(deform(1.075f, amplitude), deform(0.99f, amplitude), local)
            scaleY = lerp(deform(0.92f, amplitude), deform(1.015f, amplitude), local)
        } else {
            val local = smooth((progress - 0.88f) / 0.12f)
            translationY = lerp(-dp(0.8f) * bubbleScale * amplitude, 0f, local)
            scaleX = lerp(deform(0.99f, amplitude), 1f, local)
            scaleY = lerp(deform(1.015f, amplitude), 1f, local)
        }
    }

    private fun applyRestFrame() {
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
    }

    private fun stopLandingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        mainHandler.removeCallbacks(repeatLandingAction)
        landingAnimator?.cancel()
        landingAnimator = null
        landingFrameProgress = -1f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
    }

    /** 到点收边：按气泡中心离哪侧更近，走贴边动画。 */
    private fun collapseToEdge() {
        if (!attached || dismissing) return
        val centerX = params.x + viewSize / 2f
        animateToDock(if (centerX < screenWidth / 2f) -1 else 1)
    }

    private fun handleTimeout() {
        if (!attached || dismissing) return
        val validator = dismissValidator
        if (validator != null) {
            try {
                if (!validator()) {
                    timeoutSeconds = 0
                    android.util.Log.i(
                        "BubbleNotify",
                        "ignored timeout; auto-dismiss is currently disabled",
                    )
                    return
                }
            } catch (error: Throwable) {
                android.util.Log.w(
                    "BubbleNotify",
                    "unable to verify timeout policy; preserving bubble", error,
                )
                return
            }
        }
        if (timeoutSeconds <= 0) {
            android.util.Log.i("BubbleNotify", "ignored stale timeout; auto-dismiss disabled")
            return
        }
        if (!dimOnTimeout) {
            // 到点不消失，也不原地变暗：收起到最近的屏幕边缘（半隐藏、可再拖回来）。
            if (dockingAllowed) collapseToEdge() else dismiss("timeout")
            return
        }
        timeoutDimmed = true
        stopBreathingAnimation()
        stopLandingAnimation()
        animate().cancel()
        animate().alpha(0.35f).setDuration(360L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun restoreFromTimeoutDim() {
        if (!timeoutDimmed || dockedEdge != 0) return
        timeoutDimmed = false
        animate().cancel()
        animate().alpha(1f).setDuration(150L).start()
        if (timeoutSeconds > 0) {
            mainHandler.removeCallbacks(timeoutAction)
            mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L)
        }
    }

    private fun beginDrag() {
        if (!pointerDown || dismissing || !attached) return
        dragging = true
        // A long-press that turns into movement is a drag, never a deferred
        // expand action when the finger is released.
        longPressHandled = false
        mainHandler.removeCallbacks(timeoutAction)
        stopBreathingAnimation()
        stopLandingAnimation()
        stopDockBreathing()
        alpha = 1f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragStartedAction?.invoke()
        animate().cancel()
        animate().scaleX(1.08f).scaleY(1.08f).setDuration(140L).start()
    }

    private fun handleLongPress() {
        if (!pointerDown || dismissing || !attached) return
        val callback = longPressCallback
        if (callback != null) {
            longPressHandled = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        beginDrag()
    }

    private fun updateDrag(distanceX: Float, distanceY: Float) {
        params.x = Math.round(
            clamp(
                downWindowX + distanceX,
                -viewSize / 2f,
                screenWidth - viewSize / 2f,
            ),
        )
        params.y = Math.round(
            clamp(downWindowY + distanceY, safeTop.toFloat(), safeBottom.toFloat()),
        )
        try {
            windowManager!!.updateViewLayout(this, params)
        } catch (ignored: Throwable) {
        }
    }

    private fun finishDrag() {
        dragging = false
        params.y = Math.round(
            clamp(params.y.toFloat(), safeTop.toFloat(), safeBottom.toFloat()),
        )
        if (params.y >= safeBottom - dp(52f)) {
            dismissToBottom()
            return
        }
        val centerX = params.x + viewSize / 2f
        val snapDistance = dp(52f)
        if (centerX <= snapDistance) {
            animateToDock(-1)
        } else if (centerX >= screenWidth - snapDistance) {
            animateToDock(1)
        } else {
            stopDockBreathing()
            dockedEdge = 0
            restoreVisibleAlpha()
            params.x = Math.round(
                clamp(params.x.toFloat(), visibleLeft.toFloat(), visibleRight.toFloat()),
            )
            updateWindowLayout()
            notifyPositionChanged()
        }
        animate().cancel()
        animate()
            .alpha(if (dockedEdge == 0) 1f else 0.5f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        if (dockedEdge == 0) {
            if (!landingSuppressed) {
                startBreathingAnimation()
            }
            if (timeoutSeconds > 0) {
                mainHandler.postDelayed(timeoutAction, timeoutSeconds * 1000L)
            }
        }
    }

    private fun dismissToBottom() {
        if (!attached || dismissing) return
        dismissing = true
        val action = bottomDismissAction
        if (action != null) action.invoke()
        mainHandler.removeCallbacks(timeoutAction)
        mainHandler.removeCallbacks(longPressAction)
        stopBreathingAnimation()
        stopLandingAnimation()
        stopDockBreathing()
        animate().cancel()
        animate().alpha(0f).scaleX(0.38f).scaleY(0.38f)
            .translationY(dp(76f) * bubbleScale).setDuration(260L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeImmediately()
                }
            }).start()
    }

    private fun dispatchSwipe(distanceX: Float, distanceY: Float): Boolean {
        val threshold = dp(38f).toFloat()
        if (Math.abs(distanceX) < threshold && Math.abs(distanceY) < threshold) return false
        if (Math.abs(distanceX) > Math.abs(distanceY)) {
            if (dockingAllowed) animateToDock(if (distanceX < 0f) -1 else 1)
            return true
        }
        swipeListener?.onSwipe(distanceY < 0f)
        return true
    }

    /**
     * Temporary-notification setting: keep the bubble floating instead of docking to an edge.
     * Must be safe to call before the window is attached (showQueueHead configures the bubble
     * before show()), otherwise WindowManager.updateViewLayout throws and takes SystemUI down.
     */
    fun setDockingEnabled(enabled: Boolean) {
        dockingAllowed = enabled
        if (enabled || dockedEdge == 0) return
        dockedEdge = 0
        stopDockBreathing()
        alpha = 1f
        params.x = Math.round(
            clamp(
                params.x.toFloat(), 0f,
                Math.max(0, screenWidth - viewSize).toFloat(),
            ),
        )
        if (!attached || dismissing) return
        try {
            windowManager!!.updateViewLayout(this, params)
        } catch (error: Throwable) {
            shownError = error
        }
    }

    /**
     * 从贴边状态弹回浮动位置。新消息到达时调用：贴边只是上一颗的临时收起状态，
     * 不应该让下一条消息继续半隐藏在边上。
     */
    fun popOut() {
        if (!attached || dockedEdge == 0 || dismissing) return
        dockedEdge = 0
        timeoutDimmed = false
        stopBreathingAnimation()
        stopDockBreathing()
        dockAnimator?.cancel()
        dockAnimator = null
        val targetX =
            Math.round(clamp(floatingX.toFloat(), visibleLeft.toFloat(), visibleRight.toFloat()))
        val targetY =
            Math.round(clamp(floatingY.toFloat(), safeTop.toFloat(), safeBottom.toFloat()))
        val startX = params.x
        val startY = params.y
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 220L
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { a ->
            val progress = a.animatedValue as Float
            params.x = Math.round(lerp(startX.toFloat(), targetX.toFloat(), progress))
            params.y = Math.round(lerp(startY.toFloat(), targetY.toFloat(), progress))
            updateWindowLayout()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (canceled) return
                notifyPositionChanged()
                startBreathingAnimation()
            }
        })
        dockAnimator = animator
        animator.start()
        animate().cancel()
        animate().alpha(1f).setDuration(180L).start()
    }

    private fun animateToDock(edge: Int) {
        if (!attached || !dockingAllowed) return
        if (dockedEdge == 0) {
            floatingX = params.x
            floatingY = params.y
        }
        dockedEdge = Integer.compare(edge, 0)
        stopDockBreathing()
        val startX = params.x
        val targetX = if (dockedEdge < 0) -viewSize / 2 else screenWidth - viewSize / 2
        dockAnimator?.cancel()
        val animator = ValueAnimator.ofInt(startX, targetX)
        animator.duration = 260L
        animator.interpolator = DecelerateInterpolator(1.7f)
        animator.addUpdateListener { a ->
            params.x = a.animatedValue as Int
            updateWindowLayout()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                dockAnimator = null
                if (!canceled) {
                    notifyPositionChanged()
                    startDockBreathing()
                }
            }
        })
        animate().cancel()
        animate().alpha(0.5f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        dockAnimator = animator
        animator.start()
    }

    private fun updateWindowLayout() {
        try {
            windowManager!!.updateViewLayout(this, params)
        } catch (ignored: Throwable) {
        }
    }

    private fun startDockBreathing() {
        stopBreathingAnimation()
        if (!attached || dockedEdge == 0 || dismissing || landingSuppressed) return
        if (dockBreathAnimator != null) return
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 2600L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.repeatMode = ValueAnimator.REVERSE
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { animation ->
            if (!attached || dockedEdge == 0 || dismissing) return@addUpdateListener
            val progress = animation.animatedValue as Float
            val wave = Math.sin(progress * Math.PI).toFloat()
            val baseAlpha = if (timeoutDimmed) 0.35f else 0.5f
            alpha = baseAlpha - wave * 0.15f
            val scale = 1f - wave * 0.055f
            scaleX = scale
            scaleY = scale + wave * 0.018f
        }
        dockBreathAnimator = animator
        animator.start()
    }

    private fun stopDockBreathing() {
        dockBreathAnimator?.cancel()
        dockBreathAnimator = null
        if (!dismissing) {
            scaleX = 1f
            scaleY = 1f
            if (dockedEdge == 0) alpha = if (timeoutDimmed) 0.35f else 1f
        }
    }

    private fun updateImeAvoidance() {
        val wm = windowManager
        if (!attached || wm == null) return
        if (dragging) {
            if (imeAvoiding) imeRestoreY = params.y
            mainHandler.postDelayed(imeAvoidanceAction, 100L)
            return
        }
        try {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val windowInsets = metrics.windowInsets
            val ime = windowInsets.getInsets(WindowInsets.Type.ime())
            val visible = windowInsets.isVisible(WindowInsets.Type.ime()) &&
                ime.bottom > dp(48f)
            var targetY = params.y
            if (visible) {
                if (!imeAvoiding) {
                    imeAvoiding = true
                    imeRestoreY = params.y
                }
                val imeTop = bounds.bottom - ime.bottom
                targetY = Math.min(imeRestoreY, imeTop - viewSize - dp(12f))
                targetY = Math.max(safeTop, targetY)
            } else if (imeAvoiding) {
                targetY = imeRestoreY
            }
            animateImeTo(targetY, !visible && imeAvoiding)
        } catch (ignored: Throwable) {
        }
        // Visibility changes are polled, while the actual movement is frame-paced
        // by ValueAnimator to avoid the old 25fps stepped motion.
        if (attached) mainHandler.postDelayed(imeAvoidanceAction, 100L)
    }

    private fun animateImeTo(targetY: Int, restoring: Boolean) {
        val target = Math.round(
            clamp(targetY.toFloat(), safeTop.toFloat(), safeBottom.toFloat()),
        )
        val running = imeAnimator
        if (running != null && running.isRunning && imeTargetY == target) return
        if (params.y == target) {
            if (restoring) imeAvoiding = false
            return
        }
        imeAnimator?.cancel()
        val startY = params.y
        imeTargetY = target
        val animator = ValueAnimator.ofInt(startY, target)
        animator.duration = if (restoring) 230L else 180L
        animator.interpolator = DecelerateInterpolator(1.35f)
        animator.addUpdateListener { animation ->
            if (!attached) return@addUpdateListener
            params.y = animation.animatedValue as Int
            updateWindowLayout()
            layoutChangedAction?.invoke()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var canceled = false

            override fun onAnimationCancel(animation: Animator) {
                canceled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!canceled && restoring && Math.abs(params.y - imeRestoreY) <= 2) {
                    params.y = imeRestoreY
                    imeAvoiding = false
                }
                if (imeAnimator === animation) imeAnimator = null
            }
        })
        imeAnimator = animator
        animator.start()
    }

    private fun notifyPositionChanged() {
        val rangeX = Math.max(1f, (visibleRight - visibleLeft).toFloat())
        val rangeY = Math.max(1f, (safeBottom - safeTop).toFloat())
        val clampedX = clamp(params.x.toFloat(), visibleLeft.toFloat(), visibleRight.toFloat())
        val x = Math.round((clampedX - visibleLeft) / rangeX * 100f)
        val y = Math.round((params.y - safeTop) / rangeY * 100f)
        if (dockedEdge == 0) {
            floatingX = params.x
            floatingY = params.y
        }
        positionListener?.onPositionChanged(landscape, x, y, dockedEdge)
    }

    private fun isCoreTouch(event: MotionEvent): Boolean {
        val dx = event.x - width / 2f
        val dy = event.y - height / 2f
        val touchRadius = dp(CORE_RADIUS_DP) * bubbleScale + dp(8f)
        return dx * dx + dy * dy <= touchRadius * touchRadius
    }

    private fun resolvePosition(config: BubbleVisualConfig) {
        refreshSafeBounds()

        landscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val horizontalRange = Math.max(0, visibleRight - visibleLeft)
        val verticalRange = Math.max(0, safeBottom - safeTop)
        params.x = visibleLeft +
            Math.round(horizontalRange * config.positionX(landscape) / 100f)
        params.y = safeTop +
            Math.round(verticalRange * config.positionY(landscape) / 100f)
        dockedEdge = config.dockEdge(landscape)
        if (dockedEdge < 0) params.x = -viewSize / 2
        if (dockedEdge > 0) params.x = screenWidth - viewSize / 2
        if (dockedEdge == 0) {
            floatingX = params.x
            floatingY = params.y
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float =
        Math.max(min, Math.min(max, value))

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private fun smooth(progress: Float): Float {
        val value = clamp(progress, 0f, 1f)
        return value * value * (3f - 2f * value)
    }

    private fun backIn(value: Float): Float {
        val tension = 1.35f
        return value * value * ((tension + 1f) * value - tension)
    }

    private fun backOut(value: Float): Float {
        val tension = 1.55f
        val shifted = value - 1f
        return 1f + shifted * shifted * ((tension + 1f) * shifted + tension)
    }

    private fun deform(target: Float, amplitude: Float): Float =
        1f + (target - 1f) * amplitude

    internal fun interface PositionListener {
        fun onPositionChanged(landscape: Boolean, x: Int, y: Int, dock: Int)
    }

    internal fun interface SwipeListener {
        /** true for an upward swipe; false for a downward swipe. */
        fun onSwipe(up: Boolean)
    }

    private fun dp(value: Float): Int =
        Math.round(value * resources.displayMetrics.density)

    private companion object {
        /** 落地回弹/循环呼吸位移开关；关闭后气泡只在原地淡入。 */
        const val LANDING_BOUNCE_ENABLED = true
        private const val VIEW_DP = 92f
        private const val CORE_RADIUS_DP = 29f
        private const val ICON_RADIUS_DP = 20f

        /** Upward travel after a long press that turns the gesture into "fan out the stack". */
        private const val EXPAND_SWIPE_DP = 22f

        /** Base size of the corner app badge; the user setting scales this. */
        private const val BADGE_BASE_DP = 16f
        private const val BADGE_CENTER_X_DP = 13.5f
        private const val BADGE_CENTER_Y_DP = 14.5f
    }
}
