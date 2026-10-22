package com.singflow.android.core.parser

import com.singflow.android.core.model.ProxyNode
import java.net.URLDecoder
import java.util.Locale

/**
 * 节点名称、指纹与国家预判工具。
 *
 * 所有方法均为纯函数，可在 JVM 单元测试中直接验证。
 */
object NodeParserUtils {

    /** 控制字符（Cc）与格式字符（Cf，含零宽空格/方向控制符）统一清洗。 */
    private val INVISIBLE_CHARS = Regex("[\\p{Cntrl}\\p{Cf}]")

    /** 节点名称最大展示长度。 */
    const val MAX_NAME_LENGTH = 64

    /** 一条国家名称识别规则。 */
    data class CountryMatch(val code: String, val nameCn: String)

    /** 未知地区。 */
    val UNKNOWN_COUNTRY = CountryMatch("UN", "其他地区")

    /**
     * 国家/地区识别规则，按顺序匹配，先命中者优先。
     *
     * 规则同时覆盖表情旗帜、中文城市名与常见英文/机场三字码，便于在未实测前先完成分组。
     */
    private val COUNTRY_RULES: List<CountryMatchWithRegex> = listOf(
        rule("HK", "中国香港", "(🇭🇰|香港|港区|\\bHKG\\b|\\bHK\\b|Hong\\s*Kong|HKSAR)"),
        rule("TW", "中国台湾", "(🇹🇼|台湾|台北|新北|台中|高雄|\\bTPE\\b|\\bTW\\b|Taiwan|Taipei)"),
        rule("JP", "日本", "(🇯🇵|日本|东京|大阪|埼玉|名古屋|\\bNRT\\b|\\bHND\\b|\\bKIX\\b|\\bJP\\b|Japan|Tokyo|Osaka)"),
        rule("SG", "新加坡", "(🇸🇬|新加坡|狮城|\\bSIN\\b|\\bSG\\b|Singapore)"),
        rule("US", "美国", "(🇺🇸|美国|洛杉矶|圣何塞|西雅图|纽约|硅谷|芝加哥|达拉斯|\\bLAX\\b|\\bSJC\\b|\\bSFO\\b|\\bJFK\\b|\\bUS\\b|United\\s*States|America)"),
        rule("KR", "韩国", "(🇰🇷|韩国|首尔|釜山|\\bICN\\b|\\bKR\\b|Korea|Seoul)"),
        rule("GB", "英国", "(🇬🇧|英国|伦敦|\\bLHR\\b|\\bUK\\b|\\bGB\\b|Britain|London)"),
        rule("DE", "德国", "(🇩🇪|德国|法兰克福|\\bFRA\\b|\\bDE\\b|Germany|Frankfurt)"),
        rule("FR", "法国", "(🇫🇷|法国|巴黎|\\bCDG\\b|\\bFR\\b|France|Paris)"),
        rule("NL", "荷兰", "(🇳🇱|荷兰|阿姆斯特丹|\\bAMS\\b|\\bNL\\b|Netherlands|Amsterdam)"),
        rule("CA", "加拿大", "(🇨🇦|加拿大|多伦多|温哥华|\\bYYZ\\b|\\bYVR\\b|\\bCA\\b|Canada|Toronto|Vancouver)"),
        rule("AU", "澳大利亚", "(🇦🇺|澳大利亚|澳洲|悉尼|墨尔本|\\bSYD\\b|\\bMEL\\b|\\bAU\\b|Australia|Sydney|Melbourne)"),
        rule("RU", "俄罗斯", "(🇷🇺|俄罗斯|莫斯科|\\bSVO\\b|\\bRU\\b|Russia|Moscow)"),
        rule("TR", "土耳其", "(🇹🇷|土耳其|伊斯坦布尔|\\bIST\\b|\\bTR\\b|Turkey|Istanbul)"),
        rule("IN", "印度", "(🇮🇳|印度|孟买|\\bBOM\\b|\\bIN\\b|India|Mumbai)"),
        rule("BR", "巴西", "(🇧🇷|巴西|圣保罗|\\bGRU\\b|\\bBR\\b|Brazil|Sao\\s*Paulo)"),
        rule("AE", "阿联酋", "(🇦🇪|阿联酋|迪拜|\\bDXB\\b|\\bAE\\b|Emirates|Dubai)"),
        rule("MY", "马来西亚", "(🇲🇾|马来西亚|吉隆坡|\\bKUL\\b|\\bMY\\b|Malaysia|Kuala\\s*Lumpur)"),
        rule("TH", "泰国", "(🇹🇭|泰国|曼谷|\\bBKK\\b|\\bTH\\b|Thailand|Bangkok)"),
        rule("VN", "越南", "(🇻🇳|越南|胡志明|河内|\\bSGN\\b|\\bVN\\b|Vietnam|Hanoi)"),
        rule("PH", "菲律宾", "(🇵🇭|菲律宾|马尼拉|\\bMNL\\b|\\bPH\\b|Philippines|Manila)"),
        rule("ID", "印度尼西亚", "(🇮🇩|印度尼西亚|印尼|雅加达|\\bCGK\\b|\\bID\\b|Indonesia|Jakarta)"),
        rule("CN", "中国大陆", "(🇨🇳|中国大陆|回国|上海|北京|广州|深圳|杭州|\\bCN\\b|China|Shanghai|Beijing)")
    )

    private data class CountryMatchWithRegex(
        val match: CountryMatch,
        val regex: Regex
    )

    private fun rule(code: String, nameCn: String, pattern: String): CountryMatchWithRegex {
        return CountryMatchWithRegex(CountryMatch(code, nameCn), Regex(pattern, RegexOption.IGNORE_CASE))
    }

    /**
     * 清洗节点名称。
     *
     * 处理：
     * 1. percent-encoding（含中文与 Emoji）；
     * 2. `+` 号不被误当作空格（订阅名里常见 `A+B`）；
     * 3. 去除控制字符与零宽字符；
     * 4. 超长截断，空值回退到 `host:port`。
     *
     * @param rawFragment 原始 fragment（可能已解码，也可能是百分号编码）
     * @param fallbackHost 回退主机
     * @param port 回退端口
     */
    fun cleanNodeDisplayName(rawFragment: String?, fallbackHost: String, port: Int): String {
        val fallback = "$fallbackHost:$port"
        if (rawFragment.isNullOrBlank()) return fallback
        val decoded = runCatching {
            URLDecoder.decode(rawFragment.replace("+", "%2B"), "UTF-8")
        }.getOrElse { rawFragment }
        val cleaned = INVISIBLE_CHARS.replace(decoded, "")
            .trim()
            .take(MAX_NAME_LENGTH)
        return cleaned.ifBlank { fallback }
    }

    /**
     * 按节点名称预判国家/地区。
     */
    fun guessCountryFromName(displayName: String): CountryMatch {
        if (displayName.isBlank()) return UNKNOWN_COUNTRY
        for (rule in COUNTRY_RULES) {
            if (rule.regex.containsMatchIn(displayName)) return rule.match
        }
        return UNKNOWN_COUNTRY
    }

    /**
     * 为节点补齐展示名、原始链接、连接指纹与国家预判。
     *
     * 已通过实测校准（`isRegionVerified = true`）的节点保留其国家信息，
     * 避免订阅刷新把实测结果覆盖回名称预判。
     */
    fun normalize(node: ProxyNode, rawShareUri: String? = null): ProxyNode {
        val cleanedName = cleanNodeDisplayName(node.name, node.server, node.port)
        val existingCountry = node.countryCode.trim().uppercase(Locale.US)
        val needsCountry = !node.isRegionVerified && (existingCountry.isBlank() || existingCountry == "UN")
        val guess = if (needsCountry) guessCountryFromName(cleanedName) else null
        return node.copy(
            name = cleanedName,
            rawShareUri = rawShareUri ?: node.rawShareUri,
            countryCode = guess?.code ?: node.countryCode,
            countryNameCn = guess?.nameCn ?: node.countryNameCn
        )
    }
}
