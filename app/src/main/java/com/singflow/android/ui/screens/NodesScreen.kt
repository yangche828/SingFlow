package com.singflow.android.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.ui.components.NodeItemCard

/**
 * 节点库页面。
 */
@Composable
fun NodesScreen(
    nodes: List<ProxyNode>,
    selectedId: String,
    testing: Boolean,
    statusMessage: String?,
    onSelect: (ProxyNode) -> Unit,
    onDelete: (String) -> Unit,
    onTestAll: () -> Unit,
    onImportClick: () -> Unit,
    onClearAll: () -> Unit
) {
    var sortByLatency by remember { mutableStateOf(false) }
    val displayed = remember(nodes, sortByLatency) {
        if (sortByLatency) {
            nodes.sortedWith(compareBy({ if (it.latencyMs < 0) Int.MAX_VALUE else it.latencyMs }, { it.name }))
        } else {
            nodes
        }
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
                    text = "节点库",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "共 ${nodes.size} 个节点 · 已选 ${nodes.count { it.id == selectedId }} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

        Spacer(modifier = Modifier.height(12.dp))

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
                    Text("测速中…")
                } else {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("一键测速")
                }
            }
            FilledTonalButton(
                onClick = { sortByLatency = !sortByLatency },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (sortByLatency) "按延迟排序中" else "按延迟排序")
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

        if (displayed.isEmpty()) {
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
                items(displayed, key = { it.id }) { node ->
                    NodeItemCard(
                        node = node,
                        selected = node.id == selectedId,
                        onClick = { onSelect(node) },
                        onDelete = { onDelete(node.id) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(28.dp))
                }
            }
        }
    }
}
