package com.singflow.android.core.model

import kotlinx.serialization.Serializable

/**
 * TLS / REALITY 配置，对应 sing-box outbound 的 `tls` 字段。
 *
 * @property enabled 是否启用 TLS
 * @property serverName SNI，留空时使用节点地址
 * @property insecure 是否跳过证书校验
 * @property alpn ALPN 列表
 * @property utlsFingerprint uTLS 指纹，默认 chrome
 * @property realityEnabled 是否启用 REALITY
 * @property realityPublicKey REALITY 公钥 (pbk)
 * @property realityShortId REALITY shortId (sid)
 * @property realitySpiderX REALITY spiderX (spx)
 */
@Serializable
data class TlsConfig(
    val enabled: Boolean = false,
    val serverName: String = "",
    val insecure: Boolean = false,
    val alpn: List<String> = emptyList(),
    val utlsFingerprint: String = "chrome",
    val realityEnabled: Boolean = false,
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = ""
) {
    /** 是否配置了 uTLS 指纹。 */
    val hasUtls: Boolean get() = utlsFingerprint.isNotBlank()

    companion object {
        val DEFAULT = TlsConfig()

        /** 构造一个标准的 TLS 配置。 */
        fun tls(
            serverName: String = "",
            insecure: Boolean = false,
            alpn: List<String> = emptyList(),
            fingerprint: String = "chrome"
        ): TlsConfig = TlsConfig(
            enabled = true,
            serverName = serverName,
            insecure = insecure,
            alpn = alpn,
            utlsFingerprint = fingerprint
        )
    }
}
