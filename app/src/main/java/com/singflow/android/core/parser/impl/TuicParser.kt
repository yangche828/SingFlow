package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser

/**
 * TUIC v5 解析器：`tuic://uuid:password@host:port?...#remark`。
 */
class TuicParser : NodeLinkParser {

    override val name: String = "TUIC"
    override val schemes: List<String> = listOf("tuic")

    override fun parse(link: String): ProxyNode? {
        val parsed = LinkParserUtils.parse(link) ?: return null
        if (parsed.scheme != "tuic") return null

        val decodedUserInfo = LinkParserUtils.decodeStrict(parsed.userInfo)
        if (decodedUserInfo.isBlank()) return null
        val (uuid, password) = LinkParserUtils.splitFirstColon(decodedUserInfo)
        if (uuid.isBlank()) return null

        val alpn = LinkParserUtils.parseAlpn(parsed.param("alpn"))
        val finalAlpn = if (alpn.isEmpty()) listOf("h3") else alpn
        val sni = parsed.param("sni", "peer")

        return ProxyNode(
            name = parsed.fragment.ifBlank { "${parsed.host}:${parsed.port}" },
            protocol = ProtocolType.TUIC,
            server = parsed.host,
            port = parsed.port,
            uuid = uuid,
            password = password,
            congestionControl = parsed.param("congestion_control", "congestion-control").ifBlank { "bbr" },
            udpRelayMode = parsed.param("udp_relay_mode", "udp-relay-mode").ifBlank { "native" },
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = parsed.boolParam("allow_insecure", "allowinsecure", "insecure"),
                alpn = finalAlpn,
                utlsFingerprint = parsed.param("fp")
            )
        )
    }
}
