package com.singflow.android.core.config

import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多国出站组与应用级国家分流编译测试。
 */
class CountryRoutingConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val pipeline = SingBoxConfigPipeline(json)

    private fun node(
        id: String,
        country: String,
        score: Int,
        favorite: Boolean = false,
        protocol: ProtocolType = ProtocolType.VLESS
    ) = ProxyNode(
        id = id,
        name = "$country-$id",
        protocol = protocol,
        server = "$id.example.com",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        countryCode = country,
        countryNameCn = country,
        healthScore = score,
        isFavorite = favorite
    )

    private val us1 = node("us1", "US", 90, favorite = true)
    private val us2 = node("us2", "US", 80)
    private val jp1 = node("jp1", "JP", 85)
    private val sg1 = node("sg1", "SG", 60)
    private val nodes = listOf(us1, us2, jp1, sg1)

    @Test
    fun `multi node config builds country groups and proxy selector`() {
        val root = pipeline.compile(
            nodes = nodes,
            selectedNodeId = us1.id,
            settings = AppSettings.DEFAULT,
            appRouteRules = emptyList()
        )
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val tags = outbounds.map { it["tag"]!!.jsonPrimitive.content }

        assertTrue(tags.contains("node-us1"))
        assertTrue(tags.contains("auto-US"))
        assertTrue(tags.contains("group-US"))
        assertTrue(tags.contains("auto-JP"))
        assertTrue(tags.contains("group-JP"))
        assertTrue(tags.contains("group-FAVORITES"))
        assertTrue(tags.contains("group-GLOBAL"))
        assertTrue(tags.contains("proxy"))
        assertTrue(tags.contains("direct"))

        // 红线：禁止 block / dns 出站
        assertFalse(outbounds.any { it["type"]!!.jsonPrimitive.content == "block" })
        assertFalse(outbounds.any { it["type"]!!.jsonPrimitive.content == "dns" })

        val proxy = outbounds.first { it["tag"]!!.jsonPrimitive.content == "proxy" }
        assertEquals("selector", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("node-us1", proxy["default"]!!.jsonPrimitive.content)

        val groupUs = outbounds.first { it["tag"]!!.jsonPrimitive.content == "group-US" }
        val groupMembers = groupUs["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("auto-US", groupMembers.first())
        assertTrue(groupMembers.contains("node-us1"))
        assertTrue(groupMembers.contains("node-us2"))

        val autoUs = outbounds.first { it["tag"]!!.jsonPrimitive.content == "auto-US" }
        assertEquals("urltest", autoUs["type"]!!.jsonPrimitive.content)
        assertEquals("https://www.gstatic.com/generate_204", autoUs["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `app country rules compile to route rules and fall back when country missing`() {
        val rules = listOf(
            AppRouteRuleEntity("com.example.direct", "直连应用", AppRouteRuleEntity.MODE_DIRECT),
            AppRouteRuleEntity("com.example.block", "阻断应用", AppRouteRuleEntity.MODE_BLOCK),
            AppRouteRuleEntity("com.example.fav", "收藏应用", AppRouteRuleEntity.MODE_FAVORITES),
            AppRouteRuleEntity(
                "com.example.jp",
                "日本应用",
                AppRouteRuleEntity.MODE_COUNTRY,
                targetCountryCode = "jp"
            ),
            AppRouteRuleEntity(
                "com.example.de",
                "德国应用",
                AppRouteRuleEntity.MODE_COUNTRY,
                targetCountryCode = "DE"
            )
        )
        val settings = AppSettings.DEFAULT.copy(
            failover = AppSettings.DEFAULT.failover.copy(defaultCountryCode = "JP")
        )
        val root = pipeline.compile(nodes, us1.id, settings, rules)
        val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        // 前三条固定：sniff / hijack-dns / 私有地址直连
        val appRules = routeRules.drop(3).dropLast(1)
        assertEquals(5, appRules.size)

        assertEquals("direct", appRules[0]["outbound"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.example.direct"), appRules[0]["package_name"]!!.jsonArray.map { it.jsonPrimitive.content })

        assertEquals("reject", appRules[1]["action"]!!.jsonPrimitive.content)
        assertNull(appRules[1]["outbound"])

        assertEquals("group-FAVORITES", appRules[2]["outbound"]!!.jsonPrimitive.content)
        assertEquals("group-JP", appRules[3]["outbound"]!!.jsonPrimitive.content)
        // DE 没有节点，回退默认国家组 group-JP
        assertEquals("group-JP", appRules[4]["outbound"]!!.jsonPrimitive.content)

        // 大陆分流规则仍然排在应用规则之后
        val cnRule = routeRules.last().jsonObject
        assertNotNull(cnRule["rule_set"])
        assertEquals("direct", cnRule["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wireguard selected node falls back to single node config`() {
        val wg = node("wg1", "US", 90, protocol = ProtocolType.WIREGUARD).copy(
            wgPrivateKey = "cHJpdmF0ZUtleQ==",
            wgPeerPublicKey = "cHVibGljS2V5",
            wgLocalAddress = "10.0.0.2/32"
        )
        val root = pipeline.compile(
            nodes = listOf(wg, us1),
            selectedNodeId = wg.id,
            settings = AppSettings.DEFAULT
        )

        assertEquals(1, root["endpoints"]!!.jsonArray.size)
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, outbounds.size)
        assertEquals("direct", outbounds[0]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `compiled grouped config keeps deprecated fields out`() {
        val compact = pipeline.compileCompact(nodes, us1.id, AppSettings.DEFAULT)
        listOf(
            "\"inet4_address\"",
            "\"store_rdrc\"",
            "\"domain_strategy\"",
            "\"type\":\"block\"",
            "\"type\":\"dns\"",
            "\"outbound\":\"any\""
        ).forEach { forbidden ->
            assertFalse("配置中出现废弃字段 $forbidden", compact.contains(forbidden))
        }
    }
}
