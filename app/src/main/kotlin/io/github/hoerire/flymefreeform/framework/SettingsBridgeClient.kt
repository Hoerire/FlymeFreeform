package io.github.hoerire.flymefreeform.framework

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.SettingsBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 应用侧的设置通道。
 *
 * 框架服务只在安装或升级后投递一次，进程重启后拿不到，因此设置读写改由常驻广播完成：
 * 向 system_server 发查询或推送，同一通道回执权威快照。调用方必须在后台线程执行，
 * 单次交换最多阻塞 [SettingsBridge.TIMEOUT_MS]。
 */
internal class SettingsBridgeClient(private val context: Context) {
    /** system_server 回执：设置快照与框架信息。 */
    data class Reply(
        val settings: Map<String, Any?>,
        val frameworkName: String?,
        val frameworkVersion: String?,
        val apiVersion: Int?,
        val writeOk: Boolean,
    )

    private val lock = Any()
    private var awaiting: CountDownLatch? = null
    private var received: Reply? = null
    private var registered = false
    private var nextRequestId = 1L
    private var expectedRequestId = -1L

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ModulePreferences.ACTION_SETTINGS_SNAPSHOT) return
                // 快照决定模块行为，只接受系统进程的回执。
                if (sentFromUid != Process.SYSTEM_UID) return
                // 迟到的旧回执不能覆盖界面当前状态。
                val id = intent.getLongExtra(SettingsBridge.EXTRA_REQUEST_ID, -1L)
                if (id != synchronized(lock) { expectedRequestId }) return
                val settings = SettingsBridge.readMap(intent.extras) ?: return
                val reply =
                    Reply(
                        settings = settings,
                        frameworkName = intent.getStringExtra(SettingsBridge.EXTRA_FRAMEWORK_NAME),
                        frameworkVersion =
                            intent.getStringExtra(SettingsBridge.EXTRA_FRAMEWORK_VERSION),
                        apiVersion =
                            intent.getIntExtra(SettingsBridge.EXTRA_API_VERSION, 0)
                                .takeIf { it > 0 },
                        writeOk = intent.getBooleanExtra(SettingsBridge.EXTRA_WRITE_OK, false),
                    )
                synchronized(lock) {
                    received = reply
                    awaiting?.countDown()
                }
            }
        }

    fun start(): Boolean =
        try {
            context.registerReceiver(
                receiver,
                IntentFilter(ModulePreferences.ACTION_SETTINGS_SNAPSHOT),
                Context.RECEIVER_EXPORTED,
            )
            registered = true
            true
        } catch (_: RuntimeException) {
            false
        }

    fun close() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: RuntimeException) {
            // 进程退出过程中注销失败无需处理。
        }
    }

    /** 查询 system_server 当前快照；未连接时返回 null。 */
    fun request(): Reply? = exchange(null)

    /** 推送整份快照；返回落盘后的权威快照，失败返回 null。 */
    fun push(settings: Map<String, Any?>): Reply? = exchange(settings)

    private fun exchange(settings: Map<String, Any?>?): Reply? {
        if (!registered) return null
        val latch = CountDownLatch(1)
        val requestId = synchronized(lock) {
            received = null
            awaiting = latch
            expectedRequestId = nextRequestId++
            expectedRequestId
        }
        val delivered =
            try {
                val action =
                    if (settings == null) ModulePreferences.ACTION_SETTINGS_REQUEST
                    else ModulePreferences.ACTION_SETTINGS_PUSHED
                // 回执地址必须显式声明：系统进程按包名回执，不依赖广播身份。
                val intent =
                    Intent(action)
                        .setPackage(SYSTEM_PACKAGE)
                        .putExtra(SettingsBridge.EXTRA_REQUEST_ID, requestId)
                        .putExtra(
                            ModulePreferences.EXTRA_REQUESTER_PACKAGE,
                            context.packageName,
                        )
                if (settings != null) {
                    val extras = Bundle()
                    SettingsBridge.putMap(extras, settings)
                    intent.putExtras(extras)
                }
                // 接收方用 getSentFromUid() 校验发起者，必须共享身份。
                val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
                context.sendBroadcast(intent, null, options)
                latch.await(SettingsBridge.TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: RuntimeException) {
                false
            } catch (_: InterruptedException) {
                false
            } finally {
                synchronized(lock) { awaiting = null }
            }
        if (!delivered) return null
        return synchronized(lock) { received }
    }

    private companion object {
        /** 收件方是系统进程本身；system_server 中以包名 "android" 注册接收器。 */
        const val SYSTEM_PACKAGE = "android"
    }
}
