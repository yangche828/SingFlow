package com.singflow.android.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig

/**
 * 节点持久化实体。
 *
 * 传输层与 TLS 配置以 JSON 字符串保存，避免大量冗余列；
 * 健康度、国家与收藏字段用于分组、排序与自动切换。
 *
 * 注意：新增列必须同时提供 [ColumnInfo.defaultValue]，否则 Room 迁移校验会因为
 * 「表里有 DEFAULT、实体没声明」而判定 schema 不一致。
 */
@Entity(
    tableName = "nodes",
    indices = [
        Index(value = ["configHash"], name = "index_nodes_configHash"),
        Index(value = ["countryCode", "healthScore"], name = "index_nodes_countryCode_healthScore"),
        Index(value = ["isFavorite", "healthScore"], name = "index_nodes_isFavorite_healthScore"),
        Index(value = ["groupId"], name = "index_nodes_groupId")
    ]
)
data class NodeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "''") val configHash: String = "",
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
    val createdAt: Long,
    @ColumnInfo(defaultValue = "''") val rawShareUri: String = "",
    @ColumnInfo(defaultValue = "'UN'") val countryCode: String = "UN",
    @ColumnInfo(defaultValue = "'未知地区'") val countryNameCn: String = "未知地区",
    val egressIp: String? = null,
    @ColumnInfo(defaultValue = "0") val isRegionVerified: Boolean = false,
    @ColumnInfo(defaultValue = "0") val probeTotalCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val probeSuccessCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val consecutiveFailures: Int = 0,
    @ColumnInfo(defaultValue = "0") val emaLatencyMs: Float = 0f,
    @ColumnInfo(defaultValue = "0") val latencyJitterMs: Float = 0f,
    @ColumnInfo(defaultValue = "50") val healthScore: Int = 50,
    @ColumnInfo(defaultValue = "'未检测'") val healthTag: String = "未检测",
    @ColumnInfo(defaultValue = "0") val lastProbeTimeMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val cooldownUntilMs: Long = 0L,
    @ColumnInfo(defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0") val cumulativeRxBytes: Long = 0L,
    @ColumnInfo(defaultValue = "0") val cumulativeTxBytes: Long = 0L
) {
    /**
     * 转换为领域模型。
     *
     * @param transport 由转换器反序列化后的传输配置
     * @param tls 由转换器反序列化后的 TLS 配置
     */
    fun toDomain(transport: TransportConfig, tls: TlsConfig): ProxyNode {
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
            createdAt = createdAt,
            rawShareUri = rawShareUri,
            countryCode = countryCode,
            countryNameCn = countryNameCn,
            egressIp = egressIp,
            isRegionVerified = isRegionVerified,
            probeTotalCount = probeTotalCount,
            probeSuccessCount = probeSuccessCount,
            consecutiveFailures = consecutiveFailures,
            emaLatencyMs = emaLatencyMs,
            latencyJitterMs = latencyJitterMs,
            healthScore = healthScore,
            healthTag = healthTag,
            lastProbeTimeMs = lastProbeTimeMs,
            cooldownUntilMs = cooldownUntilMs,
            isFavorite = isFavorite,
            cumulativeRxBytes = cumulativeRxBytes,
            cumulativeTxBytes = cumulativeTxBytes
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
                configHash = node.configHash,
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
                createdAt = node.createdAt,
                rawShareUri = node.rawShareUri,
                countryCode = node.countryCode,
                countryNameCn = node.countryNameCn,
                egressIp = node.egressIp,
                isRegionVerified = node.isRegionVerified,
                probeTotalCount = node.probeTotalCount,
                probeSuccessCount = node.probeSuccessCount,
                consecutiveFailures = node.consecutiveFailures,
                emaLatencyMs = node.emaLatencyMs,
                latencyJitterMs = node.latencyJitterMs,
                healthScore = node.healthScore,
                healthTag = node.healthTag,
                lastProbeTimeMs = node.lastProbeTimeMs,
                cooldownUntilMs = node.cooldownUntilMs,
                isFavorite = node.isFavorite,
                cumulativeRxBytes = node.cumulativeRxBytes,
                cumulativeTxBytes = node.cumulativeTxBytes
            )
        }

        private fun parseReserved(raw: String): List<Int> {
            if (raw.isBlank()) return emptyList()
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        }
    }
}
