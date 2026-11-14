package io.github.hoerire.flymefreeform.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.createBitmap
import io.github.hoerire.flymefreeform.config.PinnedTarget
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
    private var includeSystemApps = false
    val apps: StateFlow<List<SelectableEntry>> = mutableApps.asStateFlow()

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
     * 重新加载候选应用。
     *
     * [includeSystemApps] 为真时并入系统应用：桌面图标列表（LauncherApps）只包含有启动图标的应用，
     * 而「排除列表」「禁用浮窗」都按包名匹配，系统组件往往没有图标也就选不到。
     * 这里额外扫描带系统标记的包，按包名去重，保留先出现的（带图标的那条）。
     */
    fun refresh(includeSystemApps: Boolean = this.includeSystemApps) {
        this.includeSystemApps = includeSystemApps
        worker.execute {
            val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return@execute
            val collator = Collator.getInstance(Locale.getDefault())
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
            val systemEntries =
                if (!includeSystemApps) {
                    emptyList()
                } else {
                    systemAppEntries()
                }
            mutableApps.value =
                (launcherEntries + systemEntries)
                    .distinctBy(SelectableEntry::packageName)
                    .sortedWith { first, second -> collator.compare(first.label, second.label) }
        }
    }

    /**
     * 系统应用条目。
     *
     * 这里**不要求有启动入口**：跨端互联、消息同步这类系统组件本身没有桌面图标，
     * 但会以调用方身份拉起界面；两个列表都按包名匹配，必须能选到它们。
     * 没有启动入口的条目只用于「按包名豁免」，不参与扇形可启动应用的挑选。
     */
    private fun systemAppEntries(): List<SelectableEntry> {
        val manager = context.packageManager
        return manager
            .getInstalledApplications(0)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter {
                (it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            }
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
        const val THREAD_NAME = "FlymeFreeform-AppCatalog"
        const val ICON_CACHE_SIZE_DP = 48f
    }
}
