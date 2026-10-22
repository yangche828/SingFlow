package com.singflow.android.core.engine

import com.singflow.android.core.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 节点延迟测速器。
 *
 * 提供两种模式：
 * 1. TCP 握手测速（默认，直接连接 server:port）；
 * 2. HTTP 204 URLTest（经由本地 Mixed 入站代理，验证真实可用性）。
 */
class NodeLatencyTester(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
) {

    /**
     * 对单个节点执行 TCP 握手测速。
     *
     * @return 毫秒延迟，失败返回 null
     */
    suspend fun testTcp(node: ProxyNode, timeoutMs: Int = 3000): Int? = withContext(Dispatchers.IO) {
        if (node.server.isBlank() || node.port !in 1..65535) return@withContext null
        runCatching {
            val started = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(node.server, node.port), timeoutMs)
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                elapsed.toInt()
            }
        }.getOrNull()
    }

    /**
     * 并发测速全部节点（并发度 10）。
     *
     * @param onResult 每个节点完成时的回调（节点 ID, 延迟）
     * @return 节点 ID → 延迟（失败为 -1）
     */
    suspend fun testAll(
        nodes: List<ProxyNode>,
        concurrency: Int = 10,
        timeoutMs: Int = 3000,
        onResult: suspend (String, Int) -> Unit = { _, _ -> }
    ): Map<String, Int> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        val results = nodes.map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val latency = testTcp(node, timeoutMs) ?: -1
                    onResult(node.id, latency)
                    node.id to latency
                }
            }
        }.awaitAll().toMap()
        results
    }

    /**
     * 通过本地 Mixed 代理执行 HTTP 204 测速。
     *
     * @param url 测速地址，建议 `http://cp.cloudflare.com/generate_204`
     * @param mixedPort 本地 mixed 入站端口
     */
    suspend fun testHttp204(url: String, mixedPort: Int, timeoutMs: Int = 5000): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val proxyClient = client.newBuilder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", mixedPort)))
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val started = System.nanoTime()
            proxyClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                ((System.nanoTime() - started) / 1_000_000L).toInt()
            }
        }.getOrNull()
    }

    companion object {
        /** 默认测速地址。 */
        const val DEFAULT_TEST_URL = "http://cp.cloudflare.com/generate_204"
    }
}
