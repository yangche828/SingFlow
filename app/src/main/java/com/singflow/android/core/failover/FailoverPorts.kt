package com.singflow.android.core.failover

import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.probe.ClashApiClient

/**
 * 自动切换所需的节点读写能力。
 *
 * 抽象成接口后，切换控制器可以脱离 Room 在 JVM 单测中完整验证。
 */
interface NodeStore {
    /** 整体写回节点（健康度、冷却期、实测国家等）。 */
    suspend fun update(node: ProxyNode)

    /** 读取可参与切换的健康候选。 */
    suspend fun healthyCandidates(nowMs: Long, minScore: Int): List<ProxyNode>
}

/**
 * [NodeStore] 的 Room 仓库适配器。
 */
class RepositoryNodeStore(
    private val repository: NodeRepository
) : NodeStore {
    override suspend fun update(node: ProxyNode) {
        repository.update(node)
    }

    override suspend fun healthyCandidates(nowMs: Long, minScore: Int): List<ProxyNode> {
        return repository.getHealthyCandidates(nowMs, minScore)
    }
}

/**
 * 出站切换能力（libbox Selector 内存态切换）。
 */
interface OutboundSwitcher {
    /**
     * 将 selector 组切换到指定节点。
     *
     * @return 是否切换成功
     */
    suspend fun switchTo(groupTag: String, node: ProxyNode): Boolean
}

/**
 * 基于 Clash API 的 Selector 切换实现。
 *
 * @param clashApi Clash API 客户端
 * @param tagProvider 节点 ID → 出站 tag 的映射；多节点配置为 `node-{id}`
 */
class ClashApiOutboundSwitcher(
    private val clashApi: ClashApiClient,
    private val tagProvider: (ProxyNode) -> String = { "node-${it.id}" }
) : OutboundSwitcher {
    override suspend fun switchTo(groupTag: String, node: ProxyNode): Boolean {
        return clashApi.select(groupTag, tagProvider(node))
    }
}
