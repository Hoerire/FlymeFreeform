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
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 排除列表：这些应用在前台时，角落手势不会呼出扇形菜单。
 * 行样式与「扇形应用」页里的列表保持一致。
 */
@Composable
internal fun BlacklistScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onAddApps: () -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
) {
    val blacklist = state.settings.blacklistPackages
    val enabled = state.settingsEditable
    val byPackage = remember(apps) {
        apps.filter { it.target is PinnedTarget.App }.associateBy { it.packageName }
    }
    ScreenScaffold(
        title = stringResource(R.string.blacklist_screen_title),
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
                        title = stringResource(R.string.blacklist_add_action),
                        summary = stringResource(R.string.blacklist_add_summary),
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
                                title = stringResource(R.string.blacklist_empty_title),
                                summary = stringResource(R.string.blacklist_empty_summary),
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
                                                if (enabled) onBlacklistChange(blacklist - pkg)
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

/** 排除列表的应用选择页：与「添加快捷项」一致的行样式，只是独立成页。 */
@Composable
internal fun BlacklistPickerScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    onBack: () -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
) {
    val blacklist = state.settings.blacklistPackages
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
        title = stringResource(R.string.blacklist_add_action),
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
                                                text = stringResource(R.string.blacklist_added),
                                                style = MiuixTheme.textStyles.body2,
                                                color = MiuixTheme.colorScheme.primary,
                                            )
                                        }
                                    },
                                    onClick = {
                                        if (!enabled) return@BasicComponent
                                        onBlacklistChange(
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