package io.github.hoerire.free.config

/** App 与 Hook 进程共享的框架配置协议；已发布的名称和类型不可随意复用。 */
internal object ModulePreferences {
    const val GROUP = "module_runtime"
    const val ACTION_PINNED_TARGETS_CHANGED =
        "io.github.hoerire.free.action.PINNED_TARGETS_CHANGED"

    /**
     * 启动标记：模块自己代发的启动（如更多面板里的快捷方式）不希望被改成小窗。
     *
     * 快捷方式指向应用内的具体页面，开成小窗会让流程变形；用显式标记而不是按组件猜，
     * 才不会误伤真正该开小窗的应用启动。
     */
    const val EXTRA_SKIP_ZOOM = "io.github.hoerire.free.extra.SKIP_ZOOM"

    /** 模块应用 → system_server：推送整份设置快照（附带回执请求）。 */
    const val ACTION_SETTINGS_PUSHED = "io.github.hoerire.free.action.SETTINGS_PUSHED"

    /** 模块应用 → system_server：只要当前快照，用于应用启动时对齐界面。 */
    const val ACTION_SETTINGS_REQUEST = "io.github.hoerire.free.action.SETTINGS_REQUEST"

    /**
     * 查询方可显式声明回执地址。
     *
     * SystemUI 与侧边栏不在模块应用的包下，靠广播身份取发起方需要额外开共享身份；
     * 直接写明包名更稳，接收方按白名单校验后照此回执。
     */
    const val EXTRA_REQUESTER_PACKAGE = "requester_package"

    /** system_server → 模块应用：权威快照（推送的回执与查询的答复共用）。 */
    const val ACTION_SETTINGS_SNAPSHOT = "io.github.hoerire.free.action.SETTINGS_SNAPSHOT"

    /**
     * system_server → 侧边栏进程：最近任务包名（新的在前）。
     *
     * 侧边栏进程是普通应用 uid，拿不到 `getRecentTasks`；而 ColorOS 不把游戏写进
     * UsageStats（王者荣耀 7 天内 0 条 ACTIVITY_RESUMED），「最近」分组就会漏掉它。
     * 由 system_server 把最近任务顺序推过去，侧边栏只做合并。
     */
    const val ACTION_RECENTS_SYNC = "io.github.hoerire.free.action.RECENTS_SYNC"
    const val EXTRA_RECENT_PACKAGES = "recent_packages"

    /** 侧边栏应用：接收上面的最近任务推送。 */
    const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"
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
    /** 贴边浮标同时保留的个数上限（设置项「贴边数量」）。 */
    const val KEY_FLOAT_HANDLE_MAX_COUNT = "float_handle_max_count"
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

    /** 复制链接：复制链接弹窗与系统分发服务代发的 VIEW 启动。 */
    const val KEY_ZOOM_LAUNCH_CLIPBOARD = "zoom_launch_clipboard_v1"

    /** 窗口超过数量限制时，自动关闭旧窗口而不是收成贴边浮标。 */
    /** 侧边栏进程就绪：由模块在侧边栏进程内发出，system_server 收到后才去读目录。 */
    const val ACTION_SIDEBAR_READY = "io.github.hoerire.free.action.SIDEBAR_READY"

    /** 侧边栏真正被系统拉起（面板进入 SHOWING）：目录只认这个事件。 */
    const val ACTION_SIDEBAR_SHOWN = "io.github.hoerire.free.action.SIDEBAR_SHOWN"

    const val KEY_AUTO_CLOSE_WINDOW = "auto_close_window_v1"
    const val DEFAULT_AUTO_CLOSE_WINDOW = false

    /** 长按静音：长按浮动窗口把该应用静音，再次长按恢复。 */
    const val KEY_LONG_PRESS_MUTE = "mini_long_press_v1"
    const val DEFAULT_LONG_PRESS_MUTE = false


    /** 贴边挂机：收成贴边浮标后保持应用继续运行，不停止任务。 */
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


    const val KEY_RADIAL_KEEP_OPEN = "radial_keep_open"

    /**
     * 选中光圈：滑选到某个条目时，在图标外沿画一圈白色高亮。
     *
     * 圈宽由选中进度驱动（0 → 环宽），与图标放大一起构成选中反馈；
     * 关掉后只剩下放大。
     */
    const val KEY_RADIAL_SELECTION_RING = "radial_selection_ring_v1"

    /**
     * 流光特效：选中项外沿那圈常亮光圈、星头与运动轨迹。
     *
     * 与「选中光圈」是两个独立开关：光圈是不动的细白边 + 投影，
     * 流光是一直在转的动效，可以只开其中一个。
     */
    const val KEY_RADIAL_SHIMMER_ENABLED = "radial_shimmer_enabled_v1"

    /** 选中流光的参数集合；存成一行分隔串，见 [RadialShimmer]。 */
    const val KEY_RADIAL_SHIMMER = "radial_shimmer_v1"

    /** 扇形半径（百分比）：左右两侧共用同一个值，同时放大或缩小。 */
    const val KEY_RADIAL_RADIUS_PERCENT = "radial_radius_percent_v1"

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
    /** 贴边数量：系统手机默认 2 个，只放大不缩小，上限 10。 */
    const val DEFAULT_FLOAT_HANDLE_MAX_COUNT = 2
    const val MIN_FLOAT_HANDLE_MAX_COUNT = 2
    const val MAX_FLOAT_HANDLE_MAX_COUNT = 10
    const val DEFAULT_ZOOM_ALLOW_ANY_APP = false
    const val DEFAULT_SHOW_SYSTEM_APPS = false
    const val DEFAULT_RADIAL_KEEP_OPEN = false
    /** 选中光圈默认关闭：默认只保留 20% 的选中放大，特效由用户自己开。 */
    const val DEFAULT_RADIAL_SELECTION_RING = false
    const val DEFAULT_RADIAL_SHIMMER_ENABLED = false
    /** 自由拖拽默认关闭：改写的是系统级拖拽准入，由用户主动开启。 */
    const val DEFAULT_FREE_DRAG = false
    /**
     * 扇形最多能放几个应用（收藏上限同值）。须定义在引用它的常量之前。
     *
     * 扇形里还有一个常驻的「更多」格，所以实际槽位是 [MAX_PINNED_APPS] + 1，
     * 最多 8 格；`RadialIconGeometry` 按槽位数反推最小半径，保证不互相贴住。
     */
    const val MAX_PINNED_APPS = 7
    const val MIN_CORNER_TRIGGER_RANGE_DP = 24
    const val MAX_CORNER_TRIGGER_RANGE_DP = 160
    const val MIN_RADIAL_HIT_RANGE_DP = 40
    /**
     * 上限 220：图标圆心在半径 242 基准 dp 的圆弧上，命中半径到这个量级
     * 才能一直覆盖到屏幕直角本身；再大也不会有新的覆盖区域。
     */
    const val MAX_RADIAL_HIT_RANGE_DP = 220

    /** 扇形半径的允许区间与默认值（百分比，100 为基准半径）。 */
    const val MIN_RADIAL_RADIUS_PERCENT = 70
    const val MAX_RADIAL_RADIUS_PERCENT = 140
    const val DEFAULT_RADIAL_RADIUS_PERCENT = 100

    fun coerceRadialRadiusPercent(value: Int): Int =
        value.coerceIn(MIN_RADIAL_RADIUS_PERCENT, MAX_RADIAL_RADIUS_PERCENT)

    fun coerceCornerTriggerRangeDp(value: Int): Int =
        value.coerceIn(MIN_CORNER_TRIGGER_RANGE_DP, MAX_CORNER_TRIGGER_RANGE_DP)

    fun coerceRadialHitRangeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_HIT_RANGE_DP, MAX_RADIAL_HIT_RANGE_DP)

    fun coerceRadialIconSizeDp(value: Int): Int =
        value.coerceIn(MIN_RADIAL_ICON_SIZE_DP, MAX_RADIAL_ICON_SIZE_DP)

    fun coerceFloatHandleMaxCount(value: Int): Int =
        value.coerceIn(MIN_FLOAT_HANDLE_MAX_COUNT, MAX_FLOAT_HANDLE_MAX_COUNT)

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
