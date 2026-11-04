package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

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
    onRadialHitRangeChange: (Int) -> Unit,
    onRadialMaxItemsChange: (Int) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onNavigateToPinnedApps: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    var cornerRangePreviewDp by remember { mutableStateOf<Int?>(null) }
    var hitRangePreviewDp by remember { mutableStateOf<Int?>(null) }
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
            !state.settings.pinsSaved -> stringResource(R.string.radial_apps_recent_summary)
            state.settings.pinnedTargets.isEmpty() -> stringResource(R.string.radial_apps_empty_summary)
            else -> stringResource(R.string.radial_apps_count_summary, state.settings.pinnedTargets.size)
        }
    val sideItems =
        listOf(
            DropdownItem(text = stringResource(R.string.corner_trigger_side_both)),
            DropdownItem(text = stringResource(R.string.corner_trigger_side_left)),
            DropdownItem(text = stringResource(R.string.corner_trigger_side_right)),
        )
    SettingsPage(
        title = stringResource(R.string.radial_settings_title),
        onBack = onBack,
    ) {
        item(key = "trigger") {
            Card(modifier = Modifier.fillMaxWidth()) {
                OverlaySpinnerPreference(
                    items = sideItems,
                    selectedIndex = sideIndex,
                    title = stringResource(R.string.corner_trigger_side_title),
                    summary = stringResource(R.string.corner_trigger_side_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, enabled) },
                    onSelectedIndexChange = { index ->
                        when (index) {
                            0 -> onCornerSidesChange(true, true)
                            1 -> onCornerSidesChange(true, false)
                            2 -> onCornerSidesChange(false, true)
                        }
                    },
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
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.CenterFocusStrong,
                    confirmedValue = state.settings.radialHitRangeDp,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_hit_range_title),
                    summary = stringResource(R.string.radial_hit_range_summary),
                    minDp = ModulePreferences.MIN_RADIAL_HIT_RANGE_DP,
                    maxDp = ModulePreferences.MAX_RADIAL_HIT_RANGE_DP,
                    coerceDp = ModulePreferences::coerceRadialHitRangeDp,
                    onPreviewChange = { hitRangePreviewDp = it },
                    onCommit = onRadialHitRangeChange,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Apps,
                    confirmedValue = state.settings.radialMaxItems,
                    isUpdating = state.isUpdating,
                    enabled = enabled,
                    title = stringResource(R.string.radial_max_items_title),
                    summary = stringResource(R.string.radial_max_items_summary),
                    minDp = ModulePreferences.MIN_RADIAL_MAX_ITEMS,
                    maxDp = ModulePreferences.MAX_RADIAL_MAX_ITEMS,
                    coerceDp = ModulePreferences::coerceRadialMaxItems,
                    onPreviewChange = {},
                    onCommit = onRadialMaxItemsChange,
                    showDpUnit = false,
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
    hitRangePreviewDp?.let { rangeDp ->
        HitRangePreview(
            hitRangeDp = rangeDp,
            itemCount =
                if (state.settings.pinsSaved) {
                    state.settings.pinnedTargets.size + 1
                } else {
                    ModulePreferences.MAX_PINNED_APPS + 1
                },
            leftEnabled = left,
            rightEnabled = right,
        )
    }
}