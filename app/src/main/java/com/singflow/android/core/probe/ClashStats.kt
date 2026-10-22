package com.singflow.android.core.probe

/**
 * 诊断聚合计数项。
 *
 * @property label 规则或出站链
 * @property count 活跃连接数
 */
data class ClashCountedLabel(
    val label: String,
    val count: Int
)

/**
 * 活跃连接聚合统计（纯函数，便于测试）。
 */
object ClashStats {

    /**
     * 按规则聚合活跃连接，命中数降序；空规则归为「未命中规则」。
     */
    fun ruleHits(connections: List<ClashConnection>): List<ClashCountedLabel> {
        return connections
            .groupingBy { it.rule.ifBlank { "未命中规则" } }
            .eachCount()
            .map { (label, count) -> ClashCountedLabel(label, count) }
            .sortedWith(compareByDescending<ClashCountedLabel> { it.count }.thenBy { it.label })
    }

    /**
     * 按出站链首节点聚合，便于观察实际使用的代理组。
     */
    fun chainHits(connections: List<ClashConnection>): List<ClashCountedLabel> {
        return connections
            .groupingBy { it.chains.firstOrNull() ?: "直连" }
            .eachCount()
            .map { (label, count) -> ClashCountedLabel(label, count) }
            .sortedWith(compareByDescending<ClashCountedLabel> { it.count }.thenBy { it.label })
    }
}
