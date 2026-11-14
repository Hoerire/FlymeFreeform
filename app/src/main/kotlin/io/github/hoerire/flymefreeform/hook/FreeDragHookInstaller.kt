package io.github.hoerire.flymefreeform.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.hoerire.flymefreeform.log.ModuleLog
import io.github.hoerire.flymefreeform.log.VerboseLog
import java.lang.reflect.Method

/**
 * 自由拖拽：解除内容传送门的长按拖拽准入限制。
 *
 * 反编译结论（oplus-services.jar / oplus-framework.jar）：
 *   应用进程 bind 时 OplusViewDragSystemServer#preBindApplication 向
 *   OplusViewDragRusHelper#getPackageDragConfig(pkg) 取配置，取到 null 就直接 return，
 *   应用侧的 OplusViewDragManager#init 也随之提前结束 —— 长按拖拽于是完全不接管。
 *   配置来自 /system_ext/oplus/sys_view_drag_list.xml（可被 /data 下的 RUS 版本覆盖）：
 *     包名 → Activity（窗口标题 contains 匹配）→ 具体 View 类名（getSimpleName() 精确相等）
 *
 * 做法：给每个包补一份通用配置。
 *   · 通用条目的 activityName 用包名 —— findViewDragList 用 windowTitle.contains(name)，
 *     窗口标题形如「包名/Activity」，包名必然命中，等于对所有页面生效；
 *   · ViewDragInfo 只填 viewName + type，其余字段为空时 isEntryNameMatch / isParentMatch /
 *     isExtraInfoMatch 都直接返回 true，不需要额外条件；
 *   · 原厂已登记的应用，把 web 控件追加进它每个 Activity 的控件列表：原厂是「命中第一条即返回」，
 *     只追加在末尾救不了「原厂写了这个 Activity、却没写 WebView」的页面（浏览器就是这种）；
 *   · 系统按 getSimpleName() 精确匹配，所以名单只能覆盖已知类名。混淆后的单字母类名
 *     （如 VIA 的 t4.b）放在最后兜底。
 *
 * 两道硬限制（都是应用进程内的判定，system_server 改不了）：
 *   · Compose / 自绘渲染的页面没有标准控件，按类名匹配不到，取不到内容；
 *   · 图片必须能从 ImageView 的 drawable 取到，取不到会被静默丢弃。
 *
 * 只改「能不能拖」，不参与「拖给谁」：投递目标仍由系统自己的规则决定。
 * 游戏模式下不补配置；横屏不做限制。
 */
internal class FreeDragHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val environment: ModuleEnvironmentState,
) {
    private var packageClass: Class<*>? = null
    private var activityClass: Class<*>? = null
    private var viewClass: Class<*>? = null
    private var genericViews: List<Any> = emptyList()
    private var webViews: List<Any> = emptyList()

    fun install(classLoader: ClassLoader) {
        val helper = runCatching { classLoader.loadClass(HELPER_CLASS) }.getOrNull()
        if (helper == null) {
            ModuleLog.warn("FREE_DRAG_NO_HELPER")
            return
        }
        packageClass = loadClass(classLoader, PACKAGE_CLASS)
        activityClass = loadClass(classLoader, ACTIVITY_CLASS)
        viewClass = loadClass(classLoader, VIEW_CLASS)
        if (packageClass == null || activityClass == null || viewClass == null) {
            ModuleLog.warn("FREE_DRAG_NO_BEAN")
            return
        }
        genericViews = buildGenericViews()
        // 原厂条目里补充的即此类配置：网页控件（浏览器文章页缺的正是它）。
        webViews = buildGenericViews(GENERIC_VIEWS.filter { it.second == "web" })
        if (genericViews.isEmpty()) {
            ModuleLog.warn("FREE_DRAG_NO_VIEW")
            return
        }
        val installed = hookConfigProvider(helper) + hookRawXmlProvider(helper)
        if (installed == 0) {
            ModuleLog.warn("FREE_DRAG_NO_METHOD")
        } else {
            // 唯一一条常开日志：hook 注入成功。详细日志关闭时其余诊断全部静默，
            // 这条走框架日志出口（不经 ModuleLog 的开关），用于确认注入发生过。
            ModuleLog.install("FREE_DRAG_INSTALLED_" + installed)
        }
    }

    /** 配置读取入口：未登记的包补一份通用白名单。 */
    private fun hookConfigProvider(helper: Class<*>): Int {
        val method = findMethod(helper, "getPackageDragConfig", String::class.java) ?: return 0
        return runCatching {
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.freedrag.config")
                .intercept { chain ->
                    val original = chain.proceed()
                    if (!configuration.snapshot.freeDragActive) return@intercept original
                    // 游戏模式下一律不补配置：游戏里的长按更值钱，横屏不算。
                    if (environment.inGameMode) return@intercept original
                    val packageName = chain.getArg(0) as? String
                    if (!isRewritable(packageName)) return@intercept original
                    runCatching { withGenericEntry(packageName as String, original) }
                        .getOrElse { exception ->
                            // 逐次调用的诊断，只在详细日志开启时输出，避免刷屏。
                            ModuleLog.debug(TAG, exception) { "FREE_DRAG_BUILD_FAILED" }
                            original
                        }
                }
            1
        }.getOrElse { exception ->
            ModuleLog.warn("FREE_DRAG_CONFIG_HOOK_FAILED", exception)
            0
        }
    }

    /**
     * 原始 XML：只负责让应用侧的 init 不要提前 return。
     *
     * 系统只有在拿到原始 XML 时才会初始化拖拽阴影的尺寸（OplusViewDragShadowBuilder.init），
     * 否则阴影静态尺寸为 0。内容与补进去的通用条目保持一致，避免两套口径。
     */
    private fun hookRawXmlProvider(helper: Class<*>): Int {
        val method = findMethod(helper, "getPackageRawConfigXml", String::class.java) ?: return 0
        return runCatching {
            method.isAccessible = true
            module
                .hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.freedrag.rawxml")
                .intercept { chain ->
                    val original = chain.proceed() as? String
                    if (!original.isNullOrEmpty()) return@intercept original
                    if (!configuration.snapshot.freeDragActive) return@intercept original
                    if (environment.inGameMode) return@intercept original
                    val packageName = chain.getArg(0) as? String
                    if (!isRewritable(packageName)) return@intercept original
                    buildGenericXml(packageName as String)
                }
            1
        }.getOrElse { exception ->
            ModuleLog.warn("FREE_DRAG_RAWXML_HOOK_FAILED", exception)
            0
        }
    }

    private fun isRewritable(packageName: String?): Boolean =
        !packageName.isNullOrEmpty() && packageName !in EXCLUDED_PACKAGES

    /**
     * 在原有白名单末尾追加通用条目。
     *
     * 应用侧 findViewDragList 命中第一条即返回，所以追加在末尾等于「原厂精确规则优先，
     * 没覆盖到的页面才走通用规则」，不会影响已经按控件调优过的那些应用。
     */
    private fun withGenericEntry(packageName: String, original: Any?): Any? {
        val packageType = packageClass ?: return original
        val activityType = activityClass ?: return original

        val merged = packageType.getDeclaredConstructor().newInstance()
        packageType.getMethod("setPackageName", String::class.java).invoke(merged, packageName)

        val originalWhite =
            original?.let {
                runCatching { it.javaClass.getMethod("getWhiteList").invoke(it) as? List<*> }
                    .getOrNull()
            }
        // 原厂已经把这个包登记得很全（微信这类大包有 30 多个 Activity）时不再追加：
        // 追加是「每个 Activity 补一份 web 控件 + 末尾再挂一份通用条目」，条目数按
        // Activity 数线性膨胀，而 bind 载荷是跟着 ApplicationInfo 一起走的。实测有用户
        // 的微信在 bindApplication 阶段反复被杀（error during bind），载荷 1.44MB，
        // 这类大包原厂规则本身就够用，我们没有理由再去撑它。
        if (originalWhite != null && originalWhite.size > MAX_APPEND_ACTIVITIES) {
            if (VerboseLog.enabled) {
                ModuleLog.debug(TAG) {
                    "FREE_DRAG_SKIP_LARGE_" + packageName + "_base" + originalWhite.size
                }
            }
            return original
        }
        val whiteList = ArrayList<Any?>()
        if (originalWhite != null) {
            // 原厂条目光克隆再补 web 控件：findViewDragList 命中第一条就返回，
            // 只靠末尾的通用条目救不了「原厂已登记但没写 WebView」的页面
            // （浏览器为典型场景：文章页的 WebView 被原厂 Activity 规则挡住）。
            for (entry in originalWhite) whiteList.add(withWebViews(entry, activityType))
        }
        whiteList.add(buildGenericActivity(packageName, activityType))
        packageType.getMethod("setWhiteList", List::class.java).invoke(merged, whiteList)

        if (original != null) {
            runCatching {
                val black = original.javaClass.getMethod("getBlackList").invoke(original) as? List<*>
                if (black != null) {
                    packageType.getMethod("setBlackList", List::class.java)
                        .invoke(merged, ArrayList<Any?>(black))
                }
                val global = original.javaClass.getMethod("getConfigDragInfo").invoke(original)
                if (global != null) {
                    packageType.getMethod("setConfigDragInfo", global.javaClass)
                        .invoke(merged, global)
                }
            }
        }
        if (VerboseLog.enabled) {
            ModuleLog.debug(TAG) {
                "FREE_DRAG_CONFIG_" + packageName + "_base" + (originalWhite?.size ?: 0) +
                    "_generic" + genericViews.size +
                    "_entries" + (whiteList.size + (originalWhite?.size ?: 0) * webViews.size)
            }
        }
        return merged
    }

    /**
     * 克隆原厂的 Activity 条目，并在其控件列表末尾补上 web 控件。
     *
     * 只补 web 这一类：原厂条目的属性（parent / brother / type / source）保持原样，
     * 追加项排在最后，已有规则优先级不变，只覆盖它没登记的网页控件。
     */
    private fun withWebViews(entry: Any?, activityType: Class<*>): Any? {
        if (entry == null) return null
        val existing =
            runCatching { entry.javaClass.getMethod("getViewDragInfoList").invoke(entry) as? List<*> }
                .getOrNull() ?: return entry
        val merged = ArrayList<Any?>(existing)
        merged.addAll(webViews)
        val clone = runCatching {
            activityType.getDeclaredConstructor().newInstance().also { copy ->
                val name = entry.javaClass.getMethod("getActivityName").invoke(entry) as? String
                activityType.getMethod("setActivityName", String::class.java).invoke(copy, name)
                activityType.getMethod("setViewDragInfoList", List::class.java).invoke(copy, merged)
            }
        }.getOrNull() ?: return entry
        return clone
    }

    private fun buildGenericActivity(packageName: String, activityType: Class<*>): Any {
        val activity = activityType.getDeclaredConstructor().newInstance()
        // 用包名而不是空串：应用侧是 activityName 是否出现在窗口标题里（WindowManager 的标题
        // 形如 "包名/Activity"），包名一定能命中，且非空字符串不会在解析/传参时退化成 null。
        // 早先写过空串，一旦配置走 XML 重解析那条路，空属性会被解析成 null，
        // findViewDragList 里 title.contains(null) 直接抛 NPE，整个应用在触摸时崩溃。
        activityType.getMethod("setActivityName", String::class.java).invoke(activity, packageName)
        activityType.getMethod("setViewDragInfoList", List::class.java)
            .invoke(activity, ArrayList<Any?>(genericViews))
        return activity
    }

    /** 按 (类名, 类型) 批量构造 ViewDragInfo；[source] 用于只取其中 web 一类。 */
    private fun buildGenericViews(
        source: List<Pair<String, String>> = GENERIC_VIEWS,
    ): List<Any> {
        val viewType = viewClass ?: return emptyList()
        val constructor = runCatching { viewType.getDeclaredConstructor() }.getOrNull() ?: return emptyList()
        val nameSetter =
            runCatching { viewType.getMethod("setViewName", String::class.java) }.getOrNull()
                ?: return emptyList()
        val typeSetter =
            runCatching { viewType.getMethod("setType", String::class.java) }.getOrNull()
                ?: return emptyList()
        return source.mapNotNull { (name, type) ->
            runCatching {
                constructor.newInstance().also {
                    nameSetter.invoke(it, name)
                    typeSetter.invoke(it, type)
                }
            }.getOrNull()
        }
    }

    /**
     * 合成原始 XML：**只用来让应用侧的 init 跑起来**，不参与匹配。
     *
     * preBindApplication 里有一道分界：原始 XML 长度 ≥ 0x2000 会被压成 gz 下发，
     * 应用侧走「解压 + 重新解析」那条路，用解析结果**覆盖**我们传过去的配置对象；
     * 小于 0x2000 则只当作字符串存着，配置对象是唯一的匹配依据。
     *
     * 所以这里刻意不把控件表写进 XML：写进去必然超过 8KB，等于把匹配交给解析器
     * （空属性 → null，之前就是这么崩的）。保持短小，配置对象说了算。
     */
    private fun buildGenericXml(packageName: String): String = buildString {
        append("<packageConfig><whiteList><package name=\"")
        append(packageName)
        append("\"><activity name=\"")
        append(packageName)
        append("\"/></package></whiteList></packageConfig>")
    }

    private fun loadClass(loader: ClassLoader, name: String): Class<*>? =
        runCatching { loader.loadClass(name) }.getOrNull()
            ?: runCatching { Class.forName(name, false, loader) }.getOrNull()

    private fun findMethod(
        target: Class<*>,
        name: String,
        vararg parameters: Class<*>,
    ): Method? =
        runCatching { target.getDeclaredMethod(name, *parameters) }.getOrNull()
            ?: runCatching { target.getMethod(name, *parameters) }.getOrNull()

    private companion object {
        const val TAG = "FreeDrag"
        const val HELPER_CLASS = "com.android.server.viewdrag.OplusViewDragRusHelper"
        const val PACKAGE_CLASS = "android.view.viewdrag.bean.PackageDragConfigInfo"
        const val ACTIVITY_CLASS = "android.view.viewdrag.bean.ActivityDragInfo"
        const val VIEW_CLASS = "android.view.viewdrag.bean.ViewDragInfo"

        /**
         * 原厂 Activity 条目超过这个数就不再追加。
         *
         * 我们的追加是「每个 Activity 补一份 web 控件表 + 末尾再挂一份通用条目」，
         * 条目数随 Activity 数线性膨胀；这些条目会跟着 ApplicationInfo 进 binder，
         * 超过 1MB 时应用会绑定失败（实测有用户的微信因此起不来）。原厂登记了
         * 这么多 Activity 的包，本身覆盖已经很全，兜底收益远小于风险。
         */
        const val MAX_APPEND_ACTIVITIES = 8

        /**
         * 不参与自由拖拽的包。
         *
         * 桌面和系统界面本身就靠长按做排序/编辑，桌面图标又大量是 ImageView/TextView，
         * 放进来会把系统自己的长按手势顶掉；设置页同理，长按条目容易被误触。
         */
        val EXCLUDED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.launcher",
            "com.android.launcher3",
            "com.oplus.launcher",
        )

        /** 混淆后常见的单字母类名，作为兜底条目使用，见 [GENERIC_VIEWS]。 */
        val OBFUSCATED_WEB_NAMES: List<String> =
            (('a'..'z') + ('A'..'Z')).map(Char::toString)

        /**
         * 通用控件表（类名 → 拖拽类型）。
         *
         * 系统用 getSimpleName() 精确匹配，列父类没有用，只能一个类名一条。
         * 组成：原厂白名单里出现过的第三方控件名（去重、按频次降序）
         *     + 常见图片库类名
         *     + 各家浏览器内核的 WebView 子类名
         *     + 末尾兜底的单字母类名（混淆后的 WebView 子类，如 VIA 的 t4.b）
         * 常用的排前面：匹配从前往后遍历，命中即返回。
         */
        val GENERIC_VIEWS = listOf(
            "AppCompatTextView" to "text",
            "TextView" to "text",
            "ImageView" to "image",
            "AppCompatImageView" to "image",
            "SimpleDraweeView" to "image",
            "CircleImageView" to "image",
            "RoundedImageView" to "image",
            "RoundImageView" to "image",
            "CoolEllipsizeTextView" to "text",
            "AutoLinkTextView" to "text",
            "AndroidComposeView" to "compose",
            // 网页控件：标准名 + 各家内核/混淆后的常见子类名。
            // 夸克 = UC 内核（WebViewAndroid / BrowserWebViewEx），
            // 系统浏览器 = heytap 内核（com.heytap.webview.kernel.WebView 及其外部子类）。
            "WebView" to "web",
            "X5WebView" to "web",
            "WebViewAndroid" to "web",
            "BrowserWebViewEx" to "web",
            "BaseWebView" to "web",
            "NestedScrollWebView" to "web",
            "ArticleWebView" to "web",
            "SafeWebView" to "web",
            "SystemWebView" to "web",
            "InnerSystemWebView" to "web",
            "KKWebView" to "web",
            "MCWebView" to "web",
            "PooledWebView" to "web",
            "CheckWebView" to "web",
            "ClientWebView" to "web",
            "ReactTextView" to "text",
            "RemoteImageView" to "image",
            "CommentTextView" to "text",
            "PhotoView" to "image",
            "ReactImageView" to "image",
            "DXNativeTextView" to "text",
            "TTRichTextView" to "text",
            "AliUrlImageView" to "image",
            "NightModeAsyncImageView" to "image",
            "KwaiImageView" to "image",
            "ScaleTextView" to "text",
            "ExpandableTextView" to "text",
            "SubsamplingScaleImageView" to "image",
            "CoverImageView" to "image",
            "EmojiTextView" to "text",
            "TouchImageView" to "image",
            "PhotoDraweeView" to "image",
            "XYImageView" to "image",
            "BIUITextView" to "text",
            "MMNeat7extView" to "text",
            "MultiTouchImageView" to "image",
            "PreLayoutTextView" to "text",
            "AsyncImageView" to "image",
            "AssembleScaleView" to "image",
            "TUrlImageView" to "image",
            "CommentThumbImageView" to "image",
            "BPTextView" to "text",
            "MMTextView" to "text",
            "SmartImageView" to "image",
            "RCTRoundImageView" to "image",
            "MGSimpleDraweeView" to "image",
            "CommentKwaiImageView" to "image",
            "DXNativeFastText" to "text",
            "DXNativeRichText" to "text",
            "MTextView" to "text",
            "WubaDraweeView" to "image",
            "VipImageView" to "image",
            "PinchImageView" to "image",
            "BiliImageView" to "image",
            "HImageView" to "image",
            "WatermarkImageView" to "image",
            "PicassoImageView" to "image",
            "ShapeableImageView" to "image",
            "DYImageView" to "image",
            "DuImageLoaderView" to "image",
            "BPImageView" to "image",
            "RichTextView" to "text",
            "SingleLineTextView" to "text",
            "PatchedTextView" to "text",
            "ZoomImageView" to "image",
            "FontTextView" to "text",
            "FeedTitleTextView" to "text",
            "UgcPlayableView" to "image",
            "PicassoTextView" to "text",
            "RoundRectImageView" to "image",
            "CustomTextView" to "text",
            "AHPictureView" to "image",
            "FixedImageView" to "image",
            "MaxLinedLayoutTextView" to "text",
            "WeImageView" to "image",
            "MaskImageView" to "image",
            "RichTextSpanInterceptNoTextView" to "text",
            "FrescoImageView" to "image",
            "TTObservedImageView" to "image",
            "ZHDraweeView" to "image",
            "AIOMsgTextView" to "text",
            "QiyiDraweeView" to "image",
            "RoundCornerImageView" to "image",
            "EqualRatioImageView" to "image",
            "IgImageView" to "image",
            "ChattingEmojiView" to "image",
            "MMRoundCornerImageView" to "image",
            "ScrollMentionTextView" to "text",
            "RatioImageView" to "image",
            "CooTouchImageView" to "image",
            "LargeZoomImageView" to "image",
            "FeedDraweeView" to "image",
            "BdSpanTouchFixTextView" to "text",
            "BookCoverView" to "image",
            "QZoneRichTextView" to "text",
            "ImageFilterView" to "image",
            "HippyTextView" to "text",
            "MediumBoldTextView" to "text",
            "HYRNImageView" to "image",
            "SizedTextView" to "text",
            "ExpandTextView" to "text",
            "EllipsizeAutoLinkTextView" to "text",
            "ProductImageLoaderView" to "image",
            "XCircleImageView" to "image",
            "IdenticonImageView" to "image",
            "MBlogTextView" to "text",
            "ThemeBiliImageView" to "image",
            "ImproveTextView" to "text",
            "SquareImageView" to "image",
            "ReverseRemoteImageView" to "image",
            "TransitionPhotoDraweeView" to "image",
            "MentionTextView" to "text",
            "ECDraweeView" to "image",
            "RecRatioRoundedImageView" to "image",
            "RecRatioImageView" to "image",
            "HandlePressStateCommentTextView" to "text",
            "DragPhotoView" to "image",
            "EmojiconTextView" to "text",
            "ZHTextView" to "text",
            "HugePhotoDraweeView" to "image",
            "EleMistImageView" to "image",
            "MPTextView" to "text",
            "TbListTextView" to "text",
            "MucangImageView" to "image",
            "TextWidget" to "text",
            "RoundBubbleImageView" to "image",
            "ETTextView" to "text",
            "QCircleAsyncTextView" to "text",
            "ArkSoftwareView" to "text",
            "TXImageView" to "image",
            "YKIconFontTextView" to "text",
            "EllipsizeTextView" to "text",
            "MJImageView" to "image",
            "KKTextView" to "text",
            "KKImageView" to "image",
            "NeteaseMusicSimpleDraweeView" to "image",
            "CustomThemeTextView" to "text",
            "SinaTextView" to "text",
            "CropStartImageView" to "image",
            "RecommendDesTextView" to "text",
            "AHScaleImageView" to "image",
            "BoldTextView" to "text",
            "RoundCornerAndAspectImageView" to "image",
            "LargeImageCropView" to "image",
            "SpannedTextView" to "text",
            "THDesignTextView" to "text",
            "FastImageViewWithUrl" to "image",
            "MarkReadTextView" to "text",
            "GalleryListRecyclingImageView" to "image",
            "ExcludeFontPaddingTextView" to "text",
            "BIUIImageView" to "image",
            "ImoImageView" to "image",
            "ResizeImageView" to "image",
            "BigImageView" to "image",
            "MBlogDetailTextView" to "text",
            "CommentPicView" to "image",
            "PolygonImageView" to "image",
            "SplitDraggableImageView" to "image",
            "NextExperiment3ExpandableTextView" to "text",
            "TintTextView" to "text",
            "DescTextView" to "text",
            "SnsPostDescPreloadTextView" to "text",
            "SnsCommentPreloadTextView" to "text",
            "AsyncTextView" to "text",
            "SnsTextView" to "text",
            "DmtTextView" to "text",
            "ECFrescoImageView" to "image",
            "RatioRoundedImageView" to "image",
            "LegoTextView" to "text",
            "FitSubsamplingScaleImageView" to "image",
            "KwaiFixedSimpleDraweeView" to "image",
            "KwaiZoomImageView" to "image",
            "MerchantKwaiImageView" to "image",
            "CommentZoomImageView" to "image",
            "TNodeImageView" to "image",
            "StaticLayoutView" to "text",
            "RichTextViewImpl" to "text",
            "SimpleRtfTextView" to "text",
            "TNImage" to "image",
            "TNRichText" to "text",
            "XJImageView" to "image",
            "HtmlTextView" to "text",
            "SelectableTextView" to "text",
            "DynamicTextView" to "text",
            "AndroidText" to "text",
            "NoteDetailTextView" to "text",
            "GestureImageView" to "image",
            "MessageItemTextView" to "text",
            "PhotoImageView" to "image",
            "RadiusDraweeView" to "image",
            "TextViewSupportEllipsize" to "text",
            "LinkTextView" to "text",
            "DPZoomImageView" to "image",
            "MistTextView" to "text",
            "KMImageView" to "image",
            "MPImageView" to "image",
            "MyTextView" to "text",
            "TbImageView" to "image",
            "EMTextView" to "text",
            "GetCollapseTextView" to "text",
            "WorkExpTextView" to "text",
            "TopicTextView" to "text",
            "BrowserImageTextView" to "text",
            "GuildAsyncTextView" to "text",
            "AioTextView" to "text",
            "QFSExpandableAsyncTextView" to "text",
            "RFWSquareImageView" to "image",
            "RFWMatrixImageView" to "image",
            "URLImageView" to "image",
            "UVTextView" to "text",
            "LargeImageView" to "image",
            "SpanFontSizeTextView" to "text",
            "YKTextView" to "text",
            "YKRatioImageView" to "image",
            "ScrollTextView" to "text",
            "WubaScaleImageView" to "image",
            "MeasureWidthTextView" to "text",
            "DPImageView" to "image",
            "MgFrescoImageView" to "image",
            "SkinnableTextView" to "text",
            "CommentContentViewWithBubbleBackground" to "text",
            "LunaAdapterImageView" to "image",
            "GXText" to "text",
            "TextViewFixTouchConsume" to "text",
            "AspectRatioImageView" to "image",
            "WXTextView" to "text",
            "CommentAttachmentView" to "image",
            "SinaSubsamplingScaleImageView" to "image",
            "EmotionTextView" to "text",
            "NetworkImageView" to "image",
            "FolderTextView" to "text",
            "MaterialTextView" to "text",
            "AHUIRichLabel" to "text",
            "AutoAdjustImageView" to "image",
            "SpannableTextViewNew" to "text",
            "ImageViewDYEx" to "image",
            "MateImageView" to "image",
            "WRTextView" to "text",
            "FrodoPhotoView" to "image",
            "MoreActionTextView" to "text",
            "RoundBorderImageView" to "image",
            "ScaleAlignTextView" to "text",
            "AtUserEmoticonTextView" to "text",
            "CommentExpandTextView" to "text",
            "ShowImageTextView" to "text",
            "UgcVideoTitleMentionTextView" to "text",
            "SquareCommentVideoImage" to "image",
            "ZZSimpleDraweeView" to "image",
            "ExcellentDraweeView" to "image",
            "TycSVGImageView" to "image",
            "WKImageView" to "image",
            "WKTextView" to "text",
            "QDFontTextView" to "text",
            "CollapsibleTextView" to "text",
            "TCAppCompatTextView" to "text",
            "SamsSketchImageView" to "image",
            "GradientTextView" to "text",
            "CustomRoundAngleImageView" to "image",
            "FixSimpleDraweeView" to "image",
            "SSTextView" to "text",
            "BdBaseImageView" to "image",
            "EbuyRoundImageView" to "image",
            "NovelContainerImageView" to "image",
            "SnapImageView" to "image",
            "ForegroundTextView" to "text",
            "TimeTextView" to "text",
            "SafeSelectableTextView" to "text",
            "CheckedTextView" to "text",
            "ScalingImageView" to "image",
            "ScaleImageView" to "image",
            "ShapedImageView" to "image",
            "SimpleImageView" to "image",
        ) +
            // 单字母名放最后：先让有名字的规则匹配，字母只兜底。
            // 早前放在最前面，网页里的单字母容器（如夸克的 z）会抢先被认成 web，
            // 真正的 WebView 轮不到匹配，反而拖不动。
            OBFUSCATED_WEB_NAMES.map { it to "web" }
    }
}