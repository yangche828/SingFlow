package com.singflow.android.core.probe

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clash API 响应解析测试。
 */
class ClashResponseParserTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `parses selector and urltest groups`() {
        val body = """
            {
              "proxies": {
                "proxy": {"type": "Selector", "now": "group-JP", "all": ["group-JP", "group-US"]},
                "group-JP": {"type": "URLTest", "now": "node-jp", "all": ["node-jp", "node-jp2"]},
                "node-jp": {"type": "Vless", "now": ""}
              }
            }
        """.trimIndent()

        val groups = ClashResponseParser.parseGroups(body, json)

        assertEquals(2, groups.size)
        assertEquals("proxy", groups[0].tag)
        assertEquals("group-JP", groups[0].now)
        assertEquals(2, groups[0].members.size)
        assertEquals("URLTest", groups[1].type)
    }

    @Test
    fun `parses traffic and connection metadata`() {
        val body = """
            {
              "uploadTotal": 1024,
              "downloadTotal": 2048,
              "connections": [
                {
                  "id": "c1",
                  "upload": 10,
                  "download": 20,
                  "rule": "RuleSet",
                  "rulePayload": "geosite-cn",
                  "chains": ["proxy", "node-jp"],
                  "metadata": {
                    "network": "tcp",
                    "host": "example.com",
                    "destinationIP": "1.2.3.4",
                    "destinationPort": "443"
                  }
                }
              ]
            }
        """.trimIndent()

        val traffic = ClashResponseParser.parseTraffic(body, json)

        assertNotNull(traffic)
        assertEquals(1024L, traffic!!.uploadTotal)
        assertEquals(2048L, traffic.downloadTotal)
        assertEquals(1, traffic.connections.size)
        val connection = traffic.connections.first()
        assertEquals("example.com", connection.host)
        assertEquals("example.com:443", connection.destination)
        assertEquals("tcp", connection.network)
        assertTrue(connection.rule.contains("geosite-cn"))
        assertEquals("proxy → node-jp", connection.chainText)
        assertEquals(10L, connection.upload)
    }

    @Test
    fun `empty or invalid body yields empty result`() {
        assertTrue(ClashResponseParser.parseGroups("not-json", json).isEmpty())
        assertEquals(null, ClashResponseParser.parseTraffic("{}", json))
        val empty = ClashResponseParser.parseTraffic("{\"connections\":[]}", json)
        assertNotNull(empty)
        assertTrue(empty!!.connections.isEmpty())
    }
}
