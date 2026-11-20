package io.github.hoerire.free.bubble

import android.content.Context

/**
 * 气泡落点的运行时存储。
 *
 * 拖动后的位置是**运行时状态**，不属于用户设置：写进 SystemUI 自己的偏好文件，
 * 不碰模块应用的 shared_prefs（root 往应用目录写 shared_prefs 会撞 SELinux，
 * 表现为设置不保存）。只存浮动坐标，贴边不入库，见 [dockEdge]。
 */
internal class BubblePlacementStore(context: Context) {
    private val preferences =
        context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    fun positionX(landscape: Boolean): Int =
        preferences.getInt(
            if (landscape) KEY_LANDSCAPE_X else KEY_PORTRAIT_X,
            if (landscape) BubbleMetrics.DEFAULT_LANDSCAPE_X else BubbleMetrics.DEFAULT_PORTRAIT_X,
        ).coerceIn(0, 100)

    fun positionY(landscape: Boolean): Int =
        preferences.getInt(
            if (landscape) KEY_LANDSCAPE_Y else KEY_PORTRAIT_Y,
            if (landscape) BubbleMetrics.DEFAULT_LANDSCAPE_Y else BubbleMetrics.DEFAULT_PORTRAIT_Y,
        ).coerceIn(0, 100)

    /**
     * 新气泡一律从浮动位置出现：贴边是**当前这颗**的临时状态（超时收起 / 拖到边缘），
     * 不写进偏好、也不影响下一条消息。历史上存过 dock=1 的机器这里也返回 0。
     */
    fun dockEdge(landscape: Boolean): Int = 0

    /** 只写浮动坐标；贴边状态由视图自己持有，不落盘。 */
    fun persist(landscape: Boolean, xPercent: Int, yPercent: Int) {
        runCatching {
            preferences
                .edit()
                .putInt(
                    if (landscape) KEY_LANDSCAPE_X else KEY_PORTRAIT_X,
                    xPercent.coerceIn(0, 100),
                )
                .putInt(
                    if (landscape) KEY_LANDSCAPE_Y else KEY_PORTRAIT_Y,
                    yPercent.coerceIn(0, 100),
                )
                .apply()
        }
    }

    private companion object {
        const val STORE_NAME = "free_bubble_placement"
        const val KEY_PORTRAIT_X = "portrait_x"
        const val KEY_PORTRAIT_Y = "portrait_y"
        const val KEY_LANDSCAPE_X = "landscape_x"
        const val KEY_LANDSCAPE_Y = "landscape_y"
    }
}
