package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.hoerire.free.ui.component.PreferenceIcon

/**
 * 贴边浮标二级页：贴边挂机与浮标数量。
 *
 * 入口行的 summary 只列条目名，不复述这里的说明文字。
 */
@Composable
internal fun EdgeFloatScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onFloatKeepAliveChange: (Boolean) -> Unit,
    onFloatHandleMaxCountChange: (Int) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    SettingsPage(
        title = stringResource(R.string.zoom_group_edge_title),
        onBack = onBack,
    ) {
        item(key = "edgeGroup") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.floatKeepAlive,
                    onCheckedChange = onFloatKeepAliveChange,
                    title = stringResource(R.string.float_keep_alive_title),
                    summary = stringResource(R.string.float_keep_alive_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ElectricalServices, enabled) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.PushPin,
                    confirmedValue = state.settings.floatHandleMaxCount,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.float_handle_max_title),
                    summary = stringResource(R.string.float_handle_max_summary),
                    minDp = ModulePreferences.MIN_FLOAT_HANDLE_MAX_COUNT,
                    maxDp = ModulePreferences.MAX_FLOAT_HANDLE_MAX_COUNT,
                    coerceDp = ModulePreferences::coerceFloatHandleMaxCount,
                    onPreviewChange = {},
                    onCommit = onFloatHandleMaxCountChange,
                    showDpUnit = false,
                )
            }
        }
    }
}
