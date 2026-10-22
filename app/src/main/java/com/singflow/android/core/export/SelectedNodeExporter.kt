package com.singflow.android.core.export

import com.singflow.android.core.model.ProxyNode
import java.util.Base64

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
     * 订阅文本解码（用于测试/校验）。
     */
    fun decodeSubscription(encoded: String): String {
        if (encoded.isBlank()) return ""
        return runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
