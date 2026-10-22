package com.singflow.android.core.probe

import com.singflow.android.core.failover.NodeStore
import com.singflow.android.core.model.ProxyNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量并发探活引擎测试。
 */
class BatchProbeEngineTest {

    private fun node(id: String) = ProxyNode(
        id = id,
        name = id,
        server = "$id.example.com",
        port = 443
    )

    private class FakeStore : NodeStore {
        val updated: MutableList<ProxyNode> = Collections.synchronizedList(mutableListOf())

        override suspend fun update(node: ProxyNode) {
            updated += node
        }

        override suspend fun healthyCandidates(nowMs: Long, minScore: Int): List<ProxyNode> = emptyList()
    }

    @Test
    fun `probes all nodes and respects concurrency limit`() = runTest {
        val store = FakeStore()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val prober = object : NodeProber {
            override suspend fun probe(node: ProxyNode, timeoutMs: Int): ProbeResult {
                val current = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, current) }
                delay(10)
                active.decrementAndGet()
                return ProbeResult(node.id, true, 50)
            }
        }
        val engine = BatchProbeEngine(prober, store, concurrency = 5)
        val nodes = (1..20).map { node("n$it") }

        val summary = engine.probeAll(nodes, timeoutMs = 1000)

        assertEquals(20, summary.total)
        assertEquals(20, summary.successCount)
        assertEquals(0, summary.failureCount)
        assertEquals(20, store.updated.size)
        assertTrue("实际最大并发 ${maxActive.get()} 超过限制", maxActive.get() <= 5)
    }

    @Test
    fun `failure results update health and cooldown`() = runTest {
        val store = FakeStore()
        val prober = object : NodeProber {
            override suspend fun probe(node: ProxyNode, timeoutMs: Int): ProbeResult {
                return ProbeResult.failure(node.id, "timeout", checkedAtMs = 1_000L)
            }
        }
        val engine = BatchProbeEngine(prober, store, concurrency = 2, now = { 1_000L })
        val nodes = listOf(node("a"), node("b"))

        val summary = engine.probeAll(nodes, cooldownMinutesOnDead = 10)

        assertEquals(2, summary.failureCount)
        assertEquals(2, store.updated.size)
        // 每个节点只失败一次，未达到冷却阈值
        assertTrue(store.updated.all { it.consecutiveFailures == 1 && it.cooldownUntilMs == 0L })
    }

    @Test
    fun `empty input returns empty summary`() = runTest {
        val engine = BatchProbeEngine(
            prober = object : NodeProber {
                override suspend fun probe(node: ProxyNode, timeoutMs: Int): ProbeResult =
                    ProbeResult(node.id, true, 1)
            },
            store = FakeStore()
        )

        val summary = engine.probeAll(emptyList())

        assertEquals(0, summary.total)
        assertEquals(0, summary.successCount)
    }
}
