package com.singflow.android.core.probe

import com.singflow.android.core.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 经本地 mixed 入站（HTTP 代理）发起真实请求的探活器。
 *
 * 该实现测量的是「当前出站节点」的端到端链路，因此用于故障转移时对当前节点的健康检查；
 * 请求 `cdn-cgi/trace` 可同时拿回落地 IP 与国家代码。
 *
 * @param mixedPort 本地 mixed 入站端口
 * @param client 基础 OkHttp 客户端
 */
class MixedProxyNodeProber(
    private val mixedPort: Int,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) : NodeProber {

    override suspend fun probe(node: ProxyNode, timeoutMs: Int): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val proxyClient = client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, mixedPort)))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong() + 1_500L, TimeUnit.MILLISECONDS)
            .build()

        val trace = execute(proxyClient, ClashApiClient.TRACE_URL, timeoutMs)
        if (trace != null) {
            val elapsed = (System.currentTimeMillis() - started).toInt().coerceAtLeast(0)
            val egress = EgressTraceParser.parseCloudflareTrace(trace)
            return@withContext ProbeResult(
                nodeId = node.id,
                success = true,
                latencyMs = elapsed,
                egressIp = egress?.ip,
                countryCode = egress?.countryCode,
                countryNameCn = egress?.countryNameCn
            )
        }

        val fallback = execute(proxyClient, ClashApiClient.GEOIP_FALLBACK_URL, timeoutMs)
        if (fallback != null) {
            val elapsed = (System.currentTimeMillis() - started).toInt().coerceAtLeast(0)
            val egress = EgressTraceParser.parseGeoIpJson(fallback)
            return@withContext ProbeResult(
                nodeId = node.id,
                success = true,
                latencyMs = elapsed,
                egressIp = egress?.ip,
                countryCode = egress?.countryCode,
                countryNameCn = egress?.countryNameCn
            )
        }

        ProbeResult.failure(node.id, "经 mixed 代理请求超时或失败")
    }

    /**
     * 执行一次 GET 并返回响应体；非 2xx 或异常返回 null。
     */
    private fun execute(client: OkHttpClient, url: String, timeoutMs: Int): String? {
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()
            }
        }.getOrNull()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
    }
}
