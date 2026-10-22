package com.singflow.android.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 统一的代理节点领域模型。所有解析器最终都产出该模型，所有 UI 与配置编译都只依赖它。
 *
 * @property id 本地唯一 ID
 * @property groupId 所属分组（订阅 ID 或 `manual`）
 * @property name 节点备注
 * @property protocol 协议类型
 * @property server 服务器地址（域名或 IP，IPv6 不含方括号）
 * @property port 端口
 * @property uuid VMess / VLESS / TUIC 的 UUID
 * @property alterId VMess alterId
 * @property securityCipher VMess 加密方式 / SS 方法复用位
 * @property flow VLESS flow（如 xtls-rprx-vision）
 * @property password Trojan / SS / Hysteria2 / TUIC 密码
 * @property method Shadowsocks 加密方法
 * @property plugin Shadowsocks 插件
 * @property pluginOpts Shadowsocks 插件参数
 * @property upMbps Hysteria2 上行带宽
 * @property downMbps Hysteria2 下行带宽
 * @property obfsType Hysteria2 混淆类型
 * @property obfsPassword Hysteria2 混淆密码
 * @property congestionControl TUIC 拥塞控制
 * @property udpRelayMode TUIC UDP 中继模式
 * @property wgPrivateKey WireGuard 私钥
 * @property wgPeerPublicKey WireGuard 对端公钥
 * @property wgPreSharedKey WireGuard 预共享密钥
 * @property wgLocalAddress WireGuard 本地地址（可含掩码）
 * @property wgReserved WireGuard reserved 字节
 * @property wgMtu WireGuard MTU
 * @property rawOutboundJson CUSTOM_JSON 协议的原始 outbound JSON
 * @property transport 传输层配置
 * @property tls TLS 配置
 * @property latencyMs 最近一次测速延迟，-1 表示未测速
 * @property createdAt 创建时间戳
 */
@Serializable
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val groupId: String = "manual",
    val name: String = "",
    val protocol: ProtocolType = ProtocolType.VLESS,
    val server: String = "",
    val port: Int = 443,
    val uuid: String = "",
    val alterId: Int = 0,
    val securityCipher: String = "auto",
    val flow: String = "",
    val password: String = "",
    val method: String = "",
    val plugin: String = "",
    val pluginOpts: String = "",
    val upMbps: Int = 0,
    val downMbps: Int = 0,
    val obfsType: String = "",
    val obfsPassword: String = "",
    val congestionControl: String = "bbr",
    val udpRelayMode: String = "native",
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgLocalAddress: String = "172.16.0.2/32",
    val wgReserved: List<Int> = emptyList(),
    val wgMtu: Int = 1420,
    val rawOutboundJson: String = "",
    val transport: TransportConfig = TransportConfig.DEFAULT,
    val tls: TlsConfig = TlsConfig.DEFAULT,
    val latencyMs: Int = -1,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** 展示用标题：备注为空时回退到 `server:port`。 */
    val displayName: String
        get() = name.ifBlank { "$server:$port" }

    /** 是否已测速。 */
    val hasLatency: Boolean get() = latencyMs >= 0

    /**
     * 生成用于去重的稳定键。
     */
    fun dedupKey(): String {
        val credential = when (protocol) {
            ProtocolType.VMESS, ProtocolType.VLESS, ProtocolType.TUIC -> uuid
            ProtocolType.SHADOWSOCKS -> method
            ProtocolType.WIREGUARD -> wgPeerPublicKey
            else -> password
        }
        return "${protocol.name}|$server|$port|$credential"
    }
}
