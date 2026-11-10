package io.github.hoerire.flymefreeform.config

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val ACTION_PINNED_TARGETS_CHANGED =
        "io.github.hoerire.flymefreeform.action.PINNED_TARGETS_CHANGED"
    const val EXTRA_PINNED_TARGETS = "pinned_targets"
    const val EXTRA_PINS_SAVED = "pins_saved"
    const val EXTRA_MODULE_ENABLED = "module_enabled"
    const val KEY_MODULE_ENABLED = "enabled"
    const val KEY_LEFT_CORNER_ENABLED = "corner_left_enabled"
    const val KEY_RIGHT_CORNER_ENABLED = "corner_right_enabled"
    const val KEY_CORNER_TRIGGER_RANGE_DP = "corner_trigger_range_dp"
    const val KEY_RADIAL_HIT_RANGE_DP = "radial_hit_range_dp"

    /** 扇形图标的基准直径（dp）；第三方主题图标与「更多」圆盘都按它对齐。 */
    const val KEY_RADIAL_ICON_SIZE_DP = "radial_icon_size_dp"
    const val KEY_PINNED_TARGETS = "corner_targets_v2"
    const val KEY_CORNER_PINS = "corner_pins_v1"
    const val KEY_OUTSIDE_TAP_CLOSE_MODE = "outside_tap_close_mode_v1"
    const val KEY_HANDLE_SWIPE_UP_TO_MINI_ENABLED = "handle_swipe_up_to_mini_enabled"
    const val KEY_PAUSE_IN_LANDSCAPE = "pause_in_landscape"
    const val KEY_PAUSE_IN_GAME_MODE = "pause_in_game_mode"
    const val KEY_VERBOSE_LOG = "verbose_log_enabled"
    const val KEY_BLACKLIST_PACKAGES = "foreground_blacklist_v1"

    /**
     * 「禁用浮窗」：这些应用不会被以任何形式的自由浮窗打开。
     *
     * 与 [KEY_BLACKLIST_PACKAGES] 不是同一个语义，键必须分开：黑名单管的是「暂停模块增强」
     * （扇形、窗外关闭等），这份只影响启动改写。
     */
    const val KEY_ZOOM_EXCLUDED_PACKAGES = "zoom_launch_excluded_v1"
    const val KEY_ZOOM_MAX_WINDOW_COUNT = "zoom_max_window_count"
    const val KEY_ZOOM_ALLOW_ANY_APP = "zoom_allow_any_app"
    /** 小窗跳转总开关：通知、控制中心、应用互跳、桌面快捷项与卡片统一受它控制。 */
    /** 小窗交互总开关：关闭时下列各项一律不介入，避免与其他模块抢同一批入口。 */
    const val KEY_ZOOM_INTERACTION_ENABLED = "zoom_interaction_enabled"
    /** 小窗跳转细分场景：通知中心点击、通知横幅、控制中心磁贴、应用互跳、侧边栏。 */
    const val KEY_ZOOM_LAUNCH_NOTICE = "zoom_launch_notice_v2"
    const val KEY_ZOOM_LAUNCH_HEADS_UP = "zoom_launch_heads_up_v2"
    const val KEY_ZOOM_LAUNCH_CONTROL_CENTER = "zoom_launch_control_center_v2"
    const val KEY_ZOOM_LAUNCH_INTER_APP = "zoom_launch_inter_app_v2"

    /** 桌面长按快捷项是否转小窗；关闭后保持系统默认全屏。 */
    const val KEY_ZOOM_LAUNCHER_SHORTCUT = "zoom_launcher_shortcut_v1"

    /** 窗口超过数量限制时，自动关闭旧窗口而不是收成贴边浮标。 */
    /** 侧边栏进程就绪：由模块在侧边栏进程内发出，system_server 收到后才去读目录。 */
    const val ACTION_SIDEBAR_READY = "io.github.hoerire.flymefreeform.action.SIDEBAR_READY"

    const val KEY_AUTO_CLOSE_WINDOW = "auto_close_window_v1"
    const val DEFAULT_AUTO_CLOSE_WINDOW = false

    /** 桌面卡片与负一屏是否转小窗；关闭后保持系统默认全屏。 */
    const val KEY_ZOOM_LAUNCHER_CARD = "zoom_launcher_card_v1"

    /** 应用选择列表是否列出系统应用；默认关，保持列表干净。 */
    const val KEY_SHOW_SYSTEM_APPS = "show_system_apps_v1"

    /** 「禁用浮窗」默认屏蔽项的一次性迁移标记；写过一次设置后就不再自动补。 */
    const val KEY_ZOOM_EXCLUDED_LINKER_MIGRATED = "zoom_excluded_linker_migrated_v1"

    /**
     * 设备互联（OPPO 互联）包名。
     *
     * 它既没有启动入口、也不走系统通知，靠服务在后台代发启动，
     * 普通判定拦不住，因此默认放进「禁用浮窗」。
     */
    const val DEVICE_LINK_PACKAGE = "com.oplus.linker"

    /** 旧版三个分项开关的键，仅用于升级后迁移取值。 */
    const val LEGACY_KEY_ZOOM_LAUNCH_NOTIFICATION = "zoom_launch_notification"
    const val LEGACY_KEY_ZOOM_LAUNCH_CONTROL_CENTER = "zoom_launch_control_center"
    const val LEGACY_KEY_ZOOM_LAUNCH_INTER_APP = "zoom_launch_inter_app"
    const val KEY_RADIAL_KEEP_OPEN = "radial_keep_open"
    const val DEFAULT_ENABLED = false
    const val DEFAULT_CORNER_ENABLED = true
    const val DEFAULT_CORNER_TRIGGER_RANGE_DP = 84
    const val DEFAULT_RADIAL_HIT_RANGE_DP = 100
    const val DEFAULT_RADIAL_ICON_SIZE_DP = 44
    const val MIN_RADIAL_ICON_SIZE_DP = 32
    const val MAX_RADIAL_ICON_SIZE_DP = 56
    val DEFAULT_OUTSIDE_TAP_CLOSE_MODE = OutsideTapCloseMode.Disabled
    const val DEFAULT_HANDLE_SWIPE_UP_TO_MINI_ENABLED = false
    const val DEFAULT_PAUSE_IN_LANDSCAPE = true
    const val DEFAULT_PAUSE_IN_GAME_MODE = true
    const val DEFAULT_VERBOSE_LOG = false
    const val DEFAULT_ZOOM_MAX_WINDOW_COUNT = 1
    const val MIN_ZOOM_MAX_WINDOW_COUNT = 1
    const val MAX_ZOOM_MAX_WINDOW_COUNT = 5
    const val DEFAULT_ZOOM_ALLOW_ANY_APP = false
    const val DEFAULT_ZOOM_INTERACTION_ENABLED = false
    /** 两个桌面入口默认跟随主开关，保持既有行为。 */
    const val DEFAULT_ZOOM_LAUNCHER_SHORTCUT = false
    const val DEFAULT_ZOOM_LAUNCHER_CARD = false
    const val DEFAULT_SHOW_SYSTEM_APPS = false
    const val DEFAULT_RADIAL_KEEP_OPEN = false
    /** 扇形最多能放几个应用（收藏上限同值）。须定义在引用它的常量之前。 */
    const val MAX_PINNED_APPS = 6
    /** 扇形同时显示的条目数：1–6，默认 6。 */
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160
    const val MIN_RADIAL_HIT_RANGE_DP = 40
    const val MAX_RADIAL_HIT_RANGE_DP = 140

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)

    fun coerceRadialHitRangeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_HIT_RANGE_DP, MAX_RADIAL_HIT_RANGE_DP)

    fun coerceRadialIconSizeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_ICON_SIZE_DP, MAX_RADIAL_ICON_SIZE_DP)

    fun coerceZoomMaxWindowCount(value: Int): Int =
        value.coerceIn(MIN_ZOOM_MAX_WINDOW_COUNT, MAX_ZOOM_MAX_WINDOW_COUNT)

    /** 黑名单包名：换行分隔，逐条校验，最多 200 条。 */
    fun decodePackages(raw: String?): List<String> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BLACKLIST_PACKAGES)
            .toList()

    fun encodePackages(packages: List<String>): String =
        packages
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .take(MAX_BLACKLIST_PACKAGES)
            .joinToString("\n")

    private val PACKAGE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private const val MAX_BLACKLIST_PACKAGES = 200
}
