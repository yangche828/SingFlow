package com.singflow.android.core.model

/**
 * VPN 生命周期状态。
 */
enum class VpnStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    STOPPING,

    /** 受信任 Wi-Fi 挂起：前台服务驻留，隧道暂停，离开受信任网络后自动恢复。 */
    PARKED,
    ERROR
}

/**
 * 单条运行日志。
 *
 * @property timestamp 毫秒时间戳
 * @property level DEBUG / INFO / WARN / ERROR
 * @property message 日志内容
 */
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String = "INFO",
    val message: String = ""
) {
    /** 格式化为 `HH:mm:ss LEVEL message`。 */
    fun format(): String {
        val time = java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
        return String.format("%02d:%02d:%02d %-5s %s", time.hour, time.minute, time.second, level, message)
    }
}

/**
 * VPN 运行态快照，由引擎层写入、UI 层订阅。
 *
 * @property status 当前状态
 * @property activeNodeId 激活节点 ID
 * @property activeNodeName 激活节点名称
 * @property uploadSpeed 实时上传字节/秒
 * @property downloadSpeed 实时下载字节/秒
 * @property totalUpload 本次会话累计上传字节
 * @property totalDownload 本次会话累计下载字节
 * @property connectedSeconds 已连接秒数
 * @property engineMode NATIVE / DIAGNOSTIC
 * @property errorMessage 错误信息
 * @property logs 最近日志（最多 400 条）
 */
data class VpnState(
    val status: VpnStatus = VpnStatus.IDLE,
    val activeNodeId: String = "",
    val activeNodeName: String = "",
    val uploadSpeed: Long = 0L,
    val downloadSpeed: Long = 0L,
    val totalUpload: Long = 0L,
    val totalDownload: Long = 0L,
    val connectedSeconds: Long = 0L,
    val engineMode: String = "IDLE",
    val errorMessage: String = "",
    val logs: List<LogEntry> = emptyList()
) {
    val isBusy: Boolean get() = status == VpnStatus.CONNECTING || status == VpnStatus.STOPPING
    val isConnected: Boolean get() = status == VpnStatus.CONNECTED
    val isDiagnosticMode: Boolean get() = engineMode == "DIAGNOSTIC"
}
