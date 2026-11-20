package io.github.hoerire.free.bubble

import java.util.Locale
import kotlin.math.abs

/**
 * 剪贴板建议用的纯文本分类器。
 *
 * 注入在 SystemUI 的进程（负责弹气泡）与 system_server 的剪贴板钩子共用这一份实现。
 * system_server 侧是 Android 10 以上唯一可靠的剪贴板来源——剪贴板读取受焦点限制。
 * 分类只有一处实现，两条来路结论一致；
 * 钩子也只会转发可执行的那一小段文本，而不是整段剪贴板。
 */
internal object ClipboardClassifier {
    const val KIND_PHONE = 1
    const val KIND_EXPRESS = 2
    const val KIND_LINK = 3
    const val KIND_SHOPPING = 4
    const val KIND_APP_LINK = 5
    const val KIND_ADDRESS = 6
    const val KIND_COORDINATES = 7

    /** 一次复制最多给出这么多条建议气泡。 */
    const val MAX_RESULTS = 4

    /** 建议类型的稳定标签，用于建议 id 与日志（如 `clipboard:phone`）。 */
    fun kindTag(kind: Int): String =
        when (kind) {
            KIND_PHONE -> "phone"
            KIND_EXPRESS -> "express"
            KIND_SHOPPING -> "shopping"
            KIND_LINK -> "link"
            KIND_APP_LINK -> "app_link"
            KIND_ADDRESS -> "address"
            KIND_COORDINATES -> "coordinates"
            else -> kind.toString()
        }

    data class Result(
        val kind: Int,
        /** 规整后的载荷：可拨号码、URL、app 深链、快递单号或地址。 */
        val value: String,
        /** 原始剪贴板文本（已截短），用作气泡正文。 */
        val display: String,
        /** 按域名或 scheme 猜出的包名，可能为空。 */
        val appPackage: String?,
    )

    /** 购物站点：一律按「打开购物应用」处理。 */
    private val SHOPPING_HOSTS = arrayOf(
        "taobao.com", "tmall.com", "tb.cn", "tmall.hk", "jd.com", "jd.hk", "3.cn",
        "pinduoduo.com", "yangkeduo.com", "suning.com", "vip.com", "kaola.com",
        "you.163.com", "mogujie.com", "dangdang.com", "smzdm.com",
    )

    /** 属于某个特定应用的域名：该应用已安装时用它打开。 */
    private val HOST_APPS: Map<String, String> = linkedMapOf(
        "mp.weixin.qq.com" to "com.tencent.mm",
        "weixin.qq.com" to "com.tencent.mm",
        "b23.tv" to "tv.danmaku.bili",
        "bilibili.com" to "tv.danmaku.bili",
        "v.douyin.com" to "com.ss.android.ugc.aweme",
        "douyin.com" to "com.ss.android.ugc.aweme",
        "xhslink.com" to "com.xingin.xhs",
        "xiaohongshu.com" to "com.xingin.xhs",
        "zhihu.com" to "com.zhihu.android",
        "weibo.com" to "com.sina.weibo",
        "weibo.cn" to "com.sina.weibo",
        "t.cn" to "com.sina.weibo",
        "music.163.com" to "com.netease.cloudmusic",
        "i.y.qq.com" to "com.tencent.qqmusic",
        "y.qq.com" to "com.tencent.qqmusic",

        "surl.amap.com" to "com.autonavi.minimap",
        "uri.amap.com" to "com.autonavi.minimap",
        "amap.com" to "com.autonavi.minimap",
        "ditu.amap.com" to "com.autonavi.minimap",
        "j.map.baidu.com" to "com.baidu.BaiduMap",
        "map.baidu.com" to "com.baidu.BaiduMap",
        "map.qq.com" to "com.tencent.map",
        "maps.app.goo.gl" to "com.google.android.apps.maps",
        "maps.google.com" to "com.google.android.apps.maps",
        "maps.apple.com" to "com.apple.maps",
        "maps.yandex.com" to "ru.yandex.yandexmaps",
    )

    /** 深链 scheme 与其对应应用的映射。 */
    private val SCHEME_APPS: Map<String, String?> = linkedMapOf<String, String?>(
        "androidamap" to "com.autonavi.minimap",
        "baidumap" to "com.baidu.BaiduMap",
        "qqmap" to "com.tencent.map",
        "google.navigation" to "com.google.android.apps.maps",
        "geo" to null,
        "taobao" to "com.taobao.taobao",
        "tmall" to "com.tmall.wireless",
        "weixin" to "com.tencent.mm",
        "alipays" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "bilibili" to "tv.danmaku.bili",
        "snssdk1128" to "com.ss.android.ugc.aweme",
        "xhsdiscover" to "com.xingin.xhs",
        "zhihu" to "com.zhihu.android",
        "sinaweibo" to "com.sina.weibo",
        "qqmusic" to "com.tencent.qqmusic",
        "orpheus" to "com.netease.cloudmusic",
        "mqqapi" to "com.tencent.mobileqq",
    )

    // --- 电话 -------------------------------------------------------------------------
    private val PHONE_TEXT = Regex("(?i)^(?:tel:)?[+]?[\\d\\s()\\-—–]{5,28}$")
    private val PHONE_SEPARATORS = Regex("[\\s()\\-—–]")
    private val CN_MOBILE = Regex("^1[3-9]\\d{9}$")
    private val CN_LANDLINE = Regex("^0\\d{9,11}$")
    private val CN_LOCAL = Regex("^[2-9]\\d{6,7}$")
    private val CN_SERVICE = Regex("^(?:[48]00\\d{3,7}|\\d{5})$")
    private val INTL_PHONE = Regex("^\\+\\d{8,15}$")

    // --- 链接 -------------------------------------------------------------------------
    private val HTTP_URL = Regex("(?i)https?://[^\\s<>\"'，。；、）】》〉」』]+")
    private val URL_TRAILING = Regex("[.,;:!?)\\]}>]+$")
    private val BARE_HOST = Regex(
        "(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?$",
    )
    private val DEEP_LINK = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(\\S{3,})$")
    private val SCHEME_ONLY = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):.*")

    // --- 快递 -------------------------------------------------------------------------
    private val UPU_CODE = Regex("^[A-Z]{2}\\d{9}[A-Z]{2}$")
    private val PREFIXED_EXPRESS = Regex(
        "^(?:SF|YT|YD|ZTO|STO|JT|JD|DPK|EMS|CNS|CN|LP|RB|1Z)[A-Z0-9]{6,22}$",
    )
    private val DIGIT_EXPRESS = Regex("^\\d{10,20}$")
    private val TRACKING_TOKEN = Regex("(?i)[A-Z]{0,2}\\d{9,20}[A-Z]{0,2}")
    private val EXPRESS_KEYWORDS = Regex(
        "快递|物流|包裹|运单|揽收|派送|派件|签收|驿站|菜鸟|顺丰|中通|圆通|申通|韵达|极兔|德邦|邮政|京东物流",
    )

    // --- 地址 / 坐标 ---------------------------------------------------------
    private val SENTENCE_END = Regex("[。！？!?；;]")
    private val HAS_CJK = Regex("[\\u4e00-\\u9fa5]")
    private val HAS_DIGIT = Regex("\\d")
    private val CITY_WORDS = arrayOf("省", "自治区", "特别行政区", "市", "州")
    private val DISTRICT_WORDS = arrayOf("区", "县", "镇", "街道", "乡", "开发区", "新区")
    private val STREET_WORDS = arrayOf(
        "路", "街", "道", "巷", "号", "小区", "大厦", "广场", "村", "园", "苑", "里", "弄",
        "胡同", "大道", "号楼", "座", "栋", "中心", "商场", "酒店", "公寓", "产业园", "科技园",
    )

    /** 门牌号 / 楼栋号，即让一行文本成为地址的关键部分。 */
    private val HOUSE_NUMBER = Regex("\\d+\\s*(?:号|栋|座|楼|层|室|弄)")

    /** 地图应用在地址前面加的引导词。 */
    private val ADDRESS_LABEL = Regex(
        "(?i)^(?:详细)?(?:地址|位置|地点|导航到|目的地|收货地址|所在位置)\\s*[:：]\\s*",
    )

    /** 分享地点卡片里电话行的引导词。 */
    private val PHONE_LABEL = Regex(
        "(?i)^(?:联系)?(?:电话|手机|手机号|手机号码|座机|热线|tel|phone)\\s*[:：]?\\s*",
    )

    /** 分享地点卡片的元数据行：永远不属于地址。 */
    private val MAP_METADATA_LINE = Regex(
        "电话|营业时间|人均|评分|点评|距离|距您|公里|千米|导航|路线|公交|驾车|步行|骑行|" +
            "分享|复制|收藏|来源|打开|查看|停车场|停车场入口|出入口|楼层|营业中|休息|" +
            "地图|高德|百度地图|腾讯地图|Google Maps|Apple 地图",
    )

    /** 「116.481028,39.989643」这类坐标对（十进制度）。 */
    private val COORDINATE_PAIR = Regex(
        "(-?\\d{1,3}\\.\\d{3,9})\\s*[,，]\\s*(-?\\d{1,3}\\.\\d{3,9})",
    )

    /** 英文地址样式：「1600 Amphitheatre Pkwy, Mountain View, CA 94043」。 */
    private val ADDRESS_EN = Regex(
        "^\\d{1,6}\\s+[^,]{2,60},\\s*[^,]{2,40}(?:,\\s*[A-Z]{2})?\\s*\\d{5}(?:-\\d{4})?$",
    )
    private val ADDRESS_EN_SUFFIX = Regex(
        "(?i)\\b(?:st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|way|" +
            "pkwy|parkway|ct|court|pl|place|hwy|highway|sq|square|ter|terrace)\\b",
    )

    /** 地点卡片扫描用的行分隔符（与上游 Java 实现同一套规则）。 */
    private val LINE_BREAK = Regex("[\\r\\n]+")

    /**
     * 文本里所有可执行条目，按优先级排序且不重复。
     * 例如一张地点卡片会同时给出地址、电话与分享链接，
     * 每一条都应各自成为一颗气泡。
     */
    fun classifyAll(text: String?): List<Result> {
        val results = mutableListOf<Result>()
        if (text == null) return results
        val value = javaTrim(text)
        if (value.isEmpty() || value.length > 4096) return results
        val display = shorten(value, 140)

        // 分享卡片把链接与地址/电话混在一起：先去掉 URL 再扫一遍，
        // 这样链接旁边的条目也不会漏。
        val plain = HTTP_URL.replace(value, " ")
        var phone = phoneNumber(value)
        if (phone == null) phone = phoneInLines(plain)
        if (phone != null) results.add(Result(KIND_PHONE, phone, display, null))

        val coords = coordinates(plain)
        if (coords != null) {
            results.add(Result(KIND_COORDINATES, coords, display, null))
        }

        val url = firstUrl(value)
        if (url != null) {
            val shopping = isShoppingUrl(url)
            results.add(
                Result(
                    if (shopping) KIND_SHOPPING else KIND_LINK,
                    url,
                    display,
                    appPackageForUrl(url),
                ),
            )
        } else {
            val deep = deepLink(value)
            if (deep != null) {
                results.add(
                    Result(KIND_APP_LINK, deep, display, appPackageForScheme(deep)),
                )
            }
        }

        val express = expressNumber(plain)
        if (express != null) results.add(Result(KIND_EXPRESS, express, display, null))

        val addr = address(plain)
        if (addr != null) results.add(Result(KIND_ADDRESS, addr, display, null))

        // 只保留优先级最高的若干条，且同一个值不重复。
        val unique = mutableListOf<Result>()
        for (candidate in results) {
            var duplicate = false
            for (kept in unique) {
                if (kept.value == candidate.value) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) unique.add(candidate)
            if (unique.size >= MAX_RESULTS) break
        }
        return unique
    }

    // --- 电话 -------------------------------------------------------------------------

    /** 可拨号码；不是电话号码时返回 null。 */
    private fun phoneNumber(text: String?): String? {
        if (text == null) return null
        val value = javaTrim(text)
        if (value.length < 5 || value.length > 40) return null
        if (!PHONE_TEXT.matches(value)) return null
        var digits = PHONE_SEPARATORS.replace(value, "")
        if (digits.regionMatches(0, "tel:", 0, 4, ignoreCase = true)) digits = digits.substring(4)
        if (digits.startsWith("0086")) digits = "+86" + digits.substring(4)
        if (digits.startsWith("+")) {
            return if (INTL_PHONE.matches(digits)) digits else null
        }
        if (CN_MOBILE.matches(digits) ||
            CN_LANDLINE.matches(digits) ||
            CN_LOCAL.matches(digits) ||
            CN_SERVICE.matches(digits)
        ) {
            return digits
        }
        return null
    }

    // --- 链接 -------------------------------------------------------------------------

    /** 文本里的第一个 http(s) URL（分享文案会把链接裹在推广语里）。 */
    private fun firstUrl(text: String?): String? {
        if (text == null) return null
        val value = javaTrim(text)
        val found = HTTP_URL.find(value)
        if (found != null) return stripTrailingUrlPunctuation(found.value)
        if (BARE_HOST.matches(value)) {
            return "https://" + stripTrailingUrlPunctuation(value)
        }
        return null
    }

    private fun isShoppingUrl(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        for (shopping in SHOPPING_HOSTS) {
            if (host == shopping || host.endsWith(".$shopping")) return true
        }
        return false
    }

    private fun appPackageForUrl(url: String?): String? {
        val host = hostOf(url) ?: return null
        for ((key, value) in HOST_APPS) {
            if (host == key || host.endsWith(".$key")) return value
        }
        return null
    }

    /** 非 http 的 app 链接，例如 `taobao://item/123`。 */
    private fun deepLink(text: String?): String? {
        if (text == null) return null
        val value = javaTrim(text)
        val match = DEEP_LINK.matchEntire(value) ?: return null
        val scheme = match.groupValues[1].lowercase(Locale.ROOT)
        if (scheme == "http" || scheme == "https") return null
        return value
    }

    private fun appPackageForScheme(uri: String?): String? {
        if (uri == null) return null
        val match = SCHEME_ONLY.matchEntire(javaTrim(uri)) ?: return null
        return SCHEME_APPS[match.groupValues[1].lowercase(Locale.ROOT)]
    }

    /** URL 的域名：转小写并去掉 scheme、端口、路径与查询串；为空时返回 null。 */
    internal fun hostOf(url: String?): String? {
        if (url == null) return null
        var value = javaTrim(url)
        val scheme = value.indexOf("://")
        if (scheme >= 0) value = value.substring(scheme + 3)
        val slash = value.indexOf('/')
        if (slash >= 0) value = value.substring(0, slash)
        val query = value.indexOf('?')
        if (query >= 0) value = value.substring(0, query)
        val fragment = value.indexOf('#')
        if (fragment >= 0) value = value.substring(0, fragment)
        val at = value.indexOf('@')
        if (at >= 0) value = value.substring(at + 1)
        val port = value.indexOf(':')
        if (port >= 0) value = value.substring(0, port)
        value = value.lowercase(Locale.ROOT)
        return if (value.isEmpty()) null else value
    }

    private fun stripTrailingUrlPunctuation(url: String): String {
        var value = URL_TRAILING.replace(url, "")
        while (value.endsWith(")") && count(value, '(') < count(value, ')')) {
            value = value.substring(0, value.length - 1)
        }
        return value
    }

    private fun count(value: String, target: Char): Int {
        var total = 0
        for (element in value) {
            if (element == target) total++
        }
        return total
    }

    // --- 快递 -----------------------------------------------------------------------

    /** 快递单号；为 null 表示没有。纯数字且长度够的按运单号接受。 */
    private fun expressNumber(text: String?): String? {
        if (text == null) return null
        val value = javaTrim(text)
        val compact = value.replace(Regex("[\\s\\-]"), "").uppercase(Locale.ROOT)
        if (UPU_CODE.matches(compact) ||
            PREFIXED_EXPRESS.matches(compact) ||
            DIGIT_EXPRESS.matches(compact)
        ) {
            return compact
        }
        if (EXPRESS_KEYWORDS.containsMatchIn(value)) {
            for (match in TRACKING_TOKEN.findAll(compact)) {
                val token = match.value
                if (token.length >= 10 && token.length <= 24) return token
            }
        }
        return null
    }

    // --- 地址 -----------------------------------------------------------------------

    /**
     * 保守的中文地址识别：必须有行政区划、区/街道与门牌号才算地址，
     * 只是提到「市/区」的普通句子一律不算。
     *
     * 判定按地图应用实际复制到剪贴板的内容调校：要么是一行完整地址
     *（如「省市区街道门牌」），要么是分享链接下面的分行结构（地点名、地址、电话、营业时间）。
     * 取最像地址的那一行，元数据行直接忽略。
     */
    private fun address(text: String?): String? {
        if (text == null) return null
        val value = stripLabels(javaTrim(text))
        if (value.length < 6) return null

        var best: String? = null
        var bestScore = 0
        for (rawLine in LINE_BREAK.split(value)) {
            val line = stripLabels(javaTrim(rawLine))
            if (line.length < 6 || line.length > 120) continue
            val score = addressScore(line)
            if (score > bestScore) {
                bestScore = score
                best = line
            }
        }
        // 单行地址也要过与地点卡片单行相同的判定门槛。
        if (best == null && !value.contains("\n")) {
            if (addressScore(value) > 0) best = value
        }
        return best
    }

    /** 0 表示不是地址；分值越高地址越完整。 */
    private fun addressScore(line: String): Int {
        if (line.length < 6 || line.length > 120) return 0
        if (!HAS_CJK.containsMatchIn(line) && !ADDRESS_EN.matches(line)) return 0
        if (SENTENCE_END.containsMatchIn(line)) return 0
        if (firstUrl(line) != null || phoneNumber(line) != null) return 0

        if (ADDRESS_EN.matches(line) && ADDRESS_EN_SUFFIX.containsMatchIn(line)) {
            return 6
        }
        if (!HAS_CJK.containsMatchIn(line)) return 0

        val city = containsAny(line, CITY_WORDS)
        val district = containsAny(line, DISTRICT_WORDS)
        val street = containsAny(line, STREET_WORDS)
        val house = HOUSE_NUMBER.containsMatchIn(line) || HAS_DIGIT.containsMatchIn(line)
        if (MAP_METADATA_LINE.containsMatchIn(line)) return 0
        if (!street) return 0
        // 省市区 + 街道/门牌、市区 + 门牌、街道 + 门牌，都算合理的地址。
        if (city && district) return if (house) 5 else 4
        if (district) return if (house) 4 else 0
        if (city) return if (house) 3 else 0
        return if (house) 2 else 0
    }

    /** 去掉「地址：」这类前置标签、句末标点与多余的引号。 */
    private fun stripLabels(text: String): String {
        var value = text
        for (i in 0 until 3) {
            val next = ADDRESS_LABEL.replaceFirst(value, "")
            if (next == value) break
            value = next
        }
        value = value.replace(Regex("^[\\s:：,，、。.]+"), "")
        value = value.replace(Regex("[\\s,，、;；]+$"), "")
        return javaTrim(value)
    }

    /** 独立成行的第一个电话号码（地点卡片会写成「电话：xxx」）。 */
    private fun phoneInLines(text: String?): String? {
        if (text == null || !text.contains("\n")) return null
        for (rawLine in LINE_BREAK.split(text)) {
            val line = javaTrim(PHONE_LABEL.replaceFirst(javaTrim(rawLine), ""))
            val number = phoneNumber(line)
            if (number != null) return number
        }
        return null
    }

    /**
     * 地图应用复制出来的坐标对，规整为「纬度,经度」。国内地图应用通常
     * 复制「经度,纬度」，Google 复制「纬度,经度」；按数值量级区分这两种。
     */
    private fun coordinates(text: String?): String? {
        if (text == null) return null
        val value = javaTrim(text)
        if (value.isEmpty() || value.length > 200) return null
        if (firstUrl(value) != null) return null
        for (match in COORDINATE_PAIR.findAll(value)) {
            val groups = match.groupValues
            var normalized: String? = null
            try {
                val first = groups[1].toDouble()
                val second = groups[2].toDouble()
                var lat: Double
                var lng: Double
                if (abs(first) > 90 && abs(second) <= 90) {
                    lng = first
                    lat = second
                } else if (abs(second) > 90 && abs(first) <= 90) {
                    lat = first
                    lng = second
                } else if (first > 72 && first < 136 && second > 3 && second < 56) {
                    // 中国大陆范围：经度的数值通常更大。
                    lng = first
                    lat = second
                } else {
                    lat = first
                    lng = second
                }
                if (abs(lat) <= 90 && abs(lng) <= 180) {
                    normalized = String.format(Locale.ROOT, "%.6f,%.6f", lat, lng)
                }
            } catch (ignored: Throwable) {
            }
            if (normalized != null) return normalized
        }
        return null
    }

    private fun containsAny(value: String, words: Array<String>): Boolean {
        for (word in words) {
            if (value.contains(word)) return true
        }
        return false
    }

    /**
     * Java `String.trim()` 只去掉 <= ' ' 的字符；Kotlin 的 trim() 还会去掉
     * 中文分享文本里常见的全角空格 U+3000 等 Unicode 空白，
     * 那会改变电话 / URL / 地址正则看到的内容。
     */
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    private fun shorten(value: String, max: Int): String {
        return if (value.length <= max) value else value.substring(0, max)
    }
}
