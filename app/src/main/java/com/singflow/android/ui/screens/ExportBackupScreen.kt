package com.singflow.android.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.singflow.android.ui.BackupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出与备份页面：节点链接二维码、分享，以及本地备份导入导出。
 *
 * @param nodes 全部节点
 * @param selectedNode 当前节点
 * @param onBack 返回
 */
@Composable
fun ExportBackupScreen(
    nodes: List<ProxyNode>,
    selectedNode: ProxyNode?,
    onBack: () -> Unit,
    backupViewModel: BackupViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val message by backupViewModel.message.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val payload = backupViewModel.buildBackup()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(payload.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入文件")
                }
            }.onSuccess {
                backupViewModel.notify("备份已导出")
            }.onFailure {
                backupViewModel.notify("导出失败：${it.message}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                backupViewModel.notify("读取备份文件失败")
            } else {
                backupViewModel.restore(text)
            }
        }
    }

    val shareText = remember(nodes) {
        nodes.mapNotNull { it.rawShareUri?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("\n")
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "导出与备份",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${nodes.size} 个节点 · 本地 JSON 备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { backupViewModel.clearMessage() }) { Text("知道了") }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Text(
            text = "当前节点",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (selectedNode == null) {
            Text(
                text = "尚未选择节点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = selectedNode.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${selectedNode.protocol.displayName} · ${selectedNode.server}:${selectedNode.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            val raw = selectedNode.rawShareUri.orEmpty()
            if (raw.isBlank()) {
                Text(
                    text = "该节点不是通过分享链接导入的，无法生成二维码；请在节点库重新导入分享链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val qr by produceState<Bitmap?>(initialValue = null, raw) {
                    value = withContext(Dispatchers.Default) { QrCodeGenerator.generate(raw) }
                }
                qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "节点二维码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(raw))
                            backupViewModel.notify("分享链接已复制")
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("复制")
                    }
                    OutlinedButton(
                        onClick = { shareText(context, raw, selectedNode.displayName) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("分享")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "全部节点链接",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (shareText.isBlank()) "暂无可导出的分享链接" else "共 ${shareText.lines().size} 条分享链接",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (shareText.isBlank()) {
                        backupViewModel.notify("暂无可导出的分享链接")
                    } else {
                        clipboard.setText(AnnotatedString(shareText))
                        backupViewModel.notify("已复制全部分享链接")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("复制全部")
            }
            OutlinedButton(
                onClick = {
                    if (shareText.isBlank()) {
                        backupViewModel.notify("暂无可导出的分享链接")
                    } else {
                        shareText(context, shareText, "SingFlow 节点")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("分享全部")
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "本地备份",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "备份包含节点、设置、订阅与应用分流规则；恢复为合并模式，不会清空现有数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { exportLauncher.launch("singflow-backup.json") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("导出备份")
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("恢复备份")
            }
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

private fun shareText(context: android.content.Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, title)
    }
    context.startActivity(Intent.createChooser(intent, "分享节点"))
}
