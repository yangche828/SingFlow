package com.singflow.android.core.backup

import com.singflow.android.core.model.ProxyNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 JSON 序列化往返测试。
 */
class BackupBundleTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun `backup bundle round trips nodes settings and rules`() {
        val node = ProxyNode(
            id = "node-1",
            name = "测试节点",
            server = "127.0.0.1",
            port = 8445,
            rawShareUri = "vless://demo@127.0.0.1:8445#demo",
            countryCode = "JP",
            healthScore = 88
        )
        val bundle = BackupBundle(
            version = BackupBundle.CURRENT_VERSION,
            exportedAt = 1_700_000_000_000L,
            nodes = listOf(node)
        )

        val encoded = json.encodeToString(BackupBundle.serializer(), bundle)
        val decoded = json.decodeFromString(BackupBundle.serializer(), encoded)

        assertEquals(BackupBundle.CURRENT_VERSION, decoded.version)
        assertEquals(1, decoded.nodes.size)
        assertEquals("测试节点", decoded.nodes.first().name)
        assertEquals(8445, decoded.nodes.first().port)
        assertEquals(88, decoded.nodes.first().healthScore)
        assertTrue(encoded.contains("\"version\":1"))
    }
}
