package com.singflow.android.core.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * sing-box Clash API 轻量客户端。
 *
 * 提供三类能力：
 * 1. `/proxies/{tag}/delay`：在不切换当前出站的前提下，实测指定节点的真实 RTT；
 * 2. `PUT /proxies/{group}`：在 selector 组内内存态切换节点（不重建 TUN）；
 * 3. `/proxies/{group}`：读取当前选中的成员。
 *
 * @param client 共享 OkHttp 客户端
 * @param baseUrl Clash API 地址（与配置中的 `experimental.clash_api.external_controller` 一致）
 */
class ClashApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    /** Clash API 是否可用。 */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url("/version")).get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * 测量指定代理出站的真实 RTT。
     *
     * @param proxyTag 出站 tag（多节点配置为 `node-{id}`，单节点为 `proxy`）
     * @param targetUrl 测速目标 URL
     * @param timeoutMs 单次超时
     * @return 延迟毫秒；失败返回 null
     */
    suspend fun delay(proxyTag: String, targetUrl: String, timeoutMs: Int): Int? =
        withContext(Dispatchers.IO) {
            if (proxyTag.isBlank()) return@withContext null
            runCatching {
                val httpUrl = baseUrl.toHttpUrl().newBuilder()
                    .addPathSegment("proxies")
                    .addPathSegment(proxyTag)
                    .addPathSegment("delay")
                    .addQueryParameter("timeout", timeoutMs.coerceAtLeast(1).toString())
                    .addQueryParameter("url", targetUrl)
                    .build()
                val request = Request.Builder().url(httpUrl).get().build()
                client.newBuilder()
                    .callTimeout(timeoutMs.toLong() + 1_500L, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(request)
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body?.string().orEmpty()
                        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                            ?: return@use null
                        val delay = obj["delay"]?.jsonPrimitive?.intOrNull ?: return@use null
                        delay.takeIf { it > 0 }
                    }
            }.getOrNull()
        }

    /**
     * 在 selector 组内切换成员。
     *
     * @param groupTag selector 组 tag（多节点配置默认 `proxy`）
     * @param memberTag 目标成员 tag
     */
    suspend fun select(groupTag: String, memberTag: String): Boolean = withContext(Dispatchers.IO) {
        if (groupTag.isBlank() || memberTag.isBlank()) return@withContext false
        runCatching {
            val body = buildJsonObject { put("name", memberTag) }
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url("/proxies/$groupTag"))
                .put(body)
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * 读取 selector 组当前选中的成员 tag。
     */
    suspend fun selectedMember(groupTag: String): String? = withContext(Dispatchers.IO) {
        if (groupTag.isBlank()) return@withContext null
        runCatching {
            val request = Request.Builder().url(url("/proxies/$groupTag")).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: return@use null
                obj["now"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun url(path: String): HttpUrl = "$baseUrl$path".toHttpUrl()

    companion object {
        /** 默认 Clash API 地址（与 ExperimentalInterceptor 保持一致）。 */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:9090"

        /** 默认测速地址。 */
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

        /** Cloudflare Trace 地址（响应约 150 字节）。 */
        const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

        /** GeoIP 回退地址。 */
        const val GEOIP_FALLBACK_URL = "https://api.ip.sb/geoip"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
