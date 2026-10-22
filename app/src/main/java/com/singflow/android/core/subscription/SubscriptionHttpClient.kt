package com.singflow.android.core.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 订阅下载结果。
 *
 * @property content 原始响应文本（已去除 BOM，未做 Base64 解码）
 * @property userInfo 订阅流量/到期信息
 */
data class SubscriptionDownload(
    val content: String,
    val userInfo: SubscriptionUserInfo?
)

/**
 * 订阅 HTTP 下载器。
 *
 * 负责请求头、超时与 5 MiB 上限；解析逻辑仍由 [SubscriptionManager] 处理，
 * 便于用 MockWebServer 独立测试网络行为。
 */
class SubscriptionHttpClient(
    private val client: OkHttpClient
) {

    /**
     * 下载订阅文本。
     *
     * @throws IllegalStateException 非 2xx、空响应或超过大小限制
     */
    suspend fun download(url: String): SubscriptionDownload = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .get()
            .build()
        val downloadClient = client.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val body = response.body ?: error("响应为空")
            val bytes = body.byteStream().readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) {
                error("订阅内容超过 5 MiB 限制")
            }
            SubscriptionDownload(
                content = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"),
                userInfo = SubscriptionUserInfo.parse(response.header("Subscription-Userinfo"))
            )
        }
    }

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val USER_AGENT = "SingFlow/2.0 (Android)"
    }
}
