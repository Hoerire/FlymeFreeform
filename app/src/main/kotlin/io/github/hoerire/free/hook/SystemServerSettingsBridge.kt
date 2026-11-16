package io.github.hoerire.free.hook

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.SettingsBridge
import io.github.hoerire.free.platform.coloros.SidebarEntryProtocol
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * system_server 侧的设置通道与存储。
 *
 * 框架配置存储在本进程内不可写（写入直接抛异常），因此设置不再回写框架：
 * 由模块应用推送整份快照，本类把它落到系统进程自己的文件里当场生效，
 * 并把同一份快照回执给发起者；重启后从文件恢复，不依赖框架。
 */
internal class SystemServerSettingsBridge(
    private val context: Context,
    private val configuration: ProcessConfiguration,
    private val frameworkName: String,
    private val frameworkVersion: String,
    private val apiVersion: Int,
    private val logger: (priority: Int, code: String, throwable: Throwable?) -> Unit,
) {
    private val started = AtomicBoolean(false)

    /**
     * 存储位置：系统上下文的 filesDir 在系统进程里会抛
     * “No data directory found for package android”，因此优先用系统数据目录本身。
     */
    private val file = resolveStoreFile()
    private val lock = Any()
    private var stored: Map<String, Any?>? = null

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                logger(Log.INFO, "SETTINGS_BRIDGE_RECV_" + intent?.action + "_uid" + sentFromUid, null)
                when (intent?.action) {
                    ModulePreferences.ACTION_SETTINGS_PUSHED ->
                        handlePush(intent, sentFromUid, requesterOf(intent))
                    // 读取只回一份配置，不涉及写入：按白名单校验请求方即可。
                    ModulePreferences.ACTION_SETTINGS_REQUEST ->
                        requesterOf(intent)?.let { reply(intent, it) }
                }
            }
        }

    private fun resolveStoreFile(): File =
        File(SYSTEM_DATA_DIR, STORE_NAME).takeIf { it.parentFile?.canWrite() == true }
            ?: runCatching { File(context.filesDir, STORE_NAME) }
                .getOrElse { File(SYSTEM_DATA_DIR, STORE_NAME) }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            restore()
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SETTINGS_BRIDGE_RESTORE_FAILED", exception)
        }
        try {
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(ModulePreferences.ACTION_SETTINGS_PUSHED)
                    addAction(ModulePreferences.ACTION_SETTINGS_REQUEST)
                },
                // 发起方是本模块应用或其它作用域进程；载荷另行校验发起者身份。
                Context.RECEIVER_EXPORTED,
            )
            logger(Log.INFO, "SETTINGS_BRIDGE_READY", null)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SETTINGS_BRIDGE_UNAVAILABLE", exception)
        }
    }

    /** 开机恢复：系统进程先于模块应用启动，必须用上次落盘的值运行。 */
    private fun restore() {
        val restored =
            try {
                if (!file.exists()) null
                else SettingsBridge.decodeBytes(file.readBytes())
            } catch (exception: RuntimeException) {
                logger(Log.WARN, "SETTINGS_BRIDGE_RESTORE_FAILED", exception)
                null
            } ?: return
        stored = restored
        apply(restored)
        logger(
            Log.INFO,
            "SETTINGS_BRIDGE_RESTORED_keys_" + restored.size + "_" + file.absolutePath,
            null,
        )
    }

    private fun apply(map: Map<String, Any?>) {
        val snapshot =
            try {
                SettingsBridge.decode(map)
            } catch (exception: RuntimeException) {
                logger(Log.WARN, "SETTINGS_BRIDGE_DECODE_FAILED", exception)
                return
            }
        configuration.override(snapshot)
    }

    private fun persist(map: Map<String, Any?>): Boolean {
        val bytes = SettingsBridge.encodeBytes(map) ?: return false
        val temp = File(file.parentFile, STORE_NAME + ".tmp")
        return try {
            temp.writeBytes(bytes)
            // 原子替换：避免写到一半被读到。
            temp.renameTo(file) || file.let { temp.copyTo(it, overwrite = true); temp.delete(); true }
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SETTINGS_BRIDGE_PERSIST_FAILED", exception)
            false
        }
    }

    private fun handlePush(intent: Intent, senderUid: Int, requesterPackage: String?) {
        if (!trusted(senderUid)) return
        val map = SettingsBridge.readMap(intent.extras)
        if (map.isNullOrEmpty()) {
            logger(Log.WARN, "SETTINGS_BRIDGE_EMPTY_PAYLOAD", null)
            return
        }
        val persisted = persist(map)
        synchronized(lock) { stored = map }
        apply(map)
        logger(
            Log.INFO,
            "SETTINGS_BRIDGE_APPLIED_keys_" + map.size + "_persisted_" + persisted,
            null,
        )
        reply(intent, requesterPackage)
    }

    /** 回执带上当前快照：发起方据此确认通道可用并对齐界面。 */
    private fun reply(source: Intent?, targetPackage: String?) {
        val snapshot =
            synchronized(lock) { stored } ?: SettingsBridge.encode(configuration.snapshot)
        val extras = android.os.Bundle()
        extras.putString(SettingsBridge.EXTRA_FRAMEWORK_NAME, frameworkName)
        extras.putString(SettingsBridge.EXTRA_FRAMEWORK_VERSION, frameworkVersion)
        extras.putInt(SettingsBridge.EXTRA_API_VERSION, apiVersion)
        extras.putBoolean(SettingsBridge.EXTRA_WRITE_OK, true)
        extras.putLong(
            SettingsBridge.EXTRA_REQUEST_ID,
            source?.getLongExtra(SettingsBridge.EXTRA_REQUEST_ID, -1L) ?: -1L,
        )
        SettingsBridge.putMap(extras, snapshot)
        val intent =
            Intent(ModulePreferences.ACTION_SETTINGS_SNAPSHOT).apply {
                setPackage(targetPackage?.takeIf { it.isNotEmpty() } ?: SidebarEntryProtocol.MODULE_PACKAGE)
                putExtras(extras)
            }
        try {
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendBroadcast(intent, null, options)
        } catch (exception: RuntimeException) {
            logger(Log.WARN, "SETTINGS_BRIDGE_REPLY_FAILED", exception)
        }
    }

    /** 请求方自报的包名：只认已知的作用域进程与模块应用，其余一律不回复。 */
    private fun requesterOf(intent: Intent?): String? =
        intent?.getStringExtra(ModulePreferences.EXTRA_REQUESTER_PACKAGE)
            ?.takeIf { it in ALLOWED_REQUESTERS }

    /** 只接受本模块应用（或系统进程）发起的广播：载荷决定模块行为，不能由第三方写入。 */
    private fun trusted(sender: Int): Boolean {
        if (sender < 0) {
            logger(Log.WARN, "SETTINGS_BRIDGE_UNVERIFIED_SENDER", null)
            return false
        }
        if (sender == android.os.Process.SYSTEM_UID) return true
        val expected =
            try {
                context.packageManager
                    .getApplicationInfo(
                        SidebarEntryProtocol.MODULE_PACKAGE,
                        android.content.pm.PackageManager.ApplicationInfoFlags.of(0),
                    )
                    .uid
            } catch (exception: RuntimeException) {
                logger(Log.WARN, "SETTINGS_BRIDGE_MODULE_UID_UNAVAILABLE", exception)
                return false
            }
        if (sender != expected) {
            logger(Log.WARN, "SETTINGS_BRIDGE_FOREIGN_SENDER", null)
            return false
        }
        return true
    }

    private companion object {
        /** 允许索取配置的进程：模块应用、系统进程与两个作用域进程。 */
        val ALLOWED_REQUESTERS =
            setOf(
                "android",
                SidebarEntryProtocol.MODULE_PACKAGE,
                "com.android.systemui",
                "com.coloros.smartsidebar",
                "com.android.launcher",
                "com.oplus.contentportal",
            )

        const val STORE_NAME = "free_settings.bin"
        const val SYSTEM_DATA_DIR = "/data/system"
    }
}
