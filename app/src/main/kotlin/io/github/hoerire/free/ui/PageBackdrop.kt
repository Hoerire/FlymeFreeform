package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import io.github.neoruaa.glasmio.glass.LocalBlurBackdrop
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition

/**
 * 页面级采样层：把这一页真实绘制的内容录进一条 [rememberLayerBackdrop]，
 * 并通过 [LocalBlurBackdrop] 提供给弹窗这类模态表面。
 *
 * 主题里的 [GlasmioBackdropHost] 只铺一层纯色渐变，模态表面采到它等于没有内容可透，
 * 渲染结果就和不透明底色一样。弹窗要像 iOS 那样透出身后内容，采样源必须是页面本身：
 * [content] 画在录制层里，[overlay]（弹窗）放在录制层外——弹窗本体不能进采样层，
 * 否则会自己采自己。
 */
@Composable
internal fun PageBackdropScope(
    content: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalBlurBackdrop provides backdrop) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .glassBackdropSource(backdrop)
                        .trackBackdropPosition(backdrop),
            ) {
                content()
            }
            overlay()
        }
    }
}
