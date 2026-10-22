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
    /** 订阅全部节点，按创建时间升序。 */
    @Query("SELECT * FROM nodes ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<NodeEntity>>

    /** 一次性读取全部节点。 */
    @Query("SELECT * FROM nodes ORDER BY createdAt ASC")
    suspend fun getAll(): List<NodeEntity>

    /** 按 ID 查询。 */
    @Query("SELECT * FROM nodes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NodeEntity?

    /** 查询节点总数。 */
    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun count(): Int

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

    /** 删除单个节点。 */
    @Delete
    suspend fun delete(node: NodeEntity)

    /** 按 ID 删除。 */
    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun deleteById(id: String)

    /** 清空全部节点。 */
    @Query("DELETE FROM nodes")
    suspend fun deleteAll()
}
