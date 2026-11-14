package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.update.UpdateState
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 更新弹窗的统一入口。
 *
 * 传了 [backdrop] 时沿用主页标题栏那套「模糊 + 半透明」的表面；没有 backdrop 的页面
 * 退回半透明底色，保证任何页面弹出来都是同一套 Miuix 圆角与遮罩。
 */
@Composable
internal fun UpdateOverlay(
    update: UpdateState,
    backdrop: LayerBackdrop?,
    onDismissUpdate: (String) -> Unit,
) {
    val available = update as? UpdateState.Available
    var closedVersion by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = available?.takeIf { it.version != closedVersion }
    val uriHandler = LocalUriHandler.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val surfaceModifier =
        if (backdrop == null) {
            Modifier
        } else {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RoundedCornerShape(28.dp),
                blurRadius = 25f,
                colors =
                    BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(surfaceColor.copy(alpha = 0.8f))),
                    ),
            )
        }
    OverlayDialog(
        show = pending != null,
        modifier = surfaceModifier,
        backgroundColor = if (backdrop == null) surfaceColor.copy(alpha = 0.92f) else Color.Transparent,
        // 强制居中样式（缩放淡入），手机上否则会按底部弹层从下往上滑。
        largeScreen = true,
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
    ) {
        pending?.let { info ->
            UpdateDialogContent(
                notes = info.notes,
                onOpen = {
                    closedVersion = info.version
                    runCatching { uriHandler.openUri(info.pageUrl) }
                },
                onDismiss = {
                    closedVersion = info.version
                    onDismissUpdate(info.version)
                },
            )
        }
    }
}
