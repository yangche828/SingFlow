package com.singflow.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.singflow.android.ui.theme.SignalGreen
import com.singflow.android.ui.theme.SignalRed

/**
 * 导入节点底部弹层。
 *
 * @param onDismiss 关闭
 * @param onImportText 导入指定文本（链接 / 多行 / 订阅 / JSON）
 * @param onGenerateSamples 生成示例节点
 * @param message 导入结果提示
 * @param isImporting 是否正在导入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBottomSheet(
    onDismiss: () -> Unit,
    onImportText: (String) -> Unit,
    onGenerateSamples: () -> Unit,
    message: String?,
    isImporting: Boolean
) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    var clipboardHint by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                text = "导入节点",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "支持 vmess / vless / trojan / ss / hysteria2 / tuic / wireguard 链接、Base64 订阅与 sing-box JSON。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedButton(
                onClick = {
                    val clipText = clipboard.getText()?.text.orEmpty()
                    if (clipText.isBlank()) {
                        clipboardHint = "剪贴板为空，请先复制节点链接"
                    } else {
                        clipboardHint = null
                        onImportText(clipText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isImporting
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = "  从剪贴板一键导入", fontWeight = FontWeight.SemiBold)
            }

            clipboardHint?.let { hint ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("多行 V2Ray 链接 / Base64 订阅原文") },
                placeholder = { Text("vless://uuid@host:443?security=reality#节点名称") },
                minLines = 5,
                maxLines = 10,
                enabled = !isImporting
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onImportText(text)
                        text = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = text.isNotBlank() && !isImporting
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null)
                }
                Text(text = "  解析并导入", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onGenerateSamples,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isImporting
            ) {
                Icon(Icons.Rounded.Science, contentDescription = null)
                Text(text = "  生成 5 个全协议示例节点", fontWeight = FontWeight.SemiBold)
            }

            message?.let { result ->
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (result.startsWith("成功")) SignalGreen else SignalRed
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("关闭")
            }
        }
    }
}
