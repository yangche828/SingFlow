package com.singflow.android.core.database

import kotlinx.coroutines.flow.Flow

/**
 * 应用级国家分流规则仓库。
 *
 * @param dao 规则 DAO
 */
class AppRouteRuleRepository(
    private val dao: AppRouteRuleDao
) {
    /** 观察全部规则。 */
    fun observeAll(): Flow<List<AppRouteRuleEntity>> = dao.observeAll()

    /** 读取全部规则。 */
    suspend fun getAll(): List<AppRouteRuleEntity> = dao.getAll()

    /** 按包名读取。 */
    suspend fun getByPackage(packageName: String): AppRouteRuleEntity? = dao.getByPackage(packageName)

    /** 保存单条规则。 */
    suspend fun save(rule: AppRouteRuleEntity) {
        dao.upsert(rule)
    }

    /** 批量保存。 */
    suspend fun saveAll(rules: List<AppRouteRuleEntity>) {
        if (rules.isEmpty()) return
        dao.upsertAll(rules)
    }

    /** 删除某应用的规则。 */
    suspend fun delete(packageName: String) {
        dao.deleteByPackage(packageName)
    }

    /** 清空全部规则。 */
    suspend fun clear() {
        dao.deleteAll()
    }
}
