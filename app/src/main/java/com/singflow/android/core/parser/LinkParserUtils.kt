package com.singflow.android.core.parser

import java.net.URLDecoder
import java.util.Locale

/**
 * 一条已经拆解完成的代理链接。
 *
 * @property scheme 协议头（小写）
 * @property userInfo `@` 左侧的原始用户信息（未解码）
 * @property host 主机（IPv6 已去除方括号）
 * @property port 端口
 * @property query 已 URL 解码的查询参数（键统一小写）
 * @property fragment 已 URL 解码的备注
 */
data class ParsedLink(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val query: Map<String, String>,
    val fragment: String
) {
    /** 读取查询参数。 */
    fun param(vararg keys: String): String {
        for (key in keys) {
            val value = query[key.lowercase(Locale.US)]
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    /** 读取布尔型查询参数（1/true/yes）。 */
    fun boolParam(vararg keys: String): Boolean {
        val value = param(*keys).lowercase(Locale.US)
        return value == "1" || value == "true" || value == "yes"
    }
}

/**
 * 链接解析工具：手写拆分，避免 `java.net.URI` 对中文/Emoji 备注与 IPv6 抛出异常。
 */
object LinkParserUtils {

    /**
     * 解析 `scheme://[userinfo@]host:port[/path][?query][#fragment]`。
     *
     * @return 解析失败返回 null
     */
    fun parse(link: String): ParsedLink? {
        val trimmed = link.trim().removePrefix("\uFEFF").trim()
        val schemeIndex = trimmed.indexOf("://")
        if (schemeIndex <= 0) return null
        val scheme = trimmed.substring(0, schemeIndex).lowercase(Locale.US)
        var rest = trimmed.substring(schemeIndex + 3)

        var fragment = ""
        val hashIndex = rest.indexOf('#')
        if (hashIndex >= 0) {
            fragment = decodeStrict(rest.substring(hashIndex + 1))
            rest = rest.substring(0, hashIndex)
        }

        var queryPart = ""
        val queryIndex = rest.indexOf('?')
        if (queryIndex >= 0) {
            queryPart = rest.substring(queryIndex + 1)
            rest = rest.substring(0, queryIndex)
        }

        // 去掉可能的路径（例如 ws 传输会在 authority 后附带 /path）
        var pathPart = ""
        val slashIndex = rest.indexOf('/')
        if (slashIndex >= 0) {
            pathPart = rest.substring(slashIndex)
            rest = rest.substring(0, slashIndex)
        }

        var userInfo = ""
        val atIndex = rest.lastIndexOf('@')
        if (atIndex >= 0) {
            userInfo = rest.substring(0, atIndex)
            rest = rest.substring(atIndex + 1)
        }

        var host: String
        var port = 443
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            host = rest.substring(1, close)
            val after = rest.substring(close + 1)
            if (after.startsWith(":")) {
                port = after.substring(1).toIntOrNull() ?: return null
            }
        } else {
            val colonIndex = rest.lastIndexOf(':')
            if (colonIndex < 0) {
                host = rest
            } else {
                host = rest.substring(0, colonIndex)
                port = rest.substring(colonIndex + 1).toIntOrNull() ?: return null
            }
        }
        host = host.trim()
        if (host.isBlank()) return null

        val query = parseQuery(queryPart)
        if (pathPart.isNotBlank() && query["path"].isNullOrBlank()) {
            // 某些链接把 path 直接写在 authority 后，补充到查询参数之外由调用方处理。
            val mutable = query.toMutableMap()
            mutable["__path"] = decodeStrict(pathPart)
            return ParsedLink(scheme, userInfo, host, port, mutable, fragment)
        }
        return ParsedLink(scheme, userInfo, host, port, query, fragment)
    }

    /**
     * 解码 URI 组件，且保留 `+` 字面量（Base64 密钥中常见）。
     */
    fun decodeStrict(value: String): String {
        if (value.isEmpty()) return value
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        }.getOrElse { value }
    }

    /**
     * 解码表单风格的查询值（`+` 视为空格）。
     */
    fun decodeFormValue(value: String): String {
        if (value.isEmpty()) return value
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrElse { value }
    }

    /**
     * 解析查询字符串，键统一为小写。
     */
    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val index = pair.indexOf('=')
            if (index < 0) {
                result[pair.lowercase(Locale.US)] = ""
            } else {
                val key = pair.substring(0, index).lowercase(Locale.US)
                val value = decodeFormValue(pair.substring(index + 1))
                result[key] = value
            }
        }
        return result
    }

    /**
     * 拆分 `method:password`，只在第一个冒号处分割。
     *
     * 这是 SS-2022 多段密钥（密钥本身含 `:`）能够正确解析的关键。
     */
    fun splitFirstColon(raw: String): Pair<String, String> {
        val index = raw.indexOf(':')
        if (index < 0) return raw to ""
        return raw.substring(0, index) to raw.substring(index + 1)
    }

    /**
     * 从传输类型参数中归一化出 sing-box 支持的传输类型。
     */
    fun normalizeTransportType(raw: String): String {
        return when (raw.lowercase(Locale.US)) {
            "", "tcp", "raw", "none" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "h2", "http" -> "http"
            "httpupgrade" -> "httpupgrade"
            "quic" -> "quic"
            "xhttp", "splithttp" -> "httpupgrade"
            else -> raw.lowercase(Locale.US)
        }
    }

    /**
     * 从 ALPN 参数字符串（逗号分隔）生成列表。
     */
    fun parseAlpn(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
