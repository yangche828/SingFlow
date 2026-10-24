package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser

/**
 * VLESS 解析器，完整支持 TLS / REALITY / XTLS Vision。
 */
class VlessParser : NodeLinkParser {

    override val name: String = "VLESS"
    override val schemes: List<String> = listOf("vless")

    override fun parse(link: String): ProxyNode? {
        val parsed = LinkParserUtils.parse(link) ?: return null
        if (!parsed.scheme.equals("vless", ignoreCase = true)) return null
        val uuid = LinkParserUtils.decodeStrict(parsed.userInfo)
        if (uuid.isBlank()) return null

        val security = parsed.param("security").lowercase()
        val reality = security == "reality"
        val tlsEnabled = security == "tls" || security == "reality" || parsed.boolParam("tls")
        val transportType = LinkParserUtils.normalizeTransportType(parsed.param("type", "net"))
        val host = parsed.param("host")
        val path = parsed.param("path", "__path")
        val serviceName = parsed.param("servicename")
        val sni = parsed.param("sni", "serverName", "peer")
        val fingerprint = parsed.param("fp").ifBlank { "chrome" }
        val alpn = LinkParserUtils.parseAlpn(parsed.param("alpn"))
        // ech 参数形如 "cloudflare-ech.com+https://dns.alidns.com/dns-query"：
        // 加号前是 ECH config 来源域名，加号后是解析用 DoH；仅有域名时由
        // EchConfigResolver 回退到境内可达的阿里 DoH（不是 Google DoH）。
        val echParam = parsed.param("ech")
        val echParts = echParam.split('+', limit = 2)
        val echSource = echParts.getOrNull(0)?.trim().orEmpty()
        val echDoh = echParts.getOrNull(1)?.trim().orEmpty()

        val tls = TlsConfig(
            enabled = tlsEnabled,
            serverName = sni,
            insecure = parsed.boolParam("allowinsecure", "insecure"),
            alpn = alpn,
            utlsFingerprint = fingerprint,
            realityEnabled = reality,
            realityPublicKey = parsed.param("pbk"),
            realityShortId = parsed.param("sid"),
            realitySpiderX = parsed.param("spx"),
            echEnabled = echSource.isNotBlank(),
            echConfigSource = echSource,
            echDohUrl = echDoh
        )

        val transport = when (transportType) {
            "ws" -> TransportConfig(type = "ws", host = host, path = path.ifBlank { "/" })
            "grpc" -> TransportConfig(type = "grpc", serviceName = serviceName.ifBlank { path })
            "http" -> TransportConfig(type = "http", host = host, path = path.ifBlank { "/" })
            "httpupgrade" -> TransportConfig(type = "httpupgrade", host = host, path = path.ifBlank { "/" })
            "quic" -> TransportConfig(type = "quic", host = host, path = path)
            else -> TransportConfig.DEFAULT
        }

        return ProxyNode(
            name = parsed.fragment.ifBlank { "${parsed.host}:${parsed.port}" },
            protocol = ProtocolType.VLESS,
            server = parsed.host,
            port = parsed.port,
            uuid = uuid,
            flow = parsed.param("flow"),
            transport = transport,
            tls = tls
        )
    }
}
