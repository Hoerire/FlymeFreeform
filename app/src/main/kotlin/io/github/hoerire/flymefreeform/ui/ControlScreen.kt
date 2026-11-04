package io.github.hoerire.flymefreeform.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.BuildConfig
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionIssue
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionStatus
import io.github.hoerire.flymefreeform.ui.component.TopBarBackdrop
import io.github.hoerire.flymefreeform.ui.component.captureForTopBar
import io.github.hoerire.flymefreeform.ui.component.rememberTopBarBackdrop
import io.github.hoerire.flymefreeform.ui.component.topBarContainerColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ControlScreen(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToZoomOptions: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val featuresEnabled = state.settingsEditable && state.settings.enabled
    var cornerRangePreviewDp by remember { mutableStateOf<Int?>(null) }
    var hitRangePreviewDp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(featuresEnabled) {
        if (!featuresEnabled) {
            cornerRangePreviewDp = null
            hitRangePreviewDp = null
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val isWideScreen = windowWidth >= WideWindowMinWidth
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWideScreen) {
                        SmallTopAppBar(
                            title = stringResource(R.string.app_name),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = stringResource(R.string.app_name),
                            color = topBarColor,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current
            val safeStart = innerPadding.calculateStartPadding(layoutDirection)
            val safeEnd = innerPadding.calculateEndPadding(layoutDirection)
            val safeWidth = (windowWidth - safeStart - safeEnd).coerceAtLeast(0.dp)
            val centeredSide = maxOf(ScreenHorizontalMargin, (safeWidth - ScreenContentMaxWidth) / 2)
            Box(modifier = Modifier.fillMaxSize().captureForTopBar(backdrop)) {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .consumeWindowInsets(innerPadding)
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                    verticalArrangement = Arrangement.spacedBy(CardSpacing),
                    contentPadding =
                        PaddingValues(
                            start = safeStart + centeredSide,
                            top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                            end = safeEnd + centeredSide,
                            bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                        ),
                    overscrollEffect = null,
                ) {
                    item(key = "runtime") {
                        RuntimeCard(
                            state = state,
                            onModuleEnabledChange = onModuleEnabledChange,
                            onRequestScopes = onRequestScopes,
                        )
                    }
                    item(key = "settings") {
                        SettingsCard(
                            state = state,
                            featuresEnabled = featuresEnabled,
                            onCornerSidesChange = onCornerSidesChange,
                            onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                            onRadialHitRangeChange = onRadialHitRangeChange,
                            onCornerRangePreviewChange = { cornerRangePreviewDp = it },
                            onHitRangePreviewChange = { hitRangePreviewDp = it },
                            onNavigateToPinnedApps = onNavigateToPinnedApps,
                        )
                    }
                    item(key = "window_interaction") {
                        WindowInteractionCard(
                            state = state,
                            featuresEnabled = featuresEnabled,
                            onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                            onHandleSwipeUpToMiniEnabledChange =
                                onHandleSwipeUpToMiniEnabledChange,
                        )
                    }
                    item(key = "zoom_launch") {
                        ZoomLaunchCard(
                            state = state,
                            featuresEnabled = featuresEnabled,
                            onNavigateToZoomOptions = onNavigateToZoomOptions,
                        )
                    }
                    item(key = "environment") {
                        EnvironmentCard(
                            state = state,
                            featuresEnabled = featuresEnabled,
                            onPauseInLandscapeChange = onPauseInLandscapeChange,
                            onPauseInGameModeChange = onPauseInGameModeChange,
                            onNavigateToBlacklist = onNavigateToBlacklist,
                        )
                    }
                    item(key = "about") {
                        AboutCard(
                            state = state,
                            featuresEnabled = featuresEnabled,
                            onVerboseLogChange = onVerboseLogChange,
                        )
                    }
                }
            }
        }
        cornerRangePreviewDp?.let { rangeDp ->
            CornerRangePreview(
                rangeDp = rangeDp,
                leftEnabled = state.settings.leftCornerEnabled,
                rightEnabled = state.settings.rightCornerEnabled,
            )
        }
        hitRangePreviewDp?.let { rangeDp ->
            HitRangePreview(
                hitRangeDp = rangeDp,
                itemCount =
                    if (state.settings.pinsSaved) {
                        state.settings.pinnedTargets.size + 1
                    } else {
                        ModulePreferences.MAX_PINNED_APPS + 1
                    },
                leftEnabled = state.settings.leftCornerEnabled,
                rightEnabled = state.settings.rightCornerEnabled,
            )
        }
    }
}

@Composable
private fun ZoomLaunchCard(
    state: FrameworkConnectionState,
    featuresEnabled: Boolean,
    onNavigateToZoomOptions: () -> Unit,
) {
    // 三个子功能各自独立，这里只显示已开启的数量，具体开关在二级页面里。
    val enabledCount =
        listOf(
            state.settings.zoomAllowAnyApp,
            state.settings.zoomLaunchNotification,
            state.settings.zoomLaunchControlCenter,
        ).count { it }
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.zoom_limit_removed_title),
            summary =
                if (enabledCount == 0) {
                    stringResource(R.string.zoom_options_summary_none)
                } else {
                    stringResource(R.string.zoom_options_summary_count, enabledCount)
                },
            onClick = onNavigateToZoomOptions,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.LockOpen, featuresEnabled) },
        )
    }
}

@Composable
private fun EnvironmentCard(
    state: FrameworkConnectionState,
    featuresEnabled: Boolean,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onNavigateToBlacklist: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.blacklist_title),
            summary = stringResource(R.string.blacklist_summary),
            onClick = onNavigateToBlacklist,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Block, featuresEnabled) },
        )
        SwitchPreference(
            checked = state.settings.pauseInLandscape,
            onCheckedChange = onPauseInLandscapeChange,
            title = stringResource(R.string.pause_in_landscape_title),
            summary = stringResource(R.string.pause_in_landscape_summary),
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, featuresEnabled) },
        )
        SwitchPreference(
            checked = state.settings.pauseInGameMode,
            onCheckedChange = onPauseInGameModeChange,
            title = stringResource(R.string.pause_in_game_mode_title),
            summary = stringResource(R.string.pause_in_game_mode_summary),
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, featuresEnabled) },
        )
    }
}

@Composable
private fun WindowInteractionCard(
    state: FrameworkConnectionState,
    featuresEnabled: Boolean,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
) {
    val modes = OutsideTapCloseMode.entries
    val items =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
        )
    Card(modifier = Modifier.fillMaxWidth()) {
        OverlaySpinnerPreference(
            items = items,
            selectedIndex = modes.indexOf(state.settings.outsideTapCloseMode),
            title = stringResource(R.string.outside_tap_close_title),
            summary = stringResource(R.string.outside_tap_close_summary),
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.TouchApp, featuresEnabled) },
            onSelectedIndexChange = { index ->
                modes.getOrNull(index)?.let(onOutsideTapCloseModeChange)
            },
        )
        SwitchPreference(
            checked = state.settings.handleSwipeUpToMiniEnabled,
            onCheckedChange = onHandleSwipeUpToMiniEnabledChange,
            title = stringResource(R.string.handle_swipe_up_to_mini_title),
            summary = stringResource(R.string.handle_swipe_up_to_mini_summary),
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, featuresEnabled) },
        )
    }
}

@Composable
private fun RuntimeCard(
    state: FrameworkConnectionState,
    onModuleEnabledChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
) {
    val presentation = frameworkPresentation(state)
    // 写入配置期间不切换文案：isUpdating 只用于禁用交互。
    // 换个更短的文案会让该行行数变化，整页高度随之跳动。
    val moduleSummary =
        when {
            !state.canChangeSettings && state.status != FrameworkConnectionStatus.Connected ->
                stringResource(R.string.module_enabled_summary_waiting)
            state.missingScopes.isNotEmpty() -> stringResource(R.string.module_enabled_summary_scope)
            state.settings.enabled -> stringResource(R.string.module_enabled_summary_on)
            else -> stringResource(R.string.module_enabled_summary_off)
        }
    val showScopeAction =
        state.status == FrameworkConnectionStatus.Connected && state.missingScopes.isNotEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.settings.enabled,
            onCheckedChange = onModuleEnabledChange,
            title = stringResource(R.string.module_enabled_title),
            summary = moduleSummary,
            enabled = state.settingsEditable,
            startAction = {
                PreferenceIcon(Icons.Rounded.PowerSettingsNew, state.settingsEditable)
            },
        )
        BasicComponent(
            title = stringResource(R.string.framework_service_title),
            summary = presentation.summary,
            endActions = {
                Text(
                    text = presentation.label,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            },
            startAction = { PreferenceIcon(Icons.Rounded.ElectricalServices) },
        )
        AnimatedVisibility(visible = showScopeAction) {
            ArrowPreference(
                title = stringResource(R.string.scope_title),
                summary =
                    if (state.isRequestingScope) stringResource(R.string.scope_requesting)
                    else if (state.issue == FrameworkConnectionIssue.ScopeRequestFailed) {
                        stringResource(R.string.scope_request_failed)
                    } else {
                        stringResource(
                            R.string.scope_missing,
                            state.missingScopes.joinToString(" · "),
                        )
                    },
                onClick = onRequestScopes,
                enabled = state.canRequestScope,
                startAction = { PreferenceIcon(Icons.Rounded.AccountTree, state.canRequestScope) },
            )
        }
    }
}

@Composable
private fun SettingsCard(
    state: FrameworkConnectionState,
    featuresEnabled: Boolean,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onCornerRangePreviewChange: (Int?) -> Unit,
    onHitRangePreviewChange: (Int?) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
) {
    val selectedSideIndex =
        when {
            state.settings.leftCornerEnabled && !state.settings.rightCornerEnabled -> 1
            !state.settings.leftCornerEnabled && state.settings.rightCornerEnabled -> 2
            else -> 0
        }
    val sideItems =
        listOf(
            DropdownItem(text = stringResource(R.string.corner_trigger_side_both)),
            DropdownItem(text = stringResource(R.string.corner_trigger_side_left)),
            DropdownItem(text = stringResource(R.string.corner_trigger_side_right)),
        )
    val appsSummary =
        when {
            !state.settings.pinsSaved -> stringResource(R.string.radial_apps_recent_summary)
            state.settings.pinnedTargets.isEmpty() -> stringResource(R.string.radial_apps_empty_summary)
            else -> stringResource(R.string.radial_apps_count_summary, state.settings.pinnedTargets.size)
        }
    Card(modifier = Modifier.fillMaxWidth()) {
        OverlaySpinnerPreference(
            items = sideItems,
            selectedIndex = selectedSideIndex,
            title = stringResource(R.string.corner_trigger_side_title),
            summary = stringResource(R.string.corner_trigger_side_summary),
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, featuresEnabled) },
            onSelectedIndexChange = { index ->
                when (index) {
                    0 -> onCornerSidesChange(true, true)
                    1 -> onCornerSidesChange(true, false)
                    2 -> onCornerSidesChange(false, true)
                }
            },
        )
        RemoteDpSliderPreference(
            icon = Icons.Rounded.Straighten,
            confirmedValue = state.settings.cornerTriggerRangeDp,
            isUpdating = state.isUpdating,
            enabled = featuresEnabled,
            title = stringResource(R.string.corner_trigger_range_title),
            summary = stringResource(R.string.corner_trigger_range_summary),
            minDp = ModulePreferences.MIN_CORNER_TRIGGER_RANGE_DP,
            maxDp = ModulePreferences.MAX_CORNER_TRIGGER_RANGE_DP,
            coerceDp = ModulePreferences::coerceCornerTriggerRangeDp,
            onPreviewChange = onCornerRangePreviewChange,
            onCommit = onCornerTriggerRangeChange,
        )
        RemoteDpSliderPreference(
            icon = Icons.Rounded.CenterFocusStrong,
            confirmedValue = state.settings.radialHitRangeDp,
            isUpdating = state.isUpdating,
            enabled = featuresEnabled,
            title = stringResource(R.string.radial_hit_range_title),
            summary = stringResource(R.string.radial_hit_range_summary),
            minDp = ModulePreferences.MIN_RADIAL_HIT_RANGE_DP,
            maxDp = ModulePreferences.MAX_RADIAL_HIT_RANGE_DP,
            coerceDp = ModulePreferences::coerceRadialHitRangeDp,
            onPreviewChange = onHitRangePreviewChange,
            onCommit = onRadialHitRangeChange,
        )
        ArrowPreference(
            title = stringResource(R.string.radial_apps_title),
            summary = appsSummary,
            onClick = onNavigateToPinnedApps,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Apps, featuresEnabled) },
        )
    }
}

/** 滑条行图标相对整行垂直中心的补偿量：整行比单纯标题行高，居中会显得偏下。 */
private val SliderIconOffsetY = (-6).dp

@Composable
internal fun RemoteDpSliderPreference(
    icon: ImageVector,
    confirmedValue: Int,
    isUpdating: Boolean,
    enabled: Boolean,
    title: String,
    summary: String,
    minDp: Int,
    maxDp: Int,
    coerceDp: (Int) -> Int,
    onPreviewChange: (Int?) -> Unit,
    onCommit: (Int) -> Unit,
    /** false 时只显示数值，不带 dp 单位（用于「个数」这类非尺寸项）。 */
    showDpUnit: Boolean = true,
) {
    var draftValue by remember { mutableFloatStateOf(coerceDp(confirmedValue).toFloat()) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(confirmedValue, isUpdating, enabled) {
        if (!enabled && pressed) {
            pressed = false
            onPreviewChange(null)
        }
        if (!pressed && !isUpdating) draftValue = coerceDp(confirmedValue).toFloat()
    }
    val commit =
        {
            onPreviewChange(null)
            val committed = coerceDp(draftValue.roundToInt())
            draftValue = committed.toFloat()
            if (committed != confirmedValue) onCommit(committed)
        }
    val latestCommit by rememberUpdatedState(commit)
    SliderPreference(
        value = draftValue,
        onValueChange = { value ->
            // 只认真实按下时的回调。SliderPreference 会在布局与跟随动画里自己回放数值，
            // 将上述帧误判为拖动会写入配置，导致数值持续漂移。
            if (pressed) {
                draftValue = value.coerceIn(minDp.toFloat(), maxDp.toFloat())
                onPreviewChange(coerceDp(draftValue.roundToInt()))
            }
        },
        // Initial 阶段只观察不消费；按下才允许改值，抬手才提交。
        modifier =
            Modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            pressed = true
                        } else if (pressed && event.changes.none { it.pressed }) {
                            // 抬手或被系统手势打断都算结束，避免按下状态卡住。
                            pressed = false
                            latestCommit()
                        }
                    }
                }
            },
        title = title,
        summary = summary,
        valueText =
            if (showDpUnit) {
                stringResource(R.string.dp_value, coerceDp(draftValue.roundToInt()))
            } else {
                coerceDp(draftValue.roundToInt()).toString()
            },
        enabled = enabled,
        // SliderPreference 把 start 区域垂直居中于「标题 + 滑条」整行，
        // 图标因此落在标题下方；上移抵消，让它与标题同一水平线。
        startAction = {
            Box(modifier = Modifier.offset(y = SliderIconOffsetY)) {
                PreferenceIcon(icon, enabled)
            }
        },
        valueRange = minDp.toFloat()..maxDp.toFloat(),
        steps = maxDp - minDp - 1,
        onValueChangeFinished = null,
    )
}

@Composable
private fun CornerRangePreview(
    rangeDp: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = MiuixTheme.colorScheme.primary
    val inactiveColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = rangeDp.dp.toPx()
        val arcSize = Size(radius * 2f, radius * 2f)
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))

        fun drawCorner(startAngle: Float, topLeft: Offset, enabled: Boolean) {
            val color = if (enabled) activeColor else inactiveColor
            drawArc(
                color = color.copy(alpha = if (enabled) 0.22f else 0.08f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
            )
            drawArc(
                color = color.copy(alpha = if (enabled) 0.82f else 0.30f),
                startAngle = startAngle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, pathEffect = dash),
            )
        }

        drawCorner(
            startAngle = 270f,
            topLeft = Offset(-radius, size.height - radius),
            enabled = leftEnabled,
        )
        drawCorner(
            startAngle = 180f,
            topLeft = Offset(size.width - radius, size.height - radius),
            enabled = rightEnabled,
        )
    }
}

/**
 * 拖动「命中范围」时的实时预览：按扇形真实几何画出每个槽位的命中圆与图标圆点，
 * 让用户直接看到命中圈有多大、相邻圈是否已经连成一片。
 */
@Composable
private fun HitRangePreview(
    hitRangeDp: Int,
    itemCount: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = MiuixTheme.colorScheme.primary
    val inactiveColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val basePixel = minOf(size.width, size.height) / RadialBaseShortEdgeDp
        val arcRadius = RadialBaseArcRadiusDp * basePixel
        val iconRadius = RadialBaseIconDiameterDp * basePixel / 2f
        val hitRadius = hitRangeDp.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
        val slots = itemCount.coerceIn(1, 7)
        val step = RadialSpanDegrees / slots

        fun drawSide(originX: Float, originY: Float, mirrored: Boolean, enabled: Boolean) {
            val color = if (enabled) activeColor else inactiveColor
            for (index in 0 until slots) {
                val slot = slots - index - 1
                val leftDegrees = -RadialSafeDegrees - (slot + 0.5f) * step
                val degrees = if (mirrored) -180f - leftDegrees else leftDegrees
                val radians = degrees * PI.toFloat() / 180f
                val center =
                    Offset(
                        x = originX + cos(radians) * arcRadius,
                        y = originY + sin(radians) * arcRadius,
                    )
                drawCircle(
                    color = color.copy(alpha = if (enabled) 0.10f else 0.04f),
                    radius = hitRadius,
                    center = center,
                )
                drawCircle(
                    color = color.copy(alpha = if (enabled) 0.55f else 0.22f),
                    radius = hitRadius,
                    center = center,
                    style = Stroke(width = strokeWidth, pathEffect = dash),
                )
                drawCircle(
                    color = color.copy(alpha = if (enabled) 0.85f else 0.30f),
                    radius = iconRadius,
                    center = center,
                )
            }
        }

        drawSide(originX = 0f, originY = size.height, mirrored = false, enabled = leftEnabled)
        drawSide(originX = size.width, originY = size.height, mirrored = true, enabled = rightEnabled)
    }
}

@Composable
private fun AboutCard(
    state: FrameworkConnectionState,
    featuresEnabled: Boolean,
    onVerboseLogChange: (Boolean) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.about_version_title),
            endActions = {
                Text(
                    text = stringResource(
                        R.string.about_version_format,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE.toString(),
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            },
            startAction = { PreferenceIcon(Icons.Rounded.Info) },
        )
        SwitchPreference(
            checked = state.settings.verboseLogEnabled,
            onCheckedChange = onVerboseLogChange,
            title = stringResource(R.string.verbose_log_title),
            summary = stringResource(R.string.verbose_log_summary),
            enabled = featuresEnabled,
            startAction = {
                PreferenceIcon(
                    ImageVector.vectorResource(R.drawable.ic_log),
                    featuresEnabled,
                )
            },
        )
        ArrowPreference(
            title = stringResource(R.string.about_own_repo_title),
            summary = stringResource(R.string.about_own_repo_summary),
            onClick = {
                try {
                    uriHandler.openUri(OWN_REPO_URL)
                } catch (_: IllegalArgumentException) {
                } catch (_: ActivityNotFoundException) {
                }
            },
            startAction = {
                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_github))
            },
        )
        ArrowPreference(
            title = stringResource(R.string.about_upstream_title),
            summary = stringResource(R.string.about_upstream_summary),
            onClick = {
                try {
                    uriHandler.openUri(UPSTREAM_REPO_URL)
                } catch (_: IllegalArgumentException) {
                } catch (_: ActivityNotFoundException) {
                }
            },
            startAction = {
                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_github))
            },
        )
    }
}

@Composable
internal fun PreferenceIcon(imageVector: ImageVector, enabled: Boolean = true) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp).size(24.dp),
        tint =
            if (enabled) MiuixTheme.colorScheme.onBackground
            else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
    )
}

@Composable
private fun frameworkPresentation(state: FrameworkConnectionState): FrameworkPresentation =
    when (state.status) {
        FrameworkConnectionStatus.Waiting -> FrameworkPresentation(stringResource(R.string.framework_status_waiting), stringResource(R.string.framework_waiting_summary))
        FrameworkConnectionStatus.Connected -> {
            val unknown = stringResource(R.string.framework_unknown_value)
            FrameworkPresentation(
                stringResource(R.string.framework_status_connected),
                stringResource(
                    R.string.framework_connected_summary,
                    state.frameworkName ?: unknown,
                    state.frameworkVersion ?: unknown,
                    state.apiVersion?.toString() ?: unknown,
                ),
            )
        }
        FrameworkConnectionStatus.Incompatible -> FrameworkPresentation(stringResource(R.string.framework_status_incompatible), stringResource(state.issue.incompatibleSummary()))
        FrameworkConnectionStatus.Error -> FrameworkPresentation(stringResource(R.string.framework_status_error), stringResource(state.issue.errorSummary()))
    }

@StringRes
private fun FrameworkConnectionIssue?.incompatibleSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.ServiceApiTooOld -> R.string.framework_api_too_old_summary
        FrameworkConnectionIssue.RemoteCapabilityMissing -> R.string.framework_remote_missing_summary
        FrameworkConnectionIssue.SystemCapabilityMissing -> R.string.framework_system_missing_summary
        FrameworkConnectionIssue.MultipleServices -> R.string.framework_multiple_services_summary
        else -> R.string.framework_connection_failed_summary
    }

@StringRes
private fun FrameworkConnectionIssue?.errorSummary(): Int =
    when (this) {
        FrameworkConnectionIssue.WriteFailed -> R.string.framework_write_failed_summary
        else -> R.string.framework_connection_failed_summary
    }

private data class FrameworkPresentation(val label: String, val summary: String)

private const val OWN_REPO_URL = "https://github.com/Hoerire/FlymeFreeform"
private const val UPSTREAM_REPO_URL = "https://github.com/Mangi-11/FlymeFreeform"

private const val RadialBaseShortEdgeDp = 400f
private const val RadialBaseArcRadiusDp = 242f
private const val RadialBaseIconDiameterDp = 44f
private const val RadialSpanDegrees = 84f
private const val RadialSafeDegrees = 3f

internal val ScreenHorizontalMargin = 12.dp
internal val ScreenContentMaxWidth = 600.dp
internal val WideWindowMinWidth = 600.dp
internal val ScreenTopSpacing = 12.dp
internal val ScreenBottomSpacing = 12.dp
private val CardSpacing = 12.dp
