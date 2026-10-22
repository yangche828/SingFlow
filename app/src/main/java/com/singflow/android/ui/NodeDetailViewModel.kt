package com.singflow.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.probe.ClashApiClient
import com.singflow.android.core.probe.ClashConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 节点详情页的连接观察器。
 *
 * 从 Clash API 拉取活跃连接，仅保留出站链包含该节点的连接，
 * 用于回答「这个节点当前承载了哪些流量」。
 */
@HiltViewModel
class NodeDetailViewModel @Inject constructor(
    private val clashApiClient: ClashApiClient
) : ViewModel() {

    /**
     * 节点连接状态。
     *
     * @property available Clash API 是否可用
     * @property connections 经过当前节点的连接（下载量降序）
     */
    data class UiState(
        val available: Boolean = false,
        val connections: List<ClashConnection> = emptyList(),
        val lastUpdated: Long = 0L
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var currentNodeId: String = ""

    /** 开始每 2 秒轮询。 */
    fun startPolling(nodeId: String) {
        if (nodeId.isBlank()) return
        currentNodeId = nodeId
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(2_000)
            }
        }
    }

    /** 停止轮询。 */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun refresh() {
        if (!clashApiClient.isAvailable()) {
            _state.update { it.copy(available = false, connections = emptyList()) }
            return
        }
        val traffic = clashApiClient.traffic()
        val filtered = connectionsForNode(traffic?.connections.orEmpty(), currentNodeId)
        _state.update {
            it.copy(
                available = true,
                connections = filtered,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}

/**
 * 过滤出出站链包含指定节点的连接，按流量降序、最多 20 条。
 */
internal fun connectionsForNode(
    connections: List<ClashConnection>,
    nodeId: String
): List<ClashConnection> {
    if (nodeId.isBlank()) return emptyList()
    val tag = "node-$nodeId"
    return connections
        .filter { connection -> connection.chains.contains(tag) }
        .sortedByDescending { it.download + it.upload }
        .take(20)
}
