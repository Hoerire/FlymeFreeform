package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 小窗交互的二级页面。
 *
 * 各项独立挂钩、独立判定，互不依赖：
 * - 窗外关闭：扩展标题层触摸区，接管窗外的点击；
 * - 小窗数量：改写 `getMaxWinNum`；
 * - 解除限制：`isSupportFlexibleWindow` 一律放行；
 * - 启动场景转小窗：在 ActivityStarter 改写通知、控制中心与应用间的启动；
 * - 快速上滑：改写小窗手柄的上滑模式。
 *
 * 除窗外关闭有默认取值外，其余能力默认关闭，由用户按需打开。
 */
@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomInteractionEnabledChange: (Boolean) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchChange: (Boolean) -> Unit,
    onZoomLaunchNoticeChange: (Boolean) -> Unit,
    onZoomLaunchHeadsUpChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
    onZoomLaunchSidebarChange: (Boolean) -> Unit,
    onZoomLauncherShortcutChange: (Boolean) -> Unit,
    onZoomLauncherCardChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onNavigateToZoomExcluded: () -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    // 总开关本身只受模块总开关约束；它下面所有项还要再看它自己。
    val master = state.settings.zoomInteractionEnabled
    val subEnabled = enabled && master
    val outsideTapModes = OutsideTapCloseMode.entries
    val outsideTapItems =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
        )
    SettingsPage(
        title = stringResource(R.string.zoom_limit_removed_title),
        onBack = onBack,
    ) {
        item(key = "options") {
            Card(modifier = Modifier.fillMaxWidth()) {
                // 快速上滑转迷你：独立项，不受「轻量打开」控制，放在最上面。
                SwitchPreference(
                    checked = state.settings.handleSwipeUpToMiniEnabled,
                    onCheckedChange = onHandleSwipeUpToMiniEnabledChange,
                    title = stringResource(R.string.handle_swipe_up_to_mini_title),
                    summary = stringResource(R.string.handle_swipe_up_to_mini_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, enabled) },
                )
                OverlaySpinnerPreference(
                    items = outsideTapItems,
                    selectedIndex = outsideTapModes.indexOf(state.settings.outsideTapCloseMode),
                    title = stringResource(R.string.outside_tap_close_title),
                    summary = stringResource(R.string.outside_tap_close_summary),
                    // 窗外关闭独立于「轻量打开」，不受总开关控制。
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.TouchApp, enabled) },
                    onSelectedIndexChange = { index ->
                        outsideTapModes.getOrNull(index)?.let(onOutsideTapCloseModeChange)
                    },
                )
                // 总开关「轻量打开」：默认关，关闭时它下面每一项都灰掉且不介入系统。
                SwitchPreference(
                    checked = master,
                    onCheckedChange = onZoomInteractionEnabledChange,
                    title = stringResource(R.string.zoom_interaction_title),
                    summary = stringResource(R.string.zoom_interaction_summary),
                    // 总开关只看模块总开关，不受自己影响。
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.TouchApp, enabled) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Widgets,
                    confirmedValue = state.settings.zoomMaxWindowCount,
                    isUpdating = state.isUpdating,
                    enabled = subEnabled,
                    title = stringResource(R.string.zoom_max_windows_title),
                    summary = stringResource(R.string.zoom_max_windows_summary),
                    minDp = ModulePreferences.MIN_ZOOM_MAX_WINDOW_COUNT,
                    maxDp = ModulePreferences.MAX_ZOOM_MAX_WINDOW_COUNT,
                    coerceDp = ModulePreferences::coerceZoomMaxWindowCount,
                    onPreviewChange = {},
                    onCommit = onZoomMaxWindowCountChange,
                    showDpUnit = false,
                )
                // 解除限制＝原本不支持的应用也放行，图标用「开锁」直接表达解锁准入。
                SwitchPreference(
                    checked = state.settings.zoomAllowAnyApp,
                    onCheckedChange = onZoomAllowAnyAppChange,
                    title = stringResource(R.string.zoom_allow_any_app_title),
                    summary = stringResource(R.string.zoom_allow_any_app_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.LockOpen, enabled) },
                )
                // 小窗跳转按场景拆开：原来那一个开关现在由下面五项分别控制。
                SwitchPreference(
                    checked = state.settings.zoomLaunchNotice,
                    onCheckedChange = onZoomLaunchNoticeChange,
                    title = stringResource(R.string.zoom_launch_notice_title),
                    summary = stringResource(R.string.zoom_launch_notice_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchHeadsUp,
                    onCheckedChange = onZoomLaunchHeadsUpChange,
                    title = stringResource(R.string.zoom_launch_heads_up_title),
                    summary = stringResource(R.string.zoom_launch_heads_up_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchControlCenter,
                    onCheckedChange = onZoomLaunchControlCenterChange,
                    title = stringResource(R.string.zoom_launch_control_center_title),
                    summary = stringResource(R.string.zoom_launch_control_center_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchInterApp,
                    onCheckedChange = onZoomLaunchInterAppChange,
                    title = stringResource(R.string.zoom_launch_inter_app_title),
                    summary = stringResource(R.string.zoom_launch_inter_app_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchSidebar,
                    onCheckedChange = onZoomLaunchSidebarChange,
                    title = stringResource(R.string.zoom_launch_sidebar_title),
                    summary = stringResource(R.string.zoom_launch_sidebar_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, enabled) },
                )
                // 桌面上的两个入口单独可关：关掉后保持系统默认的全屏行为。
                SwitchPreference(
                    checked = state.settings.zoomLauncherShortcut,
                    onCheckedChange = onZoomLauncherShortcutChange,
                    title = stringResource(R.string.zoom_launcher_shortcut_title),
                    summary = stringResource(R.string.zoom_launcher_shortcut_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, enabled) },
                )
                // 窗口超过数量限制时：开 → 直接关闭旧窗口；关 → 保持系统收成贴边浮标。
                SwitchPreference(
                    checked = state.settings.autoCloseWindow,
                    onCheckedChange = onAutoCloseWindowChange,
                    title = stringResource(R.string.auto_close_window_title),
                    summary = stringResource(R.string.auto_close_window_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.CropFree, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLauncherCard,
                    onCheckedChange = onZoomLauncherCardChange,
                    title = stringResource(R.string.zoom_launcher_card_title),
                    summary = stringResource(R.string.zoom_launcher_card_summary),
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Widgets, enabled) },
                )
                // 禁用浮窗＝列表内应用不以任何形式的自由浮窗打开，图标用「四角括号」画全屏边界。
                ArrowPreference(
                    title = stringResource(R.string.zoom_excluded_title),
                    summary = stringResource(R.string.zoom_excluded_summary),
                    onClick = onNavigateToZoomExcluded,
                    enabled = subEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.CropFree, enabled) },
                )
            }
        }
    }
}
