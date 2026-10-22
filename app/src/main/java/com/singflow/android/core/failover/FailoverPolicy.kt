package com.singflow.android.core.failover

import com.singflow.android.core.model.FailoverConfig
import com.singflow.android.core.model.FailoverPreset
import com.singflow.android.core.model.ProxyNode
import java.util.Locale

/**
 * 自动切换候选选择策略。
 *
 * 本对象只做纯函数决策，不执行探活、不写数据库、不调用内核，
 * 因此可以完整地在 JVM 单元测试中验证四大预设与迟滞防抖门槛。
 */
object FailoverPolicy {

    /** 参与自动切换的最低健康分。 */
    const val MIN_HEALTH_SCORE = 45

    /**
     * 计算候选节点池，按优先级排序。
     *
     * @param current 当前节点
     * @param healthy 全量节点（内部会过滤冷却中、低分与自身）
     * @param config 切换配置
     * @param nowMs 当前时间
     */
    fun candidates(
        current: ProxyNode,
        healthy: List<ProxyNode>,
        config: FailoverConfig,
        nowMs: Long = System.currentTimeMillis()
    ): List<ProxyNode> {
        val available = healthy
            .filter { it.id != current.id }
            .filter { !it.isCoolingDown(nowMs) }
            .filter { it.healthScore >= MIN_HEALTH_SCORE }
            .filter { it.server.isNotBlank() && it.port in 1..65535 }

        if (available.isEmpty()) return emptyList()

        val ranked = available.sortedWith(compareByDescending<ProxyNode> { it.isFavorite }
            .thenByDescending { it.healthScore }
            .thenBy { effectiveLatency(it) })

        return when (config.preset) {
            FailoverPreset.COUNTRY_LOCKED_HEALTH -> countryLocked(current, ranked, config)
            FailoverPreset.FAVORITES_FIRST_FAST -> {
                val favorites = ranked.filter { it.isFavorite }
                favorites.ifEmpty { ranked.filter { it.healthScore >= 75 } }
            }
            FailoverPreset.GLOBAL_LOWEST_LATENCY ->
                ranked.sortedWith(compareBy<ProxyNode> { effectiveLatency(it) }.thenByDescending { it.healthScore })
            FailoverPreset.STABLE_HYSTERESIS_ONLY -> {
                val sameCountry = sameCountry(current, ranked)
                sameCountry.ifEmpty { ranked }
            }
        }
    }

    /**
     * 选择最优候选；断网紧急切换时不检查迟滞门槛。
     */
    fun bestCandidate(
        current: ProxyNode,
        healthy: List<ProxyNode>,
        config: FailoverConfig,
        isEmergency: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ProxyNode? {
        if (!config.enabled) return null
        for (candidate in candidates(current, healthy, config, nowMs)) {
            if (isEmergency || shouldSwitch(current, candidate, config)) return candidate
        }
        return null
    }

    /**
     * 非故障切换必须同时满足健康分增益与延迟改善两个门槛，避免频繁抖动。
     */
    fun shouldSwitch(current: ProxyNode, candidate: ProxyNode, config: FailoverConfig): Boolean {
        val scoreGain = candidate.healthScore - current.healthScore
        if (scoreGain < config.switchHysteresisScoreDiff) return false
        val currentLatency = effectiveLatency(current)
        val candidateLatency = effectiveLatency(candidate)
        if (currentLatency <= 0) return true
        return currentLatency - candidateLatency >= config.switchLatencyToleranceMs
    }

    /**
     * 当前节点是否应判定为异常（连续失败达到确认次数，或超过慢速阈值）。
     */
    fun isNodeUnhealthy(node: ProxyNode, config: FailoverConfig): Boolean {
        if (node.consecutiveFailures >= config.failConfirmRetries) return true
        return node.latencyMs > 0 && node.latencyMs > config.slowLatencyThresholdMs
    }

    private fun countryLocked(
        current: ProxyNode,
        ranked: List<ProxyNode>,
        config: FailoverConfig
    ): List<ProxyNode> {
        val sameCountry = sameCountry(current, ranked)
        if (sameCountry.isNotEmpty() || !config.allowCrossCountryFallback) return sameCountry

        for (code in config.normalizedFallbackOrder()) {
            if (code == current.countryCode.uppercase(Locale.US)) continue
            val pool = ranked.filter { it.countryCode.uppercase(Locale.US) == code }
            if (pool.isNotEmpty()) return pool
        }
        return ranked
    }

    private fun sameCountry(current: ProxyNode, ranked: List<ProxyNode>): List<ProxyNode> {
        val code = current.countryCode.trim().uppercase(Locale.US)
        if (code.isBlank() || code == "UN") return emptyList()
        return ranked.filter { it.countryCode.trim().uppercase(Locale.US) == code }
    }

    private fun effectiveLatency(node: ProxyNode): Int = when {
        node.latencyMs > 0 -> node.latencyMs
        node.emaLatencyMs > 0f -> node.emaLatencyMs.toInt()
        else -> Int.MAX_VALUE
    }
}

