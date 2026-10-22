package com.singflow.android.core.probe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延迟历史环形缓冲测试。
 */
class LatencyHistoryStoreTest {

    @After
    fun tearDown() {
        LatencyHistoryStore.clearAll()
    }

    @Test
    fun `records samples and trims to max size`() {
        repeat(LatencyHistoryStore.MAX_SAMPLES + 5) { index ->
            LatencyHistoryStore.record("node-1", index + 1, index.toLong())
        }
        val samples = LatencyHistoryStore.samples("node-1")
        assertEquals(LatencyHistoryStore.MAX_SAMPLES, samples.size)
        assertEquals(6, samples.first().latencyMs)
        assertEquals(LatencyHistoryStore.MAX_SAMPLES + 5, samples.last().latencyMs)
    }

    @Test
    fun `ignores invalid latency and unknown node`() {
        LatencyHistoryStore.record("node-1", 0)
        LatencyHistoryStore.record("node-1", -1)
        assertTrue(LatencyHistoryStore.samples("node-1").isEmpty())
        assertTrue(LatencyHistoryStore.samples("missing").isEmpty())
    }

    @Test
    fun `clear removes only selected node`() {
        LatencyHistoryStore.record("a", 10)
        LatencyHistoryStore.record("b", 20)
        LatencyHistoryStore.clear("a")
        assertTrue(LatencyHistoryStore.samples("a").isEmpty())
        assertEquals(1, LatencyHistoryStore.samples("b").size)
    }
}
