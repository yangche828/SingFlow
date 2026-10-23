package com.singflow.android.core.database

import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import com.singflow.android.core.subscription.SubscriptionNodeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 节点仓库：负责领域模型与持久化实体之间的转换。
 *
 * 订阅重复导入时使用 [saveAllPreservingStats]，只刷新名称与配置，
 * 完整保留收藏、实测国家与历史健康度。
 *
 * @param nodeDao 节点 DAO
 * @param json JSON 编解码器
 */
class NodeRepository(
    private val nodeDao: NodeDao,
    private val json: Json
) : SubscriptionNodeStore {
    /** 观察全部节点（收藏与健康度优先）。 */
    fun observeNodes(): Flow<List<ProxyNode>> = nodeDao.observeAll().map { list -> list.map { it.toDomainSafe() } }

    /** 读取全部节点。 */
    suspend fun getAll(): List<ProxyNode> = nodeDao.getAll().map { it.toDomainSafe() }

    /** 按 ID 读取。 */
    suspend fun getById(id: String): ProxyNode? = nodeDao.getById(id)?.toDomainSafe()

    /** 按连接指纹读取。 */
    suspend fun findByConfigHash(hash: String): ProxyNode? {
        if (hash.isBlank()) return null
        return nodeDao.findByConfigHash(hash)?.toDomainSafe()
    }

    /** 读取指定国家的节点。 */
    suspend fun getByCountry(countryCode: String): List<ProxyNode> =
        nodeDao.getByCountry(countryCode).map { it.toDomainSafe() }

    /** 读取某个订阅/分组下的节点。 */
    override suspend fun getByGroup(groupId: String): List<ProxyNode> =
        nodeDao.getByGroup(groupId).map { it.toDomainSafe() }

    /** 写入实测出口 IP 与国家。 */
    suspend fun updateEgress(id: String, egressIp: String, countryCode: String, countryNameCn: String) {
        nodeDao.updateEgress(id, egressIp, countryCode, countryNameCn)
    }

    /** 读取自动切换候选。 */
    suspend fun getHealthyCandidates(
        nowMs: Long = System.currentTimeMillis(),
        minScore: Int = 50
    ): List<ProxyNode> = nodeDao.getHealthyCandidates(nowMs, minScore).map { it.toDomainSafe() }

    /** 保存单个节点。 */
    suspend fun save(node: ProxyNode) {
        nodeDao.upsert(node.toEntity())
    }

    /** 批量保存节点（新导入场景）。 */
    suspend fun saveAll(nodes: List<ProxyNode>): Int {
        if (nodes.isEmpty()) return 0
        nodeDao.upsertAll(nodes.map { it.toEntity() })
        return nodes.size
    }

    /**
     * 批量保存并保留已有节点的统计与个性化字段。
     *
     * 用于订阅刷新：同一连接指纹的节点只更新配置与展示名，
     * 不覆盖收藏、累计流量、探活次数与实测国家。
     */
    override suspend fun saveAllPreservingStats(nodes: List<ProxyNode>): Int {
        if (nodes.isEmpty()) return 0
        var written = 0
        for (node in nodes) {
            val incoming = node.toEntity()
            val existing = nodeDao.findByConfigHash(node.configHash) ?: nodeDao.getById(node.id)
            if (existing == null) {
                nodeDao.upsert(incoming)
            } else {
                nodeDao.update(NodeMerge.preserve(existing, incoming))
            }
            written++
        }
        return written
    }

    /** 更新节点（健康度、国家、收藏等整体写回）。 */
    suspend fun update(node: ProxyNode) {
        nodeDao.update(node.toEntity())
    }

    /** 更新节点延迟。 */
    suspend fun updateLatency(id: String, latencyMs: Int) {
        nodeDao.updateLatency(id, latencyMs)
    }

    /** 设置收藏。 */
    suspend fun setFavorite(id: String, favorite: Boolean) {
        nodeDao.setFavorite(id, favorite)
    }

    /** 累加节点流量。 */
    suspend fun addTraffic(id: String, rxBytes: Long, txBytes: Long) {
        if (rxBytes <= 0 && txBytes <= 0) return
        nodeDao.addTraffic(id, rxBytes, txBytes)
    }

    /** 重置全部延迟。 */
    suspend fun resetLatency() {
        nodeDao.resetAllLatency()
    }

    /** 删除节点。 */
    suspend fun delete(id: String) {
        nodeDao.deleteById(id)
    }

    /** 批量删除节点。 */
    override suspend fun deleteAll(ids: List<String>) {
        if (ids.isEmpty()) return
        nodeDao.deleteByIds(ids)
    }

    /** 清空节点。 */
    suspend fun clear() {
        nodeDao.deleteAll()
    }

    /** 节点数量。 */
    suspend fun count(): Int = nodeDao.count()

    private fun NodeEntity.toDomainSafe(): ProxyNode {
        val transport = runCatching {
            json.decodeFromString(TransportConfig.serializer(), transportJson)
        }.getOrElse { TransportConfig.DEFAULT }
        val tls = runCatching {
            json.decodeFromString(TlsConfig.serializer(), tlsJson)
        }.getOrElse { TlsConfig.DEFAULT }
        return toDomain(transport, tls)
    }

    private fun ProxyNode.toEntity(): NodeEntity {
        return NodeEntity.fromDomain(
            node = this,
            transportJson = json.encodeToString(TransportConfig.serializer(), transport),
            tlsJson = json.encodeToString(TlsConfig.serializer(), tls)
        )
    }
}
