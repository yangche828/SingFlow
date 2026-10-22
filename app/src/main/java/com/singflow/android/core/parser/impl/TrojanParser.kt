package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser

/**
 * Trojan 解析器。Trojan 默认启用 TLS。
 */
class TrojanParser : NodeLinkParser {

    override val name: String = "Trojan"
    override val schemes: List<String> = listOf("trojan")

    override fun parse(link: String): ProxyNode? {
        val parsed = LinkParserUtils.parse(link) ?: return null
        if (!parsed.scheme.equals("trojan", ignoreCase = true)) return null
        val password = LinkParserUtils.decodeStrict(parsed.userInfo)
        if (password.isBlank()) return null

        val transportType = LinkParserUtils.normalizeTransportType(parsed.param("type", "net"))
        val host = parsed.param("host")
        val path = parsed.param("path", "__path")
        val serviceName = parsed.param("servicename")
        val alpn = LinkParserUtils.parseAlpn(parsed.param("alpn"))
        val sni = parsed.param("sni", "peer", "servername")

        val transport = when (transportType) {
            "ws" -> TransportConfig(type = "ws", host = host, path = path.ifBlank { "/" })
            "grpc" -> TransportConfig(type = "grpc", serviceName = serviceName.ifBlank { path })
            "http" -> TransportConfig(type = "http", host = host, path = path.ifBlank { "/" })
            "httpupgrade" -> TransportConfig(type = "httpupgrade", host = host, path = path.ifBlank { "/" })
            else -> TransportConfig.DEFAULT
        }

        return ProxyNode(
            name = parsed.fragment.ifBlank { "${parsed.host}:${parsed.port}" },
            protocol = ProtocolType.TROJAN,
            server = parsed.host,
            port = parsed.port,
            password = password,
            transport = transport,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = parsed.boolParam("allowinsecure", "insecure", "skip-cert-verify"),
                alpn = alpn,
                utlsFingerprint = parsed.param("fp").ifBlank { "chrome" }
            )
        )
    }
}
