package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * 多国出站组拦截器（红线 2 / 红线 3）。
 *
 * 扫描全部节点并按国家聚合，生成：
 * - `node-{id}`：单节点出站（WireGuard 为 endpoint）；
 * - `auto-{CC}`：该国健康分前 10 节点的 `urltest` 组；
 * - `group-{CC}`：该国 `selector` 组，默认指向 `auto-{CC}`；
 * - `group-FAVORITES`：收藏节点组；
 * - `group-GLOBAL`：全局健康节点 `urltest` 组；
 * - `proxy`：默认路由 selector，默认指向当前选中节点或默认国家组。
 *
 * 绝不生成 `block` / `dns` 出站；应用阻断由路由动作 `reject` 完成。
 */
class CountryGroupInterceptor : ConfigInterceptor {

    override val name: String = "CountryGroupInterceptor"

    override fun intercept(context: SingBoxConfigContext) {
        if (context.nodes.size <= 1) return

        val strategy = if (context.settings.enableIpv6) "prefer_ipv4" else "ipv4_only"
        val resolver = buildJsonObject {
            put("server", "dns-direct")
            put("strategy", strategy)
        }

        val nodes = context.nodes.distinctBy { it.id }
        val outbounds = mutableListOf<JsonObject>()
        val endpoints = mutableListOf<JsonObject>()
        val tagById = LinkedHashMap<String, String>()

        for (node in nodes) {
            val tag = nodeTag(node)
            tagById[node.id] = tag
            if (node.protocol == ProtocolType.WIREGUARD) {
                endpoints += ProxyOutboundFactory.buildWireGuardEndpoint(node, tag, resolver)
            } else {
                outbounds += ProxyOutboundFactory.buildProxyOutbound(
                    node,
                    tag,
                    resolver,
                    context.settings.antiBlocking
                )
            }
        }

        val groupNodes = nodes.filter { it.protocol != ProtocolType.WIREGUARD }
        if (groupNodes.isEmpty()) {
            // 全部为 WireGuard：没有可安全放入 selector 的普通出站，退回单节点语义。
            outbounds += directOutbound()
            context.outbounds = JsonArray(outbounds)
            context.endpoints = JsonArray(endpoints)
            context.defaultCountryGroupTag = context.proxyTag
            context.diagnostics += "CountryGroup: 全部节点为 WireGuard，跳过国家分组"
            return
        }

        val byCountry = groupNodes
            .filter { it.countryCode.length == 2 && it.countryCode.uppercase(Locale.US) != "UN" }
            .groupBy { it.countryCode.uppercase(Locale.US) }
            .toSortedMap()

        val groupTags = mutableListOf<String>()
        for ((country, countryNodes) in byCountry) {
            val sorted = countryNodes.sortedByDescending { it.healthScore }
            val autoTag = "auto-$country"
            val groupTag = "group-$country"
            val autoMembers = sorted.take(MAX_AUTO_MEMBERS).map { tagById.getValue(it.id) }
            outbounds += urltestOutbound(autoTag, autoMembers)
            val selectorMembers = (listOf(autoTag) + sorted.map { tagById.getValue(it.id) }).distinct()
            outbounds += selectorOutbound(groupTag, selectorMembers, autoTag)
            groupTags += groupTag
            context.availableCountryCodes += country
            context.diagnostics += "CountryGroup: $country ${countryNodes.size} 节点 -> $groupTag"
        }

        val favorites = groupNodes.filter { it.isFavorite }.sortedByDescending { it.healthScore }
        val favoritesTag = if (favorites.isEmpty()) {
            null
        } else {
            val tag = "group-FAVORITES"
            outbounds += selectorOutbound(
                tag = tag,
                members = favorites.map { tagById.getValue(it.id) },
                defaultTag = tagById.getValue(favorites.first().id)
            )
            groupTags += tag
            tag
        }
        context.favoritesGroupTag = favoritesTag

        val globalMembers = groupNodes
            .sortedByDescending { it.healthScore }
            .take(MAX_AUTO_MEMBERS)
            .map { tagById.getValue(it.id) }
        if (globalMembers.isNotEmpty()) {
            outbounds += urltestOutbound(GLOBAL_TAG, globalMembers)
            groupTags += GLOBAL_TAG
        }

        val preferredCountry = context.settings.failover.defaultCountryCode.trim().uppercase(Locale.US)
        val defaultTag = when {
            preferredCountry.isNotBlank() && preferredCountry in context.availableCountryCodes ->
                "group-$preferredCountry"
            GLOBAL_TAG in groupTags -> GLOBAL_TAG
            else -> groupTags.firstOrNull() ?: context.proxyTag
        }
        context.defaultCountryGroupTag = defaultTag

        val selectedTag = tagById[context.node.id]
            ?.takeIf { context.node.protocol != ProtocolType.WIREGUARD }
            ?: defaultTag
        val proxyMembers = sequenceOf(selectedTag, defaultTag)
            .plus(groupTags)
            .plus(groupNodes.map { tagById.getValue(it.id) })
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        outbounds += selectorOutbound(context.proxyTag, proxyMembers, selectedTag)

        outbounds += directOutbound()
        context.outbounds = JsonArray(outbounds)
        context.endpoints = JsonArray(endpoints)
        context.diagnostics +=
            "CountryGroup: ${context.availableCountryCodes.size} 个国家组，default=$defaultTag, selected=$selectedTag"
    }

    private fun nodeTag(node: ProxyNode): String = "node-${node.id}"

    private fun selectorOutbound(tag: String, members: List<String>, defaultTag: String): JsonObject {
        return buildJsonObject {
            put("type", "selector")
            put("tag", tag)
            putJsonArray("outbounds") { members.forEach { add(it) } }
            put("default", defaultTag)
            put("interrupt_exist_connections", false)
        }
    }

    private fun urltestOutbound(tag: String, members: List<String>): JsonObject {
        return buildJsonObject {
            put("type", "urltest")
            put("tag", tag)
            putJsonArray("outbounds") { members.forEach { add(it) } }
            put("url", URL_TEST_URL)
            put("interval", URL_TEST_INTERVAL)
            put("tolerance", URL_TEST_TOLERANCE)
            put("interrupt_exist_connections", false)
        }
    }

    private fun directOutbound(): JsonObject = buildJsonObject {
        put("type", "direct")
        put("tag", "direct")
    }

    private companion object {
        /** 单个 urltest 组最多成员数，避免上百节点同时探测。 */
        const val MAX_AUTO_MEMBERS = 10

        /** 全局组 tag。 */
        const val GLOBAL_TAG = "group-GLOBAL"

        /** 测速地址。 */
        const val URL_TEST_URL = "https://www.gstatic.com/generate_204"

        /** 组内测速间隔。 */
        const val URL_TEST_INTERVAL = "3m"

        /** 切换容差（毫秒）。 */
        const val URL_TEST_TOLERANCE = 50
    }
}
