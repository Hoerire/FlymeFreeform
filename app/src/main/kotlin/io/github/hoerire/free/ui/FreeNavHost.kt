package io.github.hoerire.free.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import io.github.hoerire.free.apps.SelectableEntry
import io.github.hoerire.free.config.OutsideTapCloseMode
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.RadialGroup
import io.github.hoerire.free.config.RadialGroupSide
import io.github.hoerire.free.config.RadialShimmer
import io.github.hoerire.free.framework.FrameworkConnectionState
import io.github.hoerire.free.update.UpdateState

/**
 * 应用内的页面路由标识。
 *
 * 只用于本文件的返回栈与保存恢复，不再实现 navigation3 的 `NavKey`：
 * 栈内容由下面那份 `listSaver` 按类名存取，不经过 Bundle 序列化。
 */
internal sealed interface AppRoute {
    object Settings : AppRoute

    object PinnedApps : AppRoute

    object Blacklist : AppRoute

    object BlacklistPicker : AppRoute

    object ZoomOptions : AppRoute

    object ZoomLaunchScenes : AppRoute

    object ZoomExcluded : AppRoute

    object ZoomExcludedPicker : AppRoute

    object RadialSettings : AppRoute

    /** 选中光圈进去的流光参数页。 */
    object RadialShimmer : AppRoute

    /** 外观参数页：命中范围 / 图标大小 / 扇形大小 / 背景遮罩。 */
    object RadialSize : AppRoute

    /** 选中特效二级页：光圈 / 流光 / 选中放大，流光参数再往里一层。 */
    object RadialEffect : AppRoute

    /** 自由传送二级页：总开关 + 长按时长。 */
    object FreeDrag : AppRoute

    /** 手势交互二级页：快速上滑 / 长按静音 / 关闭方式。 */
    object ZoomGesture : AppRoute

    /** 贴边浮标二级页：贴边挂机 / 浮标数量。 */
    object ZoomEdgeFloat : AppRoute

    object About : AppRoute
}

/**
 * 设置界面与各级子页面。
 *
 * **不要换回第三方导航组件。** 早期用过的导航库给每条 entry 都挂了一层全屏
 * `pointerInput` 输入阻断，条件是
 * `blockInputDuringTransition && !isSettled && targetScene != transition.targetState`，
 * 而 settled 由内部 `SeekableTransitionState` 的 animateTo 协程推动。从二级页返回时
 * 该协程没走到位，退出的那一页就永远留在组合树里，视觉上它已经透明（看起来停在设置页），
 * 但它的阻断层把后续所有点击都吞掉 —— 表现就是「返回后界面卡住、只有输入事件、路由不变」。
 * 日志：返回后路由已变化，后续点击不再产生路由变化 —— 与「阻断层仍在树上」一致。
 * 因此按路由直接切页，转场交给 [RouteTransition] 用 graphicsLayer 实现：
 * 没有可拖动的转场状态机、没有残留组合、没有输入阻断，也不逐帧重排页面。
 */
@Composable
internal fun FreeNavHost(
    stateFlow: kotlinx.coroutines.flow.StateFlow<FrameworkConnectionState>,
    appsFlow: kotlinx.coroutines.flow.StateFlow<List<SelectableEntry>>,
    appsLoadingFlow: kotlinx.coroutines.flow.StateFlow<Boolean>,
    sidebarCatalogFlow: kotlinx.coroutines.flow.StateFlow<io.github.hoerire.free.apps.SidebarCatalogState>,
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
    onPauseInKeyboardChange: (Boolean) -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onFreeDragChange: (Boolean) -> Unit,
    onQuickShareChange: (Boolean) -> Unit,
    onDragLongPressChange: (Int) -> Unit,
    onZoomMaxWindowCountChange: (Int) -> Unit,
    onZoomAllowAnyAppChange: (Boolean) -> Unit,
    onZoomLaunchNoticeChange: (Boolean) -> Unit,
    onZoomLaunchHeadsUpChange: (Boolean) -> Unit,
    onZoomLaunchControlCenterChange: (Boolean) -> Unit,
    onZoomLaunchInterAppChange: (Boolean) -> Unit,
    onZoomLaunchClipboardChange: (Boolean) -> Unit,
    onAutoCloseWindowChange: (Boolean) -> Unit,
    onFloatKeepAliveChange: (Boolean) -> Unit,
    onFloatHandleMaxCountChange: (Int) -> Unit,
    onLongPressMuteChange: (Boolean) -> Unit,
    onZoomExcludedChange: (List<String>) -> Unit,
    onRadialInteractionEnabledChange: (Boolean) -> Unit,
    onRadialKeepOpenChange: (Boolean) -> Unit,
    onSecondaryGroupSideChange: (RadialGroupSide) -> Unit,
    onRadialShimmerEnabledChange: (Boolean) -> Unit,
    onRadialShimmerChange: (RadialShimmer) -> Unit,
    onRadialRadiusPercentChange: (Int) -> Unit,
    onRequestScopes: () -> Unit,
    onPinnedTargetsChange: (RadialGroup, List<PinnedTarget>) -> Unit,
    onBlacklistChange: (List<String>) -> Unit,
    updateFlow: kotlinx.coroutines.flow.StateFlow<UpdateState>,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: (String) -> Unit,
) {
    // 返回栈按类名保存：路由对象不属于 Bundle 可存类型，直接交给 saveable 会在首帧抛异常。
    val backStack =
        rememberSaveable(saver = AppBackStackSaver) {
            mutableStateListOf<AppRoute>(AppRoute.Settings)
        }

    // 转场状态由导航动作直接驱动（不靠组合期推断），切页当帧就能开始动画。
    val transition = remember { RouteTransitionState(backStack.lastOrNull() ?: AppRoute.Settings) }

    val pushRoute: (AppRoute) -> Unit = remember(backStack, transition) {
        { target ->
            if (backStack.lastOrNull() != target) {
                backStack.add(target)
                transition.switchTo(target, forward = true)
            }
        }
    }

    val popBackStack: () -> Unit = remember(backStack, transition) {
        {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
                val target = backStack.lastOrNull() ?: AppRoute.Settings
                transition.switchTo(target, forward = false)
            }
        }
    }

    val navigateToPinnedApps: () -> Unit = remember(backStack) { { pushRoute(AppRoute.PinnedApps) } }
    val navigateToBlacklist: () -> Unit = remember(backStack) { { pushRoute(AppRoute.Blacklist) } }
    val navigateToBlacklistPicker: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.BlacklistPicker) } }
    val navigateToRadialSettings: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.RadialSettings) } }
    val navigateToRadialShimmer: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.RadialShimmer) } }
    val navigateToRadialSize: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.RadialSize) } }
    val navigateToRadialEffect: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.RadialEffect) } }
    val navigateToFreeDrag: () -> Unit = remember(backStack) { { pushRoute(AppRoute.FreeDrag) } }
    val navigateToGesture: () -> Unit = remember(backStack) { { pushRoute(AppRoute.ZoomGesture) } }
    val navigateToEdgeFloat: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.ZoomEdgeFloat) } }
    val navigateToAbout: () -> Unit = remember(backStack) { { pushRoute(AppRoute.About) } }
    val navigateToZoomOptions: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.ZoomOptions) } }
    val navigateToZoomLaunchScenes: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.ZoomLaunchScenes) } }
    val navigateToZoomExcluded: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.ZoomExcluded) } }
    val navigateToZoomExcludedPicker: () -> Unit =
        remember(backStack) { { pushRoute(AppRoute.ZoomExcludedPicker) } }

    // 系统返回键/返回手势由本实现接管，不依赖导航库的 NavigationEvent 接线。
    BackHandler(enabled = backStack.size > 1, onBack = popBackStack)

    val route = backStack.lastOrNull() ?: AppRoute.Settings
    // 设置页可能以浮窗形式显示，此时生命周期不一定是 RESUMED：状态直接跟随组合收集，
    // 避免界面永远停在初始值。
    val state = stateFlow.collectAsState().value
    val apps = appsFlow.collectAsState().value
    val appsLoading = appsLoadingFlow.collectAsState().value
    val sidebarCatalog = sidebarCatalogFlow.collectAsState().value
    val update = updateFlow.collectAsState().value

    LaunchedEffect(route) {
        if (android.util.Log.isLoggable("Free", android.util.Log.INFO)) {
            android.util.Log.i("Free", "APP_ROUTE_" + route.javaClass.simpleName + "_size" + backStack.size)
        }
    }

    // 转场只用 graphicsLayer 平移：不走布局、不做测量，也不会产生输入阻断层。
    RouteTransition(state = transition, modifier = Modifier.fillMaxSize()) { pageRoute ->
        when (pageRoute) {
            AppRoute.Settings ->
                ControlScreen(
                    state = state,
                    update = update,
                    onDismissUpdate = onDismissUpdate,
                    onModuleEnabledChange = onModuleEnabledChange,
                    onCornerSidesChange = onCornerSidesChange,
                    onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                    onRadialHitRangeChange = onRadialHitRangeChange,
                    onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                    onHandleSwipeUpToMiniEnabledChange = onHandleSwipeUpToMiniEnabledChange,
                    onPauseInLandscapeChange = onPauseInLandscapeChange,
                    onPauseInGameModeChange = onPauseInGameModeChange,
                    onRequestScopes = onRequestScopes,
                    onNavigateToPinnedApps = navigateToPinnedApps,
                    onNavigateToRadialSettings = navigateToRadialSettings,
                    onNavigateToBlacklist = navigateToBlacklist,
                    onNavigateToZoomOptions = navigateToZoomOptions,
                    onNavigateToFreeDrag = navigateToFreeDrag,
                    onNavigateToAbout = navigateToAbout,
                )
            AppRoute.About ->
                AboutScreen(
                    update = update,
                    verboseLogEnabled = state.settings.verboseLogEnabled,
                    onBack = popBackStack,
                    onVerboseLogChange = onVerboseLogChange,
                    onCheckUpdate = onCheckUpdate,
                    onDismissUpdate = onDismissUpdate,
                )
            AppRoute.RadialSettings ->
                RadialSettingsScreen(
                    state = state,
                    onBack = popBackStack,
                    onCornerSidesChange = onCornerSidesChange,
                    onCornerTriggerRangeChange = onCornerTriggerRangeChange,
                    onRadialInteractionEnabledChange = onRadialInteractionEnabledChange,
                    onRadialKeepOpenChange = onRadialKeepOpenChange,
                    onPauseInLandscapeChange = onPauseInLandscapeChange,
                    onPauseInGameModeChange = onPauseInGameModeChange,
                    onPauseInKeyboardChange = onPauseInKeyboardChange,
                    onNavigateToPinnedApps = navigateToPinnedApps,
                    onNavigateToRadialEffect = navigateToRadialEffect,
                    onNavigateToRadialSize = navigateToRadialSize,
                )
            AppRoute.ZoomGesture ->
                GestureScreen(
                    state = state,
                    onBack = popBackStack,
                    onHandleSwipeUpToMiniEnabledChange = onHandleSwipeUpToMiniEnabledChange,
                    onLongPressMuteChange = onLongPressMuteChange,
                    onOutsideTapCloseModeChange = onOutsideTapCloseModeChange,
                )
            AppRoute.ZoomEdgeFloat ->
                EdgeFloatScreen(
                    state = state,
                    onBack = popBackStack,
                    onFloatKeepAliveChange = onFloatKeepAliveChange,
                    onFloatHandleMaxCountChange = onFloatHandleMaxCountChange,
                )
            AppRoute.FreeDrag ->
                FreeDragScreen(
                    state = state,
                    onBack = popBackStack,
                    onFreeDragChange = onFreeDragChange,
                    onQuickShareChange = onQuickShareChange,
                    onDragLongPressChange = onDragLongPressChange,
                )
            AppRoute.RadialEffect ->
                RadialEffectScreen(
                    state = state,
                    onBack = popBackStack,
                    onRadialShimmerEnabledChange = onRadialShimmerEnabledChange,
                    onRadialShimmerChange = onRadialShimmerChange,
                    onNavigateToRadialShimmer = navigateToRadialShimmer,
                )
            AppRoute.RadialShimmer ->
                RadialShimmerScreen(
                    state = state,
                    onBack = popBackStack,
                    onRadialShimmerChange = onRadialShimmerChange,
                )
            AppRoute.RadialSize ->
                RadialSizeScreen(
                    state = state,
                    onBack = popBackStack,
                    onRadialHitRangeChange = onRadialHitRangeChange,
                    onRadialIconSizeChange = onRadialIconSizeChange,
                    onRadialRadiusPercentChange = onRadialRadiusPercentChange,
                    onBackdropLevelChange = onBackdropLevelChange,
                )
            AppRoute.ZoomOptions ->
                ZoomOptionsScreen(
                    state = state,
                    onBack = popBackStack,
                    onZoomMaxWindowCountChange = onZoomMaxWindowCountChange,
                    onZoomAllowAnyAppChange = onZoomAllowAnyAppChange,
                    onAutoCloseWindowChange = onAutoCloseWindowChange,
                    onNavigateToZoomExcluded = navigateToZoomExcluded,
                    onNavigateToZoomLaunchScenes = navigateToZoomLaunchScenes,
                    onNavigateToGesture = navigateToGesture,
                    onNavigateToEdgeFloat = navigateToEdgeFloat,
                )
            AppRoute.ZoomLaunchScenes ->
                ZoomLaunchScenesScreen(
                    state = state,
                    onBack = popBackStack,
                    onZoomLaunchNoticeChange = onZoomLaunchNoticeChange,
                    onZoomLaunchHeadsUpChange = onZoomLaunchHeadsUpChange,
                    onZoomLaunchControlCenterChange = onZoomLaunchControlCenterChange,
                    onZoomLaunchInterAppChange = onZoomLaunchInterAppChange,
                    onZoomLaunchClipboardChange = onZoomLaunchClipboardChange,
                )
            AppRoute.ZoomExcluded ->
                ZoomExcludedScreen(
                    state = state,
                    apps = apps,
                    onBack = popBackStack,
                    onAddApps = navigateToZoomExcludedPicker,
                    onZoomExcludedChange = onZoomExcludedChange,
                )
            AppRoute.ZoomExcludedPicker ->
                ZoomExcludedPickerScreen(
                    state = state,
                    apps = apps,
                    appsLoading = appsLoading,
                    onBack = popBackStack,
                    onZoomExcludedChange = onZoomExcludedChange,
                )
            AppRoute.Blacklist ->
                BlacklistScreen(
                    state = state,
                    apps = apps,
                    onBack = popBackStack,
                    onAddApps = navigateToBlacklistPicker,
                    onBlacklistChange = onBlacklistChange,
                )
            AppRoute.BlacklistPicker ->
                BlacklistPickerScreen(
                    state = state,
                    apps = apps,
                    appsLoading = appsLoading,
                    onBack = popBackStack,
                    onBlacklistChange = onBlacklistChange,
                )
            AppRoute.PinnedApps ->
                PinnedAppsScreen(
                    state = state,
                    apps = apps,
                    sidebarCatalog = sidebarCatalog,
                    onRefreshCatalog = onRefreshCatalog,
                    onCancelCatalog = onCancelCatalog,
                    onBack = popBackStack,
                    onPinnedTargetsChange = onPinnedTargetsChange,
                    onSecondaryGroupSideChange = onSecondaryGroupSideChange,
                )
        }
    }
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
        AppRoute.RadialShimmer.javaClass.name -> AppRoute.RadialShimmer
        AppRoute.RadialSize.javaClass.name -> AppRoute.RadialSize
        AppRoute.RadialEffect.javaClass.name -> AppRoute.RadialEffect
        AppRoute.FreeDrag.javaClass.name -> AppRoute.FreeDrag
        AppRoute.ZoomGesture.javaClass.name -> AppRoute.ZoomGesture
        AppRoute.ZoomEdgeFloat.javaClass.name -> AppRoute.ZoomEdgeFloat
        AppRoute.About.javaClass.name -> AppRoute.About
        else -> null
    }
