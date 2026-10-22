package com.singflow.android.core.failover

import com.singflow.android.core.model.FailoverConfig
import com.singflow.android.core.model.FailoverPreset
import com.singflow.android.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动切换候选策略单元测试。
 */
class FailoverPolicyTest {

    private fun node(
        id: String,
        country: String,
        score: Int,
        latency: Int = 100,
        favorite: Boolean = false,
        cooldownUntil: Long = 0L
    ) = ProxyNode(
        id = id,
        name = id,
        server = "$id.example.com",
        port = 443,
        countryCode = country,
        healthScore = score,
        latencyMs = latency,
        isFavorite = favorite,
        cooldownUntilMs = cooldownUntil
    )

    private val current = node("current", "US", 70, 120)

    @Test
    fun `country locked prefers same country by health`() {
        val candidates = listOf(
            node("jp1", "JP", 95, 40),
            node("us1", "US", 85, 90),
            node("us2", "US", 60, 60)
        )
        val config = FailoverConfig(preset = FailoverPreset.COUNTRY_LOCKED_HEALTH)

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L)

        assertEquals(listOf("us1", "us2"), pool.map { it.id })
    }

    @Test
    fun `country locked falls back in configured country order`() {
        val candidates = listOf(
            node("hk1", "HK", 90, 50),
            node("jp1", "JP", 88, 60)
        )
        val config = FailoverConfig(
            preset = FailoverPreset.COUNTRY_LOCKED_HEALTH,
            fallbackCountryOrder = listOf("SG", "JP", "HK")
        )

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L)

        assertEquals(listOf("jp1"), pool.map { it.id })
    }

    @Test
    fun `country locked does not cross border when disabled`() {
        val candidates = listOf(node("jp1", "JP", 90, 50))
        val config = FailoverConfig(
            preset = FailoverPreset.COUNTRY_LOCKED_HEALTH,
            allowCrossCountryFallback = false
        )

        assertTrue(FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L).isEmpty())
    }

    @Test
    fun `favorites first keeps favorite pool ahead of higher score`() {
        val candidates = listOf(
            node("fav", "JP", 60, 200, favorite = true),
            node("best", "US", 95, 30)
        )
        val config = FailoverConfig(preset = FailoverPreset.FAVORITES_FIRST_FAST)

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L)

        assertEquals(listOf("fav"), pool.map { it.id })
    }

    @Test
    fun `favorites first degrades to high score pool when no favorite`() {
        val candidates = listOf(
            node("high", "JP", 80, 100),
            node("low", "US", 60, 50)
        )
        val config = FailoverConfig(preset = FailoverPreset.FAVORITES_FIRST_FAST)

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L)

        assertEquals(listOf("high"), pool.map { it.id })
    }

    @Test
    fun `global lowest latency ignores country and sorts by rtt`() {
        val candidates = listOf(
            node("fast", "JP", 60, 35),
            node("healthy", "US", 99, 300)
        )
        val config = FailoverConfig(preset = FailoverPreset.GLOBAL_LOWEST_LATENCY)

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L)

        assertEquals(listOf("fast", "healthy"), pool.map { it.id })
    }

    @Test
    fun `stable preset only allows same country`() {
        val candidates = listOf(
            node("jp1", "JP", 99, 20),
            node("us1", "US", 65, 150)
        )
        val config = FailoverConfig(preset = FailoverPreset.STABLE_HYSTERESIS_ONLY)

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 1_000L)

        assertEquals(listOf("us1"), pool.map { it.id })
    }

    @Test
    fun `cooling down and unhealthy nodes are excluded`() {
        val candidates = listOf(
            node("cool", "US", 99, 20, cooldownUntil = 10_000L),
            node("weak", "US", 44, 20),
            node("ok", "US", 70, 100)
        )
        val config = FailoverConfig(preset = FailoverPreset.COUNTRY_LOCKED_HEALTH)

        val pool = FailoverPolicy.candidates(current, candidates, config, nowMs = 5_000L)

        assertEquals(listOf("ok"), pool.map { it.id })
    }

    @Test
    fun `hysteresis requires both score and latency gains`() {
        val config = FailoverConfig(switchHysteresisScoreDiff = 12, switchLatencyToleranceMs = 80)
        val candidate = node("cand", "US", 82, 40)

        // 分数增益 12、延迟改善 80，刚好达标
        assertTrue(FailoverPolicy.shouldSwitch(current, candidate, config))
        // 分数增益不足
        assertFalse(FailoverPolicy.shouldSwitch(current, candidate.copy(healthScore = 81), config))
        // 延迟改善不足
        assertFalse(FailoverPolicy.shouldSwitch(current, candidate.copy(latencyMs = 41), config))
    }

    @Test
    fun `emergency switch ignores hysteresis and disabled config returns none`() {
        val candidates = listOf(node("cand", "US", 70, 120))
        val config = FailoverConfig(preset = FailoverPreset.COUNTRY_LOCKED_HEALTH)

        assertEquals("cand", FailoverPolicy.bestCandidate(current, candidates, config, isEmergency = true)?.id)
        assertNull(FailoverPolicy.bestCandidate(current, candidates, config, isEmergency = false))
        assertNull(
            FailoverPolicy.bestCandidate(
                current,
                candidates,
                config.copy(enabled = false),
                isEmergency = true
            )?.id
        )
    }

    @Test
    fun `unhealthy detection covers retries and slow latency`() {
        val config = FailoverConfig(failConfirmRetries = 2, slowLatencyThresholdMs = 850)
        assertTrue(FailoverPolicy.isNodeUnhealthy(current.copy(consecutiveFailures = 2), config))
        assertTrue(FailoverPolicy.isNodeUnhealthy(current.copy(latencyMs = 900), config))
        assertFalse(FailoverPolicy.isNodeUnhealthy(current.copy(latencyMs = 100), config))
    }
}

