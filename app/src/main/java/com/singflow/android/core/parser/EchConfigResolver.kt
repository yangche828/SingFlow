package com.singflow.android.core.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * ECH（Encrypted Client Hello）config 解析器。
 *
 * 对抗 SNI 阻断：VLESS 链接的 `ech=<域名>+<DoH>` 参数只携带来源域名，
 * 真正的 ECHConfigList 需在导入时通过 DoH 查询该域名的 HTTPS DNS 记录获得。
 * 解析结果以 PEM 形式缓存在 [com.singflow.android.core.model.TlsConfig.echConfigPem]，
 * 供配置编译阶段写入 sing-box 的 `tls.ech.config` 字段。
 *
 * ECH key 会轮换；若未来节点失效，重新导入订阅即可刷新。
 */
object EchConfigResolver {

    /** 境内直连可达的 DoH JSON 端点，作为链接未携带 DoH 时的兜底。 */
    const val DEFAULT_DOH: String = "https://dns.alidns.com/resolve"

    /** 单次 DoH 请求的整体超时；解析失败会降级明文 SNI，不值得让导入长时间等待。 */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** 匹配 DoH JSON 应答 data 字段中的 ECHConfigList；兼容 JSON 转义（ech=\"..\"）与裸文本两种形态。 */
    private val ECH_REGEX = Regex("""ech=\\?"([^"\\]+)""")

    /**
     * 查询 [source] 域名 HTTPS 记录中的 ECHConfigList 并包装为 PEM。
     *
     * DoH 端点归一化：链接常携带 RFC8484 端点（`/dns-query`，走 DNS wireformat），
     * 这里统一按 JSON API 查询，因此对 `/dns-query` 端点额外尝试同源的 `/resolve`；
     * 全部失败后再用境内兜底端点重试。
     *
     * @return PEM 字符串；域名无记录、网络失败或响应异常时返回 null（调用方降级为明文 SNI）。
     */
    suspend fun resolvePem(source: String, dohUrl: String = DEFAULT_DOH): String? {
        if (source.isBlank()) return null
        val query = runCatching { URLEncoder.encode(source.trim(), "UTF-8") }.getOrNull() ?: source.trim()
        val candidates = buildList {
            addAll(jsonEndpoints(dohUrl))
            addAll(jsonEndpoints(DEFAULT_DOH))
        }.distinct()
        for (base in candidates) {
            val pem = queryJsonApi(base, query)
            if (pem != null) return pem
        }
        return null
    }

    /**
     * 把端点归一化为 JSON API 候选。仅接受 http/https（订阅里出现过 `udp://1.1.1.1` 这类写法）；
     * RFC8484 的 `/dns-query` 端点同时尝试同源 `/resolve`。
     */
    internal fun jsonEndpoints(raw: String): List<String> {
        val base = raw.trim().trimEnd('/')
        if (!base.startsWith("http://") && !base.startsWith("https://")) return emptyList()
        val jsonPath = if (base.endsWith("/dns-query")) {
            base.removeSuffix("/dns-query") + "/resolve"
        } else {
            base
        }
        return listOf(jsonPath, base).distinct()
    }

    /**
     * 对 [base] 端点做 GET JSON 查询（`/resolve` 原生 JSON、`/dns-query` 兼容 Accept 头）。
     *
     * @param base 已归一化的端点
     * @param encodedSource 已 URL 编码的来源域名
     */
    private suspend fun queryJsonApi(base: String, encodedSource: String): String? =
        withContext(Dispatchers.IO) {
            val outcome = runCatching {
                val url = "$base?name=$encodedSource&type=HTTPS"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logWarn("DoH $base -> HTTP ${response.code}")
                        return@runCatching null
                    }
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!contentType.contains("json", ignoreCase = true)) {
                        logWarn("DoH $base -> 非 JSON 响应（$contentType），跳过")
                        return@runCatching null
                    }
                    val body = response.body?.string() ?: return@runCatching null
                    val echBase64 = ECH_REGEX.find(body)?.groupValues?.get(1)?.trim()
                    if (echBase64.isNullOrEmpty()) {
                        logWarn("DoH $base 200 但无 ech 字段")
                        return@runCatching null
                    }
                    buildString {
                        append("-----BEGIN ECH CONFIGS-----\n")
                        append(echBase64)
                        append("\n-----END ECH CONFIGS-----")
                    }
                }
            }
            outcome.onFailure { logWarn("DoH $base ex: ${it.javaClass.simpleName}: ${it.message}") }
            outcome.getOrNull()
        }

    /** 导入日志；ECH 失败只是降级，不抛给调用方。 */
    private fun logWarn(message: String) {
        android.util.Log.w("EchImport", message)
    }
}
