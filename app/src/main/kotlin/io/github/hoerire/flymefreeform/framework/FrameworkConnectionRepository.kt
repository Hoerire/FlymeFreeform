package io.github.hoerire.flymefreeform.framework

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot
import io.github.hoerire.flymefreeform.config.OutsideTapCloseMode
import io.github.hoerire.flymefreeform.config.PinnedTarget
import io.github.hoerire.flymefreeform.config.PinnedTargetCodec
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service 102 的进程级所有者；所有同步 Binder 调用都串行限制在单一后台线程。 */
internal class FrameworkConnectionRepository(private val context: Context) {
    private val started = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, WORKER_THREAD_NAME) }
    private val liveServices = IdentityHashMap<XposedService, Unit>()
    private val diedBeforeBind = IdentityHashMap<XposedService, Unit>()
    private val pendingServices = IdentityHashMap<XposedService, Unit>()
    private val unavailableServices = IdentityHashMap<XposedService, FrameworkConnectionState>()
    private var activeConnection: ActiveConnection? = null

    private val mutableState = MutableStateFlow(FrameworkConnectionState())
    val state: StateFlow<FrameworkConnectionState> = mutableState.asStateFlow()

    private val serviceListener =
        object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                worker.execute { handleServiceBind(service) }
            }

            override fun onServiceDied(service: XposedService) {
                worker.execute { handleServiceDied(service) }
            }
        }

    fun start() {
        check(started.compareAndSet(false, true)) { "Xposed Service listener is already registered" }
        XposedServiceHelper.registerListener(serviceListener)
    }

    fun setModuleEnabled(enabled: Boolean) =
        updateSettings { it.copy(enabled = enabled) }

    fun setCornerSides(leftEnabled: Boolean, rightEnabled: Boolean) =
        updateSettings {
            val bothDisabled = !leftEnabled && !rightEnabled
            it.copy(
                leftCornerEnabled = if (bothDisabled) true else leftEnabled,
                rightCornerEnabled = if (bothDisabled) true else rightEnabled,
            )
        }

    fun setCornerTriggerRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                cornerTriggerRangeDp =
                    ModulePreferences.coerceCornerTriggerRangeDp(rangeDp),
            )
        }

    fun setRadialHitRangeDp(rangeDp: Int) =
        updateSettings {
            it.copy(
                radialHitRangeDp =
                    ModulePreferences.coerceRadialHitRangeDp(rangeDp),
            )
        }

    fun setOutsideTapCloseMode(mode: OutsideTapCloseMode) =
        updateSettings { it.copy(outsideTapCloseMode = mode) }

    fun setHandleSwipeUpToMiniEnabled(enabled: Boolean) =
        updateSettings { it.copy(handleSwipeUpToMiniEnabled = enabled) }

    fun setPauseInLandscape(enabled: Boolean) =
        updateSettings { it.copy(pauseInLandscape = enabled) }

    fun setPauseInGameMode(enabled: Boolean) =
        updateSettings { it.copy(pauseInGameMode = enabled) }

    fun setVerboseLogEnabled(enabled: Boolean) =
        updateSettings { it.copy(verboseLogEnabled = enabled) }

    fun setRadialKeepOpen(enabled: Boolean) =
        updateSettings { it.copy(radialKeepOpen = enabled) }

    fun setRadialIconSizeDp(value: Int) =
        updateSettings {
            it.copy(radialIconSizeDp = ModulePreferences.coerceRadialIconSizeDp(value))
        }

    /** 小窗交互总开关（「轻量打开」）。默认关闭，关闭时整页能力都不介入系统。 */
    fun setZoomInteractionEnabled(enabled: Boolean) =
        updateSettings { it.copy(zoomInteractionEnabled = enabled) }

    fun setZoomMaxWindowCount(count: Int) =
        updateSettings {
            it.copy(
                zoomMaxWindowCount = ModulePreferences.coerceZoomMaxWindowCount(count),
            )
        }

    fun setZoomAllowAnyApp(enabled: Boolean) =
        updateSettings { it.copy(zoomAllowAnyApp = enabled) }

    fun setZoomLaunch(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunch = enabled) }

    // 小窗跳转的五个细分场景，各自独立存盘。
    fun setZoomLaunchNotice(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchNotice = enabled) }

    fun setZoomLaunchHeadsUp(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchHeadsUp = enabled) }

    fun setZoomLaunchControlCenter(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchControlCenter = enabled) }

    fun setZoomLaunchInterApp(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchInterApp = enabled) }

    fun setZoomLaunchSidebar(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchSidebar = enabled) }

    /** 桌面长按快捷项是否转小窗。 */
    fun setZoomLauncherShortcut(enabled: Boolean) =
        updateSettings { it.copy(zoomLauncherShortcut = enabled) }

    /** 窗口超过数量限制时是否自动关闭旧窗口（默认关闭，保持系统收成浮标的行为）。 */
    fun setAutoCloseWindow(enabled: Boolean) =
        updateSettings { it.copy(autoCloseWindow = enabled) }

    /** 桌面卡片与负一屏是否转小窗。 */
    fun setZoomLauncherCard(enabled: Boolean) =
        updateSettings { it.copy(zoomLauncherCard = enabled) }

    /** 应用选择列表是否列出系统应用。 */
    fun setShowSystemApps(enabled: Boolean) =
        updateSettings { it.copy(showSystemApps = enabled) }

    fun setBlacklistPackages(packages: List<String>) =
        updateSettings { it.copy(blacklistPackages = ModulePreferences.decodePackages(ModulePreferences.encodePackages(packages))) }

    /** 「禁用浮窗」列表；与排除列表各存各的键，互不影响。 */
    fun setZoomExcludedPackages(packages: List<String>) =
        updateSettings {
            it.copy(zoomExcludedPackages = ModulePreferences.decodePackages(ModulePreferences.encodePackages(packages)))
        }

    fun setPinnedTargets(components: List<PinnedTarget>) =
        updateSettings {
            it.copy(
                pinsSaved = true,
                pinnedTargets =
                    io.github.hoerire.flymefreeform.config.PinnedTargetCodec.normalize(components),
            )
        }

    fun requestMissingScopes() {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val current = mutableState.value
            if (!current.canRequestScope) return@execute
            val requested = current.missingScopes.toList()
            val requesting = current.copy(isRequestingScope = true, issue = null)
            activeConnection = connection.copy(confirmedState = requesting)
            mutableState.value = requesting
            try {
                connection.service.requestScope(
                    requested,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            worker.execute { refreshAfterScopeRequest(connection) }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            worker.execute { finishScopeRequestWithError(connection) }
                        }
                    },
                )
            } catch (exception: XposedService.ServiceException) {
                finishScopeRequestWithError(connection)
            } catch (exception: UnsupportedOperationException) {
                finishScopeRequestWithError(connection)
            } catch (exception: SecurityException) {
                finishScopeRequestWithError(connection)
            } catch (exception: RuntimeException) {
                finishScopeRequestWithError(connection)
            }
        }
    }

    private fun updateSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) {
        worker.execute {
            val connection = activeConnection ?: return@execute
            val previous = mutableState.value
            if (!previous.canChangeSettings) return@execute
            val next = transform(previous.settings)
            if (next == previous.settings) return@execute
            mutableState.value = previous.copy(isUpdating = true, issue = null)
            val committed = commitSettings(connection.preferences, next)
            if (activeConnection?.service !== connection.service) return@execute
            if (committed) {
                val confirmed = previous.copy(settings = next, isUpdating = false, issue = null)
                activeConnection = connection.copy(confirmedState = confirmed)
                mutableState.value = confirmed
                // 任何设置变更都推送一次：跨进程 SharedPreferences 的变更监听不可靠，
                // 只推固定项会让"详细日志""自动收窗"这类开关停留在旧快照里。
                notifySettingsChanged(next)
            } else {
                isolateFailedConnection(connection, previous)
            }
        }
    }

    private fun notifySettingsChanged(settings: ModuleSettingsSnapshot) {
        val intent = Intent(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED).apply {
            putExtra(
                ModulePreferences.EXTRA_PINNED_TARGETS,
                PinnedTargetCodec.encodeList(settings.pinnedTargets),
            )
            putExtra(ModulePreferences.EXTRA_PINS_SAVED, settings.pinsSaved)
            putExtra(ModulePreferences.EXTRA_MODULE_ENABLED, settings.enabled)
        }
        // 必须共享身份：接收方用 getSentFromUid() 校验发起者，普通广播拿到的是 -1，
        // 会被当作伪造请求丢弃（实测固定项变更从未送达 system_server）。
        val options = android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        try {
            context.sendBroadcast(intent.setPackage("android"), null, options)
            context.sendBroadcast(intent.setPackage("com.coloros.smartsidebar"), null, options)
        } catch (_: RuntimeException) {
            // Remote preference commit remains authoritative if a target process is unavailable.
        }
    }

    private fun handleServiceBind(service: XposedService) {
        if (diedBeforeBind.remove(service) != null) return
        if (liveServices.put(service, Unit) != null) return
        pendingServices[service] = Unit
        reconcileServices()
    }

    private fun handleServiceDied(service: XposedService) {
        if (liveServices.remove(service) == null) {
            diedBeforeBind[service] = Unit
            return
        }
        pendingServices.remove(service)
        unavailableServices.remove(service)
        if (activeConnection?.service === service) activeConnection = null
        reconcileServices()
    }

    private fun reconcileServices() {
        if (liveServices.size > 1) {
            mutableState.value = multipleServicesState()
            return
        }
        val service = liveServices.keys.firstOrNull()
        if (service == null) {
            activeConnection = null
            mutableState.value = FrameworkConnectionState()
            return
        }
        activeConnection?.takeIf { it.service === service }?.let { connection ->
            mutableState.value = connection.confirmedState
            return
        }
        unavailableServices[service]?.let { state ->
            mutableState.value = state
            return
        }
        if (pendingServices.remove(service) != null) {
            activateService(service)
            return
        }
        mutableState.value =
            FrameworkConnectionState(
                status = FrameworkConnectionStatus.Error,
                issue = FrameworkConnectionIssue.ConnectionFailed,
            )
    }

    private fun activateService(service: XposedService) {
        val result = probeService(service)
        if (result.connection != null) {
            unavailableServices.remove(service)
            activeConnection = result.connection
        } else {
            activeConnection = null
            unavailableServices[service] = result.state
        }
        mutableState.value = result.state
    }

    private fun probeService(service: XposedService): ProbeResult {
        var frameworkName: String? = null
        var frameworkVersion: String? = null
        var apiVersion: Int? = null
        return try {
            apiVersion = service.apiVersion
            frameworkName = service.frameworkName.normalizedMetadata()
            frameworkVersion = service.frameworkVersion.normalizedMetadata()
            val properties = service.frameworkProperties
            when {
                apiVersion < XposedService.API_102 ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.ServiceApiTooOld)
                properties and XposedService.PROP_CAP_REMOTE == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
                properties and XposedService.PROP_CAP_SYSTEM == 0L ->
                    ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.SystemCapabilityMissing)
                else -> {
                    val preferences = service.getRemotePreferences(ModulePreferences.GROUP)
                    val settings = readOrRepairSettings(preferences) ?: return ProbeResult.error(
                        frameworkName,
                        frameworkVersion,
                        apiVersion,
                        FrameworkConnectionIssue.WriteFailed,
                    )
                    val scopes = service.scope.filterTo(linkedSetOf()) { it in FrameworkConnectionState.REQUIRED_SCOPES }
                    ProbeResult.connected(service, preferences, frameworkName, frameworkVersion, apiVersion, settings, scopes)
                }
            }
        } catch (exception: XposedService.ServiceException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: UnsupportedOperationException) {
            ProbeResult.failure(frameworkName, frameworkVersion, apiVersion, FrameworkConnectionIssue.RemoteCapabilityMissing)
        } catch (exception: SecurityException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        } catch (exception: RuntimeException) {
            ProbeResult.error(frameworkName, frameworkVersion, apiVersion)
        }
    }

    private fun readOrRepairSettings(preferences: SharedPreferences): ModuleSettingsSnapshot? =
        try {
            ModuleSettingsSnapshot.readFrom(preferences)
        } catch (exception: ClassCastException) {
            ModuleSettingsSnapshot().takeIf { defaults -> commitSettings(preferences, defaults) }
        }

    // KTX edit 返回 Unit；这里必须将远端 commit 的失败结果传回 UI。
    @SuppressLint("UseKtx")
    private fun commitSettings(preferences: SharedPreferences, settings: ModuleSettingsSnapshot): Boolean =
        try {
            settings.writeTo(preferences.edit()).commit()
        } catch (exception: XposedService.ServiceException) {
            false
        } catch (exception: UnsupportedOperationException) {
            false
        } catch (exception: SecurityException) {
            false
        } catch (exception: RuntimeException) {
            false
        }

    private fun isolateFailedConnection(connection: ActiveConnection, previous: FrameworkConnectionState) {
        activeConnection = null
        val failed =
            previous.copy(
                status = FrameworkConnectionStatus.Error,
                isUpdating = false,
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.WriteFailed,
            )
        unavailableServices[connection.service] = failed
        mutableState.value = failed
    }

    private fun refreshAfterScopeRequest(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        pendingServices[connection.service] = Unit
        activeConnection = null
        reconcileServices()
    }

    private fun finishScopeRequestWithError(connection: ActiveConnection) {
        if (activeConnection?.service !== connection.service) return
        val failed =
            connection.confirmedState.copy(
                isRequestingScope = false,
                issue = FrameworkConnectionIssue.ScopeRequestFailed,
            )
        activeConnection = connection.copy(confirmedState = failed)
        mutableState.value = if (liveServices.size > 1) multipleServicesState() else failed
    }

    private fun multipleServicesState() =
        FrameworkConnectionState(
            status = FrameworkConnectionStatus.Incompatible,
            issue = FrameworkConnectionIssue.MultipleServices,
        )

    private fun String.normalizedMetadata(): String? = trim().take(MAX_METADATA_LENGTH).ifEmpty { null }

    private data class ActiveConnection(
        val service: XposedService,
        val preferences: SharedPreferences,
        val confirmedState: FrameworkConnectionState,
    )

    private data class ProbeResult(val connection: ActiveConnection?, val state: FrameworkConnectionState) {
        companion object {
            fun connected(
                service: XposedService,
                preferences: SharedPreferences,
                frameworkName: String?,
                frameworkVersion: String?,
                apiVersion: Int,
                settings: ModuleSettingsSnapshot,
                scopes: Set<String>,
            ): ProbeResult {
                val state =
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Connected,
                        frameworkName = frameworkName,
                        frameworkVersion = frameworkVersion,
                        apiVersion = apiVersion,
                        settings = settings,
                        grantedScopes = scopes,
                    )
                return ProbeResult(ActiveConnection(service, preferences, state), state)
            }

            fun failure(name: String?, version: String?, api: Int?, issue: FrameworkConnectionIssue) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Incompatible,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )

            fun error(
                name: String?,
                version: String?,
                api: Int?,
                issue: FrameworkConnectionIssue = FrameworkConnectionIssue.ConnectionFailed,
            ) =
                ProbeResult(
                    null,
                    FrameworkConnectionState(
                        status = FrameworkConnectionStatus.Error,
                        frameworkName = name,
                        frameworkVersion = version,
                        apiVersion = api,
                        issue = issue,
                    ),
                )
        }
    }

    private companion object {
        const val WORKER_THREAD_NAME = "FlymeFreeform-Service"
        const val MAX_METADATA_LENGTH = 80
    }
}
