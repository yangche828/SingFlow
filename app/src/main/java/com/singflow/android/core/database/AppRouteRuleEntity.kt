package com.singflow.android.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 应用级国家分流规则。
 *
 * @property packageName 应用包名（主键）
 * @property appName 应用中文名缓存，供 UI 秒开展示
 * @property routeMode 分流模式：COUNTRY / FAVORITES / DIRECT / BLOCK
 * @property targetCountryCode 目标国家代码（仅 COUNTRY 模式有效）
 * @property updatedAtMs 更新时间戳
 */
@Entity(tableName = "app_route_rules")
@Serializable
data class AppRouteRuleEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val routeMode: String,
    val targetCountryCode: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        /** 指定国家。 */
        const val MODE_COUNTRY = "COUNTRY"

        /** 收藏组。 */
        const val MODE_FAVORITES = "FAVORITES"

        /** 直连。 */
        const val MODE_DIRECT = "DIRECT"

        /** 断网阻断。 */
        const val MODE_BLOCK = "BLOCK"
    }
}
