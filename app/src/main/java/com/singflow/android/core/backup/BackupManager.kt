package com.singflow.android.core.backup

import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.database.AppRouteRuleRepository
import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.database.PreferencesManager
import com.singflow.android.core.database.SubscriptionDao
import com.singflow.android.core.database.SubscriptionEntity
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份文件内容。
 *
 * @property version 备份格式版本
 * @property exportedAt 导出时间戳（毫秒）
 * @property settings 全局设置
 * @property nodes 节点
 * @property subscriptions 订阅
 * @property appRules 应用分流规则
 */
@Serializable
data class BackupBundle(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings = AppSettings.DEFAULT,
    val nodes: List<ProxyNode> = emptyList(),
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val appRules: List<AppRouteRuleEntity> = emptyList()
) {
    companion object {
        /** 当前备份格式版本。 */
        const val CURRENT_VERSION = 1
    }
}

/**
 * 恢复结果。
 *
 * @property success 是否成功
 * @property message 中文说明
 * @property nodes 恢复节点数
 * @property subscriptions 恢复订阅数
 * @property rules 恢复规则数
 */
data class BackupRestoreResult(
    val success: Boolean,
    val message: String,
    val nodes: Int = 0,
    val subscriptions: Int = 0,
    val rules: Int = 0
)

/**
 * 本地备份与恢复：节点、设置、订阅、应用规则。
 *
 * @param nodeRepository 节点仓库
 * @param subscriptionDao 订阅 DAO
 * @param appRuleRepository 应用规则仓库
 * @param preferencesManager 设置存储
 * @param json JSON 编解码器
 */
class BackupManager(
    private val nodeRepository: NodeRepository,
    private val subscriptionDao: SubscriptionDao,
    private val appRuleRepository: AppRouteRuleRepository,
    private val preferencesManager: PreferencesManager,
    private val json: Json
) {

    /**
     * 生成备份 JSON。
     */
    suspend fun buildBackup(): String = withContext(Dispatchers.IO) {
        val bundle = BackupBundle(
            exportedAt = System.currentTimeMillis(),
            settings = preferencesManager.current(),
            nodes = nodeRepository.getAll(),
            subscriptions = subscriptionDao.getAll(),
            appRules = appRuleRepository.getAll()
        )
        json.encodeToString(BackupBundle.serializer(), bundle)
    }

    /**
     * 从 JSON 恢复备份。
     *
     * 采用合并语义：节点/订阅/规则按主键覆盖，不清空现有数据；
     * 设置整体覆盖，恢复后需重新连接使配置生效。
     */
    suspend fun restore(raw: String): BackupRestoreResult = withContext(Dispatchers.IO) {
        try {
            val bundle = json.decodeFromString(BackupBundle.serializer(), raw)
            if (bundle.version > BackupBundle.CURRENT_VERSION) {
                return@withContext BackupRestoreResult(
                    false,
                    "备份版本 ${bundle.version} 高于当前支持版本 ${BackupBundle.CURRENT_VERSION}"
                )
            }
            if (bundle.nodes.isNotEmpty()) {
                nodeRepository.saveAllPreservingStats(bundle.nodes)
            }
            bundle.subscriptions.forEach { subscriptionDao.upsert(it) }
            if (bundle.appRules.isNotEmpty()) {
                appRuleRepository.saveAll(bundle.appRules)
            }
            preferencesManager.update { bundle.settings }
            BackupRestoreResult(
                success = true,
                message = "恢复完成：${bundle.nodes.size} 个节点、${bundle.subscriptions.size} 个订阅、" +
                    "${bundle.appRules.size} 条应用规则，请重新连接",
                nodes = bundle.nodes.size,
                subscriptions = bundle.subscriptions.size,
                rules = bundle.appRules.size
            )
        } catch (error: Exception) {
            BackupRestoreResult(
                false,
                "备份解析失败：${error.message ?: error.javaClass.simpleName}"
            )
        }
    }
}
