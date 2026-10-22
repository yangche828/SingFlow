package com.singflow.android.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.probe.ClashConnection
import com.singflow.android.core.probe.ClashProxyGroup
import com.singflow.android.core.probe.LatencySample
import com.singflow.android.ui.components.LatencySparkline
import com.singflow.android.ui.DiagnosticsViewModel
import com.singflow.android.ui.components.formatBytes

/**
 * 连接诊断页面：当前状态、代理组、累计流量与活跃连接。
 *
 * @param vpnState VPN 运行态
 * @param selectedNode 当前节点
 * @param onBack 返回
 */
@Composable
fun DiagnosticsScreen(
    vpnState: VpnState,
    selectedNode: ProxyNode?,
    onBack: () -> Unit,
    diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by diagnosticsViewModel.state.collectAsStateWithLifecycle()

    DisposableEffect(selectedNode?.id) {
        diagnosticsViewModel.bindNode(selectedNode?.id)
        diagnosticsViewModel.startPolling()
        onDispose { diagnosticsViewModel.stopPolling() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "连接诊断",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (state.available) "Clash API 已连接 · 2 秒刷新" else "Clash API 不可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { diagnosticsViewModel.refresh() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 24.dp
            )
        ) {
            item(key = "status") {
                StatusCard(vpnState = vpnState, selectedNode = selectedNode)
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (state.latencyHistory.size >= 2) {
                item(key = "latency-history") {
                    LatencyTrendCard(state.latencyHistory)
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            if (!state.available) {
                item(key = "unavailable") {
                    Text(
                        text = "请先连接 VPN。诊断数据来自内核 Clash API，未连接时不产生代理组与连接信息。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                item(key = "groups-header") {
                    SectionTitle("代理组（${state.groups.size}）")
                }
                items(state.groups, key = { "group-${it.tag}" }) { group ->
                    GroupRow(group)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }

                item(key = "rules-header") {
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionTitle("规则命中（活跃连接）")
                }
                if (state.ruleHits.isEmpty()) {
                    item(key = "rules-empty") {
                        Text(
                            text = "暂无连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                } else {
                    items(state.ruleHits.take(10), key = { "rule-${it.label}" }) { hit ->
                        CountRow(label = hit.label, count = hit.count)
                    }
                }

                item(key = "chains-header") {
                    Spacer(modifier = Modifier.height(12.dp))
                    SectionTitle("出站链分布")
                }
                if (state.chainHits.isEmpty()) {
                    item(key = "chains-empty") {
                        Text(
                            text = "暂无连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                } else {
                    items(state.chainHits.take(10), key = { "chain-${it.label}" }) { hit ->
                        CountRow(label = hit.label, count = hit.count)
                    }
                }

                item(key = "traffic-header") {
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionTitle("累计流量")
                }
                item(key = "traffic") {
                    val traffic = state.traffic
                    Text(
                        text = if (traffic == null) {
                            "暂无数据"
                        } else {
                            "↑ ${formatBytes(traffic.uploadTotal)}    ↓ ${formatBytes(traffic.downloadTotal)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                item(key = "connections-header") {
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionTitle("活跃连接（${state.traffic?.connections?.size ?: 0}）")
                }
                val connections = state.traffic?.connections.orEmpty()
                if (connections.isEmpty()) {
                    item(key = "connections-empty") {
                        Text(
                            text = "当前没有活跃连接",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(connections, key = { it.id.ifBlank { "${it.host}-${it.destination}-${it.upload}" } }) { connection ->
                        ConnectionRow(connection)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyTrendCard(samples: List<LatencySample>) {
    val latest = samples.last().latencyMs
    val min = samples.minOf { it.latencyMs }
    val max = samples.maxOf { it.latencyMs }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前节点延迟趋势",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$latest ms",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "最近 ${samples.size} 次 · 最低 $min ms · 最高 $max ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val lineColor = MaterialTheme.colorScheme.primary
            LatencySparkline(
                samples = samples,
                color = lineColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
            )
        }
    }
}

@Composable
private fun StatusCard(vpnState: VpnState, selectedNode: ProxyNode?) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = when {
                    vpnState.isConnected -> "隧道已建立 · ${vpnState.engineMode}"
                    else -> "隧道未连接"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedNode?.let { "${it.displayName}（${it.countryLabel} · ${it.healthTag} ${it.healthScore}分）" }
                    ?: "尚未选择节点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (vpnState.isDiagnosticMode) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "诊断模式：内核未加载，流量不转发",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun GroupRow(group: ClashProxyGroup) {    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = group.tag, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${group.type} · ${group.members.size} 个成员",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = group.now.ifBlank { "未选择" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(130.dp)
        )
    }
}

@Composable
private fun ConnectionRow(connection: ClashConnection) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = connection.host.ifBlank { connection.destination },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = connection.network.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = buildString {
                append(connection.destination)
                if (connection.rule.isNotBlank()) append(" · ${connection.rule}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (connection.chains.isNotEmpty()) {
            Text(
                text = connection.chainText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "↑ ${formatBytes(connection.upload)}  ↓ ${formatBytes(connection.download)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
