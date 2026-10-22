package com.singflow.android.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 应用级国家分流规则数据访问对象。
 */
@Dao
interface AppRouteRuleDao {
    /** 观察全部规则，已设置分流的应用按更新时间倒序。 */
    @Query("SELECT * FROM app_route_rules ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<AppRouteRuleEntity>>

    /** 一次性读取全部规则。 */
    @Query("SELECT * FROM app_route_rules")
    suspend fun getAll(): List<AppRouteRuleEntity>

    /** 按包名查询。 */
    @Query("SELECT * FROM app_route_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): AppRouteRuleEntity?

    /** 插入或覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AppRouteRuleEntity)

    /** 批量覆盖。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<AppRouteRuleEntity>)

    /** 删除某应用的规则。 */
    @Query("DELETE FROM app_route_rules WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    /** 清空全部规则。 */
    @Query("DELETE FROM app_route_rules")
    suspend fun deleteAll()
}
