package com.singflow.android.core.model

import java.security.MessageDigest
import java.util.Locale

/**
 * 节点连接指纹工具。
 *
 * 指纹只包含「决定是否同一个代理入口」的要素，不包含名称、延迟、健康度等可变属性，
 * 因此可用于订阅重复导入的幂等去重，也可以在分享链接被重新编码后保持稳定。
 */
object NodeFingerprint {

    /**
     * 计算节点连接指纹（SHA-256 前 128 位，32 位小写十六进制）。
     *
     * @param protocol 协议名（大小写不敏感）
     * @param host 服务器地址（大小写不敏感）
     * @param port 端口
     * @param credential 凭据（UUID / 密码 / 私钥等）
     * @param transport 传输层描述（类型 + host + path + serviceName）
     * @param sni TLS ServerName（大小写不敏感）
     */
    fun of(
        protocol: String,
        host: String,
        port: Int,
        credential: String,
        transport: String,
        sni: String
    ): String {
        val rawKey = buildString {
            append(protocol.lowercase(Locale.US)).append('|')
            append(host.trim().lowercase(Locale.US)).append('|')
            append(port).append('|')
            append(credential).append('|')
            append(transport).append('|')
            append(sni.trim().lowercase(Locale.US))
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }.take(32)
    }

    /**
     * 从节点字段中挑选真正用于鉴权的凭据。
     */
    fun credential(node: ProxyNode): String = when (node.protocol) {
        ProtocolType.VMESS, ProtocolType.VLESS, ProtocolType.TUIC -> node.uuid
        ProtocolType.SHADOWSOCKS -> "${node.method}:${node.password}"
        ProtocolType.WIREGUARD -> node.wgPeerPublicKey
        else -> node.password
    }

    /**
     * 传输层指纹描述：只保留会影响服务端握手的字段。
     */
    fun transport(node: ProxyNode): String {
        val transport = node.transport
        if (transport.isDefaultTcp) return "tcp"
        return listOf(
            transport.type.lowercase(Locale.US),
            transport.host,
            transport.path,
            transport.serviceName
        ).joinToString(":")
    }
}
