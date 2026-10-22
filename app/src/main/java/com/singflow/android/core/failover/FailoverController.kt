package com.singflow.android.core.failover

import com.singflow.android.core.model.FailoverConfig
import com.singflow.android.core.model.FailoverPreset
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.probe.NodeProber
import com.singflow.android.core.probe.LatencyHistoryStore
import com.singflow.android.core.probe.ProbeResult
import com.singflow.android.core.scorer.NodeHealthScorer
import kotlinx.coroutines.delay

/**
 * 故障自动切换执行器。
 *
 * 一次巡检的闭环：
 * 1. 带重试地探测当前节点（真实 HTTP，而不是 TCP 握手）；
 * 2. 失败时先做本机断网甄别，手机自身没网则**不扣分、不切换**；
 * 3. 坐实节点故障后写入健康分与冷却期；
 * 4. 按预设方向挑选候选，逐个实测确认后通过 Selector 内存态切换，不重建 TUN；
 * 5. 当前节点健康时按迟滞门槛做择优切换。
 *
 * 所有外部能力（节点读写、探活、切换、本机网络判定）均通过接口注入，
 * 因此可以在 JVM 单测中覆盖全部分支。
 *
 * @param store 节点读写
 * @param currentProber 当前节点探活器（经本地 mixed 入站）
 * @param candidateProber 候选节点探活器（经 Clash API delay）
 * @param switcher Selector 切换器
 * @param localNetwork 本机底层网络判定
 * @param sleeper 重试间隔（测试可注入）
 * @param now 时间源（测试可注入）
 */
class FailoverController(
    private val store: NodeStore,
    private val currentProber: NodeProber,
    private val candidateProber: NodeProber,
    private val switcher: OutboundSwitcher,
    private val localNetwork: LocalNetworkVerifier,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * 对当前节点执行一次健康校验与按需切换。
     *
     * @param groupTag Selector 组 tag（多节点配置默认 `proxy`）
     * @param currentNode 当前承载流量的节点
     * @param config 自动切换配置
     */
    suspend fun checkAndFailover(
        groupTag: String,
        currentNode: ProxyNode,
        config: FailoverConfig
    ): FailoverOutcome {
        if (!config.enabled) {
            return FailoverOutcome(FailoverOutcome.Action.SKIPPED, message = "自动切换已关闭")
        }

        val timeout = config.singleProbeTimeoutMs.coerceIn(
            FailoverConfig.PROBE_TIMEOUT_RANGE.first,
            FailoverConfig.PROBE_TIMEOUT_RANGE.last
        )
        val retries = config.failConfirmRetries.coerceIn(
            FailoverConfig.FAIL_CONFIRM_RANGE.first,
            FailoverConfig.FAIL_CONFIRM_RANGE.last
        )

        var currentResult: ProbeResult? = null
        var currentAlive = false
        for (attempt in 1..retries) {
            val result = currentProber.probe(currentNode, timeout)
            currentResult = result
            // 只要拿到有效响应就算存活；超过慢速阈值只影响择优切换，
            // 不能当成「节点已死」反复扣分（否则健康分会长期归零）。
            if (result.success) {
                currentAlive = true
                break
            }
            if (attempt < retries) sleeper(RETRY_INTERVAL_MS)
        }

        if (currentAlive) {
            LatencyHistoryStore.record(currentNode.id, currentResult?.latencyMs ?: -1)
        }

        return if (!currentAlive) {
            handleCurrentFailure(groupTag, currentNode, config)
        } else {
            handleCurrentHealthy(groupTag, currentNode, config, currentResult!!)
        }
    }

    /**
     * 当前节点探测失败：先甄别本机网络，再扣分并紧急切换。
     */
    private suspend fun handleCurrentFailure(
        groupTag: String,
        currentNode: ProxyNode,
        config: FailoverConfig
    ): FailoverOutcome {
        if (!localNetwork.isLocalNetworkUsable()) {
            return FailoverOutcome(
                action = FailoverOutcome.Action.LOCAL_NETWORK_DOWN,
                from = currentNode,
                message = "本机底层网络不可用，跳过扣分与切换"
            )
        }

        val penalized = NodeHealthScorer.evaluateAfterProbe(
            node = currentNode,
            isSuccess = false,
            latencyMs = -1,
            cooldownMinutesOnDead = config.deadNodeCooldownMinutes,
            nowMs = now()
        )
        store.update(penalized)

        val candidates = loadCandidates(penalized, config)
        if (candidates.isEmpty()) {
            return FailoverOutcome(
                action = FailoverOutcome.Action.NO_CANDIDATE,
                from = penalized,
                message = "当前节点已判定故障，但没有可用候选节点"
            )
        }

        return switchToVerified(groupTag, penalized, candidates, config)
    }

    /**
     * 当前节点健康：更新评分，再按预设与迟滞门槛决定是否择优切换。
     */
    private suspend fun handleCurrentHealthy(
        groupTag: String,
        currentNode: ProxyNode,
        config: FailoverConfig,
        result: ProbeResult
    ): FailoverOutcome {
        val updated = NodeHealthScorer.evaluateAfterProbe(
            node = currentNode,
            isSuccess = true,
            latencyMs = result.latencyMs,
            detectedCountryCode = result.countryCode,
            detectedCountryNameCn = result.countryNameCn,
            detectedIp = result.egressIp,
            nowMs = now()
        )
        store.update(updated)

        if (config.preset == FailoverPreset.STABLE_HYSTERESIS_ONLY) {
            return FailoverOutcome(
                action = FailoverOutcome.Action.HEALTHY,
                from = updated,
                message = "稳健防抖模式：当前节点健康，保持连接"
            )
        }

        val candidates = loadCandidates(updated, config)
            .filter { FailoverPolicy.shouldSwitch(updated, it, config) }
        if (candidates.isEmpty()) {
            return FailoverOutcome(
                action = FailoverOutcome.Action.HEALTHY,
                from = updated,
                message = "当前节点健康，未达到择优切换门槛"
            )
        }

        return switchToVerified(groupTag, updated, candidates, config)
    }

    /**
     * 逐个实测候选（最多 [MAX_CONFIRM_CANDIDATES] 个），确认在线后切换。
     */
    private suspend fun switchToVerified(
        groupTag: String,
        currentNode: ProxyNode,
        candidates: List<ProxyNode>,
        config: FailoverConfig
    ): FailoverOutcome {
        val timeout = config.singleProbeTimeoutMs.coerceIn(
            FailoverConfig.PROBE_TIMEOUT_RANGE.first,
            FailoverConfig.PROBE_TIMEOUT_RANGE.last
        )
        for (candidate in candidates.take(MAX_CONFIRM_CANDIDATES)) {
            val result = candidateProber.probe(candidate, timeout)
            if (!result.success) {
                store.update(
                    NodeHealthScorer.evaluateAfterProbe(
                        node = candidate,
                        isSuccess = false,
                        latencyMs = -1,
                        cooldownMinutesOnDead = config.deadNodeCooldownMinutes,
                        nowMs = now()
                    )
                )
                continue
            }

            val verified = NodeHealthScorer.evaluateAfterProbe(
                node = candidate,
                isSuccess = true,
                latencyMs = result.latencyMs,
                detectedCountryCode = result.countryCode,
                detectedCountryNameCn = result.countryNameCn,
                detectedIp = result.egressIp,
                nowMs = now()
            )
            store.update(verified)
            if (switcher.switchTo(groupTag, verified)) {
                return FailoverOutcome(
                    action = FailoverOutcome.Action.SWITCHED,
                    from = currentNode,
                    to = verified,
                    message = "已切换至 ${verified.name}（${result.latencyMs}ms）"
                )
            }
        }

        return FailoverOutcome(
            action = FailoverOutcome.Action.NO_CANDIDATE,
            from = currentNode,
            message = "候选节点均未通过实测确认或 Selector 切换失败"
        )
    }

    /**
     * 读取健康候选并按预设排序。
     */
    private suspend fun loadCandidates(
        currentNode: ProxyNode,
        config: FailoverConfig
    ): List<ProxyNode> {
        val healthy = store.healthyCandidates(now(), FailoverPolicy.MIN_HEALTH_SCORE)
        return FailoverPolicy.candidates(currentNode, healthy, config, now())
    }

    private companion object {
        /** 失败重试间隔（毫秒）。 */
        const val RETRY_INTERVAL_MS = 400L

        /** 单次巡检最多确认的候选数量。 */
        const val MAX_CONFIRM_CANDIDATES = 3
    }
}

/**
 * 一次巡检结果。
 *
 * @property action 结果类型
 * @property from 巡检前的当前节点（已写回新评分）
 * @property to 切换后的节点（仅 [Action.SWITCHED] 时非空）
 * @property message 中文可读说明
 */
data class FailoverOutcome(
    val action: Action,
    val from: ProxyNode? = null,
    val to: ProxyNode? = null,
    val message: String
) {
    /** 巡检结果类型。 */
    enum class Action {
        /** 自动切换关闭。 */
        SKIPPED,

        /** 当前节点健康且无需切换。 */
        HEALTHY,

        /** 已完成切换。 */
        SWITCHED,

        /** 本机断网，未扣分也未切换。 */
        LOCAL_NETWORK_DOWN,

        /** 当前节点故障但无可用候选。 */
        NO_CANDIDATE
    }
}
