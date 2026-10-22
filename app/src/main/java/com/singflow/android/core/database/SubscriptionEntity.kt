package com.singflow.android.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 订阅实体（为 Phase 7 订阅自动更新预留）。
 *
 * @property id 订阅 ID
 * @property name 订阅名称
 * @property url 订阅地址
 * @property lastUpdated 最近更新时间
 * @property upload 已用上传字节（来自 Subscription-Userinfo）
 * @property download 已用下载字节
 * @property total 总流量字节
 * @property expireAt 到期时间戳（秒），0 表示未知
 * @property autoUpdate 是否自动更新
 * @property enabled 是否启用
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expireAt: Long = 0L,
    val autoUpdate: Boolean = true,
    val enabled: Boolean = true
)
