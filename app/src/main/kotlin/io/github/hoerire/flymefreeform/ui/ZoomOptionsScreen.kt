package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.material.icons.rounded.TouchApp
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
 * 排版遵循系统设置页的既有节奏：**分组标题在卡片外侧**，卡片内部只放原生偏好项。
 * 不往 Card 里塞自定义标题行——那会破坏 miuix 卡片的内边距与分隔线节奏。
 *
 * 信息结构：
 * - 「轻量打开」是启动场景的二级入口，summary 显示已开启的场景数量；
 * - 其余窗口行为项各自独立，互不依赖；
 * - 「手势与关闭」独立成组。
 */
/** 已开启的启动场景数量，用于二级入口的 summary。 */
private fun enabledSceneCount(state: FrameworkConnectionState): Int {
    val settings = state.settings
    return listOf(
        settings.zoomLaunchNotice,
        settings.zoomLaunchHeadsUp,
        settings.zoomLaunchControlCenter,
        settings.zoomLaunchInterApp,
        settings.zoomLaunchInterAppResult,
        settings.zoomLaunchShareRelay,
        settings.zoomLaunchSystem,
        settings.zoomLaunchClipboard,
    ).count { it }
}

@Composable
internal fun ZoomOptionsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onFloatKeepAliveChange: (Boolean) -> Unit,
    onLongPressMuteChange: (Boolean) -> Unit,
    onNavigateToZoomExcluded: () -> Unit,
    onNavigateToZoomLaunchScenes: () -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val sub = enabled
    val outsideTapModes = OutsideTapCloseMode.entries
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
        item(key = "window_title") {
            SectionTitle(text = stringResource(R.string.zoom_group_window_title))
        }
        item(key = "window") {
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.zoom_interaction_title),
                    summary =
                        stringResource(
                            if (enabledSceneCount(state) > 0) {
                                R.string.zoom_launch_entry_on
                            } else {
                                R.string.zoom_launch_entry_off
                            },
                            enabledSceneCount(state),
                        ),
                    onClick = onNavigateToZoomLaunchScenes,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.OpenInNew, sub) },
                )
                ArrowPreference(
                    title = stringResource(R.string.zoom_excluded_title),
                    summary = stringResource(R.string.zoom_excluded_summary),
                    onClick = onNavigateToZoomExcluded,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, sub) },
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
                SwitchPreference(
                    checked = state.settings.longPressMuteEnabled,
                    onCheckedChange = onLongPressMuteChange,
                    title = stringResource(R.string.mini_long_press_title),
                    summary = stringResource(R.string.mini_long_press_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PictureInPicture, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.floatKeepAlive,
                    onCheckedChange = onFloatKeepAliveChange,
                    title = stringResource(R.string.float_keep_alive_title),
                    summary = stringResource(R.string.float_keep_alive_summary),
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.ElectricalServices, sub) },
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
internal fun SectionTitle(text: String) {
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
