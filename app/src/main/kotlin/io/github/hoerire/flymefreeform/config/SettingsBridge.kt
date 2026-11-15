package io.github.hoerire.flymefreeform.config

import android.content.SharedPreferences

/**
 * 模块应用与 hook 进程之间的设置通道协议。
 *
 * 框架服务只在模块安装或升级后投递一次，进程重启后不再投递（实测：装完首次启动有 binder 投递，
 * 进程结束再打开则完全收不到），因此设置读写不再依赖该服务，改走常驻广播通道：
 * 模块应用把整份快照推给 system_server，system_server 落盘并发回权威快照。
 */
internal object SettingsBridge {
    const val EXTRA_SETTINGS_MAP = "settings_map"
    const val EXTRA_FRAMEWORK_NAME = "framework_name"
    const val EXTRA_FRAMEWORK_VERSION = "framework_version"
    const val EXTRA_API_VERSION = "api_version"
    /** 是否真的落盘：纯回执字段，App 据此决定保留乐观值还是回退。 */
    const val EXTRA_WRITE_OK = "write_ok"
    /** 请求序号：回执必须与最近一次请求一致，迟到的旧回执不能覆盖当前状态。 */
    const val EXTRA_REQUEST_ID = "request_id"

    /** 等待 system_server 回执的上限；超时按未连接处理。 */
    const val TIMEOUT_MS = 1500L

    fun encode(snapshot: ModuleSettingsSnapshot): HashMap<String, Any?> {
        val map = HashMap<String, Any?>()
        snapshot.writeTo(MapPreferences(map).edit()).commit()
        return map
    }

    fun decode(map: Map<String, Any?>): ModuleSettingsSnapshot =
        ModuleSettingsSnapshot.readFrom(MapPreferences(HashMap(map)))

    fun putMap(bundle: android.os.Bundle, map: Map<String, Any?>) {
        bundle.putSerializable(EXTRA_SETTINGS_MAP, HashMap(map))
    }

    /** 落盘载体：整份快照编成字节，跨进程与重启都还原成同一张表。 */
    fun encodeBytes(map: Map<String, Any?>): ByteArray? =
        try {
            val bundle = android.os.Bundle()
            putMap(bundle, map)
            val parcel = android.os.Parcel.obtain()
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            bytes
        } catch (_: RuntimeException) {
            null
        }

    fun decodeBytes(bytes: ByteArray): Map<String, Any?>? =
        try {
            val parcel = android.os.Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = android.os.Bundle.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            readMap(bundle)
        } catch (_: RuntimeException) {
            null
        }

    @Suppress("UNCHECKED_CAST")
    fun readMap(bundle: android.os.Bundle?): Map<String, Any?>? =
        try {
            bundle?.getSerializable(EXTRA_SETTINGS_MAP) as? Map<String, Any?>
        } catch (_: RuntimeException) {
            null
        }
}

/**
 * 只用于搬移快照的 [SharedPreferences]：读写落在内存映射上。
 *
 * 复用 [ModuleSettingsSnapshot.writeTo] / [ModuleSettingsSnapshot.readFrom]，键名与类型
 * 因此只有一处定义，通道两端不需要各自维护字段清单。
 */
internal class MapPreferences(private val map: MutableMap<String, Any?>) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String, defValue: String?): String? =
        map[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        when (val value = map[key]) {
            is Set<*> -> value.filterIsInstance<String>().toMutableSet()
            else -> defValues
        }

    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MapEditor(map)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class MapEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val removals = ArrayList<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply(key, values?.toMutableSet())

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply(key, value)

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply(key, value)

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply(key, value)

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }

        override fun commit(): Boolean {
            removals.forEach { key -> map.remove(key) }
            return true
        }

        override fun apply() = Unit

        private fun apply(key: String, value: Any?): SharedPreferences.Editor {
            removals -= key
            if (value == null) map.remove(key) else map[key] = value
            return this
        }
    }
}
