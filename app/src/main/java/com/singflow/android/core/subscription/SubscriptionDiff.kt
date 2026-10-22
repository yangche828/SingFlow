package com.singflow.android.core.subscription

import com.singflow.android.core.model.ProxyNode

/**
 * 订阅节点差异。
 *
 * @property added 新增节点（按 configHash 判断）
 * @property retained 仍然存在的节点
 * @property removed 已从订阅中消失的节点
 */
data class SubscriptionNodeDiff(
    val added: List<ProxyNode>,
    val retained: List<ProxyNode>,
    val removed: List<ProxyNode>
) {
    /** 新订阅快照包含的节点数。 */
    val fetchedCount: Int get() = added.size + retained.size
}

/**
 * 计算订阅节点差异。
 *
 * 以 `configHash`（协议 + 入口 + 凭据 + 传输层 + SNI）为唯一键，
 * 避免仅因节点改名/改端口顺序而误删或重复导入。
 */
object SubscriptionNodeDiffCalculator {

    /**
     * @param existing 当前订阅分组下的节点
     * @param fetched 本次订阅解析出的节点
     */
    fun diff(existing: List<ProxyNode>, fetched: List<ProxyNode>): SubscriptionNodeDiff {
        val existingByHash = existing.associateBy { it.configHash }
        val fetchedByHash = fetched.associateBy { it.configHash }
        val added = fetched.filter { it.configHash !in existingByHash }
        val retained = fetched.filter { it.configHash in existingByHash }
        val removed = existing.filter { it.configHash !in fetchedByHash }
        return SubscriptionNodeDiff(added = added, retained = retained, removed = removed)
    }
}
