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
    data object RadialSettings : AppRoute

    @Serializable
    data object EnvironmentScope : AppRoute
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
    onOutsideTapCloseModeChange: (OutsideTapCloseMode) -> Unit,
    onHandleSwipeUpToMiniEnabledChange: (Boolean) -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onRadialMaxItemsChange: (Int) -> Unit,
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
    val navigateToEnvironmentScope = remember(backStack) {
        {
            if (backStack.lastOrNull() != AppRoute.EnvironmentScope) {
                backStack.add(AppRoute.EnvironmentScope)
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
                onNavigateToEnvironmentScope = navigateToEnvironmentScope,
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
                onRadialMaxItemsChange = onRadialMaxItemsChange,
                onRadialKeepOpenChange = onRadialKeepOpenChange,
                onNavigateToPinnedApps = navigateToPinnedApps,
            )
        }
        entry<AppRoute.ZoomOptions> {
            ZoomOptionsScreen(
                state = state,
                onBack = popBackStack,
                onZoomMaxWindowCountChange = onZoomMaxWindowCountChange,
                onZoomAllowAnyAppChange = onZoomAllowAnyAppChange,
                onZoomLaunchChange = onZoomLaunchChange,
                onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                onHandleSwipeUpToMiniEnabledChange = onHandleSwipeUpToMiniEnabledChange,
            )
        }
        entry<AppRoute.EnvironmentScope> {
            EnvironmentScopeScreen(
                state = state,
                onBack = popBackStack,
                onPauseInLandscapeChange = onPauseInLandscapeChange,
                onPauseInGameModeChange = onPauseInGameModeChange,
                onNavigateToBlacklist = navigateToBlacklist,
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
