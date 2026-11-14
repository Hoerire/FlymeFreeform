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
        val updateChecker = (application as FlymeFreeformApplication).updateChecker
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val sidebarCatalog = sidebarRepository.state.collectAsStateWithLifecycle().value
            val apps = appRepository.apps.collectAsStateWithLifecycle().value
            val update = updateChecker.state.collectAsStateWithLifecycle().value
            // 选择列表是否列系统应用由设置决定；切换后按新口径重载一次。
            androidx.compose.runtime.LaunchedEffect(state.settings.showSystemApps) {
                appRepository.refresh(state.settings.showSystemApps)
            }
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
                    onRadialIconSizeChange = repository::setRadialIconSizeDp,
                    onShadePercentChange = repository::setShadePercent,
                    onOutsideTapCloseModeChange = repository::setOutsideTapCloseMode,
                    onHandleSwipeUpToMiniEnabledChange =
                        repository::setHandleSwipeUpToMiniEnabled,
                    onPauseInLandscapeChange = repository::setPauseInLandscape,
                    onPauseInGameModeChange = repository::setPauseInGameMode,
                    onVerboseLogChange = repository::setVerboseLogEnabled,
                    onFreeDragChange = repository::setFreeDragEnabled,
                    onDragLongPressChange = repository::setDragLongPressMs,
                    onZoomMaxWindowCountChange = repository::setZoomMaxWindowCount,
                    onZoomAllowAnyAppChange = repository::setZoomAllowAnyApp,
                    onZoomLaunchNoticeChange = repository::setZoomLaunchNotice,
                    onZoomLaunchHeadsUpChange = repository::setZoomLaunchHeadsUp,
                    onZoomLaunchControlCenterChange = repository::setZoomLaunchControlCenter,
                    onZoomLaunchInterAppChange = repository::setZoomLaunchInterApp,
                    onZoomLaunchInterAppResultChange = repository::setZoomLaunchInterAppResult,
                    onZoomLaunchShareRelayChange = repository::setZoomLaunchShareRelay,
                    onZoomLaunchSystemChange = repository::setZoomLaunchSystem,
                    onZoomLaunchClipboardChange = repository::setZoomLaunchClipboard,
                    onAutoCloseWindowChange = repository::setAutoCloseWindow,
                    onFloatKeepAliveChange = repository::setFloatKeepAlive,
                    onLongPressMuteChange = repository::setLongPressMute,
                    onRadialKeepOpenChange = repository::setRadialKeepOpen,
                    onRequestScopes = repository::requestMissingScopes,
                    onPinnedTargetsChange = repository::setPinnedTargets,
                    onBlacklistChange = repository::setBlacklistPackages,
                    onShowSystemAppsChange = repository::setShowSystemApps,
                    onZoomExcludedChange = repository::setZoomExcludedPackages,
                    update = update,
                    onCheckUpdate = { updateChecker.check(force = true) },
                    onDismissUpdate = updateChecker::dismiss,
                )
            }
        }
    }
}
