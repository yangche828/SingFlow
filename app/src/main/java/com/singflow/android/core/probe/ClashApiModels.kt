package com.singflow.android.core.probe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Clash API 代理组快照。
 *
 * @property tag 组 tag
 * @property type 组类型（Selector / URLTest / ...）
 * @property now 当前选中成员
 * @property members 全部成员 tag
 */
data class ClashProxyGroup(
    val tag: String,
    val type: String,
    val now: String,
    val members: List<String>
)

/**
 * Clash API 单条连接。
 *
 * @property id 连接 ID
 * @property host 目标域名或 IP
 * @property destination 目标地址（host:port）
 * @property network 网络类型（tcp/udp）
 * @property rule 命中的规则
 * @property chains 出站链
 * @property upload 上传字节
 * @property download 下载字节
 */
data class ClashConnection(
    val id: String,
    val host: String,
    val destination: String,
    val network: String,
    val rule: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long
) {
    /** 出站链展示文本。 */
    val chainText: String get() = chains.joinToString(" → ")
}

/**
 * Clash API 流量与连接快照。
 *
 * @property uploadTotal 累计上传
 * @property downloadTotal 累计下载
 * @property connections 当前连接
 */
data class ClashTraffic(
    val uploadTotal: Long,
    val downloadTotal: Long,
    val connections: List<ClashConnection>
)

/**
 * Clash API 响应解析器（纯函数，便于 JVM 单测）。
 */
object ClashResponseParser {

    private val GROUP_TYPES = setOf("Selector", "URLTest", "Fallback", "LoadBalance", "Relay")

    /**
     * 解析 `/proxies` 响应中的代理组。
     */
    fun parseGroups(body: String, json: Json): List<ClashProxyGroup> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()
        val proxies = (root["proxies"] as? JsonObject) ?: root
        val groups = mutableListOf<ClashProxyGroup>()
        proxies.forEach { (tag, element) ->
            val obj = element as? JsonObject ?: return@forEach
            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type !in GROUP_TYPES) return@forEach
            val members = (obj["all"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            groups += ClashProxyGroup(
                tag = tag,
                type = type,
                now = obj["now"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                members = members
            )
        }
        return groups.sortedWith(compareByDescending<ClashProxyGroup> { it.tag == "proxy" }.thenBy { it.tag })
    }

    /**
     * 解析 `/connections` 响应。
     */
    fun parseTraffic(body: String, json: Json): ClashTraffic? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (!root.containsKey("connections") &&
            !root.containsKey("uploadTotal") &&
            !root.containsKey("downloadTotal")
        ) {
            return null
        }
        val connections = (root["connections"] as? JsonArray).orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val metadata = obj["metadata"] as? JsonObject
            val host = metadata?.get("host")?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { metadata?.get("destinationIP")?.jsonPrimitive?.contentOrNull.orEmpty() }
            val port = metadata?.get("destinationPort")?.jsonPrimitive?.contentOrNull.orEmpty()
            val chains = (obj["chains"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            val rule = obj["rule"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val payload = obj["rulePayload"]?.jsonPrimitive?.contentOrNull.orEmpty()
            ClashConnection(
                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                host = host,
                destination = if (port.isBlank()) host else "$host:$port",
                network = metadata?.get("network")?.jsonPrimitive?.contentOrNull.orEmpty(),
                rule = if (rule.isBlank()) payload else if (payload.isBlank()) rule else "$rule($payload)",
                chains = chains,
                upload = obj["upload"]?.jsonPrimitive?.longOrNull ?: 0L,
                download = obj["download"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }
        return ClashTraffic(
            uploadTotal = root["uploadTotal"]?.jsonPrimitive?.longOrNull ?: 0L,
            downloadTotal = root["downloadTotal"]?.jsonPrimitive?.longOrNull ?: 0L,
            connections = connections
        )
    }

    private fun JsonArray?.orEmpty(): List<*> = this ?: emptyList<Any?>()
}
