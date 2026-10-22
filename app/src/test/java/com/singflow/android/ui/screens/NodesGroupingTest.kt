package com.singflow.android.ui.screens

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点分组顺序测试。
 */
class NodesGroupingTest {

    private fun node(id: String, code: String, name: String, favorite: Boolean = false) = ProxyNode(
        id = id,
        name = id,
        protocol = ProtocolType.VLESS,
        server = "example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        countryCode = code,
        countryNameCn = name,
        isFavorite = favorite
    )

    @Test
    fun `search matches name server and country`() {
        val nodes = listOf(
            node("jp", "JP", "日本").copy(name = "东京节点", server = "jp.example.com"),
            node("us", "US", "美国").copy(name = "洛杉矶", server = "us.example.com")
        )

        assertEquals(1, filterNodes(nodes, "东京").size)
        assertEquals(1, filterNodes(nodes, "us.example").size)
        assertEquals(1, filterNodes(nodes, "美国").size)
        assertEquals(2, filterNodes(nodes, "  ").size)
        assertTrue(filterNodes(nodes, "不存在").isEmpty())
    }

    @Test
    fun `favorites stay first and custom country order applies`() {
        val nodes = listOf(
            node("jp", "JP", "日本"),
            node("us", "US", "美国"),
            node("cn", "CN", "中国大陆"),
            node("fav", "SG", "新加坡", favorite = true)
        )

        val groups = buildGroups(nodes, sortByLatency = false, countryOrder = listOf("US", "JP"))

        assertEquals("⭐ 我的收藏", groups.first().title)
        assertEquals("US", groups[1].countryCode)
        assertEquals("JP", groups[2].countryCode)
        assertEquals("CN", groups[3].countryCode)
    }

    @Test
    fun `unknown region sorts last when not ordered`() {
        val nodes = listOf(
            node("unknown", "UN", "未知"),
            node("jp", "JP", "日本"),
            node("us", "US", "美国")
        )

        val groups = buildGroups(nodes, sortByLatency = false, countryOrder = listOf("US"))

        assertEquals("US", groups[0].countryCode)
        assertEquals("JP", groups[1].countryCode)
        assertEquals("UN", groups[2].countryCode)
    }
}
