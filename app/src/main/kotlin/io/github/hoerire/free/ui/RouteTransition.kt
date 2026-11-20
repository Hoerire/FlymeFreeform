package io.github.hoerire.free.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

/**
 * 页面转场时长（毫秒）。
 *
 * 纯时间动画：被打断也只是重新起一次，不会像 `SeekableTransitionState` 那样停在半路
 * 把退出页挂在组合树上（那个 bug 的成因见 `FreeNavHost` 顶部注释）。
 */
internal const val ROUTE_SLIDE_MS = 300

/**
 * 页面转场状态：当前页 + 正在退场的上一页 + 方向。
 *
 * 只由导航动作（[switchTo]）写，不依赖组合期的推断，所以切页那一帧就能拿到
 * 「新页 + 旧页」的正确组合，不会先闪一下再开始动画。
 */
internal class RouteTransitionState<T>(initial: T) {
    /** 当前显示的页。 */
    var displayed by mutableStateOf(initial)
        private set

    /** 正在退场的页；`null` 表示没有进行中的转场。 */
    var leaving by mutableStateOf<T?>(null)

    /** true = 进入（新页从右侧盖上）；false = 返回（当前页整页向右滑走）。 */
    var forward by mutableStateOf(true)
        private set

    /** 每次切换自增，作为动画的 key。 */
    var generation by mutableIntStateOf(0)
        private set

    fun switchTo(target: T, forward: Boolean) {
        if (target == displayed) return
        leaving = displayed
        this.forward = forward
        displayed = target
        generation++
    }
}

/**
 * 两页滑动转场。
 *
 * 位移走 `graphicsLayer` 的 `translationX`，不用 `slideInHorizontally` 那类走布局阶段的偏移。
 * 依据：布局偏移会让整棵页面树每帧重新摆放，并连带刷新页面里的玻璃采样层，转场期间掉帧；
 * `graphicsLayer` 只改图层的变换矩阵，页面内容录一次 RenderNode 后每帧只是 GPU 平移。
 * 动画值只在 `graphicsLayer` 的 lambda 里读，因此每帧不触发重组，只更新图层。
 */
@Composable
internal fun <T> RouteTransition(
    state: RouteTransitionState<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    // key 用 generation：切页当帧就换成从 0 起步的新 Animatable，
    // 不会出现「先画在终点、下一帧又跳回起点」的闪动。
    val progress =
        remember(state.generation) {
            Animatable(if (state.leaving == null) 1f else 0f)
        }
    LaunchedEffect(state.generation) {
        if (state.leaving != null) {
            progress.animateTo(1f, tween(ROUTE_SLIDE_MS))
            state.leaving = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        state.leaving?.let { leaving ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // 返回时退场页要盖在新页上面（新页是不透明的，否则看不见滑动）。
                        .zIndex(if (state.forward) 0f else 1f)
                        .graphicsLayer {
                            val p = progress.value
                            translationX =
                                if (state.forward) -size.width / 3f * p else size.width * p
                        },
            ) {
                content(leaving)
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .zIndex(if (state.forward) 1f else 0f)
                    .graphicsLayer {
                        val p = progress.value
                        translationX =
                            if (state.forward) size.width * (1f - p) else -size.width / 3f * (1f - p)
                    },
        ) {
            content(state.displayed)
        }
    }
}
