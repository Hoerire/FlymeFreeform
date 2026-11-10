package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 小窗交互。
 *
 * 结构是「一个总闸 + 三组能力」，而不是一列平铺开关：
 * 1. 轻量打开：总闸，常驻可用；关掉后下面三组整体不介入系统。
 * 2. 应用启动：哪些入口改走自由浮窗（通知、横幅、控制中心、应用互跳、桌面两处）。
 * 3. 窗口行为：数量上限、解除准入、超限收窗、禁用浮窗名单。
 * 4. 手势与关闭：不随总闸走的两个独立项。
 *
 * 分组标题会写明当前是否被总闸挡住，避免出现"点了没反应但不知道为什么"。
 */
@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomInteractionEnabledChange: (Boolean) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchNoticeChange: (Boolean) -> Unit,
    onZoomLaunchHeadsUpChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
    onZoomLauncherShortcutChange: (Boolean) -> Unit,
    onZoomLauncherCardChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onNavigateToZoomExcluded: () -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val master = state.settings.zoomInteractionEnabled
    val sub = enabled && master
    val outsideTapModes = OutsideTapCloseMode.entries
    val outsideTapItems =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
        )
    // 总闸关闭时，分组标题直接说明为什么整组是灰的。
    val lockedHint = if (master) null else stringResource(R.string.zoom_group_locked_hint)

    SettingsPage(
        title = stringResource(R.string.zoom_limit_removed_title),
        onBack = onBack,
    ) {
        item(key = "master") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = master,
                    onCheckedChange = onZoomInteractionEnabledChange,
                    title = stringResource(R.string.zoom_interaction_title),
                    summary =
                        stringResource(
                            if (master) R.string.zoom_master_on_summary else R.string.zoom_master_off_summary,
                        ),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PowerSettingsNew, enabled) },
                )
            }
        }

        item(key = "launch") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = stringResource(R.string.zoom_group_launch_title),
                    hint = lockedHint,
                    enabled = sub,
                    icon = Icons.Rounded.Apps,
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchNotice,
                    onCheckedChange = onZoomLaunchNoticeChange,
                    title = stringResource(R.string.zoom_launch_notice_title),
                    summary = stringResource(R.string.zoom_launch_notice_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchHeadsUp,
                    onCheckedChange = onZoomLaunchHeadsUpChange,
                    title = stringResource(R.string.zoom_launch_heads_up_title),
                    summary = stringResource(R.string.zoom_launch_heads_up_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchControlCenter,
                    onCheckedChange = onZoomLaunchControlCenterChange,
                    title = stringResource(R.string.zoom_launch_control_center_title),
                    summary = stringResource(R.string.zoom_launch_control_center_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchInterApp,
                    onCheckedChange = onZoomLaunchInterAppChange,
                    title = stringResource(R.string.zoom_launch_inter_app_title),
                    summary = stringResource(R.string.zoom_launch_inter_app_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPictureAlt, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLauncherShortcut,
                    onCheckedChange = onZoomLauncherShortcutChange,
                    title = stringResource(R.string.zoom_launcher_shortcut_title),
                    summary = stringResource(R.string.zoom_launcher_shortcut_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLauncherCard,
                    onCheckedChange = onZoomLauncherCardChange,
                    title = stringResource(R.string.zoom_launcher_card_title),
                    summary = stringResource(R.string.zoom_launcher_card_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Widgets, sub) },
                )
            }
        }

        item(key = "window") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = stringResource(R.string.zoom_group_window_title),
                    hint = lockedHint,
                    enabled = sub,
                    icon = Icons.Rounded.Widgets,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Widgets,
                    confirmedValue = state.settings.zoomMaxWindowCount,
                    isUpdating = state.isUpdating,
                    enabled = sub,
                    title = stringResource(R.string.zoom_max_windows_title),
                    summary = stringResource(R.string.zoom_max_windows_summary),
                    minDp = ModulePreferences.MIN_ZOOM_MAX_WINDOW_COUNT,
                    maxDp = ModulePreferences.MAX_ZOOM_MAX_WINDOW_COUNT,
                    coerceDp = ModulePreferences::coerceZoomMaxWindowCount,
                    onPreviewChange = {},
                    onCommit = onZoomMaxWindowCountChange,
                    showDpUnit = false,
                )
                SwitchPreference(
                    checked = state.settings.zoomAllowAnyApp,
                    onCheckedChange = onZoomAllowAnyAppChange,
                    title = stringResource(R.string.zoom_allow_any_app_title),
                    summary = stringResource(R.string.zoom_allow_any_app_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.LockOpen, sub) },
                )
                SwitchPreference(
                    checked = state.settings.autoCloseWindow,
                    onCheckedChange = onAutoCloseWindowChange,
                    title = stringResource(R.string.auto_close_window_title),
                    summary = stringResource(R.string.auto_close_window_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.CropFree, sub) },
                )
                ArrowPreference(
                    title = stringResource(R.string.zoom_excluded_title),
                    summary = stringResource(R.string.zoom_excluded_summary),
                    onClick = onNavigateToZoomExcluded,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.CropFree, sub) },
                )
            }
        }

        item(key = "gesture") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = stringResource(R.string.zoom_group_gesture_title),
                    hint = stringResource(R.string.zoom_group_gesture_hint),
                    enabled = enabled,
                    icon = Icons.Rounded.TouchApp,
                )
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
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.TouchApp, enabled) },
                    onSelectedIndexChange = { index ->
                        outsideTapModes.getOrNull(index)?.let(onOutsideTapCloseModeChange)
                    },
                )
            }
        }
    }
}

/** 分组标题：说明这一组管什么，以及当前是否被总闸挡住。 */
@Composable
private fun SectionHeader(
    title: String,
    hint: String?,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            PreferenceIcon(icon, enabled)
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color =
                    if (enabled) MiuixTheme.colorScheme.onBackground
                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 30.dp, top = 2.dp),
            )
        }
    }
}
