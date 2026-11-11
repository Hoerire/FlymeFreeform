package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
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
 * 排版遵循系统设置页的既有节奏：**分组标题在卡片外侧**，卡片内部只放原生偏好项。
 * 不往 Card 里塞自定义标题行——那会破坏 miuix 卡片的内边距与分隔线节奏。
 *
 * 信息结构：
 * - 总闸「轻量打开」独占一张卡片，summary 直接描述当前状态；
 * - 「应用启动」「窗口行为」两组受总闸控制，被挡住时标题右侧标注原因；
 * - 「手势与关闭」独立，不随总闸走。
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
    // 通知横幅：屏幕 + 状态栏下方的一条横幅，自绘（Material 里没有对应图示）。
    val headsUpIcon = ImageVector.vectorResource(R.drawable.ic_heads_up_banner)
    val outsideTapItems =
        listOf(
            DropdownItem(text = stringResource(R.string.outside_tap_mode_disabled)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_single)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_double)),
            DropdownItem(text = stringResource(R.string.outside_tap_mode_bottom)),
        )
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

        item(key = "launch_title") {
            SectionTitle(text = stringResource(R.string.zoom_group_launch_title))
        }
        item(key = "launch") {
            Card(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = state.settings.zoomLaunchNotice,
                    onCheckedChange = onZoomLaunchNoticeChange,
                    title = stringResource(R.string.zoom_launch_notice_title),
                    summary = stringResource(R.string.zoom_launch_notice_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchHeadsUp,
                    onCheckedChange = onZoomLaunchHeadsUpChange,
                    title = stringResource(R.string.zoom_launch_heads_up_title),
                    summary = stringResource(R.string.zoom_launch_heads_up_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(headsUpIcon, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchControlCenter,
                    onCheckedChange = onZoomLaunchControlCenterChange,
                    title = stringResource(R.string.zoom_launch_control_center_title),
                    summary = stringResource(R.string.zoom_launch_control_center_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Tune, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLaunchInterApp,
                    onCheckedChange = onZoomLaunchInterAppChange,
                    title = stringResource(R.string.zoom_launch_inter_app_title),
                    summary = stringResource(R.string.zoom_launch_inter_app_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.OpenInNew, sub) },
                )
                SwitchPreference(
                    checked = state.settings.zoomLauncherShortcut,
                    onCheckedChange = onZoomLauncherShortcutChange,
                    title = stringResource(R.string.zoom_launcher_shortcut_title),
                    summary = stringResource(R.string.zoom_launcher_shortcut_summary),
                    enabled = sub,
                    // 快捷方式＝长按桌面图标后展开的那列操作项，用竖排三点直接表达那个菜单。
                    startAction = { PreferenceIcon(Icons.Rounded.MoreVert, sub) },
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

        item(key = "window_title") {
            SectionTitle(text = stringResource(R.string.zoom_group_window_title))
        }
        item(key = "window") {
            Card(modifier = Modifier.fillMaxWidth()) {
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Layers,
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
                    startAction = { PreferenceIcon(Icons.Rounded.DeleteSweep, sub) },
                )
                ArrowPreference(
                    title = stringResource(R.string.zoom_excluded_title),
                    summary = stringResource(R.string.zoom_excluded_summary),
                    onClick = onNavigateToZoomExcluded,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, sub) },
                )
            }
        }

        item(key = "gesture_title") {
            SectionTitle(text = stringResource(R.string.zoom_group_gesture_title))
        }
        item(key = "gesture") {
            Card(modifier = Modifier.fillMaxWidth()) {
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

/**
 * 分组标题：位于卡片外侧，副标题说明这一组当前是否可用。
 *
 * 字号取 body2、颜色取次级色，与系统设置页的 section header 一致；
 * 右侧的 note 用更弱的颜色，避免和标题抢注意力。
 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
    )
}
