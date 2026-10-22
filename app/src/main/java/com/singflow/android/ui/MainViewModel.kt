package com.singflow.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.config.SingBoxConfigPipeline
import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.database.PreferencesManager
import com.singflow.android.core.engine.NodeLatencyTester
import com.singflow.android.core.engine.VpnControl
import com.singflow.android.core.engine.VpnStateRepository
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.RoutingMode
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.model.VpnStatus
import com.singflow.android.core.parser.UniversalImportManager
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
    private val latencyTester: NodeLatencyTester
) : ViewModel() {

    /** 全部节点。 */
    val nodes: StateFlow<List<ProxyNode>> = nodeRepository.observeNodes()
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
    val configPreview: StateFlow<String> = combine(selectedNode, settings) { node, current ->
        if (node == null) {
            ""
        } else {
            runCatching { configPipeline.compilePretty(node, current, com.singflow.android.SingFlowApp.ruleSetDir.absolutePath) }
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
                val config = runCatching {
                    configPipeline.compileCompact(
                        node,
                        settings.value,
                        com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
                    )
                }
                    .getOrElse {
                        _message.value = "配置编译失败: ${it.message}"
                        return@launch
                    }
                VpnControl.reload(context.applicationContext, config, node.id, node.displayName, settings.value)
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
        val config = runCatching {
            configPipeline.compileCompact(
                node,
                currentSettings,
                com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
            )
        }
            .getOrElse {
                _message.value = "配置编译失败: ${it.message}"
                return
            }
        configPipeline.diagnostics(
            node,
            currentSettings,
            com.singflow.android.SingFlowApp.ruleSetDir.absolutePath
        ).forEach { line ->
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
            val results = latencyTester.testAll(list) { id, latency ->
                nodeRepository.updateLatency(id, latency)
            }
            _testing.value = false
            val elapsed = System.currentTimeMillis() - started
            val reachable = results.values.count { it >= 0 }
            _message.value = "测速完成：$reachable/${results.size} 可达，耗时 ${elapsed}ms"
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
}
