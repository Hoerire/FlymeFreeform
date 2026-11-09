package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.apps.SelectableEntry
import io.github.hoerire.flymefreeform.config.PinnedTarget
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import io.github.hoerire.flymefreeform.ui.component.TopBarBackdrop
import io.github.hoerire.flymefreeform.ui.component.rememberTopBarBackdrop
import io.github.hoerire.flymefreeform.ui.component.topBarContainerColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 包名列表页：排除列表与「禁用浮窗」共用这一套骨架，只有文案与数据源不同。
 *
 * 行样式与「扇形应用」页里的列表保持一致；数据由调用方按各自语义取。
 */
@Composable
private fun PackageListScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    packages: List<String>,
    onChange: (List<String>) -> Unit,
    title: String,
    addAction: String,
    addSummary: String,
    emptyTitle: String,
    emptySummary: String,
) {
    val blacklist = packages
    val enabled = state.settingsEditable
    val byPackage = remember(apps) {
        apps.filter { it.target is PinnedTarget.App }.associateBy { it.packageName }
    }
    ScreenScaffold(
        title = title,
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalMargin,
                    top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                    end = ScreenHorizontalMargin,
                    bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                ),
        ) {
            item(key = "add") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = addAction,
                        summary = addSummary,
                        enabled = enabled,
                        onClick = onAddApps,
                    )
                }
            }
            item(key = "list") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column {
                        if (blacklist.isEmpty()) {
                            BasicComponent(
                                title = emptyTitle,
                                summary = emptySummary,
                                enabled = false,
                            )
                        } else {
                            blacklist.forEach { pkg ->
                                val entry = byPackage[pkg]
                                BasicComponent(
                                    title = entry?.label ?: pkg,
                                    summary = pkg,
                                    startAction = { EntryIcon(entry) },
                                    endActions = {
                                        IconButton(
                                            onClick = {
                                                if (enabled) onChange(blacklist - pkg)
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Remove,
                                                contentDescription =
                                                    stringResource(R.string.blacklist_remove_action),
                                                modifier = Modifier.size(20.dp),
                                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 排除列表：这些应用在前台时，角落手势不会呼出扇形菜单。 */
@Composable
internal fun BlacklistScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
) {
    PackageListScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        onAddApps = onAddApps,
        packages = state.settings.blacklistPackages,
        onChange = onBlacklistChange,
        title = stringResource(R.string.blacklist_screen_title),
        addAction = stringResource(R.string.blacklist_add_action),
        addSummary = stringResource(R.string.blacklist_add_summary),
        emptyTitle = stringResource(R.string.blacklist_empty_title),
        emptySummary = stringResource(R.string.blacklist_empty_summary),
    )
}

/**
 * 「禁用浮窗」：这些应用不会被以任何形式的自由浮窗打开。
 *
 * 与黑名单共用列表骨架，但数据独立——黑名单暂停的是模块增强，这份只关启动改写。
 */
@Composable
internal fun ZoomExcludedScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    onZoomExcludedChange: (List<String>) -> Unit,
) {
    PackageListScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        onAddApps = onAddApps,
        packages = state.settings.zoomExcludedPackages,
        onChange = onZoomExcludedChange,
        title = stringResource(R.string.zoom_excluded_screen_title),
        addAction = stringResource(R.string.zoom_excluded_add_action),
        addSummary = stringResource(R.string.zoom_excluded_add_summary),
        emptyTitle = stringResource(R.string.zoom_excluded_empty_title),
        emptySummary = stringResource(R.string.zoom_excluded_empty_summary),
    )
}

/** 应用选择页：排除列表与「禁用浮窗」共用。 */
@Composable
private fun PackagePickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    packages: List<String>,
    onChange: (List<String>) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    title: String,
    addedLabel: String,
) {
    val blacklist = packages
    val enabled = state.settingsEditable
    var query by remember { mutableStateOf("") }
    val appEntries = remember(apps) { apps.filter { it.target is PinnedTarget.App } }
    val candidates =
        remember(appEntries, query) {
            if (query.isBlank()) {
                appEntries
            } else {
                appEntries.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
            }
        }
    ScreenScaffold(
        title = title,
        onBack = onBack,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalMargin,
                    top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                    end = ScreenHorizontalMargin,
                    bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                ),
        ) {
            item(key = "search") {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.search_apps_placeholder),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
            }
            item(key = "system") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    SwitchPreference(
                        checked = state.settings.showSystemApps,
                        onCheckedChange = onShowSystemAppsChange,
                        title = stringResource(R.string.show_system_apps_title),
                        summary = stringResource(R.string.show_system_apps_summary),
                        enabled = state.settingsEditable,
                        startAction = { PreferenceIcon(Icons.Rounded.Widgets, enabled) },
                    )
                }
            }
            item(key = "list") {
                Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column {
                        if (candidates.isEmpty()) {
                            BasicComponent(
                                title = stringResource(R.string.no_matching_apps),
                                enabled = false,
                            )
                        } else {
                            candidates.forEach { entry ->
                                val added = entry.packageName in blacklist
                                BasicComponent(
                                    title = entry.label,
                                    summary = entry.packageName,
                                    startAction = { EntryIcon(entry) },
                                    endActions = {
                                        if (added) {
                                            Text(
                                                text = addedLabel,
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                    onClick = {
                                        if (!enabled) return@BasicComponent
                                        onChange(
                                            if (added) {
                                                blacklist - entry.packageName
                                            } else {
                                                blacklist + entry.packageName
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 排除列表的应用选择页。 */
@Composable
internal fun BlacklistPickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
) {
    PackagePickerScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        packages = state.settings.blacklistPackages,
        onChange = onBlacklistChange,
        onShowSystemAppsChange = onShowSystemAppsChange,
        title = stringResource(R.string.blacklist_add_action),
        addedLabel = stringResource(R.string.blacklist_added),
    )
}

/** 「禁用浮窗」的应用选择页。 */
@Composable
internal fun ZoomExcludedPickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onZoomExcludedChange: (List<String>) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
) {
    PackagePickerScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        packages = state.settings.zoomExcludedPackages,
        onChange = onZoomExcludedChange,
        onShowSystemAppsChange = onShowSystemAppsChange,
        title = stringResource(R.string.zoom_excluded_add_action),
        addedLabel = stringResource(R.string.blacklist_added),
    )
}

@Composable
internal fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= WideWindowMinWidth
        val navigationIcon: @Composable () -> Unit = { BackButton(onBack) }
        Scaffold(
            contentWindowInsets =
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .union(WindowInsets.ime),
            topBar = {
                TopBarBackdrop(backdrop) {
                    if (isWide) {
                        SmallTopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    } else {
                        TopAppBar(
                            title = title,
                            color = topBarColor,
                            navigationIcon = navigationIcon,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            },
            content = content,
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back_action),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun EntryIcon(entry: SelectableEntry?) {
    if (entry == null) return
    Image(
        bitmap = entry.icon.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp).size(32.dp),
    )
}