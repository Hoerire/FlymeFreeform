package io.github.hoerire.free.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.createBitmap
import io.github.hoerire.free.config.PinnedTarget
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class LauncherAppRepository(private val context: Context) {
    private val worker =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(1),
            { task -> Thread(task, THREAD_NAME) },
            ThreadPoolExecutor.DiscardOldestPolicy(),
        )
    private val mutableApps = MutableStateFlow<List<SelectableEntry>>(emptyList())
    private val mutableLoading = MutableStateFlow(false)

    /** 上一轮结果：重新打开开关时先按缓存立即生效，再在后台补齐。 */
    private var cachedNonLauncherEntries: List<SelectableEntry>? = null
    private var cachedLauncherEntries: List<SelectableEntry> = emptyList()
    val apps: StateFlow<List<SelectableEntry>> = mutableApps.asStateFlow()
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }

    init {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        // Repository 由 Application 持有到进程结束，因此接收器也只注册一次。
        context.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    /**
     * 重新加载候选应用：桌面图标列表 + 其余全部已安装应用。
     *
     * 桌面图标列表（LauncherApps）只包含有启动图标的应用，而「排除列表」「禁用浮窗」都按
     * 包名匹配——系统组件、预装组件往往没有图标，只扫桌面列表就永远选不到它们
     * （例：「无线耳机」com.oplus.melody）。所以这里把剩余的已安装应用一并列出，
     * 按包名去重，保留先出现的（带启动入口的那条）。
     */
    fun refresh() {
        // 上一轮结果先顶上：首次扫描要重建数百个图标，期间不能只让界面停在空列表。
        cachedNonLauncherEntries?.let { emit(cachedLauncherEntries, it) }
        mutableLoading.value = true
        worker.execute {
            val launcherApps =
                context.getSystemService(LauncherApps::class.java)
                    ?: run {
                        mutableLoading.value = false
                        return@execute
                    }
            val launcherEntries =
                launcherApps
                    .getActivityList(null, Process.myUserHandle())
                    .asSequence()
                    .filterNot { it.componentName.packageName == context.packageName }
                    .mapNotNull { info ->
                        try {
                            SelectableEntry(
                                target = PinnedTarget.App(info.componentName.packageName, info.componentName.className),
                                packageName = info.componentName.packageName,
                                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
                            )
                        } catch (_: RuntimeException) {
                            null
                        }
                    }
                    .toList()
            cachedLauncherEntries = launcherEntries
            // 桌面图标列表之外的应用（系统组件、预装组件、没有图标的包）一并列出。
            val otherEntries =
                nonLauncherEntries(launcherEntries.mapTo(HashSet()) { it.packageName })
                    .also { cachedNonLauncherEntries = it }
            emit(launcherEntries, otherEntries)
            mutableLoading.value = false
        }
    }

    /** 以固定排序规则合成最终列表。 */
    private fun emit(
        launcherEntries: List<SelectableEntry>,
        otherEntries: List<SelectableEntry>,
    ) {
        val collator = Collator.getInstance(Locale.getDefault())
        mutableApps.value =
            (launcherEntries + otherEntries)
                .distinctBy(SelectableEntry::packageName)
                .sortedWith { first, second -> collator.compare(first.label, second.label) }
    }

    /**
     * 非桌面应用条目：桌面图标列表之外的所有已安装应用。
     *
     * 这里**不要求有启动入口**：跨端互联、消息同步这类系统组件本身没有桌面图标，
     * 但会以调用方身份拉起界面；排除列表按包名匹配，必须能选到它们。
     * 没有启动入口的条目只用于「按包名豁免」，不参与扇形可启动应用的挑选。
     *
     * 也**不按系统标记过滤**：ColorOS 有一批预装组件装在 /data/app 里，既没有
     * `FLAG_SYSTEM`、也没有桌面图标（例：「无线耳机」`com.oplus.melody`），
     * 只按系统标记扫会让它们在排除列表里彻底选不到。桌面图标列表里已有的包跳过，
     * 避免重复加载图标。
     */
    private fun nonLauncherEntries(known: Set<String>): List<SelectableEntry> {
        val manager = context.packageManager
        return manager
            .getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName && it.packageName !in known }
            .mapNotNull { info ->
                runCatching {
                    val component = manager.getLaunchIntentForPackage(info.packageName)?.component
                    SelectableEntry(
                        target = PinnedTarget.App(info.packageName, component?.className.orEmpty()),
                        packageName = info.packageName,
                        label = manager.getApplicationLabel(info).toString().trim()
                            .ifEmpty { info.packageName },
                        icon = manager.getApplicationIcon(info).toBitmap(),
                    )
                }.getOrNull()
            }
            .toList()
    }

    private fun Drawable.toBitmap(): Bitmap {
        val targetSize = (ICON_CACHE_SIZE_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        val result = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return result
    }

    private companion object {
        const val THREAD_NAME = "Free-AppCatalog"
        const val ICON_CACHE_SIZE_DP = 48f
    }
}
