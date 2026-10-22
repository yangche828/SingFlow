package com.singflow.android.core.database

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
}
