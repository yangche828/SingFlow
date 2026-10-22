package com.singflow.android.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * WebDAV 备份结果。
 *
 * @property success 是否成功
 * @property message 中文说明
 * @property content 下载内容（仅下载成功时）
 */
data class WebDavResult(
    val success: Boolean,
    val message: String,
    val content: String? = null
)

/**
 * 极简 WebDAV 客户端：PUT 上传 / GET 下载单个 JSON 文件。
 *
 * 面向 Nextcloud、坚果云、群晖等常见 WebDAV 服务；不实现 MKCOL，
 * 上传前需保证目标目录已存在。
 *
 * @param client 共享 OkHttp 客户端
 */
class WebDavClient(
    private val client: OkHttpClient
) {

    /**
     * 上传备份文本。
     */
    suspend fun upload(url: String, username: String, password: String, content: String): WebDavResult =
        withContext(Dispatchers.IO) {
            val validation = validateUrl(url)
            if (validation != null) return@withContext WebDavResult(false, validation)
            val body = content.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(url.trim())
                .put(body)
                .applyAuth(username, password)
                .build()
            runCatching {
                webDavClient().newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        WebDavResult(true, "已上传到 WebDAV")
                    } else {
                        WebDavResult(false, "WebDAV 上传失败：HTTP ${response.code}")
                    }
                }
            }.getOrElse { error ->
                WebDavResult(false, "WebDAV 上传失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

    /**
     * 下载备份文本。
     */
    suspend fun download(url: String, username: String, password: String): WebDavResult =
        withContext(Dispatchers.IO) {
            val validation = validateUrl(url)
            if (validation != null) return@withContext WebDavResult(false, validation)
            val request = Request.Builder()
                .url(url.trim())
                .get()
                .applyAuth(username, password)
                .build()
            runCatching {
                webDavClient().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use WebDavResult(false, "WebDAV 下载失败：HTTP ${response.code}")
                    }
                    val body = response.body ?: return@use WebDavResult(false, "WebDAV 响应为空")
                    val bytes = body.byteStream().readNBytes(MAX_BYTES + 1)
                    if (bytes.size > MAX_BYTES) {
                        return@use WebDavResult(false, "WebDAV 备份超过 10 MiB 限制")
                    }
                    WebDavResult(
                        success = true,
                        message = "已从 WebDAV 下载备份",
                        content = bytes.toString(Charsets.UTF_8)
                    )
                }
            }.getOrElse { error ->
                WebDavResult(false, "WebDAV 下载失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

    private fun webDavClient(): OkHttpClient = client.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.applyAuth(username: String, password: String): Request.Builder {
        if (username.isNotBlank()) {
            header("Authorization", Credentials.basic(username, password))
        }
        return this
    }

    private fun validateUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return "请先填写 WebDAV 文件地址"
        val scheme = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") return "WebDAV 地址必须是 http/https URL"
        return null
    }

    private companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
