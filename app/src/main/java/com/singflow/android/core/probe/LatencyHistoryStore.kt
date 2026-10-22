package com.singflow.android.core.probe

import java.io.File
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
) {
    /** 该次采样是否成功（0 表示超时/失败，用于曲线留空）。 */
    val isSuccess: Boolean get() = latencyMs > 0
}

/**
 * 节点延迟历史环形缓冲。
 *
 * 每个节点保留最近 [MAX_SAMPLES] 次成功采样；内存 + 可选文件持久化，
 * 重启后可继续绘制趋势，同时限制磁盘增长。
 */
object LatencyHistoryStore {

    /** 每个节点保留的最大采样数。 */
    const val MAX_SAMPLES = 40

    private const val FILE_NAME = "latency_history.txt"

    private val history = ConcurrentHashMap<String, ArrayDeque<LatencySample>>()
    private var storageFile: File? = null

    /**
     * 绑定持久化文件并恢复历史。
     *
     * @param directory 应用私有目录（不存在时自动创建）
     */
    @Synchronized
    fun attach(directory: File) {
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, FILE_NAME)
        storageFile = file
        if (!file.exists()) return
        val decoded = runCatching { decodeHistory(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
        history.clear()
        decoded.forEach { (nodeId, samples) ->
            history[nodeId] = ArrayDeque(samples.takeLast(MAX_SAMPLES))
        }
    }

    /** 记录一次成功延迟；非正延迟忽略。 */
    @Synchronized
    fun record(nodeId: String, latencyMs: Int, timestampMs: Long = System.currentTimeMillis()) {
        if (nodeId.isBlank() || latencyMs <= 0) return
        append(nodeId, LatencySample(timestampMs, latencyMs))
    }

    /**
     * 记录一次失败采样（延迟 0），用于在趋势图中留出断线空洞。
     */
    @Synchronized
    fun recordFailure(nodeId: String, timestampMs: Long = System.currentTimeMillis()) {
        if (nodeId.isBlank()) return
        append(nodeId, LatencySample(timestampMs, 0))
    }

    private fun append(nodeId: String, sample: LatencySample) {
        val deque = history.getOrPut(nodeId) { ArrayDeque() }
        deque.addLast(sample)
        while (deque.size > MAX_SAMPLES) {
            deque.removeFirst()
        }
        persist()
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
        persist()
    }

    /** 清空全部历史（测试/断开连接时使用）。 */
    @Synchronized
    fun clearAll() {
        history.clear()
        persist()
    }

    private fun persist() {
        val file = storageFile ?: return
        runCatching {
            file.writeText(encodeHistory(history), Charsets.UTF_8)
        }
    }
}

/**
 * 编码延迟历史为文本：每行 `nodeId|ts:lat;ts:lat`。
 */
internal fun encodeHistory(history: Map<String, ArrayDeque<LatencySample>>): String {
    return history.entries.joinToString("\n") { (nodeId, samples) ->
        val points = samples.joinToString(";") { "${it.timestampMs}:${it.latencyMs}" }
        "$nodeId|$points"
    }
}

/**
 * 解析延迟历史文本；非法行忽略。
 */
internal fun decodeHistory(text: String): Map<String, List<LatencySample>> {
    if (text.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, List<LatencySample>>()
    text.lineSequence().forEach { line ->
        val separator = line.indexOf('|')
        if (separator <= 0) return@forEach
        val nodeId = line.substring(0, separator)
        val samples = line.substring(separator + 1)
            .split(';')
            .mapNotNull { point ->
                val parts = point.split(':')
                val timestamp = parts.getOrNull(0)?.toLongOrNull()
                val latency = parts.getOrNull(1)?.toIntOrNull()
                if (timestamp == null || latency == null || latency < 0) {
                    null
                } else {
                    LatencySample(timestamp, latency)
                }
            }
            .takeLast(LatencyHistoryStore.MAX_SAMPLES)
        if (samples.isNotEmpty()) result[nodeId] = samples
    }
    return result
}
