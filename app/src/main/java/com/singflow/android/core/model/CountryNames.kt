package com.singflow.android.core.model

import java.util.Locale

/**
 * ISO 3166-1 alpha-2 国家/地区代码到中文名称的映射。
 *
 * 用于落地 GeoIP 识别结果展示；未收录代码回退到「其他地区」。
 */
object CountryNames {

    /** 未知地区展示名。 */
    const val UNKNOWN_CN = "其他地区"

    private val NAMES: Map<String, String> = mapOf(
        "HK" to "中国香港",
        "MO" to "中国澳门",
        "TW" to "中国台湾",
        "CN" to "中国大陆",
        "JP" to "日本",
        "KR" to "韩国",
        "SG" to "新加坡",
        "MY" to "马来西亚",
        "TH" to "泰国",
        "VN" to "越南",
        "PH" to "菲律宾",
        "ID" to "印度尼西亚",
        "IN" to "印度",
        "US" to "美国",
        "CA" to "加拿大",
        "MX" to "墨西哥",
        "BR" to "巴西",
        "AR" to "阿根廷",
        "GB" to "英国",
        "DE" to "德国",
        "FR" to "法国",
        "NL" to "荷兰",
        "BE" to "比利时",
        "CH" to "瑞士",
        "AT" to "奥地利",
        "SE" to "瑞典",
        "NO" to "挪威",
        "FI" to "芬兰",
        "DK" to "丹麦",
        "PL" to "波兰",
        "CZ" to "捷克",
        "RU" to "俄罗斯",
        "UA" to "乌克兰",
        "TR" to "土耳其",
        "AE" to "阿联酋",
        "SA" to "沙特阿拉伯",
        "IL" to "以色列",
        "AU" to "澳大利亚",
        "NZ" to "新西兰",
        "ZA" to "南非",
        "EG" to "埃及",
        "NG" to "尼日利亚",
        "CL" to "智利",
        "CO" to "哥伦比亚",
        "PE" to "秘鲁"
    )

    /**
     * 查询中文名称。
     *
     * @param code ISO 代码（大小写不敏感）
     */
    fun nameOf(code: String?): String {
        val normalized = code?.trim()?.uppercase(Locale.US).orEmpty()
        if (normalized.isBlank() || normalized == "UN") return UNKNOWN_CN
        return NAMES[normalized] ?: UNKNOWN_CN
    }

    /** 是否是可识别的国家/地区代码。 */
    fun isKnown(code: String?): Boolean {
        val normalized = code?.trim()?.uppercase(Locale.US).orEmpty()
        return normalized in NAMES
    }

    /** 全部已知代码，按中文名排序。 */
    fun knownCodes(): List<String> = NAMES.keys.sortedBy { NAMES[it] }
}
