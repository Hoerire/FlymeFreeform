package io.github.hoerire.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Messenger
import android.os.SystemClock
import android.util.Log
import io.github.hoerire.flymefreeform.apps.SelectableEntry
import io.github.hoerire.flymefreeform.config.PinnedTarget
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 设置页只能请求目录；启动能力不通过广播暴露。注册与 system_server 同生命周期。 */
internal class ColorOsEntryCatalogBroker(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
    private val onCatalog: (List<SelectableEntry>) -> Unit,
) {
    private val connection = ColorOsSidebarEntryConnection(context, handler, log)
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { task -> Thread(task, "FlymeFreeform-CatalogRelay") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val callbacks = mutableListOf<(List<SelectableEntry>?) -> Unit>()
    private var refreshing = false
    private var launching = false
    private var registered = false
    private var lastRequestAt = -1_000L

    /** 首次启动/固定项变化时若侧边栏尚未就绪，请求会失败；按退避补几次，避免扇形长期缺快捷项。 */
    private var retryCount = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SidebarEntryProtocol.CATALOG_ACTION ||
                sentFromPackage != SidebarEntryProtocol.MODULE_PACKAGE || sentFromUid < 0
            ) {
                log(
                    Log.WARN,
                    "ENTRY_CATALOG_DROP_IDENTITY_ac" + intent?.action +
                        "_pkg" + sentFromPackage + "_uid" + sentFromUid,
                    null,
                )
                return
            }
            val sender = sentFromUid
            try {
                val id = intent.getStringExtra(SidebarEntryProtocol.ID)
                if (id == null) {
                    log(Log.WARN, "ENTRY_CATALOG_DROP_NO_ID", null)
                    return
                }
                val deadline = intent.getLongExtra(SidebarEntryProtocol.DEADLINE, 0)
                val reply = intent.getParcelableExtra(SidebarEntryProtocol.REPLY, Messenger::class.java)
                if (reply == null) {
                    log(Log.WARN, "ENTRY_CATALOG_DROP_NO_REPLY", null)
                    return
                }
                val now = SystemClock.uptimeMillis()
                if (!SidebarProtocol.isValidRequestId(id) ||
                    !SidebarProtocol.isValidDeadline(deadline, now, SidebarEntryProtocol.TIMEOUT_MS) ||
                    now - lastRequestAt < 500L
                ) {
                    log(
                        Log.WARN,
                        "ENTRY_CATALOG_DROP_INVALID_id" + SidebarProtocol.isValidRequestId(id) +
                            "_left" + (deadline - now) +
                            "_since" + (now - lastRequestAt),
                        null,
                    )
                    return
                }
                lastRequestAt = now
                worker.execute {
                    try {
                        val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
                        // 系统按签名权限过滤广播后，仍核对实际 UID 与当前用户，拒绝同证书其他包。
                        val userContext = Context::class.java.getMethod("createContextAsUser", android.os.UserHandle::class.java, Int::class.javaPrimitiveType)
                            .invoke(this@ColorOsEntryCatalogBroker.context, android.os.UserHandle.getUserHandleForUid(sender), 0) as Context
                        val expected = userContext.packageManager.getApplicationInfo(
                            SidebarEntryProtocol.MODULE_PACKAGE, PackageManager.ApplicationInfoFlags.of(0),
                        ).uid
                        if (sender != expected || sender / 100_000 != user || SystemClock.uptimeMillis() >= deadline) {
                            handler.post {
                                log(
                                    Log.WARN,
                                    "ENTRY_CATALOG_DROP_SENDER_s" + sender + "_e" + expected +
                                        "_user" + user + "_left" + (deadline - SystemClock.uptimeMillis()),
                                    null,
                                )
                            }
                            return@execute
                        }
                        handler.post {
                            refresh { entries ->
                                sendCatalog(reply, id, deadline, entries)
                            }
                        }
                    } catch (exception: Exception) { handler.post { log(Log.WARN, "ENTRY_CATALOG_REQUEST_FAILED", null) } }
                }
            } catch (exception: RuntimeException) { log(Log.WARN, "ENTRY_CATALOG_REQUEST_INVALID", null) }
        }
    }

    fun start() {
        if (registered) return
        try {
            context.registerReceiver(receiver, IntentFilter(SidebarEntryProtocol.CATALOG_ACTION),
                SidebarEntryProtocol.CATALOG_PERMISSION, handler, Context.RECEIVER_EXPORTED)
            // 侧边栏进程就绪后才读目录：只读一次，成功即止（事件驱动，不做轮询重试）。
            context.registerReceiver(
                readyReceiver,
                IntentFilter(io.github.hoerire.flymefreeform.config.ModulePreferences.ACTION_SIDEBAR_READY),
                null,
                handler,
                Context.RECEIVER_EXPORTED,
            )
            // 侧边栏进程点「识屏」时自己不启动（后台会被拒），改由它发这条广播，这里以系统身份代发。
            context.registerReceiver(identifyReceiver, IntentFilter(ScreenIdentifyStarter.REQUEST_ACTION),
                null, handler, Context.RECEIVER_EXPORTED)
            registered = true
        } catch (exception: RuntimeException) {
            log(Log.WARN, "ENTRY_CATALOG_RECEIVER_UNAVAILABLE", exception)
        }
    }

    /**
     * 识屏代发请求。
     *
     * 不挂签名权限（侧边栏进程签名不同），改为核对「发起包名 + uid」：
     * 只有 `com.coloros.smartsidebar` 自己的广播才执行，且动作是本模块独有。
     */
    private val identifyReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != ScreenIdentifyStarter.REQUEST_ACTION) return
            if (sentFromPackage != ScreenIdentifyStarter.SIDEBAR_PACKAGE || sentFromUid < 0) {
                log(Log.WARN, "SCREEN_IDENTIFY_DROP_IDENTITY_pkg" + sentFromPackage + "_uid" + sentFromUid, null)
                return
            }
            val expected = try {
                context.packageManager.getApplicationInfo(
                    ScreenIdentifyStarter.SIDEBAR_PACKAGE,
                    PackageManager.ApplicationInfoFlags.of(0),
                ).uid
            } catch (t: Throwable) {
                log(Log.WARN, "SCREEN_IDENTIFY_DROP_NO_TARGET", t)
                return
            }
            if (sentFromUid != expected) {
                log(Log.WARN, "SCREEN_IDENTIFY_DROP_UID_" + sentFromUid + "_" + expected, null)
                return
            }
            val x = intent.getFloatExtra("first_point_down_x", 0f)
            val y = intent.getFloatExtra("first_point_down_y", 0f)
            ScreenIdentifyStarter.start(context, x, y)
        }
    }

    /** 侧边栏进程就绪广播：校验来源后立即读一次目录。 */
    private val readyReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != io.github.hoerire.flymefreeform.config.ModulePreferences.ACTION_SIDEBAR_READY) return
            val pkg = sentFromPackage
            if (pkg != "com.coloros.smartsidebar" || sentFromUid < 0) {
                log(Log.WARN, "SIDEBAR_READY_DROP_IDENTITY_pkg" + pkg + "_uid" + sentFromUid, null)
                return
            }
            retryCount = 0
            log(Log.INFO, "SIDEBAR_READY_CATALOG_REQUEST", null)
            refresh()
        }
    }

    fun refresh(onComplete: ((List<SelectableEntry>?) -> Unit)? = null) {
        if (onComplete != null) {
            if (callbacks.size >= 2) { onComplete(null); return }
            callbacks += onComplete
        }
        // 已有请求在跑属正常时序：回调仍排在 callbacks 里，会随本轮结果一起送达，不是失败。
        if (refreshing) return
        refreshing = true
        if (!connection.request { entries, _ -> publish(entries) }) {
            log(Log.WARN, "ENTRY_CATALOG_CONNECTION_BUSY", null)
            publish(null)
        }
    }

    fun launch(target: PinnedTarget.Sidebar, beforeLaunch: () -> Boolean) {
        // 识屏：侧边栏进程在后台上，前台服务启动会被系统拒绝；改由 system_server 代发同一个 Intent。
        if (target.alias == ScreenIdentifyStarter.ALIAS) {
            connection.cancel()
            if (beforeLaunch() && ScreenIdentifyStarter.start(context, 0f, 0f)) {
                log(Log.DEBUG, "SYSTEM_ENTRY_DISPATCHED_" + target.alias + "_direct", null)
            } else {
                log(Log.WARN, "PINNED_ENTRY_NOT_DISPATCHED", null)
            }
            return
        }
        connection.cancel()
        launching = true
        if (!connection.request(target, beforeLaunch) { _, dispatched ->
                launching = false
                if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
                    log(
                        Log.DEBUG,
                        "SYSTEM_ENTRY_" + (if (dispatched) "DISPATCHED_" else "NOT_DISPATCHED_") + target.alias,
                        null,
                    )
                }
                if (!dispatched) log(Log.WARN, "PINNED_ENTRY_NOT_DISPATCHED", null)
            }) { launching = false; log(Log.WARN, "PINNED_ENTRY_BUSY", null) }
    }

    fun cancelLaunch() { if (launching) connection.cancel() }

    fun cancel() = connection.cancel()

    private companion object {
        // 正常由"侧边栏就绪"事件驱动；这里只保留一次兜底，避免重复读取的开销。
        /**
         * 失败重试上限。
         *
         * 重启 SystemUI 时侧边栏进程往往还没起来，绑定要等它冷启动。
         * 之前只重试 1 次、间隔 2 秒，几乎必然错过窗口期，表现为"目录时有时无"。
         * 现在按 12 秒一轮重试，给足冷启动时间；一旦拿到条目就把计数归零，
         * 恢复正常后不会再有任何重试。
         */
        const val MAX_RETRIES = 10
        val RETRY_DELAYS_MS = longArrayOf(12_000L)
    }

    fun invalidate() {
        connection.cancel()
        onCatalog(emptyList())
    }

    private fun publish(entries: List<SelectableEntry>?) {
        refreshing = false
        if (entries == null && retryCount < MAX_RETRIES) {
            val delay = RETRY_DELAYS_MS[retryCount.coerceIn(0, RETRY_DELAYS_MS.lastIndex)]
            retryCount++
            log(Log.DEBUG, "ENTRY_CATALOG_RETRY_" + retryCount + "_in" + delay, null)
            handler.postDelayed({ refresh() }, delay)
            return
        }
        if (entries != null) retryCount = 0
        if (entries != null) onCatalog(entries)
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it(entries) }
    }

    private fun sendCatalog(reply: Messenger, id: String, deadline: Long, entries: List<SelectableEntry>?) {
        try {
            worker.execute {
                try {
                    if (entries != null) {
                        for ((index, entry) in entries.withIndex()) {
                            if (SystemClock.uptimeMillis() >= deadline) return@execute
                            reply.send(SidebarEntryProtocol.item(id, index, entry))
                        }
                    }
                    if (SystemClock.uptimeMillis() < deadline) reply.send(SidebarEntryProtocol.message(
                        if (entries == null) SidebarEntryProtocol.FAILED else SidebarEntryProtocol.COMPLETE, id,
                    ).apply { data.putInt(SidebarEntryProtocol.COUNT, entries?.size ?: 0) })
                } catch (_: android.os.RemoteException) {
                    // 页面或 App 已离开，剩余目录不再发送。
                    log(Log.DEBUG, "ENTRY_CATALOG_REPLY_DISCONNECTED", null)
                }
                if (entries == null) log(Log.WARN, "ENTRY_CATALOG_REPLY_FAILED", null)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            log(Log.WARN, "ENTRY_CATALOG_REPLY_QUEUE_FULL", null)
        }
    }
}
