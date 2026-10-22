package com.singflow.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.ui.theme.badgeColor
import com.singflow.android.ui.theme.healthColor
import com.singflow.android.ui.theme.latencyColor

/**
 * 节点卡片。
 *
 * 采用「名称 / 国家+健康度 / 地址+延迟」三行布局，在 320dp 窄屏上也不会挤压换行。
 *
 * @param node 节点
 * @param selected 是否当前选中
 * @param onClick 点击选择
 * @param onDelete 删除
 * @param onToggleFavorite 切换收藏
 * @param selectionMode 是否处于批量选择模式
 * @param batchSelected 批量模式下是否已勾选
 * @param onToggleSelect 批量模式下切换勾选
 */
@Composable
fun NodeItemCard(
    node: ProxyNode,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    batchSelected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    val badge = node.protocol.badgeColor()
    val healthTint = healthColor(node.healthScore)
    Card(
        onClick = { if (selectionMode) onToggleSelect() else onClick() },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selectionMode && batchSelected -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            }
        ),
        border = when {
            selectionMode && batchSelected -> BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary)
            selected -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
            else -> null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = batchSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(badge.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = node.protocol.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = badge
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.countryNameCn,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${node.healthTag} ${node.healthScore}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = healthTint,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${node.server}:${node.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatLatency(node.latencyMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = latencyColor(node.latencyMs),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (!selectionMode) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (node.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = if (node.isFavorite) "取消收藏" else "收藏节点",
                        modifier = Modifier.size(18.dp),
                        tint = if (node.isFavorite) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "删除节点",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
