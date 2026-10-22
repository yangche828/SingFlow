package com.singflow.android.core.probe

/**
 * 一次节点探测结果。
 *
 * @property nodeId 节点 ID
 * @property success 是否成功（拿到有效 HTTP 响应）
 * @property latencyMs 端到端 RTT（毫秒），失败为 -1
 * @property egressIp 实测落地出口 IP
 * @property countryCode 实测落地国家/地区代码
 * @property countryNameCn 实测落地中文名
 * @property error 失败原因
 * @property checkedAtMs 探测时间戳
 */
data class ProbeResult(
    val nodeId: String,
    val success: Boolean,
    val latencyMs: Int = -1,
    val egressIp: String? = null,
    val countryCode: String? = null,
    val countryNameCn: String? = null,
    val error: String? = null,
    val checkedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        /** 构造失败结果。 */
        fun failure(nodeId: String, error: String, checkedAtMs: Long = System.currentTimeMillis()): ProbeResult {
            return ProbeResult(
                nodeId = nodeId,
                success = false,
                error = error,
                checkedAtMs = checkedAtMs
            )
        }
    }
}
