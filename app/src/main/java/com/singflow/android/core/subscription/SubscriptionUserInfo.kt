package com.singflow.android.core.subscription

import java.util.Locale

/**
 * 订阅流量信息（来自 HTTP 响应头 `Subscription-Userinfo`）。
 *
 * @property upload 已用上传字节
 * @property download 已用下载字节
 * @property total 总流量字节（0 表示未知）
 * @property expireAt 到期时间戳（秒，0 表示未知）
 */
data class SubscriptionUserInfo(
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expireAt: Long = 0L
) {
    /** 已用总流量。 */
    val used: Long get() = (upload + download).coerceAtLeast(0L)

    /** 是否设置了总流量。 */
    val hasQuota: Boolean get() = total > 0L

    /** 使用百分比（0..100），总量未知时返回 -1。 */
    val usedPercent: Int
        get() = if (!hasQuota) -1 else ((used.toDouble() / total) * 100).toInt().coerceIn(0, 100)

    /** 到期时间戳（毫秒），未知为 0。 */
    val expireAtMillis: Long get() = if (expireAt > 0) expireAt * 1000L else 0L

    companion object {
        /**
         * 解析 `Subscription-Userinfo: upload=1; download=2; total=3; expire=1700000000`。
         *
         * 字段缺失或非法时按 0 处理；整段为空返回 null。
         */
        fun parse(raw: String?): SubscriptionUserInfo? {
            if (raw.isNullOrBlank()) return null
            val values = LinkedHashMap<String, Long>()
            raw.split(';', ',').forEach { segment ->
                val index = segment.indexOf('=')
                if (index <= 0) return@forEach
                val key = segment.substring(0, index).trim().lowercase(Locale.US)
                val value = segment.substring(index + 1).trim().toLongOrNull() ?: return@forEach
                if (value >= 0) values[key] = value
            }
            if (values.isEmpty()) return null
            return SubscriptionUserInfo(
                upload = values["upload"] ?: 0L,
                download = values["download"] ?: 0L,
                total = values["total"] ?: 0L,
                expireAt = values["expire"] ?: 0L
            )
        }
    }
}
