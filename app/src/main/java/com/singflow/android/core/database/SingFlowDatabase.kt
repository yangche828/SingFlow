package com.singflow.android.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * SingFlow 主数据库。
 */
@Database(
    entities = [NodeEntity::class, SubscriptionEntity::class, AppRouteRuleEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(NodeConverters::class)
abstract class SingFlowDatabase : RoomDatabase() {
    /** 节点 DAO。 */
    abstract fun nodeDao(): NodeDao

    /** 订阅 DAO。 */
    abstract fun subscriptionDao(): SubscriptionDao

    /** 应用级国家分流规则 DAO。 */
    abstract fun appRouteRuleDao(): AppRouteRuleDao

    companion object {
        const val DATABASE_NAME = "singflow.db"
    }
}
