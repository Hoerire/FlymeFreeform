package io.github.hoerire.free.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.gesture.RadialGeometry
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.window.RadialIconGeometry
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 外观参数页：命中范围、图标大小、扇形大小、背景遮罩。
 *
 * 一级页只保留入口行，四条滑杆集中在此页；页顶是实时示意图，
 * 拖动时用预览值重画，因此每行只写一行说明即可。
 */
@Composable
internal fun RadialSizeScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onRadialIconSizeChange: (Int) -> Unit,
    onRadialRadiusPercentChange: (Int) -> Unit,
    onBackdropLevelChange: (Int) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    var previewHitRangeDp by remember { mutableStateOf<Int?>(null) }
    var previewIconSizeDp by remember { mutableStateOf<Int?>(null) }
    var previewRadiusPercent by remember { mutableStateOf<Int?>(null) }
    var previewBackdropLevel by remember { mutableStateOf<Int?>(null) }

    SettingsPage(
        title = stringResource(R.string.radial_size_page_title),
        onBack = onBack,
    ) {
        item(key = "sizePreview") {
            RadialSizePreview(
                hitRangeDp = previewHitRangeDp ?: state.settings.radialHitRangeDp,
                iconSizeDp = previewIconSizeDp ?: state.settings.radialIconSizeDp,
                radiusPercent = previewRadiusPercent ?: state.settings.radialRadiusPercent,
                backdropLevel = previewBackdropLevel ?: state.settings.backdropLevel,
            )
        }
        item(key = "sizeGroup") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.CenterFocusStrong,
                    confirmedValue = state.settings.radialHitRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_hit_range_title),
                    summary = stringResource(R.string.radial_hit_range_summary),
                    minDp = ModulePreferences.MIN_RADIAL_HIT_RANGE_DP,
                    maxDp = ModulePreferences.MAX_RADIAL_HIT_RANGE_DP,
                    coerceDp = ModulePreferences::coerceRadialHitRangeDp,
                    onPreviewChange = { previewHitRangeDp = it },
                    onCommit = onRadialHitRangeChange,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Apps,
                    confirmedValue = state.settings.radialIconSizeDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_icon_size_title),
                    summary = stringResource(R.string.radial_icon_size_summary),
                    minDp = ModulePreferences.MIN_RADIAL_ICON_SIZE_DP,
                    maxDp = ModulePreferences.MAX_RADIAL_ICON_SIZE_DP,
                    coerceDp = ModulePreferences::coerceRadialIconSizeDp,
                    onPreviewChange = { previewIconSizeDp = it },
                    onCommit = onRadialIconSizeChange,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.ZoomOutMap,
                    confirmedValue = state.settings.radialRadiusPercent,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_radius_title),
                    summary = stringResource(R.string.radial_radius_summary),
                    minDp = ModulePreferences.MIN_RADIAL_RADIUS_PERCENT,
                    maxDp = ModulePreferences.MAX_RADIAL_RADIUS_PERCENT,
                    coerceDp = ModulePreferences::coerceRadialRadiusPercent,
                    onPreviewChange = { previewRadiusPercent = it },
                    onCommit = onRadialRadiusPercentChange,
                    showDpUnit = false,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.BlurCircular,
                    confirmedValue = state.settings.backdropLevel,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_backdrop_title),
                    summary = stringResource(R.string.radial_backdrop_summary),
                    minDp = ModulePreferences.MIN_BACKDROP_LEVEL,
                    maxDp = ModulePreferences.MAX_BACKDROP_LEVEL,
                    coerceDp = ModulePreferences::coerceBackdropLevel,
                    onPreviewChange = { previewBackdropLevel = it },
                    onCommit = onBackdropLevelChange,
                    showDpUnit = false,
                )
            }
        }
    }
}

/**
 * 尺寸与命中的实时示意图：左下角是屏幕角落，弧上 5 格是扇形里的图标，
 * 虚线圆是「命中范围」，中间那一格画成选中态（白边 + 光晕）。
 *
 * 比例按卡片高度自适应，所有换算都用 dp 对 dp，所以拖动滑杆时图会跟着变，
 * 但不会因为屏幕尺寸不同而失真。
 */
@Composable
private fun RadialSizePreview(
    hitRangeDp: Int,
    iconSizeDp: Int,
    radiusPercent: Int,
    backdropLevel: Int,
) {
    val colors = COUITheme.colorScheme
    Column {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(PREVIEW_HEIGHT_DP)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.backgroundElevated),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 背景遮罩浓度：呼出时整块屏幕被压暗的程度。
                drawRect(
                    color = Color.Black.copy(alpha = backdropLevel / 100f * PREVIEW_DIM_SCALE),
                )
                val radiusDp =
                    RadialIconGeometry.BASE_RADIUS_DP *
                        (radiusPercent.coerceIn(
                            ModulePreferences.MIN_RADIAL_RADIUS_PERCENT,
                            ModulePreferences.MAX_RADIAL_RADIUS_PERCENT,
                        ) / 100f)
                val iconDp = iconSizeDp.toFloat().coerceAtLeast(1f)
                val hitDp = hitRangeDp.toFloat().coerceAtLeast(1f)
                // 竖向要放下「半径 + 命中圈」，横向同理，再留一点边距。
                val neededDp = radiusDp + hitDp + iconDp * 0.5f + PREVIEW_PADDING_DP * 2f
                val pxPerDp = minOf(size.width, size.height) / neededDp
                val origin = Offset(PREVIEW_PADDING_DP * pxPerDp, size.height - PREVIEW_PADDING_DP * pxPerDp)
                val radiusPx = radiusDp * pxPerDp
                val hitPx = hitDp * pxPerDp
                val iconPx = iconDp * pxPerDp
                val dash = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))
                val step = RadialGeometry.SPAN_DEGREES / PREVIEW_SLOTS

                for (index in 0 until PREVIEW_SLOTS) {
                    val slot = PREVIEW_SLOTS - index - 1
                    val degrees = -RadialGeometry.SAFE_DEGREES - (slot + 0.5f) * step
                    val radians = degrees * PI.toFloat() / 180f
                    val center =
                        Offset(
                            x = origin.x + cos(radians) * radiusPx,
                            y = origin.y + sin(radians) * radiusPx,
                        )
                    drawCircle(color = colors.primary.copy(alpha = 0.10f), radius = hitPx, center = center)
                    drawCircle(
                        color = colors.primary.copy(alpha = 0.55f),
                        radius = hitPx,
                        center = center,
                        style = Stroke(width = 1.4.dp.toPx(), pathEffect = dash),
                    )
                    drawCircle(
                        color = colors.primary.copy(alpha = 0.85f),
                        radius = iconPx / 2f,
                        center = center,
                    )
                    // 中间那一格画成选中态：外发光 + 细白边，顺带示意选中特效。
                    if (index == PREVIEW_SLOTS / 2) {
                        val glowPx = iconPx * PREVIEW_GLOW_SCALE
                        drawCircle(
                            brush =
                                Brush.radialGradient(
                                    colors = listOf(colors.labelPrimary.copy(alpha = 0.5f), Color.Transparent),
                                    center = center,
                                    radius = glowPx,
                                ),
                            radius = glowPx,
                            center = center,
                        )
                        drawCircle(
                            color = colors.labelPrimary.copy(alpha = 0.9f),
                            radius = iconPx / 2f,
                            center = center,
                            style = Stroke(width = 1.4.dp.toPx()),
                        )
                    }
                }
                // 角落原点。
                drawCircle(color = colors.labelTertiary, radius = 3.dp.toPx(), center = origin)
            }
        }
        androidx.compose.material3.Text(
            text = stringResource(R.string.radial_size_preview_hint),
            style = COUITheme.textStyles.bodyXS,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 6.dp),
        )
    }
}

/** 预览卡高度。 */
private val PREVIEW_HEIGHT_DP = 176.dp
/** 预览里「屏幕角落」到卡片边缘的留白（dp）。 */
private const val PREVIEW_PADDING_DP = 16f
/** 预览格数：固定 5 格，够看出排布与命中圈关系。 */
private const val PREVIEW_SLOTS = 5
/** 背景遮罩在预览里的最大压暗程度（0..1）。 */
private const val PREVIEW_DIM_SCALE = 0.35f
/** 选中格光晕半径相对图标直径的倍数。 */
private const val PREVIEW_GLOW_SCALE = 1.6f
