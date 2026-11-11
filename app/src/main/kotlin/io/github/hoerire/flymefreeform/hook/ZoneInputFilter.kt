package io.github.hoerire.flymefreeform.hook

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.InputEvent
import io.github.libxposed.api.XposedModule

/**
 * 全局输入过滤器（第一阶段：只透传 + 探针）。
 *
 * Gesture Monitor 是多份副本，逐个吞事件拦不住彼此；InputFilter 运行在分发之前，
 * 是唯一能一次性作用于所有接收方的闸门。这一版只验证「能装上、能收到事件、透传不影响触摸」，
 * 暂不丢弃任何事件。
 */
internal class ZoneInputFilter(
    private val module: XposedModule,
) : Binder() {
    private var host: IBinder? = null
    private var events = 0

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            TRANSACTION_install -> {
                data.enforceInterface(DESCRIPTOR)
                host = data.readStrongBinder()
                module.log(Log.INFO, TAG, "INPUT_FILTER_INSTALLED", null)
                return true
            }
            TRANSACTION_uninstall -> {
                data.enforceInterface(DESCRIPTOR)
                host = null
                module.log(Log.INFO, TAG, "INPUT_FILTER_UNINSTALLED", null)
                return true
            }
            TRANSACTION_filterInputEvent -> {
                data.enforceInterface(DESCRIPTOR)
                val event = runCatching {
                    data.readParcelable<InputEvent>(InputEvent::class.java.classLoader)
                }.getOrNull()
                val policyFlags = data.readInt()
                if (events < PROBE_LIMIT) {
                    events++
                    module.log(Log.INFO, TAG, "INPUT_FILTER_EVENT_" + (event?.javaClass?.simpleName ?: "?"), null)
                }
                forward(event, policyFlags)
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    /** Proxy 路径（setInputFilter 走接口调用）的入口。 */
    fun filterFromProxy(args: Array<Any?>?) {
        val event = args?.getOrNull(0) as? InputEvent
        val policyFlags = (args?.getOrNull(1) as? Int) ?: 0
        if (events < PROBE_LIMIT) {
            events++
            module.log(Log.INFO, TAG, "INPUT_FILTER_EVENT_" + (event?.javaClass?.simpleName ?: "?"), null)
        }
        forward(event, policyFlags)
    }

    /** 原样转发给系统；第一阶段不丢弃任何事件。 */
    private fun forward(event: InputEvent?, policyFlags: Int) {
        val target = host ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(HOST_DESCRIPTOR)
            data.writeParcelable(event, 0)
            data.writeInt(policyFlags)
            target.transact(TRANSACTION_sendInputEvent, data, reply, 0)
        } catch (exception: Throwable) {
            module.log(Log.WARN, TAG, "INPUT_FILTER_FORWARD_FAILED", exception)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val DESCRIPTOR = "android.hardware.input.IInputFilter"
        const val HOST_DESCRIPTOR = "android.hardware.input.IInputFilterHost"
        const val TRANSACTION_install = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_uninstall = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_filterInputEvent = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_sendInputEvent = IBinder.FIRST_CALL_TRANSACTION
        const val PROBE_LIMIT = 8
    }
}