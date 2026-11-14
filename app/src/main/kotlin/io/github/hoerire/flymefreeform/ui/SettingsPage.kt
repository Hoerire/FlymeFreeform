package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 二级设置页的统一骨架。
 *
 * 顶栏、安全区与列表间距在此集中处理，各页面只提供标题与条目内容，
 * 避免每个页面各自复写一遍内边距计算，改动时只需改这一处。
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    ScreenScaffold(title = title, onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalMargin,
                    top = innerPadding.calculateTopPadding() + ScreenTopSpacing,
                    end = ScreenHorizontalMargin,
                    bottom = innerPadding.calculateBottomPadding() + ScreenBottomSpacing,
                ),
            content = content,
        )
    }
}
