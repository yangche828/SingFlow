package com.singflow.android.core.probe

import com.singflow.android.core.model.ProxyNode

/**
 * 经 Clash API `delay` 接口探测任意节点的探活器。
 *
 * 不会改变当前出站选择，因此适合在保持现有流量的同时批量评估候选节点。
 *
 * @param clashApi Clash API 客户端
 * @param tagProvider 节点 ID → 出站 tag 的映射；多节点配置为 `node-{id}`
 */
class ClashApiNodeProber(
    private val clashApi: ClashApiClient,
    private val tagProvider: (ProxyNode) -> String = { "node-${it.id}" }
) : NodeProber {

    override suspend fun probe(node: ProxyNode, timeoutMs: Int): ProbeResult {
        val tag = tagProvider(node)
        val latency = clashApi.delay(tag, ClashApiClient.DEFAULT_TEST_URL, timeoutMs)
            ?: return ProbeResult.failure(node.id, "Clash API delay 失败（tag=$tag）")
        return ProbeResult(
            nodeId = node.id,
            success = true,
            latencyMs = latency
        )
    }
}
