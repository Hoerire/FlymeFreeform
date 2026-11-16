package io.github.hoerire.flymefreeform

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
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
            // 设置页可能以浮窗形式显示，此时生命周期不一定是 RESUMED：
            // 用 collectAsState 直接跟随组合，避免状态收集不启动、界面永远停在初始值。
            val state = repository.state.collectAsState().value
            val sidebarCatalog = sidebarRepository.state.collectAsState().value
            val apps = appRepository.apps.collectAsState().value
            val appsLoading = appRepository.loading.collectAsState().value
            val update = updateChecker.state.collectAsState().value
            androidx.compose.runtime.LaunchedEffect(Unit) { repository.retryIfNeeded() }
            // 选择列表是否列系统应用由设置决定；切换后按新口径重载一次。
            androidx.compose.runtime.LaunchedEffect(state.settings.showSystemApps) {
                appRepository.refresh(state.settings.showSystemApps)
            }
            FlymeFreeformTheme {
                FlymeFreeformNavHost(
                    stateFlow = repository.state,
                    appsFlow = appRepository.apps,
                    appsLoadingFlow = appRepository.loading,
                    sidebarCatalogFlow = sidebarRepository.state,
                    onRefreshCatalog = sidebarRepository::refresh,
                    onCancelCatalog = sidebarRepository::cancel,
                    onModuleEnabledChange = repository::setModuleEnabled,
                    onCornerSidesChange = repository::setCornerSides,
                    onCornerTriggerRangeChange = repository::setCornerTriggerRangeDp,
                    onRadialHitRangeChange = repository::setRadialHitRangeDp,
                    onRadialIconSizeChange = repository::setRadialIconSizeDp,
                    onBackdropLevelChange = repository::setBackdropLevel,
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
                    onZoomLaunchClipboardChange = repository::setZoomLaunchClipboard,
                    onAutoCloseWindowChange = repository::setAutoCloseWindow,
                    onFloatKeepAliveChange = repository::setFloatKeepAlive,
                    onFloatHandleSwitchChange = repository::setFloatHandleSwitch,
                    onFloatHandleMaxCountChange = repository::setFloatHandleMaxCount,
                    onLongPressMuteChange = repository::setLongPressMute,
                    onRadialKeepOpenChange = repository::setRadialKeepOpen,
                    onRequestScopes = repository::requestMissingScopes,
                    onPinnedTargetsChange = repository::setPinnedTargets,
                    onBlacklistChange = repository::setBlacklistPackages,
                    onShowSystemAppsChange = repository::setShowSystemApps,
                    onZoomExcludedChange = repository::setZoomExcludedPackages,
                    updateFlow = updateChecker.state,
                    onCheckUpdate = { updateChecker.check(force = true) },
                    onDismissUpdate = updateChecker::dismiss,
                )
            }
        }
    }
}
