package io.github.hoerire.flymefreeform.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.BuildConfig
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.update.UpdateState
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ControlScreen(
    state: FrameworkConnectionState,
    update: UpdateState,
    onDismissUpdate: (String) -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onFreeDragChange: (Boolean) -> Unit,
    onDragLongPressChange: (Int) -> Unit,
    onRequestScopes: () -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    onNavigateToRadialSettings: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToZoomOptions: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    val featuresEnabled = state.settingsEditable && state.settings.enabled

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
                        EntryCard(
                            featuresEnabled = featuresEnabled,
                            onNavigateToRadialSettings = onNavigateToRadialSettings,
                            onNavigateToZoomOptions = onNavigateToZoomOptions,
                            onNavigateToBlacklist = onNavigateToBlacklist,
                        )
                    }
                    item(key = "freeDrag") {
                        FreeDragCard(
                            state = state,
                            featuresEnabled = featuresEnabled,
                            onFreeDragChange = onFreeDragChange,
                            onDragLongPressChange = onDragLongPressChange,
                        )
                    }
                    item(key = "about") {
                        AboutCard(onNavigateToAbout = onNavigateToAbout)
                    }
                }
            }

            UpdateOverlay(
                update = update,
                backdrop = backdrop,
                onDismissUpdate = onDismissUpdate,
            )
        }
    }
}

/** 自由拖拽：只有一个开关，独立成卡片放在配置入口下面。 */
@Composable
private fun FreeDragCard(
    state: FrameworkConnectionState,
    featuresEnabled: Boolean,
    onFreeDragChange: (Boolean) -> Unit,
    onDragLongPressChange: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            checked = state.settings.freeDragEnabled,
            onCheckedChange = onFreeDragChange,
            title = stringResource(R.string.free_drag_title),
            summary = stringResource(R.string.free_drag_summary),
            enabled = featuresEnabled,
            startAction = {
                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_free_share), featuresEnabled)
            },
        )
        RemoteDpSliderPreference(
            icon = ImageVector.vectorResource(R.drawable.ic_trigger_delay),
            confirmedValue = state.settings.dragLongPressMs,
            isUpdating = state.isUpdating,
            enabled = featuresEnabled && state.settings.freeDragEnabled,
            title = stringResource(R.string.drag_long_press_title),
            summary = stringResource(R.string.drag_long_press_summary),
            minDp = ModulePreferences.MIN_DRAG_LONG_PRESS_MS,
            maxDp = ModulePreferences.MAX_DRAG_LONG_PRESS_MS,
            coerceDp = ModulePreferences::coerceDragLongPressMs,
            onPreviewChange = {},
            onCommit = onDragLongPressChange,
            showDpUnit = false,
        )
    }
}

/** 三个配置分组的入口；具体开关在各自的二级页面内。 */
@Composable
private fun EntryCard(
    featuresEnabled: Boolean,
    onNavigateToRadialSettings: () -> Unit,
    onNavigateToZoomOptions: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.radial_settings_title),
            summary = stringResource(R.string.radial_settings_summary),
            onClick = onNavigateToRadialSettings,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, featuresEnabled) },
        )
        ArrowPreference(
            title = stringResource(R.string.zoom_limit_removed_title),
            summary = stringResource(R.string.zoom_options_summary),
            onClick = onNavigateToZoomOptions,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.LockOpen, featuresEnabled) },
        )
        ArrowPreference(
            title = stringResource(R.string.blacklist_title),
            summary = stringResource(R.string.blacklist_summary),
            onClick = onNavigateToBlacklist,
            enabled = featuresEnabled,
            startAction = { PreferenceIcon(Icons.Rounded.Block, featuresEnabled) },
        )
    }
}

/** 模块总开关与框架连接状态：配置能否写入取决于这里的连接结果。 */
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
internal fun CornerRangePreview(
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
internal fun HitRangePreview(
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

/** 关于分组：一张卡两行，版本进二级页，交流反馈直接跳转。 */
@Composable
private fun AboutCard(onNavigateToAbout: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = stringResource(R.string.about_feedback_title),
            summary = stringResource(R.string.about_feedback_summary),
            onClick = {
                try {
                    uriHandler.openUri(FEEDBACK_GROUP_URL)
                } catch (_: IllegalArgumentException) {
                } catch (_: ActivityNotFoundException) {
                }
            },
            startAction = { PreferenceIcon(Icons.Rounded.Forum) },
        )
        ArrowPreference(
            title = stringResource(R.string.about_app_title),
            startAction = { PreferenceIcon(Icons.Rounded.Info) },
            bottomAction = {
                // 版本行：左为标签，右为完整版本号，右侧留出箭头的位置。
                Row(
                    // 起点与标题文字对齐：图标 24dp + 图标右间距 6dp。
                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.about_version_label),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            R.string.about_version_format,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE.toString(),
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                        textAlign = TextAlign.End,
                    )
                }
            },
            onClick = onNavigateToAbout,
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

internal const val OWN_REPO_URL = "https://github.com/Hoerire/FlymeFreeform"
internal const val UPSTREAM_REPO_URL = "https://github.com/Mangi-11/FlymeFreeform"

/** 交流反馈：QQ 群邀请链接，点击后交给系统/QQ 打开。 */
internal const val FEEDBACK_GROUP_URL = "https://qm.qq.com/q/OrRJTQDcsw"

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
/**
 * 拖动「图标大小」时的实时预览：按扇形真实几何画出槽位圆点与「更多」圆盘，
 * 用来确认主题图标与溢出按钮是否对齐。
 */
@Composable
internal fun IconSizePreview(
    iconSizeDp: Int,
    itemCount: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = MiuixTheme.colorScheme.primary
    val inactiveColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val basePixel = minOf(size.width, size.height) / RadialBaseShortEdgeDp
        // 与 RadialIconGeometry 一致：图标变大时半径同比外推。
        val arcRadius =
            (RadialBaseArcRadiusDp + (iconSizeDp - RadialBaseIconDiameterDp) / 2f) * basePixel
        val iconRadius = iconSizeDp * basePixel / 2f
        val slots = itemCount.coerceIn(1, 7)
        val step = RadialSpanDegrees / slots
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))

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
                    color = color.copy(alpha = if (enabled) 0.14f else 0.05f),
                    radius = iconRadius,
                    center = center,
                )
                drawCircle(
                    color = color.copy(alpha = if (enabled) 0.65f else 0.25f),
                    radius = iconRadius,
                    center = center,
                    style = Stroke(width = strokeWidth, pathEffect = dash),
                )
                // 最后一格是「更多」：按可见占比画，和应用图标对齐后的观感一致。
                val isMoreSlot = slot == slots - 1
                drawCircle(
                    color = color.copy(alpha = if (enabled) 0.85f else 0.30f),
                    radius = iconRadius * (if (isMoreSlot) 0.92f else 1f),
                    center = center,
                )
            }
        }

        drawSide(originX = 0f, originY = size.height, mirrored = false, enabled = leftEnabled)
        drawSide(originX = size.width, originY = size.height, mirrored = true, enabled = rightEnabled)
    }
}
