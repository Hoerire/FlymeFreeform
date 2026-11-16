package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SwipeUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.SectionTitle
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference

/**
 * 小窗交互。
 *
 * 排版遵循系统设置页的既有节奏：**分组标题在卡片外侧**，卡片内部只放原生偏好项。
 * 不往分组里塞自定义标题行——那会破坏卡片的内边距与分隔线节奏。
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
    onNavigateToZoomExcluded: () -> Unit,
    onNavigateToZoomLaunchScenes: () -> Unit,
    onNavigateToGesture: () -> Unit,
    onNavigateToEdgeFloat: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    val sub = enabled
    SettingsPage(
        title = stringResource(R.string.zoom_limit_removed_title),
        onBack = onBack,
    ) {
        item(key = "window_title") {
            SectionTitle(text = stringResource(R.string.zoom_group_window_title))
        }
        item(key = "window") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
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
                // 手势交互与贴边浮标各自收成一行入口，具体开关在二级页里；
                // summary 只列条目名，不复述二级页的说明文字。
                ArrowPreference(
                    title = stringResource(R.string.zoom_group_gesture_title),
                    summary = stringResource(R.string.zoom_gesture_entry_summary),
                    onClick = onNavigateToGesture,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeUp, sub) },
                )
                ArrowPreference(
                    title = stringResource(R.string.zoom_group_edge_title),
                    summary = stringResource(R.string.zoom_edge_entry_summary),
                    onClick = onNavigateToEdgeFloat,
                    enabled = sub,
                    startAction = { PreferenceIcon(Icons.Rounded.PushPin, sub) },
                )
            }
        }

    }
}
