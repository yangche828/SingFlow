package com.singflow.android.core.engine

import com.singflow.android.core.model.LogEntry
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.model.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级 VPN 运行态仓库。
 *
 * 由 [SingFlowVpnService] / [TrafficAndLogMonitor] 写入，UI 与磁贴订阅。
 */
object VpnStateRepository {

    private const val MAX_LOGS = 400

    private val _state = MutableStateFlow(VpnState())
    private val lock = Any()

    /** 可订阅的运行态。 */
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** 当前快照。 */
    fun snapshot(): VpnState = _state.value

    /**
     * 更新状态字段。
     */
    fun setStatus(
        status: VpnStatus,
        nodeId: String = _state.value.activeNodeId,
        nodeName: String = _state.value.activeNodeName,
        engineMode: String = _state.value.engineMode,
        errorMessage: String = ""
    ) {
        synchronized(lock) {
            _state.value = _state.value.copy(
                status = status,
                activeNodeId = nodeId,
                activeNodeName = nodeName,
                engineMode = engineMode,
                errorMessage = errorMessage
            )
        }
    }

    /**
     * 追加日志。
     */
    fun addLog(level: String, message: String) {
        synchronized(lock) {
            val entry = LogEntry(level = level, message = message)
            val merged = (_state.value.logs + entry).takeLast(MAX_LOGS)
            _state.value = _state.value.copy(logs = merged)
        }
    }

    /**
     * 批量追加日志（服务启动时回放编译诊断）。
     */
    fun addLogs(entries: List<Pair<String, String>>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val merged = (_state.value.logs + entries.map { (level, message) ->
                LogEntry(timestamp = now, level = level, message = message)
            }).takeLast(MAX_LOGS)
            _state.value = _state.value.copy(logs = merged)
        }
    }

    /**
     * 更新流量与连接时长。
     */
    fun updateTraffic(
        uploadSpeed: Long,
        downloadSpeed: Long,
        totalUpload: Long,
        totalDownload: Long,
        connectedSeconds: Long
    ) {
        synchronized(lock) {
            _state.value = _state.value.copy(
                uploadSpeed = uploadSpeed.coerceAtLeast(0),
                downloadSpeed = downloadSpeed.coerceAtLeast(0),
                totalUpload = totalUpload.coerceAtLeast(0),
                totalDownload = totalDownload.coerceAtLeast(0),
                connectedSeconds = connectedSeconds.coerceAtLeast(0)
            )
        }
    }

    /**
     * 重置为未连接状态（保留日志）。
     */
    fun reset() {
        synchronized(lock) {
            val logs = _state.value.logs
            _state.value = VpnState(status = VpnStatus.IDLE, logs = logs)
        }
    }

    /**
     * 清空日志。
     */
    fun clearLogs() {
        synchronized(lock) {
            _state.value = _state.value.copy(logs = emptyList())
        }
    }

    /**
     * 设置错误状态。
     */
    fun setError(message: String) {
        synchronized(lock) {
            _state.value = _state.value.copy(status = VpnStatus.ERROR, errorMessage = message)
            addLog("ERROR", message)
        }
    }
}
