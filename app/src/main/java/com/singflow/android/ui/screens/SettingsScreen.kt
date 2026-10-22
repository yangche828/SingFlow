package com.singflow.android.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.FailoverConfig
import com.singflow.android.core.model.FailoverPreset
import com.singflow.android.core.model.PerAppMode
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.ui.theme.SignalAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置页：DNS、TUN、IPv6、分应用代理、故障自动切换与开机自启。
 *
 * @param settings 当前设置
 * @param countries 节点库中实际存在的国家/地区（代码 to 中文名），用于默认国家选择
 * @param appRuleCount 已设置的应用分流规则数量
 * @param subscriptionCount 订阅数量
 * @param onOpenAppRouting 打开应用级国家分流页面
 * @param onOpenSubscriptions 打开订阅管理页面
 * @param onUpdate 保存设置
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    countries: List<Pair<String, String>> = emptyList(),
    appRuleCount: Int = 0,
    subscriptionCount: Int = 0,
    onOpenAppRouting: () -> Unit = {},
    onOpenSubscriptions: () -> Unit = {},
    onUpdate: (AppSettings) -> Unit
) {
    var remoteDns by remember(settings.remoteDns) { mutableStateOf(settings.remoteDns) }
    var directDns by remember(settings.directDns) { mutableStateOf(settings.directDns) }
    var mtuText by remember(settings.tunMtu) { mutableStateOf(settings.tunMtu.toString()) }
    var portText by remember(settings.mixedPort) { mutableStateOf(settings.mixedPort.toString()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showIntervalPicker by remember { mutableStateOf(false) }
    var showAdvancedFailover by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "DNS、TUN 与分应用代理",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))

        SettingsSection(title = "DNS") {
            OutlinedTextField(
                value = remoteDns,
                onValueChange = { remoteDns = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("远程 DoH / DoT") },
                supportingText = { Text("例：https://1.1.1.1/dns-query") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = directDns,
                onValueChange = { directDns = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("直连 DNS") },
                supportingText = { Text("例：223.5.5.5") },
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSection(title = "TUN 与本地入站") {
            OutlinedTextField(
                value = mtuText,
                onValueChange = { mtuText = it.filter { ch -> ch.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("TUN MTU") },
                supportingText = { Text("建议 9000；范围 1280 – 65535") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Mixed 入站端口") },
                supportingText = { Text("默认 7890，供本地应用与 URLTest 使用") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSection(title = "网络") {
            SwitchRow(
                title = "启用 IPv6",
                subtitle = "TUN 增加 fdfe:dcba:9876::1/126 与 IPv6 DNS",
                checked = settings.enableIpv6,
                onCheckedChange = { onUpdate(settings.copy(enableIpv6 = it)) }
            )
            SwitchRow(
                title = "开机自动连接",
                subtitle = "开机或应用升级后恢复上次隧道",
                checked = settings.autoConnectOnBoot,
                onCheckedChange = { onUpdate(settings.copy(autoConnectOnBoot = it)) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSection(title = "分应用代理") {
            SwitchRow(
                title = "启用分应用代理",
                subtitle = "默认始终排除 SingFlow 自身，防止回环",
                checked = settings.perAppProxyEnabled,
                onCheckedChange = { onUpdate(settings.copy(perAppProxyEnabled = it)) }
            )
            if (settings.perAppProxyEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    PerAppMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.perAppProxyMode == mode,
                            onClick = { onUpdate(settings.copy(perAppProxyMode = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = PerAppMode.entries.size),
                            label = { Text(mode.displayName) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showAppPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("选择应用（已选 ${settings.perAppPackages.size} 个）")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSection(title = "应用与分流") {
            OutlinedButton(
                onClick = onOpenAppRouting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("按应用指定国家（已设置 $appRuleCount 个）")
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenSubscriptions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("订阅管理（$subscriptionCount 个，12 小时自动更新）")
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "例如：AI 应用走美国、动漫应用走日本，其余跟随默认国家",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingsSection(title = "故障自动切换") {
            SwitchRow(
                title = "多节点国家分组",
                subtitle = "把全部节点编译为按国家分组的出站组，需重新连接后生效",
                checked = settings.countryRoutingEnabled,
                onCheckedChange = { onUpdate(settings.copy(countryRoutingEnabled = it)) }
            )
            Spacer(modifier = Modifier.height(6.dp))
            SwitchRow(
                title = "启用自动切换",
                subtitle = "节点断连时自动切往同国最高健康分节点，不重建 TUN",
                checked = settings.failover.enabled,
                onCheckedChange = {
                    onUpdate(settings.copy(failover = settings.failover.copy(enabled = it)))
                }
            )
            if (settings.failover.enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "切换方向",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                FailoverPreset.entries.forEach { preset ->
                    val selected = settings.failover.preset == preset
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onUpdate(settings.copy(failover = settings.failover.copy(preset = preset)))
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                onUpdate(settings.copy(failover = settings.failover.copy(preset = preset)))
                            }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = preset.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val countryCode = settings.failover.defaultCountryCode
                val countryLabel = if (countryCode.isBlank()) {
                    "智能（全局高分）"
                } else {
                    val name = countries.firstOrNull { it.first.equals(countryCode, ignoreCase = true) }?.second
                        ?: countryCode
                    "${ProxyNode.countryFlag(countryCode)} $name"
                }
                OutlinedButton(
                    onClick = { showCountryPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("默认国家：$countryLabel")
                }
                Spacer(modifier = Modifier.height(8.dp))
                val interval = settings.failover.activeCheckIntervalSec
                OutlinedButton(
                    onClick = { showIntervalPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (interval <= 0) {
                            "巡检周期：仅被动触发"
                        } else {
                            "巡检周期：${interval} 秒"
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { showAdvancedFailover = !showAdvancedFailover },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showAdvancedFailover) "收起细节参数" else "细节参数微调")
                }
                if (showAdvancedFailover) {
                    ParameterSlider(
                        label = "单次探活超时",
                        value = settings.failover.singleProbeTimeoutMs,
                        valueRange = 1000f..5000f,
                        steps = 7,
                        unit = "ms"
                    ) { value ->
                        onUpdate(
                            settings.copy(
                                failover = settings.failover.copy(singleProbeTimeoutMs = value)
                            )
                        )
                    }
                    ParameterSlider(
                        label = "连续失败确认次数",
                        value = settings.failover.failConfirmRetries,
                        valueRange = 1f..5f,
                        steps = 3,
                        unit = "次"
                    ) { value ->
                        onUpdate(
                            settings.copy(
                                failover = settings.failover.copy(failConfirmRetries = value)
                            )
                        )
                    }
                    ParameterSlider(
                        label = "择优健康分差",
                        value = settings.failover.switchHysteresisScoreDiff,
                        valueRange = 0f..30f,
                        steps = 29,
                        unit = "分"
                    ) { value ->
                        onUpdate(
                            settings.copy(
                                failover = settings.failover.copy(switchHysteresisScoreDiff = value)
                            )
                        )
                    }
                    ParameterSlider(
                        label = "择优延迟改善",
                        value = settings.failover.switchLatencyToleranceMs,
                        valueRange = 20f..300f,
                        steps = 13,
                        unit = "ms"
                    ) { value ->
                        onUpdate(
                            settings.copy(
                                failover = settings.failover.copy(switchLatencyToleranceMs = value)
                            )
                        )
                    }
                    ParameterSlider(
                        label = "慢速熔断阈值",
                        value = settings.failover.slowLatencyThresholdMs,
                        valueRange = 400f..2000f,
                        steps = 15,
                        unit = "ms"
                    ) { value ->
                        onUpdate(
                            settings.copy(
                                failover = settings.failover.copy(slowLatencyThresholdMs = value)
                            )
                        )
                    }
                    ParameterSlider(
                        label = "故障冷却时长",
                        value = settings.failover.deadNodeCooldownMinutes,
                        valueRange = 1f..60f,
                        steps = 58,
                        unit = "分钟"
                    ) { value ->
                        onUpdate(
                            settings.copy(
                                failover = settings.failover.copy(deadNodeCooldownMinutes = value)
                            )
                        )
                    }
                    SwitchRow(
                        title = "允许跨国防灾",
                        subtitle = "同国全部不可用时，按备用国家顺序切换",
                        checked = settings.failover.allowCrossCountryFallback,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                settings.copy(
                                    failover = settings.failover.copy(allowCrossCountryFallback = enabled)
                                )
                            )
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val mtu = mtuText.toIntOrNull()?.coerceIn(1280, 65535) ?: settings.tunMtu
                    val port = portText.toIntOrNull()?.coerceIn(1024, 65535) ?: settings.mixedPort
                    onUpdate(
                        settings.copy(
                            remoteDns = remoteDns.trim(),
                            directDns = directDns.trim(),
                            tunMtu = mtu,
                            mixedPort = port
                        )
                    )
                    savedHint = "已保存，新配置将在下次连接 / 热切换时生效"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("保存 DNS 与端口")
            }
            OutlinedButton(
                onClick = {
                    onUpdate(
                        AppSettings.DEFAULT.copy(
                            selectedNodeId = settings.selectedNodeId,
                            autoConnectOnBoot = settings.autoConnectOnBoot,
                            perAppPackages = settings.perAppPackages
                        )
                    )
                    savedHint = "已恢复默认 DNS / MTU / 路由模式"
                }
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }

        savedHint?.let { hint ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = SignalAmber
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "关于 SingFlow",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "版本 2.0.0 · 包名 com.singflow.android\n支持 Android 13 (API 33) – Android 17 (API 37)\n配置规范：sing-box 1.14+ / 1.15+",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }

    if (showAppPicker) {
        AppPickerDialog(
            selectedPackages = settings.perAppPackages,
            onDismiss = { showAppPicker = false },
            onConfirm = { packages ->
                onUpdate(settings.copy(perAppPackages = packages))
                showAppPicker = false
            }
        )
    }

    if (showCountryPicker) {
        AlertDialog(
            onDismissRequest = { showCountryPicker = false },
            title = { Text("默认国家") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item(key = "auto") {
                        CountryOptionRow(
                            label = "智能选择（全局高分）",
                            selected = settings.failover.defaultCountryCode.isBlank(),
                            onClick = {
                                onUpdate(
                                    settings.copy(
                                        failover = settings.failover.copy(defaultCountryCode = "")
                                    )
                                )
                                showCountryPicker = false
                            }
                        )
                    }
                    items(countries, key = { it.first }) { (code, name) ->
                        CountryOptionRow(
                            label = "${ProxyNode.countryFlag(code)} $name",
                            selected = settings.failover.defaultCountryCode.equals(code, ignoreCase = true),
                            onClick = {
                                onUpdate(
                                    settings.copy(
                                        failover = settings.failover.copy(defaultCountryCode = code)
                                    )
                                )
                                showCountryPicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCountryPicker = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showIntervalPicker) {
        AlertDialog(
            onDismissRequest = { showIntervalPicker = false },
            title = { Text("主动巡检周期") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(FailoverConfig.ACTIVE_CHECK_INTERVAL_OPTIONS, key = { it }) { option ->
                        CountryOptionRow(
                            label = if (option <= 0) "仅被动触发（不主动巡检）" else "$option 秒",
                            selected = settings.failover.activeCheckIntervalSec == option,
                            onClick = {
                                onUpdate(
                                    settings.copy(
                                        failover = settings.failover.copy(activeCheckIntervalSec = option)
                                    )
                                )
                                showIntervalPicker = false
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntervalPicker = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

/**
 * 细节参数滑块：拖动时只更新本地状态，松手后才写入 DataStore，避免频繁落盘。
 */
@Composable
private fun ParameterSlider(
    label: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String,
    onCommit: (Int) -> Unit
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${local.toInt()} $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local.toInt()) },
            valueRange = valueRange,
            steps = steps
        )
    }
}

/**
 * 默认国家选项行。
 */
@Composable
private fun CountryOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 应用选择对话框。
 */
@Composable
private fun AppPickerDialog(
    selectedPackages: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(selectedPackages) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .map { info ->
                        InstalledApp(
                            label = runCatching { info.loadLabel(packageManager).toString() }
                                .getOrDefault(info.packageName),
                            packageName = info.packageName
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }.getOrElse { emptyList() }
        }
        apps = loaded
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索应用") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                when {
                    loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                    filtered.isEmpty() -> {
                        Text(
                            text = "未找到应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                            items(filtered, key = { it.packageName }) { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected = if (selected.contains(app.packageName)) {
                                                selected - app.packageName
                                            } else {
                                                selected + app.packageName
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = selected.contains(app.packageName),
                                        onCheckedChange = { checked ->
                                            selected = if (checked) {
                                                selected + app.packageName
                                            } else {
                                                selected - app.packageName
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text("确定（${selected.size}）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private data class InstalledApp(
    val label: String,
    val packageName: String
)
