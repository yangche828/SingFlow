package com.singflow.android.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode

/**
 * 节点持久化实体。传输层与 TLS 配置以 JSON 字符串保存，避免大量冗余列。
 */
@Entity(tableName = "nodes")
data class NodeEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val name: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val uuid: String,
    val alterId: Int,
    val securityCipher: String,
    val flow: String,
    val password: String,
    val method: String,
    val plugin: String,
    val pluginOpts: String,
    val upMbps: Int,
    val downMbps: Int,
    val obfsType: String,
    val obfsPassword: String,
    val congestionControl: String,
    val udpRelayMode: String,
    val wgPrivateKey: String,
    val wgPeerPublicKey: String,
    val wgPreSharedKey: String,
    val wgLocalAddress: String,
    val wgReserved: String,
    val wgMtu: Int,
    val rawOutboundJson: String,
    val transportJson: String,
    val tlsJson: String,
    val latencyMs: Int,
    val createdAt: Long
) {
    /**
     * 转换为领域模型。
     *
     * @param transport 由转换器反序列化后的传输配置
     * @param tls 由转换器反序列化后的 TLS 配置
     */
    fun toDomain(transport: com.singflow.android.core.model.TransportConfig, tls: com.singflow.android.core.model.TlsConfig): ProxyNode {
        return ProxyNode(
            id = id,
            groupId = groupId,
            name = name,
            protocol = ProtocolType.fromRaw(protocol) ?: ProtocolType.CUSTOM_JSON,
            server = server,
            port = port,
            uuid = uuid,
            alterId = alterId,
            securityCipher = securityCipher,
            flow = flow,
            password = password,
            method = method,
            plugin = plugin,
            pluginOpts = pluginOpts,
            upMbps = upMbps,
            downMbps = downMbps,
            obfsType = obfsType,
            obfsPassword = obfsPassword,
            congestionControl = congestionControl,
            udpRelayMode = udpRelayMode,
            wgPrivateKey = wgPrivateKey,
            wgPeerPublicKey = wgPeerPublicKey,
            wgPreSharedKey = wgPreSharedKey,
            wgLocalAddress = wgLocalAddress,
            wgReserved = parseReserved(wgReserved),
            wgMtu = wgMtu,
            rawOutboundJson = rawOutboundJson,
            transport = transport,
            tls = tls,
            latencyMs = latencyMs,
            createdAt = createdAt
        )
    }

    companion object {
        /**
         * 从领域模型创建实体。
         *
         * @param transportJson 传输配置 JSON
         * @param tlsJson TLS 配置 JSON
         */
        fun fromDomain(node: ProxyNode, transportJson: String, tlsJson: String): NodeEntity {
            return NodeEntity(
                id = node.id,
                groupId = node.groupId,
                name = node.name,
                protocol = node.protocol.name,
                server = node.server,
                port = node.port,
                uuid = node.uuid,
                alterId = node.alterId,
                securityCipher = node.securityCipher,
                flow = node.flow,
                password = node.password,
                method = node.method,
                plugin = node.plugin,
                pluginOpts = node.pluginOpts,
                upMbps = node.upMbps,
                downMbps = node.downMbps,
                obfsType = node.obfsType,
                obfsPassword = node.obfsPassword,
                congestionControl = node.congestionControl,
                udpRelayMode = node.udpRelayMode,
                wgPrivateKey = node.wgPrivateKey,
                wgPeerPublicKey = node.wgPeerPublicKey,
                wgPreSharedKey = node.wgPreSharedKey,
                wgLocalAddress = node.wgLocalAddress,
                wgReserved = node.wgReserved.joinToString(","),
                wgMtu = node.wgMtu,
                rawOutboundJson = node.rawOutboundJson,
                transportJson = transportJson,
                tlsJson = tlsJson,
                latencyMs = node.latencyMs,
                createdAt = node.createdAt
            )
        }

        private fun parseReserved(raw: String): List<Int> {
            if (raw.isBlank()) return emptyList()
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
    }
}
