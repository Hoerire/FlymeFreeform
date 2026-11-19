package io.github.hoerire.free.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.free.R
import io.github.hoerire.free.update.UpdateState
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 更新弹窗的统一入口。
 *
 * 用 COUI 的 `OverlayDialog`（COUI 警告对话框），按钮走 `DialogButtonBar`：
 * 文案放不下时它会自动从横排翻成纵排，不再挤压文字。
 * 弹窗必须挂在某个 `Scaffold` 下面才能渲染，所以由页面骨架的 `overlay` 槽位承载。
 *
 * 弹窗常驻组合，仅切换可见性，以保留关闭动画；
 * 关闭或跳转后记录版本号，同一版本不再重复弹出。
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
    LaunchedEffect(pending) { notesScroll.scrollTo(0) }

    OverlayDialog(
        show = pending != null,
        title =
            pending?.let {
                stringResource(R.string.update_available_title, it.version)
            },
        onDismissRequest = {
            pending?.let {
                closedVersion = it.version
                onDismissUpdate(it.version)
            }
        },
    ) {
        pending?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Text(
                text = notes,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(notesScroll),
                style = COUITheme.textStyles.bodyS,
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
        DialogButtonBar(
            negative =
                DialogButtonBarAction(
                    text = stringResource(R.string.update_dismiss_action),
                    onClick = {
                        pending?.let {
                            closedVersion = it.version
                            onDismissUpdate(it.version)
                        }
                    },
                ),
            positive =
                DialogButtonBarAction(
                    text = stringResource(R.string.update_open_action),
                    onClick = {
                        pending?.let {
                            closedVersion = it.version
                            runCatching { uriHandler.openUri(it.pageUrl) }
                        }
                    },
                ),
        )
    }
}
