package com.singflow.android.core.subscription

import com.singflow.android.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅节点差异计算测试。
 */
class SubscriptionNodeDiffTest {

    private fun node(id: String, port: Int) = ProxyNode(
        id = id,
        name = "node-$port",
        protocol = com.singflow.android.core.model.ProtocolType.VLESS,
        server = "example.com",
        port = port,
        uuid = "11111111-1111-1111-1111-111111111111"
    )

    @Test
    fun `detects added retained and removed nodes`() {
        val kept = node("a", 443)
        val removed = node("b", 8443)
        val added = node("c", 8444)

        val diff = SubscriptionNodeDiffCalculator.diff(
            existing = listOf(kept, removed),
            fetched = listOf(kept.copy(name = "renamed"), added)
        )

        assertEquals(1, diff.added.size)
        assertEquals(8444, diff.added.first().port)
        assertEquals(1, diff.retained.size)
        assertEquals(1, diff.removed.size)
        assertEquals(8443, diff.removed.first().port)
        assertEquals(2, diff.fetchedCount)
    }

    @Test
    fun `empty subscription removes all existing nodes`() {
        val diff = SubscriptionNodeDiffCalculator.diff(
            existing = listOf(node("a", 443), node("b", 8443)),
            fetched = emptyList()
        )
        assertEquals(0, diff.added.size)
        assertEquals(2, diff.removed.size)
    }

    @Test
    fun `unchanged snapshot yields no changes`() {
        val nodes = listOf(node("a", 443), node("b", 8443))
        val diff = SubscriptionNodeDiffCalculator.diff(nodes, nodes)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(2, diff.retained.size)
    }
}
