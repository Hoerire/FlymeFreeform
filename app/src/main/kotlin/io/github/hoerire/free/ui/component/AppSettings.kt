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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.R
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.preference.ArrowPreference as CouiArrowPreference
import io.github.suqi8.coui.kmp.basic.BasicComponent as CouiBasicComponent
import io.github.suqi8.coui.kmp.preference.ListPreference as CouiListPreference
import io.github.suqi8.coui.kmp.preference.ListPreferenceEntry
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.preference.SliderPreference as CouiSliderPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference as CouiSwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 设置界面的公共部件，全部落在 COUI（ColorOS 17 风格）的语义上。
 *
 * 页面骨架、分组、行与控件在这里一次性定义，各页面只组合这些部件；
 * 换外观或调间距时不必逐页修改。
 *
 * **列表一律不画分割线**（产品要求）：分组用 `Card` + 普通 `Column`，
 * 不使用 COUI 的 `CardListGroup`（它会自行绘制行间分隔线），
 * 各行之间的界面区隔靠卡片底色与行内边距表达。
 */

/** 设置页骨架：COUI 小标题顶栏 + 返回按钮 + 列表页面。 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle.orEmpty(),
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
            overlay()
        }
    }
}

/** 首页骨架：COUI 大标题顶栏（无返回按钮）+ 列表页面。 */
@Composable
internal fun HomeScaffold(
    title: String,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            io.github.suqi8.coui.kmp.basic.TopAppBar(
                title = title,
                largeTitle = title,
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
            overlay()
        }
    }
}

/** 自绘内容的页面骨架：页面自己滚动时使用，只提供返回按钮与标题。 */
@Composable
internal fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            content(padding)
        }
    }
}

/** 只有返回按钮、没有标题的页面骨架（关于页沿用上一级的标题）。 */
@Composable
internal fun BackOnlyScaffold(
    onBack: () -> Unit,
    overlay: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = { SmallTopAppBar(title = "", navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
            overlay()
        }
    }
}

/** 分组容器：一页里的若干行共用一个圆角底板。 */
@Composable
internal fun SettingsGroup(
    modifier: Modifier = Modifier,
    /** 是否绘制卡片底色；嵌在弹窗等已有表面的容器里时关掉，避免多出一层色块。 */
    framed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (framed) {
        Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column { content() }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) { content() }
    }
}

/** 分组标题。 */
@Composable
internal fun SectionTitle(text: String) {
    PreferenceCategory(title = text)
}

/** 行首图标：用模块自己的矢量图，不依赖第三方图标字体。 */
@Composable
internal fun PreferenceIcon(imageVector: ImageVector, enabled: Boolean = true) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint =
            if (enabled) {
                COUITheme.colorScheme.primary
            } else {
                COUITheme.colorScheme.labelTertiary
            },
    )
}

/** 返回按钮。 */
@Composable
internal fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back_action),
            modifier = Modifier.size(22.dp),
            tint = COUITheme.colorScheme.labelPrimary,
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
    /** 保留参数：COUI 版列表不再画行间分割线，值被忽略。 */
    @Suppress("UNUSED_PARAMETER") showTopSeparator: Boolean = false,
) {
    CouiSwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        startAction = startAction,
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
    /** 保留参数：COUI 版列表不再画行间分割线，值被忽略。 */
    @Suppress("UNUSED_PARAMETER") showTopSeparator: Boolean = false,
) {
    CouiArrowPreference(
        title = title,
        summary = summary,
        onClick = onClick,
        enabled = enabled,
        startAction = startAction,
    )
}

/** 只有文字与可选尾部的行。 */
@Composable
internal fun BasicComponent(
    title: String,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    /** 保留参数：COUI 版列表不再画行间分割线，值被忽略。 */
    @Suppress("UNUSED_PARAMETER") showTopSeparator: Boolean = false,
) {
    CouiBasicComponent(
        title = title,
        summary = summary,
        startAction = startAction,
        endActions = endActions,
        onClick = onClick,
        enabled = enabled,
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
    /** 保留参数：COUI 版列表不再画行间分割线，值被忽略。 */
    @Suppress("UNUSED_PARAMETER") showTopSeparator: Boolean = false,
) {
    CouiSliderPreference(
        value = value,
        onValueChange = onValueChange,
        title = title,
        summary = summary,
        startAction = icon,
        valueText = valueText,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
    )
}

/**
 * 选项行：点击后由 COUI 弹出底部单选面板。
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
    /**
     * 是否在标题下再显示一次当前选中项。
     *
     * 右侧的值区本来就会显示当前选中项，对"值本身就很短"的行（如生效位置）重复一次
     * 只是多一行灰字，这类场景传 false。
     */
    showSummary: Boolean = true,
    /** 保留参数：COUI 版列表不再画行间分割线，值被忽略。 */
    @Suppress("UNUSED_PARAMETER") showTopSeparator: Boolean = false,
) {
    val labels = values.map { valueText(it) }
    val selectedIndex = values.indexOf(selected)
    CouiListPreference(
        entries = labels.map { ListPreferenceEntry(text = it) },
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            values.getOrNull(index)?.let(onSelected)
        },
        title = title,
        summary = if (showSummary) labels.getOrNull(selectedIndex) else null,
        startAction = icon,
        cancelButtonText = stringResource(R.string.cancel),
        enabled = enabled,
    )
}

/** 行内小标题的通用间距，供自绘页面沿用同一套垂直节奏。 */
internal val SettingsRowSpacing = 8.dp

/** 供自绘页面使用的空占位（保持与列表页一致的底部留白）。 */
@Composable
internal fun BottomSpacer() {
    Spacer(Modifier.width(0.dp))
}

/** 自绘页面里的一行文字标题，跟随 COUI 的正文样式。 */
@Composable
internal fun BodyText(text: String, color: androidx.compose.ui.graphics.Color = COUITheme.colorScheme.labelPrimary) {
    Text(text = text, style = COUITheme.textStyles.bodyM, color = color)
}
