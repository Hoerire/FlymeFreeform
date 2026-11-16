package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurCircular
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.RadialShimmer
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.SectionTitle
import io.github.hoerire.free.ui.component.SettingsGroup

/**
 * 流光参数三级页：选中流光的六条滑杆。
 *
 * 入口在「选中特效」二级页；独立成页的依据是七条同类滑杆内联展开会把所在分组撑得很长，
 * 且展开/收起各多一次交互。
 */
@Composable
internal fun RadialShimmerScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onRadialShimmerChange: (RadialShimmer) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    SettingsPage(
        title = stringResource(R.string.radial_shimmer_title),
        onBack = onBack,
    ) {
        item(key = "shimmerParams_title") {
            SectionTitle(text = stringResource(R.string.radial_shimmer_title))
        }
        item(key = "shimmerParams") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                val shimmer = state.settings.radialShimmer
                ShimmerSlider(
                    title = stringResource(R.string.radial_shimmer_speed_title),
                    summary = stringResource(R.string.radial_shimmer_speed_summary),
                    icon = Icons.Rounded.Speed,
                    value = shimmer.speedDegPerSecond,
                    range = RadialShimmer.SPEED_RANGE,
                    enabled = enabled,
                    isUpdating = state.isUpdating,
                    onCommit = { onRadialShimmerChange(shimmer.copy(speedDegPerSecond = it)) },
                )
                ShimmerSlider(
                    title = stringResource(R.string.radial_shimmer_arc_width_title),
                    summary = stringResource(R.string.radial_shimmer_arc_width_summary),
                    icon = Icons.Rounded.Straighten,
                    value = shimmer.arcWidthPercent,
                    range = RadialShimmer.ARC_WIDTH_RANGE,
                    enabled = enabled,
                    isUpdating = state.isUpdating,
                    onCommit = { onRadialShimmerChange(shimmer.copy(arcWidthPercent = it)) },
                )
                ShimmerSlider(
                    title = stringResource(R.string.radial_shimmer_arc_alpha_title),
                    summary = stringResource(R.string.radial_shimmer_arc_alpha_summary),
                    icon = Icons.Rounded.Opacity,
                    value = shimmer.arcAlphaPercent,
                    range = RadialShimmer.PERCENT_RANGE,
                    enabled = enabled,
                    isUpdating = state.isUpdating,
                    onCommit = { onRadialShimmerChange(shimmer.copy(arcAlphaPercent = it)) },
                )
                ShimmerSlider(
                    title = stringResource(R.string.radial_shimmer_arc_gap_title),
                    summary = stringResource(R.string.radial_shimmer_arc_gap_summary),
                    icon = Icons.Rounded.SpaceBar,
                    value = shimmer.arcGapPercent,
                    range = RadialShimmer.ARC_GAP_RANGE,
                    enabled = enabled,
                    isUpdating = state.isUpdating,
                    onCommit = { onRadialShimmerChange(shimmer.copy(arcGapPercent = it)) },
                )
                ShimmerSlider(
                    title = stringResource(R.string.radial_shimmer_glow_alpha_title),
                    summary = stringResource(R.string.radial_shimmer_glow_alpha_summary),
                    icon = Icons.Rounded.Flare,
                    value = shimmer.glowAlphaPercent,
                    range = RadialShimmer.PERCENT_RANGE,
                    enabled = enabled,
                    isUpdating = state.isUpdating,
                    onCommit = { onRadialShimmerChange(shimmer.copy(glowAlphaPercent = it)) },
                )
                ShimmerSlider(
                    title = stringResource(R.string.radial_shimmer_glow_radius_title),
                    summary = stringResource(R.string.radial_shimmer_glow_radius_summary),
                    icon = Icons.Rounded.BlurCircular,
                    value = shimmer.glowRadiusPercent,
                    range = RadialShimmer.GLOW_RADIUS_RANGE,
                    enabled = enabled,
                    isUpdating = state.isUpdating,
                    onCommit = { onRadialShimmerChange(shimmer.copy(glowRadiusPercent = it)) },
                )
            }
        }
    }
}

/** 流光参数滑杆：区间与提交都交给上层，读值即参数本身。 */
@Composable
private fun ShimmerSlider(
    title: String,
    summary: String,
    icon: ImageVector,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    isUpdating: Boolean,
    onCommit: (Int) -> Unit,
) {
    RemoteDpSliderPreference(
        icon = icon,
        confirmedValue = value,
        isUpdating = isUpdating,
        enabled = enabled,
        title = title,
        summary = summary,
        minDp = range.first,
        maxDp = range.last,
        coerceDp = { it.coerceIn(range.first, range.last) },
        onPreviewChange = {},
        onCommit = onCommit,
        showDpUnit = false,
    )
}
