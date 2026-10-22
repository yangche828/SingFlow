package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import com.singflow.android.core.parser.Base64Utils
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser
import com.singflow.android.core.parser.booleanOrDefault
import com.singflow.android.core.parser.intOrDefault
import com.singflow.android.core.parser.stringList
import com.singflow.android.core.parser.stringOrEmpty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * VMess 解析器。
 *
 * 同时支持：
 * 1. v2rayN 的 `vmess://BASE64(JSON)`；
 * 2. `vmess://uuid@host:port?...#remark` 标准 URI。
 */
class VmessParser(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) : NodeLinkParser {

    override val name: String = "VMess"
    override val schemes: List<String> = listOf("vmess")

    override fun parse(link: String): ProxyNode? {
        val raw = link.trim()
        if (!canParse(raw)) return null
        val payload = raw.substringAfter("://").substringBefore('#')
        val decoded = Base64Utils.safeBase64DecodeOrNull(payload)
        if (decoded != null && decoded.trimStart().startsWith("{")) {
            return parseJson(decoded)
        }
        return parseUri(raw)
    }

    /**
     * 解析 v2rayN Base64 JSON 格式，端口与 alterId 兼容字符串/数字。
     */
    fun parseJson(decoded: String): ProxyNode? {
        val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull() ?: return null
        val server = obj.stringOrEmpty("add")
        if (server.isBlank()) return null
        val port = obj.intOrDefault("port", 443).takeIf { it in 1..65535 } ?: return null
        val net = LinkParserUtils.normalizeTransportType(obj.stringOrEmpty("net"))
        val tlsEnabled = obj.stringOrEmpty("tls").equals("tls", ignoreCase = true)
        val host = obj.stringOrEmpty("host").substringBefore(',').trim()
        val path = obj.stringOrEmpty("path")
        val alpn = LinkParserUtils.parseAlpn(obj.stringOrEmpty("alpn"))
        val sni = obj.stringOrEmpty("sni").ifBlank { host }
        val transport = buildTransport(
            net = net,
            host = host,
            path = obj.stringOrEmpty("serviceName").ifBlank { path },
            headerType = obj.stringOrEmpty("type")
        )
        return ProxyNode(
            name = obj.stringOrEmpty("ps").ifBlank { "$server:$port" },
            protocol = ProtocolType.VMESS,
            server = server,
            port = port,
            uuid = obj.stringOrEmpty("id"),
            alterId = obj.intOrDefault("aid", 0),
            securityCipher = obj.stringOrEmpty("scy").ifBlank { "auto" },
            transport = transport,
            tls = TlsConfig(
                enabled = tlsEnabled,
                serverName = sni,
                insecure = obj.booleanOrDefault("allowInsecure", false),
                alpn = alpn,
                utlsFingerprint = obj.stringOrEmpty("fp").ifBlank { "chrome" }
            )
        )
    }

    private fun parseUri(raw: String): ProxyNode? {
        val parsed = LinkParserUtils.parse(raw) ?: return null
        val uuid = LinkParserUtils.decodeStrict(parsed.userInfo)
        if (uuid.isBlank()) return null
        val net = LinkParserUtils.normalizeTransportType(parsed.param("type", "net"))
        val security = parsed.param("security", "tls")
        val host = parsed.param("host")
        val path = parsed.param("path", "__path")
        val serviceName = parsed.param("servicename")
        val tlsEnabled = security.equals("tls", ignoreCase = true) ||
            security.equals("reality", ignoreCase = true) ||
            parsed.boolParam("tls")
        return ProxyNode(
            name = parsed.fragment.ifBlank { "${parsed.host}:${parsed.port}" },
            protocol = ProtocolType.VMESS,
            server = parsed.host,
            port = parsed.port,
            uuid = uuid,
            alterId = parsed.param("aid").toIntOrNull() ?: 0,
            securityCipher = parsed.param("scy").ifBlank { "auto" },
            transport = buildTransport(
                net = net,
                host = host,
                path = if (net == "grpc") serviceName.ifBlank { path } else path,
                headerType = parsed.param("headertype")
            ),
            tls = TlsConfig(
                enabled = tlsEnabled,
                serverName = parsed.param("sni", "peer").ifBlank { host },
                insecure = parsed.boolParam("allowinsecure", "insecure"),
                alpn = LinkParserUtils.parseAlpn(parsed.param("alpn")),
                utlsFingerprint = parsed.param("fp").ifBlank { "chrome" }
            )
        )
    }

    /**
     * 依据传输类型构建统一的传输配置。
     */
    private fun buildTransport(net: String, host: String, path: String, headerType: String): TransportConfig {
        return when (net) {
            "ws" -> TransportConfig(type = "ws", host = host, path = path.ifBlank { "/" })
            "grpc" -> TransportConfig(type = "grpc", serviceName = path)
            "http" -> TransportConfig(
                type = "http",
                host = host,
                path = path.ifBlank { "/" },
                headers = if (headerType.isNotBlank()) mapOf("Host" to host) else emptyMap()
            )
            "httpupgrade" -> TransportConfig(type = "httpupgrade", host = host, path = path.ifBlank { "/" })
            "quic" -> TransportConfig(type = "quic", host = host, path = path)
            else -> TransportConfig.DEFAULT
        }
    }

    /** 供测试使用的 JSON 解析入口。 */
    fun parseObject(obj: JsonObject): ProxyNode? = parseJson(obj.toString())

    /** 供测试使用的字段读取。 */
    fun alpnOf(obj: JsonObject): List<String> = obj.stringList("alpn")
}
