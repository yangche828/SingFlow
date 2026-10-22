package com.singflow.android.core.probe

import com.singflow.android.core.failover.NodeStore
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.scorer.NodeHealthScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 批量探活结果。
 *
 * @property results 每个节点的探测结果
 * @property successCount 成功数量
 * @property failureCount 失败数量
 */
data class BatchProbeSummary(
    val results: List<ProbeResult>
) {
    val total: Int get() = results.size
    val successCount: Int get() = results.count { it.success }
    val failureCount: Int get() = results.count { !it.success }
}

/**
 * 批量并发探活引擎。
 *
 * 通过 [Semaphore] 严格限制并发握手数量（默认 12），避免上百节点同时拨号
 * 耗尽系统 socket 句柄或压垮家用路由器 NAT 表；每个节点完成后立即调用
 * [NodeHealthScorer] 写回健康度，因此 UI 可以边测边刷新。
 *
 * @param prober 单节点探活器
 * @param store 节点读写
 * @param concurrency 最大并发数
 * @param now 时间源（测试可注入）
 */
class BatchProbeEngine(
    private val prober: NodeProber,
    private val store: NodeStore,
    private val concurrency: Int = DEFAULT_CONCURRENCY,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 并发探测全部节点。
     *
     * @param nodes 待探测节点
     * @param timeoutMs 单次超时
     * @param cooldownMinutesOnDead 连续失败后的冷却时长
     * @param onProgress 每个节点完成时的回调（已完成的节点数、结果）
     */
    suspend fun probeAll(
        nodes: List<ProxyNode>,
        timeoutMs: Int = NodeProber.DEFAULT_TIMEOUT_MS,
        cooldownMinutesOnDead: Int = 10,
        onProgress: suspend (completed: Int, result: ProbeResult) -> Unit = { _, _ -> }
    ): BatchProbeSummary = coroutineScope {
        if (nodes.isEmpty()) return@coroutineScope BatchProbeSummary(emptyList())

        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val results = nodes.map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val result = prober.probe(node, timeoutMs)
                    store.update(
                        NodeHealthScorer.evaluateAfterProbe(
                            node = node,
                            isSuccess = result.success,
                            latencyMs = result.latencyMs,
                            detectedCountryCode = result.countryCode,
                            detectedCountryNameCn = result.countryNameCn,
                            detectedIp = result.egressIp,
                            cooldownMinutesOnDead = cooldownMinutesOnDead,
                            nowMs = now()
                        )
                    )
                    onProgress(completed.incrementAndGet(), result)
                    result
                }
            }
        }.awaitAll()
        BatchProbeSummary(results)
    }

    companion object {
        /** 默认并发度。 */
        const val DEFAULT_CONCURRENCY = 12
    }
}
