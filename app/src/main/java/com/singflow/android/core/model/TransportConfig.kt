package com.singflow.android.core.model

import kotlinx.serialization.Serializable

/**
 * 传输层配置，对应 sing-box outbound 的 `transport` 字段。
 *
 * @property type tcp / ws / grpc / http / httpupgrade / quic / xhttp
 * @property host 传输层 Host（HTTP Host 头 / WS Host）
 * @property path 传输层路径
 * @property headers 自定义请求头
 * @property serviceName gRPC service name
 * @property maxEarlyData WS early data 上限
 * @property earlyDataHeaderName WS early data header 名称
 */
@Serializable
data class TransportConfig(
    val type: String = "tcp",
    val host: String = "",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val serviceName: String = "",
    val maxEarlyData: Int = 0,
    val earlyDataHeaderName: String = ""
) {
    /** 是否需要进行传输层编译。 */
    val isDefaultTcp: Boolean get() = type.isBlank() || type.equals("tcp", ignoreCase = true)

    companion object {
        val DEFAULT = TransportConfig()
    }
}
