package com.singflow.android.core.model

import kotlinx.serialization.Serializable

/**
 * 支持的代理协议类型。
 *
 * @property displayName 界面展示名称
 * @property badgeColorHex ARGB 颜色值，用于节点卡片协议徽章
 */
@Serializable
enum class ProtocolType(
    val displayName: String,
    val badgeColorHex: Long
) {
    VMESS("VMess", 0xFF3B82F6),
    VLESS("VLESS", 0xFF8B5CF6),
    TROJAN("Trojan", 0xFFF59E0B),
    SHADOWSOCKS("Shadowsocks", 0xFF10B981),
    HYSTERIA2("Hysteria2", 0xFFF472B6),
    TUIC("TUIC", 0xFF06B6D4),
    WIREGUARD("WireGuard", 0xFF14B8A6),
    SOCKS("SOCKS", 0xFF64748B),
    HTTP("HTTP", 0xFF94A3B8),
    CUSTOM_JSON("Custom JSON", 0xFFA3E635);

    /** sing-box outbound 的 type 字符串。 */
    val singBoxType: String
        get() = when (this) {
            VMESS -> "vmess"
            VLESS -> "vless"
            TROJAN -> "trojan"
            SHADOWSOCKS -> "shadowsocks"
            HYSTERIA2 -> "hysteria2"
            TUIC -> "tuic"
            WIREGUARD -> "wireguard"
            SOCKS -> "socks"
            HTTP -> "http"
            CUSTOM_JSON -> "custom"
        }

    companion object {
        /**
         * 根据链接协议头或 sing-box type 解析枚举，未知类型返回 null。
         */
        fun fromRaw(raw: String): ProtocolType? {
            return when (raw.lowercase().trim()) {
                "vmess" -> VMESS
                "vless" -> VLESS
                "trojan" -> TROJAN
                "ss", "shadowsocks" -> SHADOWSOCKS
                "hysteria2", "hy2" -> HYSTERIA2
                "tuic" -> TUIC
                "wireguard", "wg" -> WIREGUARD
                "socks", "socks5" -> SOCKS
                "http", "https" -> HTTP
                "custom", "custom_json" -> CUSTOM_JSON
                else -> null
            }
        }
    }
}
