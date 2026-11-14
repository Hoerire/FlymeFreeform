package io.github.hoerire.flymefreeform.window

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.gesture.CornerSide
import io.github.hoerire.flymefreeform.gesture.RadialGeometry
import io.github.hoerire.flymefreeform.gesture.GesturePoint
import io.github.hoerire.flymefreeform.gesture.RadialLayout
import io.github.hoerire.flymefreeform.platform.coloros.AppCatalogSnapshot
import io.github.hoerire.flymefreeform.platform.coloros.RadialEntry
import io.github.hoerire.flymefreeform.ui.theme.CornerOverlayTheme
import io.github.hoerire.flymefreeform.ui.theme.LocalRadialItemColors
import io.github.hoerire.flymefreeform.ui.theme.RadialItemColors
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("ViewConstructor")
/**
 * 扇形浮层的 Compose 容器（运行在 system_server）。
 *
 * 职责：绘制扇形/更多面板、把触摸坐标换算成选中格（[RadialGeometry]）、
 * 管理入场/退场动画与提交时机。
 *
 * 提交时机（[dismissAnimated]）：松手当帧即回调 [Listener.onEntryCommitted]，
 * 不等 200ms 退场动画；浮层随后自行淡出。取屏类工具由宿主 [removeOverlay][io.github.hoerire.flymefreeform.platform.coloros.ColorOsFreeformCoordinator]
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
    private var panelImages: Map<io.github.hoerire.flymefreeform.config.PinnedTarget, ImageBitmap> = emptyMap()
    private var side = CornerSide.Right
    private var radialHitRangeDp = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP
    private var radialIconSizeDp = ModulePreferences.DEFAULT_RADIAL_ICON_SIZE_DP
    /** 浮层显示时刻：呼出手势的抬手会作为 tap 落到这里，必须忽略。 */
    private var shownAtUptime = 0L
    /** 「永久保持」：松手未选中时保留浮层，并允许直接点选条目。 */
    private var keepOpen = false
    private var latestX = 0f
    private var latestY = 0f
    private var selectedIndex: Int? = null
    private var dismissing = false
    private var dismissNotified = false
    /** 松手当帧已把目标交给宿主（应用/快捷方式），退场动画结束后只做收尾。 */
    private var commitNotified = false
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
        keepOpen: Boolean = false,
    ) {
        check(!isAttachedToWindow) { "Overlay must be initialized before it is attached" }
        this.keepOpen = keepOpen
        this.side = side
        // 保持挂起不改变命中判定：滑动选格与点选统一使用「命中范围」的设定值。
        this.radialHitRangeDp = ModulePreferences.coerceRadialHitRangeDp(hitRangeDp)
        this.radialIconSizeDp = ModulePreferences.coerceRadialIconSizeDp(iconSizeDp)
        shownAtUptime = android.os.SystemClock.uptimeMillis()
        entrySettled = false
        this.catalog = catalog
        latestX = x
        latestY = y
        radialImages.value = catalog.radialApps.map(::RadialImage)
        panelImages = catalog.panelApps.associate { entry -> entry.target to entry.icon.asImageBitmap() }
        selectedIndex = null
        selectedIndexState.intValue = NO_SELECTION
        handoffEntryProgress.floatValue = 0f
        handoffLayoutState.value = EMPTY_LAYOUT
        handoffMetricsState.value = null
        panelModeState.value = false
        dismissing = false
        dismissNotified = false
        commitNotified = false
        val displayMetrics = resources.displayMetrics
        updateLayout(
            requestedWidth = displayMetrics.widthPixels,
            requestedHeight = displayMetrics.heightPixels,
            safeInsets = OverlaySafeInsets(),
        )
        removeCallbacks(timeout)
        if (!keepOpen) postDelayed(timeout, GESTURE_TIMEOUT_MS)
    }

    fun updateGesture(x: Float, y: Float): Int? {
        latestX = x
        latestY = y
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
        // 诊断：进场动画是否真的在跑（仅详细日志时输出）。
        remember(animationsEnabled, motionScale) {
            io.github.hoerire.flymefreeform.log.ModuleLog.debug(
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
                io.github.hoerire.flymefreeform.log.ModuleLog.debug("ANIM") {
                    "SYSTEM_ANIM_ENTRY_compensated_elapsed_" + elapsed + "_remaining_" + remaining +
                        "_total_" + total
                }
                if (start >= 1f || remaining <= 0) {
                    entryProgress.snapTo(1f)
                } else {
                    entryProgress.snapTo(start)
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
            // 入场动画结束事件：抬手判定只读这个标志，不做任何时间计算。
            entrySettled = true
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
                                    folmeSpring(
                                        damping = PANEL_EXPAND_DAMPING,
                                        response = PANEL_EXPAND_RESPONSE_SECONDS,
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
                                    val hitRadius =
                                        radialHitRangeDp * resources.displayMetrics.density
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
        animationsEnabled: Boolean,
    ) {
        val itemColors = LocalRadialItemColors.current
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
            // 遮罩由这里画（窗口不再用 FLAG_DIM_BEHIND）：随内容透明度一起淡入淡出，
            // 比系统 dim 更贴合入场/收回的节奏。
            //
            // 磨砂质感：底色之上叠一层极细的随机颗粒。真背景模糊在本模块的窗口类型上
            // 不可用（ColorOS 只对特定窗口类型做 cross-window blur），颗粒层用几乎
            // 不可见的明暗噪点制造"毛玻璃"观感，代价只是一次 drawPoints。
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
                colors = itemColors,
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
        colors: RadialItemColors,
    ) {
        val direction = if (layout.side == CornerSide.Left) 1f else -1f
        val overshoot = direction * RadialEntryMotion.HORIZONTAL_OVERSHOOT_DP * metrics.pixelsPerBaseDp * motion.horizontalOvershoot
        val itemCount = layout.itemCenters.size.coerceAtLeast(1)
        layout.itemCenters.forEachIndexed { index, destination ->
            // 逐个图标交错飞出：以前所有格共用 motion.radialProgress，
            // 整个扇形像一块铁板一样整体平移，看起来只有"展开 / 回弹"两段。
            // 这里给每一格分配一个起始偏移，靠后的格子晚一点出发，
            // 扇形就有了"依次铺开"的层次；收回时顺序自然反过来。
            val startAt =
                if (itemCount > 1) ITEM_STAGGER_RATIO * index / (itemCount - 1) else 0f
            val localProgress =
                ((motion.radialProgress - startAt) / (1f - ITEM_STAGGER_RATIO)).coerceIn(0f, 1f)
            val centerX = layout.origin.x + (destination.x - layout.origin.x) * localProgress + overshoot * localProgress
            val centerY = layout.origin.y + (destination.y - layout.origin.y) * localProgress
            val ringProgress = itemRings.getOrNull(index)?.value ?: 0f
            // 选中项放大：复用每格原有 0→1 的进度动画，1f→1.2f 平滑放大；
            // 不再绘制白色描边外圈（原实现用同一进度画 SELECTION_RING_ALPHA 的圆环）。
            val selectionScale = 1f + SELECTED_ICON_SCALE_GAIN * ringProgress
            val scale = motion.iconScale * contentScale * selectionScale
            val diameter = metrics.iconDiameter * scale
            rotate(motion.rotationDegrees, pivot = Offset(centerX, centerY)) {
                if (index < catalog.radialApps.size) {
                    radialImages.value.getOrNull(index)?.let { image ->
                        drawSystemImage(image.icon, centerX, centerY, diameter, contentAlpha)
                    }
                } else {
                    drawMoreItem(centerX, centerY, diameter, contentAlpha, colors)
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
        val squircleEnabled = isSquircleEnabled()
        val panelColor = MiuixTheme.colorScheme.surface.copy(alpha = PANEL_SURFACE_ALPHA)
        val labelColor = MiuixTheme.colorScheme.onSurface
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
                        squircleEnabled = squircleEnabled,
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
                    key = { entry -> io.github.hoerire.flymefreeform.config.PinnedTargetCodec.encode(entry.target) },
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
            listener.onEntryCommitted(pendingCommit)
            if (disposed) return
        }
        // 收起没有退场动画：直接收场（窗口由宿主在回调里立刻移除）。
        completeRadialExit()
    }

    private fun completeRadialExit() {
        if (dismissNotified || disposed) return
        // 收起只有一个出口：通知宿主移除窗口。
        // 选中目标的启动在 dismissAnimated 里已经交出去了（commitNotified），
        // 原先这里还留了一条"未提交则补发 onEntryCommitted"的分支，
        // 那是退场动画时代为了在动画结束后才提交而设的，现在用不到了。
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
         * 交错比例：整段入场里最多有这么长的时间用于"逐格错开"。
         *
         * 0.35 意味着第 0 格立即出发、最后一格晚 35% 出发，但所有格子都在动画结束时归位。
         * 再大就会让最后一格显得"迟到"，再小又回到整体平移的观感。
         */
        /**
         * 每格错峰比例。
         *
         * 设成 0：所有格子用同一段时长飞完同样距离，速度一致。
         * 之前是 0.35，靠后的格子被压到 65% 的时间里跑完，速度更快，
         * 叠起来看就是一片残影。
         */
        const val ITEM_STAGGER_RATIO = 0f


        /** 背景遮罩不透明度（窗口不再使用 FLAG_DIM_BEHIND，由绘制层提供）。 */
        /**
         * 遮罩浓度：只留极淡一层。
         *
         * 压暗主要由窗口的背景模糊负责，这里仅用于把扇形和模糊后的背景拉开一点层次。
         */
        const val BACKDROP_ALPHA = 0.06f



        val EMPTY_LAYOUT =
            RadialLayout(
                side = CornerSide.Right,
                origin = io.github.hoerire.flymefreeform.gesture.GesturePoint(0f, 0f),
                radius = 0f,
                itemCenters = emptyList(),
            )
        const val NO_SELECTION = -1
        /** 选中项放大幅度：1f → 1.2f，与每格 0→1 的进度动画相乘。 */
        const val SELECTED_ICON_SCALE_GAIN = 0.2f
        const val PANEL_SURFACE_ALPHA = 253f / 255f
        // 24dp 图标在 40dp 圆盘内：点径 3.5、点距 7.125（图标视口单位）。
        /** 呼出后的静默窗口：屏蔽呼出手势残留的那次抬手。 */
        const val OPEN_GRACE_MS = 180L

        const val MORE_DOT_RADIUS_FRACTION = 1.75f / 24f * 0.6f
        const val MORE_DOT_SPACING_FRACTION = 7.125f / 24f * 0.6f
        const val PANEL_EXPAND_DAMPING = 0.9f
        const val PANEL_EXPAND_RESPONSE_SECONDS = 0.3f
        const val PANEL_VISIBILITY_THRESHOLD = 0.0001f
        const val PANEL_CONTENT_DELAY_MILLIS = 45
        const val PANEL_CONTENT_FADE_MILLIS = 180
        const val PANEL_CONTENT_TRANSLATION_FRACTION = 0.12f
        const val TICK_INTERVAL_MS = 80L
        const val GESTURE_TIMEOUT_MS = 5_000L

        const val PANEL_TIMEOUT_MS = 15_000L
        const val DISMISS_FALLBACK_GRACE_MS = 260L
    }
}

private fun Modifier.panelReveal(
    progress: () -> Float,
    seedSize: Float,
    cornerRadius: Float,
    anchorOnLeft: Boolean,
    squircleEnabled: Boolean,
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
                squircleEnabled = squircleEnabled,
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

private class RadialImage(entry: RadialEntry) {
    // 绘制侧兜底再裁一次圆：目录那份可能来自缓存或旧的塑形结果，
    // 这里保证真正交给 Canvas 的位图一定是圆的。
    val icon: ImageBitmap = IconContentMetrics.circular(entry.icon).asImageBitmap()
    val badge: ImageBitmap? = entry.badge?.asImageBitmap()
    val badgeLeftOffsetFraction: Float = entry.badgeLeftOffsetFraction
}