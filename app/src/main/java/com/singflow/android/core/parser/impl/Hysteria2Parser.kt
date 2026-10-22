package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser

/**
 * Hysteria2 解析器，支持 `hysteria2://` 与 `hy2://`。
 */
class Hysteria2Parser : NodeLinkParser {

    override val name: String = "Hysteria2"
    override val schemes: List<String> = listOf("hysteria2", "hy2")

    override fun parse(link: String): ProxyNode? {
        val parsed = LinkParserUtils.parse(link) ?: return null
        if (parsed.scheme != "hysteria2" && parsed.scheme != "hy2") return null

        // 密码可能包含 `user:pass` 形式，整体作为认证信息。
        val password = LinkParserUtils.decodeStrict(parsed.userInfo)
        val sni = parsed.param("sni", "peer")
        val alpn = LinkParserUtils.parseAlpn(parsed.param("alpn"))
        val obfs = parsed.param("obfs")
        val obfsPassword = parsed.param("obfs-password", "obfs_password")
        val up = parsed.param("upmbps", "up").toIntOrNull() ?: 0
        val down = parsed.param("downmbps", "down").toIntOrNull() ?: 0

        val finalAlpn = if (alpn.isEmpty()) listOf("h3") else alpn

        return ProxyNode(
            name = parsed.fragment.ifBlank { "${parsed.host}:${parsed.port}" },
            protocol = ProtocolType.HYSTERIA2,
            server = parsed.host,
            port = parsed.port,
            password = password,
            upMbps = up,
            downMbps = down,
            obfsType = obfs,
            obfsPassword = obfsPassword,
            tls = TlsConfig(
                enabled = true,
                serverName = sni,
                insecure = parsed.boolParam("insecure", "allowinsecure", "skip-cert-verify"),
                alpn = finalAlpn,
                utlsFingerprint = ""
            )
        )
    }
}
