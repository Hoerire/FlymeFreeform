package io.github.hoerire.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import io.github.hoerire.flymefreeform.apps.CatalogTransfer
import io.github.hoerire.flymefreeform.apps.SelectableEntry
import io.github.hoerire.flymefreeform.config.PinnedTarget
import io.github.hoerire.flymefreeform.config.PinnedTargetCodec
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server ↔ 侧边栏进程的传输层。
 *
 * 连接一旦建立就**常驻**：provider PREPARE 与 bindService 只在冷启动时各做一次，
 * 之后的每次启动直接复用 Messenger，只付一次 Binder 往返——原生侧边栏按钮在同一进程里，
 * 可省的即这部分固定开销。服务端进程死亡时自动作废并重建。
 */
internal class ColorOsSidebarEntryConnection(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val worker = ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "FlymeFreeform-EntryTransport") }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val reply = Messenger(Handler(handler.looper, ::receive))
    private var current: Request? = null
    private var lastFailure = -5_000L

    /** 常驻连接；null 表示尚未建立。 */
    private var link: WarmLink? = null
    private var binding = false

    /**
     * 最近一次准备阶段解析出的用户信息。
     *
     * 连接回调可能晚于请求 deadline 才到达，那时 [current] 已被超时清空；
     * 没有这份缓存就只能放弃建链，而 [binding] 已置真，后续请求再也不会重新绑定，
     * 表现为「侧边栏目录永久不可用，直到进程被杀」。
     */
    private var lastUserId = -1
    private var lastUid = -1
    private var lastUserContext: Context? = null
    private var preparing = false
    private var pendingAfterBind: (() -> Unit)? = null

    private class WarmLink(
        val binder: IBinder,
        val remote: Messenger,
        val uid: Int,
        val userId: Int,
        val userContext: Context,
    )

    // DeathRecipient 的 SAM 方法是无参的 binderDied()，拿不到具体 binder，直接作废常驻连接。
    private val death = IBinder.DeathRecipient { handler.post { dropLink(unbind = true) } }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (name != COMPONENT) return
            val warm = link
            if (warm != null && warm.binder === service) return
            val userId = current?.userId?.takeIf { it >= 0 } ?: lastUserId
            val uid = current?.uid?.takeIf { it > 0 } ?: lastUid
            val userContext = current?.userContext ?: lastUserContext
            // 常开诊断：以前这三处静默 return，绑定明明成功却一路等到 7 秒超时，
            // 日志里只剩 TIMEOUT 看不出卡在哪。现在把拿到的值直接打出来。
            if (userId < 0 || uid <= 0 || userContext == null) {
                if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
                    log(
                        Log.DEBUG,
                        "SIDEBAR_ENTRY_CONNECT_INCOMPLETE_user" + userId + "_uid" + uid +
                            "_ctx" + (userContext != null),
                        null,
                    )
                }
                // 必须作废这次绑定：binding 若停留在 true，后续请求再也不会 bindService，
                // 目录会一直读不到，直到进程被杀（整机重启才恢复，热重启不行）。
                // 这正是"热重启几次之后就读不上"的根因——热重启让上次缓存的
                // userId/uid/userContext 失效，回调走进这个分支却不复位 binding。
                dropLink(unbind = true)
                return
            }
            val created = WarmLink(service, Messenger(service), uid, userId, userContext)
            try {
                service.linkToDeath(death, 0)
            } catch (exception: RuntimeException) {
                report(exception)
                return
            }
            link = created
            handler.post {
                val waiting = pendingAfterBind
                pendingAfterBind = null
                waiting?.invoke()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handler.post { dropLink(unbind = true) }
        }

        override fun onBindingDied(name: ComponentName) {
            handler.post { dropLink(unbind = true) }
        }

        override fun onNullBinding(name: ComponentName) {
            handler.post { dropLink(unbind = true) }
        }
    }

    /**
     * 发起一次目录/启动请求。
     *
     * 冷路径：读取用户上下文 → 唤醒侧边栏 provider → bindService → 发送消息；
     * 热路径（[link] 已存在）：直接复用 Messenger，只付一次 Binder 往返。
     * 同一时刻只允许一个请求（[current]），超时由 [Request.deadline] 兜底。
     */
    fun request(
        target: PinnedTarget.Sidebar? = null,
        beforeLaunch: () -> Boolean = { true },
        onResult: (List<SelectableEntry>?, Boolean) -> Unit,
    ): Boolean {
        if (current != null) return false
        val request = Request(target, beforeLaunch, onResult)
        current = request
        handler.postAtTime(request.timeout, request.deadline)
        val warm = link
        if (warm != null) {
            // 热路径：不再碰 provider/绑定，直接下发。
            request.userId = warm.userId
            request.uid = warm.uid
            request.userContext = warm.userContext
            request.remote = warm.remote
            handler.post {
                if (current !== request) return@post
                if (!request.beforeLaunch()) {
                    finish(request)
                } else {
                    send(request, warm)
                }
            }
            return true
        }
        prepareAndBind(request)
        return true
    }

    fun cancel() {
        current?.let(::finish)
    }

    private fun prepareAndBind(request: Request) {
        if (preparing) {
            // 上一次准备还在路上：等它结束后重试一次。
            handler.postDelayed({ if (current === request && link == null) prepareAndBind(request) }, 30L)
            return
        }
        preparing = true
        work(request) {
          try {
            val userId = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
            val user = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, userId) as UserHandle
            val userContext =
                Context::class.java
                    .getMethod("createContextAsUser", UserHandle::class.java, Int::class.javaPrimitiveType)
                    .invoke(context, user, 0) as Context
            request.userId = userId
            request.userContext = userContext
            val uid = ColorOsSidebarTarget.supportedUid(userContext)
            val foreground = userContext.getSystemService(UserManager::class.java)?.isUserForeground == true
            val toggle = Settings.Secure.getInt(userContext.contentResolver, "edge_panel_toggle", -1)
            val targetUserMismatch = request.target != null && request.target.userId != userId
            if (uid == null || !foreground || toggle != 1 || targetUserMismatch) {
                log(
                    Log.WARN,
                    "SIDEBAR_ENTRY_SKIP_uid" + (uid ?: -1) +
                        "_fg" + (if (foreground) 1 else 0) +
                        "_toggle" + toggle +
                        "_tu" + (if (targetUserMismatch) 1 else 0) +
                        "_user" + userId,
                    null,
                )
                preparing = false
                handler.post { finish(request) }
                return@work
            }
            request.uid = uid
            lastUserId = userId
            lastUid = uid
            lastUserContext = userContext
            if (!request.active.get()) {
                preparing = false
                return@work
            }
            // 只在首次建立连接时唤醒侧边栏的 provider。
            val provider = userContext.contentResolver
                .acquireUnstableContentProviderClient(ColorOsSidebarTarget.PROVIDER_AUTHORITY)
                ?: throw IllegalStateException("SIDEBAR_ENTRY_PROVIDER_UNAVAILABLE")
            provider.use { it.call(ColorOsSidebarTarget.PREPARE_METHOD, null, null) }
            preparing = false
            if (!request.active.get()) return@work
            handler.post {
                if (current !== request) return@post
                val warm = link
                if (warm != null) {
                    request.remote = warm.remote
                    send(request, warm)
                    return@post
                }
                pendingAfterBind = { if (current === request) link?.let { send(request, it) } }
                if (!binding) {
                    val intent = Intent(SidebarEntryProtocol.BIND_ACTION).setComponent(COMPONENT)
                    binding = try {
                        userContext.bindService(intent, Context.BIND_AUTO_CREATE, { action -> handler.post(action) }, serviceConnection)
                    } catch (exception: RuntimeException) {
                        report(exception)
                        false
                    }
                    if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
                        log(Log.DEBUG, "SIDEBAR_ENTRY_BIND_" + binding, null)
                    }
                    if (!binding) {
                        pendingAfterBind = null
                        finish(request)
                    }
                }
            }
          } finally {
            preparing = false
          }
        }
    }

    private fun send(request: Request, warm: WarmLink) {
        if (io.github.hoerire.flymefreeform.log.VerboseLog.enabled) {
            log(Log.DEBUG, "ENTRY_SEND_" + (if (request.target == null) "LIST" else "LAUNCH"), null)
        }
        work(request) {
            val message = SidebarEntryProtocol.message(
                if (request.target == null) SidebarEntryProtocol.LIST else SidebarEntryProtocol.LAUNCH,
                request.id, request.deadline, reply,
            )
            request.target?.let { message.data.putString(SidebarEntryProtocol.TARGET, PinnedTargetCodec.encode(it)) }
            if (request.active.get()) warm.remote.send(message)
        }
    }

    private fun receive(message: Message): Boolean {
        val request = current ?: return true
        if (message.sendingUid != request.uid || message.arg1 != SidebarEntryProtocol.VERSION) return true
        try {
            val data = message.peekData()
            if (data?.getString(SidebarEntryProtocol.ID) != request.id) return true
            if (SystemClock.uptimeMillis() >= request.deadline) { finish(request); return true }
            when (message.what) {
                SidebarEntryProtocol.PREPARED -> {
                    if (request.target == null || request.confirmed) return true
                    if (!request.beforeLaunch()) { finish(request); return true }
                    request.confirmed = true
                    val warm = link ?: return true
                    work(request) {
                        warm.remote.send(
                            SidebarEntryProtocol.message(SidebarEntryProtocol.EXECUTE, request.id, request.deadline, reply),
                        )
                    }
                }
                SidebarEntryProtocol.ITEM -> {
                    if (request.target != null) { finish(request); return true }
                    val entry = SidebarEntryProtocol.readEntry(data, request.userId)
                    if (entry == null || !request.transfer.append(data.getInt(SidebarEntryProtocol.INDEX, -1), entry)) finish(request)
                }
                SidebarEntryProtocol.COMPLETE -> {
                    val entries = if (request.target == null) request.transfer.complete(data.getInt(SidebarEntryProtocol.COUNT, -1)) else null
                    finish(request, entries)
                }
                SidebarEntryProtocol.DISPATCHED -> finish(request, dispatched = request.target != null && request.confirmed)
                SidebarEntryProtocol.FAILED -> finish(request)
            }
        } catch (exception: RuntimeException) { report(exception); finish(request) }
        return true
    }

    private fun finish(request: Request, entries: List<SelectableEntry>? = null, dispatched: Boolean = false) {
        if (current !== request) return
        current = null
        request.active.set(false)
        request.transfer.cancel()
        handler.removeCallbacks(request.timeout)
        // 连接保持常驻：只让对端清掉它自己的会话，不断开绑定。
        val remote = link?.remote
        if (remote != null) {
            try {
                remote.send(SidebarEntryProtocol.message(SidebarEntryProtocol.CANCEL, request.id, reply = reply))
            } catch (exception: Exception) {
                report(exception)
                dropLink(unbind = true)
            }
        }
        try {
            request.onResult(entries, dispatched)
        } catch (exception: RuntimeException) { report(exception) }
    }

    private fun dropLink(unbind: Boolean) {
        val warm = link
        link = null
        pendingAfterBind = null
        if (warm != null) {
            try { warm.binder.unlinkToDeath(death, 0) } catch (_: RuntimeException) {}
        }
        current?.let { finish(it) }
        if (binding) {
            binding = false
            try {
                (warm?.userContext ?: context).unbindService(serviceConnection)
            } catch (_: RuntimeException) {}
        }
    }

    private fun work(request: Request, action: () -> Unit) {
        try {
            worker.execute {
                if (!request.active.get()) return@execute
                try { action() }
                catch (exception: Exception) {
                    if (exception is InvocationTargetException && exception.cause is Error) throw exception.cause as Error
                    handler.post { report(exception); finish(request) }
                }
            }
        } catch (exception: RuntimeException) { report(exception); finish(request) }
    }

    private fun report(exception: Exception) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailure >= 5_000L) {
            lastFailure = now
            log(
                Log.WARN,
                "SIDEBAR_ENTRY_TRANSPORT_FAILED_" + exception.javaClass.simpleName +
                    "_" + (exception.message ?: "-"),
                exception,
            )
        }
    }

    private inner class Request(
        val target: PinnedTarget.Sidebar?, val beforeLaunch: () -> Boolean,
        val onResult: (List<SelectableEntry>?, Boolean) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        val deadline = SystemClock.uptimeMillis() + SidebarEntryProtocol.TIMEOUT_MS
        val active = AtomicBoolean(true)
        @Volatile var userId = -1
        @Volatile var uid = -1
        @Volatile var userContext: Context? = null
        @Volatile var remote: Messenger? = null
        var confirmed = false
        val transfer = CatalogTransfer(SidebarEntryProtocol.MAX_ENTRIES, SelectableEntry::target)
        val timeout = Runnable {
            log(Log.WARN, "SIDEBAR_ENTRY_TIMEOUT_" + (if (target == null) "LIST" else "LAUNCH"), null)
            // 绑定已发起但连接还没回来：这次请求等不到结果了，先把绑定作废，
            // 下一次请求才会重新 bindService；否则会一直等一个不会再触发回调的旧绑定。
            // 超时说明这条链路已经不可信：可能绑定没回来，也可能 link 还在但对端不再回应。
            // 两种情况都作废连接，让下一次请求重新 bindService，避免一直挂在死连接上。
            finish(this)
            dropLink(unbind = true)
        }
    }

    private companion object {
        val COMPONENT = ComponentName(ColorOsSidebarTarget.PACKAGE_NAME, ColorOsSidebarTarget.SERVICE_CLASS)
    }
}
