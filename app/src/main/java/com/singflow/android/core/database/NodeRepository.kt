package com.singflow.android.core.database

import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 节点仓库：负责领域模型与持久化实体之间的转换。
 *
 * @param nodeDao 节点 DAO
 * @param json JSON 编解码器
 */
class NodeRepository(
    private val nodeDao: NodeDao,
    private val json: Json
) {
    /** 观察全部节点。 */
    fun observeNodes(): Flow<List<ProxyNode>> = nodeDao.observeAll().map { list -> list.map { it.toDomainSafe() } }

    /** 读取全部节点。 */
    suspend fun getAll(): List<ProxyNode> = nodeDao.getAll().map { it.toDomainSafe() }

    /** 按 ID 读取。 */
    suspend fun getById(id: String): ProxyNode? = nodeDao.getById(id)?.toDomainSafe()

    /** 保存单个节点。 */
    suspend fun save(node: ProxyNode) {
        nodeDao.upsert(node.toEntity())
    }

    /** 批量保存节点，返回实际写入数量。 */
    suspend fun saveAll(nodes: List<ProxyNode>): Int {
        if (nodes.isEmpty()) return 0
        nodeDao.upsertAll(nodes.map { it.toEntity() })
        return nodes.size
    }

    /** 更新节点延迟。 */
    suspend fun updateLatency(id: String, latencyMs: Int) {
        nodeDao.updateLatency(id, latencyMs)
    }

    /** 重置全部延迟。 */
    suspend fun resetLatency() {
        nodeDao.resetAllLatency()
    }

    /** 删除节点。 */
    suspend fun delete(id: String) {
        nodeDao.deleteById(id)
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
