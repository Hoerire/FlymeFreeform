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

    /**
     * 扇形背景的合并滑条：负值为黑色遮罩浓度，正值为系统模糊，0 为不处理背景。
     *
     * 两个方向共用同一条滑块：往左压暗，往右模糊，中间即原样透出背后内容。
     */
    const val KEY_BACKDROP_LEVEL = "backdrop_level"

    /** 分项时代的遮罩浓度与布尔模糊开关，合并后只用于迁移。 */
    const val LEGACY_KEY_SHADE_PERCENT = "shade_percent"
    const val LEGACY_KEY_RADIAL_BLUR_ENABLED = "radial_blur_enabled"


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
    /** 已退役的「轻量打开」总开关：仅用于升级后清掉残留值。 */
    const val LEGACY_KEY_ZOOM_INTERACTION_ENABLED = "zoom_interaction_enabled"
    /** 小窗跳转细分场景：通知中心点击、通知横幅、控制中心磁贴、应用互跳、侧边栏。 */
    const val KEY_ZOOM_LAUNCH_NOTICE = "zoom_launch_notice_v2"
    const val KEY_ZOOM_LAUNCH_HEADS_UP = "zoom_launch_heads_up_v2"
    const val KEY_ZOOM_LAUNCH_CONTROL_CENTER = "zoom_launch_control_center_v2"
    const val KEY_ZOOM_LAUNCH_INTER_APP = "zoom_launch_inter_app_v2"

    /** 带回传的跨应用跳转（登录、授权、流程页）。 */
    const val KEY_ZOOM_LAUNCH_INTER_APP_RESULT = "zoom_launch_inter_app_result_v1"

    /** 分享中转页：分享给另一个应用时以浮窗打开（模块另起 zoom 启动）。 */
    const val KEY_ZOOM_LAUNCH_SHARE_RELAY = "zoom_launch_share_relay_v1"

    /** system_server 代发的启动（系统通知落点、模块管理工具入口）。 */
    const val KEY_ZOOM_LAUNCH_SYSTEM = "zoom_launch_system_v1"

    /** 复制链接：复制链接弹窗与系统分发服务代发的 VIEW 启动。 */
    const val KEY_ZOOM_LAUNCH_CLIPBOARD = "zoom_launch_clipboard_v1"

    /** 窗口超过数量限制时，自动关闭旧窗口而不是收成贴边浮标。 */
    /** 侧边栏进程就绪：由模块在侧边栏进程内发出，system_server 收到后才去读目录。 */
    const val ACTION_SIDEBAR_READY = "io.github.hoerire.flymefreeform.action.SIDEBAR_READY"

    const val KEY_AUTO_CLOSE_WINDOW = "auto_close_window_v1"
    const val DEFAULT_AUTO_CLOSE_WINDOW = false

    /** 长按静音：长按浮动窗口把该应用静音，再次长按恢复。 */
    const val KEY_LONG_PRESS_MUTE = "mini_long_press_v1"
    const val DEFAULT_LONG_PRESS_MUTE = false


    /** 贴边保活：收成贴边浮标后保持应用继续运行，不停止任务。 */
    const val KEY_FLOAT_KEEP_ALIVE = "float_keep_alive_v1"
    const val DEFAULT_FLOAT_KEEP_ALIVE = false

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

    /** 已移除的桌面入口开关（快捷项 / 卡片），仅用于升级后清掉残留值。 */
    const val LEGACY_KEY_ZOOM_LAUNCHER_SHORTCUT = "zoom_launcher_shortcut_v1"
    const val LEGACY_KEY_ZOOM_LAUNCHER_CARD = "zoom_launcher_card_v1"
    const val KEY_RADIAL_KEEP_OPEN = "radial_keep_open"

    /**
     * 「自由拖拽」：解除内容传送门的长按拖拽准入限制。
     *
     * 系统的长按拖拽只对 sys_view_drag_list.xml 里登记过的包 + Activity + 控件类名生效，
     * 没登记的包连配置都拿不到。打开后 hook 在配置下发处补一份通用白名单，
     * 让任意应用、任意页面都能长按拖起内容。只改「能不能拖」，不改「拖给谁」。
     */
    const val KEY_FREE_DRAG = "free_drag_enabled"

    /** 拖拽触发延迟：系统长按阈值（毫秒），默认 400 即官方行为。 */
    const val KEY_DRAG_LONG_PRESS_MS = "drag_long_press_ms_v1"
    const val DEFAULT_DRAG_LONG_PRESS_MS = 400
    const val MIN_DRAG_LONG_PRESS_MS = 100
    const val MAX_DRAG_LONG_PRESS_MS = 400

    fun coerceDragLongPressMs(value: Int): Int =
        value.coerceIn(MIN_DRAG_LONG_PRESS_MS, MAX_DRAG_LONG_PRESS_MS)
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
    /** 默认不处理背景：呼出扇形时既不压暗也不模糊。 */
    const val DEFAULT_BACKDROP_LEVEL = 0
    const val MIN_BACKDROP_LEVEL = -100
    const val MAX_BACKDROP_LEVEL = 100
    /** 滑条正值满格对应的模糊半径（dp）。 */
    const val MAX_RADIAL_BLUR_DP = 100
    /** 布尔开关时代开着模糊的机器迁移到的程度：对应上一版固定半径 24dp。 */
    const val LEGACY_BLUR_LEVEL = 24

    const val DEFAULT_ZOOM_MAX_WINDOW_COUNT = 1
    const val MIN_ZOOM_MAX_WINDOW_COUNT = 1
    const val MAX_ZOOM_MAX_WINDOW_COUNT = 5
    const val DEFAULT_ZOOM_ALLOW_ANY_APP = false
    const val DEFAULT_SHOW_SYSTEM_APPS = false
    const val DEFAULT_RADIAL_KEEP_OPEN = false
    /** 自由拖拽默认关闭：改写的是系统级拖拽准入，由用户主动开启。 */
    const val DEFAULT_FREE_DRAG = false
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

    fun coerceBackdropLevel(value: Int): Int =
        value.coerceIn(MIN_BACKDROP_LEVEL, MAX_BACKDROP_LEVEL)

    /** 遮罩不透明度（0–1）：只有滑条的负值部分参与压暗。 */
    fun backdropShadeAlpha(level: Int): Float =
        (-coerceBackdropLevel(level).coerceAtMost(0)).toFloat() / -MIN_BACKDROP_LEVEL

    /** 模糊半径（dp）：只有滑条的正值部分参与模糊。 */
    fun backdropBlurDp(level: Int): Int =
        coerceBackdropLevel(level).coerceAtLeast(0) * MAX_RADIAL_BLUR_DP / MAX_BACKDROP_LEVEL


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
