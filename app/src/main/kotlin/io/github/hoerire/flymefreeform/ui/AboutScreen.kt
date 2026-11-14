package io.github.hoerire.flymefreeform.ui

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.hoerire.flymefreeform.BuildConfig
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.update.UpdateState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 关于页。
 *
 * 版本号、更新检测、诊断开关与两个仓库地址集中在同一张卡片内；
 * 有新版本时，更新动作插在检测结果之后。
 */
@Composable
internal fun AboutScreen(
    update: UpdateState,
    verboseLogEnabled: Boolean,
    onBack: () -> Unit,
    onVerboseLogChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val available = update as? UpdateState.Available
    // 标题已在上一级入口显示，这里只保留返回按钮。
    SettingsPage(title = "", onBack = onBack) {
        item(key = "updateOverlay") {
            UpdateOverlay(
                update = update,
                backdrop = null,
                onDismissUpdate = onDismissUpdate,
            )
        }
        item(key = "about") {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.about_check_update_title),
                    summary = updateSummary(update),
                    onClick = onCheckUpdate,
                    startAction = { PreferenceIcon(Icons.Rounded.Refresh) },
                )
                if (available != null) {
                    BasicComponent(
                        title = stringResource(R.string.update_available_title, available.version),
                        summary =
                            stringResource(
                                R.string.update_available_summary,
                                BuildConfig.VERSION_NAME,
                            ),
                        startAction = { PreferenceIcon(Icons.Rounded.SystemUpdate) },
                    )
                    BasicComponent(
                        title = stringResource(R.string.update_open_action),
                        summary = stringResource(R.string.update_open_summary),
                        onClick = {
                            try {
                                uriHandler.openUri(available.pageUrl)
                            } catch (_: IllegalArgumentException) {
                            } catch (_: ActivityNotFoundException) {
                            }
                        },
                    )
                    BasicComponent(
                        title = stringResource(R.string.update_dismiss_action),
                        summary = stringResource(R.string.update_dismiss_summary),
                        onClick = { onDismissUpdate(available.version) },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.about_own_repo_title),
                    summary = stringResource(R.string.about_own_repo_summary),
                    onClick = {
                        try {
                            uriHandler.openUri(OWN_REPO_URL)
                        } catch (_: IllegalArgumentException) {
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                    startAction = {
                        PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_github))
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.about_upstream_title),
                    summary = stringResource(R.string.about_upstream_summary),
                    onClick = {
                        try {
                            uriHandler.openUri(UPSTREAM_REPO_URL)
                        } catch (_: IllegalArgumentException) {
                        } catch (_: ActivityNotFoundException) {
                        }
                    },
                    startAction = {
                        PreferenceIcon(ImageVector.vectorResource(R.drawable.ic_github))
                    },
                )
                SwitchPreference(
                    checked = verboseLogEnabled,
                    onCheckedChange = onVerboseLogChange,
                    title = stringResource(R.string.verbose_log_title),
                    summary = stringResource(R.string.verbose_log_summary),
                    startAction = {
                        PreferenceIcon(
                            ImageVector.vectorResource(R.drawable.ic_log),
                        )
                    },
                )
            }
        }
    }
}

/** 检查更新这一行的小字：随检测状态变化。 */
@Composable
private fun updateSummary(update: UpdateState): String =
    when (update) {
        UpdateState.Idle -> stringResource(R.string.about_check_update_summary_idle)
        UpdateState.Checking -> stringResource(R.string.about_check_update_summary_checking)
        UpdateState.UpToDate -> stringResource(R.string.about_check_update_summary_latest)
        UpdateState.Failed -> stringResource(R.string.about_check_update_summary_failed)
        is UpdateState.Available ->
            stringResource(R.string.about_check_update_summary_available, update.version)
    }
