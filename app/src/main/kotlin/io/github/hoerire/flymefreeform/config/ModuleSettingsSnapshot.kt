package io.github.hoerire.flymefreeform.config

import android.content.SharedPreferences

internal data class ModuleSettingsSnapshot(
    val enabled: Boolean = ModulePreferences.DEFAULT_ENABLED,
    val leftCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val rightCornerEnabled: Boolean = ModulePreferences.DEFAULT_CORNER_ENABLED,
    val cornerTriggerRangeDp: Int = ModulePreferences.DEFAULT_CORNER_TRIGGER_RANGE_DP,
    val radialHitRangeDp: Int = ModulePreferences.DEFAULT_RADIAL_HIT_RANGE_DP,
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
    /** 小窗优化·数量上限：系统允许的最大小窗数至少要达到该值。 */
    val zoomMaxWindowCount: Int = ModulePreferences.DEFAULT_ZOOM_MAX_WINDOW_COUNT,
    /** 小窗优化·解除应用准入：isSupportFlexibleWindow 一律放行。 */
    val zoomAllowAnyApp: Boolean = ModulePreferences.DEFAULT_ZOOM_ALLOW_ANY_APP,
    /** 小窗优化·通知与横幅：点击通知栏或横幅时以自由浮窗打开。 */
    val zoomLaunchNotification: Boolean = ModulePreferences.DEFAULT_ZOOM_LAUNCH_NOTIFICATION,
    /** 小窗优化·控制中心：磁贴、应用快捷方式启动时以自由浮窗打开。 */
    val zoomLaunchControlCenter: Boolean =
        ModulePreferences.DEFAULT_ZOOM_LAUNCH_CONTROL_CENTER,
    /** 小窗优化·应用跳转：应用之间互相打开时以自由浮窗启动。 */
    val zoomLaunchInterApp: Boolean = ModulePreferences.DEFAULT_ZOOM_LAUNCH_INTER_APP,
    /** 扇形呼出后未选择任何条目时保持显示，不自动收起。 */
    val radialKeepOpen: Boolean = ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
    val radialMaxItems: Int = ModulePreferences.DEFAULT_RADIAL_MAX_ITEMS,
) {

    fun isBlacklisted(packageName: String?): Boolean =
        packageName != null && blacklistPackages.any { it == packageName }

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
            .putBoolean(ModulePreferences.KEY_ZOOM_LAUNCH_NOTIFICATION, zoomLaunchNotification)
            .putBoolean(
                ModulePreferences.KEY_ZOOM_LAUNCH_CONTROL_CENTER,
                zoomLaunchControlCenter,
            )
            .putBoolean(
                ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP,
                zoomLaunchInterApp,
            )
            .putBoolean(ModulePreferences.KEY_RADIAL_KEEP_OPEN, radialKeepOpen)
            .putInt(
                ModulePreferences.KEY_RADIAL_MAX_ITEMS,
                ModulePreferences.coerceRadialMaxItems(radialMaxItems),
            )
            .putString(
                ModulePreferences.KEY_BLACKLIST_PACKAGES,
                blacklistPackages.joinToString("\n"),
            )
            .putInt(
                ModulePreferences.KEY_CORNER_TRIGGER_RANGE_DP,
                ModulePreferences.coerceCornerTriggerRangeDp(cornerTriggerRangeDp),
            )
            .putInt(
                ModulePreferences.KEY_RADIAL_HIT_RANGE_DP,
                ModulePreferences.coerceRadialHitRangeDp(radialHitRangeDp),
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
            return ModuleSettingsSnapshot(
                enabled = enabled,
                leftCornerEnabled = if (bothCornersDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothCornersDisabled) true else rightEnabled,
                cornerTriggerRangeDp = cornerTriggerRangeDp,
                radialHitRangeDp = radialHitRangeDp,
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
                zoomLaunchNotification = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_LAUNCH_NOTIFICATION,
                    ModulePreferences.DEFAULT_ZOOM_LAUNCH_NOTIFICATION,
                ),
                zoomLaunchControlCenter = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_LAUNCH_CONTROL_CENTER,
                    ModulePreferences.DEFAULT_ZOOM_LAUNCH_CONTROL_CENTER,
                ),
                zoomLaunchInterApp = preferences.getBoolean(
                    ModulePreferences.KEY_ZOOM_LAUNCH_INTER_APP,
                    ModulePreferences.DEFAULT_ZOOM_LAUNCH_INTER_APP,
                ),
                radialKeepOpen = preferences.getBoolean(
                    ModulePreferences.KEY_RADIAL_KEEP_OPEN,
                    ModulePreferences.DEFAULT_RADIAL_KEEP_OPEN,
                ),
                radialMaxItems =
                    ModulePreferences.coerceRadialMaxItems(
                        preferences.getInt(
                            ModulePreferences.KEY_RADIAL_MAX_ITEMS,
                            ModulePreferences.DEFAULT_RADIAL_MAX_ITEMS,
                        ),
                    ),
            )
        }
    }
}
