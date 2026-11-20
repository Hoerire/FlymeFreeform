package io.github.hoerire.free.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwipeRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.hoerire.free.R
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.bubble.CloneApp
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.ArrowPreference
import io.github.hoerire.free.ui.component.BasicComponent
import io.github.hoerire.free.ui.component.PreferenceIcon
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.hoerire.free.ui.component.SwitchPreference
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowBottomSheet

/** 气泡设置的写入口：页面只描述“下一个快照长什么样”。 */
internal typealias BubbleSettingsTransform = (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot

private enum class BubblePage {
    Overview,
    Apps,
    Appearance,
    Timing,
    Preview,
    StatusScroll,
    Suggestions,
}

/**
 * 气泡通知设置：总览页只放「总开关 + 分类入口」，具体开关进二级页。
 *
 * 这样一屏能看清有哪些能力，不会被十几个开关混在一起；每页只保留同一类语义的项。
 */
@Composable
internal fun BubbleScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    clones: List<CloneApp>,
    onRefreshClones: () -> Unit,
    onBack: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    var page by remember { mutableStateOf(BubblePage.Overview) }
    var pickerMode by remember { mutableIntStateOf(MODE_MAIN) }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    LifecycleResumeEffect(Unit) {
        onRefreshClones()
        onPauseOrDispose {}
    }
    BackHandler(enabled = page != BubblePage.Overview) { page = BubblePage.Overview }
    val goBack: () -> Unit = {
        if (page == BubblePage.Overview) onBack() else page = BubblePage.Overview
    }

    when (page) {
        BubblePage.Overview ->
            BubbleOverview(
                state = state,
                onBack = onBack,
                onNavigate = { page = it },
                onSettingsChange = onSettingsChange,
            )
        BubblePage.Apps ->
            BubbleAppsPage(
                state = state,
                apps = apps,
                clones = clones,
                onBack = goBack,
                onAddMain = {
                    pickerMode = MODE_MAIN
                    pickerQuery = ""
                    pickerOpen = true
                },
                onAddClone = {
                    pickerMode = MODE_CLONE
                    pickerQuery = ""
                    pickerOpen = true
                    onRefreshClones()
                },
                onRefreshClones = onRefreshClones,
                onSettingsChange = onSettingsChange,
            )
        BubblePage.Appearance ->
            BubbleAppearancePage(state, goBack, onSettingsChange)
        BubblePage.Timing ->
            BubbleTimingPage(state, goBack, onSettingsChange)
        BubblePage.Preview ->
            BubblePreviewPage(state, goBack, onSettingsChange)
        BubblePage.StatusScroll ->
            BubbleStatusScrollPage(state, goBack, onSettingsChange)
        BubblePage.Suggestions ->
            BubbleSuggestionsPage(state, goBack, onSettingsChange)
    }

    if (pickerOpen) {
        BubblePickerSheet(
            mode = pickerMode,
            apps = apps,
            clones = clones,
            selected =
                if (pickerMode == MODE_MAIN) {
                    state.settings.bubbleWhitelist
                } else {
                    state.settings.bubbleCloneWhitelist
                },
            query = pickerQuery,
            onQueryChange = { pickerQuery = it },
            onToggle = { key ->
                when (pickerMode) {
                    MODE_MAIN ->
                        onSettingsChange { snapshot ->
                            val next =
                                if (key in snapshot.bubbleWhitelist) {
                                    snapshot.bubbleWhitelist - key
                                } else {
                                    snapshot.bubbleWhitelist + key
                                }
                            snapshot.copy(
                                bubbleWhitelist =
                                    ModulePreferences.decodeBubbleKeys(
                                        ModulePreferences.encodeBubbleKeys(next),
                                    ),
                            )
                        }
                    else ->
                        onSettingsChange { snapshot ->
                            val next =
                                if (key in snapshot.bubbleCloneWhitelist) {
                                    snapshot.bubbleCloneWhitelist - key
                                } else {
                                    snapshot.bubbleCloneWhitelist + key
                                }
                            snapshot.copy(
                                bubbleCloneWhitelist =
                                    ModulePreferences.decodeBubbleKeys(
                                        ModulePreferences.encodeBubbleKeys(next),
                                    ),
                            )
                        }
                }
            },
            onRefreshClones = onRefreshClones,
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun BubbleOverview(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onNavigate: (BubblePage) -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    SettingsPage(title = stringResource(R.string.bubble_screen_title), onBack = onBack) {
        item(key = "intro") {
            Text(
                text = stringResource(R.string.bubble_screen_subtitle),
                modifier = Modifier.padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
        item(key = "master") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.bubbleEnabled,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleEnabled = value) }
                    },
                    title = stringResource(R.string.bubble_enabled_title),
                    summary = stringResource(R.string.bubble_enabled_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.bubblePreviewSuppressBanner,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubblePreviewSuppressBanner = value) }
                    },
                    title = stringResource(R.string.bubble_suppress_banner_title),
                    summary = stringResource(R.string.bubble_suppress_banner_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, bubbleEditable) },
                )
            }
        }
        item(key = "categories") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.bubble_group_apps),
                    summary =
                        stringResource(
                            R.string.bubble_apps_summary,
                            settings.bubbleWhitelist.size,
                            settings.bubbleCloneWhitelist.size,
                        ),
                    onClick = { onNavigate(BubblePage.Apps) },
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Widgets, bubbleEditable) },
                )
                ArrowPreference(
                    title = stringResource(R.string.bubble_group_appearance),
                    summary =
                        stringResource(
                            R.string.bubble_appearance_summary,
                            settings.bubbleSizePercent,
                            settings.bubbleIconPercent,
                        ),
                    onClick = { onNavigate(BubblePage.Appearance) },
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Layers, bubbleEditable) },
                )
                ArrowPreference(
                    title = stringResource(R.string.bubble_group_timing),
                    summary = timingSummary(settings),
                    onClick = { onNavigate(BubblePage.Timing) },
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, bubbleEditable) },
                )
                ArrowPreference(
                    title = stringResource(R.string.bubble_group_preview),
                    summary = previewSummary(settings),
                    onClick = { onNavigate(BubblePage.Preview) },
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.ContentCopy, bubbleEditable) },
                )
                ArrowPreference(
                    title = stringResource(R.string.bubble_group_status_scroll),
                    summary =
                        if (settings.statusScrollActive) {
                            stringResource(
                                R.string.bubble_status_scroll_summary_on,
                                settings.statusScrollRounds,
                            )
                        } else {
                            stringResource(R.string.bubble_status_scroll_summary_off)
                        },
                    onClick = { onNavigate(BubblePage.StatusScroll) },
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, bubbleEditable) },
                )
                ArrowPreference(
                    title = stringResource(R.string.bubble_group_suggestions),
                    summary =
                        if (settings.suggestionsActive) {
                            stringResource(R.string.bubble_suggestions_summary_on)
                        } else {
                            stringResource(R.string.bubble_suggestions_summary_off)
                        },
                    onClick = { onNavigate(BubblePage.Suggestions) },
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Lightbulb, bubbleEditable) },
                )
            }
        }
    }
}

@Composable
private fun timingSummary(settings: ModuleSettingsSnapshot): String =
    if (settings.bubbleAutoDismiss) {
        stringResource(
            R.string.bubble_timing_summary_seconds,
            settings.bubbleTimeoutSeconds,
            stringResource(
                if (settings.bubbleDimOnTimeout) {
                    R.string.bubble_timeout_dim_kept
                } else {
                    R.string.bubble_timeout_dock
                },
            ),
        )
    } else {
        stringResource(R.string.bubble_timing_summary_off)
    }

@Composable
private fun previewSummary(settings: ModuleSettingsSnapshot): String {
    if (!settings.bubblePreviewEnabled) return stringResource(R.string.bubble_preview_summary_off)
    val edge =
        stringResource(
            if (settings.bubblePreviewEdgeOnly) {
                R.string.bubble_preview_edge_on
            } else {
                R.string.bubble_preview_edge_off
            },
        )
    val duration =
        if (settings.bubblePreviewDynamicTimeout) {
            stringResource(R.string.bubble_preview_dynamic_on)
        } else {
            stringResource(
                R.string.bubble_preview_dynamic_off,
                settings.bubblePreviewTimeoutSeconds,
            )
        }
    return stringResource(R.string.bubble_preview_summary_on, edge, duration)
}

@Composable
private fun BubbleAppsPage(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    clones: List<CloneApp>,
    onBack: () -> Unit,
    onAddMain: () -> Unit,
    onAddClone: () -> Unit,
    onRefreshClones: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    val appByPackage = remember(apps) { apps.associateBy(SelectableEntry::packageName) }
    val cloneByKey = remember(clones) { clones.associateBy(CloneApp::key) }
    val cloneIndexByKey =
        remember(clones) {
            clones
                .groupBy(CloneApp::packageName)
                .flatMap { (_, group) ->
                    group.sortedBy(CloneApp::userId).mapIndexed { index, clone ->
                        clone.key to (index + 1)
                    }
                }
                .toMap()
        }
    LaunchedEffect(Unit) { onRefreshClones() }
    SettingsPage(title = stringResource(R.string.bubble_group_apps), onBack = onBack) {
        item(key = "apps_intro") {
            Text(
                text = stringResource(R.string.bubble_apps_title, settings.bubbleWhitelist.size),
                modifier =
                    Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { heading() },
                color = COUITheme.colorScheme.labelSecondary,
                style = COUITheme.textStyles.bodyS,
            )
        }
        item(key = "apps") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                settings.bubbleWhitelist.forEach { packageName ->
                    val app = appByPackage[packageName]
                    BubbleAddedRow(
                        title = app?.label ?: packageName,
                        summary = packageName,
                        icon = app?.icon,
                        cloneNumber = 0,
                        enabled = bubbleEditable,
                        onRemove = {
                            onSettingsChange {
                                it.copy(bubbleWhitelist = settings.bubbleWhitelist - packageName)
                            }
                        },
                    )
                }
                if (settings.bubbleWhitelist.isEmpty()) {
                    BasicComponent(
                        title = stringResource(R.string.bubble_apps_empty),
                        summary = stringResource(R.string.bubble_apps_empty_summary),
                        enabled = false,
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.bubble_add_app),
                    startAction = { PreferenceIcon(Icons.Rounded.Add, bubbleEditable) },
                    onClick = onAddMain,
                    enabled = bubbleEditable,
                )
            }
        }
        item(key = "clones_intro") {
            Text(
                text =
                    stringResource(R.string.bubble_clones_title, settings.bubbleCloneWhitelist.size),
                modifier =
                    Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { heading() },
                color = COUITheme.colorScheme.labelSecondary,
                style = COUITheme.textStyles.bodyS,
            )
        }
        item(key = "clones") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                settings.bubbleCloneWhitelist.forEach { key ->
                    val clone = cloneByKey[key]
                    BubbleAddedRow(
                        title =
                            clone?.label?.let { label ->
                                stringResource(
                                    R.string.bubble_clone_label,
                                    label,
                                    cloneIndexByKey[key] ?: 1,
                                )
                            } ?: key,
                        summary = key,
                        icon = clone?.icon,
                        cloneNumber = cloneIndexByKey[key] ?: 0,
                        enabled = bubbleEditable,
                        onRemove = {
                            onSettingsChange {
                                it.copy(
                                    bubbleCloneWhitelist = settings.bubbleCloneWhitelist - key,
                                )
                            }
                        },
                    )
                }
                if (settings.bubbleCloneWhitelist.isEmpty()) {
                    BasicComponent(
                        title = stringResource(R.string.bubble_clones_empty),
                        summary = stringResource(R.string.bubble_clones_empty_summary),
                        enabled = false,
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.bubble_add_clone),
                    summary =
                        if (clones.isEmpty()) {
                            stringResource(R.string.bubble_clones_empty_summary)
                        } else {
                            stringResource(R.string.bubble_clones_available, clones.size)
                        },
                    startAction = { PreferenceIcon(Icons.Rounded.ContentCopy, bubbleEditable) },
                    onClick = onAddClone,
                    enabled = bubbleEditable,
                )
            }
        }
        item(key = "skip_intro") {
            Text(
                text = stringResource(R.string.bubble_skip_title),
                modifier =
                    Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                        .semantics { heading() },
                color = COUITheme.colorScheme.labelSecondary,
                style = COUITheme.textStyles.bodyS,
            )
        }
        item(key = "skip") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.bubbleSkipForeground,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleSkipForeground = value) }
                    },
                    title = stringResource(R.string.bubble_skip_foreground_title),
                    summary = stringResource(R.string.bubble_skip_foreground_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.DoNotDisturbOn, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.bubbleSkipGame,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleSkipGame = value) }
                    },
                    title = stringResource(R.string.bubble_skip_game_title),
                    summary = stringResource(R.string.bubble_skip_game_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.bubbleSkipLocked,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleSkipLocked = value) }
                    },
                    title = stringResource(R.string.bubble_skip_locked_title),
                    summary = stringResource(R.string.bubble_skip_locked_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Lock, bubbleEditable) },
                )
            }
        }
    }
}

@Composable
private fun BubbleAppearancePage(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    SettingsPage(title = stringResource(R.string.bubble_group_appearance), onBack = onBack) {
        item(key = "appearance") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Layers,
                    confirmedValue = settings.bubbleSizePercent,
                    isUpdating = state.isUpdating,
                    enabled = bubbleEditable,
                    title = stringResource(R.string.bubble_size_title),
                    summary = stringResource(R.string.bubble_size_summary),
                    minDp = ModulePreferences.MIN_BUBBLE_SIZE_PERCENT,
                    maxDp = ModulePreferences.MAX_BUBBLE_SIZE_PERCENT,
                    coerceDp = ModulePreferences::coerceBubbleSizePercent,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                bubbleSizePercent =
                                    ModulePreferences.coerceBubbleSizePercent(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Notifications,
                    confirmedValue = settings.bubbleIconPercent,
                    isUpdating = state.isUpdating,
                    enabled = bubbleEditable,
                    title = stringResource(R.string.bubble_icon_title),
                    summary = stringResource(R.string.bubble_icon_summary),
                    minDp = ModulePreferences.MIN_BUBBLE_ICON_PERCENT,
                    maxDp = ModulePreferences.MAX_BUBBLE_ICON_PERCENT,
                    coerceDp = ModulePreferences::coerceBubbleIconPercent,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                bubbleIconPercent =
                                    ModulePreferences.coerceBubbleIconPercent(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.ContentCopy,
                    confirmedValue = settings.bubbleBadgePercent,
                    isUpdating = state.isUpdating,
                    enabled = bubbleEditable,
                    title = stringResource(R.string.bubble_badge_title),
                    summary = stringResource(R.string.bubble_badge_summary),
                    minDp = ModulePreferences.MIN_BUBBLE_BADGE_PERCENT,
                    maxDp = ModulePreferences.MAX_BUBBLE_BADGE_PERCENT,
                    coerceDp = ModulePreferences::coerceBubbleBadgePercent,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                bubbleBadgePercent =
                                    ModulePreferences.coerceBubbleBadgePercent(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
                SwitchPreference(
                    checked = settings.bubbleMotionContinuous,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleMotionContinuous = value) }
                    },
                    title = stringResource(R.string.bubble_motion_title),
                    summary = stringResource(R.string.bubble_motion_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Tune, bubbleEditable) },
                )
            }
        }
    }
}

@Composable
private fun BubbleTimingPage(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    SettingsPage(title = stringResource(R.string.bubble_group_timing), onBack = onBack) {
        item(key = "timing") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.bubbleAutoDismiss,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleAutoDismiss = value) }
                    },
                    title = stringResource(R.string.bubble_auto_dismiss_title),
                    summary = stringResource(R.string.bubble_auto_dismiss_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, bubbleEditable) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Tune,
                    confirmedValue = settings.bubbleTimeoutSeconds,
                    isUpdating = state.isUpdating,
                    enabled = bubbleEditable && settings.bubbleAutoDismiss,
                    title = stringResource(R.string.bubble_timeout_title),
                    summary = stringResource(R.string.bubble_timeout_summary),
                    minDp = ModulePreferences.MIN_BUBBLE_TIMEOUT_SECONDS,
                    maxDp = ModulePreferences.MAX_BUBBLE_TIMEOUT_SECONDS,
                    coerceDp = ModulePreferences::coerceBubbleTimeoutSeconds,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                bubbleTimeoutSeconds =
                                    ModulePreferences.coerceBubbleTimeoutSeconds(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
                SwitchPreference(
                    checked = settings.bubbleDimOnTimeout,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubbleDimOnTimeout = value) }
                    },
                    title = stringResource(R.string.bubble_dim_title),
                    summary = stringResource(R.string.bubble_dim_summary),
                    enabled = bubbleEditable && settings.bubbleAutoDismiss,
                    startAction = { PreferenceIcon(Icons.Rounded.Layers, bubbleEditable) },
                )
            }
        }
    }
}

@Composable
private fun BubblePreviewPage(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    SettingsPage(title = stringResource(R.string.bubble_group_preview), onBack = onBack) {
        item(key = "preview") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.bubblePreviewEnabled,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubblePreviewEnabled = value) }
                    },
                    title = stringResource(R.string.bubble_preview_enabled_title),
                    summary = stringResource(R.string.bubble_preview_enabled_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Layers, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.bubblePreviewEdgeOnly,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubblePreviewEdgeOnly = value) }
                    },
                    title = stringResource(R.string.bubble_edge_only_title),
                    summary = stringResource(R.string.bubble_edge_only_summary),
                    enabled = bubbleEditable && settings.bubblePreviewEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.bubblePreviewDynamicTimeout,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(bubblePreviewDynamicTimeout = value) }
                    },
                    title = stringResource(R.string.bubble_preview_dynamic_title),
                    summary = stringResource(R.string.bubble_preview_dynamic_summary),
                    enabled = bubbleEditable && settings.bubblePreviewEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Tune, bubbleEditable) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Tune,
                    confirmedValue = settings.bubblePreviewTimeoutSeconds,
                    isUpdating = state.isUpdating,
                    enabled =
                        bubbleEditable && settings.bubblePreviewEnabled &&
                            !settings.bubblePreviewDynamicTimeout,
                    title = stringResource(R.string.bubble_preview_timeout_title),
                    summary = stringResource(R.string.bubble_preview_timeout_summary),
                    minDp = ModulePreferences.MIN_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
                    maxDp = ModulePreferences.MAX_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
                    coerceDp = ModulePreferences::coerceBubblePreviewTimeoutSeconds,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                bubblePreviewTimeoutSeconds =
                                    ModulePreferences.coerceBubblePreviewTimeoutSeconds(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Layers,
                    confirmedValue = settings.bubblePreviewAlpha,
                    isUpdating = state.isUpdating,
                    enabled = bubbleEditable && settings.bubblePreviewEnabled,
                    title = stringResource(R.string.bubble_preview_alpha_title),
                    summary = stringResource(R.string.bubble_preview_alpha_summary),
                    minDp = ModulePreferences.MIN_BUBBLE_PREVIEW_ALPHA,
                    maxDp = ModulePreferences.MAX_BUBBLE_PREVIEW_ALPHA,
                    coerceDp = ModulePreferences::coerceBubblePreviewAlpha,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                bubblePreviewAlpha =
                                    ModulePreferences.coerceBubblePreviewAlpha(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
            }
        }
    }
}

@Composable
private fun BubbleStatusScrollPage(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    SettingsPage(title = stringResource(R.string.bubble_group_status_scroll), onBack = onBack) {
        item(key = "status_scroll") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.statusScrollEnabled,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(statusScrollEnabled = value) }
                    },
                    title = stringResource(R.string.bubble_status_scroll_enabled_title),
                    summary = stringResource(R.string.bubble_status_scroll_enabled_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, bubbleEditable) },
                )
                RemoteDpSliderPreference(
                    icon = Icons.Rounded.Layers,
                    confirmedValue = settings.statusScrollRounds,
                    isUpdating = state.isUpdating,
                    enabled = bubbleEditable && settings.statusScrollEnabled,
                    title = stringResource(R.string.bubble_status_scroll_rounds_title),
                    summary = stringResource(R.string.bubble_status_scroll_rounds_summary),
                    minDp = ModulePreferences.MIN_STATUS_SCROLL_ROUNDS,
                    maxDp = ModulePreferences.MAX_STATUS_SCROLL_ROUNDS,
                    coerceDp = ModulePreferences::coerceStatusScrollRounds,
                    onPreviewChange = {},
                    onCommit = { value ->
                        onSettingsChange {
                            it.copy(
                                statusScrollRounds =
                                    ModulePreferences.coerceStatusScrollRounds(value),
                            )
                        }
                    },
                    showDpUnit = false,
                )
            }
        }
    }
}

@Composable
private fun BubbleSuggestionsPage(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onSettingsChange: (transform: BubbleSettingsTransform) -> Unit,
) {
    val settings = state.settings
    val bubbleEditable = state.settingsEditable && settings.enabled
    SettingsPage(title = stringResource(R.string.bubble_group_suggestions), onBack = onBack) {
        item(key = "suggestions") {
            SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.suggestionsEnabled,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionsEnabled = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_master_title),
                    summary = stringResource(R.string.bubble_suggestion_master_summary),
                    enabled = bubbleEditable,
                    startAction = { PreferenceIcon(Icons.Rounded.Lightbulb, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.suggestionFlashlight,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionFlashlight = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_flashlight_title),
                    summary = stringResource(R.string.bubble_suggestion_flashlight_summary),
                    enabled = bubbleEditable && settings.suggestionsEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Lightbulb, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.suggestionMusic,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionMusic = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_music_title),
                    summary = stringResource(R.string.bubble_suggestion_music_summary),
                    enabled = bubbleEditable && settings.suggestionsEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Notifications, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.suggestionAppInstall,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionAppInstall = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_app_install_title),
                    summary = stringResource(R.string.bubble_suggestion_app_install_summary),
                    enabled = bubbleEditable && settings.suggestionsEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Widgets, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.suggestionClipboard,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionClipboard = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_clipboard_title),
                    summary = stringResource(R.string.bubble_suggestion_clipboard_summary),
                    enabled = bubbleEditable && settings.suggestionsEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ContentCopy, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.suggestionKeepAwake,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionKeepAwake = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_keep_awake_title),
                    summary = stringResource(R.string.bubble_suggestion_keep_awake_summary),
                    enabled = bubbleEditable && settings.suggestionsEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.LightMode, bubbleEditable) },
                )
                SwitchPreference(
                    checked = settings.suggestionNoEdge,
                    onCheckedChange = { value ->
                        onSettingsChange { it.copy(suggestionNoEdge = value) }
                    },
                    title = stringResource(R.string.bubble_suggestion_no_edge_title),
                    summary = stringResource(R.string.bubble_suggestion_no_edge_summary),
                    enabled = bubbleEditable && settings.suggestionsEnabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SwipeRight, bubbleEditable) },
                )
            }
        }
    }
}

@Composable
private fun BubbleAddedRow(
    title: String,
    summary: String,
    icon: android.graphics.Bitmap?,
    cloneNumber: Int,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = { BubbleAppIcon(icon = icon, cloneNumber = cloneNumber) },
        endActions = {
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(COUITheme.colorScheme.error)
                        .clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Remove,
                    contentDescription = stringResource(R.string.bubble_remove_named, title),
                    modifier = Modifier.size(16.dp),
                    tint = Color.White,
                )
            }
        },
        enabled = enabled,
    )
}

@Composable
private fun BubbleAppIcon(icon: android.graphics.Bitmap?, cloneNumber: Int) {
    Box(modifier = Modifier.size(44.dp)) {
        if (icon != null) {
            Image(
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(COUITheme.colorScheme.backgroundElevated),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    color = COUITheme.colorScheme.labelSecondary,
                    style = COUITheme.textStyles.bodyM,
                )
            }
        }
        if (cloneNumber > 0) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(COUITheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = cloneNumber.toString(),
                    color = COUITheme.colorScheme.onPrimary,
                    style = COUITheme.textStyles.bodyS,
                )
            }
        }
    }
}

@Composable
private fun BubblePickerSheet(
    mode: Int,
    apps: List<SelectableEntry>,
    clones: List<CloneApp>,
    selected: List<String>,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onRefreshClones: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedSet = remember(selected) { selected.toSet() }
    val cloneIndexByKey =
        remember(clones) {
            clones
                .groupBy(CloneApp::packageName)
                .flatMap { (_, group) ->
                    group.sortedBy(CloneApp::userId).mapIndexed { index, clone ->
                        clone.key to (index + 1)
                    }
                }
                .toMap()
        }
    WindowBottomSheet(
        show = true,
        title =
            stringResource(
                if (mode == MODE_MAIN) R.string.bubble_add_app else R.string.bubble_add_clone,
            ),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp).size(20.dp),
                    tint = COUITheme.colorScheme.labelSecondary,
                )
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.search_apps_placeholder),
                    singleLine = true,
                )
            }
            if (mode == MODE_MAIN) {
                val candidates =
                    remember(apps, query) {
                        apps
                            .filter { it.packageName != "io.github.hoerire.free" }
                            .filter {
                                query.isBlank() ||
                                    it.label.contains(query, ignoreCase = true) ||
                                    it.packageName.contains(query, ignoreCase = true)
                            }
                            .sortedBy { it.label }
                    }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                ) {
                    items(candidates, key = { "pick:" + it.packageName }) { entry ->
                        val checked = entry.packageName in selectedSet
                        BasicComponent(
                            title = entry.label,
                            summary = entry.packageName,
                            startAction = {
                                Image(
                                    bitmap = entry.icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier =
                                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
                                )
                            },
                            endActions =
                                if (checked) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = COUITheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                            onClick = { onToggle(entry.packageName) },
                        )
                    }
                }
            } else {
                val candidates =
                    remember(clones, query) {
                        clones
                            .filter {
                                query.isBlank() ||
                                    it.label.contains(query, ignoreCase = true) ||
                                    it.packageName.contains(query, ignoreCase = true)
                            }
                            .sortedBy { it.label }
                    }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                ) {
                    if (candidates.isEmpty()) {
                        item(key = "clone-empty") {
                            BasicComponent(
                                title = stringResource(R.string.bubble_clones_none_detected),
                                summary = stringResource(R.string.bubble_clones_none_detected_summary),
                                onClick = onRefreshClones,
                            )
                        }
                    }
                    items(candidates, key = { "clone-pick:" + it.key }) { clone ->
                        val checked = clone.key in selectedSet
                        BasicComponent(
                            title =
                                stringResource(
                                    R.string.bubble_clone_label,
                                    clone.label,
                                    cloneIndexByKey[clone.key] ?: 1,
                                ),
                            summary = clone.key,
                            startAction = {
                                BubbleAppIcon(
                                    icon = clone.icon,
                                    cloneNumber = cloneIndexByKey[clone.key] ?: 1,
                                )
                            },
                            endActions =
                                if (checked) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = COUITheme.colorScheme.primary,
                                        )
                                    }
                                } else {
                                    null
                                },
                            onClick = { onToggle(clone.key) },
                        )
                    }
                }
            }
        }
    }
}

private const val MODE_MAIN = 0
private const val MODE_CLONE = 1
