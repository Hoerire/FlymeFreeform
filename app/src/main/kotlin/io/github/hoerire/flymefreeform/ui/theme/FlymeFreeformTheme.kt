package io.github.hoerire.flymefreeform.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost

@Composable
internal fun FlymeFreeformTheme(content: @Composable () -> Unit) {
    GlasmioTheme {
        val colors = GlasmioTheme.colors
        // 全局采样源：页面自身不透明，这里只给弹窗与玻璃控件提供一层随深浅色变化的底色。
        val backdrop =
            remember(colors) {
                Brush.verticalGradient(
                    listOf(colors.groupedBackground, colors.elevatedBackground),
                )
            }
        GlasmioBackdropHost(
            modifier = Modifier.fillMaxSize(),
            sampledContent = {
                Box(modifier = Modifier.fillMaxSize().background(backdrop))
            },
            overlayContent = { content() },
        )
    }
}
