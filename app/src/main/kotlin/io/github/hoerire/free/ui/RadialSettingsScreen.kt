package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Adjust
import androidx.compose.material.icons.rounded.Apps
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
import io.github.hoerire.free.ui.component.PreferenceIcon

/**
 * 扇形相关的二级页面。
 *
 * 只放「怎么呼出、呼出后怎么用」：触发位置、保持挂起、应用入口、触发范围与各页入口。
 * 尺寸 / 时长 / 背景这类外观参数集中在「外观细节」页，「选中特效」也从那里进——
 * 这里不再重复列，避免同一组参数出现在两个层级。
 *
 * 拖动触发范围的滑条时，通过 [CornerRangePreview] 叠加显示当前取值，只在拖动期间存在。
 */
@Composable
internal fun RadialSettingsScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,

    onRadialInteractionEnabledChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onPauseInKeyboardChange: (Boolean) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
    onNavigateToRadialSize: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    // 「启用交互」关掉时整页其余项一起灰掉：参数都还在，重新打开就能继续调。
    val rowEnabled = enabled && state.settings.radialInteractionEnabled
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
        // 整页只有一个卡片：九项都是同一层级的开关或入口，再分四段会让页面被标题切成碎片。
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
                    enabled = rowEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.PushPin, rowEnabled) },
                )
                ArrowPreference(
                    title = stringResource(R.string.radial_apps_title),
                    summary = appsSummary,
                    onClick = onNavigateToPinnedApps,
                    enabled = rowEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Apps, rowEnabled) },
                )
                ListChoicePreference(
                    title = stringResource(R.string.corner_trigger_side_title),
                    icon = { PreferenceIcon(Icons.Rounded.SwipeRight, rowEnabled) },
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
                    enabled = rowEnabled,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Straighten,
                    confirmedValue = state.settings.cornerTriggerRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = rowEnabled,
                    title = stringResource(R.string.corner_trigger_range_title),
                    summary = stringResource(R.string.corner_trigger_range_summary),
                    minDp = ModulePreferences.MIN_CORNER_TRIGGER_RANGE_DP,
                    maxDp = ModulePreferences.MAX_CORNER_TRIGGER_RANGE_DP,
                    coerceDp = ModulePreferences::coerceCornerTriggerRangeDp,
                    onPreviewChange = { cornerRangePreviewDp = it },
                    onCommit = onCornerTriggerRangeChange,
                )
                // 命中范围是判定参数，与触发范围是同一类：两者一起决定"能不能碰到、碰到了算不算"。
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Adjust,
                    confirmedValue = state.settings.radialHitRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = rowEnabled,
                    title = stringResource(R.string.radial_hit_range_title),
                    summary = stringResource(R.string.radial_hit_range_summary),
                    minDp = ModulePreferences.MIN_RADIAL_HIT_RANGE_DP,
                    maxDp = ModulePreferences.MAX_RADIAL_HIT_RANGE_DP,
                    coerceDp = ModulePreferences::coerceRadialHitRangeDp,
                    // 没有屏幕叠加预览：命中圈的可视化在外观页顶的示意图里。
                    onPreviewChange = {},
                    onCommit = onRadialHitRangeChange,
                )
                ArrowPreference(
                    title = stringResource(R.string.radial_size_page_title),
                    summary = stringResource(R.string.radial_size_entry_summary),
                    onClick = onNavigateToRadialSize,
                    enabled = rowEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.CenterFocusStrong, rowEnabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInLandscape,
                    onCheckedChange = onPauseInLandscapeChange,
                    title = stringResource(R.string.pause_in_landscape_title),
                    summary = stringResource(R.string.pause_in_landscape_summary),
                    enabled = rowEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, rowEnabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInGameMode,
                    onCheckedChange = onPauseInGameModeChange,
                    title = stringResource(R.string.pause_in_game_mode_title),
                    summary = stringResource(R.string.pause_in_game_mode_summary),
                    enabled = rowEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, rowEnabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInKeyboard,
                    onCheckedChange = onPauseInKeyboardChange,
                    title = stringResource(R.string.pause_in_keyboard_title),
                    summary = stringResource(R.string.pause_in_keyboard_summary),
                    enabled = rowEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Keyboard, rowEnabled) },
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
