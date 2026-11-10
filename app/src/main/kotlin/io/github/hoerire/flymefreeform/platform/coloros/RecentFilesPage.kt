package io.github.hoerire.flymefreeform.platform.coloros

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * 「最近文件和文档」页面：底部按「图片 / 文档」两组切换，列表点击交给系统打开。
 */
internal class RecentFilesPage(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var documentTab: TextView? = null
    private var imageTab: TextView? = null
    private var showingDocuments = true

    fun build(onBack: () -> Unit): View {
        val page = FrameLayout(context).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            isClickable = true
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        page.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(header(onBack), LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(context)
        scroll.addView(list, FrameLayout.LayoutParams(-1, -2))
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(tabs(), LinearLayout.LayoutParams(-1, dp(64)))

        render(true)
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

    /** 底部两个分组按钮，对应系统侧边栏的「图片 / 文档」。 */
    private fun tabs(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        documentTab = tab("文档") { render(true) }
        imageTab = tab("图片") { render(false) }
        bar.addView(documentTab, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        bar.addView(imageTab, LinearLayout.LayoutParams(0, -1, 1f))
        return bar
    }

    private fun tab(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun render(documents: Boolean) {
        showingDocuments = documents
        styleTab(documentTab, documents)
        styleTab(imageTab, !documents)
        list.removeAllViews()
        val items = runCatching {
            if (documents) queryDocuments() else queryImages()
        }.getOrElse { emptyList() }
        if (items.isEmpty()) {
            val hint = if (documents) "没有最近使用的文档" else "没有最近使用的图片"
            list.addView(message(hint), LinearLayout.LayoutParams(-1, dp(96)))
            return
        }
        items.forEach { item -> list.addView(row(item), LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun styleTab(target: TextView?, active: Boolean) {
        target ?: return
        target.setTextColor(if (active) 0xFF1A1A1A.toInt() else 0xFF8A8A8E.toInt())
        target.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(if (active) 0xFFF0F0F2.toInt() else 0x00000000)
        }
        target.background = bg
    }

    private fun row(item: Entry): View {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            setOnClickListener { open(item) }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = item.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1A1A1A.toInt())
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        column.addView(TextView(context).apply {
            text = formatTime(item.modified) + "  ·  " + item.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF8A8A8E.toInt())
        })
        line.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
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

    private fun queryDocuments(): List<Entry> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val selection = MediaStore.Files.FileColumns.MIME_TYPE + " IN (" + MIME_PLACEHOLDERS + ")"
        return read(collection, projection, selection, DOCUMENT_MIME, ::labelOfDocument)
    }

    private fun queryImages(): List<Entry> {
        val collection = MediaStore.Images.Media.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        return read(collection, projection, null, null) { "图片" }
    }

    private fun read(
        collection: android.net.Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        label: (String) -> String,
    ): List<Entry> {
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
                out.add(Entry(name, label(mime), mime, modified, uri))
            }
        }
        return out
    }

    private fun labelOfDocument(mime: String): String = when {
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