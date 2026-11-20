package io.github.hoerire.free.bubble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一个分身条目：包名 + 用户 + 标签 + 图标。 */
internal data class CloneApp(
    val packageName: String,
    val userId: Int,
    val label: String,
    val icon: Bitmap?,
) {
    val key: String get() = "$packageName#$userId"
    val identity: BubbleIdentity get() = BubbleIdentity(packageName, userId)
}

/**
 * 分身目录的跨进程协议。
 *
 * system_server 扫不到模块应用能读的位置，所以把结果编成字符串行随广播带过来：
 * `pkg \u0001 uid \u0001 label(Base64) \u0001 iconPng(Base64)`。
 * 标签与图标都走 Base64，避免应用名里的分隔符破坏协议。
 */
internal object CloneCatalogProtocol {
    const val ACTION_CATALOG = "io.github.hoerire.free.action.CLONE_CATALOG"
    const val ACTION_REQUEST = "io.github.hoerire.free.action.CLONE_CATALOG_REQUEST"
    const val EXTRA_ENTRIES = "clone_entries"
    const val EXTRA_REQUESTER_PACKAGE = "requester_package"
    const val MODULE_PACKAGE = "io.github.hoerire.free"

    private const val SEPARATOR = '\u0001'
    private const val MAX_ENTRIES = 64
    private const val MAX_LABEL_BYTES = 512
    private const val MAX_ICON_BYTES = 64 * 1024

    fun encode(entries: List<CloneApp>): ArrayList<String> =
        entries.take(MAX_ENTRIES).mapNotNull { entry ->
            runCatching {
                val label = entry.label.ifBlank { entry.packageName }.encodeUtf8()
                if (label.length > MAX_LABEL_BYTES) return@runCatching null
                val icon = entry.icon?.let(::encodeIcon).orEmpty()
                "${entry.packageName}$SEPARATOR${entry.userId}$SEPARATOR$label$SEPARATOR$icon"
            }.getOrNull()
        }.toCollection(ArrayList(entries.size))

    fun decode(raw: List<String>?): List<CloneApp> =
        raw.orEmpty().mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size < 4) return@mapNotNull null
            val packageName = parts[0]
            val userId = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (packageName.isEmpty() || userId <= 0) return@mapNotNull null
            val label = decodeUtf8(parts[2]) ?: packageName
            CloneApp(packageName, userId, label, decodeIcon(parts[3]))
        }.distinctBy { it.key }

    private fun encodeIcon(bitmap: Bitmap): String? =
        runCatching {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            val bytes = out.toByteArray()
            if (bytes.size > MAX_ICON_BYTES) "" else Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrDefault("")

    private fun decodeIcon(encoded: String): Bitmap? {
        if (encoded.isEmpty()) return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun String.encodeUtf8(): String =
        Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun decodeUtf8(encoded: String): String? =
        runCatching {
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull()
}

/**
 * 模块应用侧的分身目录缓存。
 *
 * 只负责「拿到并记住」：扫描在 system_server 做（只有特权进程能枚举其他用户），
 * 这里订阅一条广播、落一份磁盘缓存，界面直接读 StateFlow。
 */
internal class CloneCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private val mutableClones = MutableStateFlow(loadCached())
    val clones: StateFlow<List<CloneApp>> = mutableClones.asStateFlow()

    private val receiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                if (intent?.action != CloneCatalogProtocol.ACTION_CATALOG) return
                val entries =
                    CloneCatalogProtocol.decode(
                        intent.getStringArrayListExtra(CloneCatalogProtocol.EXTRA_ENTRIES),
                    )
                if (entries.isEmpty() && mutableClones.value.isNotEmpty()) return
                preferences
                    .edit()
                    .putString(KEY_CACHE, CloneCatalogProtocol.encode(entries).joinToString("\n"))
                    .apply()
                mutableClones.value = entries
            }
        }

    init {
        runCatching {
            appContext.registerReceiver(
                receiver,
                android.content.IntentFilter(CloneCatalogProtocol.ACTION_CATALOG),
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    /** 向 system_server 要一份最新目录；界面打开或下拉刷新时调用。 */
    fun refresh() {
        runCatching {
            appContext.sendBroadcast(
                android.content.Intent(CloneCatalogProtocol.ACTION_REQUEST)
                    // 扫描在 SystemUI 进程（持有 MANAGE_USERS），不经过 system_server。
                    .setPackage("com.android.systemui")
                    .putExtra(
                        CloneCatalogProtocol.EXTRA_REQUESTER_PACKAGE,
                        CloneCatalogProtocol.MODULE_PACKAGE,
                    ),
            )
        }
    }

    private fun loadCached(): List<CloneApp> {
        val raw = preferences.getString(KEY_CACHE, null) ?: return emptyList()
        return CloneCatalogProtocol.decode(raw.split('\n'))
    }

    private companion object {
        const val STORE_NAME = "free_clone_catalog"
        const val KEY_CACHE = "entries"
    }
}
