package io.github.hoerire.flymefreeform.platform.coloros

import java.io.File

/**
 * 「更多」面板的收藏分组。
 *
 * 面板数据来自系统侧边栏，条目本身都在 adapter 的 mData 里（[Title, 工具…, Title, 应用…]）。
 * 这里按系统侧边栏的用户设置（coloros_ep_entry_user.xml）的顺序重排成收藏段；
 * 面板里没有的快捷入口用 ToolProxy 现造。
 *
 * 收藏之后接一段「最近打开的应用」，数据直接取系统 UserPanelView.getRecentData()，
 * 不再自己统计点击记录。
 *
 * 优先走 [transform]（hook getMData，数据首次读取即为最终顺序，面板不会闪）；
 * [apply] 只作为兜底。
 */
/**
 * 把系统「全部应用」面板的列表改成「收藏 + 最近」两组。
 *
 * 工作方式：
 * 1. [prepareAsync] 在后台线程构造行数据（读侧边栏顺序 XML + UsageStats 最近记录），结果缓存在 [prepared]；
 * 2. [apply] 在主线程把结果**原地**写进 adapter 的 mData（不换对象、默认不 notify），避免重建与掉帧；
 * 3. adapter 的数据观察者会在系统重灌原厂数据时再次调用 [apply] 覆盖回来。
 *
 * 数据契约：行元素是从侧边栏 dex 反射构造的原生 bean，顺序即最终展示顺序；
 * mData 必须是可变 List，否则退化为 field 赋值 + notifyDataSetChanged。
 */
internal object FavoriteGroupInjector {
    private const val TAG = "FFFAV"

    /** 排障日志由设置里的「详细日志」实时控制；关闭时消息构造一并跳过。 */
    private fun d(throwable: Throwable? = null, message: () -> String) {
        io.github.hoerire.flymefreeform.log.ModuleLog.debug(TAG, throwable, message)
    }
    /**
     * 「最近」组的统计窗口。
     *
     * 只取 24 小时：窗口越大，`queryEvents` 的跨进程事件量与反序列化开销越高
     * （实测 7 天窗口单次查询约 169ms，24 小时降到几十毫秒），而最近列表本身只需要顶部若干条。
     * 该窗口同时用于事件流与兜底的 `queryUsageStats` 统计，保证两条路径口径一致。
     */
    private const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000
    private const val PREPARE_TTL_MS = 15_000L
    private val XML = File("/data/user/0/com.coloros.smartsidebar/files/edge_panel/coloros_ep_entry_user.xml")

    private val cache = java.util.WeakHashMap<List<*>, List<*>>()

    /** 每个 adapter 第一次注入前的原始列表；后续注入都以它为基准。 */
    private val originals = java.util.WeakHashMap<Any, List<*>>()



    /**
     * hook 到 SearchHelper.getCurrentDataList()：面板拿到的第一份数据就是最终顺序，
     * 不改字段、不调 notifyDataSetChanged，所以不会有「先渲染再重排」的闪烁。
     */
    /** 最近一次成功重排的结果：重排失败时沿用该结果，不回退为系统原始列表。 */
    @Volatile
    private var lastGood: List<Any?>? = null

    /** 后台预计算结果：主线程只做“把这份列表原地写进去”，不查 XML/UsageStats。 */
    @Volatile
    private var prepared: List<Any?>? = null
    @Volatile
    private var preparing = false
    @Volatile
    private var preparedAt = 0L
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FlymeFreeform-PanelPrepare").apply { isDaemon = true }
    }

    /** 在后台把「收藏 + 最近」算好；主线程后续只做内存替换。 */
    fun prepareAsync(
        context: android.content.Context? = null,
        loader: ClassLoader? = null,
        force: Boolean = false,
    ) {
        if (preparing) {
            d { "prepareAsync: busy, skip (force=" + force + ")" }
            return
        }
        if (!force && prepared != null && System.currentTimeMillis() - preparedAt < PREPARE_TTL_MS) {
            d { "prepareAsync: cache hit age=" + (System.currentTimeMillis() - preparedAt) + "ms" }
            return
        }
        d { "prepareAsync: start force=" + force + " prepared=" + (prepared?.size ?: -1) }
        preparing = true
        val requestedAt = System.currentTimeMillis()
        executor.execute {
            try {
                val ctx = context ?: appContext()
                val cl = loader
                    ?: lastGood?.firstOrNull { it != null }?.javaClass?.classLoader
                    ?: return@execute
                val rows = buildRows(cl, ctx)
                if (rows != null) {
                    prepared = rows
                    preparedAt = System.currentTimeMillis()
                    d { "prepareAsync done size=" + rows.size +
                            " cost=" + (System.currentTimeMillis() - requestedAt) + "ms" }
                    // 面板已经打开的话，算完就地回写，用户不用关掉再开才能看到新顺序。
                    val adapter = appliedAdapter?.get()
                    if (adapter != null) mainHandler.post { apply(adapter, ctx, notify = true) }
                } else {
                    d { "prepareAsync: buildRows 返回 null" }
                }
            } catch (t: Throwable) {
                d(t) { "prepareAsync fail" }
            } finally {
                preparing = false
            }
        }
    }

    fun transform(data: List<*>?): List<Any?>? {
        if (data == null || data.size < 2) return lastGood
        val titles = data.count { it?.javaClass?.simpleName == "TitleLabelData" }
        d { "transform size=" + data.size + " titles=" + titles }
        // 搜索结果没有标题，原样放行。
        if (titles == 0) return null
        cache[data]?.let { return it }
        val result = reorder(data, data, appContext())
        if (result == null) {
            d { "transform: reorder 失败，沿用上次结果 " + (lastGood?.size ?: -1) }
            return lastGood
        }
        cache[data] = result
        lastGood = result
        d { "transform -> " + result.size + " 条" }
        return result
    }

    /** 侧边栏进程自己的 Application，用作 UsageStatsManager 的 context。 */
    private fun appContext(): android.content.Context? = try {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
            as? android.content.Context
    } catch (_: Throwable) {
        null
    }

    private fun notifyChanged(adapter: Any) {
        try {
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            d { "notify applied" }
        } catch (t: Throwable) {
            d(t) { "notify fail" }
        }
    }

    /** 兜底：直接改写 adapter 的 mData 并通知刷新（会有一次重建）。 */
    /** 最近一次注入过的 adapter，用于“算完就地回写”。 */
    private var appliedAdapter: java.lang.ref.WeakReference<Any>? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun apply(
        adapter: Any,
        context: android.content.Context? = null,
        notify: Boolean = false,
    ) {
        val startedAt = System.currentTimeMillis()
        appliedAdapter = java.lang.ref.WeakReference(adapter)
        try {
            val field = adapter.javaClass.getDeclaredField("mData").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val current = field.get(adapter) as? List<*> ?: return
            val original = originals[adapter] ?: current.also { originals[adapter] = it }
            // 用后台算好的结果；没有就按当前列表重排。
            val cleaned = prepared
                ?: reorder(original, adapter, context)
                ?: lastGood.also {
                    d { "apply: reorder 失败，回落上次结果 " + (it?.size ?: -1) }
                } ?: return
            if (cleaned == current) {
                d { "apply: 已是最新，跳过（size=" + current.size + "）" }
                return
            }
            // 关键：不换列表对象、不调 notifyDataSetChanged，直接原地改这份 ArrayList。
            // adapter 还没 attach/measure，第一次布局读到的就是最终数据 → 没有重建、没有掉帧。
            @Suppress("UNCHECKED_CAST")
            val mutable = current as? MutableList<Any?>
            if (mutable != null) {
                mutable.clear()
                mutable.addAll(cleaned)
                // 后台算完的回写必须 notify 一次，否则面板整段生命周期里都不会重绘。
                if (notify) notifyChanged(adapter)
            } else {
                field.set(adapter, cleaned)
                notifyChanged(adapter)
            }
            lastGood = cleaned
            // 只原地改内容，不 notify：下一次系统自己 notify 或下次打开面板时就会用上模块数据。
            // （首次打开仍是原厂数据，这是已知且可接受的代价——notify 会带来重建/掉帧等一堆问题。）
            val count = runCatching { adapter.javaClass.getMethod("getItemCount").invoke(adapter) }.getOrNull()
            d { "in-place total=" + cleaned.size + " itemCount=" + count +
                    " sameList=" + (mutable != null) +
                    " source=" + (if (prepared != null) "prepared" else "reorder/lastGood") +
                    " notify=" + notify + " cost=" + (System.currentTimeMillis() - startedAt) + "ms" }
        } catch (t: Throwable) {
            d(t) { "apply fail" }
        }
    }

    /**
     * 廉价校验：只有列表真的被系统换掉（长度或首元素变了）才重新重排。
     * 后续几个时间点用这个，避免每次都去查 UsageStats、重排 79 条，导致面板发卡。
     */
    fun applyIfStale(adapter: Any, context: android.content.Context? = null) {
        try {
            val field = adapter.javaClass.getDeclaredField("mData").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val current = field.get(adapter) as? List<*> ?: return
            val good = lastGood
            if (good != null && current.size == good.size && current.firstOrNull() === good.firstOrNull()) {
                d { "stale check: 未变化（size=" + current.size + "），跳过重排" }
                return
            }
            apply(adapter, context)
        } catch (t: Throwable) {
            d(t) { "stale check fail" }
        }
    }

    /**
     * 纯数据构造：只看用户设置 XML + 最近使用，不依赖当前列表内容。
     * 在后台线程调用，主线程只负责把结果原地写进 adapter 的列表。
     */
    private fun buildRows(loader: ClassLoader, context: android.content.Context?): List<Any?>? {
        val order = readOrder() ?: return null
        val picked = order.mapNotNull { buildAliasItem(loader, context, it) }.distinctBy { aliasOf(it) }
        // 进程刚起时 ToolProxy/ToolEntryHelper 还没初始化，会造不出工具类条目（只剩应用类），
        // 该结果不完整，采用会导致收藏条目缺失且无标签。
        if (picked.size < order.size) {
            d { "buildRows: 收藏不完整 picked=" + picked.size + " order=" + order.size }
            return null
        }
        val pickedAliases = picked.mapNotNull { aliasOf(it) }.toHashSet()
        // 收藏 + 扇形里固定项：两组同源，都不再出现在「最近」。
        val recent = recentItems(loader, context, pickedAliases + pinnedAliases())
        val head = makeTitle(loader, null, "收藏")
        val recentTitle = if (recent.isNotEmpty()) makeTitle(loader, null, "最近") else null
        val out = ArrayList<Any?>(picked.size + recent.size + 2)
        head?.let { out.add(it) }
        out.addAll(picked)
        if (recentTitle != null) {
            out.add(recentTitle)
            out.addAll(recent)
        }
        d { "buildRows: 收藏=" + picked.size + " 最近=" + recent.size + " 合计=" + out.size }
        return out
    }

    /** 顺序来自用户设置；返回新列表，顺序无需变化时返回 null。 */
    private fun reorder(data: List<*>, source: Any, context: android.content.Context?): List<Any?>? {
        // 优先复用系统主面板 UserListAdapter.mPanelData 的完整数据
        // （其结构为 [标题 工具, 工具…, 标题 最近, 最近应用…]），仅重命名两个分组标题。
        systemPanelRows()?.let { rows ->
            val cleaned = normalize(rows)
            val titles = cleaned.filter { it?.javaClass?.simpleName == "TitleLabelData" }
            d { "rows=" + cleaned.size + " titles=" + titles.size }
            // mPanelData 里没有标题行（实测 titles=0，只有工具区条目和占位项），
            // 只有它确实带分组标题时才整份采用。
            if (cleaned.size > 1 && titles.size >= 2) {
                titles.getOrNull(0)?.let { setTitle(it, "收藏") }
                titles.getOrNull(1)?.let { setTitle(it, "最近") }
                return cleaned
            }
        }
        return reorderByUserXml(data, source, context)
    }

    /** 去掉没有后续条目的空标题；其余原样保留。 */
    private fun normalize(rows: List<Any?>): List<Any?> {
        val cleaned = ArrayList<Any?>(rows.size)
        for (i in rows.indices) {
            val item = rows[i]
            if (item?.javaClass?.simpleName == "TitleLabelData") {
                val next = rows.getOrNull(i + 1)
                if (next == null || next.javaClass.simpleName != "AppLabelData") continue
            }
            cleaned.add(item)
        }
        return cleaned
    }

    /** 系统主面板的数据：扫描所有窗口，找 UserListAdapter 的 mPanelData。 */
    private fun systemPanelRows(): List<Any?>? {
        try {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmg.getMethod("getInstance").invoke(null)
            val field = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            val views = field.get(instance) as? List<*> ?: return null
            for (view in views) {
                if (view !is android.view.View) continue
                val adapter = findAdapter(view) ?: continue
                if (!adapter.javaClass.name.contains("UserListAdapter")) continue
                val rows = panelDataOf(adapter)
                if (rows != null) {
                    d { "mPanelData=" + rows.size }
                    return rows
                }
            }
            d { "UserListAdapter not found" }
            return null
        } catch (t: Throwable) {
            d(t) { "panel rows fail" }
            return null
        }
    }

    private fun findAdapter(view: android.view.View): Any? {
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findAdapter(view.getChildAt(i))?.let { return it }
            }
        }
        return runCatching { view.javaClass.getMethod("getAdapter").invoke(view) }.getOrNull()
    }

    private fun panelDataOf(adapter: Any): List<Any?>? {
        var cls: Class<*>? = adapter.javaClass
        while (cls != null && cls != Any::class.java) {
            val current = cls
            val field = runCatching { current.getDeclaredField("mPanelData") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                return field.get(adapter) as? List<Any?>
            }
            cls = current.superclass
        }
        return null
    }

    private fun reorderByUserXml(data: List<*>, source: Any, context: android.content.Context?): List<Any?>? {
        try {
            val order = readOrder() ?: return null
            @Suppress("UNCHECKED_CAST")
            val items = data as List<Any?>
            val byAlias = HashMap<String, Any?>()
            for (item in items) {
                val alias = aliasOf(item) ?: continue
                byAlias.putIfAbsent(alias, item)
            }
            // 列表对象本身是 java.util.ArrayList（boot loader），类加载器必须取条目自己的，
            // 否则 Class.forName(TitleLabelData/ToolProxy) 会 ClassNotFoundException。
            val loader = items.firstOrNull { it != null }?.javaClass?.classLoader
                ?: source.javaClass.classLoader
            val picked = order.mapNotNull { alias ->
                byAlias[alias] ?: buildAliasItem(loader, context, alias)
            }.distinctBy { aliasOf(it) }
            if (picked.isEmpty()) {
                d { "no match order=" + order.size + " data=" + items.size }
                return null
            }
            val groupTitles = items.filter { it?.javaClass?.simpleName == "TitleLabelData" }
            val head = groupTitles.getOrNull(0)
            val second = groupTitles.getOrNull(1)
            // 最近打开：直接采用侧边栏基于使用情况访问权限统计出的名单。
            val pickedAliases = picked.mapNotNull { aliasOf(it) }.toHashSet()
            // 这条兜底路径原先连「收藏」都没排除，现在与 buildRows 用同一套排除集。
            val recentItems = recentItems(loader, context, pickedAliases + pinnedAliases())
            d { "picked=" + picked.size + " recent=" + recentItems.size +
                " second=" + (second != null) }
            d { "recentAliases=" + recentItems.mapNotNull { aliasOf(it) } }
            val out = ArrayList<Any?>(picked.size + recentItems.size + 2)
            head?.let { out.add(it) }
            out.addAll(picked)
            if (recentItems.isNotEmpty()) {
                // 用和「收藏」完全相同的 viewType 新建标题，保证这个头一定按标题布局渲染。
                val recentTitle = makeTitle(loader, head, "最近") ?: second
                recentTitle?.let { out.add(it) }
                out.addAll(recentItems)
            }
            // 标题后面没有条目会多出一个可展开箭头，空分组直接去掉。
            val cleaned = ArrayList<Any?>(out.size)
            for (i in out.indices) {
                val item = out[i]
                if (item?.javaClass?.simpleName == "TitleLabelData") {
                    val next = out.getOrNull(i + 1)
                    if (next == null || next.javaClass.simpleName != "AppLabelData") continue
                }
                cleaned.add(item)
            }
            // 分组标题：收藏段与最近段。
            val titles = cleaned.filter { it?.javaClass?.simpleName == "TitleLabelData" }
            titles.getOrNull(0)?.let { setTitle(it, "收藏") }
            titles.getOrNull(1)?.let { setTitle(it, "最近") }
            return cleaned
        } catch (t: Throwable) {
            d(t) { "reorder fail" }
            return null
        }
    }

    /**
     * 扇形里固定项的 alias 提供者，由 SidebarHookInstaller 注入。
     *
     * 侧边栏进程自己读不到模块配置，固定项只能由安装器转交；取不到时按空集合处理，
     * 「最近」恢复为只排除收藏的旧行为，不影响面板。
     */
    @Volatile
    private var pinnedProvider: (() -> Set<String>)? = null

    fun attachPinnedTargets(provider: () -> Set<String>) {
        pinnedProvider = provider
    }

    /** 固定项 → 面板 alias：应用是「包名#入口」，侧边栏条目用它自己的 alias。 */
    fun aliasOfTarget(target: io.github.hoerire.flymefreeform.config.PinnedTarget): String =
        when (target) {
            is io.github.hoerire.flymefreeform.config.PinnedTarget.App -> target.packageName + "#" + target.className
            is io.github.hoerire.flymefreeform.config.PinnedTarget.Sidebar -> target.alias
        }

    private fun pinnedAliases(): Set<String> = try {
        pinnedProvider?.invoke() ?: emptySet()
    } catch (t: Throwable) {
        d(t) { "pinned aliases fail" }
        emptySet()
    }

    /**
     * 「最近打开的应用」：侧边栏本身就是按「使用情况访问权限」统计的，
     * 这里直接用同一个 UsageStatsManager 查，按最后使用时间倒序，再映射成面板条目。
     */
    private fun recentItems(
        loader: ClassLoader,
        context: android.content.Context?,
        exclude: Set<String>,
    ): List<Any?> {
        if (context == null) {
            d { "recent: no context" }
            return emptyList()
        }
        val manager = context.getSystemService(android.app.usage.UsageStatsManager::class.java)
        if (manager == null) {
            d { "recent: no UsageStatsManager" }
            return emptyList()
        }
        val end = System.currentTimeMillis()
        // 用「切到前台」事件排真实先后；queryUsageStats 的 lastTimeUsed 是按桶聚合的，排序会变成近似字母序。
        val ordered = LinkedHashSet<String>()
        try {
            val events = manager.queryEvents(end - RECENT_WINDOW_MS, end)
            val event = android.app.usage.UsageEvents.Event()
            val hits = ArrayList<Pair<Long, String>>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    hits.add(event.timeStamp to event.packageName)
                }
            }
            hits.sortByDescending { it.first }
            hits.forEach { ordered.add(it.second) }
        } catch (t: Throwable) {
            d(t) { "recent: queryEvents fail" }
        }
        if (ordered.size < 3) {
            // 事件流拿不到时退回使用统计（顺序可能不够准，但不会空）。
            val stats = try {
                manager.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                    end - RECENT_WINDOW_MS,
                    end,
                )
            } catch (t: Throwable) {
                null
            } ?: emptyList()
            stats.asSequence()
                .filter { it.lastTimeUsed > 0 && it.totalTimeInForeground > 0 }
                .sortedByDescending { it.lastTimeUsed }
                .forEach { ordered.add(it.packageName) }
        }
        ordered.remove("com.coloros.smartsidebar")
        ordered.remove("android")
        d { "usage pkgs=" + ordered.size + " " + ordered.take(12) }
        val self = context.packageName
        val picked = ordered.asSequence()
            .filterNot { it == self }
            .mapNotNull { pkg ->
                val activity = activityOf(context, pkg) ?: return@mapNotNull null
                buildAliasItem(loader, context, pkg + "#" + activity)
            }
            // 收藏与扇形固定项都不再出现在「最近」里。
            .filter { aliasOf(it)?.let { a -> a !in exclude } ?: true }
            .toList()
            .distinctBy { aliasOf(it) }
        d { "recent built=" + picked.size + " " + picked.mapNotNull { aliasOf(it) }.take(12) }
        return picked
    }

    /** 应用显示名。 */
    private fun labelOf(context: android.content.Context?, pkg: String): String? = try {
        val pm = context?.packageManager ?: return null
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        null
    }

    /** 系统启动器里该包的入口 Activity；拿不到返回 null。 */
    private fun activityOf(context: android.content.Context, pkg: String): String? = try {
        context.packageManager.getLaunchIntentForPackage(pkg)?.component?.className
    } catch (_: Throwable) {
        null
    }

    /** 按 alias 造条目：`pkg#activity` 是应用/快捷入口，其它当工具。 */
    private fun buildAliasItem(loader: ClassLoader, context: android.content.Context?, alias: String): Any? {
        // 优先由系统 ToolProxy 解析（含标签、图标与点击路由），失败时再构造 EntryBean。
        buildItem(loader, alias)?.let { return it }
        val hash = alias.indexOf('#')
        if (hash <= 0) return null
        val pkg = alias.substring(0, hash)
        val activity = alias.substring(hash + 1)
        return buildAppItem(loader, context, pkg, activity)
    }

    /** 造一个应用条目：EntryBean(alias, pkg, activity, type=2, mutiUserId=0) → AppLabelData。 */
    private fun buildAppItem(
        loader: ClassLoader,
        context: android.content.Context?,
        pkg: String,
        activity: String,
    ): Any? = try {
        val beanCls = Class.forName(
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean",
            false,
            loader,
        )
        // 5 参构造是 private（Kotlin data class 的默认构造），必须走 declared + setAccessible。
        val ctor = beanCls.declaredConstructors.firstOrNull {
            it.parameterCount == 5 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == String::class.java &&
                it.parameterTypes[3] == Int::class.javaPrimitiveType &&
                it.parameterTypes[4] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("EntryBean 5 参构造未找到")
        val bean = ctor.newInstance(pkg + "#" + activity, pkg, activity, 2, 0)
        // 面板标签取自 ItemBean.mLabel；构造出的 bean 不含该字段，仅显示图标而无名称。
        val label = labelOf(context, pkg)
        if (!label.isNullOrEmpty()) {
            var cls: Class<*>? = bean.javaClass
            while (cls != null && cls != Any::class.java) {
                val field = runCatching { cls!!.getDeclaredField("mLabel") }.getOrNull()
                if (field != null) {
                    field.isAccessible = true
                    field.set(bean, label)
                    break
                }
                cls = cls.superclass
            }
        }
        val ald = Class.forName("com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData", false, loader)
        ald.constructors
            .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInstance(bean) }
            ?.newInstance(bean)
    } catch (t: Throwable) {
        d(t) { "buildAppItem fail " + pkg }
        null
    }

    private fun pkgOf(item: Any?): String? = try {
        val bean = item?.javaClass?.getMethod("getEntryBean")?.invoke(item) ?: return null
        bean.javaClass.getMethod("getPkg").invoke(bean) as? String
    } catch (_: Throwable) {
        null
    }

    /**
     * 造一个和 [template] 同 viewType 的分组标题。
     * 系统的 getItemViewType 是按 TitleLabelData.getViewType() 选的布局，
     * 复用 ViewType.USER_APP 之类的值会被当成普通图标；这里直接拷贝模板的枚举值。
     */
    private fun makeTitle(loader: ClassLoader, template: Any?, text: String): Any? = try {
        val cls = Class.forName("com.oplus.smartsidebar.panelview.edgepanel.data.TitleLabelData", false, loader)
        val item = cls.getConstructor(CharSequence::class.java, String::class.java)
            .newInstance(text, text)
        val type = template?.javaClass?.getMethod("getViewType")?.invoke(template)
            ?: runCatching {
                Class.forName("com.oplus.smartsidebar.panelview.data.ViewType", false, loader)
                    .getField("ALL_APP_TABLE_TOOL").get(null)
            }.getOrNull()
        if (type != null) {
            cls.getMethod("setViewType", type.javaClass).invoke(item, type)
        }
        d { "made title=" + text + " viewType=" + type }
        item
    } catch (t: Throwable) {
        d(t) { "make title fail" }
        null
    }

    /** 取系统侧边栏的「最近打开的应用」：ViewDataHandlerImpl -> PanelMainView -> UserPanelView。 */
    private fun systemRecentData(loader: ClassLoader): List<Any> = try {
        val handler = Class.forName(
            "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl",
            false,
            loader,
        )
        val instance = handler.getField("INSTANCE").get(null)
        val mainView = handler.getMethod("getMPanelMainView").invoke(instance)
        val userPanel = mainView?.javaClass?.getMethod("getMPanel")?.invoke(mainView)
        @Suppress("UNCHECKED_CAST")
        val list = userPanel?.javaClass?.getMethod("getRecentData")?.invoke(userPanel) as? List<Any>
        if (list == null) {
            d { "recent data null" }
            emptyList()
        } else {
            list.filterNotNull()
        }
    } catch (t: Throwable) {
        d(t) { "recent data fail" }
        emptyList()
    }

    /** 改写分组标题文本：把 TitleLabelData 里的 String 字段改成目标文案。 */
    private fun setTitle(item: Any, text: String) {
        try {
            var cls: Class<*>? = item.javaClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    if (field.type != String::class.java && field.type != CharSequence::class.java) continue
                    field.isAccessible = true
                    field.set(item, text)
                }
                cls = cls.superclass
            }
            d { "title -> " + text }
        } catch (t: Throwable) {
            d(t) { "set title fail" }
        }
    }

    /** 用系统 ToolProxy 取 EntryBean，再包成 AppLabelData。 */
    private fun buildItem(loader: ClassLoader, alias: String): Any? = try {
        val proxy = Class.forName(
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.proxies.ToolProxy",
            false,
            loader,
        )
        val instance = proxy.getMethod("getInstance").invoke(null)
        val bean = proxy.getMethod("getBean", String::class.java).invoke(instance, alias)
        if (bean == null) {
            null
        } else {
            val ald = Class.forName("com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData", false, loader)
            ald.constructors
                .firstOrNull { it.parameterCount == 1 && it.parameterTypes[0].isInstance(bean) }
                ?.newInstance(bean)
        }
    } catch (t: Throwable) {
        d(t) { "build fail " + alias }
        null
    }

    private fun aliasOf(item: Any?): String? = try {
        val bean = item?.javaClass?.getMethod("getEntryBean")?.invoke(item) ?: return null
        bean.javaClass.getMethod("getAlias").invoke(bean) as? String
    } catch (_: Throwable) {
        null
    }

    /** 按文件里的先后顺序返回 alias，只取工具(0)与应用/快捷入口(2)。 */
    private fun readOrder(): List<String>? = try {
        if (!XML.exists()) {
            d { "xml missing: " + XML }
            null
        } else {
            Regex("<item([^>]*)>").findAll(XML.readText()).mapNotNull { m ->
                val attrs = m.groupValues[1]
                val type = attr(attrs, "type")
                val alias = attr(attrs, "alias")
                if (alias.isNullOrEmpty()) null
                else if (type == null || type == "0" || type == "2") alias
                else null
            }.toList()
        }
    } catch (t: Throwable) {
        d(t) { "read xml fail" }
        null
    }

    private fun attr(attrs: String, name: String): String? =
        Regex(name + "=\"([^\"]*)\"").find(attrs)?.groupValues?.getOrNull(1)
}
