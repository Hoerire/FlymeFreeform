package io.github.hoerire.flymefreeform.config

import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val radialHitRangeDp: Int = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
    /** 扇形图标基准直径（dp）：应用图标、「更多」圆盘与面板图标都按它缩放。 */
    val radialIconSizeDp: Int = ModulePreferences.DEFAULT_RADIAL_ICON_SIZE_DP,
    val pinsSaved: Boolean = false,
    val pinnedTargets: List<PinnedTarget> = emptyList(),
    val outsideTapCloseMode: OutsideTapCloseMode =
        ModulePreferences.DEFAULT_OUTSIDE_TAP_CLOSE_MODE,
    val handleSwipeUpToMiniEnabled: Boolean =
        ModulePreferences.DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED,
    val pauseInLandscape: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_LANDSCAPE,
    val pauseInGameMode: Boolean = ModulePreferences.DEFAULT_PAUSE_IN_GAME_MODE,
    val verboseLogEnabled: Boolean = ModulePreferences.DEFAULT_VERBOSE_LOG,
    /** 这些应用在前台时不触发扇形菜单。 */
    val blacklistPackages: List<String> = emptyList(),
    /** 小窗扩展·数量上限：系统允许的最大小窗数至少要达到该值。 */
    val zoomMaxWindowCount: Int = ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
    /** 小窗扩展·解除应用准入：isSupportFlexibleWindow 一律放行。 */
    val zoomAllowAnyApp: Boolean = ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
    // 小窗跳转按入口拆成四项，各自独立存盘、独立判定。
    // 全部默认关闭：未写入时取 false，不继承旧版那个已被移除的单一开关。

    /** 通知中心：点通知以小窗打开。 */
    val zoomLaunchNotice: Boolean = false,

    /** 通知横幅：横幅展开时直接以小窗呈现。 */
    val zoomLaunchHeadsUp: Boolean = false,

    /** 控制中心：磁贴长按打开的目标页面以小窗呈现。 */
    val zoomLaunchControlCenter: Boolean = false,

    /** 应用互跳：一个应用普通地拉起另一个应用（无结果回传要求）时改小窗。 */
    val zoomLaunchInterApp: Boolean = false,

    /** 带回传的跳转：登录、授权、流程页等携带 resultTo 的跨应用启动。 */
    val zoomLaunchInterAppResult: Boolean = false,

    /** 分享跳转：分享给另一个应用时改小窗（走模块自己的 zoom 启动）。 */
    val zoomLaunchShareRelay: Boolean = false,

    /** 系统代发：system_server 发起的应用启动。 */
    val zoomLaunchSystem: Boolean = false,

    /** 复制链接：复制链接弹窗与系统分发服务代发的启动。 */
    val zoomLaunchClipboard: Boolean = false,
    /**
     * 禁用浮窗：这些应用不会被以任何形式的自由浮窗打开，作为目标、发起方或前台都算。
     *
     * 与 [blacklistPackages] 分开存：那份是「暂停模块增强」，这份只关启动改写。
     */
    val zoomExcludedPackages: List<String> = emptyList(),
    /** 黑色遮罩浓度（百分比），0 为完全透明。 */
    val shadePercent: Int = ModulePreferences.DEFAULT_SHADE_PERCENT,
    val autoCloseWindow: Boolean = ModulePreferences.DEFAULT_AUTO_CLOSE_WINDOW,
    /** 长按迷你窗：长按放大、再长按还原。 */
    val longPressMuteEnabled: Boolean = ModulePreferences.DEFAULT_LONG_PRESS_MUTE,
    /** 贴边保活：收成贴边浮标后保持应用继续运行。 */
    val floatKeepAlive: Boolean = ModulePreferences.DEFAULT_FLOAT_KEEP_ALIVE,
    /** 应用选择列表是否列出系统应用（本机生效，不影响 Hook 判定）。 */
    val showSystemApps: Boolean = ModulePreferences.DEFAULT_SHOW_SYSTEM_APPS,
    /** 扇形呼出后未选择任何条目时保持显示，不自动收起。 */
    val radialKeepOpen: Boolean = ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
    /**
     * 自由拖拽：解除内容传送门的长按拖拽准入限制。
     *
     * 打开后 system_server 侧会给没有系统白名单的应用补一份通用配置，
     * 任意页面都能长按拖起文字/图片。只影响「能不能拖」，不参与「拖给谁」。
     */
    val freeDragEnabled: Boolean = ModulePreferences.DEFAULT_FREE_DRAG,
    /** 拖拽触发延迟：系统长按阈值（毫秒），400 即官方行为。 */
    val dragLongPressMs: Int = ModulePreferences.DEFAULT_DRAG_LONG_PRESS_MS,
) {

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
    val zoomLaunchInterAppResultEffective: Boolean
        get() = zoomInteractionActive && zoomLaunchInterAppResult
    val zoomLaunchShareRelayEffective: Boolean get() = zoomInteractionActive && zoomLaunchShareRelay
    val zoomLaunchSystemEffective: Boolean get() = zoomInteractionActive && zoomLaunchSystem
    val zoomLaunchClipboardEffective: Boolean
        get() = zoomInteractionActive && zoomLaunchClipboard
    val autoCloseWindowEffective: Boolean get() = zoomInteractionActive && autoCloseWindow
    val floatKeepAliveEffective: Boolean get() = zoomInteractionActive && floatKeepAlive
    /** 长按迷你窗只跟模块总开关走。 */
    val longPressMuteEffective: Boolean get() = enabled && longPressMuteEnabled
    /** 快速上滑转迷你独立于启动场景开关，只跟模块总开关走。 */
    val handleSwipeUpToMiniEffective: Boolean get() = enabled && handleSwipeUpToMiniEnabled

    /** 自由拖拽只跟模块总开关走：它和自由浮窗没有依赖关系。 */
    val freeDragActive: Boolean get() = enabled && freeDragEnabled
    val zoomMaxWindowCountEffective: Int
        get() = if (zoomInteractionActive) zoomMaxWindowCount else ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT

    fun isBlacklisted(packageName: String?): Boolean =
        packageName != null && blacklistPackages.any { it == packageName }

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
            .putBoolean(ModulePreferences.KEY_VERBOSE_LOG, verboseLogEnabled)
            .putInt(
                ModulePreferences.KEY_ZOOM_MAX_WINDOW_COUNT,
                ModulePreferences.coerceZoomMaxWindowCount(zoomMaxWindowCount),
            )
            .putBoolean(ModulePreferences.KEY_ZOOM_ALLOW_ANY_APP, zoomAllowAnyApp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_NOTICE, zoomLaunchNotice)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_HEADS_UP, zoomLaunchHeadsUp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_CONTROL_CENTER, zoomLaunchControlCenter)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP, zoomLaunchInterApp)
            .putBoolean(
                ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP_RESULT,
                zoomLaunchInterAppResult,
            )
            .putBoolean(
                ModulePreferences.KEY_ZOOM_LAUNCH_SHARE_RELAY,
                zoomLaunchShareRelay,
            )
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_SYSTEM, zoomLaunchSystem)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_CLIPBOARD, zoomLaunchClipboard)
            .putBoolean(ModulePreferences.KEY_AUTO_CLOSE_WINDOW, autoCloseWindow)
            .putBoolean(ModulePreferences.KEY_FLOAT_KEEP_ALIVE, floatKeepAlive)
            .putBoolean(ModulePreferences.KEY_LONG_PRESS_MUTE, longPressMuteEnabled)
            .putBoolean(ModulePreferences.KEY_SHOW_SYSTEM_APPS, showSystemApps)
            // 旧分项键不再写入，升级后一并清除，避免残留值干扰排查。
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_INTERACTION_ENABLED)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_INTER_APP)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCHER_SHORTCUT)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCHER_CARD)
            .putBoolean(ModulePreferences.KEY_RADIAL_KEEP_OPEN, radialKeepOpen)
            .putBoolean(ModulePreferences.KEY_FREE_DRAG, freeDragEnabled)
            .putInt(
                ModulePreferences.KEY_DRAG_LONG_PRESS_MS,
                ModulePreferences.coerceDragLongPressMs(dragLongPressMs),
            )
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
                ModulePreferences.KEY_SHADE_PERCENT,
                ModulePreferences.coerceShadePercent(shadePercent),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_ICON_SIZE_DP,
                ModulePreferences.coerceRadialIconSizeDp(radialIconSizeDp),
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
            val bothCornersDisabled = !leftEnabled && !rightEnabled
            // 老配置迁移（升级路径）：
            // 1. 总开关「轻量打开」为停用状态但仍有值且为关 —— 说明升级前用户把小窗能力整体关着，
            //    那么所有场景都按关处理；第一次保存设置时这个键会被清掉，之后不再干预。
            // 2. 新拆出来的场景键（登录授权 / 系统代发 / 复制链接）没存过时继承旧「应用跳转」
            //    的值，避免升级后用户原本能小窗的场景突然变全屏。
            // 3. 「应用分享」为新增能力，不继承旧值：其旧行为表现为全屏，直接打开会造成形态突变。
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
                zoomLaunchInterAppResult =
                    sceneInherit(ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP_RESULT),
                zoomLaunchShareRelay = scene(ModulePreferences.KEY_ZOOM_LAUNCH_SHARE_RELAY),
                zoomLaunchSystem = sceneInherit(ModulePreferences.KEY_ZOOM_LAUNCH_SYSTEM),
                zoomLaunchClipboard = sceneInherit(ModulePreferences.KEY_ZOOM_LAUNCH_CLIPBOARD),
                leftCornerEnabled = if (bothCornersDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothCornersDisabled) true else rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                radialHitRangeDp = radialHitRangeDp,
                shadePercent = ModulePreferences.coerceShadePercent(
                    preferences.getInt(
                        ModulePreferences.KEY_SHADE_PERCENT,
                        ModulePreferences.DEFAULT_SHADE_PERCENT,
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
                showSystemApps = preferences.getBoolean(
                    ModulePreferences.KEY_SHOW_SYSTEM_APPS,
                    ModulePreferences.DEFAULT_SHOW_SYSTEM_APPS,
                ),
                radialKeepOpen = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_KEEP_OPEN,
                    ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
                ),
                freeDragEnabled = preferences.getBoolean(
                    ModulePreferences.KEY_FREE_DRAG,
                    ModulePreferences.DEFAULT_FREE_DRAG,
                ),
                dragLongPressMs = preferences.getInt(
                    ModulePreferences.KEY_DRAG_LONG_PRESS_MS,
                    ModulePreferences.DEFAULT_DRAG_LONG_PRESS_MS,
                ),
            )
        }

    }
}
