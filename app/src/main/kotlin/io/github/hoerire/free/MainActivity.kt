package io.github.hoerire.free

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import io.github.hoerire.free.ui.FreeNavHost
import io.github.hoerire.free.ui.theme.FreeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false

        installGestureExclusion()

        // 排查用户反馈时先确认跑的是哪个包：版本号 + 安装时间都写进 logcat。
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            android.util.Log.i(
                "Free",
                "APP_START_c" + info.longVersionCode + "_v" + info.versionName +
                    "_upd" + info.lastUpdateTime,
            )
        }

        val repository =
            (application as FreeApplication).frameworkConnectionRepository
        val appRepository =
            (application as FreeApplication).launcherAppRepository
        val sidebarRepository = (application as FreeApplication).sidebarEntryRepository
        val updateChecker = (application as FreeApplication).updateChecker
        setContent {
            // 设置页可能以浮窗形式显示，此时生命周期不一定是 RESUMED：
            // 用 collectAsState 直接跟随组合，避免状态收集不启动、界面永远停在初始值。
            val state = repository.state.collectAsState().value
            val sidebarCatalog = sidebarRepository.state.collectAsState().value
            val apps = appRepository.apps.collectAsState().value
            val appsLoading = appRepository.loading.collectAsState().value
            val update = updateChecker.state.collectAsState().value
            androidx.compose.runtime.LaunchedEffect(Unit) { repository.retryIfNeeded() }
            // 应用列表固定包含全部已安装应用（含没有桌面图标的组件），不再按开关筛选。
            androidx.compose.runtime.LaunchedEffect(Unit) {
                appRepository.refresh()
            }
            FreeTheme {
                FreeNavHost(
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
                    onPauseInKeyboardChange = repository::setPauseInKeyboard,
                    onVerboseLogChange = repository::setVerboseLogEnabled,
                    onFreeDragChange = repository::setFreeDragEnabled,
                    onQuickShareChange = repository::setQuickShareEnabled,
                    onDragLongPressChange = repository::setDragLongPressMs,
                    onZoomMaxWindowCountChange = repository::setZoomMaxWindowCount,
                    onZoomAllowAnyAppChange = repository::setZoomAllowAnyApp,
                    onZoomLaunchNoticeChange = repository::setZoomLaunchNotice,
                    onZoomLaunchHeadsUpChange = repository::setZoomLaunchHeadsUp,
                    onZoomLaunchControlCenterChange = repository::setZoomLaunchControlCenter,
                    onZoomLaunchInterAppChange = repository::setZoomLaunchInterApp,
                    onZoomLaunchClipboardChange = repository::setZoomLaunchClipboard,
                    onAutoCloseWindowChange = repository::setAutoCloseWindow,
                    onFloatKeepAliveChange = repository::setFloatKeepAlive,
                    onFloatHandleMaxCountChange = repository::setFloatHandleMaxCount,
                    onLongPressMuteChange = repository::setLongPressMute,
                    onRadialInteractionEnabledChange = repository::setRadialInteractionEnabled,
                    onRadialKeepOpenChange = repository::setRadialKeepOpen,
                    onRadialSelectionRingChange = repository::setRadialSelectionRing,
                    onRadialShimmerEnabledChange = repository::setRadialShimmerEnabled,
                    onRadialShimmerChange = repository::setRadialShimmer,
                    onRadialRadiusPercentChange = repository::setRadialRadiusPercent,
                    onRequestScopes = repository::requestMissingScopes,
                    onPinnedTargetsChange = repository::setPinnedTargets,
                    onBlacklistChange = repository::setBlacklistPackages,
                    onZoomExcludedChange = repository::setZoomExcludedPackages,
                    updateFlow = updateChecker.state,
                    onCheckUpdate = { updateChecker.check(force = true) },
                    onDismissUpdate = updateChecker::dismiss,
                )
            }
        }
    }

    /**
     * 把页面顶部的左右两端从系统返回手势里排除出去。
     *
     * 顶部返回按钮的落点在屏幕左边缘 24dp 的返回手势区里（日志：action_down is in
     * interception region {[gesture…][0,141][84,2800]}，点按坐标 x=78），系统会把它当成
     * 一次返回手势直接退出应用或把任务切到后台，页面看起来就"卡住"了。
     * 声明手势排除区后，这一带的触摸只交给应用自己的按钮处理。
     */
    private fun installGestureExclusion() {
        val decor = window.decorView
        decor.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> applyGestureExclusion(view) }
        decor.post { applyGestureExclusion(decor) }
    }

    private fun applyGestureExclusion(view: android.view.View) {
        if (view.width <= 0 || view.height <= 0) return
        val density = resources.displayMetrics.density
        // 系统对每条边的排除上限是 200dp，这里取整个顶部条带的两端。
        val width = (200f * density).toInt().coerceAtMost(view.width / 2)
        val height = (120f * density).toInt().coerceAtMost(view.height)
        view.systemGestureExclusionRects =
            listOf(
                android.graphics.Rect(0, 0, width, height),
                android.graphics.Rect(view.width - width, 0, view.width, height),
            )
    }
}
