package com.singflow.android.core.export

import com.singflow.android.core.config.interceptors.ProxyOutboundFactory
import com.singflow.android.core.model.AntiBlockingConfig
import com.singflow.android.core.model.ProxyNode
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 精选节点导出器。
 *
 * 把收藏或高分节点转换为通用分享链接集合，或 Base64 订阅文本，
 * 便于备用机扫码/粘贴导入。
 */
object SelectedNodeExporter {

    /** 高分阈值。 */
    const val HIGH_HEALTH_THRESHOLD = 80

    /**
     * 过滤可导出的节点：必须有分享链接。
     */
    fun select(
        nodes: List<ProxyNode>,
        favoritesOnly: Boolean,
        minHealth: Int = 0
    ): List<ProxyNode> {
        return nodes.filter { node ->
            node.rawShareUri.isNotBlank() &&
                (!favoritesOnly || node.isFavorite) &&
                node.healthScore >= minHealth
        }
    }

    /**
     * 生成换行分隔的分享链接。
     */
    fun toLinks(nodes: List<ProxyNode>): String {
        return nodes.mapNotNull { it.rawShareUri?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("\n")
    }

    /**
     * 生成 Base64 订阅文本（标准 Base64，无换行）。
     */
    fun toSubscription(nodes: List<ProxyNode>): String {
        val links = toLinks(nodes)
        if (links.isBlank()) return ""
        return Base64.getEncoder().encodeToString(links.toByteArray(Charsets.UTF_8))
    }

    /**
     * 生成最小可用的 sing-box 1.14 配置（mixed 入站 + 节点 outbounds + direct）。
     *
     * 仅用于分享/迁移节点，不包含 TUN、分流规则与订阅信息；
     * 节点凭据会写入配置，需提醒用户妥善保管。
     */
    fun toSingBoxConfig(
        nodes: List<ProxyNode>,
        antiBlocking: AntiBlockingConfig = AntiBlockingConfig.DEFAULT
    ): String {
        val resolver = buildJsonObject { put("server", "dns-direct") }
        val selected = nodes.filter { it.rawShareUri.isNotBlank() }
        val root = buildJsonObject {
            put("log", buildJsonObject { put("level", "warn") })
            putJsonArray("dns") {
                add(buildJsonObject {
                    put("type", "local")
                    put("tag", "dns-direct")
                })
            }
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", 2080)
                })
            }
            putJsonArray("outbounds") {
                selected.forEachIndexed { index, node ->
                    add(
                        ProxyOutboundFactory.buildProxyOutbound(
                            node = node,
                            tag = "node-${index + 1}",
                            resolver = resolver,
                            antiBlocking = antiBlocking
                        )
                    )
                }
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            }
            put("route", buildJsonObject {
                put("default_domain_resolver", resolver)
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("action", "sniff")
                        put("timeout", "300ms")
                    })
                }
                put("final", "direct")
            })
        }
        return Json { prettyPrint = true; encodeDefaults = true }
            .encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    /**
     * 订阅文本解码（用于测试/校验）。
     */
    fun decodeSubscription(encoded: String): String {
        if (encoded.isBlank()) return ""
        return runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
