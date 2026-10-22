package com.singflow.android.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 订阅数据访问对象（为后续订阅管理预留）。
 */
@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY lastUpdated DESC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun deleteById(id: String)
}
