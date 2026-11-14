package io.github.hoerire.flymefreeform.apps

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import io.github.hoerire.flymefreeform.platform.coloros.SidebarEntryProtocol
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SidebarCatalogStatus { Idle, Loading, Ready, Unavailable }
internal data class SidebarCatalogState(
    val entries: List<SelectableEntry> = emptyList(),
    val status: SidebarCatalogStatus = SidebarCatalogStatus.Idle,
)

/** 仅在页面可见时请求；缓存只供显示，目录失败时不允许添加旧条目。 */
internal class SidebarEntryRepository(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val reply = Messenger(Handler(handler.looper, ::receive))
    private val mutableState = MutableStateFlow(SidebarCatalogState())
    val state = mutableState.asStateFlow()
    private var id: String? = null
    private var transfer: CatalogTransfer<SelectableEntry, io.github.hoerire.flymefreeform.config.PinnedTarget>? = null
    private var attempt = 0
    private val timeout = Runnable { fail() }
    // 重试必须复用同一条计数链路：调 refresh() 会把 attempt 归零，变成无限重试。
    private val retry = Runnable {
        if (mutableState.value.status == SidebarCatalogStatus.Loading) request()
    }

    fun refresh() {
        cancel()
        attempt = 0
        request()
    }

    /** 发起一次目录请求；失败由 [fail] 按退避重试，最多 [MAX_ATTEMPTS] 次。 */
    private fun request() {
        val request = UUID.randomUUID().toString()
        id = request
        transfer = CatalogTransfer(SidebarEntryProtocol.MAX_ENTRIES, SelectableEntry::target)
        mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Loading)
        handler.postDelayed(timeout, SidebarEntryProtocol.TIMEOUT_MS)
        try {
            context.sendBroadcast(Intent(SidebarEntryProtocol.CATALOG_ACTION).setPackage("android").apply {
                putExtra(SidebarEntryProtocol.ID, request)
                putExtra(SidebarEntryProtocol.DEADLINE, SystemClock.uptimeMillis() + SidebarEntryProtocol.TIMEOUT_MS)
                putExtra(SidebarEntryProtocol.REPLY, reply)
            }, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
        } catch (_: RuntimeException) { fail() }
    }

    fun cancel() {
        id = null
        transfer?.cancel()
        transfer = null
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(retry)
        if (mutableState.value.status == SidebarCatalogStatus.Loading) {
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Idle)
        }
    }

    private fun receive(message: Message): Boolean {
        if (id == null || message.sendingUid != Process.SYSTEM_UID || message.arg1 != SidebarEntryProtocol.VERSION) return true
        try {
            val data = message.peekData() ?: return true
            if (data.getString(SidebarEntryProtocol.ID) != id) return true
            when (message.what) {
                SidebarEntryProtocol.ITEM -> {
                    val entry = SidebarEntryProtocol.readEntry(data, (Process.myUid() / 100_000))
                    if (entry == null || transfer?.append(data.getInt(SidebarEntryProtocol.INDEX, -1), entry) != true) fail()
                }
                SidebarEntryProtocol.COMPLETE -> {
                    val entries = transfer?.complete(data.getInt(SidebarEntryProtocol.COUNT, -1))
                    if (entries == null) fail() else {
                        cancel()
                        mutableState.value = SidebarCatalogState(entries, SidebarCatalogStatus.Ready)
                    }
                }
                SidebarEntryProtocol.FAILED -> fail()
            }
        } catch (_: RuntimeException) { fail() }
        return true
    }

    private fun fail() {
        cancel()
        // 重试用尽仍失败：如果之前成功读到过目录，就继续沿用那份结果，
        // 不要把界面切成「暂不可用」，否则固定位的工具/快捷项会变空格。
        if (mutableState.value.entries.isNotEmpty()) {
            mutableState.value = mutableState.value.copy(
                status = SidebarCatalogStatus.Ready,
                entries = mutableState.value.entries,
            )
            return
        }
        // 侧边栏进程常在开机/重启后晚于模块就绪，一次失败就置 Unavailable 会让
        // 「更多」面板一直不可用；这里退避重试，成功后自然恢复。
        if (attempt < MAX_ATTEMPTS) {
            attempt++
            mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Loading)
            handler.postDelayed(retry, RETRY_DELAYS_MS[(attempt - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)])
            return
        }
        mutableState.value = mutableState.value.copy(status = SidebarCatalogStatus.Unavailable)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(600L, 1_500L, 3_000L)
    }
}
