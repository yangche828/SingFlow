package com.singflow.android.core.probe

import com.singflow.android.core.model.ProxyNode

/**
 * 单节点探活器。
 *
 * 不同实现分别适用于：
 * - 当前节点：经本地 mixed 入站发起真实 HTTP 请求，可顺带识别落地国家；
 * - 候选节点：经 Clash API 的 `/delay` 在不切换出站的情况下测量 RTT。
 */
interface NodeProber {
    /**
     * 探测一个节点。
     *
     * @param node 目标节点
     * @param timeoutMs 单次超时
     */
    suspend fun probe(node: ProxyNode, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ProbeResult

    companion object {
        /** 默认单次探活超时。 */
        const val DEFAULT_TIMEOUT_MS = 3_000
    }
}
