package com.singflow.android.core.probe

import com.singflow.android.core.model.CountryNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * 落地出口信息（IP + 国家/地区）。
 */
data class EgressInfo(
    val ip: String?,
    val countryCode: String?,
    val countryNameCn: String
)

/**
 * 解析 Cloudflare `cdn-cgi/trace` 与 `api.ip.sb/geoip` 响应。
 *
 * 解析器不发起网络请求、不依赖 Android，可在 JVM 单测中直接验证。
 */
object EgressTraceParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")
    private val IPV6 = Regex("^[0-9a-fA-F:]{2,}(%[0-9a-zA-Z]+)?$")

    /**
     * 解析 `https://www.cloudflare.com/cdn-cgi/trace` 的纯文本响应。
     *
     * @return 无法解析出 IP 或国家时返回 null
     */
    fun parseCloudflareTrace(text: String): EgressInfo? {
        if (text.isBlank()) return null
        var ip: String? = null
        var loc: String? = null
        text.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim().lowercase(Locale.US)
            val value = line.substring(index + 1).trim()
            when (key) {
                "ip" -> if (isIpLiteral(value)) ip = value
                "loc" -> if (value.length == 2 && value.all { it in 'A'..'Z' }) loc = value
            }
        }
        if (ip == null && loc == null) return null
        val code = loc?.uppercase(Locale.US)
        return EgressInfo(
            ip = ip,
            countryCode = code,
            countryNameCn = CountryNames.nameOf(code)
        )
    }

    /**
     * 解析 `https://api.ip.sb/geoip` 的 JSON 响应。
     */
    fun parseGeoIpJson(raw: String): EgressInfo? {
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val ip = obj.stringOrNull("ip")?.takeIf { isIpLiteral(it) }
        val code = (obj.stringOrNull("country_code") ?: obj.stringOrNull("countryCode"))
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
        if (ip == null && code == null) return null
        return EgressInfo(
            ip = ip,
            countryCode = code,
            countryNameCn = CountryNames.nameOf(code)
        )
    }

    /**
     * 判断字符串是否为 IPv4/IPv6 字面量（不触发 DNS 解析）。
     */
    fun isIpLiteral(value: String): Boolean {
        val trimmed = value.trim()
        if (IPV4.matches(trimmed)) {
            return trimmed.split('.').all { part ->
                val number = part.toIntOrNull() ?: return false
                number in 0..255
            }
        }
        return trimmed.count { it == ':' } >= 2 && IPV6.matches(trimmed)
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
