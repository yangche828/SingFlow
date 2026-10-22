package com.singflow.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singflow.android.core.export.QrCodeGenerator
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.probe.ClashConnection
import com.singflow.android.core.probe.LatencyHistoryStore
import com.singflow.android.ui.NodeDetailViewModel
import com.singflow.android.ui.components.LatencySparkline
import com.singflow.android.ui.components.formatLatency
import com.singflow.android.ui.theme.healthColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 节点详情页：基础信息、健康统计、延迟趋势、二维码与操作。
 *
 * @param node 节点；为空表示已被删除
 * @param isSelected 是否当前选中
 * @param onBack 返回
 * @param onSetCurrent 设为当前节点
 * @param onToggleFavorite 切换收藏
 * @param onDelete 删除
 */
@Composable
fun NodeDetailScreen(
    node: ProxyNode?,
    isSelected: Boolean,
    onBack: () -> Unit,
    onSetCurrent: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    nodeDetailViewModel: NodeDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedConnection by remember { mutableStateOf<ClashConnection?>(null) }
    val detailState by nodeDetailViewModel.state.collectAsStateWithLifecycle()

    if (node == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("节点不存在或已被删除", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        return
    }

    DisposableEffect(node.id) {
        nodeDetailViewModel.startPolling(node.id)
        onDispose { nodeDetailViewModel.stopPolling() }
    }

    val samples = remember(node.id) { LatencyHistoryStore.samples(node.id) }
    val raw = node.rawShareUri.orEmpty()
    val qr by produceState<android.graphics.Bitmap?>(initialValue = null, raw) {
        value = if (raw.isBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { QrCodeGenerator.generate(raw) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "节点详情",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (node.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (node.isFavorite) "取消收藏" else "收藏",
                    tint = if (node.isFavorite) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = node.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${node.protocol.displayName} · ${node.server}:${node.port}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.countryLabel,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${node.healthTag} ${node.healthScore}分",
                        style = MaterialTheme.typography.labelLarge,
                        color = healthColor(node.healthScore)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                InfoLine("当前延迟", formatLatency(node.latencyMs))
                InfoLine("探测成功", "${node.probeSuccessCount}/${node.probeTotalCount}")
                InfoLine("EMA / 抖动", "${node.emaLatencyMs.toInt()} ms / ${node.latencyJitterMs.toInt()} ms")
                InfoLine("最近探测", formatTime(node.lastProbeTimeMs))
                if (node.consecutiveFailures > 0) {
                    InfoLine("连续失败", "${node.consecutiveFailures} 次")
                }
                if (node.cooldownUntilMs > 0L) {
                    InfoLine(
                        "熔断状态",
                        if (node.isCoolingDown()) "冷却至 ${formatTime(node.cooldownUntilMs)}" else "已恢复"
                    )
                }
                if (node.egressIp != null) {
                    InfoLine("实测出口", "${node.egressIp}（${node.countryNameCn}）")
                }
                if (isSelected) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "当前正在使用此节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text("延迟趋势", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        if (samples.size < 2) {
            Text(
                text = "暂无历史采样，先在节点页执行「一键探活」或等待自动巡检。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val success = samples.filter { it.isSuccess }
            val min = success.minOfOrNull { it.latencyMs }
            val max = success.maxOfOrNull { it.latencyMs }
            Text(
                text = if (min != null && max != null) {
                    "最近 ${samples.size} 次 · 失败 ${samples.size - success.size} 次 · 最低 $min ms · 最高 $max ms"
                } else {
                    "最近 ${samples.size} 次 · 全部失败"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            LatencySparkline(
                samples = samples,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "该节点连接（${detailState.connections.size}）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        when {
            !detailState.available -> Text(
                text = "连接 VPN 后可查看经过此节点的活跃连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            detailState.connections.isEmpty() -> Text(
                text = "暂无经过此节点的活跃连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                detailState.connections.forEach { connection ->
                    ConnectionRow(connection) { selectedConnection = connection }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("分享", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        if (raw.isBlank()) {
            Text(
                text = "该节点不是通过分享链接导入的，无法生成二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            qr?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "节点二维码",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(raw)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("复制链接")
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, raw)
                            putExtra(Intent.EXTRA_SUBJECT, node.displayName)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享节点"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("分享")
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onSetCurrent,
                enabled = !isSelected,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isSelected) "当前节点" else "设为当前节点")
            }
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除节点") },
            text = { Text("将删除「${node.displayName}」，确定继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    selectedConnection?.let { connection ->
        AlertDialog(
            onDismissRequest = { selectedConnection = null },
            title = { Text("连接详情") },
            text = {
                Column {
                    InfoLine("目标", connection.destination)
                    InfoLine("网络", connection.network.ifBlank { "未知" })
                    InfoLine("命中规则", connection.rule.ifBlank { "未命中规则" })
                    InfoLine("出站链", connection.chainText)
                    InfoLine("上传", formatBytes(connection.upload))
                    InfoLine("下载", formatBytes(connection.download))
                    InfoLine("连接 ID", connection.id)
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(connection.host))
                            selectedConnection = null
                        }
                    ) { Text("复制域名") }
                    TextButton(onClick = { selectedConnection = null }) { Text("关闭") }
                }
            }
        )
    }
}

@Composable
private fun ConnectionRow(connection: ClashConnection, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.host.ifBlank { connection.destination },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = connection.rule.ifBlank { "未命中规则" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "↑${formatBytes(connection.upload)} ↓${formatBytes(connection.download)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "从未"
    return runCatching {
        Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.getDefault()))
    }.getOrDefault("未知")
}
