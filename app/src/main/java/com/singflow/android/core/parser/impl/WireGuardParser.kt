package com.singflow.android.core.parser.impl

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.parser.LinkParserUtils
import com.singflow.android.core.parser.NodeLinkParser

/**
 * WireGuard 解析器。
 *
 * 支持：
 * `wireguard://privateKey@host:port?publicKey=...&address=...&mtu=...#remark`
 * 以及 `wg://` 别名。
 */
class WireGuardParser : NodeLinkParser {

    override val name: String = "WireGuard"
    override val schemes: List<String> = listOf("wireguard", "wg")

    override fun parse(link: String): ProxyNode? {
        val parsed = LinkParserUtils.parse(link) ?: return null
        if (parsed.scheme != "wireguard" && parsed.scheme != "wg") return null

        val userInfo = LinkParserUtils.decodeStrict(parsed.userInfo)
        val privateKey = parsed.param("privatekey", "private_key").ifBlank { userInfo }
        if (privateKey.isBlank()) return null

        val peerPublicKey = parsed.param("publickey", "public_key", "peer_public_key", "peer")
        val presharedKey = parsed.param("presharedkey", "preshared_key", "pre_shared_key")
        val address = parsed.param("address", "local_address", "ip").ifBlank { "172.16.0.2/32" }
        val mtu = parsed.param("mtu").toIntOrNull()?.takeIf { it in 576..9000 } ?: 1420
        val reserved = parsed.param("reserved")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..255 }

        return ProxyNode(
            name = parsed.fragment.ifBlank { "WireGuard-${parsed.host}" },
            protocol = ProtocolType.WIREGUARD,
            server = parsed.host,
            port = parsed.port,
            wgPrivateKey = privateKey,
            wgPeerPublicKey = peerPublicKey,
            wgPreSharedKey = presharedKey,
            wgLocalAddress = address,
            wgReserved = reserved,
            wgMtu = mtu
        )
    }
}
