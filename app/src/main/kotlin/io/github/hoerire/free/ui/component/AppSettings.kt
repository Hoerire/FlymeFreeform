package io.github.hoerire.free.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.R
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.preference.GlasmioListPreference
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceEntry
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceGroupTitle
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioSlider

/**
 * 设置界面的公共部件，全部落在 Glasmio 的语义上。
 *
 * 页面骨架、分组、行与控件在这里一次性定义，各页面只组合这些部件；
 * 换外观或调间距时不必逐页修改。
 */

/** 设置页骨架：折叠大标题、返回按钮与顶部渐进模糊都由 Glasmio 负责。 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    GlasmioPinnedListPage(
        title = title,
        onNavigateBack = onBack,
        navigateBackContentDescription = stringResource(R.string.back_action),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** 自绘内容的页面骨架：页面自己滚动时使用，只提供返回按钮与标题。 */
@Composable
internal fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = IosTypography.title2,
                color = GlasmioTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content(PaddingValues(bottom = 16.dp))
    }
}

/** 分组容器：一页里的若干行共用一个圆角底板。 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    /** 是否绘制卡片外框；嵌在弹窗等已有表面的容器里时关掉，避免多出一圈边线。 */
    framed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlasmioGroupedList(modifier = modifier, framed = framed, content = content)
}

/** 分组标题。 */
@Composable
internal fun SectionTitle(text: String) {
    GlasmioPreferenceGroupTitle(text)
}

/** 行首图标：用模块自己的矢量图，不依赖 SF Symbols 字体。 */
@Composable
internal fun PreferenceIcon(imageVector: ImageVector, enabled: Boolean = true) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = if (enabled) GlasmioTheme.colors.accent else GlasmioTheme.colors.tertiaryContent,
    )
}

/** 返回按钮。 */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    GlasmioIconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = GlasmioTheme.colors.content,
        )
    }
}

/** 开关行。 */
@Composable
internal fun SwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    /** 组内行间分割线：默认不画，卡片本身的分组已经足够区分条目。 */
    showTopSeparator: Boolean = false,
) {
    GlasmioSwitchPreference(
        title = { Text(title, style = IosTypography.body) },
        description = summary,
        icon = startAction,
        checked = checked,
        onCheckedChange = onCheckedChange,
        isEnabled = enabled,
        showTopSeparator = showTopSeparator,
    )
}

/** 可点进二级页的行。 */
@Composable
internal fun ArrowPreference(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
    /** 组内行间分割线：默认不画，卡片本身的分组已经足够区分条目。 */
    showTopSeparator: Boolean = false,
) {
    GlasmioPreferenceEntry(
        title = { Text(title, style = IosTypography.body) },
        description = summary,
        icon = startAction,
        onClick = onClick,
        isEnabled = enabled,
        trailingContent = {
            SfIcon(
                systemName = "chevron.right",
                contentDescription = null,
                size = 14.dp,
                tint = GlasmioTheme.colors.tertiaryContent,
            )
        },
        showTopSeparator = showTopSeparator,
    )
}

/** 只有文字与可选尾部的行。 */
@Composable
internal fun BasicComponent(
    title: String,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** 组内行间分割线：默认不画，卡片本身的分组已经足够区分条目。 */
    showTopSeparator: Boolean = false,
) {
    GlasmioPreferenceEntry(
        title = { Text(title, style = IosTypography.body) },
        description = summary,
        icon = startAction,
        trailingContent = endActions,
        onClick = onClick,
        isEnabled = enabled,
        showTopSeparator = showTopSeparator,
    )
}

/** 数值滑条行：标题、说明、滑条与右侧当前值。 */
@Composable
internal fun SliderRow(
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueText: String,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    /** 组内行间分割线：默认不画，卡片本身的分组已经足够区分条目。 */
    showTopSeparator: Boolean = false,
) {
    GlasmioPreferenceEntry(
        title = { Text(title, style = IosTypography.body) },
        description = summary,
        icon = icon,
        content = {
            GlasmioSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                enabled = enabled,
                onValueChangeFinished = onValueChangeFinished,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        },
        trailingContent = {
            // 固定宽度并右对齐：拖动时数字位数变化不会改变右侧列宽，
            // 否则标题与说明会跟着重新换行，整行看起来在抖。
            Text(
                text = valueText,
                style = IosTypography.body,
                color = GlasmioTheme.colors.secondaryContent,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        },
        isEnabled = enabled,
        showTopSeparator = showTopSeparator,
    )
}

/**
 * 选项行：点击后由 Glasmio 弹出选择列表。
 *
 * 副标题固定显示当前选中的选项，而不是泛泛的说明文字：
 * 这类行的用途是随时看清"现在选的是哪个"，说明文字在需要时进列表即可看到。
 */
@Composable
internal fun <T> ListChoicePreference(
    title: String,
    icon: (@Composable () -> Unit)? = null,
    selected: T,
    values: List<T>,
    valueText: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: Boolean = true,
    /** 组内行间分割线：默认不画，卡片本身的分组已经足够区分条目。 */
    showTopSeparator: Boolean = false,
) {
    val selectedLabel = if (values.any { it == selected }) valueText(selected) else null
    GlasmioListPreference(
        title = { Text(title, style = IosTypography.body) },
        description = selectedLabel,
        icon = icon,
        selectedValue = selected,
        values = values,
        valueText = valueText,
        onValueSelected = onSelected,
        isEnabled = enabled,
        showTopSeparator = showTopSeparator,
    )
}
