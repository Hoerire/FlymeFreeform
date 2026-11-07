package io.github.hoerire.flymefreeform

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hoerire.flymefreeform.ui.FlymeFreeformNavHost
import io.github.hoerire.flymefreeform.ui.theme.FlymeFreeformTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        val repository =
            (application as FlymeFreeformApplication).frameworkConnectionRepository
        val appRepository =
            (application as FlymeFreeformApplication).launcherAppRepository
        val sidebarRepository = (application as FlymeFreeformApplication).sidebarEntryRepository
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val sidebarCatalog = sidebarRepository.state.collectAsStateWithLifecycle().value
            val apps = appRepository.apps.collectAsStateWithLifecycle().value
            FlymeFreeformTheme {
                FlymeFreeformNavHost(
                    state = state,
                    apps = apps,
                    sidebarCatalog = sidebarCatalog,
                    onRefreshCatalog = sidebarRepository::refresh,
                    onCancelCatalog = sidebarRepository::cancel,
                    onModuleEnabledChange = repository::setModuleEnabled,
                    onCornerSidesChange = repository::setCornerSides,
                    onCornerTriggerRangeChange = repository::setCornerTriggerRangeDp,
                    onRadialHitRangeChange = repository::setRadialHitRangeDp,
                    onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                    onHandleSwipeUpToMiniEnabledChange =
                        repository::setHandleSwipeUpToMiniEnabled,
                    onPauseInLandscapeChange = repository::setPauseInLandscape,
                    onPauseInGameModeChange = repository::setPauseInGameMode,
                    onVerboseLogChange = repository::setVerboseLogEnabled,
                    onZoomMaxWindowCountChange = repository::setZoomMaxWindowCount,
                    onZoomAllowAnyAppChange = repository::setZoomAllowAnyApp,
                    onZoomLaunchNotificationChange = repository::setZoomLaunchNotification,
                    onZoomLaunchControlCenterChange = repository::setZoomLaunchControlCenter,
                    onZoomLaunchInterAppChange = repository::setZoomLaunchInterApp,
                    onRadialKeepOpenChange = repository::setRadialKeepOpen,
                    onRadialMaxItemsChange = repository::setRadialMaxItems,
                    onRequestScopes = repository::requestMissingScopes,
                    onPinnedTargetsChange = repository::setPinnedTargets,
                    onBlacklistChange = repository::setBlacklistPackages,
                )
            }
        }
    }
}
