package com.singflow.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.ui.components.NodeItemCard

/**
 * 节点库页面：收藏置顶 + 按国家/地区自动分组。
 */
@Composable
fun NodesScreen(
    nodes: List<ProxyNode>,
    selectedId: String,
    testing: Boolean,
    statusMessage: String?,
    onSelect: (ProxyNode) -> Unit,
    onDelete: (String) -> Unit,
    onToggleFavorite: (ProxyNode) -> Unit,
    onTestAll: () -> Unit,
    onImportClick: () -> Unit,
    onClearAll: () -> Unit,
    onBatchDelete: (Set<String>) -> Unit = {},
    onBatchFavorite: (Set<String>, Boolean) -> Unit = { _, _ -> },
    geoScanning: Boolean = false,
    canScanGeoIp: Boolean = false,
    onScanGeoIp: () -> Unit = {}
) {
    var sortByLatency by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var confirmGeoScan by remember { mutableStateOf(false) }
    var batchHint by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val groups = remember(nodes, sortByLatency) {
        buildGroups(nodes, sortByLatency)
    }

    LaunchedEffect(nodes) {
        selectedIds = selectedIds.intersect(nodes.map { it.id }.toSet())
        if (nodes.isEmpty()) selectionMode = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (selectionMode) "已选 ${selectedIds.size} 个" else "节点库",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "共 ${nodes.size} 个节点 · ${groups.size} 个分组 · 收藏 ${nodes.count { it.isFavorite }} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selectionMode) {
                IconButton(onClick = {
                    selectionMode = false
                    selectedIds = emptySet()
                    batchHint = null
                }) {
                    Icon(Icons.Rounded.Done, contentDescription = "完成批量选择")
                }
            } else {
                IconButton(onClick = { selectionMode = true }) {
                    Icon(Icons.Rounded.Checklist, contentDescription = "批量管理")
                }
                IconButton(onClick = onImportClick) {
                    Icon(Icons.Rounded.Add, contentDescription = "导入节点")
                }
                IconButton(onClick = onClearAll) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        contentDescription = "清空节点",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectionMode) {
            val allSelected = nodes.isNotEmpty() && selectedIds.size == nodes.size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectedIds = if (allSelected) emptySet() else nodes.map { it.id }.toSet()
                    }
                ) {
                    Text(if (allSelected) "取消全选" else "全选")
                }
                OutlinedButton(
                    onClick = {
                        if (selectedIds.isEmpty()) {
                            batchHint = "请先选择节点"
                        } else {
                            onBatchFavorite(selectedIds, true)
                            batchHint = "已收藏 ${selectedIds.size} 个节点"
                        }
                    },
                    enabled = selectedIds.isNotEmpty()
                ) { Text("收藏") }
                OutlinedButton(
                    onClick = {
                        if (selectedIds.isEmpty()) {
                            batchHint = "请先选择节点"
                        } else {
                            onBatchFavorite(selectedIds, false)
                            batchHint = "已取消收藏 ${selectedIds.size} 个节点"
                        }
                    },
                    enabled = selectedIds.isNotEmpty()
                ) { Text("取消收藏") }
                OutlinedButton(
                    onClick = {
                        val links = nodes.filter { it.id in selectedIds }
                            .mapNotNull { it.rawShareUri?.takeIf(String::isNotBlank) }
                            .distinct()
                        if (links.isEmpty()) {
                            batchHint = "选中节点没有可导出的分享链接"
                        } else {
                            clipboard.setText(AnnotatedString(links.joinToString("\n")))
                            batchHint = "已复制 ${links.size} 条分享链接"
                        }
                    },
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制链接")
                }
                OutlinedButton(
                    onClick = { confirmBatchDelete = true },
                    enabled = selectedIds.isNotEmpty()
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
            batchHint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTestAll,
                    enabled = !testing && nodes.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("探活中…")
                    } else {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("一键探活")
                    }
                }
                FilledTonalButton(
                    onClick = { sortByLatency = !sortByLatency },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (sortByLatency) "延迟排序中" else "延迟排序")
                }
            }
            if (nodes.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmGeoScan = true },
                    enabled = canScanGeoIp && !geoScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (geoScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("实测出口中…")
                    } else {
                        Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("实测节点真实出口")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "还没有节点",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "点击右上角 + 导入，或生成示例节点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onImportClick) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text("  导入节点")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { group ->
                    item(key = "header-${group.title}") {
                        NodeGroupHeader(title = group.title, count = group.nodes.size)
                    }
                    items(group.nodes, key = { it.id }) { node ->
                        NodeItemCard(
                            node = node,
                            selected = node.id == selectedId,
                            onClick = { onSelect(node) },
                            onDelete = { onDelete(node.id) },
                            onToggleFavorite = { onToggleFavorite(node) },
                            selectionMode = selectionMode,
                            batchSelected = node.id in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (node.id in selectedIds) {
                                    selectedIds - node.id
                                } else {
                                    selectedIds + node.id
                                }
                            }
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }

    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text("批量删除节点") },
            text = { Text("将删除选中的 ${selectedIds.size} 个节点，确定继续？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBatchDelete(selectedIds)
                        confirmBatchDelete = false
                        selectionMode = false
                        selectedIds = emptySet()
                        batchHint = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchDelete = false }) { Text("取消") }
            }
        )
    }

    if (confirmGeoScan) {
        AlertDialog(
            onDismissRequest = { confirmGeoScan = false },
            title = { Text("实测节点真实出口") },
            text = {
                Text(
                    "将逐个把 Selector 切换到每个节点并请求 Cloudflare Trace，读取真实出口 IP 与国家，" +
                        "完成后恢复原节点。扫描期间网络会短暂中断（每个节点约 2–4 秒），确定继续？"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmGeoScan = false
                        onScanGeoIp()
                    }
                ) { Text("开始实测") }
            },
            dismissButton = {
                TextButton(onClick = { confirmGeoScan = false }) { Text("取消") }
            }
        )
    }
}

/**
 * 国家/地区分组。
 */
private data class NodeGroup(
    val title: String,
    val nodes: List<ProxyNode>
)

/**
 * 构建分组：收藏组固定置顶，其余按国家中文名排序，未知地区放最后。
 */
private fun buildGroups(nodes: List<ProxyNode>, sortByLatency: Boolean): List<NodeGroup> {
    if (nodes.isEmpty()) return emptyList()

    fun sorted(list: List<ProxyNode>): List<ProxyNode> = if (sortByLatency) {
        list.sortedWith(
            compareBy(
                { if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs },
                { it.name }
            )
        )
    } else {
        list.sortedWith(compareByDescending<ProxyNode> { it.healthScore }.thenBy { it.name })
    }

    val groups = mutableListOf<NodeGroup>()
    val favorites = nodes.filter { it.isFavorite }
    if (favorites.isNotEmpty()) {
        groups += NodeGroup("⭐ 我的收藏", sorted(favorites))
    }

    nodes.filterNot { it.isFavorite }
        .groupBy { it.countryCode to it.countryNameCn }
        .entries
        .sortedWith(
            compareBy(
                { it.key.first == "UN" },
                { it.key.second }
            )
        )
        .forEach { (key, list) ->
            val title = "${ProxyNode.countryFlag(key.first)} ${key.second}"
            groups += NodeGroup(title, sorted(list))
        }
    return groups
}

/**
 * 分组标题。
 */
@Composable
private fun NodeGroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
