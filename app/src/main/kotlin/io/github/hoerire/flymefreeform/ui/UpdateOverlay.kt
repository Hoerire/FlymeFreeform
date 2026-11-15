package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.update.UpdateState
import io.github.neoruaa.glasmio.core.GlasmioTheme
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog

/**
 * 更新弹窗的统一入口。
 *
 * 弹窗常驻组合，仅切换 [GlasmioAlertDialog] 的可见性，以保留关闭动画；
 * 关闭或跳转后记录版本号，同一版本不再重复弹出。
 *
 * 采样源留空：默认取 [io.github.neoruaa.glasmio.glass.LocalBlurBackdrop]，
 * 也就是 [PageBackdropScope] 录下的页面内容。库内部再做一次跨窗口取样，
 * 弹窗才能像首页标题那样透出身后内容。
 */
@Composable
internal fun UpdateOverlay(
    update: UpdateState,
    onDismissUpdate: (String) -> Unit,
) {
    val available = update as? UpdateState.Available
    var closedVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = available?.takeIf { it.version != closedVersion }
    val uriHandler = LocalUriHandler.current
    // 说明文本可滚动：每次弹出都回到顶部，避免沿用上次的滚动位置把开头截掉。
    val notesScroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(pending) { notesScroll.scrollTo(0) }
    GlasmioAlertDialog(
        visible = pending != null,
        title =
            pending?.let {
                stringResource(R.string.update_available_title, it.version)
            } ?: "",
        onDismissRequest = {
            pending?.let {
                closedVersion = it.version
                onDismissUpdate(it.version)
            }
        },
        buttons =
            listOf(
                GlasmioAlertButtonSpec(
                    label = stringResource(R.string.update_dismiss_action),
                    role = GlasmioAlertButtonRole.Cancel,
                    onClick = {
                        pending?.let {
                            closedVersion = it.version
                            onDismissUpdate(it.version)
                        }
                    },
                ),
                GlasmioAlertButtonSpec(
                    label = stringResource(R.string.update_open_action),
                    onClick = {
                        pending?.let {
                            closedVersion = it.version
                            runCatching { uriHandler.openUri(it.pageUrl) }
                        }
                    },
                ),
            ),
        content = {
            // 弹窗本身就是玻璃表面，内部不再嵌卡片：再套一层会多出一圈边线。
            pending?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    text = notes,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(notesScroll),
                    style = IosTypography.subheadline,
                    color = GlasmioTheme.colors.secondaryContent,
                )
            }
        },
    )
}
