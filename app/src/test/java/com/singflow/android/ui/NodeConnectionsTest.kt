package com.singflow.android.ui

import com.singflow.android.core.probe.ClashConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点连接过滤测试。
 */
class NodeConnectionsTest {

    private fun connection(id: String, chain: String, upload: Long, download: Long) = ClashConnection(
        id = id,
        host = "$id.example.com",
        destination = "$id.example.com:443",
        network = "tcp",
        rule = "final",
        chains = listOf(chain),
        upload = upload,
        download = download
    )

    @Test
    fun `filters by node chain and sorts by traffic`() {
        val list = listOf(
            connection("low", "node-a", 1, 10),
            connection("high", "node-a", 1, 100),
            connection("other", "node-b", 1, 999)
        )

        val result = connectionsForNode(list, "a")

        assertEquals(2, result.size)
        assertEquals("high", result.first().id)
        assertEquals("low", result[1].id)
    }

    @Test
    fun `blank node id returns empty`() {
        assertTrue(connectionsForNode(listOf(connection("a", "node-a", 0, 1)), "").isEmpty())
    }
}
