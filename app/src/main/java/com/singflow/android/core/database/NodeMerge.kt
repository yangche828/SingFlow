package com.singflow.android.core.database

import com.singflow.android.core.model.TlsConfig

/**
 * 订阅刷新时的节点合并策略。
 *
 * 同一连接指纹的节点只更新配置与展示信息，保留收藏、健康统计、
 * 累计流量和已实测国家，避免每次订阅刷新把个性化数据清零。
 */
object NodeMerge {

    /**
     * 合并已有节点与订阅新节点。
     *
     * @param existing 数据库中的节点
     * @param incoming 订阅解析出的节点
     */
    fun preserve(existing: NodeEntity, incoming: NodeEntity): NodeEntity {
        return existing.copy(
            configHash = incoming.configHash,
            rawOutboundJson = incoming.rawOutboundJson,
            rawShareUri = incoming.rawShareUri.ifBlank { existing.rawShareUri },
            name = incoming.name,
            groupId = incoming.groupId,
            protocol = incoming.protocol,
            server = incoming.server,
            port = incoming.port,
            uuid = incoming.uuid,
            alterId = incoming.alterId,
            securityCipher = incoming.securityCipher,
            flow = incoming.flow,
            password = incoming.password,
            method = incoming.method,
            plugin = incoming.plugin,
            pluginOpts = incoming.pluginOpts,
            upMbps = incoming.upMbps,
            downMbps = incoming.downMbps,
            obfsType = incoming.obfsType,
            obfsPassword = incoming.obfsPassword,
            congestionControl = incoming.congestionControl,
            udpRelayMode = incoming.udpRelayMode,
            wgPrivateKey = incoming.wgPrivateKey,
            wgPeerPublicKey = incoming.wgPeerPublicKey,
            wgPreSharedKey = incoming.wgPreSharedKey,
            wgLocalAddress = incoming.wgLocalAddress,
            wgReserved = incoming.wgReserved,
            wgMtu = incoming.wgMtu,
            transportJson = incoming.transportJson,
            tlsJson = incoming.tlsJson,
            countryCode = if (existing.isRegionVerified) {
                existing.countryCode
            } else {
                incoming.countryCode
            },
            countryNameCn = if (existing.isRegionVerified) {
                existing.countryNameCn
            } else {
                incoming.countryNameCn
            }
        )
    }

    /**
     * 刷新时的 TLS/ECH 合并：已有 ECH config 不被本次失败的空结果覆盖。
     *
     * 订阅刷新会重做 DoH 查询，若本次失败（PEM 为空）而直接采用新配置，
     * ECH 会被静默关闭、SNI 重新暴露。链接已不再声明 ech 时尊重新配置。
     *
     * @param existing 库中已解码的 TLS 配置，无法解码时传 null
     * @param incoming 本次解析出的 TLS 配置
     * @return 应当落库的 TLS 配置
     */
    fun preserveEch(existing: TlsConfig?, incoming: TlsConfig): TlsConfig {
        if (existing == null) return incoming
        if (!incoming.echEnabled || incoming.echConfigPem.isNotBlank() || existing.echConfigPem.isBlank()) {
            return incoming
        }
        return incoming.copy(
            echConfigSource = incoming.echConfigSource.ifBlank { existing.echConfigSource },
            echDohUrl = incoming.echDohUrl.ifBlank { existing.echDohUrl },
            echConfigPem = existing.echConfigPem
        )
    }
}
