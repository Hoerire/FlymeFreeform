package io.github.hoerire.flymefreeform.platform.coloros

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.provider.MediaStore
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 面板内的「最近文件和文档」页面。
 *
 * 系统的同名入口是侧边栏里的内嵌页面，不是独立应用，没法通过 handle() 打开；
 * 这里在同一个卡片里盖一层自己的列表：直接查 MediaStore 的文档型条目，点击交给系统打开。
 */
internal class RecentFilesPage(private val context: Context) {
    private val density = context.resources.displayMetrics.density

    fun build(onBack: () -> Unit): View {
        val page = FrameLayout(context).apply {
            setBackgroundColor(0xF2FFFFFF.toInt())
            isClickable = true
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(header(onBack), LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val items = runCatching { query() }.getOrElse { emptyList() }
        if (items.isEmpty()) {
            list.addView(message("没有最近使用的文件或文档"), LinearLayout.LayoutParams(-1, dp(96)))
        } else {
            items.forEach { item -> list.addView(row(item), LinearLayout.LayoutParams(-1, -2)) }
        }
        return page
    }

    private fun header(onBack: () -> Unit): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        bar.addView(TextView(context).apply {
            text = "‹"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(0xFF1A1A1A.toInt())
            gravity = Gravity.CENTER
            contentDescription = "返回"
            setOnClickListener { onBack() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(TextView(context).apply {
            text = "最近文件和文档"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1A1A1A.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        return bar
    }

    private fun row(item: Entry): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { open(item) }
        }
        line.addView(TextView(context).apply {
            text = item.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1A1A1A.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        line.addView(TextView(context).apply {
            text = formatTime(item.modified) + "  ·  " + item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF8A8A8E.toInt())
        })
        return line
    }

    private fun open(item: Entry) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, item.mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }

    private fun message(text: String): View = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFF8A8A8E.toInt())
        gravity = Gravity.CENTER
    }

    private data class Entry(
        val name: String,
        val label: String,
        val mime: String,
        val modified: Long,
        val uri: android.net.Uri,
    )

    private fun query(): List<Entry> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = MediaStore.Files.FileColumns.MIME_TYPE + " IN (" + MIME_PLACEHOLDERS + ")"
        val args = DOCUMENT_MIME
        val out = ArrayList<Entry>()
        context.contentResolver.query(
            collection, projection, selection, args,
            MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < LIMIT) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: continue
                val modified = cursor.getLong(3) * 1000L
                val uri = android.content.ContentUris.withAppendedId(collection, id)
                out.add(Entry(name, labelOf(mime), mime, modified, uri))
            }
        }
        return out
    }

    private fun labelOf(mime: String): String = when {
        mime.contains("pdf") -> "PDF"
        mime.contains("word") || mime.contains("msword") -> "文档"
        mime.contains("sheet") || mime.contains("excel") -> "表格"
        mime.contains("presentation") || mime.contains("powerpoint") -> "演示"
        mime.startsWith("text/") -> "文本"
        else -> "文件"
    }

    private fun formatTime(millis: Long): String {
        if (millis <= 0L) return ""
        val now = System.currentTimeMillis()
        val pattern = if (now - millis < 24L * 60 * 60 * 1000) "今天 HH:mm" else "MM-dd HH:mm"
        return runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis)) }.getOrDefault("")
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val LIMIT = 30
        val DOCUMENT_MIME = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
        )
        val MIME_PLACEHOLDERS: String = DOCUMENT_MIME.joinToString(",") { "?" }
    }
}