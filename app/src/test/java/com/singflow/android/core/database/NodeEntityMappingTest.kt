package com.singflow.android.core.database

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领域模型与持久化实体映射单元测试。
 */
class NodeEntityMappingTest {

    @Test
    fun `round trip preserves health country favorite and traffic`() {
        val node = ProxyNode(
            id = "n1",
            name = "日本 🇯🇵 东京",
            protocol = ProtocolType.VLESS,
            server = "jp.example.com",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            transport = TransportConfig(type = "ws", host = "cdn.example.com", path = "/ws"),
            tls = TlsConfig.tls(serverName = "cdn.example.com"),
            countryCode = "JP",
            countryNameCn = "日本",
            egressIp = "104.28.1.1",
            isRegionVerified = true,
            probeTotalCount = 12,
            probeSuccessCount = 11,
            consecutiveFailures = 0,
            emaLatencyMs = 88.5f,
            latencyJitterMs = 12.25f,
            healthScore = 92,
            healthTag = "极佳",
            lastProbeTimeMs = 1_700_000_000_000L,
            cooldownUntilMs = 0L,
            isFavorite = true,
            cumulativeRxBytes = 1024L,
            cumulativeTxBytes = 512L,
            rawShareUri = "vless://demo"
        )

        val entity = NodeEntity.fromDomain(
            node = node,
            transportJson = JsonHolder.json.encodeToString(TransportConfig.serializer(), node.transport),
            tlsJson = JsonHolder.json.encodeToString(TlsConfig.serializer(), node.tls)
        )
        val restored = entity.toDomain(node.transport, node.tls)

        assertEquals(node.configHash, entity.configHash)
        assertEquals("JP", restored.countryCode)
        assertEquals("日本", restored.countryNameCn)
        assertEquals("104.28.1.1", restored.egressIp)
        assertTrue(restored.isRegionVerified)
        assertEquals(12, restored.probeTotalCount)
        assertEquals(11, restored.probeSuccessCount)
        assertEquals(88.5f, restored.emaLatencyMs, 0.001f)
        assertEquals(12.25f, restored.latencyJitterMs, 0.001f)
        assertEquals(92, restored.healthScore)
        assertEquals("极佳", restored.healthTag)
        assertTrue(restored.isFavorite)
        assertEquals(1024L, restored.cumulativeRxBytes)
        assertEquals(512L, restored.cumulativeTxBytes)
        assertEquals("vless://demo", restored.rawShareUri)
    }

    @Test
    fun `defaults describe a fresh node`() {
        val node = ProxyNode(server = "example.com", port = 443)
        assertEquals("UN", node.countryCode)
        assertEquals("未知地区", node.countryNameCn)
        assertEquals(50, node.healthScore)
        assertEquals("未检测", node.healthTag)
        assertFalse(node.isRegionVerified)
        assertFalse(node.isFavorite)
        assertFalse(node.isCoolingDown(1_000L))
    }

    @Test
    fun `country flag is derived from iso code`() {
        assertEquals("🇯🇵", ProxyNode.countryFlag("jp"))
        assertEquals("🇺🇸", ProxyNode.countryFlag("US"))
        assertEquals("🌐", ProxyNode.countryFlag("UN"))
        assertEquals("🌐", ProxyNode.countryFlag("X"))
    }
}
