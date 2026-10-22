package com.singflow.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.config.SingBoxConfigPipeline
import com.singflow.android.core.database.AppRouteRuleRepository
import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.database.PreferencesManager
import com.singflow.android.core.engine.NodeLatencyTester
import com.singflow.android.core.engine.VpnControl
import com.singflow.android.core.engine.VpnStateRepository
import com.singflow.android.core.failover.RepositoryNodeStore
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.RoutingMode
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.model.VpnStatus
import com.singflow.android.core.parser.UniversalImportManager
import com.singflow.android.core.probe.BatchProbeEngine
import com.singflow.android.core.probe.ClashApiClient
import com.singflow.android.core.probe.ClashApiNodeProber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    appRouteRuleRepository: AppRouteRuleRepository
) : ViewModel() {

    /** 全部节点。 */
    val nodes: StateFlow<List<ProxyNode>> = nodeRepository.observeNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 应用级国家分流规则。 */
    private val appRouteRules = appRouteRuleRepository.observeAll()
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
    private fun compileCompact(node: ProxyNode, currentSettings: AppSettings): String {
        val dir = com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
        val all = groupedNodes(node)
        return if (currentSettings.countryRoutingEnabled && all.size > 1) {
            configPipeline.compileCompact(
                nodes = all,
                selectedNodeId = node.id,
                settings = currentSettings,
                appRouteRules = appRouteRules.value,
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
}
