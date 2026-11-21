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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

/**
 * 页面转场的统一参数。
 *
 * 所有页面（顶层路由与二级页）都读这一份：要调手感只改这里，不用逐个页面改。
 */
internal object PageTransitionSpec {
    /** 滑动转场时长（毫秒）。纯时间动画：被打断只是重起一次，不会把退出页挂在组合树上。 */
    const val SLIDE_MS = 300

    /** 退场页与目标页的视差比例：目标页只退到屏幕外的 1/3 处。 */
    const val PARALLAX_RATIO = 1f / 3f
}

/** 滑动转场时长；保留旧名字给现有调用点。 */
internal const val ROUTE_SLIDE_MS = PageTransitionSpec.SLIDE_MS

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
 *
 * 返回走这里自带的滑动动画，不接管系统返回手势。
 */
@Composable
internal fun <T> RouteTransition(
    state: RouteTransitionState<T>,
    modifier: Modifier = Modifier,
    /** 状态保存键，用于滚动位置等在页面重新进入组合时的恢复。 */
    stateKey: (T) -> Any,
    /**
     * 当前页下面那一页，常驻组合、原地不动。
     *
     * 返回时它已经渲染好，直接露出来即可；滚动位置也因为它一直没被销毁而保留。
     */
    below: T? = null,
    content: @Composable (T) -> Unit,
) {
    val pageStates = rememberSaveableStateHolder()
    // key 用 generation：切页当帧就换成从 0 起步的新 Animatable，
    // 不会出现「先画在终点、下一帧又跳回起点」的闪动。
    val progress =
        remember(state.generation) {
            Animatable(if (state.leaving == null) 1f else 0f)
        }
    LaunchedEffect(state.generation) {
        if (state.leaving != null) {
            progress.animateTo(1f, tween(PageTransitionSpec.SLIDE_MS))
            state.leaving = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 上级页常驻：不参与动画，返回时只是被「揭开」。
        below?.let { page ->
            Box(modifier = Modifier.fillMaxSize()) {
                pageStates.SaveableStateProvider(stateKey(page)) { content(page) }
            }
        }
        // 推入时 leaving 就是 below，已经在下面渲染过了，再画一遍会重影。
        state.leaving?.takeIf { it != below }?.let { leaving ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // 返回时退场页要盖在新页上面（新页是不透明的，否则看不见滑动）。
                        .zIndex(if (state.forward) 0f else 1f)
                        .graphicsLayer {
                            val p = progress.value
                            translationX =
                                if (state.forward) {
                                    -size.width * PageTransitionSpec.PARALLAX_RATIO * p
                                } else {
                                    size.width * p
                                }
                        },
            ) {
                pageStates.SaveableStateProvider(stateKey(leaving)) { content(leaving) }
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .graphicsLayer {
                        val p = progress.value
                        // 进入：新页从右侧盖上。返回：上级页原地不动，只由下级页滑走把它露出来——
                        // 两页同时位移会显得又急又跳。
                        translationX = if (state.forward) size.width * (1f - p) else 0f
                    },
            ) {
                pageStates.SaveableStateProvider(stateKey(state.displayed)) {
                    content(state.displayed)
                }
            }
        }
    }

