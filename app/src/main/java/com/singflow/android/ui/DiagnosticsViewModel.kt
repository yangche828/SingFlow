package com.singflow.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.probe.ClashApiClient
import com.singflow.android.core.probe.ClashProxyGroup
import com.singflow.android.core.probe.ClashTraffic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 连接诊断 ViewModel：轮询 Clash API 的代理组、累计流量与活跃连接。
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val clashApiClient: ClashApiClient
) : ViewModel() {

    /**
     * 诊断快照。
     *
     * @property available Clash API 是否可用
     * @property loading 是否正在刷新
     * @property groups 代理组
     * @property traffic 流量与连接
     * @property lastUpdated 最近刷新时间
     */
    data class UiState(
        val available: Boolean = false,
        val loading: Boolean = false,
        val groups: List<ClashProxyGroup> = emptyList(),
        val traffic: ClashTraffic? = null,
        val lastUpdated: Long = 0L
    )

    private val _state = MutableStateFlow(UiState())

    /** 诊断快照。 */
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    /** 开始每 2 秒轮询（页面可见时调用，重复调用幂等）。 */
    fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(2_000L)
            }
        }
    }

    /** 停止轮询。 */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 立即刷新一次。 */
    fun refresh() {
        viewModelScope.launch { refreshOnce() }
    }

    private suspend fun refreshOnce() {
        _state.update { it.copy(loading = true) }
        val available = clashApiClient.isAvailable()
        if (!available) {
            _state.update {
                it.copy(available = false, loading = false, groups = emptyList(), traffic = null)
            }
            return
        }
        val groups = clashApiClient.proxyGroups()
        val traffic = clashApiClient.traffic()
        _state.update {
            it.copy(
                available = true,
                loading = false,
                groups = groups,
                traffic = traffic,
                lastUpdated = System.currentTimeMillis()
            )
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
