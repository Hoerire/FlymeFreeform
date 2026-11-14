package io.github.hoerire.flymefreeform.update

import android.content.Context
import io.github.hoerire.flymefreeform.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 更新检测结果；仅用于模块应用界面，不参与任何 hook 判定。 */
internal sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data object Failed : UpdateState

    data class Available(
        val version: String,
        val pageUrl: String,
        /** 发行说明纯文本；取不到时为空串。 */
        val notes: String,
    ) : UpdateState
}

/**
 * 读取 GitHub 最新发行版，判断是否有更新。
 *
 * 只在模块应用打开时检查，不注册后台任务、不做定时轮询：距上次检查不足
 * [CHECK_INTERVAL_MS] 时直接用磁盘缓存，避免频繁请求触发接口限流。
 * 用户选择「暂不更新」的版本会被记住，直到出现更高版本或手动检查。
 */
internal class UpdateChecker(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, THREAD_NAME) }
    private val running = AtomicBoolean(false)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /**
     * 应用启动时调用：每次都联网确认，缓存只在请求失败时兜底。
     *
     * 六小时缓存只约束「同一次运行内的重复调用」，启动不受它影响——否则刚发布的
     * 版本要等缓存过期才会被看到。
     */
    fun checkOnLaunch() = check(force = false, useCache = false)

    /** 手动检查：忽略缓存与「暂不更新」的记录。 */
    fun check(force: Boolean) = check(force = force, useCache = false)

    private fun check(force: Boolean, useCache: Boolean) {
        if (!running.compareAndSet(false, true)) return
        mutableState.value = UpdateState.Checking
        worker.execute {
            try {
                val cached = readCache()
                val expired =
                    cached == null ||
                        System.currentTimeMillis() - cached.checkedAt >= CHECK_INTERVAL_MS
                if (!useCache || force || expired) {
                    val latest = fetchLatest()
                    if (latest == null) {
                        // 启动时的自动检查失败保持静默，避免离线时把「检查失败」挂在设置页；
                        // 手动检查才把失败明示出来。
                        when {
                            force -> mutableState.value = UpdateState.Failed
                            cached != null -> apply(cached, false)
                            else -> mutableState.value = UpdateState.Idle
                        }
                    } else {
                        writeCache(latest)
                        apply(latest, force)
                    }
                } else {
                    apply(cached, force)
                }
            } finally {
                running.set(false)
            }
        }
    }

    /** 记录用户选择「暂不更新」的版本。 */
    fun dismiss(version: String) {
        preferences.edit().putString(KEY_DISMISSED_VERSION, version).apply()
        mutableState.value = UpdateState.Idle
    }

    private fun apply(latest: Release, force: Boolean) {
        if (VersionOrder.compare(latest.version, BuildConfig.VERSION_NAME) <= 0) {
            mutableState.value = UpdateState.UpToDate
            return
        }
        val dismissed = preferences.getString(KEY_DISMISSED_VERSION, null) == latest.version
        mutableState.value =
            if (dismissed && !force) {
                UpdateState.Idle
            } else {
                UpdateState.Available(latest.version, latest.pageUrl, latest.notes)
            }
    }

    /**
     * 读取最新发行版。
     *
     * 先走公开发布页的跳转（`/releases/latest` → `/releases/tag/<tag>`）：
     * REST 接口对匿名请求按出口 IP 限流（每小时 60 次），移动网络多为运营商 NAT，
     * 这个额度很容易被同一出口的其它请求占满，实测会直接返回 403。
     * 跳转只给得出标签，所以确认「确实有更高版本」后再补一次 Atom 取发行说明。
     */
    private fun fetchLatest(): Release? {
        val redirected = fetchViaRedirect()
        if (redirected != null) {
            val newer = VersionOrder.compare(redirected.version, BuildConfig.VERSION_NAME) > 0
            return if (newer) {
                redirected.copy(notes = fetchNotes(redirected.version))
            } else {
                redirected
            }
        }
        return fetchViaApi()
    }

    /** 跟随 `/releases/latest` 的跳转，从最终地址里取标签。 */
    private fun fetchViaRedirect(): Release? =
        runCatching {
            val connection =
                (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            try {
                if (connection.responseCode != HTTP_OK) return@runCatching null
                val pageUrl = connection.url.toString()
                // 跳转目标是 /releases/tag/<tag>，标签就是路径末段。
                val tag = pageUrl.trimEnd('/').substringAfterLast('/')
                if (tag.isEmpty() || tag == "latest") return@runCatching null
                Release(tag.trim().removePrefix("v").removePrefix("V"), pageUrl)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    /**
     * 从 releases.atom 里取对应版本的发行说明。
     *
     * 发行页本身不返回正文，Atom 的每条 entry 带 `<content>`；只取标签命中的那条，
     * 任何一步失败都退回空串——更新提示照常出现，只是没有正文。
     */
    private fun fetchNotes(version: String): String =
        runCatching {
            val connection =
                (URL(RELEASES_ATOM_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            try {
                if (connection.responseCode != HTTP_OK) return@runCatching ""
                val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                val entry =
                    body
                        .split("<entry>")
                        .drop(1)
                        .firstOrNull { chunk ->
                            val href =
                                LINK_HREF
                                    .find(chunk)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    .orEmpty()
                            href.trimEnd('/').substringAfterLast('/').removePrefix("v") == version
                        } ?: return@runCatching ""
                val html =
                    entry
                        .substringAfter("<content", "")
                        .substringAfter(">", "")
                        .substringBefore("</content>")
                htmlToText(html)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault("")

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        // Atom 的 <content> 里是转义过的 HTML（&lt;h3&gt;…），先还原成标签再解析。
        val unescaped =
            html
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
        val decoded =
            android.text.Html.fromHtml(
                unescaped,
                android.text.Html.FROM_HTML_MODE_LEGACY,
            ).toString()
        return decoded
            .replace("\r\n", "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** REST 接口兜底：可能受匿名限流影响，但能直接拿到 Markdown 正文。 */
    private fun fetchViaApi(): Release? =
        runCatching {
            val connection =
                (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            try {
                if (connection.responseCode != HTTP_OK) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { reader -> reader.readText() }
                val json = JSONObject(body)
                val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
                val pageUrl = json.optString("html_url").trim()
                val notes = json.optString("body").trim()
                if (version.isEmpty() || pageUrl.isEmpty()) {
                    null
                } else {
                    Release(version, pageUrl, notes)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    private fun readCache(): Release? {
        val version = preferences.getString(KEY_LATEST_VERSION, null) ?: return null
        val pageUrl = preferences.getString(KEY_LATEST_PAGE_URL, null) ?: return null
        val notes = preferences.getString(KEY_LATEST_NOTES, "").orEmpty()
        val checkedAt = preferences.getLong(KEY_CHECKED_AT, 0L)
        return Release(version, pageUrl, notes, checkedAt)
    }

    private fun writeCache(release: Release) {
        preferences
            .edit()
            .putString(KEY_LATEST_VERSION, release.version)
            .putString(KEY_LATEST_PAGE_URL, release.pageUrl)
            .putString(KEY_LATEST_NOTES, release.notes)
            .putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            .apply()
    }

    private data class Release(
        val version: String,
        val pageUrl: String,
        val notes: String = "",
        val checkedAt: Long = 0L,
    )

    private companion object {
        const val PREFERENCES_NAME = "update_check"
        const val KEY_LATEST_VERSION = "latest_version"
        const val KEY_LATEST_PAGE_URL = "latest_page_url"
        const val KEY_LATEST_NOTES = "latest_notes"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_DISMISSED_VERSION = "dismissed_version"

        const val RELEASES_URL = "https://github.com/Hoerire/FlymeFreeform/releases/latest"
        const val RELEASES_ATOM_URL = "https://github.com/Hoerire/FlymeFreeform/releases.atom"
        const val API_URL = "https://api.github.com/repos/Hoerire/FlymeFreeform/releases/latest"
        const val USER_AGENT = "FlymeFreeform-UpdateChecker"
        const val HTTP_OK = 200
        const val TIMEOUT_MS = 8000
        const val THREAD_NAME = "flymefreeform-update"

        /** 两次联网检查的最小间隔：六小时内只读缓存。 */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        val LINK_HREF = Regex("<link[^>]+href=\"([^\"]+)\"")
    }
}
