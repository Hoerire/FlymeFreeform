package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 轻量打开 · 场景。
 *
 * 总闸与所有启动场景集中在这一页，主页面只留一个入口：
 * 每个场景的开关独立生效，总闸关闭时整页被挡住（`sub` 为假）。
 */
@Composable
internal fun ZoomLaunchScenesScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomLaunchNoticeChange: (Boolean) -> Unit,
    onZoomLaunchHeadsUpChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
    onZoomLaunchInterAppResultChange: (Boolean) -> Unit,
    onZoomLaunchShareRelayChange: (Boolean) -> Unit,
    onZoomLaunchSystemChange: (Boolean) -> Unit,
    onZoomLaunchClipboardChange: (Boolean) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val sub = enabled
    val headsUpIcon = ImageVector.vectorResource(R.drawable.ic_heads_up_banner)
    SettingsPage(
        title = stringResource(R.string.zoom_interaction_title),
        onBack = onBack,
    ) {
        item(key = "hint") {
            SectionTitle(text = stringResource(R.string.zoom_launch_scenes_hint))
        }
        item(key = "launch") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.zoomLaunchNotice,
                    onCheckedChange = onZoomLaunchNoticeChange,
                    title = stringResource(R.string.zoom_launch_notice_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchHeadsUp,
                    onCheckedChange = onZoomLaunchHeadsUpChange,
                    title = stringResource(R.string.zoom_launch_heads_up_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(headsUpIcon, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchControlCenter,
                    onCheckedChange = onZoomLaunchControlCenterChange,
                    title = stringResource(R.string.zoom_launch_control_center_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Tune, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchInterApp,
                    onCheckedChange = onZoomLaunchInterAppChange,
                    title = stringResource(R.string.zoom_launch_inter_app_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.OpenInNew, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchInterAppResult,
                    onCheckedChange = onZoomLaunchInterAppResultChange,
                    title = stringResource(R.string.zoom_launch_inter_app_result_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Login, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchShareRelay,
                    onCheckedChange = onZoomLaunchShareRelayChange,
                    title = stringResource(R.string.zoom_launch_share_relay_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Share, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchSystem,
                    onCheckedChange = onZoomLaunchSystemChange,
                    title = stringResource(R.string.zoom_launch_system_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Memory, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchClipboard,
                    onCheckedChange = onZoomLaunchClipboardChange,
                    title = stringResource(R.string.zoom_launch_clipboard_title),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.ContentPaste, sub) },
                )
            }
        }
    }
}
