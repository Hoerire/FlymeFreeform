package io.github.hoerire.free.framework

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.config.OutsideTapCloseMode
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.PinnedTargetCodec
import io.github.hoerire.free.config.SettingsBridge
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
    /** 常驻设置通道；框架服务只在安装后投递一次，进程重启后不可用，因此以本通道为主。 */
    private var bridge: SettingsBridgeClient? = null
    private var bridgeRetries = 0

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
        // 先走常驻通道：进程重启后框架不会再投递服务，这条路才是常态。
    }

    /** 回到前台时若仍未连上，再试一次通道：进程可能早于系统进程就绪。 */
    fun retryIfNeeded() {
        if (!started.get() || bridge != null) return
        bridgeRetries = 0
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

    fun setBackdropLevel(level: Int) =
        updateSettings {
            it.copy(backdropLevel = ModulePreferences.coerceBackdropLevel(level))
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

    /** 自由拖拽：改写系统长按拖拽的准入名单。 */
    fun setFreeDragEnabled(enabled: Boolean) =
        updateSettings { it.copy(freeDragEnabled = enabled) }

    /** 拖拽触发延迟（毫秒）：写回配置，由 system_server 应用到系统长按阈值。 */
    fun setDragLongPressMs(ms: Int) =
        updateSettings { it.copy(dragLongPressMs = ModulePreferences.coerceDragLongPressMs(ms)) }

    fun setRadialIconSizeDp(value: Int) =
        updateSettings {
            it.copy(radialIconSizeDp = ModulePreferences.coerceRadialIconSizeDp(value))
        }

    /** 小窗并发上限。 */
    fun setZoomMaxWindowCount(count: Int) =
        updateSettings {
            it.copy(
                zoomMaxWindowCount = ModulePreferences.coerceZoomMaxWindowCount(count),
            )
        }

    fun setZoomAllowAnyApp(enabled: Boolean) =
        updateSettings { it.copy(zoomAllowAnyApp = enabled) }

    // 小窗跳转的细分场景，各自独立存盘、独立判定。
    fun setZoomLaunchNotice(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchNotice = enabled) }

    fun setZoomLaunchHeadsUp(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchHeadsUp = enabled) }

    fun setZoomLaunchControlCenter(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchControlCenter = enabled) }

    fun setZoomLaunchInterApp(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchInterApp = enabled) }

    fun setZoomLaunchInterAppResult(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchInterAppResult = enabled) }

    fun setZoomLaunchShareRelay(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchShareRelay = enabled) }

    fun setZoomLaunchClipboard(enabled: Boolean) =
        updateSettings { it.copy(zoomLaunchClipboard = enabled) }

    /** 窗口超过数量限制时是否自动关闭旧窗口（默认关闭，保持系统收成浮标的行为）。 */
    fun setAutoCloseWindow(enabled: Boolean) =
        updateSettings { it.copy(autoCloseWindow = enabled) }

    /** 长按迷你窗静音/恢复。 */
    fun setLongPressMute(enabled: Boolean) =
        updateSettings { it.copy(longPressMuteEnabled = enabled) }

    /** 收成贴边浮标后是否保持应用继续运行（默认关闭，保持系统原生行为）。 */
    fun setFloatHandleSwitch(enabled: Boolean) =
        updateSettings { it.copy(floatHandleSwitch = enabled) }

    fun setFloatHandleMaxCount(count: Int) =
        updateSettings {
            it.copy(floatHandleMaxCount = ModulePreferences.coerceFloatHandleMaxCount(count))
        }
    fun setFloatKeepAlive(enabled: Boolean) =
        updateSettings { it.copy(floatKeepAlive = enabled) }

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
                    io.github.hoerire.free.config.PinnedTargetCodec.normalize(components),
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

    /** 建立常驻通道并取一次权威快照；不可用时保持未连接，等待框架服务（安装后仅一次）。 */
    private fun activateBridge() {
        if (bridge != null) return
        val client = SettingsBridgeClient(context)
        if (!client.start()) {
            scheduleBridgeRetry()
            return
        }
        val reply = client.request()
        if (reply == null) {
            client.close()
            scheduleBridgeRetry()
            return
        }
        connectBridge(client, reply)
    }

    /**
     * 常驻重试：模块可能晚于应用就绪（开机早期、系统进程重启）。
     * 按固定间隔补试，连上为止；用户不需要反复开关应用。
     */
    private fun scheduleBridgeRetry() {
        if (bridge != null || bridgeRetries >= MAX_BRIDGE_RETRIES) return
        bridgeRetries++
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { worker.execute { if (bridge == null) activateBridge() } },
            BRIDGE_RETRY_INTERVAL_MS,
        )
    }

    private fun connectBridge(client: SettingsBridgeClient, reply: SettingsBridgeClient.Reply) {
        val settings =
            try {
                SettingsBridge.decode(reply.settings)
            } catch (_: RuntimeException) {
                client.close()
                return
            }
        bridge = client
        bridgeRetries = 0
        mutableState.value =
            FrameworkConnectionState(
                status = FrameworkConnectionStatus.Connected,
                frameworkName = reply.frameworkName,
                frameworkVersion = reply.frameworkVersion,
                apiVersion = reply.apiVersion,
                settings = settings,
                // 通道可用即代表模块已加载在系统进程内；其余作用域由框架自身管理。
                grantedScopes = FrameworkConnectionState.REQUIRED_SCOPES,
            )
    }

    /** 经常驻通道写入：先乐观上屏，再用回执校正，写失败才回退。 */
    private fun updateViaBridge(
        client: SettingsBridgeClient,
        transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot,
    ) {
        val previous = mutableState.value
        if (previous.status != FrameworkConnectionStatus.Connected) return
        val next = transform(previous.settings)
        if (next == previous.settings) return
        // 开关按下即生效：等待落盘期间界面不再停留在旧值，否则会被读成“点了没反应”。
        mutableState.value = previous.copy(settings = next, isUpdating = true, issue = null)
        val reply = client.push(SettingsBridge.encode(next))
        if (reply == null) {
            bridge = null
            client.close()
            mutableState.value =
                FrameworkConnectionState(
                    status = FrameworkConnectionStatus.Waiting,
                    frameworkName = previous.frameworkName,
                    frameworkVersion = previous.frameworkVersion,
                    apiVersion = previous.apiVersion,
                    settings = previous.settings,
                )
            scheduleBridgeRetry()
            return
        }
        val applied =
            try {
                SettingsBridge.decode(reply.settings)
            } catch (_: RuntimeException) {
                next
            }
        mutableState.value = previous.copy(settings = applied, isUpdating = false, issue = null)
        notifySettingsChanged(applied)
    }

    private fun updateSettings(transform: (ModuleSettingsSnapshot) -> ModuleSettingsSnapshot) {
        worker.execute {
            val channel = bridge
            if (channel != null) {
                updateViaBridge(channel, transform)
                return@execute
            }
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

    /**
     * 把整份设置推给各作用域进程。
     *
     * 框架配置存储在本进程不可写，设置只能由模块应用下发：system_server 落盘并生效，
     * SystemUI 与侧边栏各自覆盖内存快照。载荷带发起者身份，接收方校验 UID。
     */
    private fun notifySettingsChanged(settings: ModuleSettingsSnapshot) {
        val options =
            android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        // 2.2 的做法：每次设置变更都发 PINNED_TARGETS_CHANGED（带 enabled/pinsSaved），
        // 接收方先 forceReload 远程偏好再叠加这次的固定项，改动实时生效、无需重启进程。
        val extras = android.os.Bundle()
        SettingsBridge.putMap(extras, SettingsBridge.encode(settings))
        extras.putString(
            ModulePreferences.EXTRA_PINNED_TARGETS,
            PinnedTargetCodec.encodeList(settings.pinnedTargets),
        )
        extras.putBoolean(ModulePreferences.EXTRA_PINS_SAVED, settings.pinsSaved)
        extras.putBoolean(ModulePreferences.EXTRA_MODULE_ENABLED, settings.enabled)
        listOf("android", "com.android.launcher", "com.coloros.smartsidebar", "com.oplus.contentportal").forEach { target ->
            val intent =
                Intent(ModulePreferences.ACTION_PINNED_TARGETS_CHANGED).apply {
                    setPackage(target)
                    putExtras(extras)
                }
            try {
                context.sendBroadcast(intent, null, options)
            } catch (_: RuntimeException) {
                // 目标进程未运行时忽略：它启动后会主动索取当前设置。
            }
        }
    }

    private fun handleServiceBind(service: XposedService) {
        if (bridge != null) return
        if (diedBeforeBind.remove(service) != null) return
        if (liveServices.put(service, Unit) != null) return
        pendingServices[service] = Unit
        reconcileServices()
    }

    private fun handleServiceDied(service: XposedService) {
        if (bridge != null) return
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
        const val BRIDGE_RETRY_INTERVAL_MS = 2_000L
        /** 最多补试一分钟：再久说明模块没在运行，等下次进入页面再试。 */
        const val MAX_BRIDGE_RETRIES = 30

        const val WORKER_THREAD_NAME = "Free-Service"
        const val MAX_METADATA_LENGTH = 80
    }
}
