package io.github.hoerire.free.bubble

import java.util.Locale
import kotlin.math.abs

/**
 * Pure text classifier for clipboard suggestions.
 *
 * Shared by the injected SystemUI process (which builds the bubble) and by the system_server
 * clipboard hook (which is the only reliable clipboard source on Android 10+, because clipboard
 * reads are focus-gated). Keeping the classification in one place means both paths agree, and the
 * hook only ever forwards the actionable token instead of the whole clipboard text.
 */
internal object ClipboardClassifier {
    const val KIND_PHONE = 1
    const val KIND_EXPRESS = 2
    const val KIND_LINK = 3
    const val KIND_SHOPPING = 4
    const val KIND_APP_LINK = 5
    const val KIND_ADDRESS = 6
    const val KIND_COORDINATES = 7

    /** At most this many bubbles for one copied text. */
    const val MAX_RESULTS = 4

    /** Stable tag for a kind, used in suggestion ids and logs (`clipboard:phone`). */
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
        /** Normalized payload: dialable number, URL, deep link, tracking code or address. */
        val value: String,
        /** Original clipboard text, shortened, used for the bubble body. */
        val display: String,
        /** Package guessed from the host/scheme, may be null. */
        val appPackage: String?,
    )

    /** Shopping sites: always offered as "open shopping app". */
    private val SHOPPING_HOSTS = arrayOf(
        "taobao.com", "tmall.com", "tb.cn", "tmall.hk", "jd.com", "jd.hk", "3.cn",
        "pinduoduo.com", "yangkeduo.com", "suning.com", "vip.com", "kaola.com",
        "you.163.com", "mogujie.com", "dangdang.com", "smzdm.com",
    )

    /** Hosts that belong to a specific app (opened with it when it is installed). */
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

    /** Deep-link schemes mapped to their app. */
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

    // --- phone -------------------------------------------------------------------------
    private val PHONE_TEXT = Regex("(?i)^(?:tel:)?[+]?[\\d\\s()\\-—–]{5,28}$")
    private val PHONE_SEPARATORS = Regex("[\\s()\\-—–]")
    private val CN_MOBILE = Regex("^1[3-9]\\d{9}$")
    private val CN_LANDLINE = Regex("^0\\d{9,11}$")
    private val CN_LOCAL = Regex("^[2-9]\\d{6,7}$")
    private val CN_SERVICE = Regex("^(?:[48]00\\d{3,7}|\\d{5})$")
    private val INTL_PHONE = Regex("^\\+\\d{8,15}$")

    // --- links -------------------------------------------------------------------------
    private val HTTP_URL = Regex("(?i)https?://[^\\s<>\"'，。；、）】》〉」』]+")
    private val URL_TRAILING = Regex("[.,;:!?)\\]}>]+$")
    private val BARE_HOST = Regex(
        "(?i)^(?:[a-z0-9-]+\\.)+[a-z]{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?$",
    )
    private val DEEP_LINK = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://(\\S{3,})$")
    private val SCHEME_ONLY = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):.*")

    // --- express -----------------------------------------------------------------------
    private val UPU_CODE = Regex("^[A-Z]{2}\\d{9}[A-Z]{2}$")
    private val PREFIXED_EXPRESS = Regex(
        "^(?:SF|YT|YD|ZTO|STO|JT|JD|DPK|EMS|CNS|CN|LP|RB|1Z)[A-Z0-9]{6,22}$",
    )
    private val DIGIT_EXPRESS = Regex("^\\d{10,20}$")
    private val TRACKING_TOKEN = Regex("(?i)[A-Z]{0,2}\\d{9,20}[A-Z]{0,2}")
    private val EXPRESS_KEYWORDS = Regex(
        "快递|物流|包裹|运单|揽收|派送|派件|签收|驿站|菜鸟|顺丰|中通|圆通|申通|韵达|极兔|德邦|邮政|京东物流",
    )

    // --- address / coordinates ---------------------------------------------------------
    private val SENTENCE_END = Regex("[。！？!?；;]")
    private val HAS_CJK = Regex("[\\u4e00-\\u9fa5]")
    private val HAS_DIGIT = Regex("\\d")
    private val CITY_WORDS = arrayOf("省", "自治区", "特别行政区", "市", "州")
    private val DISTRICT_WORDS = arrayOf("区", "县", "镇", "街道", "乡", "开发区", "新区")
    private val STREET_WORDS = arrayOf(
        "路", "街", "道", "巷", "号", "小区", "大厦", "广场", "村", "园", "苑", "里", "弄",
        "胡同", "大道", "号楼", "座", "栋", "中心", "商场", "酒店", "公寓", "产业园", "科技园",
    )

    /** House number / building number, i.e. the part that makes a line an address. */
    private val HOUSE_NUMBER = Regex("\\d+\\s*(?:号|栋|座|楼|层|室|弄)")

    /** Leading labels map apps put before the address. */
    private val ADDRESS_LABEL = Regex(
        "(?i)^(?:详细)?(?:地址|位置|地点|导航到|目的地|收货地址|所在位置)\\s*[:：]\\s*",
    )

    /** Leading label of a phone line in a shared place card. */
    private val PHONE_LABEL = Regex(
        "(?i)^(?:联系)?(?:电话|手机|手机号|手机号码|座机|热线|tel|phone)\\s*[:：]?\\s*",
    )

    /** Metadata lines of a shared place card: they are never part of the address. */
    private val MAP_METADATA_LINE = Regex(
        "电话|营业时间|人均|评分|点评|距离|距您|公里|千米|导航|路线|公交|驾车|步行|骑行|" +
            "分享|复制|收藏|来源|打开|查看|停车场|停车场入口|出入口|楼层|营业中|休息|" +
            "地图|高德|百度地图|腾讯地图|Google Maps|Apple 地图",
    )

    /** "116.481028,39.989643" style coordinate pairs (decimal degrees). */
    private val COORDINATE_PAIR = Regex(
        "(-?\\d{1,3}\\.\\d{3,9})\\s*[,，]\\s*(-?\\d{1,3}\\.\\d{3,9})",
    )

    /** English/US style address: "1600 Amphitheatre Pkwy, Mountain View, CA 94043". */
    private val ADDRESS_EN = Regex(
        "^\\d{1,6}\\s+[^,]{2,60},\\s*[^,]{2,40}(?:,\\s*[A-Z]{2})?\\s*\\d{5}(?:-\\d{4})?$",
    )
    private val ADDRESS_EN_SUFFIX = Regex(
        "(?i)\\b(?:st|street|ave|avenue|rd|road|blvd|boulevard|dr|drive|ln|lane|way|" +
            "pkwy|parkway|ct|court|pl|place|hwy|highway|sq|square|ter|terrace)\\b",
    )

    /** Line separator used by the place-card scanners (same pattern as the Java source). */
    private val LINE_BREAK = Regex("[\\r\\n]+")

    /**
     * Every actionable item in the text, in priority order and without duplicates. A map place
     * card for example yields an address, a phone number and a share link at once, and each of
     * them deserves its own bubble.
     */
    fun classifyAll(text: String?): List<Result> {
        val results = mutableListOf<Result>()
        if (text == null) return results
        val value = javaTrim(text)
        if (value.isEmpty() || value.length > 4096) return results
        val display = shorten(value, 140)

        // Share cards mix a link with the address/phone; scan the text without URLs so those
        // items are still found next to the link.
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

        // Keep the highest priority items only, and never the same value twice.
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

    // --- phone -------------------------------------------------------------------------

    /** Dialable number, or null when the text is not a phone number. */
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

    // --- links -------------------------------------------------------------------------

    /** First http(s) URL inside the text (share texts wrap the link in promo copy). */
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

    /** A bare, non-web app link such as `taobao://item/123`. */
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

    /** Host of a URL, lowercased and stripped of scheme/port/path/query; null when empty. */
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

    // --- express -----------------------------------------------------------------------

    /** Tracking number, or null. Pure digit runs long enough to be a waybill are accepted. */
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

    // --- address -----------------------------------------------------------------------

    /**
     * Conservative Chinese address detection: needs an administrative level, a district/street
     * level and a house number, so plain sentences that merely mention 市/区 are rejected.
     *
     * Address detection tuned for what map apps actually put on the clipboard: either a single
     * "省市区街道门牌" line, or a place card with the POI name, address, phone, opening hours and
     * a share link stacked on separate lines. The best-looking line wins, metadata is ignored.
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
        // A single-line address still has to clear the same bar as one line of a place card.
        if (best == null && !value.contains("\n")) {
            if (addressScore(value) > 0) best = value
        }
        return best
    }

    /** 0 means "not an address"; higher is a more complete address. */
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
        // 省市区 + 街道/门牌, 市区 + 门牌, or 街道 + 门牌 are all plausible addresses.
        if (city && district) return if (house) 5 else 4
        if (district) return if (house) 4 else 0
        if (city) return if (house) 3 else 0
        return if (house) 2 else 0
    }

    /** Leading "地址：" style label, trailing sentence punctuation and stray quotes. */
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

    /** First phone number appearing on its own line (place cards label them "电话："). */
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
     * Coordinate pair copied from a map app, normalized to "lat,lng". Chinese map apps usually
     * copy "lng,lat", Google copies "lat,lng"; the magnitudes disambiguate the two.
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
                    // Typical mainland China bounds: the longitude is the larger-looking one.
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
     * Java `String.trim()`: only chars <= ' ' are stripped. Kotlin's trim() would also strip
     * Unicode spaces such as the ideographic space U+3000 common in Chinese share texts, which
     * would change what the phone/URL/address patterns see.
     */
    private fun javaTrim(value: String): String = value.trim { it <= ' ' }

    private fun shorten(value: String, max: Int): String {
        return if (value.length <= max) value else value.substring(0, max)
    }
}
