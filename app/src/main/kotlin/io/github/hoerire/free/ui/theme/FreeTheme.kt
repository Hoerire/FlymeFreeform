package io.github.hoerire.free.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

/**
 * 模块主题：COUI（ColorOS 17 风格的组件库）。
 *
 * 颜色、字体、控件全部取自 COUI 的主题：跟随系统深浅色（`ColorSchemeMode.System`），
 * 与 ColorOS 设置页的观感保持一致。
 */
@Composable
internal fun FreeTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    COUITheme(controller = controller, content = content)
}
