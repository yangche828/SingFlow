package com.singflow.android.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 节点数据访问对象。
 */
@Dao
interface NodeDao {
    /** 订阅全部节点，按收藏、健康分与延迟排序。 */
    @Query(
        "SELECT * FROM nodes ORDER BY isFavorite DESC, healthScore DESC, " +
            "CASE WHEN latencyMs < 0 THEN 2147483647 ELSE latencyMs END ASC, createdAt ASC"
    )
    fun observeAll(): Flow<List<NodeEntity>>

    /** 一次性读取全部节点。 */
    @Query("SELECT * FROM nodes ORDER BY createdAt ASC")
    suspend fun getAll(): List<NodeEntity>

    /** 按 ID 查询。 */
    @Query("SELECT * FROM nodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NodeEntity?

    /** 按连接指纹查询（空指纹永远不匹配，避免历史空值互相误判）。 */
    @Query("SELECT * FROM nodes WHERE configHash = :hash AND configHash != '' LIMIT 1")
    suspend fun findByConfigHash(hash: String): NodeEntity?

    /** 查询节点总数。 */
    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun count(): Int

    /** 查询指定国家的节点。 */
    @Query("SELECT * FROM nodes WHERE countryCode = :country ORDER BY isFavorite DESC, healthScore DESC")
    suspend fun getByCountry(country: String): List<NodeEntity>

    /**
     * 查询可参与自动切换的健康候选：未处于冷却期且健康分达标。
     */
    @Query(
        "SELECT * FROM nodes WHERE cooldownUntilMs < :nowMs AND healthScore >= :minScore " +
            "ORDER BY isFavorite DESC, healthScore DESC, emaLatencyMs ASC"
    )
    suspend fun getHealthyCandidates(nowMs: Long, minScore: Int = 50): List<NodeEntity>

    /** 插入或覆盖节点。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NodeEntity)

    /** 批量插入或覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<NodeEntity>)

    /** 更新节点。 */
    @Update
    suspend fun update(node: NodeEntity)

    /** 更新延迟测速结果。 */
    @Query("UPDATE nodes SET latencyMs = :latency WHERE id = :id")
    suspend fun updateLatency(id: String, latency: Int)

    /** 批量重置延迟。 */
    @Query("UPDATE nodes SET latencyMs = -1")
    suspend fun resetAllLatency()

    /** 设置收藏状态。 */
    @Query("UPDATE nodes SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /** 累加单节点流量统计。 */
    @Query(
        "UPDATE nodes SET cumulativeRxBytes = cumulativeRxBytes + :rxBytes, " +
            "cumulativeTxBytes = cumulativeTxBytes + :txBytes WHERE id = :id"
    )
    suspend fun addTraffic(id: String, rxBytes: Long, txBytes: Long)

    /** 删除单个节点。 */
    @Delete
    suspend fun delete(node: NodeEntity)

    /** 按 ID 删除。 */
    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 按 ID 集合批量删除。 */
    @Query("DELETE FROM nodes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** 清空全部节点。 */
    @Query("DELETE FROM nodes")
    suspend fun deleteAll()
}
