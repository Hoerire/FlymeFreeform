package io.github.hoerire.free.bubble

import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.ModuleSettingsSnapshot

/**
 * 气泡的只读配置：从整份设置快照切出来。
 *
 * 视觉层不认识偏好键、不认识快照；协调器只读这里的派生值。这样换设置通道时，
 * 唯一要改的就是 [from]。
 */
internal data class BubbleSettings(
    val active: Boolean,
    val whitelist: Set<String>,
    val cloneWhitelist: Set<String>,
    val sizePercent: Int,
    val iconPercent: Int,
    val badgePercent: Int,
    val timeoutSeconds: Int,
    val dimOnTimeout: Boolean,
    val previewEnabled: Boolean,
    val previewSuppressBanner: Boolean,
    val previewEdgeOnly: Boolean,
    val previewAlpha: Int,
    val previewTimeoutSeconds: Int,
    val previewDynamicTimeout: Boolean,
    val autoDismiss: Boolean,
    val continuousMotion: Boolean,
    /** 内容卡宽度：auto 时按内容自适应。 */
    val previewAutoWidth: Boolean = true,
    val previewWidthDp: Int = 280,
    val previewOverlayAlpha: Int = 18,
    val previewPosition: Int = BubbleMetrics.CONTENT_AUTO,
    val statusScrollEnabled: Boolean = ModulePreferences.DEFAULT_STATUS_SCROLL_ENABLED,
    /** 模块总开关 ×「状态栏滚动」；与气泡白名单无关。 */
    val statusScrollActive: Boolean = false,
    val statusScrollRounds: Int = ModulePreferences.DEFAULT_STATUS_SCROLL_ROUNDS,
    /** 免打扰：三种场景各自独立，见 [BubbleEnvironmentGate]。 */
    val skipForeground: Boolean = ModulePreferences.DEFAULT_BUBBLE_SKIP_FOREGROUND,
    val skipGame: Boolean = ModulePreferences.DEFAULT_BUBBLE_SKIP_GAME,
    val skipLocked: Boolean = ModulePreferences.DEFAULT_BUBBLE_SKIP_LOCKED,
    val suggestionsActive: Boolean = false,
    val suggestionFlashlight: Boolean = true,
    val suggestionMusic: Boolean = true,
    val suggestionAppInstall: Boolean = true,
    val suggestionClipboard: Boolean = false,
    val suggestionKeepAwake: Boolean = false,
    val suggestionNoEdge: Boolean = false,
) {
    val previewActive: Boolean get() = active && previewEnabled

    /** 状态栏滚动与气泡共用「应用范围」里的同一份名单（主应用与分身各看各的键）。 */
    fun acceptsStatusScroll(identity: BubbleIdentity): Boolean {
        if (!statusScrollActive) return false
        if (identity.userId > 0) {
            return cloneWhitelist.contains(BubbleKey.key(identity.packageName, identity.userId))
        }
        return whitelist.contains(identity.packageName)
    }

    /** 某类即时建议是否开启；type 取自 [ImmediateSuggestionController.TYPE_FLASHLIGHT] 等常量。 */
    fun isSuggestionEnabled(type: String): Boolean {
        if (!suggestionsActive) return false
        return when (type) {
            ImmediateSuggestionController.TYPE_FLASHLIGHT -> suggestionFlashlight
            ImmediateSuggestionController.TYPE_MUSIC -> suggestionMusic
            ImmediateSuggestionController.TYPE_APP_INSTALL -> suggestionAppInstall
            ImmediateSuggestionController.TYPE_CLIPBOARD -> suggestionClipboard
            ImmediateSuggestionController.TYPE_KEEP_AWAKE -> suggestionKeepAwake
            else -> false
        }
    }

    fun accepts(identity: BubbleIdentity): Boolean =
        accepts(identity.packageName, identity.userId)

    fun accepts(packageName: String, userId: Int): Boolean {
        if (!active) return false
        val key = BubbleKey.key(packageName, userId)
        return if (userId > 0) cloneWhitelist.contains(key) else whitelist.contains(packageName)
    }

    companion object {
        val INACTIVE =
            BubbleSettings(
                active = false,
                whitelist = emptySet(),
                cloneWhitelist = emptySet(),
                sizePercent = ModulePreferences.DEFAULT_BUBBLE_SIZE_PERCENT,
                iconPercent = ModulePreferences.DEFAULT_BUBBLE_ICON_PERCENT,
                badgePercent = ModulePreferences.DEFAULT_BUBBLE_BADGE_PERCENT,
                timeoutSeconds = ModulePreferences.DEFAULT_BUBBLE_TIMEOUT_SECONDS,
                dimOnTimeout = ModulePreferences.DEFAULT_BUBBLE_DIM_ON_TIMEOUT,
                previewEnabled = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_ENABLED,
                previewSuppressBanner = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_SUPPRESS_BANNER,
                previewEdgeOnly = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_EDGE_ONLY,
                previewAlpha = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_ALPHA,
                previewTimeoutSeconds = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_TIMEOUT_SECONDS,
                previewDynamicTimeout = ModulePreferences.DEFAULT_BUBBLE_PREVIEW_DYNAMIC_TIMEOUT,
                autoDismiss = ModulePreferences.DEFAULT_BUBBLE_AUTO_DISMISS,
                continuousMotion = ModulePreferences.DEFAULT_BUBBLE_MOTION_CONTINUOUS,
                statusScrollEnabled = ModulePreferences.DEFAULT_STATUS_SCROLL_ENABLED,
                statusScrollActive = false,
                statusScrollRounds = ModulePreferences.DEFAULT_STATUS_SCROLL_ROUNDS,
                suggestionsActive = false,
            )

        fun from(snapshot: ModuleSettingsSnapshot): BubbleSettings =
            BubbleSettings(
                active = snapshot.bubbleActive,
                whitelist = snapshot.bubbleWhitelist.toSet(),
                cloneWhitelist = snapshot.bubbleCloneWhitelist.toSet(),
                sizePercent = ModulePreferences.coerceBubbleSizePercent(snapshot.bubbleSizePercent),
                iconPercent = ModulePreferences.coerceBubbleIconPercent(snapshot.bubbleIconPercent),
                badgePercent = ModulePreferences.coerceBubbleBadgePercent(snapshot.bubbleBadgePercent),
                timeoutSeconds =
                    ModulePreferences.coerceBubbleTimeoutSeconds(snapshot.bubbleTimeoutSeconds),
                dimOnTimeout = snapshot.bubbleDimOnTimeout,
                previewEnabled = snapshot.bubblePreviewEnabled,
                previewSuppressBanner = snapshot.bubblePreviewSuppressBanner,
                previewEdgeOnly = snapshot.bubblePreviewEdgeOnly,
                previewAlpha = ModulePreferences.coerceBubblePreviewAlpha(snapshot.bubblePreviewAlpha),
                previewTimeoutSeconds =
                    ModulePreferences.coerceBubblePreviewTimeoutSeconds(
                        snapshot.bubblePreviewTimeoutSeconds,
                    ),
                previewDynamicTimeout = snapshot.bubblePreviewDynamicTimeout,
                autoDismiss = snapshot.bubbleAutoDismiss,
                continuousMotion = snapshot.bubbleMotionContinuous,
                statusScrollEnabled = snapshot.statusScrollEnabled,
                statusScrollActive = snapshot.enabled && snapshot.statusScrollEnabled,
                statusScrollRounds =
                    ModulePreferences.coerceStatusScrollRounds(snapshot.statusScrollRounds),
                skipForeground = snapshot.bubbleSkipForeground,
                skipGame = snapshot.bubbleSkipGame,
                skipLocked = snapshot.bubbleSkipLocked,
                suggestionsActive = snapshot.suggestionsActive,
                suggestionFlashlight = snapshot.suggestionFlashlight,
                suggestionMusic = snapshot.suggestionMusic,
                suggestionAppInstall = snapshot.suggestionAppInstall,
                suggestionClipboard = snapshot.suggestionClipboard,
                suggestionKeepAwake = snapshot.suggestionKeepAwake,
                suggestionNoEdge = snapshot.suggestionNoEdge,
            )
    }
}

/**
 * 视觉层的外观读取口：把「配置」和「运行时位置」拼成 [BubbleVisualConfig]。
 *
 * 位置不属于用户设置（拖动只改变这一次的落点），因此由 [BubblePlacementStore] 提供；
 * 其余参数来自 [BubbleSettings]。
 */
internal class BubbleAppearance(
    private val settings: BubbleSettings,
    private val placement: BubblePlacementStore,
) : BubbleVisualConfig {
    override fun positionX(landscape: Boolean): Int = placement.positionX(landscape)

    override fun positionY(landscape: Boolean): Int = placement.positionY(landscape)

    override fun dockEdge(landscape: Boolean): Int = placement.dockEdge(landscape)

    override fun bubbleSizePercent(): Int = settings.sizePercent

    override fun iconSizePercent(): Int = settings.iconPercent

    override fun badgeSizePercent(): Int = settings.badgePercent

    override fun continuousMotion(): Boolean = settings.continuousMotion
}
