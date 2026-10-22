package com.singflow.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singflow.android.core.model.VpnState
import com.singflow.android.ui.theme.AuroraCyan
import com.singflow.android.ui.theme.AuroraViolet

/**
 * 实时流量卡片，底部绘制最近 30 个采样点的平滑三次贝塞尔曲线。
 *
 * @param state 当前 VPN 状态
 */
@Composable
fun TrafficWaveCard(
    state: VpnState,
    modifier: Modifier = Modifier
) {
    val upSamples = remember { mutableStateListOf<Float>() }
    val downSamples = remember { mutableStateListOf<Float>() }

    LaunchedEffect(state.uploadSpeed, state.downloadSpeed) {
        upSamples.add(state.uploadSpeed.toFloat())
        downSamples.add(state.downloadSpeed.toFloat())
        if (upSamples.size > SAMPLE_COUNT) upSamples.removeAt(0)
        if (downSamples.size > SAMPLE_COUNT) downSamples.removeAt(0)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SpeedColumn(
                    label = "↑ 上传",
                    value = formatSpeed(state.uploadSpeed),
                    color = AuroraCyan
                )
                SpeedColumn(
                    label = "↓ 下载",
                    value = formatSpeed(state.downloadSpeed),
                    color = AuroraViolet,
                    horizontalAlignment = Alignment.End
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            ) {
                val gridColor = Color.White.copy(alpha = 0.06f)
                for (index in 1..3) {
                    val y = size.height * index / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }

                val maxValue = (upSamples + downSamples)
                    .maxOrNull()
                    ?.coerceAtLeast(1024f) ?: 1024f
                val upPath = buildSmoothPath(upSamples, size.width, size.height, maxValue)
                val downPath = buildSmoothPath(downSamples, size.width, size.height, maxValue)

                drawPath(
                    path = upPath,
                    brush = Brush.horizontalGradient(listOf(AuroraCyan, AuroraCyan.copy(alpha = 0.65f))),
                    style = Stroke(width = 3f)
                )
                drawPath(
                    path = downPath,
                    brush = Brush.horizontalGradient(listOf(AuroraViolet, AuroraViolet.copy(alpha = 0.65f))),
                    style = Stroke(width = 3f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "累计 ↑ ${formatBytes(state.totalUpload)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "累计 ↓ ${formatBytes(state.totalDownload)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SpeedColumn(
    label: String,
    value: String,
    color: Color,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * 依据采样点构建平滑曲线路径。
 */
private fun buildSmoothPath(
    values: List<Float>,
    width: Float,
    height: Float,
    maxValue: Float
): Path {
    val path = Path()
    if (values.isEmpty()) {
        path.moveTo(0f, height)
        path.lineTo(width, height)
        return path
    }
    val step = width / (SAMPLE_COUNT - 1).coerceAtLeast(1)
    var previousX = 0f
    var previousY = height - (values.first() / maxValue).coerceIn(0f, 1f) * height
    path.moveTo(previousX, previousY)
    for (index in 1 until values.size) {
        val x = index * step
        val y = height - (values[index] / maxValue).coerceIn(0f, 1f) * height
        val controlX = (previousX + x) / 2f
        path.cubicTo(controlX, previousY, controlX, y, x, y)
        previousX = x
        previousY = y
    }
    if (values.size == 1) {
        path.lineTo(width, previousY)
    }
    return path
}

private const val SAMPLE_COUNT = 30
