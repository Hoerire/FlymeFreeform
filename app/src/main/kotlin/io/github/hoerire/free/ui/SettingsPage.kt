package io.github.hoerire.free.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/**
 * 二级设置页的统一骨架。
 *
 * 顶栏、安全区与列表间距由 COUI 的 Scaffold/TopAppBar 统一定义，各页面只提供标题与条目内容，
 * 避免每个页面各自复写一遍内边距计算，改动时只需改这一处。
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    io.github.hoerire.free.ui.component.SettingsPage(
        title = title,
        onBack = onBack,
        subtitle = subtitle,
        content = content,
    )
}
