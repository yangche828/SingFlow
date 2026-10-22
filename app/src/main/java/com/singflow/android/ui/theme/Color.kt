package com.singflow.android.ui.theme

import androidx.compose.ui.graphics.Color
import com.singflow.android.core.model.ProtocolType

/** 极光青蓝主色。 */
val AuroraCyan = Color(0xFF22D3EE)

/** 极光蓝。 */
val AuroraBlue = Color(0xFF38BDF8)

/** 极光紫。 */
val AuroraViolet = Color(0xFF8B5CF6)

/** 极光粉。 */
val AuroraPink = Color(0xFFF472B6)

/** 深色背景。 */
val DeepSpace = Color(0xFF070B14)

/** 深色表面。 */
val DeepSurface = Color(0xFF0F172A)

/** 深色卡片。 */
val DeepCard = Color(0xFF141F35)

/** 浅色背景。 */
val FrostBackground = Color(0xFFF2F7FB)

/** 浅色表面。 */
val FrostSurface = Color(0xFFFFFFFF)

/** 成功绿。 */
val SignalGreen = Color(0xFF34D399)

/** 警告橙。 */
val SignalAmber = Color(0xFFFBBF24)

/** 错误红。 */
val SignalRed = Color(0xFFF87171)

/**
 * 协议专属徽章颜色。
 */
fun ProtocolType.badgeColor(): Color = Color(badgeColorHex)

/**
 * 根据延迟返回状态色。
 */
fun latencyColor(latencyMs: Int): Color {
    return when {
        latencyMs < 0 -> Color(0xFF94A3B8)
        latencyMs < 120 -> SignalGreen
        latencyMs < 300 -> SignalAmber
        else -> SignalRed
    }
}

/**
 * 根据健康分返回状态色。
 */
fun healthColor(score: Int): Color {
    return when {
        score >= 85 -> SignalGreen
        score >= 70 -> AuroraCyan
        score >= 50 -> SignalAmber
        else -> SignalRed
    }
}
