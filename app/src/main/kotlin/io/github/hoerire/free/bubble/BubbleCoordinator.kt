package io.github.hoerire.free.bubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import io.github.hoerire.free.config.ModulePreferences
import io.github.hoerire.free.config.ModuleSettingsSnapshot
import io.github.hoerire.free.hook.ProcessConfiguration
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 气泡通知的状态机。
 *
 * 它只做「什么时候该有什么」：队列里谁是队首、现在该显示气泡还是内容卡、点开之后轮到谁。
 * 通知监听、窗口装卸、启动应用分别由 [BubblePipeline]、[BubbleWindowHost]、[BubbleLauncher]
 * 负责；这里不碰 WindowManager，也不拼 Intent。
 *
 * 状态只有一个 [Phase] 字段位，而不是原实现的三个可空引用互相判断；窗口转移期间统一进
 * [Phase.Dismissing]，此时任何异步回调都拿不到“半开”的窗口。
 */
internal class BubbleCoordinator(
    private val module: XposedModule,
    private val context: Context,
    private val configuration: ProcessConfiguration,
) : BubblePipeline.Sink {
    private enum class Phase {
        Idle,
        Shown,
        Dismissing,
    }

    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val queue = BubbleQueue()
    private val placement = BubblePlacementStore(context)
    private val windows = BubbleWindowHost(::log)
    private val environment = BubbleEnvironmentGate(context)
    private val payloads = BubblePayloadFactory(context) { code, throwable -> log(code, throwable) }
    private val launcher = BubbleLauncher(context) { code, throwable -> log(code, throwable) }

    @Volatile
    private var settings: BubbleSettings = BubbleSettings.INACTIVE
    private var headsUpGate: BubbleHeadsUpGate? = null
    private var cloneCatalog: CloneCatalogPublisher? = null
    private var statusTicker: StatusTickerController? = null
    private var suggestions: ImmediateSuggestionController? = null
    private var phase = Phase.Idle
    private var shownItem: BubbleItem? = null
    private var transitioning: BubbleSurfaceView? = null
    private var previewSuppressedFor: BubbleIdentity? = null
    private var screenSuspended = false
    private var screenInteractive = true
    private var pipelineInstalled = false
    private var pipelineAttempts = 0
    private var headsUpAttempts = 0

    /** 最近一次气泡落点：方向切换时复用同方向的运行位置。 */
    private var runtimeX = 0
    private var runtimeY = 0
    private var runtimeDock = 0
    private var runtimeLandscape = false
    private var hasRuntimePosition = false

    private val recentKeys = LinkedHashMap<String, Long>()
    private val anchorTick =
        object : Runnable {
            override fun run() {
                val preview = windows.preview ?: return
                val bubble = windows.bubble ?: return
                if (!bubble.isAttached()) return
                runCatching { preview.updateAnchor(bubble.previewAnchorBounds()) }
                handler.postDelayed(this, ANCHOR_TICK_MS)
            }
        }

    private val displayReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handler.post { onScreenOff() }
                    Intent.ACTION_SCREEN_ON -> handler.post { onScreenOn() }
                    Intent.ACTION_USER_PRESENT -> handler.post { onScreenOn() }
                    Intent.ACTION_USER_UNLOCKED -> handler.post { onScreenOn() }
                    Intent.ACTION_CLOSE_SYSTEM_DIALOGS ->
                        handler.post { dismissPreview(animated = true) }
                }
            }
        }

    fun start(classLoader: ClassLoader) {
        if (!started.compareAndSet(false, true)) return
        settings = BubbleSettings.from(configuration.snapshot)
        screenInteractive = isScreenInteractive()
        registerDisplayReceiver()
        // 设置实时生效：快照一变就重算允许集合，并把已不合法的那颗收掉。
        configuration.observe { next -> handler.post { applySettings(next) } }
        installPipeline(classLoader)
        installHeadsUp(classLoader)
        // 状态栏滚动：与气泡共用通知管线，但有自己的白名单与视图。
        StatusTickerController(context, module, { settings }, ::log).also { ticker ->
            statusTicker = ticker
            ticker.install(classLoader)
        }
        // 分身目录由 SystemUI 进程自己发布（本进程持有 MANAGE_USERS），不依赖 system_server。
        cloneCatalog = CloneCatalogPublisher(context, ::log).also { it.start() }
        // 即时建议：手电筒/耳机/应用安装由控制器自己监听；剪贴板由 system_server 广播。
        suggestions = ImmediateSuggestionController(context, suggestionHost).also { it.start() }
        registerClipboardReceiver()
        registerGameModeObserver()
        log(
            if (settings.active) "BUBBLE_COORDINATOR_READY" else "BUBBLE_COORDINATOR_IDLE",
            null,
        )
    }

    // ---- 管线 / 横幅 ---------------------------------------------------------

    private fun installPipeline(classLoader: ClassLoader) {
        if (pipelineInstalled) return
        val count = runCatching {
            BubblePipeline(module, handler, ::log).install(classLoader, this)
        }.getOrDefault(0)
        if (count > 0) {
            pipelineInstalled = true
            return
        }
        if (pipelineAttempts >= MAX_INSTALL_ATTEMPTS) {
            log("BUBBLE_PIPELINE_UNAVAILABLE", null)
            return
        }
        pipelineAttempts++
        handler.postDelayed({ installPipeline(classLoader) }, INSTALL_RETRY_MS)
    }

    private fun installHeadsUp(classLoader: ClassLoader) {
        if (headsUpGate != null) return
        val gate = BubbleHeadsUpGate(module, context, { settings }, ::log)
        gate.install(classLoader)
        headsUpGate = gate
        if (headsUpAttempts < MAX_INSTALL_ATTEMPTS) {
            // 插件类加载器可能晚于 Application.onCreate；补挂一次也无害（内部有幂等位）。
            headsUpAttempts++
        }
    }

    // ---- 通知事件 -----------------------------------------------------------

    override fun onPosted(sbn: StatusBarNotification) {
        runCatching {
            if (!settings.active) return
            val packageName = sbn.packageName ?: return
            if (isIgnoredPackage(packageName)) return
            val notification = sbn.notification ?: return
            if (!isBubbleEligible(notification)) return
            val identity = BubbleIdentity(packageName, sbn.userId, sbn.key)
            val wantsBubble = settings.accepts(identity)
            val wantsTicker =
                settings.statusScrollActive && settings.acceptsStatusScroll(packageName)
            if (!wantsBubble && !wantsTicker) return
            environment.suppressionReason(packageName, settings)?.let { reason ->
                log("BUBBLE_SKIP_" + reason + "_" + packageName, null)
                return
            }
            if (wantsBubble && isDuplicate(sbn.key)) return
            val payload = payloads.build(sbn)
            // 状态栏滚动独立于气泡白名单：没开气泡的应用也可以只滚状态栏。
            if (wantsTicker) statusTicker?.onPosted(sbn, payload)
            if (!wantsBubble) return
            val item = BubbleItem(identity, payload, notification.contentIntent)
            queue.add(item)
            if (windows.bubble == null && transitioning == null) {
                showHead()
            } else {
                updateShownItem(item)
            }
        }.onFailure { log("BUBBLE_POSTED_FAILED", it) }
    }

    override fun onRemoved(sbn: StatusBarNotification, reason: Int) {
        runCatching {
            val key = sbn.key ?: return
            if (!BubbleRemovalPolicy.shouldDrop(reason)) return
            val headBefore = queue.head
            val removed = queue.remove(key) ?: return
            // 用户点掉/划掉/打开这条通知后，状态栏不应该继续滚它的内容。
            statusTicker?.stopFor(key)
            if (headBefore?.notificationKey != key) {
                updateChildren()
                return
            }
            dismissPreview(animated = false)
            val next = queue.head
            previewSuppressedFor = next?.identity
            val current = windows.bubble
            when {
                current != null && current.isAttached() && transitioning == null && next != null ->
                    promoteInPlace(current, next)
                current != null && transitioning == null -> {
                    transitioning = current
                    rememberPosition(current)
                    current.dismiss()
                    handler.postDelayed({
                        if (transitioning === current) transitioning = null
                        windows.forgetBubble(current)
                        showHead()
                    }, TRANSITION_DELAY_MS)
                }
                current == null -> showHead()
            }
            log("BUBBLE_REMOVED_" + removed.packageName, null)
        }.onFailure { log("BUBBLE_REMOVED_FAILED", it) }
    }

    // ---- 气泡 -----------------------------------------------------------------

    private fun showHead() {
        if (phase == Phase.Dismissing) return
        if (windows.bubble != null || transitioning != null) return
        val item = queue.head ?: return
        val bubble = BubbleSurfaceView(context, BubbleAppearance(settings, placement))
        val landscape = isLandscape()
        if (hasRuntimePosition && runtimeLandscape == landscape) {
            bubble.applyRuntimePosition(runtimeX, runtimeY, runtimeDock)
        } else {
            bubble.applyPersistedPosition(
                placement.positionX(landscape),
                placement.positionY(landscape),
                placement.dockEdge(landscape),
            )
        }
        bubble.setIcon(item.payload.icon)
        bubble.setAppBadge(item.payload.appIcon)
        bubble.setDockingEnabled(!(item.suggestionId != null && settings.suggestionNoEdge))
        bubble.setAutoDismissValidator { settings.autoDismiss && !screenSuspended }
        bubble.setOnClickAction {
            if (windows.bubble === bubble) activateHead(burst = true)
        }
        bubble.setOnSwipeListener { up ->
            if (windows.bubble !== bubble) return@setOnSwipeListener
            if (up) {
                expandStack()
            } else {
                dismissHeadBySwipe()
            }
        }
        bubble.setOnLongPressAction {
            if (windows.bubble === bubble) expandStack()
        }
        bubble.setOnExpandGestureAction {
            if (windows.bubble === bubble) expandStack()
        }
        bubble.setOnDismissedAction { onBubbleDismissed(bubble) }
        bubble.setOnPositionChangedListener { isLandscapeNow, x, y, dock ->
            // 只有浮动位置才作为下一条气泡的落点；贴边收起/拖到边缘不覆盖它。
            if (dock == 0) {
                // 注意：回调里的 x/y 是**百分比**，只能给 placement；运行时落点
                // 必须是像素，统一从 getWindowBounds() 取，别把百分比当像素用。
                placement.persist(isLandscapeNow, x, y)
                rememberPosition(bubble)
            }
            if (settings.previewEdgeOnly && windows.bubble === bubble) {
                if (dock == 0) {
                    dismissPreview(animated = true)
                } else if (windows.preview == null) {
                    showPreview()
                }
            }
        }
        bubble.setOnDragStartedAction {
            windows.dismissStack()
            bubble.setStackExpanded(false)
            bubble.visibility = View.VISIBLE
            dismissPreview(animated = true)
        }
        bubble.setOnBottomDismissAction { dismissForBottomDrop() }
        bubble.setOnConfigurationChangedAction {
            // 旧方向的运行位置作废：新方向应该读编辑器里的默认落点。
            hasRuntimePosition = false
            val current = queue.head
            dismissPreview(animated = false)
            if (current != null && windows.bubble === bubble && !screenSuspended &&
                transitioning == null
            ) {
                handler.postDelayed({
                    if (windows.bubble === bubble && queue.head?.identity == current.identity &&
                        windows.preview == null
                    ) {
                        showPreview()
                    }
                }, ROTATE_PREVIEW_DELAY_MS)
            }
        }
        bubble.setOnLayoutChangedAction {
            windows.preview?.let { preview ->
                if (windows.bubble === bubble) {
                    runCatching { preview.updateAnchor(bubble.previewAnchorBounds()) }
                }
            }
        }
        val timeout = timeoutFor(item)
        if (!windows.showBubble(bubble, timeout.seconds, timeout.dim)) {
            log("BUBBLE_SHOW_REJECTED_" + item.packageName, bubble.getShowError())
            if (screenSuspended || !isScreenInteractive()) return
            if (item.notificationKey != null) {
                queue.remove(item.notificationKey)
            } else {
                queue.removeHead()
            }
            handler.post { showHead() }
            return
        }
        phase = Phase.Shown
        shownItem = item
        updateChildren()
        val suppressed = previewSuppressedFor == item.identity
        if (suppressed) {
            previewSuppressedFor = null
            log("BUBBLE_PREVIEW_SKIP_SUPPRESSED_ONCE", null)
        }
        if (!suppressed) {
            handler.postDelayed({
                if (windows.bubble === bubble && queue.head?.identity == item.identity) {
                    showPreview()
                }
            }, INITIAL_PREVIEW_DELAY_MS)
        }
        log("BUBBLE_SHOWN_" + item.packageName + "_q" + queue.size, null)
    }

    private fun updateShownItem(item: BubbleItem) {
        val bubble = windows.bubble ?: return
        // 新消息到达时，上一颗如果正贴边收着，先弹回浮动位置再换内容。
        bubble.popOut()
        shownItem = item
        val timeout = timeoutFor(item)
        bubble.updateTimeout(timeout.seconds, timeout.dim)
        bubble.setIconAnimated(item.payload.icon)
        bubble.setAppBadge(item.payload.appIcon)
        updateChildren()
        dismissPreview(animated = false)
        showPreview()
    }

    private fun promoteInPlace(bubble: BubbleSurfaceView, next: BubbleItem) {
        bubble.popOut()
        shownItem = next
        bubble.setIconAnimated(next.payload.icon)
        bubble.setAppBadge(next.payload.appIcon)
        val timeout = timeoutFor(next)
        bubble.updateTimeout(timeout.seconds, timeout.dim)
        updateChildren()
        showPreview()
    }

    private fun activateHead(burst: Boolean) {
        val item = queue.head ?: return
        val bubble = windows.bubble ?: return
        if (phase == Phase.Dismissing) return
        // 点开气泡等于「已读」：先停掉这条通知的状态栏滚动，再走开启动画。
        statusTicker?.stopFor(item.notificationKey)
        phase = Phase.Dismissing
        transitioning = bubble
        rememberPosition(bubble)
        queue.removeHead()
        dismissPreview(animated = true)
        val completion = {
            if (transitioning === bubble) transitioning = null
            windows.forgetBubble(bubble)
            previewSuppressedFor = queue.head?.identity
            phase = Phase.Idle
            showHead()
        }
        if (burst) {
            runItemAction(item)
            bubble.playBurst(completion)
        } else {
            bubble.dismiss()
            handler.postDelayed({
                runItemAction(item)
                completion()
            }, TRANSITION_DELAY_MS)
        }
    }

    private fun dismissHeadBySwipe() {
        val item = queue.head ?: return
        val bubble = windows.bubble ?: return
        if (phase == Phase.Dismissing) return
        statusTicker?.stopFor(item.notificationKey)
        phase = Phase.Dismissing
        transitioning = bubble
        rememberPosition(bubble)
        queue.removeHead()
        dismissPreview(animated = true)
        windows.dismissStack()
        bubble.dismiss()
        handler.postDelayed({
            if (transitioning === bubble) transitioning = null
            windows.forgetBubble(bubble)
            previewSuppressedFor = queue.head?.identity
            phase = Phase.Idle
            showHead()
        }, TRANSITION_DELAY_MS)
        log("BUBBLE_SWIPED_AWAY_" + item.packageName, null)
    }

    private fun onBubbleDismissed(bubble: BubbleSurfaceView) {
        if (shownItem == null && queue.head == null) {
            windows.forgetBubble(bubble)
            phase = Phase.Idle
            return
        }
        // 模块自身发起的 dismiss（点开/换队首）由 transitioning 拦住，不能再扣一次队首。
        if (transitioning === bubble || windows.bubble !== bubble) {
            windows.forgetBubble(bubble)
            return
        }
        // 超时/用户点空白收掉时也要记住当前落点，下一颗气泡直接出现在这里。
        rememberPosition(bubble)
        windows.forgetBubble(bubble)
        shownItem = null
        queue.removeHead()
        dismissPreview(animated = false)
        previewSuppressedFor = queue.head?.identity
        phase = Phase.Idle
        showHead()
    }

    private fun dismissForBottomDrop() {
        queue.clear()
        shownItem = null
        dismissPreview(animated = false)
        windows.dismissStack()
        log("BUBBLE_ALL_DISMISSED_BOTTOM_DROP", null)
    }

    private fun dismissAll() {
        queue.clear()
        shownItem = null
        previewSuppressedFor = null
        dismissPreview(animated = false)
        windows.dismissStack()
        val bubble = windows.bubble
        phase = Phase.Idle
        transitioning = null
        if (bubble != null) {
            runCatching { bubble.removeImmediately() }
            windows.forgetBubble(bubble)
        }
    }

    private fun expandStack() {
        val bubble = windows.bubble ?: return
        val items = queue.snapshot().take(MAX_STACK_ITEMS)
        if (items.size <= 1) return
        dismissPreview(animated = true)
        val icons = items.mapNotNull { payloads.clone(it.payload.icon) }
        if (icons.isEmpty()) return
        val overlay =
            BubbleStackOverlay(
                context,
                icons,
                bubble.getCoreBounds(),
                bubble.alpha,
                { index -> openStackItem(items, index) },
                { onStackCollapsed() },
            )
        if (windows.showStack(overlay)) {
            bubble.setStackExpanded(true)
            bubble.visibility = View.INVISIBLE
        }
    }

    private fun openStackItem(items: List<BubbleItem>, index: Int) {
        val item = items.getOrNull(index) ?: return
        queue.remove(item.notificationKey)
        windows.dismissStack()
        windows.bubble?.let { bubble ->
            bubble.setStackExpanded(false)
            bubble.visibility = View.VISIBLE
        }
        updateChildren()
        runItemAction(item)
        val head = queue.head
        if (head == null) {
            windows.bubble?.let { bubble ->
                shownItem = null
                bubble.dismiss()
            }
        }
    }

    private fun onStackCollapsed() {
        val bubble = windows.bubble ?: return
        bubble.setStackExpanded(false)
        bubble.visibility = View.VISIBLE
        val head = queue.head
        if (head == null) {
            shownItem = null
            bubble.dismiss()
            return
        }
        promoteInPlace(bubble, head)
    }

    private fun updateChildren() {
        val bubble = windows.bubble ?: return
        val items = queue.snapshot()
        bubble.setStackCount(if (items.size > 1) items.size else 0)
        bubble.setChildIcons(
            items.drop(1).take(MAX_CHILD_ICONS).mapNotNull { payloads.clone(it.payload.icon) },
        )
    }

    // ---- 内容卡 ---------------------------------------------------------------

    private fun showPreview() {
        if (!settings.previewActive) {
            log("BUBBLE_PREVIEW_SKIP_OFF", null)
            return
        }
        if (windows.preview != null) {
            log("BUBBLE_PREVIEW_SKIP_EXISTING", null)
            return
        }
        if (windows.stack != null) {
            log("BUBBLE_PREVIEW_SKIP_STACK", null)
            return
        }
        val bubble = windows.bubble ?: return
        val item = queue.head ?: return
        if (deviceLocked()) {
            log("BUBBLE_PREVIEW_SKIP_LOCKED", null)
            return
        }
        if (settings.previewEdgeOnly && !bubble.isDocked()) {
            log("BUBBLE_PREVIEW_SKIP_EDGE_ONLY", null)
            return
        }
        if (!item.payload.hasContent) {
            log("BUBBLE_PREVIEW_SKIP_EMPTY", null)
            return
        }
        val preview =
            NotificationPreviewView(
                context,
                payloads.clone(item.payload.icon ?: item.payload.appIcon),
                item.payload.appName,
                item.payload.title,
                item.payload.body,
                settings.previewAlpha,
                settings.previewAutoWidth,
                settings.previewWidthDp,
                settings.previewOverlayAlpha,
            )
        preview.setOnPreviewClickListener {
            preview.dismiss()
            // 点内容卡也是点开通知：状态栏滚动到此为止。
            statusTicker?.stopFor(item.notificationKey)
            if (queue.head?.identity == item.identity) {
                activateHead(burst = false)
            } else {
                queue.remove(item.notificationKey)
                updateChildren()
                runItemAction(item)
            }
        }
        preview.setOnDismissedListener {
            windows.forgetPreview(preview)
            val current = windows.bubble
            if (current === bubble) {
                bubble.playPreviewAbsorb()
                bubble.setLandingAnimationSuppressed(false)
                val head = queue.head
                if (head != null && head.identity != item.identity) {
                    bubble.setIconAnimated(head.payload.icon)
                    bubble.setAppBadge(head.payload.appIcon)
                }
            }
        }
        bubble.setLandingAnimationSuppressed(true)
        val timeout = previewTimeoutFor(item.payload)
        if (!windows.showPreview(preview, bubble.previewAnchorBounds(), settings.previewPosition, timeout)) {
            windows.forgetPreview(preview)
            bubble.setLandingAnimationSuppressed(false)
            log("BUBBLE_PREVIEW_REJECTED", preview.getShowError())
            return
        }
        handler.removeCallbacks(anchorTick)
        handler.post(anchorTick)
        log("BUBBLE_PREVIEW_SHOWN_" + item.packageName + "_" + timeout + "s", null)
    }

    private fun dismissPreview(animated: Boolean) {
        handler.removeCallbacks(anchorTick)
        windows.dismissPreview(animated)
        windows.bubble?.setLandingAnimationSuppressed(false)
    }

    /** 固定超时是下限，正文越长给的时间越多；上限 60s。 */
    private fun previewTimeoutFor(payload: BubblePayload): Int {
        if (!settings.previewDynamicTimeout) return settings.previewTimeoutSeconds
        val text = payload.title.trim() + "\n" + payload.body.trim()
        var cjk = 0
        var latin = 0
        var whitespace = 0
        for (ch in text) {
            when {
                ch.isWhitespace() -> whitespace++
                ch.code in 0x2E80..0x9FFF -> cjk++
                else -> latin++
            }
        }
        val seconds =
            1.8f + cjk / 8.5f + latin / 15f + whitespace / 45f +
                if (payload.title.isNotEmpty()) 0.8f else 0f
        return maxOf(
            settings.previewTimeoutSeconds,
            minOf(60, maxOf(3, kotlin.math.ceil(seconds.toDouble()).toInt())),
        )
    }


    // ---- 即时建议 -------------------------------------------------------------

    private val suggestionHost =
        object : ImmediateSuggestionController.Host {
            override fun isSuggestionEnabled(type: String): Boolean =
                settings.isSuggestionEnabled(type)

            override fun isNativeClipboardSuggestionAvailable(): Boolean = false

            override fun getForegroundPackage(): String? = foregroundPackage()

            override fun showSuggestion(suggestion: ImmediateSuggestionController.Suggestion) {
                enqueueSuggestion(suggestion)
            }

            override fun dismissSuggestion(id: String) {
                dismissSuggestionKey("suggestion:" + id)
            }

            override fun logSuggestion(message: String, error: Throwable?) {
                log(message, error)
            }
        }

    private val clipboardReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ModulePreferences.ACTION_CLIPBOARD_SUGGESTION) return
                val kinds = intent.getIntArrayExtra(ModulePreferences.EXTRA_CLIP_KINDS)
                    ?: return
                val values = intent.getStringArrayExtra(ModulePreferences.EXTRA_CLIP_VALUES)
                    ?: return
                val displays = intent.getStringArrayExtra(ModulePreferences.EXTRA_CLIP_DISPLAYS)
                val apps = intent.getStringArrayExtra(ModulePreferences.EXTRA_CLIP_APPS)
                handler.post { onClipboardSuggestions(kinds, values, displays, apps) }
            }
        }

    private fun registerClipboardReceiver() {
        runCatching {
            context.registerReceiver(
                clipboardReceiver,
                IntentFilter(ModulePreferences.ACTION_CLIPBOARD_SUGGESTION),
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { log("BUBBLE_CLIPBOARD_RECEIVER_FAILED", it) }
    }

    /**
     * 游戏模式开关一变就重算：进入游戏时收掉屏上已有气泡，
     * 退出游戏后新通知照常出气泡，不需要用户手动处理。
     */
    private fun registerGameModeObserver() {
        runCatching {
            context.contentResolver.registerContentObserver(
                BubbleEnvironmentGate.gameModeUri(context),
                false,
                gameModeObserver,
            )
        }.onFailure { log("BUBBLE_GAME_OBSERVER_FAILED", it) }
    }

    private val gameModeObserver =
        object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.post {
                    environment.invalidateGameCache()
                    if (!settings.skipGame) return@post
                    if (environment.isGameMode()) {
                        dismissAll()
                        statusTicker?.removeAll()
                        log("BUBBLE_DISMISSED_GAME_MODE", null)
                    }
                }
            }
        }

    private fun onClipboardSuggestions(
        kinds: IntArray,
        values: Array<String>,
        displays: Array<String>?,
        apps: Array<String>?,
    ) {
        if (!settings.suggestionsActive || !settings.suggestionClipboard) return
        val controller = suggestions ?: return
        // 新的一次复制替换上一次的剪贴板建议。
        queue.prune { !(it.suggestionId?.startsWith("clipboard:") == true) }
        kinds.forEachIndexed { index, kind ->
            if (kind <= 0 || index >= values.size) return@forEachIndexed
            val value = values[index]
            if (value.isEmpty()) return@forEachIndexed
            val display = displays?.getOrNull(index).orEmpty()
            val appPackage = apps?.getOrNull(index)?.takeIf { it.isNotEmpty() }
            runCatching {
                controller.showClassifiedSuggestion(
                    "clipboard:" + ClipboardClassifier.kindTag(kind),
                    ClipboardClassifier.Result(kind, value, display, appPackage),
                )
            }.onFailure { log("BUBBLE_CLIPBOARD_SUGGESTION_FAILED", it) }
        }
    }

    private fun enqueueSuggestion(suggestion: ImmediateSuggestionController.Suggestion) {
        if (!settings.suggestionsActive) return
        // 建议同样受免打扰约束：来源应用正在前台、游戏模式、锁屏息屏都不弹。
        environment.suppressionReason(suggestion.sourcePackage, settings)?.let { reason ->
            log("BUBBLE_SUGGESTION_SKIP_" + reason + "_" + suggestion.id, null)
            return
        }
        val key = "suggestion:" + suggestion.id
        val identity =
            BubbleIdentity(suggestion.sourcePackage.ifBlank { "android" }, 0, key)
        val payload =
            BubblePayload(
                suggestion.icon,
                suggestion.appIcon,
                "即时建议",
                suggestion.title,
                suggestion.body,
            )
        val item =
            BubbleItem(
                identity = identity,
                payload = payload,
                contentIntent = null,
                directAction = suggestion.action,
                // 0 = 用全局停留时间，-1 = 不自动消失（保持常亮这类需要一直等用户点）。
                timeoutOverride = suggestion.timeoutSeconds.takeIf { it != 0 },
                suggestionId = suggestion.id,
            )
        // 建议到达时收起已展开的扇环，下一次展开才是新队列。
        windows.dismissStack()
        queue.add(item)
        if (windows.bubble == null && transitioning == null) {
            showHead()
        } else {
            updateShownItem(item)
        }
        log("BUBBLE_SUGGESTION_" + suggestion.id, null)
    }

    private fun dismissSuggestionKey(key: String) {
        val headBefore = queue.head
        if (queue.remove(key) == null) return
        if (headBefore?.notificationKey != key) {
            updateChildren()
            return
        }
        dismissPreview(animated = false)
        val next = queue.head
        previewSuppressedFor = next?.identity
        val bubble = windows.bubble
        if (bubble != null && transitioning == null) {
            phase = Phase.Dismissing
            transitioning = bubble
            rememberPosition(bubble)
            bubble.dismiss()
            handler.postDelayed({
                if (transitioning === bubble) transitioning = null
                windows.forgetBubble(bubble)
                phase = Phase.Idle
                showHead()
            }, TRANSITION_DELAY_MS)
        } else if (bubble == null) {
            showHead()
        }
    }

    private fun runItemAction(item: BubbleItem) {
        val direct = item.directAction
        if (direct != null) {
            runCatching { direct.invoke() }
                .onFailure { log("BUBBLE_SUGGESTION_ACTION_FAILED", it) }
            return
        }
        // 建议项没有 contentIntent，动作缺失时不要拿伪包名去启动系统。
        if (item.suggestionId != null) return
        launcher.open(item)
    }

    private fun foregroundPackage(): String? = environment.foregroundPackage()

    // ---- 环境 -----------------------------------------------------------------

    private fun onScreenOff() {
        screenSuspended = true
        screenInteractive = false
        dismissPreview(animated = true)
        windows.dismissStack()
        if (settings.skipLocked) {
            // 免打扰打开时息屏即收回：锁屏上不该留着浮层，新消息也已在 onPosted 里跳过。
            dismissAll()
            return
        }
        windows.bubble?.let { bubble ->
            // 免打扰关闭时保持旧行为：气泡留在原位，点亮即可看到。
            bubble.setStackExpanded(false)
            bubble.visibility = View.VISIBLE
            bubble.updateTimeout(0, false)
        }
    }

    private fun onScreenOn() {
        screenInteractive = isScreenInteractive()
        screenSuspended = false
        settings = BubbleSettings.from(configuration.snapshot)
        val bubble = windows.bubble
        if (bubble != null && bubble.isAttached()) {
            val timeout = timeoutFor(queue.head)
            bubble.updateTimeout(timeout.seconds, timeout.dim)
            updateChildren()
            if (!settings.previewEdgeOnly || bubble.isDocked()) {
                handler.postDelayed({ showPreview() }, SCREEN_ON_PREVIEW_DELAY_MS)
            }
            return
        }
        if (queue.head != null && transitioning == null) {
            previewSuppressedFor = queue.head?.identity
            showHead()
        }
    }

    private fun applySettings(next: ModuleSettingsSnapshot) {
        val previous = settings
        val updated = BubbleSettings.from(next)
        settings = updated
        if (updated.active != previous.active) {
            log(if (updated.active) "BUBBLE_FEATURE_ON" else "BUBBLE_FEATURE_OFF", null)
        }
        statusTicker?.applySettings(updated)
        // 常亮建议的轮询跟随开关，关掉就立刻撤掉半暗层。
        suggestions?.onSettingsChanged()
        if (!updated.active) {
            if (previous.active) dismissAll()
            return
        }
        queue.prune { updated.accepts(it.identity) }
        val head = queue.head
        val bubble = windows.bubble
        if (bubble != null && (head == null || head.identity != shownItem?.identity)) {
            phase = Phase.Dismissing
            transitioning = bubble
            rememberPosition(bubble)
            dismissPreview(animated = false)
            bubble.dismiss()
            handler.postDelayed({
                if (transitioning === bubble) transitioning = null
                windows.forgetBubble(bubble)
                phase = Phase.Idle
                showHead()
            }, TRANSITION_DELAY_MS)
            return
        }
        if (bubble != null && head != null) {
            val timeout = timeoutFor(queue.head)
            bubble.updateTimeout(timeout.seconds, timeout.dim)
        }
    }

    private fun timeoutFor(item: BubbleItem?): BubbleTimeout {
        // 即时建议自带停留时间：>0 为秒数，-1 表示不自动消失。
        item?.timeoutOverride?.let { override ->
            return if (override > 0) BubbleTimeout(override, false) else BubbleTimeout(0, false)
        }
        if (deviceLocked() || !screenInteractive) return BubbleTimeout(0, false)
        if (!settings.autoDismiss) return BubbleTimeout(0, false)
        return BubbleTimeout(settings.timeoutSeconds, settings.dimOnTimeout)
    }

    private fun rememberPosition(bubble: BubbleSurfaceView) {
        // 贴边状态不参与“下一条气泡出现在哪”，保留上一次的浮动坐标。
        if (bubble.isDocked()) return
        val bounds = runCatching { bubble.getWindowBounds() }.getOrNull() ?: return
        runtimeX = bounds.left
        runtimeY = bounds.top
        runtimeDock = bubble.getDockEdge()
        runtimeLandscape = isLandscape()
        hasRuntimePosition = true
    }

    private fun isBubbleEligible(notification: android.app.Notification): Boolean {
        if ((notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if ((notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0) return false
        return true
    }

    private fun isIgnoredPackage(packageName: String): Boolean =
        packageName == "android" ||
            packageName == "com.android.systemui" ||
            packageName == "com.oplus.systemui" ||
            packageName == context.packageName

    private fun isDuplicate(key: String?): Boolean {
        if (key == null) return false
        val now = SystemClock.elapsedRealtime()
        val last = recentKeys.put(key, now)
        if (recentKeys.size > 64) {
            recentKeys.entries.removeAll { now - it.value > 30_000L }
        }
        return last != null && now - last < DUPLICATE_WINDOW_MS
    }

    private fun isScreenInteractive(): Boolean = environment.isScreenInteractive()

    private fun deviceLocked(): Boolean = environment.isLocked()

    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun registerDisplayReceiver() {
        runCatching {
            context.registerReceiver(
                displayReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { log("BUBBLE_DISPLAY_RECEIVER_FAILED", it) }
    }

    private fun log(code: String, throwable: Throwable?) {
        module.log(
            if (throwable == null) Log.INFO else Log.WARN,
            "Free",
            code,
            throwable,
        )
    }

    private data class BubbleTimeout(val seconds: Int, val dim: Boolean)

    private companion object {
        const val MAX_STACK_ITEMS = 6
        const val MAX_CHILD_ICONS = 3
        const val DUPLICATE_WINDOW_MS = 1_200L
        const val TRANSITION_DELAY_MS = 240L
        const val INITIAL_PREVIEW_DELAY_MS = 40L
        const val ROTATE_PREVIEW_DELAY_MS = 110L
        const val SCREEN_ON_PREVIEW_DELAY_MS = 180L
        const val ANCHOR_TICK_MS = 150L
        const val INSTALL_RETRY_MS = 800L
        const val MAX_INSTALL_ATTEMPTS = 5
    }
}

