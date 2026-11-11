package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.hoerire.flymefreeform.apps.SelectableEntry
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.config.PinnedTarget
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack

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
    data object ZoomExcluded : AppRoute

    @Serializable
    data object ZoomExcludedPicker : AppRoute

    @Serializable
    data object RadialSettings : AppRoute
}

@Composable
internal fun FlymeFreeformNavHost(
    state: FrameworkConnectionState,
    apps: List<SelectableEntry>,
    sidebarCatalog: io.github.hoerire.flymefreeform.apps.SidebarCatalogState,
    onRefreshCatalog: () -> Unit,
    onCancelCatalog: () -> Unit,
    onModuleEnabledChange: (Boolean) -> Unit,
    onCornerSidesChange: (Boolean, Boolean) -> Unit,
    onCornerTriggerRangeChange: (Int) -> Unit,
    onRadialHitRangeChange: (Int) -> Unit,
    onRadialIconSizeChange: (Int) -> Unit,
    onBlurRadiusChange: (Int) -> Unit,
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onZoomInteractionEnabledChange: (Boolean) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchNoticeChange: (Boolean) -> Unit,
    onZoomLaunchHeadsUpChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
    onZoomLauncherShortcutChange: (Boolean) -> Unit,
    onZoomLauncherCardChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onZoomExcludedChange: (List<String>) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedTargetsChange: (List<PinnedTarget>) -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
) {
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Settings)
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
    val navigateToZoomOptions = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.ZoomOptions) {
                backStack.add(AppRoute.ZoomOptions)
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

    NavDisplay(
        backStack = backStack,
        modifier = Modifier.fillMaxSize(),
        onBack = popBackStack,
    ) {
        entry<AppRoute.Settings> {
            ControlScreen(
                state = state,
                onModuleEnabledChange = onModuleEnabledChange,
                onCornerSidesChange = onCornerSidesChange,
                onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                onRadialHitRangeChange = onRadialHitRangeChange,
                onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                onHandleSwipeUpToMiniEnabledChange =
                    onHandleSwipeUpToMiniEnabledChange,
                onPauseInLandscapeChange = onPauseInLandscapeChange,
                onPauseInGameModeChange = onPauseInGameModeChange,
                onVerboseLogChange = onVerboseLogChange,
                onRequestScopes = onRequestScopes,
                onNavigateToPinnedApps = navigateToPinnedApps,
                onNavigateToRadialSettings = navigateToRadialSettings,
                onNavigateToBlacklist = navigateToBlacklist,
                onNavigateToZoomOptions = navigateToZoomOptions,
            )
        }
        entry<AppRoute.RadialSettings> {
            RadialSettingsScreen(
                state = state,
                onBack = popBackStack,
                onCornerSidesChange = onCornerSidesChange,
                onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                onRadialHitRangeChange = onRadialHitRangeChange,
                onRadialIconSizeChange = onRadialIconSizeChange,
                onBlurRadiusChange = onBlurRadiusChange,
                onRadialKeepOpenChange = onRadialKeepOpenChange,
                onPauseInLandscapeChange = onPauseInLandscapeChange,
                onPauseInGameModeChange = onPauseInGameModeChange,
                onNavigateToPinnedApps = navigateToPinnedApps,
            )
        }
        entry<AppRoute.ZoomOptions> {
            ZoomOptionsScreen(
                state = state,
                onBack = popBackStack,
                onZoomInteractionEnabledChange = onZoomInteractionEnabledChange,
                onZoomMaxWindowCountChange = onZoomMaxWindowCountChange,
                onZoomAllowAnyAppChange = onZoomAllowAnyAppChange,
                onZoomLaunchNoticeChange = onZoomLaunchNoticeChange,
                onZoomLaunchHeadsUpChange = onZoomLaunchHeadsUpChange,
                onZoomLaunchControlCenterChange = onZoomLaunchControlCenterChange,
                onZoomLaunchInterAppChange = onZoomLaunchInterAppChange,
                onZoomLauncherShortcutChange = onZoomLauncherShortcutChange,
                onZoomLauncherCardChange = onZoomLauncherCardChange,
                onAutoCloseWindowChange = onAutoCloseWindowChange,
                onNavigateToZoomExcluded = navigateToZoomExcluded,
                onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                onHandleSwipeUpToMiniEnabledChange = onHandleSwipeUpToMiniEnabledChange,
            )
        }
        entry<AppRoute.ZoomExcluded> {
            ZoomExcludedScreen(
                state = state,
                apps = apps,
                onBack = popBackStack,
                onAddApps = navigateToZoomExcludedPicker,
                onZoomExcludedChange = onZoomExcludedChange,
            )
        }
        entry<AppRoute.ZoomExcludedPicker> {
            ZoomExcludedPickerScreen(
                state = state,
                apps = apps,
                onBack = popBackStack,
                onZoomExcludedChange = onZoomExcludedChange,
                onShowSystemAppsChange = onShowSystemAppsChange,
            )
        }
        entry<AppRoute.Blacklist> {
            BlacklistScreen(
                state = state,
                apps = apps,
                onBack = popBackStack,
                onAddApps = navigateToBlacklistPicker,
                onBlacklistChange = onBlacklistChange,
            )
        }
        entry<AppRoute.BlacklistPicker> {
            BlacklistPickerScreen(
                state = state,
                apps = apps,
                onBack = popBackStack,
                onBlacklistChange = onBlacklistChange,
                onShowSystemAppsChange = onShowSystemAppsChange,
            )
        }
        entry<AppRoute.PinnedApps> {
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
