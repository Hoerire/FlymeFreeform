package io.github.hoerire.flymefreeform.framework

import androidx.compose.runtime.Immutable
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot

@Immutable
internal data class FrameworkConnectionState(
    val status: FrameworkConnectionStatus = FrameworkConnectionStatus.Waiting,
    val frameworkName: String? = null,
    val frameworkVersion: String? = null,
    val apiVersion: Int? = null,
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
    val grantedScopes: Set<String> = emptySet(),
    val isUpdating: Boolean = false,
    val isRequestingScope: Boolean = false,
    val issue: FrameworkConnectionIssue? = null,
) {
    val moduleEnabled: Boolean
        get() = settings.enabled

    val missingScopes: Set<String>
        get() = REQUIRED_SCOPES - grantedScopes

    val canChangeSettings: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope &&
                missingScopes.isEmpty()

    /**
     * 与 [canChangeSettings] 的区别是忽略 [isUpdating]。
     *
     * 每次写入配置都会短暂置起 isUpdating，若控件可用性随之翻转，
     * 整页开关会在同一帧内集体变灰再恢复，观感就是页面抖一下。
     * 写入本身在单线程队列里串行执行，不依赖禁用交互来防重。
     */
    val settingsEditable: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isRequestingScope &&
                missingScopes.isEmpty()

    val canRequestScope: Boolean
        get() =
            status == FrameworkConnectionStatus.Connected &&
                !isUpdating &&
                !isRequestingScope &&
                missingScopes.isNotEmpty()

    companion object {
        val REQUIRED_SCOPES: Set<String> =
            linkedSetOf(
                "system",
                "com.android.systemui",
                "com.coloros.smartsidebar",
            )
    }
}

internal enum class FrameworkConnectionStatus {
    Waiting,
    Connected,
    Incompatible,
    Error,
}

internal enum class FrameworkConnectionIssue {
    ServiceApiTooOld,
    RemoteCapabilityMissing,
    SystemCapabilityMissing,
    MultipleServices,
    ConnectionFailed,
    WriteFailed,
    ScopeRequestFailed,
}
