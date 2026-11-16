package io.github.hoerire.flymefreeform.platform.coloros

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * 让系统自己弹出原生「全部」面板。
 *
 * ColorOS 17.5.4 起侧边栏 UI 服务换成了 AIDL（`IUiProcessHandler`），2.7.2 的 Messenger
 * 握手对不上，会退到内建面板（本版本已移除）→ 更多面板打不开。
 * 这里直接绑 `EDGE_PANEL_UI_SERVICE`，按交易码让侧边栏弹出原生面板。
 */
internal class NativeSidebarPanel(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val done = pending
                pending = null
                if (binder == null) {
                    handler.post { done?.invoke(false) }
                    return
                }
                val opened =
                    runCatching {
                        TRANSACT_CODES.forEach { code ->
                            val data = Parcel.obtain()
                            val reply = Parcel.obtain()
                            try {
                                data.writeInterfaceToken(DESCRIPTOR)
                                binder.transact(code, data, reply, 0)
                            } finally {
                                reply.recycle()
                                data.recycle()
                            }
                        }
                        true
                    }.onFailure { log(Log.WARN, "NATIVE_PANEL_TRANSACT_FAILED", it) }
                        .getOrDefault(false)
                handler.post { done?.invoke(opened) }
                runCatching { context.unbindService(this) }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                pending?.let { val cb = it; pending = null; handler.post { cb(false) } }
            }
        }

    private var pending: ((Boolean) -> Unit)? = null

    /** 绑定并让系统弹面板；回调是否成功，超时按失败处理。 */
    fun open(onResult: (Boolean) -> Unit): Boolean {
        if (pending != null) {
            onResult(false)
            return false
        }
        pending = onResult
        val intent = Intent(ACTION).setPackage(SIDEBAR_PACKAGE)
        val bound =
            runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
                .onFailure { log(Log.WARN, "NATIVE_PANEL_BIND_FAILED", it) }
                .getOrDefault(false)
        if (!bound) {
            pending = null
            onResult(false)
            return false
        }
        handler.postDelayed({
            pending?.let {
                val cb = it
                pending = null
                log(Log.WARN, "NATIVE_PANEL_TIMEOUT", null)
                runCatching { context.unbindService(connection) }
                cb(false)
            }
        }, TIMEOUT_MS)
        return true
    }

    private companion object {
        const val SIDEBAR_PACKAGE = "com.coloros.smartsidebar"
        const val ACTION = "oplus.intent.action.EDGE_PANEL_UI_SERVICE"
        const val DESCRIPTOR = "com.oplus.smartsidebar.aidl.IUiProcessHandler"
        val TRANSACT_CODES = intArrayOf(0x0c, 0x0b, 0x0d)
        const val TIMEOUT_MS = 1200L
    }
}
