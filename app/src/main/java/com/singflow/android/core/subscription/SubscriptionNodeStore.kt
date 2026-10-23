package com.singflow.android.core.subscription

import com.singflow.android.core.model.ProxyNode

/**
 * 订阅节点存储抽象。
 *
 * 生产环境由 Room 的 `NodeRepository` 实现；抽象化的目的是让
 * 订阅刷新编排（差异删除、保存、统计保留）可以在 JVM 单测中
 * 用内存实现进行全流程验证，而不依赖 Android 运行时。
 */
interface SubscriptionNodeStore {

    /** 按订阅分组读取节点。 */
    suspend fun getByGroup(groupId: String): List<ProxyNode>

    /** 按 ID 批量删除节点。 */
    suspend fun deleteAll(ids: List<String>)

    /** 批量保存节点，并保留同节点既有的延迟/统计字段。 */
    suspend fun saveAllPreservingStats(nodes: List<ProxyNode>): Int
}
