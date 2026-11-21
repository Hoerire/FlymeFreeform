package io.github.hoerire.free.config

import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val radialHitRangeDp: Int = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
    /** 扇形图标基准直径（dp）：应用图标、「更多」圆盘与面板图标都按它缩放。 */
    val radialIconSizeDp: Int = ModulePreferences.DEFAULT_RADIAL_ICON_SIZE_DP,

    /** 扇形展开/收回的动画时长（毫秒）；同时驱动背景压暗与模糊的渐变量。 */
    val radialExpandDurationMs: Int = ModulePreferences.DEFAULT_RADIAL_EXPAND_DURATION_MS,
    val radialRetractDurationMs: Int = ModulePreferences.DEFAULT_RADIAL_RETRACT_DURATION_MS,
    val pinsSaved: Boolean = false,
    val pinnedTargets: List<PinnedTarget> = emptyList(),
    /**
     * 第二组扇形固定项：与 [pinnedTargets] 完全独立，同一项不得同时出现在两组。
     *
     * 默认空：升级上来的用户左右两侧仍按 [pinnedTargets] 显示，行为不变。
     */
    val alternatePinnedTargets: List<PinnedTarget> = emptyList(),
    /**
     * 第二组的生效位置：[RadialGroupSide.Disabled] 表示不启用，两侧都显示默认组。
     *
     * 默认不启用：升级上来的用户看不到任何变化；启用后只有所选那一侧换成第二组。
     */
    val secondaryGroupSide: RadialGroupSide = RadialGroupSide.Disabled,
    val outsideTapCloseMode: OutsideTapCloseMode =
        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE,
    val handleSwipeUpToMiniEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
    val pauseInLandscape: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
    val pauseInGameMode: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
    /** 屏蔽键盘：输入法弹出时暂停扇形手势（默认关闭）。 */
    val pauseInKeyboard: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_KEYBOARD,
    val verboseLogEnabled: Boolean = ModulePreferences.DEFAULT_VERBOSE_LOG,
    /** 这些应用在前台时不触发扇形菜单。 */
    val blacklistPackages: List<String> = emptyList(),
    /** 小窗扩展·数量上限：系统允许的最大小窗数至少要达到该值。 */
    val zoomMaxWindowCount: Int = ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
    /** 贴边浮标同时保留的个数（2–10）。 */
    val floatHandleMaxCount: Int = ModulePreferences.DEFAULT_FLOAT_HANDLE_MAX_COUNT,
    /** 小窗扩展·解除应用准入：isSupportFlexibleWindow 一律放行。 */
    val zoomAllowAnyApp: Boolean = ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
    // 小窗跳转按入口拆成几项，各自独立存盘、独立判定。
    // 全部默认关闭：未写入时取 false。

    /** 通知中心：点通知以小窗打开。 */
    val zoomLaunchNotice: Boolean = false,

    /** 通知横幅：横幅展开时直接以小窗呈现。 */
    val zoomLaunchHeadsUp: Boolean = false,

    /** 控制中心：磁贴长按打开的目标页面以小窗呈现。 */
    val zoomLaunchControlCenter: Boolean = false,

    /** 应用互跳：一个应用普通地拉起另一个应用（无结果回传要求）时改小窗。 */
    val zoomLaunchInterApp: Boolean = false,

    /** 复制链接：复制链接弹窗与系统分发服务代发的启动。 */
    val zoomLaunchClipboard: Boolean = false,
    /**
     * 禁用浮窗：这些应用不会被以任何形式的自由浮窗打开，作为目标、发起方或前台都算。
     *
     * 与 [blacklistPackages] 分开存：那份是「暂停模块增强」，这份只关启动改写。
     */
    val zoomExcludedPackages: List<String> = emptyList(),
    /** 背景滑条：负值为黑色遮罩浓度，正值为系统模糊，0 为不处理背景。 */
    val backdropLevel: Int = ModulePreferences.DEFAULT_BACKDROP_LEVEL,
    val autoCloseWindow: Boolean = ModulePreferences.DEFAULT_AUTO_CLOSE_WINDOW,
    /** 长按迷你窗静音，再次长按恢复；普通小窗不响应。 */
    val longPressMuteEnabled: Boolean = ModulePreferences.DEFAULT_LONG_PRESS_MUTE,
    /** 贴边挂机：收成贴边浮标后保持应用继续运行。 */
    val floatKeepAlive: Boolean = ModulePreferences.DEFAULT_FLOAT_KEEP_ALIVE,
    /** 扇形呼出后未选择任何条目时保持显示，不自动收起。 */
    val radialKeepOpen: Boolean = ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
    /** 扇形交互总开关：关闭后底角不再呼出扇形菜单，其余配置保留。 */
    val radialInteractionEnabled: Boolean = ModulePreferences.DEFAULT_RADIAL_INTERACTION_ENABLED,
    /** 流光特效：选中项外沿的动效（光圈、星头、轨迹），独立于选中光圈。 */
    val radialShimmerEnabled: Boolean = ModulePreferences.DEFAULT_RADIAL_SHIMMER_ENABLED,
    /** 选中流光的参数（速度、亮度、宽度、发光等）。 */
    val radialShimmer: RadialShimmer = RadialShimmer(),
    /** 扇形半径百分比：左右两侧同步放大或缩小。 */
    val radialRadiusPercent: Int = ModulePreferences.DEFAULT_RADIAL_RADIUS_PERCENT,
    /**
     * 解除限制：system_server 侧给没有系统白名单的应用补一份通用配置，
     * 任意页面都能长按拖起文字/图片。只影响「能不能拖」，不参与「拖给谁」。
     */
    val freeDragEnabled: Boolean = ModulePreferences.DEFAULT_FREE_DRAG,
    /** 快速分享：拖拽时弹出模块自绘的分享面板，代替官方面板。 */
    val quickShareEnabled: Boolean = ModulePreferences.DEFAULT_QUICK_SHARE,
    /** 拖拽触发延迟：系统长按阈值（毫秒），400 即官方行为。 */
    val dragLongPressMs: Int = ModulePreferences.DEFAULT_DRAG_LONG_PRESS_MS,

    // ---- 气泡通知 ----------------------------------------------------------
    /** 气泡通知总开关；与模块总开关取与，见 [bubbleActive]。 */
    val bubbleEnabled: Boolean = ModulePreferences.DEFAULT_BUBBLE_ENABLED,
    /** 主应用白名单键（包名）。 */
    val bubbleWhitelist: List<String> = emptyList(),
    /** 分身白名单键（`包名#userId`），与 [bubbleWhitelist] 各存各的。 */
    val bubbleCloneWhitelist: List<String> = emptyList(),
    val bubbleSizePercent: Int = ModulePreferences.DEFAULT_BUBBLE_SIZE_PERCENT,
    val bubbleIconPercent: Int = ModulePreferences.DEFAULT_BUBBLE_ICON_PERCENT,
    val bubbleBadgePercent: Int = ModulePreferences.DEFAULT_BUBBLE_BADGE_PERCENT,
    val bubbleTimeoutSeconds: Int = ModulePreferences.DEFAULT_BUBBLE_TIMEOUT_SECONDS,
    val bubbleDimOnTimeout: Boolean = ModulePreferences.DEFAULT_BUBBLE_DIM_ON_TIMEOUT,
    /** 内容浮层：点气泡后展开的预览卡。 */
    val bubblePreviewEnabled: Boolean = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_ENABLED,
    /** 命中气泡白名单时，接管系统横幅（不再弹横幅，只留气泡）。 */
    val bubblePreviewSuppressBanner: Boolean =
        ModulePreferences.DEFAULT_BUBBLE_PREVIEW_SUPPRESS_BANNER,
    /** 浮层只在贴边时出现（不贴边时点气泡直接打开）。 */
    val bubblePreviewEdgeOnly: Boolean = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_EDGE_ONLY,
    val bubblePreviewAlpha: Int = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_ALPHA,
    val bubblePreviewTimeoutSeconds: Int =
        ModulePreferences.DEFAULT_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
    val bubblePreviewDynamicTimeout: Boolean =
        ModulePreferences.DEFAULT_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT,
    /** 到点后气泡自动消失；关闭则只变暗，等用户处理。 */
    val bubbleAutoDismiss: Boolean = ModulePreferences.DEFAULT_BUBBLE_AUTO_DISMISS,
    /** 装饰动效是否常驻（贴边呼吸、色散边）；关闭后只在交互时重绘。 */
    val bubbleMotionContinuous: Boolean = ModulePreferences.DEFAULT_BUBBLE_MOTION_CONTINUOUS,
    /** 状态栏滚动：最新通知在时钟位置滚动显示。 */
    val statusScrollEnabled: Boolean = ModulePreferences.DEFAULT_STATUS_SCROLL_ENABLED,
    val statusScrollRounds: Int = ModulePreferences.DEFAULT_STATUS_SCROLL_ROUNDS,
    /** 免打扰：当前应用 / 游戏 / 锁屏与息屏三种场景各自独立。 */
    val bubbleSkipForeground: Boolean = ModulePreferences.DEFAULT_BUBBLE_SKIP_FOREGROUND,
    val bubbleSkipGame: Boolean = ModulePreferences.DEFAULT_BUBBLE_SKIP_GAME,
    val bubbleSkipLocked: Boolean = ModulePreferences.DEFAULT_BUBBLE_SKIP_LOCKED,
    /** 即时建议总开关。 */
    val suggestionsEnabled: Boolean = ModulePreferences.DEFAULT_SUGGESTIONS_ENABLED,
    val suggestionFlashlight: Boolean = ModulePreferences.DEFAULT_SUGGESTION_FLASHLIGHT,
    val suggestionMusic: Boolean = ModulePreferences.DEFAULT_SUGGESTION_MUSIC,
    val suggestionAppInstall: Boolean = ModulePreferences.DEFAULT_SUGGESTION_APP_INSTALL,
    val suggestionClipboard: Boolean = ModulePreferences.DEFAULT_SUGGESTION_CLIPBOARD,
    /** 常亮建议：屏幕即将休眠时可点气泡保持常亮。 */
    val suggestionKeepAwake: Boolean = ModulePreferences.DEFAULT_SUGGESTION_KEEP_AWAKE,
    /** 建议气泡不贴边（保持自由位置）。 */
    val suggestionNoEdge: Boolean = ModulePreferences.DEFAULT_SUGGESTION_NO_EDGE,
) {

    /**
     * 底角手势是否参与：模块总开关 ×「启用交互」× 至少一侧角区开启。
     *
     * 关掉「启用交互」等价于把整套底角手势关掉 —— 扇形、抢流、防穿透、
     * 桌面端上滑接管、系统手势抑制都不再介入，触摸原样交给系统。
     */
    val cornerInteractionActive: Boolean
        get() = enabled && radialInteractionEnabled &&
            (leftCornerEnabled || rightCornerEnabled)

    /**
     * 小窗相关 hook 的总闸，语义等同 [enabled]。
     *
     * 下面每个 xxxEffective 都是「总闸 && 该项开关」，hook 一律只读这些派生值：
     * 总闸关闭时整组能力都不介入系统，而不是只把界面变灰。
     * 「轻量打开」不作为独立总开关；保留该命名以避免 hook 侧全量改名。
     */
    val zoomInteractionActive: Boolean
        get() = enabled

    val zoomAllowAnyAppEffective: Boolean get() = zoomInteractionActive && zoomAllowAnyApp
    val zoomLaunchNoticeEffective: Boolean get() = zoomInteractionActive && zoomLaunchNotice
    val zoomLaunchHeadsUpEffective: Boolean get() = zoomInteractionActive && zoomLaunchHeadsUp
    val zoomLaunchControlCenterEffective: Boolean get() = zoomInteractionActive && zoomLaunchControlCenter
    val zoomLaunchInterAppEffective: Boolean get() = zoomInteractionActive && zoomLaunchInterApp
    val zoomLaunchClipboardEffective: Boolean
        get() = zoomInteractionActive && zoomLaunchClipboard
    val autoCloseWindowEffective: Boolean
        get() = zoomInteractionActive && autoCloseWindow
    val floatKeepAliveEffective: Boolean get() = zoomInteractionActive && floatKeepAlive
    /** 长按迷你窗只跟模块总开关走。 */
    val longPressMuteEffective: Boolean get() = enabled && longPressMuteEnabled
    /** 快速上滑转迷你独立于启动场景开关，只跟模块总开关走。 */
    val handleSwipeUpToMiniEffective: Boolean get() = enabled && handleSwipeUpToMiniEnabled

    /** 解除限制是否生效：模块总开关 ×「解除限制」。 */
    val freeDragActive: Boolean get() = enabled && freeDragEnabled

    /** 快速分享是否生效：模块总开关 ×「快速分享」。 */
    val quickShareActive: Boolean get() = enabled && quickShareEnabled

    /** 气泡通知是否生效：模块总开关 ×「气泡通知」。 */
    val bubbleActive: Boolean get() = enabled && bubbleEnabled

    /** 内容浮层是否生效：气泡生效 ×「内容浮层」。 */
    val bubblePreviewActive: Boolean get() = bubbleActive && bubblePreviewEnabled

    /** 状态栏滚动是否生效：随气泡功能总闸。 */
    val statusScrollActive: Boolean get() = bubbleActive && statusScrollEnabled

    /** 即时建议总闸：随气泡功能总闸。 */
    val suggestionsActive: Boolean get() = bubbleActive && suggestionsEnabled

    /** 该身份是否在气泡白名单里；主应用与分身各查各的组。 */
    fun acceptsBubble(packageName: String?, userId: Int): Boolean {
        if (!bubbleActive || packageName == null) return false
        val key = if (userId > 0) "$packageName#$userId" else packageName
        return if (userId > 0) {
            bubbleCloneWhitelist.any { it == key }
        } else {
            bubbleWhitelist.any { it == key }
        }
    }
    /** 贴边互切打开时，小窗数量交还默认值（1）。 */
    val zoomMaxWindowCountEffective: Int
        get() = if (zoomInteractionActive) zoomMaxWindowCount else ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT

    val floatHandleMaxCountEffective: Int
        get() = if (zoomInteractionActive) floatHandleMaxCount else ModulePreferences.DEFAULT_FLOAT_HANDLE_MAX_COUNT

    fun isBlacklisted(packageName: String?): Boolean =
        packageName != null && blacklistPackages.any { it == packageName }

    /** 指定分组的固定项；分组的读写点只有这一处。 */
    fun pinsFor(group: RadialGroup): List<PinnedTarget> =
        when (group) {
            RadialGroup.Primary -> pinnedTargets
            RadialGroup.Alternate -> alternatePinnedTargets
        }

    /**
     * 该目标是否属于任一组扇形固定项。
     *
     * 启动前的准入校验必须按「两组都算」来判断：只看默认组的话，
     * 第二组里的工具/快捷方式会被判成不在固定项里而静默丢弃。
     */
    fun isPinned(target: PinnedTarget): Boolean =
        pinnedTargets.any { it == target } || alternatePinnedTargets.any { it == target }

    /**
     * 某一侧底角实际使用哪一组。
     *
     * 生效位置选「不启用」时两侧一律默认组；选了某一侧后只有那一侧换成第二组。
     */
    fun groupForSide(left: Boolean): RadialGroup =
        when (secondaryGroupSide) {
            RadialGroupSide.Disabled -> RadialGroup.Primary
            RadialGroupSide.Left -> if (left) RadialGroup.Alternate else RadialGroup.Primary
            RadialGroupSide.Right -> if (left) RadialGroup.Primary else RadialGroup.Alternate
        }

    /** 该应用在前台时是否等同关闭小窗跳转。 */
    fun isZoomLaunchExcluded(packageName: String?): Boolean =
        packageName != null && zoomExcludedPackages.any { it == packageName }

    fun isPausedByEnvironment(landscape: Boolean, gameMode: Boolean): Boolean =
        (pauseInLandscape && landscape) || (pauseInGameMode && gameMode)

    fun writeTo(editor: SharedPreferences.Editor): SharedPreferences.Editor {
        editor
            .putBoolean(ModulePreferences.KEY_MODULE_ENABLED, enabled)
            .putBoolean(ModulePreferences.KEY_LEFT_CORNER_ENABLED, leftCornerEnabled)
            .putBoolean(ModulePreferences.KEY_RIGHT_CORNER_ENABLED, rightCornerEnabled)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_LANDSCAPE, pauseInLandscape)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_GAME_MODE, pauseInGameMode)
            .putBoolean(ModulePreferences.KEY_PAUSE_IN_KEYBOARD, pauseInKeyboard)
            .putBoolean(ModulePreferences.KEY_VERBOSE_LOG, verboseLogEnabled)
            .putInt(
                ModulePreferences.KEY_ZOOM_MAX_WINDOW_COUNT,
                ModulePreferences.coerceZoomMaxWindowCount(zoomMaxWindowCount),
            )
            .putInt(
                ModulePreferences.KEY_FLOAT_HANDLE_MAX_COUNT,
                ModulePreferences.coerceFloatHandleMaxCount(floatHandleMaxCount),
            )
            .putBoolean(ModulePreferences.KEY_ZOOM_ALLOW_ANY_APP, zoomAllowAnyApp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_NOTICE, zoomLaunchNotice)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_HEADS_UP, zoomLaunchHeadsUp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_CONTROL_CENTER, zoomLaunchControlCenter)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP, zoomLaunchInterApp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_CLIPBOARD, zoomLaunchClipboard)
            // 「登录授权」「应用分享」两个场景已交给官方处理，残留键一并清掉。
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_INTER_APP_RESULT)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_SHARE_RELAY)
            .putBoolean(ModulePreferences.KEY_AUTO_CLOSE_WINDOW, autoCloseWindow)
            .putBoolean(ModulePreferences.KEY_FLOAT_KEEP_ALIVE, floatKeepAlive)
            .putBoolean(ModulePreferences.KEY_LONG_PRESS_MUTE, longPressMuteEnabled)
            // 旧分项键不再写入，升级后一并清除，避免残留值干扰排查。
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_INTERACTION_ENABLED)
            .putBoolean(ModulePreferences.KEY_RADIAL_INTERACTION_ENABLED, radialInteractionEnabled)
            .putBoolean(ModulePreferences.KEY_RADIAL_KEEP_OPEN, radialKeepOpen)
            .putBoolean(ModulePreferences.KEY_RADIAL_SHIMMER_ENABLED, radialShimmerEnabled)
            .putString(ModulePreferences.KEY_RADIAL_SHIMMER, radialShimmer.encode())
            .putInt(
                ModulePreferences.KEY_RADIAL_RADIUS_PERCENT,
                ModulePreferences.coerceRadialRadiusPercent(radialRadiusPercent),
            )
            .putBoolean(ModulePreferences.KEY_FREE_DRAG, freeDragEnabled)
            .putBoolean(ModulePreferences.KEY_QUICK_SHARE, quickShareEnabled)
            .putInt(
                ModulePreferences.KEY_DRAG_LONG_PRESS_MS,
                ModulePreferences.coerceDragLongPressMs(dragLongPressMs),
            )
            .putBoolean(ModulePreferences.KEY_BUBBLE_ENABLED, bubbleEnabled)
            .putString(
                ModulePreferences.KEY_BUBBLE_WHITELIST,
                ModulePreferences.encodeBubbleKeys(bubbleWhitelist),
            )
            .putString(
                ModulePreferences.KEY_BUBBLE_CLONE_WHITELIST,
                ModulePreferences.encodeBubbleKeys(bubbleCloneWhitelist),
            )
            .putInt(
                ModulePreferences.KEY_BUBBLE_SIZE_PERCENT,
                ModulePreferences.coerceBubbleSizePercent(bubbleSizePercent),
            )
            .putInt(
                ModulePreferences.KEY_BUBBLE_ICON_PERCENT,
                ModulePreferences.coerceBubbleIconPercent(bubbleIconPercent),
            )
            .putInt(
                ModulePreferences.KEY_BUBBLE_BADGE_PERCENT,
                ModulePreferences.coerceBubbleBadgePercent(bubbleBadgePercent),
            )
            .putInt(
                ModulePreferences.KEY_BUBBLE_TIMEOUT_SECONDS,
                ModulePreferences.coerceBubbleTimeoutSeconds(bubbleTimeoutSeconds),
            )
            .putBoolean(ModulePreferences.KEY_BUBBLE_DIM_ON_TIMEOUT, bubbleDimOnTimeout)
            .remove(ModulePreferences.LEGACY_KEY_BUBBLE_DIM_ON_TIMEOUT)
            .putBoolean(ModulePreferences.KEY_BUBBLE_PREVIEW_ENABLED, bubblePreviewEnabled)
            .putBoolean(
                ModulePreferences.KEY_BUBBLE_PREVIEW_SUPPRESS_BANNER,
                bubblePreviewSuppressBanner,
            )
            .putBoolean(ModulePreferences.KEY_BUBBLE_PREVIEW_EDGE_ONLY, bubblePreviewEdgeOnly)
            .putInt(
                ModulePreferences.KEY_BUBBLE_PREVIEW_ALPHA,
                ModulePreferences.coerceBubblePreviewAlpha(bubblePreviewAlpha),
            )
            .putBoolean(ModulePreferences.KEY_BUBBLE_MOTION_CONTINUOUS, bubbleMotionContinuous)
            .putBoolean(ModulePreferences.KEY_SUGGESTIONS_ENABLED, suggestionsEnabled)
            .putBoolean(ModulePreferences.KEY_SUGGESTION_FLASHLIGHT, suggestionFlashlight)
            .putBoolean(ModulePreferences.KEY_SUGGESTION_MUSIC, suggestionMusic)
            .putBoolean(ModulePreferences.KEY_SUGGESTION_APP_INSTALL, suggestionAppInstall)
            .putBoolean(ModulePreferences.KEY_SUGGESTION_CLIPBOARD, suggestionClipboard)
            .putBoolean(ModulePreferences.KEY_SUGGESTION_KEEP_AWAKE, suggestionKeepAwake)
            .putBoolean(ModulePreferences.KEY_SUGGESTION_NO_EDGE, suggestionNoEdge)
            .putBoolean(ModulePreferences.KEY_BUBBLE_SKIP_FOREGROUND, bubbleSkipForeground)
            .putBoolean(ModulePreferences.KEY_BUBBLE_SKIP_GAME, bubbleSkipGame)
            .putBoolean(ModulePreferences.KEY_BUBBLE_SKIP_LOCKED, bubbleSkipLocked)
            .putBoolean(ModulePreferences.KEY_STATUS_SCROLL_ENABLED, statusScrollEnabled)
            .putInt(
                ModulePreferences.KEY_STATUS_SCROLL_ROUNDS,
                ModulePreferences.coerceStatusScrollRounds(statusScrollRounds),
            )
            .putInt(
                ModulePreferences.KEY_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
                ModulePreferences.coerceBubblePreviewTimeoutSeconds(bubblePreviewTimeoutSeconds),
            )
            .putBoolean(
                ModulePreferences.KEY_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT,
                bubblePreviewDynamicTimeout,
            )
            .putBoolean(ModulePreferences.KEY_BUBBLE_AUTO_DISMISS, bubbleAutoDismiss)
            .putString(
                ModulePreferences.KEY_BLACKLIST_PACKAGES,
                blacklistPackages.joinToString("\n"),
            )
            .putString(
                ModulePreferences.KEY_ZOOM_EXCLUDED_PACKAGES,
                zoomExcludedPackages.joinToString("\n"),
            )
            .putBoolean(ModulePreferences.KEY_ZOOM_EXCLUDED_LINKER_MIGRATED, true)
            .putInt(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                ModulePreferences.coerceCornerTriggerRangeDp(cornerTriggerRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_HIT_RANGE_DP,
                ModulePreferences.coerceRadialHitRangeDp(radialHitRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_BACKDROP_LEVEL,
                ModulePreferences.coerceBackdropLevel(backdropLevel),
            )
            // 分项时代的两个键不再写入，升级后一并清除。
            .remove(ModulePreferences.LEGACY_KEY_SHADE_PERCENT)
            .remove(ModulePreferences.LEGACY_KEY_RADIAL_BLUR_ENABLED)
            .putInt(
                ModulePreferences.KEY_RADIAL_ICON_SIZE_DP,
                ModulePreferences.coerceRadialIconSizeDp(radialIconSizeDp),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_EXPAND_DURATION_MS,
                ModulePreferences.coerceRadialExpandDurationMs(radialExpandDurationMs),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_RETRACT_DURATION_MS,
                ModulePreferences.coerceRadialRetractDurationMs(radialRetractDurationMs),
            )
            .putInt(
                ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                outsideTapCloseMode.storedValue,
            )
            .putBoolean(
                ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                handleSwipeUpToMiniEnabled,
            )
        if (pinsSaved) {
            editor.putString(
                ModulePreferences.KEY_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(pinnedTargets),
            )
        } else {
            editor.remove(ModulePreferences.KEY_PINNED_TARGETS)
        }
        // 第二组没有「历史迁移」概念：始终写一份（空列表写空串），读回来仍是空列表。
        editor.putString(
            ModulePreferences.KEY_PINNED_TARGETS_ALTERNATE,
            PinnedTargetCodec.encodeList(alternatePinnedTargets),
        )
        editor
            .putInt(ModulePreferences.KEY_SECONDARY_GROUP_SIDE, secondaryGroupSide.storedValue)
        editor.remove(ModulePreferences.KEY_CORNER_PINS)
        return editor
    }

    companion object {
        fun readFrom(preferences: SharedPreferences): ModuleSettingsSnapshot {
            val enabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_MODULE_ENABLED,
                    ModulePreferences.DEFAULT_ENABLED,
                )
            val leftEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_LEFT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val rightEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_RIGHT_CORNER_ENABLED,
                    ModulePreferences.DEFAULT_CORNER_ENABLED,
                )
            val cornerTriggerRangeDp =
                ModulePreferences.coerceCornerTriggerRangeDp(
                    preferences.getInt(
                        ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                        ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
                    ),
                )
            val radialHitRangeDp =
                ModulePreferences.coerceRadialHitRangeDp(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_HIT_RANGE_DP,
                        ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
                    ),
                )
            val radialExpandDurationMs =
                ModulePreferences.coerceRadialExpandDurationMs(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_EXPAND_DURATION_MS,
                        ModulePreferences.DEFAULT_RADIAL_EXPAND_DURATION_MS,
                    ),
                )
            val radialRetractDurationMs =
                ModulePreferences.coerceRadialRetractDurationMs(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_RETRACT_DURATION_MS,
                        ModulePreferences.DEFAULT_RADIAL_RETRACT_DURATION_MS,
                    ),
                )
            val outsideTapCloseMode =
                OutsideTapCloseMode.fromStoredValue(
                    preferences.getInt(
                        ModulePreferences.KEY_OUTSIDE_TAP_CLOSE_MODE,
                        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE.storedValue,
                    ),
                )
            val handleSwipeUpToMiniEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                    ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
                )
            val hasTargets = preferences.contains(ModulePreferences.KEY_PINNED_TARGETS)
            val pinsSaved = hasTargets || preferences.contains(ModulePreferences.KEY_CORNER_PINS)
            val pins = if (hasTargets) {
                PinnedTargetCodec.decodeList(preferences.getString(ModulePreferences.KEY_PINNED_TARGETS, "").orEmpty())
            } else {
                PinnedTargetCodec.decodeLegacy(preferences.getString(ModulePreferences.KEY_CORNER_PINS, "").orEmpty())
            }
            val alternatePins =
                PinnedTargetCodec.decodeList(
                    preferences.getString(ModulePreferences.KEY_PINNED_TARGETS_ALTERNATE, "").orEmpty(),
                )
            val secondaryGroupSide =
                RadialGroupSide.fromStoredValue(
                    preferences.getInt(
                        ModulePreferences.KEY_SECONDARY_GROUP_SIDE,
                        RadialGroupSide.Disabled.storedValue,
                    ),
                )
            val bothCornersDisabled = !leftEnabled && !rightEnabled
            // 老配置迁移（升级路径）：
            // 1. 总开关「轻量打开」为停用状态但仍有值且为关 —— 说明升级前用户把小窗能力整体关着，
            //    那么所有场景都按关处理；第一次保存设置时这个键会被清掉，之后不再干预。
            // 2. 新拆出来的场景键（复制链接）没存过时继承旧「应用跳转」的值，
            //    避免升级后用户原本能小窗的场景突然变全屏。
            val legacyMaster =
                runCatching {
                    if (!preferences.contains(ModulePreferences.LEGACY_KEY_ZOOM_INTERACTION_ENABLED)) {
                        true
                    } else {
                        preferences.getBoolean(
                            ModulePreferences.LEGACY_KEY_ZOOM_INTERACTION_ENABLED,
                            true,
                        )
                    }
                }.getOrDefault(true)
            val legacyInterApp =
                preferences.getBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP, false)
            fun scene(key: String): Boolean = legacyMaster && preferences.getBoolean(key, false)
            // 没存过的新键继承旧值；存过就以用户的选择为准。
            fun sceneInherit(key: String): Boolean =
                legacyMaster && preferences.getBoolean(key, legacyInterApp)
            return ModuleSettingsSnapshot(
                enabled = enabled,
                zoomLaunchNotice = scene(ModulePreferences.KEY_ZOOM_LAUNCH_NOTICE),
                zoomLaunchHeadsUp = scene(ModulePreferences.KEY_ZOOM_LAUNCH_HEADS_UP),
                zoomLaunchControlCenter = scene(ModulePreferences.KEY_ZOOM_LAUNCH_CONTROL_CENTER),
                zoomLaunchInterApp = scene(ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP),
                zoomLaunchClipboard = sceneInherit(ModulePreferences.KEY_ZOOM_LAUNCH_CLIPBOARD),
                leftCornerEnabled = if (bothCornersDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothCornersDisabled) true else rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                radialHitRangeDp = radialHitRangeDp,
                radialExpandDurationMs = radialExpandDurationMs,
                radialRetractDurationMs = radialRetractDurationMs,
                backdropLevel =
                    ModulePreferences.coerceBackdropLevel(
                        preferences.getInt(
                            ModulePreferences.KEY_BACKDROP_LEVEL,
                            // 分项时代：开着模糊的按那一版的固定半径折成正值，
                            // 否则把遮罩浓度折成负值；两者都没设过即不处理背景。
                            if (
                                preferences.getBoolean(
                                    ModulePreferences.LEGACY_KEY_RADIAL_BLUR_ENABLED,
                                    false,
                                )
                            ) {
                                ModulePreferences.LEGACY_BLUR_LEVEL
                            } else {
                                -preferences.getInt(ModulePreferences.LEGACY_KEY_SHADE_PERCENT, 0)
                            },
                        ),
                    ),
                radialIconSizeDp =
                    ModulePreferences.coerceRadialIconSizeDp(
                        preferences.getInt(
                            ModulePreferences.KEY_RADIAL_ICON_SIZE_DP,
                            ModulePreferences.DEFAULT_RADIAL_ICON_SIZE_DP,
                        ),
                    ),
                pinsSaved = pinsSaved,
                pinnedTargets = pins,
                alternatePinnedTargets = alternatePins,
                secondaryGroupSide = secondaryGroupSide,
                outsideTapCloseMode = outsideTapCloseMode,
                handleSwipeUpToMiniEnabled = handleSwipeUpToMiniEnabled,
                pauseInLandscape = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_LANDSCAPE,
                    ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
                ),
                pauseInGameMode = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_GAME_MODE,
                    ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
                ),
                pauseInKeyboard = preferences.getBoolean(
                    ModulePreferences.KEY_PAUSE_IN_KEYBOARD,
                    ModulePreferences.DEFAULT_PAUSE_IN_KEYBOARD,
                ),
                verboseLogEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_VERBOSE_LOG,
                    ModulePreferences.DEFAULT_VERBOSE_LOG,
                ),
                blacklistPackages = ModulePreferences.decodePackages(
                    preferences.getString(ModulePreferences.KEY_BLACKLIST_PACKAGES, null),
                ),
                zoomExcludedPackages =
                    ModulePreferences.decodePackages(
                        preferences.getString(ModulePreferences.KEY_ZOOM_EXCLUDED_PACKAGES, null),
                    ).let { stored ->
                        // 设备互联默认屏蔽：老配置一次性补进去；用户手动删掉后写回标记，
                        // 之后不再自动加回，避免“删了又回来”。
                        val migrated =
                            preferences.getBoolean(
                                ModulePreferences.KEY_ZOOM_EXCLUDED_LINKER_MIGRATED,
                                false,
                            )
                        if (!migrated && ModulePreferences.DEVICE_LINK_PACKAGE !in stored) {
                            stored + ModulePreferences.DEVICE_LINK_PACKAGE
                        } else {
                            stored
                        }
                    },
                zoomMaxWindowCount =
                    ModulePreferences.coerceZoomMaxWindowCount(
                        preferences.getInt(
                            ModulePreferences.KEY_ZOOM_MAX_WINDOW_COUNT,
                            ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
                        ),
                    ),
                floatHandleMaxCount =
                    ModulePreferences.coerceFloatHandleMaxCount(
                        preferences.getInt(
                            ModulePreferences.KEY_FLOAT_HANDLE_MAX_COUNT,
                            ModulePreferences.DEFAULT_FLOAT_HANDLE_MAX_COUNT,
                        ),
                    ),
                zoomAllowAnyApp = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_ALLOW_ANY_APP,
                    ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
                ),
                autoCloseWindow = preferences.getBoolean(
                    ModulePreferences.KEY_AUTO_CLOSE_WINDOW,
                    ModulePreferences.DEFAULT_AUTO_CLOSE_WINDOW,
                ),
                floatKeepAlive = preferences.getBoolean(
                    ModulePreferences.KEY_FLOAT_KEEP_ALIVE,
                    ModulePreferences.DEFAULT_FLOAT_KEEP_ALIVE,
                ),
                longPressMuteEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_LONG_PRESS_MUTE,
                    ModulePreferences.DEFAULT_LONG_PRESS_MUTE,
                ),
                radialKeepOpen = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_KEEP_OPEN,
                    ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
                ),
                radialInteractionEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_INTERACTION_ENABLED,
                    ModulePreferences.DEFAULT_RADIAL_INTERACTION_ENABLED,
                ),
                radialShimmerEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_SHIMMER_ENABLED,
                    ModulePreferences.DEFAULT_RADIAL_SHIMMER_ENABLED,
                ),
                radialShimmer = RadialShimmer.decode(
                    preferences.getString(ModulePreferences.KEY_RADIAL_SHIMMER, null),
                ),
                radialRadiusPercent = ModulePreferences.coerceRadialRadiusPercent(
                    preferences.getInt(
                        ModulePreferences.KEY_RADIAL_RADIUS_PERCENT,
                        ModulePreferences.DEFAULT_RADIAL_RADIUS_PERCENT,
                    ),
                ),
                freeDragEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_FREE_DRAG,
                    ModulePreferences.DEFAULT_FREE_DRAG,
                ),
                quickShareEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_QUICK_SHARE,
                    ModulePreferences.DEFAULT_QUICK_SHARE,
                ),
                dragLongPressMs = preferences.getInt(
                    ModulePreferences.KEY_DRAG_LONG_PRESS_MS,
                    ModulePreferences.DEFAULT_DRAG_LONG_PRESS_MS,
                ),
                bubbleEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_ENABLED,
                    ModulePreferences.DEFAULT_BUBBLE_ENABLED,
                ),
                bubbleWhitelist = ModulePreferences.decodeBubbleKeys(
                    preferences.getString(ModulePreferences.KEY_BUBBLE_WHITELIST, null),
                ),
                bubbleCloneWhitelist = ModulePreferences.decodeBubbleKeys(
                    preferences.getString(ModulePreferences.KEY_BUBBLE_CLONE_WHITELIST, null),
                ),
                bubbleSizePercent = ModulePreferences.coerceBubbleSizePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_BUBBLE_SIZE_PERCENT,
                        ModulePreferences.DEFAULT_BUBBLE_SIZE_PERCENT,
                    ),
                ),
                bubbleIconPercent = ModulePreferences.coerceBubbleIconPercent(
                    preferences.getInt(
                        ModulePreferences.KEY_BUBBLE_ICON_PERCENT,
                        ModulePreferences.DEFAULT_BUBBLE_ICON_PERCENT,
                    ),
                ),
                bubbleBadgePercent = ModulePreferences.coerceBubbleBadgePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_BUBBLE_BADGE_PERCENT,
                        ModulePreferences.DEFAULT_BUBBLE_BADGE_PERCENT,
                    ),
                ),
                bubbleTimeoutSeconds = ModulePreferences.coerceBubbleTimeoutSeconds(
                    preferences.getInt(
                        ModulePreferences.KEY_BUBBLE_TIMEOUT_SECONDS,
                        ModulePreferences.DEFAULT_BUBBLE_TIMEOUT_SECONDS,
                    ),
                ),
                bubbleDimOnTimeout = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_DIM_ON_TIMEOUT,
                    ModulePreferences.DEFAULT_BUBBLE_DIM_ON_TIMEOUT,
                ),
                bubblePreviewEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_PREVIEW_ENABLED,
                    ModulePreferences.DEFAULT_BUBBLE_PREVIEW_ENABLED,
                ),
                bubblePreviewSuppressBanner = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_PREVIEW_SUPPRESS_BANNER,
                    ModulePreferences.DEFAULT_BUBBLE_PREVIEW_SUPPRESS_BANNER,
                ),
                bubblePreviewEdgeOnly = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_PREVIEW_EDGE_ONLY,
                    ModulePreferences.DEFAULT_BUBBLE_PREVIEW_EDGE_ONLY,
                ),
                bubblePreviewAlpha = ModulePreferences.coerceBubblePreviewAlpha(
                    preferences.getInt(
                        ModulePreferences.KEY_BUBBLE_PREVIEW_ALPHA,
                        ModulePreferences.DEFAULT_BUBBLE_PREVIEW_ALPHA,
                    ),
                ),
                bubbleMotionContinuous = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_MOTION_CONTINUOUS,
                    ModulePreferences.DEFAULT_BUBBLE_MOTION_CONTINUOUS,
                ),
                statusScrollEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_STATUS_SCROLL_ENABLED,
                    ModulePreferences.DEFAULT_STATUS_SCROLL_ENABLED,
                ),
                statusScrollRounds = ModulePreferences.coerceStatusScrollRounds(
                    preferences.getInt(
                        ModulePreferences.KEY_STATUS_SCROLL_ROUNDS,
                        ModulePreferences.DEFAULT_STATUS_SCROLL_ROUNDS,
                    ),
                ),
                suggestionsEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTIONS_ENABLED,
                    ModulePreferences.DEFAULT_SUGGESTIONS_ENABLED,
                ),
                suggestionFlashlight = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTION_FLASHLIGHT,
                    ModulePreferences.DEFAULT_SUGGESTION_FLASHLIGHT,
                ),
                suggestionMusic = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTION_MUSIC,
                    ModulePreferences.DEFAULT_SUGGESTION_MUSIC,
                ),
                suggestionAppInstall = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTION_APP_INSTALL,
                    ModulePreferences.DEFAULT_SUGGESTION_APP_INSTALL,
                ),
                suggestionClipboard = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTION_CLIPBOARD,
                    ModulePreferences.DEFAULT_SUGGESTION_CLIPBOARD,
                ),
                suggestionKeepAwake = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTION_KEEP_AWAKE,
                    ModulePreferences.DEFAULT_SUGGESTION_KEEP_AWAKE,
                ),
                suggestionNoEdge = preferences.getBoolean(
                    ModulePreferences.KEY_SUGGESTION_NO_EDGE,
                    ModulePreferences.DEFAULT_SUGGESTION_NO_EDGE,
                ),
                bubbleSkipForeground = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_SKIP_FOREGROUND,
                    ModulePreferences.DEFAULT_BUBBLE_SKIP_FOREGROUND,
                ),
                bubbleSkipGame = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_SKIP_GAME,
                    ModulePreferences.DEFAULT_BUBBLE_SKIP_GAME,
                ),
                bubbleSkipLocked = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_SKIP_LOCKED,
                    ModulePreferences.DEFAULT_BUBBLE_SKIP_LOCKED,
                ),
                bubblePreviewTimeoutSeconds = ModulePreferences.coerceBubblePreviewTimeoutSeconds(
                    preferences.getInt(
                        ModulePreferences.KEY_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
                        ModulePreferences.DEFAULT_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
                    ),
                ),
                bubblePreviewDynamicTimeout = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT,
                    ModulePreferences.DEFAULT_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT,
                ),
                bubbleAutoDismiss = preferences.getBoolean(
                    ModulePreferences.KEY_BUBBLE_AUTO_DISMISS,
                    ModulePreferences.DEFAULT_BUBBLE_AUTO_DISMISS,
                ),
            )
        }

    }
}
