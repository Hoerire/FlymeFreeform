package io.github.hoerire.flymefreeform.platform.coloros

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import io.github.hoerire.flymefreeform.window.IconContentMetrics
import io.github.hoerire.flymefreeform.apps.AppSelectionPolicy
import io.github.hoerire.flymefreeform.apps.SelectableEntry
import io.github.hoerire.flymefreeform.config.ModulePreferences
import io.github.hoerire.flymefreeform.config.ModuleSettingsSnapshot
import io.github.hoerire.flymefreeform.config.PinnedTarget
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * 扇形里的固定项顺序 = 用户在设置页拖出来的顺序，不做任何重排。
 *
 * 工具与快捷方式不占用应用名额，因此这里做过一次「工具垫底」的稳定排序；
 * 代价是工具的顺序被永久钉死——设置页能拖，扇形不跟随（表现：小布识屏、截屏
 * 一直压在最后）。顺序应当只有一处来源，就是 pinnedTargets 本身。
 */
private fun radialPinOrder(targets: List<PinnedTarget>): List<PinnedTarget> = targets

internal data class RadialEntry(
    val target: PinnedTarget,
    val label: String,
    val icon: Bitmap,
    val badge: Bitmap? = null,
    val badgeLeftOffsetFraction: Float = 0f,
)

internal data class AppCatalogSnapshot(
    val radialApps: List<RadialEntry> = emptyList(),
    val panelApps: List<RadialEntry> = emptyList(),
    val settings: ModuleSettingsSnapshot = ModuleSettingsSnapshot(),
) {
    /**
     * 目录是否需要重算。
     *
     * 扇形条目数由固定项决定，不再有上限设置，因此只需比对固定项本身。
     */
    fun matches(settings: ModuleSettingsSnapshot): Boolean =
        this.settings.pinsSaved == settings.pinsSaved &&
            this.settings.pinnedTargets == settings.pinnedTargets
}

/** 目录查询只在后台执行；发布后的 Bitmap 与列表供手势热路径只读。 */
internal class ColorOsAppCatalog(
    private val context: Context,
    private val executor: Executor,
    logger: (Int, String, Throwable?) -> Unit,
    private val publish: (AppCatalogSnapshot) -> Unit,
) {
    private val iconRenderer = ColorOsRadialIconRenderer(context.resources, logger)

    private val contentRevision = AtomicLong()

    /**
     * 最终图标（塑形 + 裁圆后的位图）按目标缓存。
     *
     * 最近任务变化会触发目录重建，而图标本身不受影响；不缓存的话每次重建都要
     * 重新 getIcon/toBitmap/塑形/裁圆，正是呼出扇形时卡顿的来源。
     */
    private val iconCache = java.util.WeakHashMap<PinnedTarget, Bitmap>()
    private var cachedContent: CatalogContent? = null // 仅目录工作线程访问。

    @Volatile private var sidebarEntries: List<SelectableEntry> = emptyList()

    /** 侧边栏目录版本：内容一变就 +1，用来让目录缓存失效。 */
    @Volatile private var sidebarRevision = 0L


    fun updateSidebarEntries(entries: List<SelectableEntry>, settings: ModuleSettingsSnapshot) {
        // 侧边栏目录暂时不可用时不要用空表覆盖：固定位的工具/快捷项解析不到就会被最近应用顶替，
        // 用户会看到「快捷项变成空格」。保留上一次成功的目录，等恢复后自然替换。
        if (entries.isEmpty()) {
            io.github.hoerire.flymefreeform.log.ModuleLog.debug("FlymeFreeform") {
                "SIDEBAR_ENTRIES_EMPTY_KEPT_CACHED_" + sidebarEntries.size
            }
            return
        }
        val next = entries.toList()
        // 侧边栏会频繁推送同一份目录，内容没变就不重建。
        if (next == sidebarEntries) return
        sidebarEntries = next
        // 目录变了必须让缓存失效，否则复用旧快照，扇形里的快捷项会「消失」。
        sidebarRevision++
        refresh(settings)
    }

    fun refresh(
        settings: ModuleSettingsSnapshot,
        reloadApps: Boolean = true,
        /** 按最新「最近任务」重排：只在打开「更多」面板时用，呼出扇形不付这个代价。 */
        forceRecentOrder: Boolean = false,
    ) {
        if (reloadApps) contentRevision.incrementAndGet()
        executor.execute {
            val revision = contentRevision.get()
            val cached = cachedContent
            // 最近任务会随时变化；扇形要在每次呼出时反映最新结果，所以它必须参与缓存判定。
            val recentComponents = recentComponents()
            val recentKey = recentComponents.map { it.flattenToString() }
            val content =
                if (cached != null && cached.revision == revision &&
                    cached.sidebarRevision == sidebarRevision &&
                    cached.settings.pinsSaved == settings.pinsSaved &&
                    cached.settings.pinnedTargets == settings.pinnedTargets &&
                    // 只有显式要求（打开「更多」面板）时才按最近任务重建。
                    (!forceRecentOrder || cached.recentKey == recentKey)
                ) {
                    cached
                } else {
                    loadContent(settings, revision, recentKey, recentComponents) ?: return@execute
                }
            cachedContent = content
            val shapedRadial = content.radialApps.map { entry ->
                val cached = iconCache[entry.target]
                if (cached != null) {
                    entry.copy(icon = cached)
                } else {
                    val drawable = content.radialSources[entry.target]
                    val base = if (drawable == null) entry.icon else {
                        try {
                            iconRenderer.shapedIcon(drawable).toBitmap()
                        } catch (_: RuntimeException) {
                            entry.icon
                        }
                    }
                    // 扇形里的图标统一裁成圆：绘制期裁切对满底位图不生效，因此在生成阶段一次成圆。
                    val icon = IconContentMetrics.circular(base)
                    iconCache[entry.target] = icon
                    entry.copy(icon = icon)
                }
            }
            shapedRadial.forEach { entry ->
                entry.icon.prepareToDraw()
                entry.badge?.prepareToDraw()
            }
            if (revision == contentRevision.get()) publish(AppCatalogSnapshot(shapedRadial, content.panelApps, settings))
        }
    }

    private fun loadContent(
        settings: ModuleSettingsSnapshot,
        revision: Long,
        recentKey: List<String>,
        recentComponents: List<ComponentName>,
    ): CatalogContent? {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val user = Process.myUserHandle()
        val activities = launcherApps.getActivityList(null, user)
        val entries =
            activities
                .asSequence()
                .filterNot { info -> info.componentName.packageName == MODULE_PACKAGE }
                .mapNotNull(::toEntry)
                .distinctBy(RadialEntry::target)
                .toList()
        val sidebar = sidebarEntries
        val nativeEntries = sidebar.map {
            RadialEntry(it.target, it.label, it.icon, it.badge, it.badge?.leftOffsetFraction() ?: 0f)
        }
        val byComponent = (entries + nativeEntries).associateBy(RadialEntry::target)
        val recents =
            recentComponents
                .map { it.toTarget() }
                .mapNotNull(byComponent::get)
                .distinctBy(RadialEntry::target)
        val collator = Collator.getInstance(Locale.getDefault())
        val alphabetical =
            entries.sortedWith { first, second ->
                collator.compare(first.label, second.label)
            }
        val radial =
            AppSelectionPolicy.radialItems(
                pinsSaved = settings.pinsSaved,
                availablePins = radialPinOrder(settings.pinnedTargets).mapNotNull(byComponent::get),
                // 扇形不再用「最近应用」填充：那份数据随时变，每次重排都要重绘，
                // 是呼出卡顿的主要来源。最近只保留在「更多」面板里。
                recent = emptyList<RadialEntry>(),
                all = alphabetical,
                identity = RadialEntry::target,
                limit = ModulePreferences.MAX_PINNED_APPS,
            )
        val excluded = radial.mapTo(HashSet(), RadialEntry::target)
        val panel =
            AppSelectionPolicy.panelItems(
                recent = recents,
                all = alphabetical,
                excluded = excluded,
                identity = RadialEntry::target,
            )
        val activityByComponent = activities.associateBy { it.componentName.toTarget() }
        // 分身与主应用共用图标资源；包内只有一个入口时复用主用户的自适应图标，与普通应用同样原生塑形。
        val cloneActivities = sidebar
            .filter { (it.target as? PinnedTarget.Sidebar)?.kind == PinnedTarget.Kind.CloneApp }
            .mapNotNull { entry ->
                activities.filter { it.componentName.packageName == entry.packageName }
                    .singleOrNull()?.let { entry.target to it }
            }.toMap()
        val sources = radial.mapNotNull { entry ->
            val info = activityByComponent[entry.target] ?: cloneActivities[entry.target]
            if (info == null) return@mapNotNull entry.target to entry.icon.toDrawable(context.resources)
            try {
                entry.target to info.getIcon(context.resources.displayMetrics.densityDpi)
            } catch (_: RuntimeException) {
                null
            }
        }.toMap()
        panel.forEach { it.icon.prepareToDraw() }
        return CatalogContent(revision, settings, radial, panel, sources, recentKey, sidebarRevision)
    }

    private data class CatalogContent(
        val revision: Long,
        val settings: ModuleSettingsSnapshot,
        val radialApps: List<RadialEntry>,
        val panelApps: List<RadialEntry>,
        val radialSources: Map<PinnedTarget, Drawable>,
        /** 生成这份目录时用到的最近任务序列；不一致就说明「最近应用」需要重排。 */
        val recentKey: List<String> = emptyList(),
        /** 生成这份目录时的侧边栏目录版本；不一致必须重建，否则快捷项会丢。 */
        val sidebarRevision: Long = 0L,
    )

    /** 在目录工作线程测量可见范围；左侧只平移角标，避免翻转其中的数字。 */
    private fun Bitmap.leftOffsetFraction(): Float {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var right = 0
        pixels.forEachIndexed { index, pixel ->
            if (pixel ushr 24 != 0) {
                val x = index % width
                left = minOf(left, x)
                right = maxOf(right, x + 1)
            }
        }
        return if (left < right) (width - left - right).toFloat() / width else 0f
    }

    private fun ComponentName.toTarget() = PinnedTarget.App(packageName, className)

    private fun recentComponents(): List<ComponentName> {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            activityManager.getRecentTasks(RECENT_LIMIT, ActivityManager.RECENT_WITH_EXCLUDED)
                .mapNotNull { task -> task.origActivity ?: task.baseIntent.component }
                .filterNot { component -> component.packageName == context.packageName }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    private fun toEntry(info: LauncherActivityInfo): RadialEntry? =
        try {
            RadialEntry(
                target = info.componentName.toTarget(),
                label = info.label?.toString()?.trim().orEmpty().ifEmpty { info.componentName.packageName },
                icon = info.getIcon(context.resources.displayMetrics.densityDpi).toBitmap(),
            )
        } catch (_: RuntimeException) {
            null
        }

    private fun Drawable.toBitmap(): Bitmap {
        val systemIconSize =
            context.getSystemService(ActivityManager::class.java)?.launcherLargeIconSize ?: 0
        val targetSize =
            (systemIconSize.takeIf { it > 0 }
                ?: maxOf(intrinsicWidth, intrinsicHeight, 1)).coerceAtLeast(1)
        if (this is BitmapDrawable && bitmap != null) {
            if (bitmap.width == targetSize && bitmap.height == targetSize) return bitmap
            return Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        }
        return createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }
    }

    private companion object {
        const val RECENT_LIMIT = 48
        const val MODULE_PACKAGE = "io.github.hoerire.flymefreeform"
    }
}