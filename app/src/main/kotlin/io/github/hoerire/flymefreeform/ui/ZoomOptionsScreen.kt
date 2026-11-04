package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 小窗扩展的二级页面。
 *
 * 三个子功能各自独立挂钩、独立判定，互不依赖：
 * - 解除数量上限：改写 `getMaxWinNum`；
 * - 解除应用准入：`isSupportFlexibleWindow` 一律放行；
 * - 启动场景转小窗：在 ActivityStarter 抢占通知/横幅/控制中心的启动。
 *
 * 默认全部关闭，由用户按需打开。
 */
@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchNotificationChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    ScreenScaffold(
        title = stringResource(R.string.zoom_limit_removed_title),
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalMargin,
                    top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                    end = ScreenHorizontalMargin,
                    bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                ),
        ) {
            item(key = "count") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    RemoteDpSliderPreference(
                        icon = Icons.Rounded.Widgets,
                        confirmedValue = state.settings.zoomMaxWindowCount,
                        isUpdating = state.isUpdating,
                        enabled = enabled,
                        title = stringResource(R.string.zoom_max_windows_title),
                        summary = stringResource(R.string.zoom_max_windows_summary),
                        minDp = ModulePreferences.MIN_ZOOM_MAX_WINDOW_COUNT,
                        maxDp = ModulePreferences.MAX_ZOOM_MAX_WINDOW_COUNT,
                        coerceDp = ModulePreferences::coerceZoomMaxWindowCount,
                        onPreviewChange = {},
                        onCommit = onZoomMaxWindowCountChange,
                        showDpUnit = false,
                    )
                }
            }
            item(key = "apps") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = state.settings.zoomAllowAnyApp,
                        onCheckedChange = onZoomAllowAnyAppChange,
                        title = stringResource(R.string.zoom_allow_any_app_title),
                        summary = stringResource(R.string.zoom_allow_any_app_summary),
                        enabled = enabled,
                        startAction = { PreferenceIcon(Icons.Rounded.Apps, enabled) },
                    )
                }
            }
            item(key = "launch_notification") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = state.settings.zoomLaunchNotification,
                        onCheckedChange = onZoomLaunchNotificationChange,
                        title = stringResource(R.string.zoom_launch_notification_title),
                        summary = stringResource(R.string.zoom_launch_notification_summary),
                        enabled = enabled,
                        startAction = { PreferenceIcon(Icons.Rounded.Notifications, enabled) },
                    )
                }
            }
            item(key = "launch_control_center") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = state.settings.zoomLaunchControlCenter,
                        onCheckedChange = onZoomLaunchControlCenterChange,
                        title = stringResource(R.string.zoom_launch_control_center_title),
                        summary = stringResource(R.string.zoom_launch_control_center_summary),
                        enabled = enabled,
                        startAction = { PreferenceIcon(Icons.Rounded.OpenInNew, enabled) },
                    )
                }
            }
            item(key = "launch_inter_app") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = state.settings.zoomLaunchInterApp,
                        onCheckedChange = onZoomLaunchInterAppChange,
                        title = stringResource(R.string.zoom_launch_inter_app_title),
                        summary = stringResource(R.string.zoom_launch_inter_app_summary),
                        enabled = enabled,
                        startAction = { PreferenceIcon(Icons.Rounded.SwapHoriz, enabled) },
                    )
                }
            }
        }
    }
}
