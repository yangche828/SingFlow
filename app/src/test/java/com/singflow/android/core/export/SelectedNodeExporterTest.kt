package com.singflow.android.core.export

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 精选节点导出测试。
 */
class SelectedNodeExporterTest {

    private fun node(
        id: String,
        favorite: Boolean,
        health: Int,
        share: String = ""
    ) = ProxyNode(
        id = id,
        name = id,
        protocol = ProtocolType.VLESS,
        server = "example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        healthScore = health,
        isFavorite = favorite,
        rawShareUri = share
    )

    @Test
    fun `select favorites and high health only`() {
        val nodes = listOf(
            node("fav", true, 30, "vless://fav"),
            node("high", false, 90, "vless://high"),
            node("low", false, 20, "vless://low"),
            node("no-share", true, 95)
        )

        val favorites = SelectedNodeExporter.select(nodes, favoritesOnly = true)
        assertEquals(1, favorites.size)
        assertEquals("fav", favorites.first().id)

        val high = SelectedNodeExporter.select(
            nodes,
            favoritesOnly = false,
            minHealth = SelectedNodeExporter.HIGH_HEALTH_THRESHOLD
        )
        assertEquals(1, high.size)
        assertEquals("high", high.first().id)
    }

    @Test
    fun `base64 subscription round trip`() {
        val nodes = listOf(
            node("a", true, 90, "vless://a"),
            node("b", true, 90, "vless://b")
        )
        val encoded = SelectedNodeExporter.toSubscription(nodes)
        assertTrue(encoded.isNotBlank())
        assertEquals("vless://a\nvless://b", SelectedNodeExporter.decodeSubscription(encoded))
    }

    @Test
    fun `empty selection produces empty text`() {
        assertEquals("", SelectedNodeExporter.toSubscription(emptyList()))
        assertEquals("", SelectedNodeExporter.decodeSubscription(""))
    }
}
