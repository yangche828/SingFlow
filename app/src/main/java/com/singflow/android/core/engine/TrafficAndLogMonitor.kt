package com.singflow.android.core.engine

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import com.singflow.android.core.model.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时流量与内核日志监控。
 *
 * 1. 优先连接 Clash API（`ws://127.0.0.1:9090/traffic` 与 `/logs`）；
 * 2. 同时使用 [TrafficStats] 兜底计算每秒上下行与累计流量。
 */
class TrafficAndLogMonitor(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    private var trafficJob: Job? = null
    private var trafficSocket: WebSocket? = null
    private var logSocket: WebSocket? = null
    private val clashTrafficActive = AtomicBoolean(false)
    private val startedAt = System.currentTimeMillis()

    private var lastUidTx = 0L
    private var lastUidRx = 0L
    private var totalTx = 0L
    private var totalRx = 0L
    private var lastClashTx = 0L
    private var lastClashRx = 0L
    private var sampleAt = System.currentTimeMillis()

    /**
     * 启动监控。
     */
    fun start(scope: CoroutineScope) {
        resetCounters()
        connectClashTraffic()
        connectClashLogs()
        trafficJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sample()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /**
     * 停止监控。
     */
    fun stop() {
        trafficJob?.cancel()
        trafficJob = null
        runCatching { trafficSocket?.close(1000, "bye") }
        runCatching { logSocket?.close(1000, "bye") }
        trafficSocket = null
        logSocket = null
        clashTrafficActive.set(false)
    }

    private fun resetCounters() {
        lastUidTx = runCatching { TrafficStats.getUidTxBytes(Process.myUid()) }.getOrDefault(0L)
        lastUidRx = runCatching { TrafficStats.getUidRxBytes(Process.myUid()) }.getOrDefault(0L)
        totalTx = 0L
        totalRx = 0L
        sampleAt = System.currentTimeMillis()
    }

    /**
     * 每秒采样：若 Clash API 可用则使用其数据，否则使用 TrafficStats 差值。
     */
    private fun sample() {
        val now = System.currentTimeMillis()
        val elapsed = ((now - sampleAt).coerceAtLeast(1)) / 1000.0
        sampleAt = now

        if (!clashTrafficActive.get()) {
            val tx = runCatching { TrafficStats.getUidTxBytes(Process.myUid()) }.getOrDefault(lastUidTx)
            val rx = runCatching { TrafficStats.getUidRxBytes(Process.myUid()) }.getOrDefault(lastUidRx)
            val deltaTx = (tx - lastUidTx).coerceAtLeast(0)
            val deltaRx = (rx - lastUidRx).coerceAtLeast(0)
            lastUidTx = tx
            lastUidRx = rx
            totalTx += deltaTx
            totalRx += deltaRx
            publish(
                upSpeed = (deltaTx / elapsed).toLong(),
                downSpeed = (deltaRx / elapsed).toLong()
            )
        } else {
            publish(upSpeed = lastClashTx, downSpeed = lastClashRx)
        }
    }

    private fun publish(upSpeed: Long, downSpeed: Long) {
        val state = VpnStateRepository.snapshot()
        if (state.status != VpnStatus.CONNECTED && state.status != VpnStatus.CONNECTING) return
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0)
        VpnStateRepository.updateTraffic(
            uploadSpeed = upSpeed,
            downloadSpeed = downSpeed,
            totalUpload = totalTx,
            totalDownload = totalRx,
            connectedSeconds = seconds
        )
    }

    /**
     * 连接 Clash API 流量 WebSocket。
     */
    private fun connectClashTraffic() {
        val request = Request.Builder().url("$CLASH_BASE/traffic").build()
        trafficSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                clashTrafficActive.set(true)
                VpnStateRepository.addLog("INFO", "Clash API /traffic 已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val up = obj.longOf("up")
                val down = obj.longOf("down")
                lastClashTx = up
                lastClashRx = down
                totalTx += up
                totalRx += down
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                clashTrafficActive.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                clashTrafficActive.set(false)
            }
        })
    }

    /**
     * 连接 Clash API 日志 WebSocket。
     */
    private fun connectClashLogs() {
        val request = Request.Builder().url("$CLASH_BASE/logs?level=info").build()
        logSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                val payload = obj?.get("payload")?.let { (it as? JsonPrimitive)?.content } ?: text
                val level = obj?.get("type")?.let { (it as? JsonPrimitive)?.content }?.uppercase() ?: "INFO"
                VpnStateRepository.addLog(level, payload)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Clash API 不可用时静默降级，由 TrafficStats 兜底。
            }
        })
    }

    private fun JsonObject.longOf(key: String): Long {
        val element = this[key] ?: return 0L
        return runCatching { (element as JsonPrimitive).content.toLong() }.getOrDefault(0L)
    }

    companion object {
        private const val CLASH_BASE = "ws://127.0.0.1:9090"
        private const val SAMPLE_INTERVAL_MS = 1000L
    }
}
