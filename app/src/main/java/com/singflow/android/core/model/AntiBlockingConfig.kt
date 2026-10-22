package com.singflow.android.core.model

import kotlinx.serialization.Serializable

/**
 * 抗封锁增强配置（全局生效）。
 *
 * @property tlsFragment 是否启用 TLS ClientHello 分片
 * @property utlsFingerprint 全局 uTLS 指纹（空表示沿用节点自身配置）
 * @property multiplexEnabled 是否启用多路复用
 * @property multiplexProtocol 复用协议：h2mux / smux / yamux
 * @property multiplexPadding 是否对复用帧填充
 * @property multiplexMaxStreams 单连接最大并发流
 */
@Serializable
data class AntiBlockingConfig(
    val tlsFragment: Boolean = false,
    val utlsFingerprint: String = "",
    val multiplexEnabled: Boolean = false,
    val multiplexProtocol: String = "h2mux",
    val multiplexPadding: Boolean = false,
    val multiplexMaxStreams: Int = 8
) {
    companion object {
        /** 默认关闭。 */
        val DEFAULT = AntiBlockingConfig()

        /** 可选 uTLS 指纹（空字符串表示默认）。 */
        val UTLS_OPTIONS = listOf(
            "" to "默认（节点自带）",
            "chrome" to "Chrome",
            "firefox" to "Firefox",
            "safari" to "Safari",
            "ios" to "iOS",
            "android" to "Android",
            "edge" to "Edge",
            "360" to "360",
            "qq" to "QQ",
            "random" to "随机"
        )

        /** 可选复用协议。 */
        val MULTIPLEX_PROTOCOLS = listOf("h2mux", "smux", "yamux")
    }
}
