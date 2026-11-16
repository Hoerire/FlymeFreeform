package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.platform.coloros.PortalSupport
import io.github.hoerire.free.ui.component.PreferenceIcon
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference

/**
 * 自由传送二级页：总开关 + 长按时长。
 *
 * 主设置页的「配置」卡片里只保留一行入口：总开关与滑杆各占一行会让那张卡多出一块高度，
 * 而两者只在需要时才会调。
 */
@Composable
internal fun FreeDragScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onFreeDragChange: (Boolean) -> Unit,
    onDragLongPressChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    // 只有设备上不存在内容传送门时才禁用：面板接管不依赖具体版本。
    val portalSupported = remember(context) { PortalSupport.isSupported(context) }
    val featuresEnabled = state.settingsEditable && state.settings.enabled
    val usable = featuresEnabled && portalSupported
    SettingsPage(
        title = stringResource(R.string.free_drag_title),
        onBack = onBack,
    ) {
        item(key = "freeDragGroup") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.freeDragEnabled,
                    onCheckedChange = onFreeDragChange,
                    title = stringResource(R.string.free_drag_title),
                    summary =
                        if (portalSupported) stringResource(R.string.free_drag_summary)
                        else stringResource(R.string.free_drag_unsupported),
                    enabled = usable,
                    startAction = {
                        PreferenceIcon(
                            ImageVector.vectorResource(R.drawable.ic_free_share),
                            usable,
                        )
                    },
                )
                RemoteDpSliderPreference(
                    icon = ImageVector.vectorResource(R.drawable.ic_trigger_delay),
                    confirmedValue = state.settings.dragLongPressMs,
                    isUpdating = state.isUpdating,
                    enabled = usable && state.settings.freeDragEnabled,
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
    }
}
