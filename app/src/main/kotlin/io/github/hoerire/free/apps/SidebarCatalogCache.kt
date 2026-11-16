package io.github.hoerire.free.apps

import android.os.Bundle
import android.os.Parcel
import io.github.hoerire.free.config.PinnedTarget
import io.github.hoerire.free.config.PinnedTargetCodec
import java.io.File

/**
 * 侧边栏目录的本地缓存。
 *
 * 目录要从侧边栏进程取，而那个进程常常在开机后很晚才起来，甚至在整段使用期间都不在运行；
 * 每次读不到就把界面清空，用户会以为功能坏了。这里把上一次成功读到的条目连同图标
 * 落到缓存目录，下次取不到时先用它显示，刷新成功后覆盖。
 *
 * 条目里带 Bitmap，直接用系统 Parcel 序列化，不再另造一套图标文件格式。
 */
internal class SidebarCatalogCache(context: android.content.Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val temp = File(context.filesDir, FILE_NAME + ".tmp")

    fun load(): List<SelectableEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val bytes = file.readBytes()
            val parcel = Parcel.obtain()
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            val bundle = Bundle.CREATOR.createFromParcel(parcel)
            parcel.recycle()
            bundle.classLoader = javaClass.classLoader
            val count = bundle.getInt(KEY_COUNT, 0)
            (0 until count).mapNotNull { index ->
                val target = bundle.getString(KEY_TARGET + index) ?: return@mapNotNull null
                val label = bundle.getString(KEY_LABEL + index) ?: return@mapNotNull null
                val packageName = bundle.getString(KEY_PACKAGE + index) ?: return@mapNotNull null
                val icon = bundle.getParcelable(KEY_ICON + index, android.graphics.Bitmap::class.java)
                    ?: return@mapNotNull null
                val badge = bundle.getParcelable(KEY_BADGE + index, android.graphics.Bitmap::class.java)
                SelectableEntry(
                    target = PinnedTargetCodec.decode(target) ?: return@mapNotNull null,
                    label = label,
                    packageName = packageName,
                    icon = icon,
                    badge = badge,
                )
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    fun save(entries: List<SelectableEntry>) {
        if (entries.isEmpty()) return
        try {
            val bundle = Bundle()
            bundle.putInt(KEY_COUNT, entries.size)
            entries.forEachIndexed { index, entry ->
                bundle.putString(KEY_TARGET + index, PinnedTargetCodec.encode(entry.target))
                bundle.putString(KEY_LABEL + index, entry.label)
                bundle.putString(KEY_PACKAGE + index, entry.packageName)
                bundle.putParcelable(KEY_ICON + index, entry.icon)
                bundle.putParcelable(KEY_BADGE + index, entry.badge)
            }
            val parcel = Parcel.obtain()
            bundle.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            temp.writeBytes(bytes)
            temp.renameTo(file)
        } catch (_: RuntimeException) {
            // 缓存只是兜底：写失败不影响正常读取。
        }
    }

    private companion object {
        const val FILE_NAME = "sidebar_catalog.bin"
        const val KEY_COUNT = "count"
        const val KEY_TARGET = "target"
        const val KEY_LABEL = "label"
        const val KEY_PACKAGE = "package"
        const val KEY_ICON = "icon"
        const val KEY_BADGE = "badge"
    }
}
