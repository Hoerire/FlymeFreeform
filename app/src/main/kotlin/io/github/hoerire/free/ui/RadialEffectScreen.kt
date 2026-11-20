package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.OpenInFull
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
 * 一级页只留一行入口：流光特效开关、选中外顶滑杆，更细的流光参数
 * （扫速 / 宽度 / 亮度 / 间隙 / 发光）再进三级页。
 */
@Composable
internal fun RadialEffectScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
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
                // 选中放大：默认 0（关闭），需要时自己调；只作用于选中的那一格。
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
                // 选中外顶：选中格沿半径向外顶出的比例（图标直径的百分比）。
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.OpenInFull,
                    confirmedValue = state.settings.radialShimmer.selectedPushPercent,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_selected_push_title),
                    summary = stringResource(R.string.radial_selected_push_summary),
                    minDp = RadialShimmer.SELECTED_PUSH_RANGE.first,
                    maxDp = RadialShimmer.SELECTED_PUSH_RANGE.last,
                    coerceDp = {
                        it.coerceIn(
                            RadialShimmer.SELECTED_PUSH_RANGE.first,
                            RadialShimmer.SELECTED_PUSH_RANGE.last,
                        )
                    },
                    onPreviewChange = {},
                    onCommit = {
                        onRadialShimmerChange(
                            state.settings.radialShimmer.copy(selectedPushPercent = it),
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
