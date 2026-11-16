package io.github.hoerire.free.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.hoerire.free.R
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.apps.SidebarCatalogState
import io.github.hoerire.free.apps.SidebarCatalogStatus
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.PinnedTargetCodec
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.ui.component.BasicComponent
import io.github.hoerire.free.ui.component.SettingsGroup
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioTextField

@Composable
internal fun PinnedAppsScreen(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onBack: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
) {
    val refreshCatalog by rememberUpdatedState(onRefreshCatalog)
    val cancelCatalog by rememberUpdatedState(onCancelCatalog)
    LifecycleResumeEffect(state.canChangeSettings) {
        if (state.canChangeSettings) refreshCatalog()
        onPauseOrDispose { cancelCatalog() }
    }
    val allEntries = remember(apps, sidebarCatalog.entries) { apps + sidebarCatalog.entries }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    // 拖动或增删后的本地顺序；远端配置回读同步前以它为准，避免列表闪回旧顺序
    var localOrder by remember { mutableStateOf<List<PinnedTarget>?>(null) }
    LaunchedEffect(state.canChangeSettings, state.issue) {
        if (!state.canChangeSettings || state.issue != null) localOrder = null
    }
    var draggingComponent by remember { mutableStateOf<PinnedTarget?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    val title = stringResource(R.string.radial_apps_screen_title)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val side = maxOf(ScreenHorizontalMargin, (maxWidth - ScreenContentMaxWidth) / 2)
        val pinned = state.settings.pinnedTargets
        LaunchedEffect(pinned) {
            if (localOrder != null && pinned == localOrder) localOrder = null
        }
        val displayed = localOrder ?: pinned
        val appByComponent = remember(allEntries) { allEntries.associateBy(SelectableEntry::target) }
        GlasmioPinnedListPage(
            title = title,
            subtitle = stringResource(R.string.radial_apps_screen_subtitle),
            onNavigateBack = onBack,
            horizontalContentPadding = side,
        ) {
            item(key = "radial_options") {
                SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.select_apps_action),
                        startAction = { PreferenceIcon(Icons.Rounded.Add, state.settingsEditable) },
                        onClick = {
                            pickerQuery = ""
                            pickerOpen = true
                        },
                        enabled = state.settingsEditable,
                    )
                }
            }
            item(key = "added_intro") {
                Text(
                    text =
                        stringResource(
                            R.string.added_apps_title,
                            displayed.size,
                            ModulePreferences.MAX_PINNED_APPS,
                        ),
                    modifier =
                        Modifier
                            .padding(start = 16.dp, bottom = 8.dp)
                            .semantics { heading() },
                    color = GlasmioTheme.colors.secondaryContent,
                    style = IosTypography.subheadline,
                )
            }
            item(key = "added") {
                SettingsGroup(modifier = Modifier.fillMaxWidth()) {
                    displayed.forEachIndexed { index, component ->
                        key(PinnedTargetCodec.encode(component)) {
                            AddedAppRow(
                                component = component,
                                app = appByComponent[component],
                                index = index,
                                enabled = state.settingsEditable,
                                draggable = state.settingsEditable && displayed.size > 1,
                                dragging = draggingComponent == component,
                                dragOffsetY = dragOffsetY,
                                rowHeightPx = rowHeightPx,
                                onRowHeight = { height ->
                                    if (rowHeightPx != height) rowHeightPx = height
                                },
                                onRemove = {
                                    val updated = displayed.filterNot { it == component }
                                    localOrder = updated
                                    onPinnedTargetsChange(updated)
                                },
                                onDragStart = {
                                    localOrder = displayed
                                    draggingComponent = component
                                    dragOffsetY = 0f
                                },
                                onDrag = { deltaY ->
                                    if (rowHeightPx > 0f) {
                                        dragOffsetY += deltaY
                                        val order =
                                            (localOrder ?: displayed).toMutableList()
                                        var current = order.indexOf(component)
                                        while (current >= 0 &&
                                            dragOffsetY > rowHeightPx / 2 &&
                                            current < order.size - 1
                                        ) {
                                            order.add(current + 1, order.removeAt(current))
                                            dragOffsetY -= rowHeightPx
                                            current++
                                        }
                                        while (current >= 0 &&
                                            dragOffsetY < -rowHeightPx / 2 &&
                                            current > 0
                                        ) {
                                            order.add(current - 1, order.removeAt(current))
                                            dragOffsetY += rowHeightPx
                                            current--
                                        }
                                        localOrder = order
                                    }
                                },
                                onDragEnd = {
                                    val order = localOrder
                                    draggingComponent = null
                                    dragOffsetY = 0f
                                    if (order != null) {
                                        if (order == pinned) {
                                            localOrder = null
                                        } else {
                                            onPinnedTargetsChange(order)
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (displayed.isEmpty()) {
                        BasicComponent(
                            title = stringResource(R.string.added_apps_empty),
                            summary = stringResource(R.string.added_apps_empty_summary),
                            enabled = false,
                        )
                    }
                }
            }
        }
        // 勾选状态跟随 displayed，sheet 内增删即时可见
        val addedSet = remember(displayed) { displayed.toSet() }
        val candidates =
            remember(allEntries, pickerQuery) {
                if (pickerQuery.isBlank()) {
                    allEntries
                } else {
                    allEntries.filter {
                        it.label.contains(pickerQuery, ignoreCase = true) ||
                            it.packageName.contains(pickerQuery, ignoreCase = true)
                    }
                }
            }
        val groups = remember(candidates) {
            listOf(
                R.string.entry_group_tools to candidates.filter { (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.Tool },
                R.string.entry_group_shortcuts to candidates.filter {
                    val kind = (it.target as? PinnedTarget.Sidebar)?.kind
                    kind == PinnedTarget.Kind.Shortcut || kind == PinnedTarget.Kind.AppEntry
                },
                R.string.entry_group_cloned_apps to candidates.filter {
                    (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp
                },
                // 只列可启动的应用：系统服务在豁免/黑名单里可选，但没有启动入口，不能进扇形。
            R.string.entry_group_apps to candidates.filter {
                (it.target as? PinnedTarget.App)?.className?.isNotEmpty() == true
            },
            ).filter { it.second.isNotEmpty() }
        }
        val sheetTitle = stringResource(R.string.select_apps_action)
        GlasmioBottomSheet(
            visible = pickerOpen,
            title = sheetTitle,
            onDismissRequest = { pickerOpen = false },
        ) {
            Text(
                text = sheetTitle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                style = IosTypography.title3,
                color = GlasmioTheme.colors.content,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp).size(20.dp),
                    tint = GlasmioTheme.colors.secondaryContent,
                )
                GlasmioTextField(
                    value = pickerQuery,
                    onValueChange = { pickerQuery = it },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.search_apps_placeholder),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp)) {
                if (sidebarCatalog.status != SidebarCatalogStatus.Ready) {
                    item(key = "sidebar_status") {
                        BasicComponent(
                            title = stringResource(if (sidebarCatalog.status == SidebarCatalogStatus.Loading) R.string.sidebar_catalog_loading else R.string.sidebar_catalog_unavailable),
                            summary = stringResource(if (sidebarCatalog.status == SidebarCatalogStatus.Loading) R.string.sidebar_catalog_loading_summary else R.string.sidebar_catalog_retry_summary),
                            onClick = onRefreshCatalog,
                            enabled = state.settingsEditable && sidebarCatalog.status != SidebarCatalogStatus.Loading,
                        )
                    }
                }
                when {
                    allEntries.isEmpty() ->
                        item(key = "apps_loading") {
                            BasicComponent(
                                title = stringResource(R.string.available_apps_loading),
                                summary = stringResource(R.string.available_apps_loading_summary),
                                enabled = false,
                            )
                        }
                    candidates.isEmpty() ->
                        item(key = "apps_no_match") {
                            BasicComponent(
                                title = stringResource(R.string.no_matching_apps),
                                enabled = false,
                            )
                        }
                    else -> groups.forEach { (groupTitle, entries) ->
                        item(key = "group_$groupTitle") {
                            Text(
                                text = stringResource(groupTitle),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
                                style = IosTypography.subheadline,
                                color = GlasmioTheme.colors.secondaryContent,
                            )
                        }
                        items(entries, key = { PinnedTargetCodec.encode(it.target) }) { app ->
                            val added = app.target in addedSet
                            BasicComponent(
                                title = app.label,
                                summary = app.packageName,
                                startAction = { AppIcon(app) },
                                endActions =
                                    if (added) {
                                        {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = GlasmioTheme.colors.accent,
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                onClick = {
                                    val updated =
                                        if (added) {
                                            displayed.filterNot { it == app.target }
                                        } else {
                                            (displayed + app.target)
                                                .take(ModulePreferences.MAX_PINNED_APPS)
                                        }
                                    localOrder = updated
                                    onPinnedTargetsChange(updated)
                                },
                                enabled =
                                    state.settingsEditable &&
                                        (added || displayed.size < ModulePreferences.MAX_PINNED_APPS) &&
                                        (added || app.target is PinnedTarget.App || sidebarCatalog.status == SidebarCatalogStatus.Ready),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddedAppRow(
    component: PinnedTarget,
    app: SelectableEntry?,
    index: Int,
    enabled: Boolean,
    draggable: Boolean,
    dragging: Boolean,
    dragOffsetY: Float,
    rowHeightPx: Float,
    onRowHeight: (Float) -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // 被拖行换位时，其余行先从旧视觉位置吸附再弹簧归位，形成实时让位动画
    val offsetAnim = remember { Animatable(0f) }
    var restIndex by remember { mutableIntStateOf(index) }
    LaunchedEffect(index, dragging) {
        if (dragging || index == restIndex) {
            restIndex = index
        } else {
            offsetAnim.snapTo(offsetAnim.value + (restIndex - index) * rowHeightPx)
            restIndex = index
            offsetAnim.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
        }
    }
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val dragModifier =
        if (draggable) {
            Modifier.pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { currentOnDragStart() },
                    onDragCancel = { currentOnDragEnd() },
                    onDragEnd = { currentOnDragEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDrag(amount.y)
                    },
                )
            }
        } else {
            Modifier
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .onSizeChanged { size -> onRowHeight(size.height.toFloat()) }
                .graphicsLayer { translationY = if (dragging) dragOffsetY else offsetAnim.value }
                .zIndex(if (dragging) 1f else 0f)
                .then(dragModifier),
    ) {
        BasicComponent(
            title = app?.label ?: component.displayIdentity(),
            summary = if (app == null) stringResource(R.string.pinned_app_missing) else null,
            startAction = {
                if (app != null) {
                    AppIcon(app)
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlasmioTheme.colors.container),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "?",
                            color = GlasmioTheme.colors.secondaryContent,
                            style = IosTypography.body,
                        )
                    }
                }
            },
            endActions = {
                Box(
                    modifier =
                        Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(GlasmioTheme.colors.destructive)
                            .clickable(enabled = enabled, onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription =
                            stringResource(
                                R.string.remove_named_app_action,
                                app?.label ?: component.displayIdentity(),
                            ),
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                }
            },
            enabled = enabled,
        )
    }
}

@Composable
private fun AppIcon(app: SelectableEntry) {
    Box(modifier = Modifier.size(44.dp)) {
        Image(
            bitmap = app.icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.matchParentSize().clip(RoundedCornerShape(12.dp)),
        )
        app.badge?.let { badge ->
            Image(
                bitmap = badge.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun PinnedTarget.displayIdentity(): String = when (this) {
    is PinnedTarget.App -> packageName
    is PinnedTarget.Sidebar -> alias
}
