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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.model.CountryNames
import com.singflow.android.core.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 应用级国家分流页面。
 *
 * 为每个已安装应用选择：跟随全局 / 指定国家 / 收藏组 / 直连 / 断网阻断。
 *
 * @param rules 已保存的分流规则
 * @param countries 节点库中实际存在的国家/地区（代码 to 中文名）
 * @param multiNodeEnabled 是否已开启多节点国家分组
 * @param onBack 返回
 * @param onSave 保存规则
 * @param onDelete 删除规则（恢复跟随全局）
 */
@Composable
fun AppRoutingScreen(
    rules: List<AppRouteRuleEntity>,
    countries: List<Pair<String, String>>,
    multiNodeEnabled: Boolean,
    onBack: () -> Unit,
    onSave: (AppRouteRuleEntity) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val ruleByPackage = remember(rules) { rules.associateBy { it.packageName } }
    var apps by remember { mutableStateOf<List<RoutableApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<RoutableApp?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadRoutableApps(packageManager) }
        loading = false
    }

    val filtered = remember(apps, query, ruleByPackage) {
        val keyword = query.trim()
        val matched = if (keyword.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
        }
        matched.sortedWith(
            compareByDescending<RoutableApp> { ruleByPackage.containsKey(it.packageName) }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "应用国家分流",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "已设置 ${rules.size} 个应用 · ${countries.size} 个国家可用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!multiNodeEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "多节点国家分组未开启。请先在「设置 → 故障自动切换」中开启，否则规则不会写入内核配置。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("搜索应用") },
            singleLine = true
        )

        if (loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else if (filtered.isEmpty()) {
            Text(
                text = "未找到应用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRouteRow(
                        app = app,
                        rule = ruleByPackage[app.packageName],
                        countryName = countryNameOf(ruleByPackage[app.packageName], countries),
                        onClick = { editing = app }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    editing?.let { app ->
        RouteModeDialog(
            app = app,
            currentRule = ruleByPackage[app.packageName],
            countries = countries,
            onDismiss = { editing = null },
            onSelect = { rule ->
                if (rule == null) onDelete(app.packageName) else onSave(rule)
                editing = null
            }
        )
    }
}

@Composable
private fun AppRouteRow(
    app: RoutableApp,
    rule: AppRouteRuleEntity?,
    countryName: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
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
        Text(
            text = routeLabel(rule, countryName),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (rule == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 路由模式选择对话框。
 */
@Composable
private fun RouteModeDialog(
    app: RoutableApp,
    currentRule: AppRouteRuleEntity?,
    countries: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSelect: (AppRouteRuleEntity?) -> Unit
) {
    fun build(mode: String, country: String = ""): AppRouteRuleEntity = AppRouteRuleEntity(
        packageName = app.packageName,
        appName = app.label,
        routeMode = mode,
        targetCountryCode = country
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                item(key = "default") {
                    ModeRow(
                        label = "跟随全局",
                        selected = currentRule == null,
                        onClick = { onSelect(null) }
                    )
                }
                item(key = "direct") {
                    ModeRow(
                        label = "直连",
                        selected = currentRule?.routeMode == AppRouteRuleEntity.MODE_DIRECT,
                        onClick = { onSelect(build(AppRouteRuleEntity.MODE_DIRECT)) }
                    )
                }
                item(key = "block") {
                    ModeRow(
                        label = "阻断（断网）",
                        selected = currentRule?.routeMode == AppRouteRuleEntity.MODE_BLOCK,
                        onClick = { onSelect(build(AppRouteRuleEntity.MODE_BLOCK)) }
                    )
                }
                item(key = "favorites") {
                    ModeRow(
                        label = "收藏节点组",
                        selected = currentRule?.routeMode == AppRouteRuleEntity.MODE_FAVORITES,
                        onClick = { onSelect(build(AppRouteRuleEntity.MODE_FAVORITES)) }
                    )
                }
                item(key = "country-header") {
                    Text(
                        text = "指定国家",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                    )
                }
                if (countries.isEmpty()) {
                    item(key = "country-empty") {
                        Text(
                            text = "节点库暂无国家分组，请先导入节点",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                } else {
                    items(countries, key = { "country-${it.first}" }) { (code, name) ->
                        val selected = currentRule?.let { rule ->
                            rule.routeMode == AppRouteRuleEntity.MODE_COUNTRY &&
                                rule.targetCountryCode.equals(code, ignoreCase = true)
                        } == true
                        ModeRow(
                            label = "${ProxyNode.countryFlag(code)} $name",
                            selected = selected,
                            onClick = { onSelect(build(AppRouteRuleEntity.MODE_COUNTRY, code)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun routeLabel(rule: AppRouteRuleEntity?, countryName: String): String = when (rule?.routeMode) {
    AppRouteRuleEntity.MODE_DIRECT -> "直连"
    AppRouteRuleEntity.MODE_BLOCK -> "阻断"
    AppRouteRuleEntity.MODE_FAVORITES -> "收藏组"
    AppRouteRuleEntity.MODE_COUNTRY -> countryName.ifBlank { rule.targetCountryCode }
    else -> "跟随全局"
}

private fun countryNameOf(
    rule: AppRouteRuleEntity?,
    countries: List<Pair<String, String>>
): String {
    if (rule == null || rule.routeMode != AppRouteRuleEntity.MODE_COUNTRY) return ""
    return countries.firstOrNull { it.first.equals(rule.targetCountryCode, ignoreCase = true) }
        ?.second
        ?: CountryNames.nameOf(rule.targetCountryCode)
}

private fun loadRoutableApps(packageManager: PackageManager): List<RoutableApp> {
    val self = "com.singflow.android"
    return runCatching {
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != self }
            .map { info ->
                RoutableApp(
                    label = runCatching { info.loadLabel(packageManager).toString() }
                        .getOrDefault(info.packageName),
                    packageName = info.packageName
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }.getOrElse { emptyList() }
}

/**
 * 已安装应用。
 */
private data class RoutableApp(
    val label: String,
    val packageName: String
)

