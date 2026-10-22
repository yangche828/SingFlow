package com.singflow.android.core.scorer

import com.singflow.android.core.model.ProxyNode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 节点健康度评分引擎。
 *
 * 评分由五个维度组成：
 * 1. 拉普拉斯平滑成功率（权重 55），避免新节点测一次成功就满分；
 * 2. 低延迟得分（权重 30，80 ms 满分、1200 ms 归零）；
 * 3. 抗抖动得分（权重 10，抖动 300 ms 归零）；
 * 4. 样本置信度（权重 5，累计 20 次拿满）；
 * 5. 连续失败惩罚（每次 18 分），让刚断连的节点分数断崖下跌以触发容灾。
 *
 * 评分函数为纯函数：输入旧节点与本次探测结果，输出新节点，便于单元测试。
 */
object NodeHealthScorer {

    /** 满分阈值。 */
    const val MAX_SCORE = 100

    /** 延迟满分阈值（ms）。 */
    const val LATENCY_FULL_SCORE_MS = 80f

    /** 延迟归零阈值（ms）。 */
    const val LATENCY_ZERO_SCORE_MS = 1200f

    /** 抖动归零阈值（ms）。 */
    const val JITTER_ZERO_SCORE_MS = 300f

    /** 指数移动平均系数：历史 0.7 / 本次 0.3。 */
    private const val EMA_OLDER_WEIGHT = 0.7f

    /** 最近一次样本权重。 */
    private const val EMA_NEW_WEIGHT = 0.3f

    /** 成功率权重。 */
    private const val RATE_WEIGHT = 55.0

    /** 延迟权重。 */
    private const val LATENCY_WEIGHT = 30.0

    /** 抖动权重。 */
    private const val JITTER_WEIGHT = 10.0

    /** 置信度权重。 */
    private const val CONFIDENCE_WEIGHT = 5.0

    /** 连续失败单次罚分。 */
    private const val FAILURE_PENALTY = 18.0

    /** 进入冷却期的连续失败次数。 */
    const val COOLDOWN_FAILURE_THRESHOLD = 2

    /**
     * 根据一次探测结果计算新的节点状态。
     *
     * @param node 探测前的节点
     * @param isSuccess 本次是否成功
     * @param latencyMs 本次实测 RTT（失败时忽略）
     * @param detectedCountryCode 实测落地国家代码（成功且识别成功时传入）
     * @param detectedCountryNameCn 实测落地中文名
     * @param detectedIp 实测落地 IP
     * @param cooldownMinutesOnDead 连续失败达到阈值后的冷却时长（分钟）
     * @param nowMs 当前时间戳（注入以便测试）
     */
    fun evaluateAfterProbe(
        node: ProxyNode,
        isSuccess: Boolean,
        latencyMs: Int,
        detectedCountryCode: String? = null,
        detectedCountryNameCn: String? = null,
        detectedIp: String? = null,
        cooldownMinutesOnDead: Int = 10,
        nowMs: Long = System.currentTimeMillis()
    ): ProxyNode {
        val newTotal = node.probeTotalCount + 1
        val newSuccess = if (isSuccess) node.probeSuccessCount + 1 else node.probeSuccessCount
        val newStreakFail = if (isSuccess) 0 else node.consecutiveFailures + 1
        val hasLatency = isSuccess && latencyMs > 0

        val newEmaLatency = if (hasLatency) {
            if (node.emaLatencyMs <= 0f) {
                latencyMs.toFloat()
            } else {
                EMA_OLDER_WEIGHT * node.emaLatencyMs + EMA_NEW_WEIGHT * latencyMs.toFloat()
            }
        } else {
            node.emaLatencyMs
        }

        val newJitter = if (hasLatency) {
            EMA_OLDER_WEIGHT * node.latencyJitterMs +
                EMA_NEW_WEIGHT * abs(latencyMs - newEmaLatency)
        } else {
            node.latencyJitterMs
        }

        val score = computeScore(
            totalCount = newTotal,
            successCount = newSuccess,
            streakFailures = newStreakFail,
            emaLatencyMs = newEmaLatency,
            jitterMs = newJitter
        )

        val cooldownUntil = when {
            newStreakFail >= COOLDOWN_FAILURE_THRESHOLD ->
                nowMs + cooldownMinutesOnDead.coerceIn(1, 60) * 60_000L
            isSuccess -> 0L
            else -> node.cooldownUntilMs
        }

        return node.copy(
            probeTotalCount = newTotal,
            probeSuccessCount = newSuccess,
            consecutiveFailures = newStreakFail,
            latencyMs = if (isSuccess) latencyMs else -1,
            emaLatencyMs = newEmaLatency,
            latencyJitterMs = newJitter,
            healthScore = score,
            healthTag = tagFor(score, newStreakFail),
            lastProbeTimeMs = nowMs,
            cooldownUntilMs = cooldownUntil,
            countryCode = detectedCountryCode ?: node.countryCode,
            countryNameCn = detectedCountryNameCn ?: node.countryNameCn,
            egressIp = detectedIp ?: node.egressIp,
            isRegionVerified = if (detectedCountryCode != null) true else node.isRegionVerified
        )
    }

    /**
     * 纯数学评分。
     */
    fun computeScore(
        totalCount: Int,
        successCount: Int,
        streakFailures: Int,
        emaLatencyMs: Float,
        jitterMs: Float
    ): Int {
        val smoothRate = (successCount + 1.0) / (totalCount + 2.0)
        val rateScore = RATE_WEIGHT * smoothRate

        val effectiveLatency = if (emaLatencyMs > 0f) emaLatencyMs else 800f
        val latencyRatio = ((LATENCY_ZERO_SCORE_MS - effectiveLatency) /
            (LATENCY_ZERO_SCORE_MS - LATENCY_FULL_SCORE_MS)).coerceIn(0f, 1f)
        val latencyScore = LATENCY_WEIGHT * latencyRatio

        val jitterRatio = (1f - jitterMs / JITTER_ZERO_SCORE_MS).coerceIn(0f, 1f)
        val jitterScore = JITTER_WEIGHT * jitterRatio

        val confidenceScore = CONFIDENCE_WEIGHT *
            min(1.0, ln((totalCount + 1).toDouble()) / ln(21.0))

        val penalty = streakFailures * FAILURE_PENALTY

        return (rateScore + latencyScore + jitterScore + confidenceScore - penalty)
            .roundToInt()
            .coerceIn(0, MAX_SCORE)
    }

    /**
     * 根据分数与连续失败次数给出中文标签。
     */
    fun tagFor(score: Int, streakFailures: Int): String = when {
        score >= 85 && streakFailures == 0 -> "极佳"
        score >= 70 && streakFailures == 0 -> "健康"
        score >= 50 && streakFailures <= 1 -> "一般"
        else -> "异常"
    }
}

