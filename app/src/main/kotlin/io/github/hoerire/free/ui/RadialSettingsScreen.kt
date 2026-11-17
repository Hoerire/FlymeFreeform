package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.free.R
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.ListChoicePreference
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference

/**
 * 扇形相关的二级页面。
 *
 * 条目按作用分三段：呼出方式（触发位置）、呼出后的行为（保持挂起、应用入口）、
 * 判定数值（触发范围、命中范围、数量上限）。应用条目的增删与排序在「扇形应用」页内维护。
 *
 * 拖动触发范围与命中范围的滑条时，通过 [CornerRangePreview] 与 [HitRangePreview]
 * 叠加显示当前取值，两者只在拖动期间存在。
 */
@Composable
internal fun RadialSettingsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,

    onRadialInteractionEnabledChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onPauseInKeyboardChange: (Boolean) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    onNavigateToRadialEffect: () -> Unit,
    onNavigateToRadialSize: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    var cornerRangePreviewDp by remember { mutableStateOf<Int?>(null) }
    val left = state.settings.leftCornerEnabled
    val right = state.settings.rightCornerEnabled
    val sideIndex =
        when {
            left && !right -> 1
            !left && right -> 2
            else -> 0
        }
    val appsSummary =
        when {
            // 没有固定项时扇形里只有「更多」入口。
            state.settings.pinnedTargets.isEmpty() -> stringResource(R.string.radial_apps_empty_summary)
            else -> stringResource(R.string.radial_apps_count_summary, state.settings.pinnedTargets.size)
        }
    val sideItems =
        listOf(
            stringResource(R.string.corner_trigger_side_both),
            stringResource(R.string.corner_trigger_side_left),
            stringResource(R.string.corner_trigger_side_right),
        )

    // 预览层必须叠在页面之上：作为同级的普通子项参与布局时，
    // 拖动滑条让预览出现/消失会把整页顶一下，表现为「突然往下跑」。
    Box(modifier = Modifier.fillMaxSize()) {
    SettingsPage(
        title = stringResource(R.string.radial_settings_title),
        onBack = onBack,
    ) {
        // 整页只有一个卡片：八项都是同一层级的开关或入口，再分四段会让页面被标题切成碎片。
        item(key = "radialSettings") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                // 总开关放在最上面：不想要扇形菜单的直接关掉即可，下面的配置全部保留。
                SwitchPreference(
                    checked = state.settings.radialInteractionEnabled,
                    onCheckedChange = onRadialInteractionEnabledChange,
                    title = stringResource(R.string.radial_interaction_title),
                    summary = stringResource(R.string.radial_interaction_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.TouchApp, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.radialKeepOpen,
                    onCheckedChange = onRadialKeepOpenChange,
                    title = stringResource(R.string.radial_keep_open_title),
                    summary = stringResource(R.string.radial_keep_open_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PushPin, enabled) },
                )
                ArrowPreference(
                    title = stringResource(R.string.radial_apps_title),
                    summary = appsSummary,
                    onClick = onNavigateToPinnedApps,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Apps, enabled) },
                )
                ListChoicePreference(
                    title = stringResource(R.string.corner_trigger_side_title),
                    icon = { PreferenceIcon(Icons.Rounded.SwipeRight, enabled) },
                    selected = sideIndex,
                    values = sideItems.indices.toList(),
                    valueText = { sideItems[it] },
                    onSelected = { index ->
                        when (index) {
                            0 -> onCornerSidesChange(true, true)
                            1 -> onCornerSidesChange(true, false)
                            2 -> onCornerSidesChange(false, true)
                        }
                    },
                    enabled = enabled,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Straighten,
                    confirmedValue = state.settings.cornerTriggerRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.corner_trigger_range_title),
                    summary = stringResource(R.string.corner_trigger_range_summary),
                    minDp = ModulePreferences.MIN_CORNER_TRIGGER_RANGE_DP,
                    maxDp = ModulePreferences.MAX_CORNER_TRIGGER_RANGE_DP,
                    coerceDp = ModulePreferences::coerceCornerTriggerRangeDp,
                    onPreviewChange = { cornerRangePreviewDp = it },
                    onCommit = onCornerTriggerRangeChange,
                )
                ArrowPreference(
                    title = stringResource(R.string.radial_effect_group_title),
                    summary = stringResource(R.string.radial_effect_entry_summary),
                    onClick = onNavigateToRadialEffect,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.AutoAwesome, enabled) },
                )
                ArrowPreference(
                    title = stringResource(R.string.radial_size_page_title),
                    summary = stringResource(R.string.radial_size_entry_summary),
                    onClick = onNavigateToRadialSize,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.CenterFocusStrong, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInLandscape,
                    onCheckedChange = onPauseInLandscapeChange,
                    title = stringResource(R.string.pause_in_landscape_title),
                    summary = stringResource(R.string.pause_in_landscape_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInGameMode,
                    onCheckedChange = onPauseInGameModeChange,
                    title = stringResource(R.string.pause_in_game_mode_title),
                    summary = stringResource(R.string.pause_in_game_mode_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInKeyboard,
                    onCheckedChange = onPauseInKeyboardChange,
                    title = stringResource(R.string.pause_in_keyboard_title),
                    summary = stringResource(R.string.pause_in_keyboard_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Keyboard, enabled) },
                )
            }
        }
    }
    cornerRangePreviewDp?.let { rangeDp ->
        CornerRangePreview(
            rangeDp = rangeDp,
            leftEnabled = left,
            rightEnabled = right,
        )
    }
    }
}
