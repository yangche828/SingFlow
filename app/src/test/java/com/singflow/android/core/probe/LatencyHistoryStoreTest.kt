package com.singflow.android.core.probe

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 延迟历史环形缓冲测试。
 */
class LatencyHistoryStoreTest {

    @After
    fun tearDown() {
        LatencyHistoryStore.clearAll()
    }

    @Test
    fun `encode and decode round trip`() {
        val encoded = "node-1|1000:12;2000:24"
        val decoded = decodeHistory(encoded)
        assertEquals(2, decoded["node-1"]?.size)
        assertEquals(24, decoded["node-1"]?.last()?.latencyMs)
        assertEquals(encoded, encodeHistory(mapOf("node-1" to ArrayDeque(decoded["node-1"].orEmpty()))))
    }

    @Test
    fun `decode ignores malformed lines`() {
        val decoded = decodeHistory("bad-line\nnode-2|1000:30;oops")
        assertEquals(1, decoded.size)
        assertEquals(1, decoded["node-2"]?.size)
    }

    @Test
    fun `history survives reattach`() {
        val directory = Files.createTempDirectory("latency-history").toFile()
        LatencyHistoryStore.attach(directory)
        LatencyHistoryStore.record("node-3", 77, 1234L)

        LatencyHistoryStore.attach(directory)

        val samples = LatencyHistoryStore.samples("node-3")
        assertEquals(1, samples.size)
        assertEquals(77, samples.first().latencyMs)
        assertEquals(1234L, samples.first().timestampMs)
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
