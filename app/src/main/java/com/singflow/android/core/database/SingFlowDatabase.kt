package com.singflow.android.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * SingFlow 主数据库。
 */
@Database(
    entities = [NodeEntity::class, SubscriptionEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(NodeConverters::class)
abstract class SingFlowDatabase : RoomDatabase() {
    /** 节点 DAO。 */
    abstract fun nodeDao(): NodeDao

    /** 订阅 DAO。 */
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        const val DATABASE_NAME = "singflow.db"
    }
}
