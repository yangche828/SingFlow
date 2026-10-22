package com.singflow.android.core.config.interceptors

import com.singflow.android.core.model.AntiBlockingConfig
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 单节点 outbound / endpoint 构造工厂。
 *
 * 供单节点编译与多国分组编译共用，避免两套逻辑产生差异。
 */
object ProxyOutboundFactory {

    /**
     * 构造标准代理 outbound；CUSTOM_JSON 会先合并原始 JSON 再覆盖 tag 与 domain_resolver。
     */
    fun buildProxyOutbound(
        node: ProxyNode,
        tag: String,
        resolver: JsonObject,
        antiBlocking: AntiBlockingConfig = AntiBlockingConfig.DEFAULT
    ): JsonObject {
        if (node.protocol == ProtocolType.CUSTOM_JSON && node.rawOutboundJson.isNotBlank()) {
            val raw = runCatching {
                Json.parseToJsonElement(node.rawOutboundJson).jsonObject
            }.getOrNull()
            if (raw != null) {
                return buildJsonObject {
                    raw.forEach { (key, value) -> put(key, value) }
                    put("tag", tag)
                    put("domain_resolver", resolver)
                }
            }
        }

        return buildJsonObject {
            put("type", node.protocol.singBoxType)
            put("tag", tag)
            put("server", node.server)
            put("server_port", node.port)
            put("domain_resolver", resolver)

            when (node.protocol) {
                ProtocolType.VMESS -> {
                    put("uuid", node.uuid)
                    put("security", normalizeVmessSecurity(node.securityCipher))
                    put("alter_id", node.alterId)
                }
                ProtocolType.VLESS -> {
                    put("uuid", node.uuid)
                    if (node.flow.isNotBlank()) put("flow", node.flow)
                }
                ProtocolType.TROJAN -> {
                    put("password", node.password)
                }
                ProtocolType.SHADOWSOCKS -> {
                    put("method", node.method.ifBlank { node.securityCipher.ifBlank { "aes-256-gcm" } })
                    put("password", node.password)
                    if (node.plugin.isNotBlank()) {
                        put("plugin", node.plugin)
                        if (node.pluginOpts.isNotBlank()) put("plugin_opts", node.pluginOpts)
                    }
                }
                ProtocolType.HYSTERIA2 -> {
                    put("password", node.password)
                    if (node.upMbps > 0) put("up_mbps", node.upMbps)
                    if (node.downMbps > 0) put("down_mbps", node.downMbps)
                    if (node.obfsType.isNotBlank()) {
                        putJsonObject("obfs") {
                            put("type", node.obfsType)
                            put("password", node.obfsPassword)
                        }
                    }
                }
                ProtocolType.TUIC -> {
                    put("uuid", node.uuid)
                    put("password", node.password)
                    put("congestion_control", node.congestionControl.ifBlank { "bbr" })
                    put("udp_relay_mode", node.udpRelayMode.ifBlank { "native" })
                }
                ProtocolType.SOCKS, ProtocolType.HTTP -> {
                    put("version", "5")
                    if (node.uuid.isNotBlank()) put("username", node.uuid)
                    if (node.password.isNotBlank()) put("password", node.password)
                }
                ProtocolType.WIREGUARD, ProtocolType.CUSTOM_JSON -> Unit
            }

            buildTls(node, antiBlocking)?.let { put("tls", it) }
            buildTransport(node)?.let { put("transport", it) }
            if (antiBlocking.multiplexEnabled && supportsMultiplex(node.protocol)) {
                putJsonObject("multiplex") {
                    put("enabled", true)
                    put("protocol", antiBlocking.multiplexProtocol)
                    put("max_streams", antiBlocking.multiplexMaxStreams.coerceIn(1, 128))
                    put("padding", antiBlocking.multiplexPadding)
                }
            }
        }
    }

    /**
     * 多路复用仅对基于 TCP 的代理协议生效；QUIC 协议自身已有连接复用。
     */
    private fun supportsMultiplex(protocol: ProtocolType): Boolean = when (protocol) {
        ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TROJAN, ProtocolType.SHADOWSOCKS -> true
        else -> false
    }

    /**
     * 构造 TLS / REALITY 段。
     */
    fun buildTls(
        node: ProxyNode,
        antiBlocking: AntiBlockingConfig = AntiBlockingConfig.DEFAULT
    ): JsonObject? {
        val forceTls = node.protocol == ProtocolType.HYSTERIA2 ||
            node.protocol == ProtocolType.TUIC ||
            node.protocol == ProtocolType.TROJAN
        if (!node.tls.enabled && !forceTls) return null

        val tls = node.tls
        return buildJsonObject {
            put("enabled", true)
            val serverName = tls.serverName.ifBlank {
                node.transport.host.ifBlank { node.server }
            }
            if (serverName.isNotBlank()) put("server_name", serverName)
            put("insecure", tls.insecure)
            if (tls.alpn.isNotEmpty()) {
                putJsonArray("alpn") { tls.alpn.forEach { add(JsonPrimitive(it)) } }
            }
            val fingerprint = antiBlocking.utlsFingerprint.ifBlank { tls.utlsFingerprint }
            if (fingerprint.isNotBlank()) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                }
            }
            if (antiBlocking.tlsFragment) {
                put("fragment", true)
                put("record_fragment", true)
                put("fragment_fallback_delay", "500ms")
            }
            if (tls.realityEnabled) {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", tls.realityPublicKey)
                    put("short_id", tls.realityShortId)
                }
            }
        }
    }

    /**
     * 构造传输层段；tcp 不生成 transport。
     */
    fun buildTransport(node: ProxyNode): JsonObject? {
        val transport = node.transport
        if (transport.isDefaultTcp) return null
        return when (transport.type.lowercase()) {
            "ws" -> buildJsonObject {
                put("type", "ws")
                put("path", transport.path.ifBlank { "/" })
                if (transport.host.isNotBlank()) {
                    putJsonObject("headers") { put("Host", transport.host) }
                }
                if (transport.maxEarlyData > 0) {
                    put("max_early_data", transport.maxEarlyData)
                    if (transport.earlyDataHeaderName.isNotBlank()) {
                        put("early_data_header_name", transport.earlyDataHeaderName)
                    }
                }
            }
            "grpc" -> buildJsonObject {
                put("type", "grpc")
                put("service_name", transport.serviceName)
            }
            "http" -> buildJsonObject {
                put("type", "http")
                if (transport.host.isNotBlank()) {
                    putJsonArray("host") { add(JsonPrimitive(transport.host)) }
                }
                put("path", transport.path.ifBlank { "/" })
            }
            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                put("path", transport.path.ifBlank { "/" })
                if (transport.host.isNotBlank()) put("host", transport.host)
            }
            "quic" -> buildJsonObject { put("type", "quic") }
            else -> null
        }
    }

    /**
     * 构造 WireGuard endpoint（sing-box 1.11+）。
     */
    fun buildWireGuardEndpoint(node: ProxyNode, tag: String, resolver: JsonObject): JsonObject {
        return buildJsonObject {
            put("type", "wireguard")
            put("tag", tag)
            putJsonArray("address") {
                node.wgLocalAddress.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { add(it) }
            }
            put("private_key", node.wgPrivateKey)
            if (node.wgPeerPublicKey.isNotBlank()) put("peer_public_key", node.wgPeerPublicKey)
            if (node.wgPreSharedKey.isNotBlank()) put("pre_shared_key", node.wgPreSharedKey)
            if (node.wgReserved.isNotEmpty()) {
                putJsonArray("reserved") { node.wgReserved.forEach { add(JsonPrimitive(it)) } }
            }
            put("mtu", node.wgMtu)
            put("server", node.server)
            put("server_port", node.port)
            put("domain_resolver", resolver)
        }
    }

    /**
     * 归一化 VMess 加密方式。
     */
    fun normalizeVmessSecurity(raw: String): String {
        val value = raw.lowercase().trim()
        return when (value) {
            "auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero" -> value
            "" -> "auto"
            else -> "auto"
        }
    }
}
