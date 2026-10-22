package com.singflow.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.model.VpnStatus
import com.singflow.android.ui.theme.AuroraBlue
import com.singflow.android.ui.theme.AuroraCyan
import com.singflow.android.ui.theme.AuroraViolet
import com.singflow.android.ui.theme.SignalAmber
import com.singflow.android.ui.theme.SignalRed

/**
 * 中央连接按钮：双层脉冲水波纹 + 状态文案。
 *
 * @param state 当前 VPN 状态
 * @param onClick 点击回调
 */
@Composable
fun HeroConnectButton(
    state: VpnState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = state.status == VpnStatus.CONNECTED
    val busy = state.isBusy
    val error = state.status == VpnStatus.ERROR

    val transition = rememberInfiniteTransition(label = "hero-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hero-pulse-value"
    )

    val glowColor = when {
        error -> SignalRed
        connected -> AuroraCyan
        busy -> SignalAmber
        else -> AuroraBlue
    }

    Box(
        modifier = modifier.size(272.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val maxRadius = size.minDimension / 2f
            if (!busy) {
                listOf(0f, 0.5f).forEach { offset ->
                    val progress = (pulse + offset) % 1f
                    val radius = maxRadius * (0.52f + 0.48f * progress)
                    drawCircle(
                        color = glowColor.copy(alpha = (1f - progress) * 0.28f),
                        radius = radius,
                        center = Offset(size.width / 2f, size.height / 2f),
                        style = Stroke(width = 2.5f)
                    )
                }
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = maxRadius
                ),
                radius = maxRadius,
                center = Offset(size.width / 2f, size.height / 2f)
            )
        }

        Box(
            modifier = Modifier
                .size(176.dp)
                .shadow(elevation = 26.dp, shape = CircleShape, ambientColor = glowColor, spotColor = glowColor)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = if (connected) {
                            listOf(AuroraCyan, AuroraBlue, AuroraViolet)
                        } else {
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface
                            )
                        }
                    )
                )
                .clickable(enabled = !busy, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "连接开关",
                    tint = if (connected) Color(0xFF04121F) else glowColor,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusLabel(state.status),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (connected) Color(0xFF04121F) else MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (connected) {
                    Text(
                        text = formatDuration(state.connectedSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF08222E),
                        textAlign = TextAlign.Center
                    )
                } else if (!busy) {
                    Text(
                        text = "TAP TO CONNECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: VpnStatus): String = when (status) {
    VpnStatus.IDLE -> "未连接"
    VpnStatus.CONNECTING -> "连接中…"
    VpnStatus.CONNECTED -> "已保护"
    VpnStatus.STOPPING -> "断开中…"
    VpnStatus.ERROR -> "连接异常"
}
