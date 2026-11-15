package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.github.hoerire.flymefreeform.apps.SelectableEntry
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.config.PinnedTarget
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import io.github.hoerire.flymefreeform.update.UpdateState
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavDisplay
import io.github.neoruaa.glasmio.scaffold.navigation.GlasmioNavHeaderMotion
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object PinnedApps : AppRoute

    @Serializable
    data object Blacklist : AppRoute

    @Serializable
    data object BlacklistPicker : AppRoute

    @Serializable
    data object ZoomOptions : AppRoute

    @Serializable
    data object ZoomLaunchScenes : AppRoute

    @Serializable
    data object ZoomExcluded : AppRoute

    @Serializable
    data object ZoomExcludedPicker : AppRoute

    @Serializable
    data object RadialSettings : AppRoute

    @Serializable
    data object About : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    stateFlow: kotlinx.coroutines.flow.StateFlow<FrameworkConnectionState>,
    appsFlow: kotlinx.coroutines.flow.StateFlow<List<SelectableEntry>>,
    appsLoadingFlow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    sidebarCatalogFlow: kotlinx.coroutines.flow.StateFlow<io.github.hoerire.flymefreeform.apps.SidebarCatalogState>,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onRadialIconSizeChange: (Int) -> Unit,
    onBackdropLevelChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onFreeDragChange: (Boolean) -> Unit,
    onDragLongPressChange: (Int) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchNoticeChange: (Boolean) -> Unit,
    onZoomLaunchHeadsUpChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
    onZoomLaunchInterAppResultChange: (Boolean) -> Unit,
    onZoomLaunchShareRelayChange: (Boolean) -> Unit,
    onZoomLaunchClipboardChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onFloatKeepAliveChange: (Boolean) -> Unit,
    onLongPressMuteChange: (Boolean) -> Unit,
    onZoomExcludedChange: (List<String>) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
    updateFlow: kotlinx.coroutines.flow.StateFlow<UpdateState>,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: (String) -> Unit,
) {
    // 返回栈按类名保存：NavKey 不属于 Bundle 可存类型，直接交给 saveable 会在首帧抛异常。
    val backStack =
        rememberSaveable(saver = AppBackStackSaver) {
            mutableStateListOf<AppRoute>(AppRoute.Settings)
        }
    val motion = remember { GlasmioNavHeaderMotion(AppRoute.Settings) }

    val popBackStack = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
            Unit
        }
    }
    val navigateToPinnedApps = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.PinnedApps) {
                backStack.add(AppRoute.PinnedApps)
            }
        }
    }
    val navigateToBlacklist = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.Blacklist) {
                backStack.add(AppRoute.Blacklist)
            }
        }
    }
    val navigateToBlacklistPicker = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.BlacklistPicker) {
                backStack.add(AppRoute.BlacklistPicker)
            }
        }
    }
    val navigateToRadialSettings = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.RadialSettings) {
                backStack.add(AppRoute.RadialSettings)
            }
        }
    }
    val navigateToAbout = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.About) {
                backStack.add(AppRoute.About)
            }
        }
    }
    val navigateToZoomOptions = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.ZoomOptions) {
                backStack.add(AppRoute.ZoomOptions)
            }
        }
    }
    val navigateToZoomLaunchScenes = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.ZoomLaunchScenes) {
                backStack.add(AppRoute.ZoomLaunchScenes)
            }
        }
    }
    val navigateToZoomExcluded = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.ZoomExcluded) {
                backStack.add(AppRoute.ZoomExcluded)
            }
        }
    }
    val navigateToZoomExcludedPicker = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.ZoomExcludedPicker) {
                backStack.add(AppRoute.ZoomExcludedPicker)
            }
        }
    }

    GlasmioNavDisplay(
        backStack = backStack,
        motion = motion,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
        entryProvider = { route ->
            NavEntry(key = route, contentKey = route.javaClass.name) {
                // 在 entry 内部收集：导航层会复用 entry 内容，把已收集好的值当参数传进来
                // 会被闭包固定在首帧，设置页就永远显示初始的“等待连接”。
                val state = stateFlow.collectAsState().value
                // 同理：应用列表、目录、更新状态也必须在本页面内订阅，按值传进来会停在首帧。
                val apps = appsFlow.collectAsState().value
                val appsLoading = appsLoadingFlow.collectAsState().value
                val sidebarCatalog = sidebarCatalogFlow.collectAsState().value
                val update = updateFlow.collectAsState().value
                when (route) {
                    AppRoute.Settings -> {
                        ControlScreen(
                            state = state,
                            update = update,
                            onDismissUpdate = onDismissUpdate,
                            onModuleEnabledChange = onModuleEnabledChange,
                            onCornerSidesChange = onCornerSidesChange,
                            onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                            onRadialHitRangeChange = onRadialHitRangeChange,
                            onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                            onHandleSwipeUpToMiniEnabledChange =
                                onHandleSwipeUpToMiniEnabledChange,
                            onPauseInLandscapeChange = onPauseInLandscapeChange,
                            onPauseInGameModeChange = onPauseInGameModeChange,
                            onFreeDragChange = onFreeDragChange,
                            onDragLongPressChange = onDragLongPressChange,
                            onRequestScopes = onRequestScopes,
                                    onNavigateToPinnedApps = navigateToPinnedApps,
                            onNavigateToRadialSettings = navigateToRadialSettings,
                            onNavigateToBlacklist = navigateToBlacklist,
                            onNavigateToZoomOptions = navigateToZoomOptions,
                            onNavigateToAbout = navigateToAbout,
                        )
                    }
                    AppRoute.About -> {
                        AboutScreen(
                            update = update,
                            verboseLogEnabled = state.settings.verboseLogEnabled,
                            onBack = popBackStack,
                            onVerboseLogChange = onVerboseLogChange,
                            onCheckUpdate = onCheckUpdate,
                            onDismissUpdate = onDismissUpdate,
                        )
                    }
                    AppRoute.RadialSettings -> {
                        RadialSettingsScreen(
                            state = state,
                            onBack = popBackStack,
                            onCornerSidesChange = onCornerSidesChange,
                            onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                            onRadialHitRangeChange = onRadialHitRangeChange,
                            onRadialIconSizeChange = onRadialIconSizeChange,
                            onBackdropLevelChange = onBackdropLevelChange,
                            onRadialKeepOpenChange = onRadialKeepOpenChange,
                            onPauseInLandscapeChange = onPauseInLandscapeChange,
                            onPauseInGameModeChange = onPauseInGameModeChange,
                            onNavigateToPinnedApps = navigateToPinnedApps,
                        )
                    }
                    AppRoute.ZoomOptions -> {
                        ZoomOptionsScreen(
                            state = state,
                            onBack = popBackStack,
                            onZoomMaxWindowCountChange = onZoomMaxWindowCountChange,
                            onZoomAllowAnyAppChange = onZoomAllowAnyAppChange,
                            onAutoCloseWindowChange = onAutoCloseWindowChange,
                            onFloatKeepAliveChange = onFloatKeepAliveChange,
                            onLongPressMuteChange = onLongPressMuteChange,
                            onNavigateToZoomExcluded = navigateToZoomExcluded,
                            onNavigateToZoomLaunchScenes = navigateToZoomLaunchScenes,
                            onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                            onHandleSwipeUpToMiniEnabledChange = onHandleSwipeUpToMiniEnabledChange,
                        )
                    }
                    AppRoute.ZoomLaunchScenes -> {
                        ZoomLaunchScenesScreen(
                            state = state,
                            onBack = popBackStack,
                            onZoomLaunchNoticeChange = onZoomLaunchNoticeChange,
                            onZoomLaunchHeadsUpChange = onZoomLaunchHeadsUpChange,
                            onZoomLaunchControlCenterChange = onZoomLaunchControlCenterChange,
                            onZoomLaunchInterAppChange = onZoomLaunchInterAppChange,
                            onZoomLaunchInterAppResultChange = onZoomLaunchInterAppResultChange,
                            onZoomLaunchShareRelayChange = onZoomLaunchShareRelayChange,
                            onZoomLaunchClipboardChange = onZoomLaunchClipboardChange,
                        )
                    }
                    AppRoute.ZoomExcluded -> {
                        ZoomExcludedScreen(
                            state = state,
                            apps = apps,
                            onBack = popBackStack,
                            onAddApps = navigateToZoomExcludedPicker,
                            onZoomExcludedChange = onZoomExcludedChange,
                        )
                    }
                    AppRoute.ZoomExcludedPicker -> {
                        ZoomExcludedPickerScreen(
                            state = state,
                            apps = apps,
                            appsLoading = appsLoading,
                            onBack = popBackStack,
                            onZoomExcludedChange = onZoomExcludedChange,
                            onShowSystemAppsChange = onShowSystemAppsChange,
                        )
                    }
                    AppRoute.Blacklist -> {
                        BlacklistScreen(
                            state = state,
                            apps = apps,
                            onBack = popBackStack,
                            onAddApps = navigateToBlacklistPicker,
                            onBlacklistChange = onBlacklistChange,
                        )
                    }
                    AppRoute.BlacklistPicker -> {
                        BlacklistPickerScreen(
                            state = state,
                            apps = apps,
                            appsLoading = appsLoading,
                            onBack = popBackStack,
                            onBlacklistChange = onBlacklistChange,
                            onShowSystemAppsChange = onShowSystemAppsChange,
                        )
                    }
                    AppRoute.PinnedApps -> {
                        PinnedAppsScreen(
                            state = state,
                            apps = apps,
                            sidebarCatalog = sidebarCatalog,
                            onRefreshCatalog = onRefreshCatalog,
                            onCancelCatalog = onCancelCatalog,
                            onBack = popBackStack,
                            onPinnedTargetsChange = onPinnedTargetsChange,
                        )
                    }
                }
            }
        },
    )
}

/** 返回栈的保存形式：类名列表，恢复时映射回路由对象。 */
private val AppBackStackSaver =
    listSaver<SnapshotStateList<AppRoute>, String>(
        save = { stack -> stack.map { route -> route.javaClass.name } },
        restore = { names ->
            mutableStateListOf(
                *names.mapNotNull(::routeOfName).ifEmpty { listOf(AppRoute.Settings) }.toTypedArray(),
            )
        },
    )

private fun routeOfName(name: String): AppRoute? =
    when (name) {
        AppRoute.Settings.javaClass.name -> AppRoute.Settings
        AppRoute.PinnedApps.javaClass.name -> AppRoute.PinnedApps
        AppRoute.Blacklist.javaClass.name -> AppRoute.Blacklist
        AppRoute.BlacklistPicker.javaClass.name -> AppRoute.BlacklistPicker
        AppRoute.ZoomOptions.javaClass.name -> AppRoute.ZoomOptions
        AppRoute.ZoomLaunchScenes.javaClass.name -> AppRoute.ZoomLaunchScenes
        AppRoute.ZoomExcluded.javaClass.name -> AppRoute.ZoomExcluded
        AppRoute.ZoomExcludedPicker.javaClass.name -> AppRoute.ZoomExcludedPicker
        AppRoute.RadialSettings.javaClass.name -> AppRoute.RadialSettings
        AppRoute.About.javaClass.name -> AppRoute.About
        else -> null
    }
