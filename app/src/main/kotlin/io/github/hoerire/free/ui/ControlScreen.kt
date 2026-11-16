package io.github.hoerire.free.ui

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.BuildConfig
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.OutsideTapCloseMode
import io.github.hoerire.free.framework.FrameworkConnectionIssue
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.framework.FrameworkConnectionStatus
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.BasicComponent
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SliderRow
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.update.UpdateState
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceEntry
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    onRequestScopes: () -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    onNavigateToRadialSettings: () -> Unit,
    onNavigateToBlacklist: () -> Unit,
    onNavigateToZoomOptions: () -> Unit,
    onNavigateToFreeDrag: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    val featuresEnabled = state.settingsEditable && state.settings.enabled

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowWidth = maxWidth
        val sidePadding = maxOf(ScreenHorizontalMargin, (windowWidth - ScreenContentMaxWidth) / 2)
        // 页面内容录进一条采样层，更新弹窗才能透出它；弹窗本体放在录制层之外。
        PageBackdropScope(
            content = {
                GlasmioPinnedListPage(
                    title = stringResource(R.string.app_name),
                    horizontalContentPadding = sidePadding,
                    verticalArrangement = Arrangement.spacedBy(CardSpacing),
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
                            onNavigateToFreeDrag = onNavigateToFreeDrag,
                        )
                    }
                    item(key = "about") {
                        AboutCard(onNavigateToAbout = onNavigateToAbout)
                    }
                }
            },
            overlay = {
                UpdateOverlay(
                    update = update,
                    onDismissUpdate = onDismissUpdate,
                )
            },
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
    onNavigateToFreeDrag: () -> Unit,
) {
    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
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
        // 自由传送原本是独立卡片（开关 + 长按时长两条），并进这张卡后只留一行入口，
        // 具体开关与滑杆都在二级页里。
        ArrowPreference(
            title = stringResource(R.string.free_drag_title),
            summary = stringResource(R.string.free_drag_summary),
            onClick = onNavigateToFreeDrag,
            enabled = featuresEnabled,
            startAction = {
                PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_free_share), featuresEnabled)
            },
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
    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
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
            onClick = null,
            endActions = {
                Text(
                    text = presentation.label,
                    color = GlasmioTheme.colors.secondaryContent,
                    style = IosTypography.subheadline,
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
    // Initial 阶段只观察不消费；按下才允许改值，抬手才提交。
    Box(
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
    ) {
        SliderRow(
            title = title,
            summary = summary,
            value = draftValue,
            valueRange = minDp.toFloat()..maxDp.toFloat(),
            onValueChange = { value ->
                // 只认真实按下时的回调。滑条在数值同步时也会推送数值，
                // 将那些帧误判为拖动会写入配置，导致数值持续漂移。
                if (pressed) {
                    draftValue = value.coerceIn(minDp.toFloat(), maxDp.toFloat())
                    onPreviewChange(coerceDp(draftValue.roundToInt()))
                }
            },
            onValueChangeFinished = {},
            valueText =
                if (showDpUnit) {
                    stringResource(R.string.dp_value, coerceDp(draftValue.roundToInt()))
                } else {
                    coerceDp(draftValue.roundToInt()).toString()
                },
            icon = { PreferenceIcon(icon, enabled) },
            enabled = enabled,
        )
    }
}

@Composable
internal fun CornerRangePreview(
    rangeDp: Int,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
) {
    val activeColor = GlasmioTheme.colors.accent
    val inactiveColor = GlasmioTheme.colors.secondaryContent
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
    val activeColor = GlasmioTheme.colors.accent
    val inactiveColor = GlasmioTheme.colors.secondaryContent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val basePixel = minOf(size.width, size.height) / RadialBaseShortEdgeDp
        val arcRadius = RadialBaseArcRadiusDp * basePixel
        val iconRadius = RadialBaseIconDiameterDp * basePixel / 2f
        // 命中判定现在就是按屏幕 dp 直算（见 RadialIconGeometry），预览也按同一口径画，
        // 所见即所得：圈内即可选中。
        val hitRadius = hitRangeDp.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
        val slots = itemCount.coerceIn(1, ModulePreferences.MAX_PINNED_APPS + 1)
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
    SettingsGroup(modifier = Modifier.fillMaxWidth()) {
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
        GlasmioPreferenceEntry(
            title = { Text(stringResource(R.string.about_app_title), style = IosTypography.body) },
            content = {
                // 版本行：左为标签，右为完整版本号，右侧留出箭头的位置。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.about_version_label),
                        color = GlasmioTheme.colors.secondaryContent,
                        style = IosTypography.subheadline,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            R.string.about_version_format,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE.toString(),
                        ),
                        color = GlasmioTheme.colors.secondaryContent,
                        style = IosTypography.subheadline,
                        textAlign = TextAlign.End,
                    )
                }
            },
            icon = { PreferenceIcon(Icons.Rounded.Info) },
            trailingContent = {
                SfIcon(
                    systemName = "chevron.right",
                    contentDescription = null,
                    size = 14.dp,
                    tint = GlasmioTheme.colors.tertiaryContent,
                )
            },
            onClick = onNavigateToAbout,
            showTopSeparator = false,
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
            if (enabled) GlasmioTheme.colors.content
            else GlasmioTheme.colors.tertiaryContent,
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

internal const val OWN_REPO_URL = "https://github.com/Hoerire/Free"
internal const val UPSTREAM_REPO_URL = "https://github.com/Mangi-11/Flyna"

/** 交流反馈：QQ 群邀请链接，点击后交给系统/QQ 打开。 */
internal const val FEEDBACK_GROUP_URL = "https://qm.qq.com/q/OrRJTQDcsw"

private const val RadialBaseShortEdgeDp = 400f
private const val RadialBaseArcRadiusDp = 242f
private const val RadialBaseIconDiameterDp = 44f
private const val RadialSpanDegrees = 84f
private const val RadialSafeDegrees = 3f

internal val ScreenHorizontalMargin = 12.dp
internal val ScreenContentMaxWidth = 600.dp
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
    val activeColor = GlasmioTheme.colors.accent
    val inactiveColor = GlasmioTheme.colors.secondaryContent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val basePixel = minOf(size.width, size.height) / RadialBaseShortEdgeDp
        // 与 RadialIconGeometry 一致：图标变大时半径同比外推。
        val arcRadius =
            (RadialBaseArcRadiusDp + (iconSizeDp - RadialBaseIconDiameterDp) / 2f) * basePixel
        val iconRadius = iconSizeDp * basePixel / 2f
        val slots = itemCount.coerceIn(1, ModulePreferences.MAX_PINNED_APPS + 1)
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
