package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TransportConfig
import com.singflow.android.core.parser.Base64Utils
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser

/**
 * Shadowsocks 解析器，兼容 SIP002、明文 URI 与 Legacy 全 Base64 三种格式。
 */
class ShadowsocksParser : NodeLinkParser {

    override val name: String = "Shadowsocks"
    override val schemes: List<String> = listOf("ss")

    override fun parse(link: String): ProxyNode? {
        val raw = link.trim()
        if (!canParse(raw)) return null
        var body = raw.substringAfter("://")

        var remark = ""
        val hashIndex = body.indexOf('#')
        if (hashIndex >= 0) {
            remark = LinkParserUtils.decodeStrict(body.substring(hashIndex + 1))
            body = body.substring(0, hashIndex)
        }

        var queryPart = ""
        val queryIndex = body.indexOf('?')
        if (queryIndex >= 0) {
            queryPart = body.substring(queryIndex + 1)
            body = body.substring(0, queryIndex)
        }
        val query = LinkParserUtils.parseQuery(queryPart)

        // 标准形态：userinfo@host:port
        if (body.contains('@')) {
            val userInfoRaw = body.substringBeforeLast('@')
            val hostPort = body.substringAfterLast('@')
            val credentials = decodeCredentials(userInfoRaw) ?: return null
            val (method, password) = LinkParserUtils.splitFirstColon(credentials)
            if (method.isBlank()) return null
            val (host, port) = parseHostPort(hostPort) ?: return null
            return buildNode(remark, host, port, method, password, query)
        }

        // Legacy 形态：ss://BASE64(method:password@host:port)
        val decoded = Base64Utils.safeBase64DecodeOrNull(body) ?: return null
        val decodedBody = decoded.trim()
        if (!decodedBody.contains('@')) return null
        val credentialsRaw = decodedBody.substringBeforeLast('@')
        val hostPort = decodedBody.substringAfterLast('@')
        val (method, password) = LinkParserUtils.splitFirstColon(credentialsRaw)
        if (method.isBlank()) return null
        val (host, port) = parseHostPort(hostPort) ?: return null
        return buildNode(remark, host, port, method, password, query)
    }

    /**
     * 解析用户信息：优先按 Base64 解码，失败则视为明文 `method:password`。
     */
    private fun decodeCredentials(raw: String): String? {
        val decoded = LinkParserUtils.decodeStrict(raw)
        if (decoded.contains(':')) {
            // 已经是明文（可能被 URL 编码）
            return decoded
        }
        val fromBase64 = Base64Utils.safeBase64DecodeOrNull(raw)
        if (fromBase64 != null && fromBase64.contains(':')) return fromBase64
        val decodedBase64 = Base64Utils.safeBase64DecodeOrNull(decoded)
        if (decodedBase64 != null && decodedBase64.contains(':')) return decodedBase64
        return null
    }

    /**
     * 拆分 host:port，支持 IPv6 方括号。
     */
    private fun parseHostPort(raw: String): Pair<String, Int>? {
        val value = raw.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close < 0) return null
            val host = value.substring(1, close)
            val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
            return host to port
        }
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return null
        val host = value.substring(0, colon)
        val port = value.substring(colon + 1).toIntOrNull() ?: return null
        return host to port
    }

    private fun buildNode(
        remark: String,
        host: String,
        port: Int,
        method: String,
        password: String,
        query: Map<String, String>
    ): ProxyNode {
        val pluginRaw = query["plugin"].orEmpty()
        val plugin = pluginRaw.substringBefore(';').trim()
        val pluginOpts = if (pluginRaw.contains(';')) pluginRaw.substringAfter(';') else ""
        return ProxyNode(
            name = remark.ifBlank { "$host:$port" },
            protocol = ProtocolType.SHADOWSOCKS,
            server = host,
            port = port,
            method = method,
            password = password,
            securityCipher = method,
            plugin = plugin,
            pluginOpts = pluginOpts,
            transport = TransportConfig.DEFAULT
        )
    }
}
