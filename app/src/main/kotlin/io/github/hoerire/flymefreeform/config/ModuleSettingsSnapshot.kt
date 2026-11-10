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
    /**
     * 小窗交互总开关（界面名「启用交互」）。
     *
     * 默认关闭。关闭时下面所有小窗相关能力都不介入系统：hook 里一律读 [zoomInteractionActive]
     * 派生的有效值，等于把整页能力整体摘掉，不会和别的模块抢同一批入口。
     * 唯独「窗外关闭」不受它控制。
     */
    val zoomInteractionEnabled: Boolean = ModulePreferences.DEFAULT_ZOOM_INTERACTION_ENABLED,
    /** 小窗扩展·数量上限：系统允许的最大小窗数至少要达到该值。 */
    val zoomMaxWindowCount: Int = ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
    /** 小窗扩展·解除应用准入：isSupportFlexibleWindow 一律放行。 */
    val zoomAllowAnyApp: Boolean = ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
    /**
     * 小窗扩展·小窗跳转：通知、控制中心、应用互跳、桌面快捷项与卡片统一以自由浮窗启动。
     *
     * 关闭后不改写任何启动，全部入口回到系统默认的全屏行为。
     */
    val zoomLaunch: Boolean = ModulePreferences.DEFAULT_ZOOM_LAUNCH,
    /**
     * 禁用浮窗：这些应用不会被以任何形式的自由浮窗打开，作为目标、发起方或前台都算。
     *
     * 与 [blacklistPackages] 分开存：那份是「暂停模块增强」，这份只关启动改写。
     */
    val zoomExcludedPackages: List<String> = emptyList(),
    /** 桌面长按快捷项转小窗；false 时保持全屏。 */
    val zoomLauncherShortcut: Boolean = ModulePreferences.DEFAULT_ZOOM_LAUNCHER_SHORTCUT,
    val autoCloseWindow: Boolean = ModulePreferences.DEFAULT_AUTO_CLOSE_WINDOW,
    /** 桌面卡片与负一屏转小窗；false 时保持全屏。 */
    val zoomLauncherCard: Boolean = ModulePreferences.DEFAULT_ZOOM_LAUNCHER_CARD,
    /** 应用选择列表是否列出系统应用（本机生效，不影响 Hook 判定）。 */
    val showSystemApps: Boolean = ModulePreferences.DEFAULT_SHOW_SYSTEM_APPS,
    /** 扇形呼出后未选择任何条目时保持显示，不自动收起。 */
    val radialKeepOpen: Boolean = ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
) {

    /** 模块总开关 + 小窗交互总开关同时打开，才允许小窗相关 hook 介入。 */
    val zoomInteractionActive: Boolean
        get() = enabled && zoomInteractionEnabled

    val zoomAllowAnyAppEffective: Boolean get() = zoomInteractionActive && zoomAllowAnyApp
    val zoomLaunchEffective: Boolean get() = zoomInteractionActive && zoomLaunch
    val zoomLauncherShortcutEffective: Boolean get() = zoomInteractionActive && zoomLauncherShortcut
    val zoomLauncherCardEffective: Boolean get() = zoomInteractionActive && zoomLauncherCard
    val autoCloseWindowEffective: Boolean get() = zoomInteractionActive && autoCloseWindow
    /** 快速上滑转迷你独立于「轻量打开」，只跟模块总开关走。 */
    val handleSwipeUpToMiniEffective: Boolean get() = enabled && handleSwipeUpToMiniEnabled
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
            .putBoolean(
                ModulePreferences.KEY_ZOOM_INTERACTION_ENABLED,
                zoomInteractionEnabled,
            )
            .putBoolean(ModulePreferences.KEY_ZOOM_ALLOW_ANY_APP, zoomAllowAnyApp)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH, zoomLaunch)
            .putBoolean(
                ModulePreferences.KEY_ZOOM_LAUNCHER_SHORTCUT,
                zoomLauncherShortcut,
            )
            .putBoolean(ModulePreferences.KEY_AUTO_CLOSE_WINDOW, autoCloseWindow)
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCHER_CARD, zoomLauncherCard)
            .putBoolean(ModulePreferences.KEY_SHOW_SYSTEM_APPS, showSystemApps)
            // 旧分项键不再写入，升级后一并清除，避免残留值干扰排查。
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER)
            .remove(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_INTER_APP)
            .putBoolean(ModulePreferences.KEY_RADIAL_KEEP_OPEN, radialKeepOpen)
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
            val zoomInteractionEnabled =
                preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_INTERACTION_ENABLED,
                    ModulePreferences.DEFAULT_ZOOM_INTERACTION_ENABLED,
                )
            return ModuleSettingsSnapshot(
                enabled = enabled,
                zoomInteractionEnabled = zoomInteractionEnabled,
                leftCornerEnabled = if (bothCornersDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothCornersDisabled) true else rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                radialHitRangeDp = radialHitRangeDp,
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
                zoomLaunch = readZoomLaunch(preferences),
                autoCloseWindow = preferences.getBoolean(
                    ModulePreferences.KEY_AUTO_CLOSE_WINDOW,
                    ModulePreferences.DEFAULT_AUTO_CLOSE_WINDOW,
                ),
            zoomLauncherShortcut = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_LAUNCHER_SHORTCUT,
                    ModulePreferences.DEFAULT_ZOOM_LAUNCHER_SHORTCUT,
                ),
                zoomLauncherCard = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_LAUNCHER_CARD,
                    ModulePreferences.DEFAULT_ZOOM_LAUNCHER_CARD,
                ),
                showSystemApps = preferences.getBoolean(
                    ModulePreferences.KEY_SHOW_SYSTEM_APPS,
                    ModulePreferences.DEFAULT_SHOW_SYSTEM_APPS,
                ),
                radialKeepOpen = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_KEEP_OPEN,
                    ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
                ),
            )
        }

        /**
         * 读取小窗跳转总开关。
         *
         * 升级前是三个分项开关，任一项为开即视为开启；由此第一次读取会把旧值合并进总开关，
         * 之后只认新键。
         */
        private fun readZoomLaunch(preferences: SharedPreferences): Boolean {
            val legacy =
                preferences.getBoolean(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION, false) ||
                    preferences.getBoolean(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER, false) ||
                    preferences.getBoolean(ModulePreferences.LEGACY_KEY_ZOOM_LAUNCH_INTER_APP, false)
            return preferences.getBoolean(ModulePreferences.KEY_ZOOM_LAUNCH, legacy)
        }
    }
}
