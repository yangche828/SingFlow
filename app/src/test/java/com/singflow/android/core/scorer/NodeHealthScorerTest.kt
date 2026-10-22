package com.singflow.android.core.scorer

import com.singflow.android.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 健康度评分引擎单元测试。
 */
class NodeHealthScorerTest {

    private val baseNode = ProxyNode(
        id = "n1",
        name = "测试节点",
        server = "example.com",
        port = 443
    )

    @Test
    fun `first success yields healthy score and records latency`() {
        val updated = NodeHealthScorer.evaluateAfterProbe(
            node = baseNode,
            isSuccess = true,
            latencyMs = 60,
            nowMs = 1_000L
        )

        assertEquals(1, updated.probeTotalCount)
        assertEquals(1, updated.probeSuccessCount)
        assertEquals(0, updated.consecutiveFailures)
        assertEquals(60, updated.latencyMs)
        assertEquals(60f, updated.emaLatencyMs, 0.001f)
        assertEquals(0f, updated.latencyJitterMs, 0.001f)
        assertEquals(78, updated.healthScore)
        assertEquals("健康", updated.healthTag)
        assertEquals(1_000L, updated.lastProbeTimeMs)
        assertFalse(updated.isCoolingDown(1_000L))
    }

    @Test
    fun `single failure is penalized but does not trigger cooldown`() {
        val updated = NodeHealthScorer.evaluateAfterProbe(
            node = baseNode,
            isSuccess = false,
            latencyMs = -1,
            nowMs = 2_000L
        )

        assertEquals(1, updated.probeTotalCount)
        assertEquals(0, updated.probeSuccessCount)
        assertEquals(1, updated.consecutiveFailures)
        assertEquals(-1, updated.latencyMs)
        assertEquals(22, updated.healthScore)
        assertEquals("异常", updated.healthTag)
        assertEquals(0L, updated.cooldownUntilMs)
    }

    @Test
    fun `two consecutive failures enter cooldown`() {
        val first = NodeHealthScorer.evaluateAfterProbe(baseNode, false, -1, nowMs = 10_000L)
        val second = NodeHealthScorer.evaluateAfterProbe(
            node = first,
            isSuccess = false,
            latencyMs = -1,
            cooldownMinutesOnDead = 10,
            nowMs = 20_000L
        )

        assertEquals(2, second.consecutiveFailures)
        assertEquals(0, second.healthScore)
        assertEquals(620_000L, second.cooldownUntilMs)
        assertTrue(second.isCoolingDown(100_000L))
        assertFalse(second.isCoolingDown(700_000L))
    }

    @Test
    fun `success after failures resets streak and cooldown`() {
        val failed = baseNode.copy(
            probeTotalCount = 2,
            probeSuccessCount = 0,
            consecutiveFailures = 2,
            cooldownUntilMs = 999_999L,
            healthScore = 0,
            healthTag = "异常"
        )
        val recovered = NodeHealthScorer.evaluateAfterProbe(
            node = failed,
            isSuccess = true,
            latencyMs = 100,
            nowMs = 30_000L
        )

        assertEquals(0, recovered.consecutiveFailures)
        assertEquals(0L, recovered.cooldownUntilMs)
        assertEquals(3, recovered.probeTotalCount)
        assertEquals(1, recovered.probeSuccessCount)
        assertEquals(64, recovered.healthScore)
        assertEquals("一般", recovered.healthTag)
    }

    @Test
    fun `ema latency and jitter follow exponential smoothing`() {
        val previous = baseNode.copy(emaLatencyMs = 100f, latencyJitterMs = 20f)
        val updated = NodeHealthScorer.evaluateAfterProbe(
            node = previous,
            isSuccess = true,
            latencyMs = 200,
            nowMs = 1L
        )

        assertEquals(130f, updated.emaLatencyMs, 0.001f)
        // 0.7 * 20 + 0.3 * |200 - 130| = 14 + 21 = 35
        assertEquals(35f, updated.latencyJitterMs, 0.001f)
    }

    @Test
    fun `detected country overwrites region only when provided`() {
        val localized = NodeHealthScorer.evaluateAfterProbe(
            node = baseNode,
            isSuccess = true,
            latencyMs = 50,
            detectedCountryCode = "JP",
            detectedCountryNameCn = "日本",
            detectedIp = "104.28.1.1",
            nowMs = 1L
        )

        assertEquals("JP", localized.countryCode)
        assertEquals("日本", localized.countryNameCn)
        assertEquals("104.28.1.1", localized.egressIp)
        assertTrue(localized.isRegionVerified)
    }

    @Test
    fun `score is clamped between zero and one hundred`() {
        assertEquals(0, NodeHealthScorer.computeScore(50, 0, 10, 0f, 0f))
        val perfect = NodeHealthScorer.computeScore(20, 20, 0, 10f, 0f)
        assertEquals(98, perfect)
        assertTrue(perfect <= NodeHealthScorer.MAX_SCORE)
    }

    @Test
    fun `tag thresholds match documented levels`() {
        assertEquals("极佳", NodeHealthScorer.tagFor(85, 0))
        assertEquals("健康", NodeHealthScorer.tagFor(70, 0))
        assertEquals("一般", NodeHealthScorer.tagFor(50, 1))
        assertEquals("异常", NodeHealthScorer.tagFor(49, 1))
        assertEquals("一般", NodeHealthScorer.tagFor(95, 1))
    }
}

