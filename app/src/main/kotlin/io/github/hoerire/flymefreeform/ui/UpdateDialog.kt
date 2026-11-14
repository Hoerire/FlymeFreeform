package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更新弹窗的内容区，由 Miuix 的 OverlayDialog 承载。
 *
 * 只放正文和两个动作：外层的圆角、遮罩与进出场动画都交给 OverlayDialog，
 * 与模块其他界面保持同一套视觉。
 */
@Composable
internal fun UpdateDialogContent(
    notes: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (notes.isNotBlank()) {
        Text(
            text = notes,
            modifier =
                Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(12.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = stringResource(R.string.update_dismiss_action),
            modifier =
                Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.update_open_action),
            modifier =
                Modifier
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}
