package com.singflow.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    onScanGeoIp: () -> Unit = {},
    countryGroupOrder: List<String> = emptyList(),
    onCountryGroupOrderChange: (List<String>) -> Unit = {}
) {
    var sortByLatency by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var confirmGeoScan by remember { mutableStateOf(false) }
    var showGroupOrder by remember { mutableStateOf(false) }
    var showNodeMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var collapsedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    var batchHint by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val filteredNodes = remember(nodes, searchQuery) {
        filterNodes(nodes, searchQuery)
    }
    val groups = remember(filteredNodes, sortByLatency, countryGroupOrder) {
        buildGroups(filteredNodes, sortByLatency, countryGroupOrder)
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
                Box {
                    IconButton(onClick = { showNodeMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(
                        expanded = showNodeMenu,
                        onDismissRequest = { showNodeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("分组排序") },
                            onClick = {
                                showNodeMenu = false
                                showGroupOrder = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清空全部节点") },
                            onClick = {
                                showNodeMenu = false
                                onClearAll()
                            }
                        )
                    }
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

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索节点 / 服务器 / 国家") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

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
                        text = if (nodes.isEmpty()) "还没有节点" else "没有匹配的节点",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (nodes.isEmpty()) {
                            "点击右上角 + 导入，或生成示例节点"
                        } else {
                            "换个关键词试试"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (nodes.isEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedButton(onClick = onImportClick) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Text("  导入节点")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groups.forEach { group ->
                    val groupKey = group.countryCode.ifBlank { group.title }
                    val expanded = searchQuery.isNotBlank() || groupKey !in collapsedGroups
                    item(key = "header-${group.title}") {
                        NodeGroupHeader(
                            title = group.title,
                            count = group.nodes.size,
                            expanded = expanded,
                            onToggle = {
                                collapsedGroups = if (groupKey in collapsedGroups) {
                                    collapsedGroups - groupKey
                                } else {
                                    collapsedGroups + groupKey
                                }
                            }
                        )
                    }
                    if (expanded) {
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

    if (showGroupOrder) {
        GroupOrderDialog(
            groups = groups.filter { it.countryCode.isNotBlank() }
                .map { it.countryCode to it.title },
            onDismiss = { showGroupOrder = false },
            onSave = { order ->
                onCountryGroupOrderChange(order)
                showGroupOrder = false
                batchHint = "分组顺序已保存"
            }
        )
    }
}

/**
 * 国家/地区分组。
 */
internal data class NodeGroup(
    val title: String,
    val nodes: List<ProxyNode>,
    val countryCode: String = ""
)

/**
 * 构建分组：收藏组固定置顶，国家组按用户自定义顺序，未排序的按中文名，未知地区放最后。
 */
internal fun buildGroups(
    nodes: List<ProxyNode>,
    sortByLatency: Boolean,
    countryOrder: List<String> = emptyList()
): List<NodeGroup> {
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
        groups += NodeGroup("⭐ 我的收藏", sorted(favorites))    }

    nodes.filterNot { it.isFavorite }
        .groupBy { it.countryCode to it.countryNameCn }
        .entries
        .sortedWith(
            compareBy(
                { entry ->
                    countryOrder.indexOfFirst { it.equals(entry.key.first, ignoreCase = true) }
                        .takeIf { it >= 0 } ?: Int.MAX_VALUE
                },
                { entry ->
                    val ordered = countryOrder.any { it.equals(entry.key.first, ignoreCase = true) }
                    when {
                        ordered -> 0
                        entry.key.first == "UN" -> 2
                        else -> 1
                    }
                },
                { it.key.second }
            )
        )
        .forEach { (key, list) ->
            val title = "${ProxyNode.countryFlag(key.first)} ${key.second}"
            groups += NodeGroup(title, sorted(list), key.first)
        }
    return groups
}

/**
 * 节点搜索过滤：名称、服务器、国家（中文名/代码）大小写不敏感。
 */
internal fun filterNodes(nodes: List<ProxyNode>, query: String): List<ProxyNode> {
    val keyword = query.trim()
    if (keyword.isBlank()) return nodes
    return nodes.filter { node ->
        node.displayName.contains(keyword, ignoreCase = true) ||
            node.name.contains(keyword, ignoreCase = true) ||
            node.server.contains(keyword, ignoreCase = true) ||
            node.countryNameCn.contains(keyword, ignoreCase = true) ||
            node.countryCode.contains(keyword, ignoreCase = true)
    }
}

/**
 * 分组顺序对话框：上/下移动，保存后持久化到设置。
 */
@Composable
private fun GroupOrderDialog(
    groups: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val labels = remember(groups) { groups.toMap() }
    var order by remember(groups) { mutableStateOf(groups.map { it.first }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组排序") },
        text = {
            if (order.isEmpty()) {
                Text(
                    text = "暂无国家分组",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(order, key = { _, code -> code }) { index, code ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = labels[code] ?: code,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        order = order.toMutableList().apply {
                                            add(index - 1, removeAt(index))
                                        }
                                    }
                                },
                                enabled = index > 0,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    contentDescription = "上移",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (index < order.lastIndex) {
                                        order = order.toMutableList().apply {
                                            add(index + 1, removeAt(index))
                                        }
                                    }
                                },
                                enabled = index < order.lastIndex,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowDownward,
                                    contentDescription = "下移",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(order) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 分组标题。
 */
@Composable
private fun NodeGroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
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
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "折叠分组" else "展开分组",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
