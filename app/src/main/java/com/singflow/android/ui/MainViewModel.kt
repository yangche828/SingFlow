package com.singflow.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.config.SingBoxConfigPipeline
import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.database.AppRouteRuleRepository
import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.database.PreferencesManager
import com.singflow.android.core.database.SubscriptionEntity
import com.singflow.android.core.engine.NodeLatencyTester
import com.singflow.android.core.engine.VpnControl
import com.singflow.android.core.engine.VpnStateRepository
import com.singflow.android.core.failover.RepositoryNodeStore
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.CountryNames
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.RoutingMode
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.model.VpnStatus
import com.singflow.android.core.parser.UniversalImportManager
import com.singflow.android.core.probe.BatchProbeEngine
import com.singflow.android.core.probe.ClashApiClient
import com.singflow.android.core.probe.ClashApiNodeProber
import com.singflow.android.core.probe.MixedProxyNodeProber
import com.singflow.android.core.subscription.SubscriptionManager
import okhttp3.OkHttpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * SingFlow 主 ViewModel：统一承载节点、设置、配置预览与 VPN 操作。
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val nodeRepository: NodeRepository,
    private val preferencesManager: PreferencesManager,
    private val importManager: UniversalImportManager,
    private val configPipeline: SingBoxConfigPipeline,
    private val latencyTester: NodeLatencyTester,
    private val clashApiClient: ClashApiClient,
    private val appRouteRuleRepository: AppRouteRuleRepository,
    private val subscriptionManager: SubscriptionManager,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    /** 全部节点。 */
    val nodes: StateFlow<List<ProxyNode>> = nodeRepository.observeNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 应用级国家分流规则。 */
    val appRouteRules: StateFlow<List<AppRouteRuleEntity>> = appRouteRuleRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 订阅列表。 */
    val subscriptions: StateFlow<List<SubscriptionEntity>> = subscriptionManager.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全局设置。 */
    val settings: StateFlow<AppSettings> = preferencesManager.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT)

    /** VPN 运行态。 */
    val vpnState: StateFlow<VpnState> = VpnStateRepository.state

    /** 当前选中节点（未显式选择时回退到第一个）。 */
    val selectedNode: StateFlow<ProxyNode?> = combine(nodes, settings) { list, current ->
        list.firstOrNull { it.id == current.selectedNodeId } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前节点编译出的完整 sing-box JSON。 */
    val configPreview: StateFlow<String> = combine(selectedNode, settings, appRouteRules) { node, current, rules ->
        if (node == null) {
            ""
        } else {
            runCatching { compilePretty(node, current) }
                .getOrElse { "// 配置编译失败: ${it.message}" }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _testing = MutableStateFlow(false)

    /** 是否正在测速。 */
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _importing = MutableStateFlow(false)

    /** 是否正在导入。 */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _geoScanning = MutableStateFlow(false)

    /** 是否正在逐个节点实测真实出口。 */
    val geoScanning: StateFlow<Boolean> = _geoScanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性提示消息。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importMessage = MutableStateFlow<String?>(null)

    /** 导入弹层内的结果提示。 */
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    init {
        // 当设置加载完成且没有选中节点时，自动选中第一个节点。
        viewModelScope.launch {
            settings.collect { current ->
                val list = nodes.value
                if (list.isNotEmpty() && current.selectedNodeId.isBlank()) {
                    preferencesManager.setSelectedNode(list.first().id)
                }
            }
        }
    }

    /** 切换路由模式。 */
    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch { preferencesManager.update { it.copy(routingMode = mode) } }
    }

    /** 选择节点；若已连接则热切换。 */
    fun selectNode(context: Context, node: ProxyNode) {
        viewModelScope.launch {
            preferencesManager.setSelectedNode(node.id)
            val status = VpnStateRepository.snapshot().status
            if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
                val currentSettings = settings.value
                val config = runCatching { compileCompact(node, currentSettings) }
                    .getOrElse {
                        _message.value = "配置编译失败: ${it.message}"
                        return@launch
                    }
                VpnControl.reload(context.applicationContext, config, node.id, node.displayName, currentSettings)
            }
        }
    }

    /** 连接 / 断开切换。 */
    fun toggleConnection(context: Context) {
        when (VpnStateRepository.snapshot().status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> VpnControl.disconnect(context.applicationContext)
            else -> connectNode(context, selectedNode.value)
        }
    }

    /** 连接指定节点。 */
    fun connectNode(context: Context, node: ProxyNode?) {
        if (node == null) {
            _message.value = "请先导入并选择节点"
            return
        }
        val currentSettings = settings.value
        val config = runCatching { compileCompact(node, currentSettings) }
            .getOrElse {
                _message.value = "配置编译失败: ${it.message}"
                return
            }
        diagnostics(node, currentSettings).forEach { line ->
            VpnStateRepository.addLog("INFO", line)
        }
        VpnControl.connect(context, config, node.id, node.displayName, currentSettings)
    }

    /**
     * 导入文本。
     *
     * @param autoConnectContext 非空时，导入成功后立即连接第一个新节点（ADB 自动化测试使用）
     */
    fun importText(
        text: String,
        groupId: String = "manual",
        autoConnectContext: Context? = null
    ) {
        viewModelScope.launch {
            _importing.value = true
            _importMessage.value = null
            val result = withContext(Dispatchers.IO) { importManager.importFromText(text, groupId) }
            _importing.value = false
            if (result.nodes.isNotEmpty()) {
                preferencesManager.setSelectedNode(result.nodes.first().id)
                val summary = "成功导入 ${result.nodes.size} 个节点"
                _importMessage.value = summary
                _message.value = summary
                if (autoConnectContext != null) {
                    connectNode(autoConnectContext, result.nodes.first())
                }
            } else {
                val error = result.errors.firstOrNull()
                val summary = if (error.isNullOrBlank()) "未识别到有效节点" else "导入失败：$error"
                _importMessage.value = summary
                _message.value = summary
            }
        }
    }

    /** 生成示例节点。 */
    fun generateSamples() {
        viewModelScope.launch {
            val samples = UniversalImportManager.sampleNodes()
            withContext(Dispatchers.IO) { nodeRepository.saveAll(samples) }
            preferencesManager.setSelectedNode(samples.first().id)
            _importMessage.value = "成功生成 ${samples.size} 个示例节点"
            _message.value = "成功生成 ${samples.size} 个示例节点"
        }
    }

    /** 并发测速全部节点。 */
    fun testAllLatency() {
        if (_testing.value) return
        viewModelScope.launch {
            val list = nodes.value
            if (list.isEmpty()) {
                _message.value = "没有可测速的节点"
                return@launch
            }
            _testing.value = true
            val started = System.currentTimeMillis()
            val currentSettings = settings.value
            val connected = VpnStateRepository.snapshot().status == VpnStatus.CONNECTED
            val groupedProbeReady = connected &&
                currentSettings.countryRoutingEnabled &&
                clashApiClient.isAvailable()
            if (groupedProbeReady) {
                // 多节点配置下每个节点都有独立 outbound，使用 Clash API 实测真实 RTT，
                // 不会改变当前出站选择。
                val engine = BatchProbeEngine(
                    prober = ClashApiNodeProber(clashApiClient),
                    store = RepositoryNodeStore(nodeRepository),
                    concurrency = BatchProbeEngine.DEFAULT_CONCURRENCY
                )
                val summary = engine.probeAll(
                    nodes = list,
                    timeoutMs = currentSettings.failover.singleProbeTimeoutMs
                )
                _testing.value = false
                val elapsed = System.currentTimeMillis() - started
                _message.value =
                    "探活完成：${summary.successCount}/${summary.total} 可达（实测 RTT），耗时 ${elapsed}ms"
            } else {
                val results = latencyTester.testAll(list) { id, latency ->
                    nodeRepository.updateLatency(id, latency)
                }
                _testing.value = false
                val elapsed = System.currentTimeMillis() - started
                val reachable = results.values.count { it >= 0 }
                _message.value = "测速完成：$reachable/${results.size} 可达，耗时 ${elapsed}ms"
            }
        }
    }

    /** 删除节点。 */
    fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { nodeRepository.delete(nodeId) }
            if (settings.value.selectedNodeId == nodeId) {
                val next = nodes.value.firstOrNull { it.id != nodeId }
                preferencesManager.setSelectedNode(next?.id.orEmpty())
            }
        }
    }

    /** 切换节点收藏状态。 */
    fun toggleFavorite(node: ProxyNode) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { nodeRepository.setFavorite(node.id, !node.isFavorite) }
        }
    }

    /** 批量收藏 / 取消收藏。 */
    fun setFavorite(nodes: Set<String>, favorite: Boolean) {
        if (nodes.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                nodes.forEach { nodeRepository.setFavorite(it, favorite) }
            }
            _message.value = if (favorite) "已收藏 ${nodes.size} 个节点" else "已取消收藏 ${nodes.size} 个节点"
        }
    }

    /** 批量删除节点；若删除当前节点则自动选择下一个。 */
    fun deleteNodes(nodeIds: Set<String>) {
        if (nodeIds.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                nodeIds.forEach { nodeRepository.delete(it) }
            }
            if (settings.value.selectedNodeId in nodeIds) {
                val next = nodes.value.firstOrNull { it.id !in nodeIds }
                preferencesManager.setSelectedNode(next?.id.orEmpty())
            }
            _message.value = "已删除 ${nodeIds.size} 个节点"
        }
    }

    /**
     * 逐个切换 Selector 并实测节点真实出口，失败后恢复原节点。
     *
     * 仅在已连接且开启多节点国家分组时可用；扫描期间网络会随节点切换短暂中断。
     */
    fun scanGeoIp() {
        if (_geoScanning.value) return
        viewModelScope.launch {
            _geoScanning.value = true
            val original = runCatching { clashApiClient.selectedMember("proxy") }.getOrNull()
            try {
                val currentSettings = settings.value
                if (VpnStateRepository.snapshot().status != VpnStatus.CONNECTED) {
                    _message.value = "请先连接 VPN 再实测出口"
                    return@launch
                }
                if (!currentSettings.countryRoutingEnabled || nodes.value.size <= 1) {
                    _message.value = "需要开启多节点国家分组后再实测出口"
                    return@launch
                }
                val candidates = nodes.value
                    .filter { it.protocol != ProtocolType.WIREGUARD }
                    .take(MAX_GEO_SCAN_NODES)
                if (candidates.isEmpty()) {
                    _message.value = "没有可实测的节点"
                    return@launch
                }
                val prober = MixedProxyNodeProber(currentSettings.mixedPort, okHttpClient)
                var success = 0
                var failed = 0
                withContext(Dispatchers.IO) {
                    candidates.forEachIndexed { index, node ->
                        _message.value = "实测出口 ${index + 1}/${candidates.size}：${node.displayName}"
                        val switched = clashApiClient.select("proxy", "node-${node.id}")
                        if (!switched) {
                            failed += 1
                            return@forEachIndexed
                        }
                        delay(350)
                        val result = prober.probe(
                            node,
                            currentSettings.failover.singleProbeTimeoutMs.coerceAtLeast(3_000)
                        )
                        val ip = result.egressIp
                        val code = result.countryCode
                        if (result.success && !ip.isNullOrBlank() && !code.isNullOrBlank()) {
                            nodeRepository.updateEgress(
                                id = node.id,
                                egressIp = ip,
                                countryCode = code,
                                countryNameCn = result.countryNameCn
                                    ?: CountryNames.nameOf(code)
                            )
                            success += 1
                        } else {
                            failed += 1
                        }
                    }
                }
                _message.value = "真实出口实测完成：成功 $success，失败 $failed"
            } finally {
                if (!original.isNullOrBlank()) {
                    runCatching { clashApiClient.select("proxy", original) }
                }
                _geoScanning.value = false
            }
        }
    }

    /** 清空全部节点。 */
    fun clearNodes() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { nodeRepository.clear() }
            preferencesManager.setSelectedNode("")
            _message.value = "已清空全部节点"
        }
    }

    /** 更新设置。 */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { preferencesManager.update(transform) }
    }

    // ---------------------------------------------------------------------
    // 订阅管理
    // ---------------------------------------------------------------------

    /** 新增订阅并立即拉取。 */
    fun addSubscription(name: String, url: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { subscriptionManager.add(name, url) }
            _message.value = result.message
        }
    }

    /** 刷新单个订阅。 */
    fun refreshSubscription(id: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { subscriptionManager.refresh(id) }
            _message.value = result.message
        }
    }

    /** 刷新全部订阅。 */
    fun refreshAllSubscriptions() {
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) { subscriptionManager.refreshAll() }
            val success = results.count { it.success }
            _message.value = if (results.isEmpty()) {
                "暂无订阅"
            } else {
                "订阅更新完成：$success/${results.size} 成功"
            }
        }
    }

    /** 删除订阅及其节点。 */
    fun deleteSubscription(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { subscriptionManager.delete(id) }
            _message.value = "订阅已删除"
        }
    }

    /** 切换订阅自动更新。 */
    fun setSubscriptionAutoUpdate(id: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { subscriptionManager.setAutoUpdate(id, enabled) }
        }
    }

    /**
     * 保存应用级国家分流规则，并在已连接时热重载配置。
     */
    fun saveAppRouteRule(context: Context, rule: AppRouteRuleEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appRouteRuleRepository.save(rule) }
            applyAppRouteRules(context, "已保存 ${rule.appName} 的分流规则")
        }
    }

    /**
     * 删除某应用的分流规则（恢复跟随全局）。
     */
    fun deleteAppRouteRule(context: Context, packageName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appRouteRuleRepository.delete(packageName) }
            applyAppRouteRules(context, "已恢复 $packageName 跟随全局")
        }
    }

    /**
     * 应用分流规则：已连接且多节点分组开启时立即热重载，否则提示生效条件。
     */
    private suspend fun applyAppRouteRules(context: Context, message: String) {
        val node = selectedNode.value ?: return
        val currentSettings = settings.value
        if (!currentSettings.countryRoutingEnabled || groupedNodes(node).size <= 1) {
            _message.value = "$message；需开启「多节点国家分组」并重新连接后生效"
            return
        }
        val rules = withContext(Dispatchers.IO) { appRouteRuleRepository.getAll() }
        val config = runCatching {
            compileCompact(node, currentSettings, rules)
        }.getOrElse {
            _message.value = "配置编译失败: ${it.message}"
            return
        }
        val status = VpnStateRepository.snapshot().status
        if (status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING) {
            VpnControl.reload(context.applicationContext, config, node.id, node.displayName, currentSettings)
            _message.value = "$message，已热重载分流配置"
        } else {
            _message.value = "$message，下次连接生效"
        }
    }

    /** 清空日志。 */
    fun clearLogs() {
        VpnStateRepository.clearLogs()
    }

    /** 消费一次性消息。 */
    fun clearMessage() {
        _message.value = null
    }

    /** 清空导入提示。 */
    fun clearImportMessage() {
        _importMessage.value = null
    }

    // ---------------------------------------------------------------------
    // 配置编译
    // ---------------------------------------------------------------------

    /**
     * 参与多节点编译的节点集合。
     *
     * 过滤掉明显无效的节点，并保证当前节点一定在列表中（否则内核会回退到第一个节点）。
     */
    private fun groupedNodes(current: ProxyNode): List<ProxyNode> {
        val valid = nodes.value.filter { it.server.isNotBlank() && it.port in 1..65535 }
        return if (valid.any { it.id == current.id }) valid else valid + current
    }

    /** 单节点 / 多节点自适应紧凑配置。 */
    private fun compileCompact(
        node: ProxyNode,
        currentSettings: AppSettings,
        rules: List<AppRouteRuleEntity> = appRouteRules.value
    ): String {
        val dir = com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
        val all = groupedNodes(node)
        return if (currentSettings.countryRoutingEnabled && all.size > 1) {
            configPipeline.compileCompact(
                nodes = all,
                selectedNodeId = node.id,
                settings = currentSettings,
                appRouteRules = rules,
                ruleSetDir = dir
            )
        } else {
            configPipeline.compileCompact(node, currentSettings, dir)
        }
    }

    /** 单节点 / 多节点自适应带缩进配置。 */
    private fun compilePretty(node: ProxyNode, currentSettings: AppSettings): String {
        val dir = com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
        val all = groupedNodes(node)
        return if (currentSettings.countryRoutingEnabled && all.size > 1) {
            configPipeline.compilePretty(
                nodes = all,
                selectedNodeId = node.id,
                settings = currentSettings,
                appRouteRules = appRouteRules.value,
                ruleSetDir = dir
            )
        } else {
            configPipeline.compilePretty(node, currentSettings, dir)
        }
    }

    /** 单节点 / 多节点自适应编译诊断。 */
    private fun diagnostics(node: ProxyNode, currentSettings: AppSettings): List<String> {
        val dir = com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
        val all = groupedNodes(node)
        return if (currentSettings.countryRoutingEnabled && all.size > 1) {
            configPipeline.diagnostics(
                nodes = all,
                selectedNodeId = node.id,
                settings = currentSettings,
                appRouteRules = appRouteRules.value,
                ruleSetDir = dir
            )
        } else {
            configPipeline.diagnostics(node, currentSettings, dir)
        }
    }

    private companion object {
        /** 单次实测出口的最大节点数，避免逐个切换耗时过长。 */
        const val MAX_GEO_SCAN_NODES = 20
    }
}
