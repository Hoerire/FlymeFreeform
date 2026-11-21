package io.github.hoerire.free.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

/**
 * 页面栈的渲染与转场，一处实现、顶层路由与二级页共用。
 *
 * 关键点：**栈里的每一页都常驻组合**，只做压栈/弹栈，不销毁页面。
 * 滚动位置、列表状态这些随组合存活，返回时不需要「保存/恢复」，
 * 也就不存在恢复失败导致的「回到顶部」；动画期间上级页早已渲染好，直接露出来。
 *
 * 动画只动两页：
 * - 推入：新页从右侧滑入，原来那页按 [PageTransitionSpec.PARALLAX_RATIO] 轻微左移；
 * - 返回：被弹出的页向右滑走，上级页原地不动。
 */
@Composable
internal fun <T> PageStackHost(
    stack: List<T>,
    state: RouteTransitionState<T>,
    modifier: Modifier = Modifier,
    stateKey: (T) -> Any,
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
        stack.forEachIndexed { index, page ->
            val top = index == stack.lastIndex
            // 推入时，退场的就是栈里的上一页：就地让它轻微左移即可，不必单独再画一层。
            val leavingInPlace = state.leaving != null && page == state.leaving && !top
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .zIndex(index.toFloat())
                        .graphicsLayer {
                            val p = progress.value
                            translationX =
                                when {
                                    top && state.forward -> size.width * (1f - p)
                                    leavingInPlace && state.forward ->
                                        -size.width * PageTransitionSpec.PARALLAX_RATIO * p
                                    else -> 0f
                                }
                        },
            ) {
                pageStates.SaveableStateProvider(stateKey(page)) { content(page) }
            }
        }
        // 返回时，被弹出栈的页已经不在 stack 里：单独渲染在最上层做退场动画。
        state.leaving?.takeIf { leaving -> !stack.contains(leaving) }?.let { leaving ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .zIndex(stack.size + 1f)
                        .graphicsLayer {
                            translationX = size.width * progress.value
                        },
            ) {
                pageStates.SaveableStateProvider(stateKey(leaving)) { content(leaving) }
            }
        }
    }
}
