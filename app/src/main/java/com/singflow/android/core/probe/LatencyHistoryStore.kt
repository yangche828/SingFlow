package com.singflow.android.core.probe

import java.util.concurrent.ConcurrentHashMap

/**
 * 单次延迟采样。
 *
 * @property timestampMs 采样时间
 * @property latencyMs 延迟毫秒
 */
data class LatencySample(
    val timestampMs: Long,
    val latencyMs: Int
)

/**
 * 进程内节点延迟历史环形缓冲。
 *
 * 仅保留最近 [MAX_SAMPLES] 次成功采样，用于诊断页绘制趋势；
 * 不落盘，避免长期运行无限增长。
 */
object LatencyHistoryStore {

    /** 每个节点保留的最大采样数。 */
    const val MAX_SAMPLES = 40

    private val history = ConcurrentHashMap<String, ArrayDeque<LatencySample>>()

    /**
     * 记录一次成功延迟；非正延迟忽略。
     */
    @Synchronized
    fun record(nodeId: String, latencyMs: Int, timestampMs: Long = System.currentTimeMillis()) {
        if (nodeId.isBlank() || latencyMs <= 0) return
        val deque = history.getOrPut(nodeId) { ArrayDeque() }
        deque.addLast(LatencySample(timestampMs, latencyMs))
        while (deque.size > MAX_SAMPLES) {
            deque.removeFirst()
        }
    }

    /** 读取节点延迟历史（时间正序）。 */
    @Synchronized
    fun samples(nodeId: String): List<LatencySample> {
        if (nodeId.isBlank()) return emptyList()
        return history[nodeId]?.toList().orEmpty()
    }

    /** 清空指定节点历史。 */
    @Synchronized
    fun clear(nodeId: String) {
        history.remove(nodeId)
    }

    /** 清空全部历史（测试/断开连接时使用）。 */
    @Synchronized
    fun clearAll() {
        history.clear()
    }
}
