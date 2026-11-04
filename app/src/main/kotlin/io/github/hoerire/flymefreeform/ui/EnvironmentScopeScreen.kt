package io.github.hoerire.flymefreeform.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hoerire.flymefreeform.R
import io.github.hoerire.flymefreeform.framework.FrameworkConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 生效范围的二级页面。
 *
 * 决定模块在什么情况下暂停：按前台应用（排除列表）、按屏幕方向、按系统的游戏模式。
 * 三者都只暂停扇形的呼出手势，小窗相关的启动与交互能力不受影响。
 */
@Composable
internal fun EnvironmentScopeScreen(
    state: FrameworkConnectionState,
    onBack: () -> Unit,
    onPauseInLandscapeChange: (Boolean) -> Unit,
    onPauseInGameModeChange: (Boolean) -> Unit,
    onNavigateToBlacklist: () -> Unit,
) {
    val enabled = state.settingsEditable && state.settings.enabled
    SettingsPage(
        title = stringResource(R.string.environment_scope_title),
        onBack = onBack,
    ) {
        item(key = "scope") {
            Card(modifier = Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = stringResource(R.string.blacklist_title),
                    summary = stringResource(R.string.blacklist_summary),
                    onClick = onNavigateToBlacklist,
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.Block, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInLandscape,
                    onCheckedChange = onPauseInLandscapeChange,
                    title = stringResource(R.string.pause_in_landscape_title),
                    summary = stringResource(R.string.pause_in_landscape_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.ScreenRotation, enabled) },
                )
                SwitchPreference(
                    checked = state.settings.pauseInGameMode,
                    onCheckedChange = onPauseInGameModeChange,
                    title = stringResource(R.string.pause_in_game_mode_title),
                    summary = stringResource(R.string.pause_in_game_mode_summary),
                    enabled = enabled,
                    startAction = { PreferenceIcon(Icons.Rounded.SportsEsports, enabled) },
                )
            }
        }
    }
}