package com.singflow.android.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singflow.android.core.model.VpnState
import com.singflow.android.ui.theme.MonoTextStyle
import com.singflow.android.ui.theme.SignalAmber
import com.singflow.android.ui.theme.SignalRed
import com.singflow.android.ui.theme.AuroraCyan

/**
 * 日志与配置预览页面。
 */
@Composable
fun LogsScreen(
    state: VpnState,
    configJson: String,
    onClearLogs: () -> Unit,
    onCopyConfig: () -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("实时内核日志", "sing-box JSON 配置")

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
                    text = "运行日志",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "内核状态、编译诊断与实时 JSON 预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (tabIndex == 0) {
                IconButton(onClick = onClearLogs) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteSweep,
                        contentDescription = "清空日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(onClick = onCopyConfig) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "复制 JSON",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tabIndex == index,
                    onClick = { tabIndex = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    label = { Text(label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tabIndex == 0) {
            LogList(state = state)
        } else {
            JsonPreview(configJson = configJson, nodeName = state.activeNodeName)
        }
    }
}

@Composable
private fun LogList(state: VpnState) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.lastIndex)
        }
    }
    if (state.logs.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = "暂无日志。连接 VPN 后将显示内核编译诊断与 Clash API 日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(state.logs) { entry ->
            Text(
                text = entry.format(),
                style = MonoTextStyle,
                color = levelColor(entry.level)
            )
        }
        item { Spacer(modifier = Modifier.height(28.dp)) }
    }
}

@Composable
private fun JsonPreview(configJson: String, nodeName: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (nodeName.isBlank()) "当前无选中节点" else "当前节点：$nodeName",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = configJson.ifBlank { "// 选择节点后将在此展示完整的 sing-box 1.14+ 配置" },
                style = MonoTextStyle,
                color = AuroraCyan
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

private fun levelColor(level: String): Color = when (level.uppercase()) {
    "ERROR" -> SignalRed
    "WARN", "WARNING" -> SignalAmber
    "DEBUG" -> Color(0xFF7C8CA6)
    else -> Color(0xFFCBD5E1)
}
