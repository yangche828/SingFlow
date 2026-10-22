package com.singflow.android.core.subscription

import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.database.SubscriptionDao
import com.singflow.android.core.database.SubscriptionEntity
import com.singflow.android.core.parser.UniversalImportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 订阅更新结果。
 *
 * @property success 是否成功
 * @property message 中文说明
 * @property imported 本次解析并写入的节点数
 * @property removed 已从订阅中移除的旧节点数
 * @property subscription 更新后的订阅记录
 */
data class SubscriptionRefreshResult(
    val success: Boolean,
    val message: String,
    val imported: Int = 0,
    val removed: Int = 0,
    val subscription: SubscriptionEntity? = null
)

/**
 * 订阅管理：新增、删除、拉取、解析、节点同步与流量元数据更新。
 *
 * 订阅内容支持：多行分享链接、整段 Base64 订阅、sing-box JSON。
 *
 * @param dao 订阅 DAO
 * @param nodeRepository 节点仓库
 * @param importManager 通用导入解析器
 * @param client 共享 OkHttp 客户端
 */
class SubscriptionManager(
    private val dao: SubscriptionDao,
    private val nodeRepository: NodeRepository,
    private val importManager: UniversalImportManager,
    private val client: OkHttpClient
) {

    /** 观察全部订阅。 */
    fun observeAll(): Flow<List<SubscriptionEntity>> = dao.observeAll()

    /** 读取全部订阅。 */
    suspend fun getAll(): List<SubscriptionEntity> = dao.getAll()

    /**
     * 新增订阅并立即拉取一次。
     *
     * @return 拉取结果；失败时订阅仍保留，便于稍后重试
     */
    suspend fun add(name: String, url: String): SubscriptionRefreshResult {
        val normalizedUrl = url.trim()
        val scheme = runCatching { URI(normalizedUrl).scheme?.lowercase() }.getOrNull()
        if (normalizedUrl.isBlank() || scheme !in setOf("http", "https")) {
            return SubscriptionRefreshResult(false, "订阅地址必须是 http/https URL")
        }
        val id = UUID.randomUUID().toString()
        val fallbackName = runCatching { URI(normalizedUrl).host }.getOrNull().orEmpty().ifBlank { normalizedUrl }
        val entity = SubscriptionEntity(
            id = id,
            name = name.trim().ifBlank { fallbackName },
            url = normalizedUrl,
            lastUpdated = 0L,
            autoUpdate = true,
            enabled = true
        )
        dao.upsert(entity)
        return refresh(id)
    }

    /**
     * 刷新单个订阅。
     */
    suspend fun refresh(id: String): SubscriptionRefreshResult {
        val entity = dao.getById(id)
            ?: return SubscriptionRefreshResult(false, "订阅不存在")
        return try {
            val (text, userInfo) = download(entity.url)
            val parsed = importManager.parseText(text, groupId = entity.id)
            if (parsed.nodes.isEmpty()) {
                return SubscriptionRefreshResult(
                    success = false,
                    message = "订阅内容中没有可识别的节点" +
                        if (parsed.errors.isEmpty()) "" else "（${parsed.errors.first()}）",
                    subscription = entity
                )
            }

            val keepHashes = parsed.nodes.map { it.configHash }.toSet()
            val existing = nodeRepository.getByGroup(entity.id)
            val stale = existing.filter { it.configHash !in keepHashes }
            if (stale.isNotEmpty()) {
                nodeRepository.deleteAll(stale.map { it.id })
            }
            nodeRepository.saveAllPreservingStats(parsed.nodes)

            val now = System.currentTimeMillis()
            val updated = entity.copy(
                name = entity.name.ifBlank {
                    runCatching { URI(entity.url).host }.getOrNull().orEmpty().ifBlank { entity.url }
                },
                lastUpdated = now,
                upload = userInfo?.upload ?: entity.upload,
                download = userInfo?.download ?: entity.download,
                total = userInfo?.total ?: entity.total,
                expireAt = userInfo?.expireAt ?: entity.expireAt
            )
            dao.upsert(updated)
            SubscriptionRefreshResult(
                success = true,
                message = "已更新 ${parsed.nodes.size} 个节点" +
                    if (stale.isEmpty()) "" else "，移除 ${stale.size} 个失效节点",
                imported = parsed.nodes.size,
                removed = stale.size,
                subscription = updated
            )
        } catch (error: Exception) {
            SubscriptionRefreshResult(
                success = false,
                message = "订阅更新失败：${error.message ?: error.javaClass.simpleName}",
                subscription = entity
            )
        }
    }

    /**
     * 刷新全部订阅。
     *
     * @param onlyAutoUpdate 仅刷新开启自动更新的订阅（后台 Worker 使用）
     */
    suspend fun refreshAll(onlyAutoUpdate: Boolean = false): List<SubscriptionRefreshResult> {
        val targets = dao.getAll().filter { it.enabled && (!onlyAutoUpdate || it.autoUpdate) }
        return targets.map { refresh(it.id) }
    }

    /**
     * 删除订阅及其全部节点。
     */
    suspend fun delete(id: String) {
        val nodes = nodeRepository.getByGroup(id)
        if (nodes.isNotEmpty()) {
            nodeRepository.deleteAll(nodes.map { it.id })
        }
        dao.deleteById(id)
    }

    /**
     * 切换自动更新。
     */
    suspend fun setAutoUpdate(id: String, enabled: Boolean) {
        val entity = dao.getById(id) ?: return
        dao.upsert(entity.copy(autoUpdate = enabled))
    }

    /**
     * 下载订阅文本，限制 5 MiB，避免异常订阅拖垮进程。
     */
    private suspend fun download(url: String): Pair<String, SubscriptionUserInfo?> =
        withContext(Dispatchers.IO) {
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
                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                text to SubscriptionUserInfo.parse(response.header("Subscription-Userinfo"))
            }
        }

    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        const val USER_AGENT = "SingFlow/2.0 (Android)"
    }
}
