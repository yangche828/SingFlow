package com.singflow.android.core.failover

import com.singflow.android.core.model.FailoverConfig
import com.singflow.android.core.model.FailoverPreset
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.probe.NodeProber
import com.singflow.android.core.probe.ProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故障切换执行器全分支测试（全部依赖以 fake 注入，无 Android/Room 依赖）。
 */
class FailoverControllerTest {

    private val now = 1_000_000L

    private fun node(
        id: String,
        country: String,
        score: Int,
        latency: Int,
        favorite: Boolean = false
    ) = ProxyNode(
        id = id,
        name = id,
        server = "$id.example.com",
        port = 443,
        countryCode = country,
        countryNameCn = country,
        healthScore = score,
        latencyMs = latency,
        isFavorite = favorite
    )

    private class FakeStore(initial: List<ProxyNode>) : NodeStore {
        val updated = mutableListOf<ProxyNode>()
        private val nodes = initial.toMutableList()

        override suspend fun update(node: ProxyNode) {
            updated += node
            val index = nodes.indexOfFirst { it.id == node.id }
            if (index >= 0) nodes[index] = node else nodes += node
        }

        override suspend fun healthyCandidates(nowMs: Long, minScore: Int): List<ProxyNode> {
            return nodes.filter { it.healthScore >= minScore && it.cooldownUntilMs <= nowMs }
        }
    }

    private class FakeProber(private val handler: suspend (ProxyNode) -> ProbeResult) : NodeProber {
        val probed = mutableListOf<String>()

        override suspend fun probe(node: ProxyNode, timeoutMs: Int): ProbeResult {
            probed += node.id
            return handler(node)
        }
    }

    private class RecordingSwitcher(private var succeed: Boolean = true) : OutboundSwitcher {
        var lastGroup: String? = null
        var lastNode: ProxyNode? = null

        override suspend fun switchTo(groupTag: String, node: ProxyNode): Boolean {
            lastGroup = groupTag
            lastNode = node
            return succeed
        }
    }

    private fun controller(
        store: FakeStore,
        currentProber: NodeProber,
        candidateProber: NodeProber,
        switcher: OutboundSwitcher,
        localUsable: Boolean
    ) = FailoverController(
        store = store,
        currentProber = currentProber,
        candidateProber = candidateProber,
        switcher = switcher,
        localNetwork = object : LocalNetworkVerifier {
            override fun isLocalNetworkUsable(): Boolean = localUsable
        },
        sleeper = { },
        now = { now }
    )

    @Test
    fun `disabled config skips everything`() = runTest {
        val current = node("us1", "US", 70, 100)
        val store = FakeStore(listOf(current))
        val controller = controller(
            store,
            FakeProber { ProbeResult(current.id, true, 50) },
            FakeProber { ProbeResult(it.id, true, 50) },
            RecordingSwitcher(),
            localUsable = true
        )

        val outcome = controller.checkAndFailover(
            "proxy",
            current,
            FailoverConfig(enabled = false)
        )

        assertEquals(FailoverOutcome.Action.SKIPPED, outcome.action)
        assertTrue(store.updated.isEmpty())
    }

    @Test
    fun `local network down never penalizes or switches`() = runTest {
        val current = node("us1", "US", 70, 100)
        val store = FakeStore(listOf(current))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult.failure(it.id, "timeout") },
            FakeProber { ProbeResult(it.id, true, 50) },
            switcher,
            localUsable = false
        )

        val outcome = controller.checkAndFailover("proxy", current, FailoverConfig())

        assertEquals(FailoverOutcome.Action.LOCAL_NETWORK_DOWN, outcome.action)
        assertTrue(store.updated.isEmpty())
        assertNull(switcher.lastNode)
    }

    @Test
    fun `healthy current updates score and country then stays`() = runTest {
        val current = node("us1", "US", 70, 100)
        val store = FakeStore(listOf(current))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber {
                ProbeResult(
                    nodeId = it.id,
                    success = true,
                    latencyMs = 60,
                    egressIp = "203.0.113.1",
                    countryCode = "JP",
                    countryNameCn = "日本"
                )
            },
            FakeProber { ProbeResult(it.id, true, 50) },
            switcher,
            localUsable = true
        )

        val outcome = controller.checkAndFailover("proxy", current, FailoverConfig())

        assertEquals(FailoverOutcome.Action.HEALTHY, outcome.action)
        assertEquals(1, store.updated.size)
        val saved = store.updated.first()
        assertEquals(1, saved.probeTotalCount)
        assertEquals("JP", saved.countryCode)
        assertEquals("203.0.113.1", saved.egressIp)
        assertNull(switcher.lastNode)
    }

    @Test
    fun `better candidate triggers hysteresis switch`() = runTest {
        val current = node("us1", "US", 70, 100)
        val candidate = node("us2", "US", 90, 10)
        val store = FakeStore(listOf(current, candidate))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult(it.id, true, 100) },
            FakeProber { ProbeResult(it.id, true, 10) },
            switcher,
            localUsable = true
        )

        val outcome = controller.checkAndFailover("proxy", current, FailoverConfig())

        assertEquals(FailoverOutcome.Action.SWITCHED, outcome.action)
        assertEquals("proxy", switcher.lastGroup)
        assertEquals("us2", switcher.lastNode?.id)
        assertEquals("us2", outcome.to?.id)
        // 当前节点与候选节点都写回
        assertEquals(2, store.updated.size)
    }

    @Test
    fun `current failure confirms candidate before switching`() = runTest {
        val current = node("us1", "US", 80, 100)
        val candidate = node("jp1", "JP", 88, 40)
        val store = FakeStore(listOf(current, candidate))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult.failure(it.id, "timeout") },
            FakeProber { ProbeResult(it.id, true, 40) },
            switcher,
            localUsable = true
        )

        val outcome = controller.checkAndFailover(
            "proxy",
            current,
            FailoverConfig(preset = FailoverPreset.COUNTRY_LOCKED_HEALTH)
        )

        assertEquals(FailoverOutcome.Action.SWITCHED, outcome.action)
        assertEquals("jp1", outcome.to?.id)
        val penalized = store.updated.first { it.id == "us1" }
        assertEquals(1, penalized.consecutiveFailures)
        assertTrue(penalized.healthScore < current.healthScore)
    }

    @Test
    fun `failed candidate probes end with no candidate`() = runTest {
        val current = node("us1", "US", 80, 100)
        val candidate = node("jp1", "JP", 88, 40)
        val store = FakeStore(listOf(current, candidate))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult.failure(it.id, "timeout") },
            FakeProber { ProbeResult.failure(it.id, "timeout") },
            switcher,
            localUsable = true
        )

        val outcome = controller.checkAndFailover("proxy", current, FailoverConfig())

        assertEquals(FailoverOutcome.Action.NO_CANDIDATE, outcome.action)
        assertNull(switcher.lastNode)
        assertTrue(store.updated.any { it.id == "jp1" && it.consecutiveFailures == 1 })
    }

    @Test
    fun `stable preset never switches while current is healthy`() = runTest {
        val current = node("us1", "US", 70, 100)
        val candidate = node("us2", "US", 99, 5)
        val store = FakeStore(listOf(current, candidate))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult(it.id, true, 90) },
            FakeProber { ProbeResult(it.id, true, 5) },
            switcher,
            localUsable = true
        )

        val outcome = controller.checkAndFailover(
            "proxy",
            current,
            FailoverConfig(preset = FailoverPreset.STABLE_HYSTERESIS_ONLY)
        )

        assertEquals(FailoverOutcome.Action.HEALTHY, outcome.action)
        assertNull(switcher.lastNode)
    }

    @Test
    fun `slow current latency is treated as failure`() = runTest {
        val current = node("us1", "US", 70, 100)
        val store = FakeStore(listOf(current))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult(it.id, true, 900) },
            FakeProber { ProbeResult(it.id, true, 50) },
            switcher,
            localUsable = false
        )

        val outcome = controller.checkAndFailover(
            "proxy",
            current,
            FailoverConfig(slowLatencyThresholdMs = 850)
        )

        assertEquals(FailoverOutcome.Action.LOCAL_NETWORK_DOWN, outcome.action)
        assertTrue(store.updated.isEmpty())
    }

    @Test
    fun `hysteresis threshold blocks marginal candidate`() = runTest {
        val current = node("us1", "US", 70, 100)
        val candidate = node("us2", "US", 75, 95)
        val store = FakeStore(listOf(current, candidate))
        val switcher = RecordingSwitcher()
        val controller = controller(
            store,
            FakeProber { ProbeResult(it.id, true, 100) },
            FakeProber { ProbeResult(it.id, true, 95) },
            switcher,
            localUsable = true
        )

        val outcome = controller.checkAndFailover("proxy", current, FailoverConfig())

        assertEquals(FailoverOutcome.Action.HEALTHY, outcome.action)
        assertNull(switcher.lastNode)
        assertFalse(store.updated.any { it.id == "us2" })
    }
}
