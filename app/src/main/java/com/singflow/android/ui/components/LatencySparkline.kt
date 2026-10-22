package com.singflow.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import com.singflow.android.core.probe.LatencySample

/**
 * 节点延迟折线图（Canvas，无第三方依赖）。
 *
 * @param samples 时间正序采样
 * @param color 折线颜色
 */
@Composable
fun LatencySparkline(
    samples: List<LatencySample>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val min = samples.minOf { it.latencyMs }
        val max = samples.maxOf { it.latencyMs }
        val span = (max - min).coerceAtLeast(1)
        val stepX = size.width / (samples.size - 1)
        var previous: Offset? = null
        samples.forEachIndexed { index, sample ->
            val point = Offset(
                x = stepX * index,
                y = size.height * (1f - (sample.latencyMs - min).toFloat() / span)
            )
            previous?.let { start ->
                drawLine(
                    color = color,
                    start = start,
                    end = point,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
            }
            drawCircle(color = color, radius = 3.5f, center = point)
            previous = point
        }
    }
}
