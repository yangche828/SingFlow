package com.singflow.android.core.probe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 诊断聚合统计测试。
 */
class ClashStatsTest {

    private fun connection(rule: String, chain: String): ClashConnection {
        return ClashConnection(
            id = "$rule-$chain",
            host = "example.com",
            destination = "example.com:443",
            network = "tcp",
            rule = rule,
            chains = listOf(chain),
            upload = 0,
            download = 0
        )
    }

    @Test
    fun `rule hits are grouped and sorted`() {
        val connections = listOf(
            connection("final", "proxy"),
            connection("final", "proxy"),
            connection("RuleSet", "group-JP")
        )
        val hits = ClashStats.ruleHits(connections)
        assertEquals(2, hits.size)
        assertEquals("final", hits.first().label)
        assertEquals(2, hits.first().count)
        assertEquals("RuleSet", hits[1].label)
    }

    @Test
    fun `blank rule falls back to unknown label`() {
        val hits = ClashStats.ruleHits(listOf(connection("", "proxy")))
        assertEquals("未命中规则", hits.first().label)
    }

    @Test
    fun `chain hits use first chain or direct`() {
        val connections = listOf(
            connection("final", "proxy"),
            connection("final", "proxy"),
            connection("final", "group-JP")
        )
        val hits = ClashStats.chainHits(connections)
        assertEquals("proxy", hits.first().label)
        assertEquals(2, hits.first().count)
        assertEquals("group-JP", hits[1].label)
    }
}
