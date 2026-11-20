package io.github.hoerire.free.window

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.RoundedCorner
import android.view.WindowInsets
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.RadialShimmer
import io.github.hoerire.free.gesture.CornerSide
import io.github.hoerire.free.gesture.RadialGeometry
import io.github.hoerire.free.gesture.GesturePoint
import io.github.hoerire.free.gesture.RadialLayout
import io.github.hoerire.free.platform.coloros.AppCatalogSnapshot
import io.github.hoerire.free.platform.coloros.RadialEntry
import io.github.hoerire.free.ui.theme.CornerOverlayTheme
import io.github.hoerire.free.ui.theme.LocalMorePanelColors
import io.github.hoerire.free.ui.theme.LocalRadialItemColors
import io.github.hoerire.free.ui.theme.RadialItemColors
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("ViewConstructor")
/**
 * 扇形浮层的 Compose 容器（运行在 system_server）。
 *
 * 职责：绘制扇形/更多面板、把触摸坐标换算成选中格（[RadialGeometry]）、
 * 管理入场/退场动画与提交时机。
 *
 * 提交时机（[dismissAnimated]）：松手当帧即回调 [Listener.onEntryCommitted]，
 * 不等 200ms 退场动画；浮层随后自行淡出。取屏类工具由宿主 [removeOverlay][io.github.hoerire.free.platform.coloros.ColorOsFreeformCoordinator]
 * 立即移除并通过两帧等待保证屏幕干净，因此不需要在此区分目标类型。
 *
 * 格数 = 应用数 + 1，最后一格固定是「更多」面板入口，不参与应用列表索引。
 */
internal class CornerRadialOverlayView(
    context: Context,
    private val listener: Listener,
    private val debug: (String) -> Unit = {},
) : AbstractComposeView(context), LifecycleOwner, SavedStateRegistryOwner {
    interface Listener {
        fun onEntryCommitted(entry: RadialEntry)

        fun onMorePanelRequested()

        fun onDismissRequested()

        fun onCleanupFailed(throwable: Throwable)
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val metricsState = mutableStateOf<AdaptiveOverlayMetrics?>(null)
    private val layoutState = mutableStateOf(EMPTY_LAYOUT)
    private val selectedIndexState = mutableIntStateOf(NO_SELECTION)
    private val panelModeState = mutableStateOf(false)
    private val backdropOnlyState = mutableStateOf(false)

    /** 遮罩浓度（0..1），由设置注入；0 表示完全透明。 */
    private val backdropAlphaState = mutableStateOf(BACKDROP_ALPHA)

    fun setBackdropAlpha(alpha: Float) {
        backdropAlphaState.value = alpha.coerceIn(0f, 1f)
    }


    private val backdropAlpha = mutableFloatStateOf(1f)
    private var backdropAnimator: ValueAnimator? = null
    private val entryProgress = Animatable(0f)


    private val handoffEntryProgress = mutableFloatStateOf(0f)
    private val handoffLayoutState = mutableStateOf(EMPTY_LAYOUT)
    private val handoffMetricsState = mutableStateOf<RadialVisualMetrics?>(null)
    private var appliedWindowInsets: WindowInsets? = null
    private var catalog = AppCatalogSnapshot()
    private val radialClipPath = Path()
    private val radialImages = mutableStateOf<List<RadialImage>>(emptyList())
    private var panelImages: Map<io.github.hoerire.free.config.PinnedTarget, ImageBitmap> = emptyMap()
    private var side = CornerSide.Right
    private var radialHitRangeDp = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP
    private var radialIconSizeDp = ModulePreferences.DEFAULT_RADIAL_ICON_SIZE_DP

    /** 扇形半径百分比：左右两侧共用，随每次呼出由设置注入。 */
    private var radialRadiusPercent = ModulePreferences.DEFAULT_RADIAL_RADIUS_PERCENT

    /** 选中光圈：滑选条目时是否在图标外沿画细白边与投影（静态）。 */

    /** 流光特效：常亮光圈、星头与运动轨迹（动态），独立于选中光圈。 */
    private var shimmerEnabled = ModulePreferences.DEFAULT_RADIAL_SHIMMER_ENABLED

    /** 选中流光的可调参数，随每次呼出由设置注入。 */
    private var shimmer = RadialShimmer()
    /** 浮层显示时刻：呼出手势的抬手会作为 tap 落到这里，必须忽略。 */
    private var shownAtUptime = 0L
    /** 「永久保持」：松手未选中时保留浮层，并允许直接点选条目。 */
    private var keepOpen = false
    private var latestX = 0f
    private var latestY = 0f

    /**
     * 手指当前位置（绘制阶段读取）。
     *
     * 用 Compose 状态而不是普通字段：MOVE 每帧都更新，Canvas 直接读它，
     * 只在绘制阶段失效重绘，不触发整棵树重组。扇形「跟随距离拉伸」就靠它驱动。
     */
    private val fingerX = mutableFloatStateOf(0f)
    private val fingerY = mutableFloatStateOf(0f)

    private var selectedIndex: Int? = null
    /** 本次手势里手指是否到过外圈：只有到过，「往内角回拉」才按取消处理。 */
    private var reachedOutward = false
    /** 手指开始停在内圈的时刻；0 表示当前不在内圈。 */
    private var inwardSince = 0L
    private var dismissing = false
    private var dismissNotified = false
    /** 松手当帧已把目标交给宿主（应用/快捷方式），退场动画结束后只做收尾。 */
    private var commitNotified = false

    /**
     * 收起动画进行中。
     *
     * 取消手势时不再"啪"地消失：反向播放一遍入场曲线，图标沿原轨迹缩回底角，
     * 播完才通知宿主移除窗口。选中目标那条路径不等动画（启动速度 + 取屏工具要求屏幕立刻干净）。
     */
    private val retracting = mutableStateOf(false)

    /** 系统动画开关的缓存（组合里算一次），供非组合上下文的 dismissAnimated 读取。 */
    private var exitAnimationsEnabled = true

    /** 本次缩回是否用"选中后"的短时长。 */
    private var retractQuick = false
    private var disposed = false
    private var lastTickUptime = 0L
    private val timeout = Runnable(::requestDismiss)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun updateRadialAppearance(next: AppCatalogSnapshot) {
        if (disposed || dismissing || panelModeState.value) return
        // 只替换同一批应用的图像，不在滑选途中交换目标或重置入场动画。
        if (catalog.radialApps.map { it.target } != next.radialApps.map { it.target }) return
        radialImages.value = next.radialApps.map(::RadialImage)
    }

    fun begin(
        side: CornerSide,
        catalog: AppCatalogSnapshot,
        x: Float,
        y: Float,
        hitRangeDp: Int = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
        iconSizeDp: Int = ModulePreferences.DEFAULT_RADIAL_ICON_SIZE_DP,
        radiusPercent: Int = ModulePreferences.DEFAULT_RADIAL_RADIUS_PERCENT,
        keepOpen: Boolean = false,
        shimmerEnabled: Boolean = ModulePreferences.DEFAULT_RADIAL_SHIMMER_ENABLED,
        shimmer: RadialShimmer = RadialShimmer(),
    ) {
        check(!isAttachedToWindow) { "Overlay must be initialized before it is attached" }
        this.keepOpen = keepOpen
        this.shimmerEnabled = shimmerEnabled
        this.shimmer = shimmer
        this.side = side
        // 保持挂起不改变命中判定：滑动选格与点选统一使用「命中范围」的设定值。
        this.radialHitRangeDp = ModulePreferences.coerceRadialHitRangeDp(hitRangeDp)
        this.radialIconSizeDp = ModulePreferences.coerceRadialIconSizeDp(iconSizeDp)
        this.radialRadiusPercent = ModulePreferences.coerceRadialRadiusPercent(radiusPercent)
        shownAtUptime = android.os.SystemClock.uptimeMillis()
        entrySettled = false
        this.catalog = catalog
        latestX = x
        latestY = y
        fingerX.floatValue = x
        fingerY.floatValue = y
         radialImages.value = catalog.radialApps.map(::RadialImage)
        panelImages = catalog.panelApps.associate { entry -> entry.target to entry.icon.asImageBitmap() }
        selectedIndex = null
        reachedOutward = false
        inwardSince = 0L
        selectedIndexState.intValue = NO_SELECTION
        handoffEntryProgress.floatValue = 0f
        handoffLayoutState.value = EMPTY_LAYOUT
        handoffMetricsState.value = null
        panelModeState.value = false
        dismissing = false
        dismissNotified = false
        commitNotified = false
        retracting.value = false
        val displayMetrics = resources.displayMetrics
        updateLayout(
            requestedWidth = displayMetrics.widthPixels,
            requestedHeight = displayMetrics.heightPixels,
            safeInsets = OverlaySafeInsets(),
        )
        removeCallbacks(timeout)
        if (!keepOpen) postDelayed(timeout, GESTURE_TIMEOUT_MS)
    }

    /**
     * 选中目标后的收拢动画：应用启动不等它，窗口移除推迟到动画播完。
     *
     * 取屏类工具要求屏幕立刻干净，协调器不会调用本方法（仍走立即移除）。
     */
    fun retractAnimated(quick: Boolean) {
        if (disposed || retracting.value) return
        if (!exitAnimationsEnabled) return
        retractQuick = quick
        retracting.value = true
    }

    fun updateGesture(x: Float, y: Float): Int? {
        latestX = x
        latestY = y
        fingerX.floatValue = x
        fingerY.floatValue = y
        if (panelModeState.value || dismissing) return selectedIndex
        removeCallbacks(timeout)
        if (!keepOpen) postDelayed(timeout, GESTURE_TIMEOUT_MS)
        updateGestureFromLatestPoint()
        return selectedIndex
    }

    /** 入场动画结束标志：由渲染层的动画完成回调置位，抬手只读它。 */
    @Volatile private var entrySettled = false

    /** 入场动画是否已经播完；没播完就抬手说明是误触，不该启动应用。 */
    fun isSettled(): Boolean = entrySettled

    fun finishGesture() {
        val selection = selectedIndex
        val selectionLabel =
            when {
                selection == null -> "NONE"
                selection < catalog.radialApps.size ->
                    catalog.radialApps.getOrNull(selection)?.label ?: "APP_" + selection
                else -> "MORE_PANEL"
            }
        debug(
            "SYSTEM_HIT_RELEASE_" + (selection ?: -1) + "_" + selectionLabel +
                "_HITRADIUS_" + (radialHitRangeDp * resources.displayMetrics.density).toInt() + "PX",
        )
        when {
            // 抬手即结束：保持挂起模式下未选中时不收起，其余情况未选中则收起。
            selection == null && keepOpen -> removeCallbacks(timeout)
            selection == null -> dismissAnimated()
            selection < catalog.radialApps.size -> dismissAnimated(catalog.radialApps[selection])
            else -> showMorePanel()
        }
    }

    fun cancelGesture() {
        dismissAnimated()
    }

    fun dismissAnimated() = dismissAnimated(pendingCommit = null)

    fun disposeOverlay(): Throwable? {
        if (disposed) return null
        disposed = true
        backdropAnimator?.cancel()
        backdropAnimator = null
        removeCallbacks(timeout)
        var failure: Throwable? = null
        try {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            disposeComposition()
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        try {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        } catch (exception: RuntimeException) {
            failure = mergeCleanupFailure(failure, exception)
        } catch (error: LinkageError) {
            failure = mergeCleanupFailure(failure, error)
        }
        return failure
    }

    @Composable
    override fun Content() {
        CornerOverlayTheme {
            val metrics = metricsState.value
            val layout = layoutState.value
            if (metrics != null) {
                OverlayContent(metrics = metrics, layout = layout)
            }
        }
    }

    @Composable
    private fun OverlayContent(
        metrics: AdaptiveOverlayMetrics,
        layout: RadialLayout,
    ) {
        val panelProgress = remember { Animatable(0f) }
        val panelContentProgress = remember { Animatable(0f) }
        val radialHandoffProgress = remember { Animatable(0f) }

        val panelInputEnabled = remember { mutableStateOf(false) }
        val itemRings =
            remember(layout.itemCenters.size) {
                List(layout.itemCenters.size) { Animatable(0f) }
            }
        // 动画时长跟系统的「动画时长缩放」走：缩放为 0 时直接跳到终态，
        // 缩放大于 1 时同比例拉长，避免模块动画比系统窗口动画先结束。
        val motionScale = remember { SystemMotion.durationScale() }
        val animationsEnabled = remember { SystemMotion.animationsRunning(motionScale) }
        // Dock 隆起圆心跟手（3.1.9 的做法）：手指给出目标，弹簧追过去。
        // dampingRatio < 1 会带一点过冲，指针扫过时图标"duang"一下再回落。
        val dockX by
            androidx.compose.animation.core.animateFloatAsState(
                targetValue = fingerX.floatValue,
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.55f,
                        stiffness = 520f,
                    ),
                label = "dockX",
            )
        val dockY by
            androidx.compose.animation.core.animateFloatAsState(
                targetValue = fingerY.floatValue,
                animationSpec =
                    androidx.compose.animation.core.spring(
                        dampingRatio = 0.55f,
                        stiffness = 520f,
                    ),
                label = "dockY",
            )
        androidx.compose.runtime.SideEffect { exitAnimationsEnabled = animationsEnabled }
        // 诊断：进场动画是否真的在跑（仅详细日志时输出）。
        remember(animationsEnabled, motionScale) {
            io.github.hoerire.free.log.ModuleLog.debug(
                "ANIM",
            ) { "SYSTEM_ANIM_ENTRY_enabled_" + animationsEnabled + "_scale_" + motionScale }
        }

        LaunchedEffect(panelModeState.value, backdropOnlyState.value) {
            if (panelModeState.value || backdropOnlyState.value) return@LaunchedEffect
            if (!animationsEnabled) {
                entryProgress.snapTo(1f)
            } else {
                // 首帧补偿：动画是在组合完成、第一帧画出来才开始跑的，
                // 而用户是从按下那一刻开始计时的。把 begin() 到现在已经过去的时间
                // 从总预算里扣掉，展开完成的时刻就固定在 begin() + 预算上。
                val total = SystemMotion.scaledMillis(RadialEntryMotion.DURATION_MILLIS, motionScale)
                val elapsed =
                    (android.os.SystemClock.uptimeMillis() - shownAtUptime)
                        .toInt()
                        .coerceIn(0, total)
                val start = elapsed.toFloat() / total
                val remaining = (total - elapsed).coerceAtLeast(0)
                io.github.hoerire.free.log.ModuleLog.debug("ANIM") {
                    "SYSTEM_ANIM_ENTRY_compensated_elapsed_" + elapsed + "_remaining_" + remaining +
                        "_total_" + total
                }
                if (start >= 1f || remaining <= 0) {
                    entryProgress.snapTo(1f)
                } else {
                    entryProgress.snapTo(start)
                    // 误触判定在展开 60% 时就放行：入场曲线为了"软回弹"放慢了，
                    // 若仍等整段动画跑完（380ms），快速轻扫会被大面积误判成误触。
                    kotlinx.coroutines.coroutineScope {
                        launch {
                            delay((remaining * SETTLE_FRACTION).toLong())
                            entrySettled = true
                        }
                        entryProgress.animateTo(
                            targetValue = 1f,
                            animationSpec =
                                tween(
                                    durationMillis = remaining,
                                    easing = LinearEasing,
                                ),
                        )
                    }
                }
            }
            // 入场动画结束事件：抬手判定只读这个标志，不做任何时间计算。
            entrySettled = true
        }
        // 收起：把入场进度线性退回 0，配合 RadialEntryMotion 的曲线就是"倒放展开"，
        // 图标沿各自的错峰轨迹缩回底角；结束后才让宿主移除窗口。
        LaunchedEffect(retracting.value) {
            if (!retracting.value) return@LaunchedEffect
            if (animationsEnabled) {
                val durationMillis =
                    if (retractQuick) {
                        RadialExitMotion.COMMIT_RETRACT_DURATION_MILLIS
                    } else {
                        RadialExitMotion.RETRACT_DURATION_MILLIS
                    }
                entryProgress.animateTo(
                    targetValue = 0f,
                    animationSpec =
                        tween(
                            durationMillis = SystemMotion.scaledMillis(durationMillis, motionScale).toInt(),
                            easing = LinearEasing,
                        ),
                )
            } else {
                entryProgress.snapTo(0f)
            }
            completeRadialExit()
        }
        LaunchedEffect(selectedIndexState.intValue, itemRings) {
            val selected = selectedIndexState.intValue
            coroutineScope {
                itemRings.forEachIndexed { index, ring ->
                    launch {
                        val target = if (index == selected) 1f else 0f
                        if (!animationsEnabled) {
                            ring.snapTo(target)
                        } else {
                            ring.animateTo(
                                targetValue = target,
                                animationSpec = tween(
                                    RadialEntryMotion.SELECTION_DURATION_MILLIS.toInt(),
                                    easing = { RadialEntryMotion.selectionProgress(it) },
                                ),
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(panelModeState.value) {
            if (panelModeState.value) {
                panelInputEnabled.value = false
                if (!animationsEnabled) {
                    radialHandoffProgress.snapTo(1f)
                    panelProgress.snapTo(1f)
                    panelContentProgress.snapTo(1f)
                    panelInputEnabled.value = true
                } else {
                    coroutineScope {
                        launch {
                            radialHandoffProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = RadialHandoffMotion.DURATION_MILLIS.toInt(),
                                        easing = LinearEasing,
                                    ),
                            )
                        }
                        launch {
                            panelProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    spring(
                                        dampingRatio = PANEL_EXPAND_DAMPING,
                                        stiffness = PANEL_EXPAND_STIFFNESS,
                                        visibilityThreshold = PANEL_VISIBILITY_THRESHOLD,
                                    ),
                            )
                        }
                        launch {
                            panelContentProgress.animateTo(
                                targetValue = 1f,
                                animationSpec =
                                    tween(
                                        durationMillis = PANEL_CONTENT_FADE_MILLIS,
                                        delayMillis = PANEL_CONTENT_DELAY_MILLIS,
                                        easing = LinearOutSlowInEasing,
                                    ),
                            )
                            panelInputEnabled.value = true
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val radialLayout = if (panelModeState.value) handoffLayoutState.value else layout
            val radialMetrics =
                if (panelModeState.value) {
                    handoffMetricsState.value ?: metrics.radial
                } else {
                    metrics.radial
                }
            RadialCanvas(
                metrics = radialMetrics,
                layout = radialLayout,
                panelProgress = panelProgress,
                radialHandoffProgress = radialHandoffProgress,
                itemRings = itemRings,
                dockX = dockX,
                dockY = dockY,
                animationsEnabled = animationsEnabled,
            )
            if (keepOpen && !panelModeState.value) {
                // 保持显示期间窗口可触摸：点中条目直接启动，点空白处收起。
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(radialLayout, catalog, radialHitRangeDp) {
                                detectTapGestures { offset ->
                                    // 呼出手势松手时系统会把这次抬手补成一个 tap，
                                    // 落点在底角附近，会命中最近的格子并立刻开小窗。
                                    // 显示后的极短时间内收到的点击一律忽略。
                                    if (android.os.SystemClock.uptimeMillis() - shownAtUptime < OPEN_GRACE_MS) {
                                        return@detectTapGestures
                                    }
                                    // 与圆环同一套缩放单位：用 unit 而不是原始 density，
                                    // 否则大屏上命中圈会明显小于设置值。
                                    // 与手势选择一致：命中半径按屏幕 dp 直算，不跟布局缩放。
                                    val hitRadius = radialHitRangeDp * resources.displayMetrics.density
                                    val index =
                                        RadialGeometry.selection(
                                            layout = radialLayout,
                                            x = offset.x,
                                            y = offset.y,
                                            previous = null,
                                            enterRadius = hitRadius,
                                            keepRadius = 0f,
                                        )
                                    if (index == null) {
                                        dismissAnimated()
                                    } else if (index < catalog.radialApps.size) {
                                        dismissAnimated(catalog.radialApps[index])
                                    } else {
                                        showMorePanel()
                                    }
                                }
                            },
                )
            }
            if (panelModeState.value) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    requestDismiss()
                                }
                            },
                )
                MoreAppsPanel(
                    metrics = metrics.panel,
                    panelProgress = panelProgress,
                    contentProgress = panelContentProgress,
                    inputEnabled = panelInputEnabled.value,
                    animationsEnabled = animationsEnabled,
                )
            }
        }
    }

    @Composable
    private fun RadialCanvas(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        panelProgress: Animatable<Float, *>,
        radialHandoffProgress: Animatable<Float, *>,
        itemRings: List<Animatable<Float, *>>,
        dockX: Float,
        dockY: Float,
        animationsEnabled: Boolean,
    ) {
        val itemColors = LocalRadialItemColors.current
        // 选中流光的旋转相位：只在动画可用时逐帧推进，关闭动画时停在固定角度。
        val shimmerAngle = remember { mutableFloatStateOf(0f) }
        if (animationsEnabled) {
            LaunchedEffect(Unit) {
                var origin = -1L
                while (true) {
                    withFrameNanos { now ->
                        if (origin < 0L) origin = now
                        shimmerAngle.floatValue =
                            ((now - origin) / NANOS_PER_SECOND * shimmer.speedDegPerSecond) % FULL_TURN_DEGREES
                    }
                }
            }
        }
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            if (backdropOnlyState.value) {
                // 「更多」面板期间不画遮罩：那块面板是系统的独立窗口，模块这层暗度会把它压暗，
                // 表现为黑色遮罩覆盖在更多菜单之上。
                return@Canvas
            }
            val panel =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    panelProgress.value.coerceIn(0f, 1f)
                }
            val handoff =
                if (panelModeState.value && !animationsEnabled) {
                    1f
                } else {
                    radialHandoffProgress.value
                }
            val entrySample = RadialEntryMotion.sample(
                when {
                    !animationsEnabled -> 1f
                    panelModeState.value -> handoffEntryProgress.floatValue
                    else -> entryProgress.value
                },
            )
            val handoffVisuals = RadialHandoffMotion.sample(
                frozenRevealProgress = entrySample.contentAlpha,
                handoffProgress = handoff,
                panelProgress = panel,
            )
            val visual = entrySample
            // 遮罩由这里画（窗口不用 FLAG_DIM_BEHIND）：随内容透明度一起淡入淡出，
            // 比系统 dim 更贴合入场/收回的节奏。背景模糊不在这里：是否模糊由
            // Coordinator 按「系统模糊」开关挂到窗口表面上，这一层只负责纯色。
            val backdropAlpha = backdropAlphaState.value * visual.contentAlpha
            if (backdropAlpha > 0f) {
                drawRect(Color.Black, alpha = backdropAlpha)
            }
            // 进场期间图标恒定不透明：半径展开和位移才是动画主体，
            // 跟着淡入走会让前段半透明叠上高速位移，看起来像残影。
            // 淡入只保留给背景遮罩（上面那行 backdropAlpha），面板切换时仍按 handoff 淡出。
            val alpha = if (panelModeState.value) visual.contentAlpha * handoffVisuals.contentAlpha else 1f
            if (alpha <= 0f) return@Canvas
            drawRadialItems(
                metrics = metrics,
                layout = layout,
                motion = visual,
                contentAlpha = alpha,
                contentScale = if (panelModeState.value) handoffVisuals.contentScale else 1f,
                itemRings = itemRings,
                dockX = dockX,
                dockY = dockY,
                colors = itemColors,
                shimmerAngle = shimmerAngle.floatValue,
            )
        }
    }

    private fun DrawScope.drawRadialItems(
        metrics: RadialVisualMetrics,
        layout: RadialLayout,
        motion: RadialEntryVisuals,
        contentAlpha: Float,
        contentScale: Float,
        itemRings: List<Animatable<Float, *>>,
        dockX: Float,
        dockY: Float,
        colors: RadialItemColors,
        shimmerAngle: Float,
    ) {
        val direction = if (layout.side == CornerSide.Left) 1f else -1f
        val overshoot = direction * RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP * metrics.pixelsPerBaseDp * motion.horizontalOvershoot
        // 拉伸强度只看「手指离圆弧有多近」：手指正好落在弧线上时最大，
        // 走向圆心或滑出圆环都会平滑收敛。面板模式（更多面板）不参与。
        val ring = metrics.radius.coerceAtLeast(1f)
        val fingerDx = fingerX.floatValue - layout.origin.x
        val fingerDy = fingerY.floatValue - layout.origin.y
        val fingerDistance = kotlin.math.hypot(fingerDx, fingerDy)
        val fingerAngle = kotlin.math.atan2(fingerDy, fingerDx)
        val proximity =
            if (panelModeState.value) {
                0f
            } else {
                (1f - kotlin.math.abs(fingerDistance / ring - 1f) / STRETCH_FALLOFF)
                    .coerceIn(0f, 1f)
            }
        val globalStretch = 1f + STRETCH_GLOBAL * proximity
        // Dock 隆起：以手指（弹簧平滑后的位置）为圆心，越近的图标抬得越高。
        val dockInfluence = metrics.iconDiameter * DOCK_REACH
        val dockGate =
            if (panelModeState.value) {
                0f
            } else {
                ((motion.radialProgress - 0.85f) / 0.15f).coerceIn(0f, 1f)
            }
        layout.itemCenters.forEachIndexed { index, destination ->
            // 所有格共用同一条展开进度（不做错峰：错峰会拉开图标间距，
            // 视觉上连成一串残影）。展开/收起都由这条进度驱动。
            val localProgress = motion.radialProgress.coerceIn(0f, 1f)
            // 拉伸：整圈按 proximity 外扩，正对手指的那一格再额外外推一点，
            // 观感上是"被手指拉起一角"，而不是整块等比放大。
            val itemVectorX = destination.x - layout.origin.x
            val itemVectorY = destination.y - layout.origin.y
            val itemAngle = kotlin.math.atan2(itemVectorY, itemVectorX)
            val facing = kotlin.math.cos(itemAngle - fingerAngle).coerceAtLeast(0f)
            val rawStretch = globalStretch * (1f + STRETCH_DIRECTIONAL * facing * facing * proximity)
            // 按硬上限收敛：外扩量 = (stretch - 1) × 该格半径，不得超过 STRETCH_MAX_DP。
            val itemRadius = kotlin.math.hypot(itemVectorX, itemVectorY).coerceAtLeast(1f)
            val maxExtra = STRETCH_MAX_DP * metrics.pixelsPerBaseDp
            val extra = (rawStretch - 1f) * itemRadius
            val stretch =
                if (extra > maxExtra) {
                    1f + maxExtra / itemRadius
                } else {
                    rawStretch
                }
            val unitX = itemVectorX / itemRadius
            val unitY = itemVectorY / itemRadius
            val ringProgress = itemRings.getOrNull(index)?.value ?: 0f
            // Dock 隆起：只看指针距离做衰减，弧的两端保持不动。
            val dockDistance =
                kotlin.math.hypot(destination.x - dockX, destination.y - dockY)
            val dockFalloff = (1f - dockDistance / dockInfluence).coerceIn(0f, 1f)
            val emphasis = maxOf(ringProgress, dockFalloff * dockFalloff)
            val dockLift = DOCK_LIFT_DP * metrics.pixelsPerBaseDp * emphasis * dockGate
            // 选中项沿半径向外顶：比例由「选中外顶」设置控制，只有选中的那一格。
            val selectedPush = ringProgress * metrics.iconDiameter * shimmer.selectedPushPercent / 100f
            val radialOffset = selectedPush + dockLift
            val centerX =
                layout.origin.x + itemVectorX * localProgress * stretch +
                    unitX * radialOffset * localProgress + overshoot * localProgress
            val centerY =
                layout.origin.y + itemVectorY * localProgress * stretch +
                    unitY * radialOffset * localProgress
            // 选中放大：只作用于被选中的那一格，默认 0（关闭）；邻格不放大。
            val emphasisScale = 1f + shimmer.selectionScalePercent / 100f * ringProgress
            val scale = motion.iconScale * contentScale * emphasisScale
            val diameter = metrics.iconDiameter * scale
            rotate(motion.rotationDegrees, pivot = Offset(centerX, centerY)) {
                // 外发光归「流光特效」：它的亮度与范围就在流光参数页里。
                if (shimmerEnabled && ringProgress > 0f) {
                    val glowRadius = diameter * shimmer.glowRadiusPercent / 100f
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        Color.White.copy(alpha = shimmer.glowAlphaPercent / 100f),
                                        Color.Transparent,
                                    ),
                                center = Offset(centerX, centerY),
                                radius = glowRadius,
                            ),
                        radius = glowRadius,
                        center = Offset(centerX, centerY),
                        alpha = ringProgress * contentAlpha,
                    )
                }
                if (index < catalog.radialApps.size) {
                    radialImages.value.getOrNull(index)?.let { image ->
                        drawSystemImage(image.icon, centerX, centerY, diameter, contentAlpha)
                    }
                } else {
                    drawMoreItem(centerX, centerY, diameter, contentAlpha, colors)
                }
                // 流光特效：一颗小星头 + 一段由细到粗、由亮到淡的运动轨迹，绕着常亮光圈跑。
                if (shimmerEnabled && ringProgress > 0f) {
                    val arcRadius =
                        diameter / 2f + metrics.itemPadding * scale * shimmer.arcGapPercent / 100f
                    val arcWidth = metrics.itemPadding * scale * shimmer.arcWidthPercent / 100f
                    val ringAlpha = ringProgress * contentAlpha
                    val arcAlpha = ringAlpha * shimmer.arcAlphaPercent / 100f
                    // 光圈：整圈常亮，并随旋转做一次呼吸（角度连续，所以不会跳变）。
                    // 只在头尾之间画一段的话，光带转过去之后这格就空了；常亮这一圈
                    // 让选中态看起来是「一直在发光」，尾迹只是它上面跑得最快的那段。
                    val breath =
                        0.78f + 0.22f * kotlin.math.sin(Math.toRadians(shimmerAngle.toDouble())).toFloat()
                    drawArc(
                        color = Color.White,
                        startAngle = 0f,
                        sweepAngle = FULL_TURN_DEGREES,
                        useCenter = false,
                        topLeft = Offset(centerX - arcRadius, centerY - arcRadius),
                        size = Size(arcRadius * 2f, arcRadius * 2f),
                        alpha = arcAlpha * METEOR_RING_ALPHA * breath,
                        style = Stroke(width = (arcWidth * METEOR_RING_WIDTH_SCALE).coerceAtLeast(METEOR_MIN_WIDTH_PX)),
                    )
                    // 运动轨迹：从星头身后铺开，越靠尾越粗、越淡（二次衰减，像流星尾巴）。
                    val headAngle = shimmerAngle + METEOR_SWEEP_DEGREES
                    for (step in 0 until METEOR_SEGMENTS) {
                        val near = step / METEOR_SEGMENTS.toFloat()
                        val far = (step + 1) / METEOR_SEGMENTS.toFloat()
                        val width =
                            (arcWidth * (METEOR_TAIL_MIN_WIDTH + far) * 0.5f)
                                .coerceAtLeast(METEOR_MIN_WIDTH_PX)
                        val tail = 1f - near
                        val alpha = arcAlpha * tail * tail
                        if (alpha <= 0.01f) continue
                        drawArc(
                            color = Color.White,
                            startAngle = headAngle - METEOR_SWEEP_DEGREES * far,
                            sweepAngle = METEOR_SWEEP_DEGREES * (far - near) + 0.6f,
                            useCenter = false,
                            topLeft = Offset(centerX - arcRadius, centerY - arcRadius),
                            size = Size(arcRadius * 2f, arcRadius * 2f),
                            alpha = alpha,
                            style = Stroke(width = width),
                        )
                    }
                    // 星头：柔和亮点 + 小五角星，半径取光带宽度的一定比例，绕着光圈跑。
                    val radians = Math.toRadians(headAngle.toDouble())
                    val headX = centerX + arcRadius * Math.cos(radians).toFloat()
                    val headY = centerY + arcRadius * Math.sin(radians).toFloat()
                    val starRadius =
                        (arcWidth * METEOR_STAR_RADIUS_SCALE)
                            .coerceAtLeast(METEOR_MIN_STAR_RADIUS_PX)
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors =
                                    listOf(
                                        Color.White.copy(alpha = arcAlpha * METEOR_STAR_GLOW_ALPHA),
                                        Color.Transparent,
                                    ),
                                center = Offset(headX, headY),
                                radius = starRadius * METEOR_STAR_GLOW_SCALE,
                            ),
                        radius = starRadius * METEOR_STAR_GLOW_SCALE,
                        center = Offset(headX, headY),
                    )
                    drawStar(Offset(headX, headY), starRadius, arcAlpha)
                }
                // 左右扇形的角标分别位于左下角、右下角，避开相邻图标，不参与圆形裁剪。
                radialImages.value.getOrNull(index)?.let { image ->
                    image.badge?.let { badge ->
                        drawBadge(
                            badge, centerX, centerY, diameter, contentAlpha,
                            horizontalOffsetFraction = if (layout.side == CornerSide.Left) image.badgeLeftOffsetFraction else 0f,
                        )
                    }
                }
            }
        }
    }

    /** 五角星：外接半径 [radius]，内接半径一半，尖端朝上。 */
    private fun DrawScope.drawStar(center: Offset, radius: Float, alpha: Float) {
        val path = Path()
        val points = 5
        for (index in 0 until points * 2) {
            val r = if (index % 2 == 0) radius else radius * 0.45f
            val angle = Math.toRadians(-90.0 + index * 180.0 / points)
            val x = center.x + r * Math.cos(angle).toFloat()
            val y = center.y + r * Math.sin(angle).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path = path, color = Color.White, alpha = alpha)
    }

    private fun DrawScope.drawBadge(
        badge: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
        horizontalOffsetFraction: Float,
    ) {
        val pixels = size.roundToInt()
        if (pixels <= 0) return
        drawImage(
            image = badge,
            dstOffset = IntOffset(
                (centerX - pixels / 2f + pixels * horizontalOffsetFraction).roundToInt(),
                (centerY - pixels / 2f).roundToInt(),
            ),
            dstSize = IntSize(pixels, pixels),
            alpha = alpha,
            filterQuality = FilterQuality.High,
        )
    }

    /**
     * 按 ColorOS 17 工具栏溢出按钮的观感绘制：圆盘、向下偏移的外沿投影或微光、上下沿高光，
     * 以及横排三点；配色随深浅色切换。比例取自同一按钮 40dp 圆盘内 24dp 图标的实机测量，随图标直径缩放。
     */
    private fun DrawScope.drawMoreItem(
        centerX: Float,
        centerY: Float,
        diameter: Float,
        alpha: Float,
        colors: RadialItemColors,
    ) {
        val radius = diameter / 2f
        if (radius <= 0f) return
        // 去掉原实现的径向光晕与描边圈：只保留圆盘与三点，避免滑到「更多」时多出一层光圈。
        drawCircle(
            brush = Brush.verticalGradient(
                0f to colors.morePlateTop,
                1f to colors.morePlateBottom,
                startY = centerY - radius,
                endY = centerY + radius,
            ),
            radius = radius,
            center = Offset(centerX, centerY),
            alpha = alpha,
        )
        for (offset in -1..1) {
            drawCircle(
                color = colors.moreDot,
                radius = diameter * MORE_DOT_RADIUS_FRACTION,
                center = Offset(centerX + offset * diameter * MORE_DOT_SPACING_FRACTION, centerY),
                alpha = alpha,
            )
        }
    }

    private fun DrawScope.drawSystemImage(
        image: ImageBitmap,
        centerX: Float,
        centerY: Float,
        size: Float,
        alpha: Float,
    ) {
        if (image.width <= 0 || image.height <= 0 || size <= 0f) return
        // 图标已在目录里裁成圆，这里整图满径绘制：不再每帧量内容包围盒。
        val aspectRatio = image.width.toFloat() / image.height
        val drawWidth = if (aspectRatio >= 1f) size else size * aspectRatio
        val drawHeight = if (aspectRatio >= 1f) size / aspectRatio else size
        val destinationSize = IntSize(drawWidth.roundToInt().coerceAtLeast(1), drawHeight.roundToInt().coerceAtLeast(1))
        radialClipPath.rewind()
        radialClipPath.addOval(Rect(centerX - size / 2f, centerY - size / 2f, centerX + size / 2f, centerY + size / 2f))
        clipPath(radialClipPath) {
            drawImage(
                image = image,
                srcSize = IntSize(image.width, image.height),
                dstOffset =
                    IntOffset(
                        (centerX - destinationSize.width / 2f).roundToInt(),
                        (centerY - destinationSize.height / 2f).roundToInt(),
                    ),
                dstSize = destinationSize,
                alpha = alpha,
                filterQuality = FilterQuality.High,
            )
        }
    }

    @Composable
    private fun MoreAppsPanel(
        metrics: PanelVisualMetrics,
        panelProgress: Animatable<Float, *>,
        contentProgress: Animatable<Float, *>,
        inputEnabled: Boolean,
        animationsEnabled: Boolean,
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val panelColors = LocalMorePanelColors.current
        val panelColor = panelColors.surface.copy(alpha = PANEL_SURFACE_ALPHA)
        val labelColor = panelColors.onSurface
        val bounds = metrics.bounds
        val width = with(density) { bounds.width.toDp() }
        val height = with(density) { bounds.height.toDp() }
        val cornerRadius = with(density) { metrics.cornerRadius.toDp() }
        val horizontalPadding = with(density) { metrics.contentHorizontalPadding.toDp() }
        val verticalPadding = with(density) { metrics.topPadding.toDp() }
        val cellHeight = with(density) { metrics.cellHeight.toDp() }
        val iconSize = with(density) { metrics.iconDiameter.toDp() }
        val labelGap = with(density) { (metrics.labelTopOffset - metrics.iconDiameter / 2f).toDp() }
        val labelSize = with(density) { metrics.labelTextSize.toSp() }

        Box(
            modifier =
                Modifier
                    .absoluteOffset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .requiredSize(width = width, height = height)
                    .panelReveal(
                        progress = {
                            if (animationsEnabled) panelProgress.value else 1f
                        },
                        seedSize = metrics.iconDiameter,
                        cornerRadius = metrics.cornerRadius,
                        anchorOnLeft = side == CornerSide.Left,
                    )
                    .squircleSurface(color = panelColor, cornerRadius = cornerRadius)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            resetPanelTimeout()
                            waitForUpOrCancellation()
                        }
                    },
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(metrics.columns),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val progress =
                                if (animationsEnabled) {
                                    contentProgress.value.coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                            alpha = progress
                            translationY =
                                (1f - progress) *
                                    metrics.iconDiameter *
                                    PANEL_CONTENT_TRANSLATION_FRACTION
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                contentPadding =
                    PaddingValues(
                        horizontal = horizontalPadding,
                        vertical = verticalPadding,
                    ),
                verticalArrangement = Arrangement.Top,
                userScrollEnabled = inputEnabled,
            ) {
                items(
                    items = catalog.panelApps,
                    key = { entry -> io.github.hoerire.free.config.PinnedTargetCodec.encode(entry.target) },
                ) { entry ->
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(cellHeight)
                                .clickable(enabled = inputEnabled) {
                                    listener.onEntryCommitted(entry)
                                },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        panelImages[entry.target]?.let { image ->
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize),
                                filterQuality = FilterQuality.High,
                            )
                        }
                        Spacer(modifier = Modifier.height(labelGap))
                        Text(
                            text = entry.label,
                            modifier = Modifier.fillMaxWidth(),
                            color = labelColor,
                            fontSize = labelSize,
                            letterSpacing = 0.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 帧率上限要等视图挂上窗口后才能设（API 35 起 View 自带这个 hint）。
        runCatching { setRequestedFrameRate(OVERLAY_MAX_FRAME_RATE) }
        if (!disposed && !lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        post {
            if (!disposed && isAttachedToWindow) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
            }
        }
    }

    override fun onDetachedFromWindow() {
        val cleanupFailure = disposeOverlay()
        super.onDetachedFromWindow()
        cleanupFailure?.let(listener::onCleanupFailed)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0 && (oldw != w || oldh != h)) {
            requestDismiss()
            return
        }
        updateLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        appliedWindowInsets = insets
        val result = super.onApplyWindowInsets(insets)
        updateLayout()
        return result
    }

    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEventPreIme(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleBack(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack(event: KeyEvent): Boolean {
        if (!panelModeState.value || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP) requestDismiss()
        return true
    }

    private fun updateLayout() {
        if (width <= 0 || height <= 0 || disposed) return
        updateLayout(
            requestedWidth = width,
            requestedHeight = height,
            safeInsets = currentSafeInsets(),
        )
    }

    private fun updateLayout(
        requestedWidth: Int,
        requestedHeight: Int,
        safeInsets: OverlaySafeInsets,
    ) {
        if (requestedWidth <= 0 || requestedHeight <= 0 || disposed) return
        val radialInsets = currentRadialInsets()
        val safeWidth = (requestedWidth - radialInsets.left - radialInsets.right).coerceAtLeast(1f)
        val safeHeight = (requestedHeight - radialInsets.top - radialInsets.bottom).coerceAtLeast(1f)
        val systemIconDiameter = systemIconSize()
        val metrics =
            AdaptiveOverlayGeometry.calculate(
                width = requestedWidth.toFloat(),
                height = requestedHeight.toFloat(),
                safeInsets = safeInsets,
                systemIconSize = systemIconDiameter,
                density = resources.displayMetrics.density,
                radialItemCount = catalog.radialApps.size + 1,
                radialHitRangeDp = radialHitRangeDp.toFloat(),
                radialIconSizeDp = radialIconSizeDp.toFloat(),
                radialRadiusPercent = radialRadiusPercent,
                radialInsets = radialInsets,
                fontScale = resources.configuration.fontScale,
                panelItemCount = catalog.panelApps.size,
                anchorOnLeft = side == CornerSide.Left,
            )
        metricsState.value = metrics
        layoutState.value =
            RadialGeometry.layout(
                side = side,
                width = safeWidth,
                height = safeHeight,
                offsetX = radialInsets.left,
                offsetY = radialInsets.top,
                radius = metrics.radial.radius,
                itemCount = catalog.radialApps.size + 1,
            )
        if (!panelModeState.value && !dismissing) updateGestureFromLatestPoint()
    }

    private fun updateGestureFromLatestPoint() {
        val metrics = metricsState.value ?: return
        val layout = layoutState.value
        if (layout.itemCenters.isEmpty()) return
        val next =
            RadialGeometry.selection(
                layout = layout,
                x = latestX,
                y = latestY,
                previous = selectedIndex,
                enterRadius = metrics.radial.selectionEnterRadius,
                keepRadius = metrics.radial.selectionKeepRadius,
            )
        val inwardDistance = kotlin.math.hypot(latestX - layout.origin.x, latestY - layout.origin.y)
        val inwardLimit = layout.radius * CANCEL_INWARD_RATIO
        if (inwardDistance >= layout.radius * REACHED_OUTWARD_RATIO) reachedOutward = true
        // 往内角回拉取消：手指到过外圈之后又退进内圈，**并且在内圈停留了一小会儿**才收起。
        // 底角是系统手势区，抢流后这条流可能被系统 CANCEL 且不再投递 UP，菜单会定格在屏幕上，
        // 所以需要一条不依赖抬手事件的退出路径；但不能一退进内圈就收——挑图标时手指会
        // 沿圆弧回带、拇指也会下压，瞬时越界很常见，那是「划着划着突然没了」的主要来源。
        if (reachedOutward && inwardDistance < inwardLimit) {
            val now = SystemClock.uptimeMillis()
            if (inwardSince == 0L) inwardSince = now
            if (now - inwardSince >= CANCEL_INWARD_HOLD_MS) {
                debug("SYSTEM_HIT_CANCEL_INWARD")
                selectedIndex = null
                selectedIndexState.intValue = NO_SELECTION
                dismissAnimated()
                return
            }
        } else {
            inwardSince = 0L
        }
        if (next == selectedIndex) return
        // 命中区判定细节：进了哪一格、离格心多远、当前生效的进入半径。
        val center = next?.let { layout.itemCenters.getOrNull(it) }
        debug(
            if (next == null) {
                "SYSTEM_HIT_LEAVE"
            } else {
                "SYSTEM_HIT_ENTER_" + next + "_" + (catalog.radialApps.getOrNull(next)?.label ?: "?") +
                    "_DIST_" + kotlin.math.hypot(latestX - center!!.x, latestY - center.y).toInt() +
                    "_ENTER_PX_" + metrics.radial.selectionEnterRadius.toInt()
            },
        )
        selectedIndex = next
        selectedIndexState.intValue = next ?: NO_SELECTION
        val now = SystemClock.uptimeMillis()
        if (next != null && now - lastTickUptime >= TICK_INTERVAL_MS) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastTickUptime = now
        }
    }

    private fun showMorePanel() {
        if (dismissing || panelModeState.value) return
        selectedIndex = null
        handoffEntryProgress.floatValue = entryProgress.value
        handoffLayoutState.value = layoutState.value
        handoffMetricsState.value = metricsState.value?.radial
        removeCallbacks(timeout)
        listener.onMorePanelRequested()
    }

    /** 当前是否处于内建「更多」面板模式。 */
    fun isPanelMode(): Boolean = panelModeState.value

    fun showBuiltInMorePanel() {
        if (disposed || !isAttachedToWindow) return
        backdropOnlyState.value = false
        panelModeState.value = true
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
        requestFocus()
    }

    fun retainBackdropForPanel() {
        backdropAlpha.floatValue = 1f
        backdropOnlyState.value = true
    }

    fun beginBackdropExit() {
        if (disposed || !backdropOnlyState.value) return
        backdropAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            backdropAlpha.floatValue = 0f
            return
        }
        backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500L
            addUpdateListener {
                val alpha = AllAppsPanelMotion.exit(it.currentPlayTime / 1000f).alpha
                backdropAlpha.floatValue = alpha
                if (alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD) {
                    backdropAlpha.floatValue = 0f
                    cancel()
                }
            }
            start()
        }
    }

    fun hideBackdropAfterFrame(onHidden: () -> Unit) {
        backdropAnimator?.cancel()
        backdropAnimator = null
        if (disposed || !isAttachedToWindow) {
            onHidden()
            return
        }
        // 必须等透明帧提交后才让截图等工具执行，不能只确认状态变量已经更新。
        if (isHardwareAccelerated) {
            viewTreeObserver.registerFrameCommitCallback { post { onHidden() } }
        } else {
            postOnAnimation { postOnAnimation { onHidden() } }
        }
        backdropAlpha.floatValue = 0f
        invalidate()
    }

    private fun dismissAnimated(pendingCommit: RadialEntry?) {
        if (dismissing || disposed) return
        dismissing = true
        removeCallbacks(timeout)
        // 松手当帧就把目标交出去，不等 200ms 退场动画：
        // 应用/快捷方式让浮层自己淡出；取屏类工具由宿主立刻 removeViewImmediate，
        // 再等两帧 → 屏幕已经是干净的，不必再为动画白等 200ms。
        if (pendingCommit != null) {
            commitNotified = true
            // 协调器在这里决定：取屏工具/动画关闭 -> 立即移除；应用/快捷方式 ->
            // 调 retractAnimated() 播完缩回再移除（启动本身已经并行开始）。
            listener.onEntryCommitted(pendingCommit)
            if (disposed) return
            if (!retracting.value) completeRadialExit()
            return
        }
        if (exitAnimationsEnabled) {
            // 取消：播一段"原路缩回"的动画，播完在 LaunchedEffect 里通知宿主移除窗口。
            retractQuick = false
            retracting.value = true
            return
        }
        // 系统关闭动画：直接收场（窗口由宿主在回调里立刻移除）。
        completeRadialExit()
    }

    private fun completeRadialExit() {
        if (dismissNotified || disposed) return
        // 收起只有一个出口：通知宿主移除窗口。
        // 选中目标的启动在 dismissAnimated 里已经交出去了（commitNotified），
        commitNotified = false
        requestDismiss()
    }

    private fun resetPanelTimeout() {
        if (!panelModeState.value || disposed) return
        removeCallbacks(timeout)
        postDelayed(timeout, PANEL_TIMEOUT_MS)
    }

    private fun requestDismiss() {
        if (dismissNotified || disposed) return
        dismissNotified = true
        removeCallbacks(timeout)
        listener.onDismissRequested()
    }

    private fun mergeCleanupFailure(
        current: Throwable?,
        next: Throwable,
    ): Throwable =
        current?.also { previous -> previous.addSuppressed(next) } ?: next

    private fun currentRadialInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        // 扇形属于角落手势本身；系统手势保留区及屏幕圆角不是整页绘制边距。
        // 只避让实际系统栏、缺口和键盘，正常竖屏圆心落在物理侧边与导航栏顶边。
        val insets = windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
        )
        return OverlaySafeInsets(insets.left.toFloat(), insets.top.toFloat(), insets.right.toFloat(), insets.bottom.toFloat())
    }

    private fun currentSafeInsets(): OverlaySafeInsets {
        val windowInsets = appliedWindowInsets ?: rootWindowInsets ?: return OverlaySafeInsets()
        val drawingInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.ime(),
            )
        val gestureInsets =
            windowInsets.getInsets(
                WindowInsets.Type.systemGestures() or
                    WindowInsets.Type.mandatorySystemGestures(),
            )
        val topLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        val topRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
        val bottomLeft = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
        val bottomRight = windowInsets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
        return OverlaySafeInsets(
            left = maxOf(drawingInsets.left, gestureInsets.left).toFloat(),
            top =
                maxOf(
                    drawingInsets.top,
                    gestureInsets.top,
                    topLeft?.radius ?: 0,
                    topRight?.radius ?: 0,
                ).toFloat(),
            right = maxOf(drawingInsets.right, gestureInsets.right).toFloat(),
            bottom =
                maxOf(
                    drawingInsets.bottom,
                    gestureInsets.bottom,
                    bottomLeft?.radius ?: 0,
                    bottomRight?.radius ?: 0,
                ).toFloat(),
        )
    }

    /** 系统大图标尺寸：布局每次都会问一次，取到后缓存，避免反复拿服务。 */
    private val cachedSystemIconSize: Float by lazy {
        try {
            context
                .getSystemService(ActivityManager::class.java)
                ?.launcherLargeIconSize
                ?.toFloat()
                ?: 0f
        } catch (_: RuntimeException) {
            0f
        }
    }

    private fun systemIconSize(): Float = cachedSystemIconSize


    private companion object {
        /**
         * 误触判定的放行点：展开进度到这里就允许提交。
         *
         * 0.6 与原 300ms 入场下的可用时刻大致对齐，避免"曲线变慢 → 轻扫失效"。
         */
        const val SETTLE_FRACTION = 0.6f

        /**
         * Dock 隆起的影响半径（以图标直径为单位）：指针正下方最高，隔一两格基本不再抬。
         */
        const val DOCK_REACH = 2.2f

        /** Dock 隆起的最大外移量（dp）：只抬指针附近，弧的两端保持不动。 */
        const val DOCK_LIFT_DP = 8f

        /** 拉伸：手指贴近弧线时，整圈最多外扩这么多（8%）。 */
        const val STRETCH_GLOBAL = 0.04f

        /** 拉伸：正对手指的那一格再额外外推的比例（10%），形成被"拉起"的斜向拖拽感。 */
        const val STRETCH_DIRECTIONAL = 0.06f

        /** 拉伸的生效窗口：|d/R - 1| 超过这个比例后完全不拉伸，避免手指远处也变形。 */
        const val STRETCH_FALLOFF = 0.75f

        /**
         * 外扩量的硬上限（dp）。
         *
         * 外扩会把图标推离手指，手指就得追着往外伸——幅度大了很难用。
         * 因此无论比例算出多少，单格外扩都不超过这个值：保留"呼吸/被拉起"的观感，
         * 但不改变真正要伸多远才够得着。6dp 约 2.2mm（图标直径的 14%），肉眼看得见、伸手几乎无感。
         */
        const val STRETCH_MAX_DP = 6f


        /** 背景遮罩不透明度（窗口不使用 FLAG_DIM_BEHIND，由绘制层提供）。 */
        /**
         * 遮罩浓度：只留极淡一层。
         *
         * 压暗主要由窗口的背景模糊负责，这里仅用于把扇形和模糊后的背景拉开一点层次。
         */
        const val BACKDROP_ALPHA = 0.06f



        val EMPTY_LAYOUT =
            RadialLayout(
                side = CornerSide.Right,
                origin = io.github.hoerire.free.gesture.GesturePoint(0f, 0f),
                radius = 0f,
                itemCenters = emptyList(),
            )
        const val NO_SELECTION = -1
        /** 选中项放大幅度：1f → 1.2f，与每格 0→1 的进度动画相乘。 */

        const val FULL_TURN_DEGREES = 360f
        const val NANOS_PER_SECOND = 1_000_000_000f
        /** 流星尾迹扫过的角度与分段数：分段换成宽度/亮度渐变的近似，足够平滑。 */
        const val METEOR_SWEEP_DEGREES = 220f
        const val METEOR_SEGMENTS = 20
        const val METEOR_MIN_WIDTH_PX = 1.2f
        const val METEOR_TAIL_MIN_WIDTH = 0.15f
        /** 常亮光圈的亮度与粗细（相对用户设定的光带宽度）。 */
        const val METEOR_RING_ALPHA = 0.32f
        const val METEOR_RING_WIDTH_SCALE = 0.62f
        /** 星头：外接半径相对光带宽度的比例，以及外晕的倍数与亮度。 */
        const val METEOR_STAR_RADIUS_SCALE = 0.40f
        const val METEOR_MIN_STAR_RADIUS_PX = 1.4f
        const val METEOR_STAR_GLOW_SCALE = 1.5f
        const val METEOR_STAR_GLOW_ALPHA = 0.55f
        /**
         * 浮层请求的刷新率上限：流光每帧都在动，交给系统按内容猜会掉到 120 甚至 60；
         * 显式要 165Hz，让 165Hz 屏上的旋转轨迹真正跑满。
         */
        const val OVERLAY_MAX_FRAME_RATE = 165f


        const val PANEL_SURFACE_ALPHA = 253f / 255f
        // 24dp 图标在 40dp 圆盘内：点径 3.5、点距 7.125（图标视口单位）。
        /** 呼出后的静默窗口：屏蔽呼出手势残留的那次抬手。 */
        const val OPEN_GRACE_MS = 180L

        const val MORE_DOT_RADIUS_FRACTION = 1.75f / 24f * 0.6f
        const val MORE_DOT_SPACING_FRACTION = 7.125f / 24f * 0.6f
        const val PANEL_EXPAND_DAMPING = 0.9f
        const val PANEL_EXPAND_RESPONSE_SECONDS = 0.3f

        /**
         * 展开弹簧的刚度：响应时长 response 对应的角频率为 ω = 2π / response，刚度取 ω²。
         * 系统进程内不引入 UI 库，阻尼比与响应时长在此换算为 Compose 原生 spring 的参数。
         */
        val PANEL_EXPAND_STIFFNESS =
            (2.0 * PI / PANEL_EXPAND_RESPONSE_SECONDS).let { omega -> (omega * omega).toFloat() }
        const val PANEL_VISIBILITY_THRESHOLD = 0.0001f
        const val PANEL_CONTENT_DELAY_MILLIS = 45
        const val PANEL_CONTENT_FADE_MILLIS = 180
        const val PANEL_CONTENT_TRANSLATION_FRACTION = 0.12f
        const val TICK_INTERVAL_MS = 80L
        const val GESTURE_TIMEOUT_MS = 5_000L

        /**
         * 回拉取消：手指退回半径的这个比例以内、并持续 [CANCEL_INWARD_HOLD_MS] 才收起。
         *
         * 取 0.45（而不是 0.55）：内圈要退得更深才算「明确回拉」，挑图标时的回带不会触发。
         */
        const val CANCEL_INWARD_RATIO = 0.45f

        /** 判定「到过外圈」的半径比例。 */
        const val REACHED_OUTWARD_RATIO = 0.55f

        /** 手指停在回拉区多久才认定为取消手势。 */
        const val CANCEL_INWARD_HOLD_MS = 180L

        const val PANEL_TIMEOUT_MS = 15_000L
    }
}

private fun Modifier.panelReveal(
    progress: () -> Float,
    seedSize: Float,
    cornerRadius: Float,
    anchorOnLeft: Boolean,
): Modifier =
    drawWithCache {
        val revealPath = Path()
        onDrawWithContent {
            val revealProgress = progress()
            val revealWidth = PanelRevealMotion.extent(size.width, seedSize, revealProgress)
            val revealHeight = PanelRevealMotion.extent(size.height, seedSize, revealProgress)
            val revealLeft =
                PanelRevealMotion.horizontalOffset(size.width, revealWidth, anchorOnLeft)
            val revealTop = PanelRevealMotion.verticalOffset(size.height, revealHeight)
            revealPath.rewind()
            revealPath.addSquircleRect(
                width = revealWidth,
                height = revealHeight,
                cornerRadius = cornerRadius,
            )
            translate(left = revealLeft, top = revealTop) {
                clipPath(revealPath) {
                    translate(left = -revealLeft, top = -revealTop) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }

/**
 * 系统进程内不引入 UI 库的本地实现：把连续圆角矩形写入路径。
 *
 * 半径先按 [SQUIRCLE_RADIUS_EXTENSION] 放大，再用短边一半截断；四角各用一段三次贝塞尔过渡，
 * 控制点沿相邻两边各偏移半径的 [SQUIRCLE_CORNER_CONTROL]，直线段与曲线段在衔接处保持切向连续。
 */
private fun Path.addSquircleRect(
    width: Float,
    height: Float,
    cornerRadius: Float,
) {
    if (width <= 0f || height <= 0f) return
    val radius = (cornerRadius * SQUIRCLE_RADIUS_EXTENSION).coerceIn(0f, minOf(width, height) * 0.5f)
    if (radius <= 0f) {
        addRect(Rect(0f, 0f, width, height))
        return
    }
    val control = radius * SQUIRCLE_CORNER_CONTROL
    moveTo(radius, 0f)
    lineTo(width - radius, 0f)
    cubicTo(width - control, 0f, width, control, width, radius)
    lineTo(width, height - radius)
    cubicTo(width, height - control, width - control, height, width - radius, height)
    lineTo(radius, height)
    cubicTo(control, height, 0f, height - control, 0f, height - radius)
    lineTo(0f, radius)
    cubicTo(0f, control, control, 0f, radius, 0f)
    close()
}

/** 连续圆角半径相对普通圆角的放大比例。 */
private const val SQUIRCLE_RADIUS_EXTENSION = 1.1f

/** 圆角控制点沿相邻两边相对半径的偏移比例。 */
private const val SQUIRCLE_CORNER_CONTROL = 0.357f

/**
 * 「更多」面板底色：在内容背后填充与 [panelReveal] 一致的连续圆角轮廓。
 *
 * 系统进程内不引入 UI 库，轮廓由 [addSquircleRect] 生成，路径按尺寸缓存后直接绘制。
 */
private fun Modifier.squircleSurface(
    color: Color,
    cornerRadius: Dp,
): Modifier =
    drawWithCache {
        val background = Path()
        background.addSquircleRect(size.width, size.height, cornerRadius.toPx())
        onDrawBehind { drawPath(background, color) }
    }

private class RadialImage(entry: RadialEntry) {
    // 绘制侧兜底再裁一次圆：目录那份可能来自缓存或旧的塑形结果，
    // 这里保证真正交给 Canvas 的位图一定是圆的。
    val icon: ImageBitmap = IconContentMetrics.circular(entry.icon).asImageBitmap()
    val badge: ImageBitmap? = entry.badge?.asImageBitmap()
    val badgeLeftOffsetFraction: Float = entry.badgeLeftOffsetFraction
}