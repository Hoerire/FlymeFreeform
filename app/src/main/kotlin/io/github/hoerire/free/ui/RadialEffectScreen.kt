package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.RadialShimmer
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.ui.component.PreferenceIcon

/**
 * 选中特效二级页。
 *
 * 一级页只留一行入口：选中光圈与流光特效是两个独立开关，选中放大是独立滑杆，
 * 更细的流光参数（扫速 / 宽度 / 亮度 / 间隙 / 发光）再进三级页。
 */
@Composable
internal fun RadialEffectScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onRadialSelectionRingChange: (Boolean) -> Unit,
    onRadialShimmerEnabledChange: (Boolean) -> Unit,
    onRadialShimmerChange: (RadialShimmer) -> Unit,
    onNavigateToRadialShimmer: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    SettingsPage(
        title = stringResource(R.string.radial_effect_group_title),
        onBack = onBack,
    ) {
        item(key = "effectGroup") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.radialSelectionRing,
                    onCheckedChange = onRadialSelectionRingChange,
                    title = stringResource(R.string.radial_selection_ring_title),
                    summary = stringResource(R.string.radial_selection_ring_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.RadioButtonChecked, enabled) },
                )
                // 光圈大小：白色细边相对图标外沿的大小，100 即正好贴边。
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.RadioButtonChecked,
                    confirmedValue = state.settings.radialShimmer.ringSizePercent,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_ring_size_title),
                    summary = stringResource(R.string.radial_ring_size_summary),
                    minDp = RadialShimmer.RING_SIZE_RANGE.first,
                    maxDp = RadialShimmer.RING_SIZE_RANGE.last,
                    coerceDp = {
                        it.coerceIn(
                            RadialShimmer.RING_SIZE_RANGE.first,
                            RadialShimmer.RING_SIZE_RANGE.last,
                        )
                    },
                    onPreviewChange = {},
                    onCommit = {
                        onRadialShimmerChange(
                            state.settings.radialShimmer.copy(ringSizePercent = it),
                        )
                    },
                    showDpUnit = false,
                )
                // 选中放大是独立功能：两个特效开关都关掉时它照样生效。
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.ZoomIn,
                    confirmedValue = state.settings.radialShimmer.selectionScalePercent,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_shimmer_scale_title),
                    summary = stringResource(R.string.radial_shimmer_scale_summary),
                    minDp = RadialShimmer.SELECTION_SCALE_RANGE.first,
                    maxDp = RadialShimmer.SELECTION_SCALE_RANGE.last,
                    coerceDp = {
                        it.coerceIn(
                            RadialShimmer.SELECTION_SCALE_RANGE.first,
                            RadialShimmer.SELECTION_SCALE_RANGE.last,
                        )
                    },
                    onPreviewChange = {},
                    onCommit = {
                        onRadialShimmerChange(
                            state.settings.radialShimmer.copy(selectionScalePercent = it),
                        )
                    },
                    showDpUnit = false,
                )
                SwitchPreference(
                    checked = state.settings.radialShimmerEnabled,
                    onCheckedChange = onRadialShimmerEnabledChange,
                    title = stringResource(R.string.radial_shimmer_enabled_title),
                    summary = stringResource(R.string.radial_shimmer_enabled_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.AutoAwesome, enabled) },
                )
                ArrowPreference(
                    title = stringResource(R.string.radial_shimmer_title),
                    summary = stringResource(R.string.radial_shimmer_summary),
                    onClick = onNavigateToRadialShimmer,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.CenterFocusStrong, enabled) },
                )
            }
        }
    }
}
