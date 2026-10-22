package com.singflow.android.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库 v1 → v2：为节点表补充健康度、国家、收藏与连接指纹字段。
 *
 * 迁移采用「原地加列 + 默认值」策略，保留用户已导入的全部节点；
 * 默认值必须与 [NodeEntity] 的 `@ColumnInfo(defaultValue = ...)` 完全一致，
 * 否则 Room 会在启动时判定 schema 不一致并抛异常。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE nodes ADD COLUMN configHash TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE nodes ADD COLUMN rawShareUri TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE nodes ADD COLUMN countryCode TEXT NOT NULL DEFAULT 'UN'")
        database.execSQL("ALTER TABLE nodes ADD COLUMN countryNameCn TEXT NOT NULL DEFAULT '未知地区'")
        database.execSQL("ALTER TABLE nodes ADD COLUMN egressIp TEXT")
        database.execSQL("ALTER TABLE nodes ADD COLUMN isRegionVerified INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN probeTotalCount INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN probeSuccessCount INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN consecutiveFailures INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN emaLatencyMs REAL NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN latencyJitterMs REAL NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN healthScore INTEGER NOT NULL DEFAULT 50")
        database.execSQL("ALTER TABLE nodes ADD COLUMN healthTag TEXT NOT NULL DEFAULT '未检测'")
        database.execSQL("ALTER TABLE nodes ADD COLUMN lastProbeTimeMs INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN cooldownUntilMs INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN cumulativeRxBytes INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE nodes ADD COLUMN cumulativeTxBytes INTEGER NOT NULL DEFAULT 0")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_configHash ON nodes (configHash)")
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_nodes_countryCode_healthScore " +
                "ON nodes (countryCode, healthScore)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_nodes_isFavorite_healthScore " +
                "ON nodes (isFavorite, healthScore)"
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_nodes_groupId ON nodes (groupId)")

        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_route_rules (
                packageName TEXT NOT NULL,
                appName TEXT NOT NULL,
                routeMode TEXT NOT NULL,
                targetCountryCode TEXT NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                PRIMARY KEY(packageName)
            )
            """.trimIndent()
        )
    }
}
