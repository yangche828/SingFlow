package com.singflow.android.ui

import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.probe.ClashCountedLabel
import com.singflow.android.core.probe.ClashProxyGroup
import com.singflow.android.core.probe.LatencySample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断报告中的订阅摘要。
 */
data class SubscriptionReport(
    val name: String,
    val upload: Long,
    val download: Long,
    val total: Long,
    val expireAt: Long
)

/**
 * 诊断报告构建器。
 *
 * 把页面可见状态整理为可粘贴的纯文本，便于用户反馈问题；
 * 只包含节点展示名与统计，不含 UUID、密码等凭据。
 */
object DiagnosticsReportBuilder {

    /**
     * 构建诊断报告。
     */
    fun build(
        vpnState: VpnState,
        selectedNode: ProxyNode?,
        allNodes: List<ProxyNode>,
        available: Boolean,
        groups: List<ClashProxyGroup>,
        ruleHits: List<ClashCountedLabel>,
        chainHits: List<ClashCountedLabel>,
        latencyHistory: List<LatencySample>,
        subscriptions: List<SubscriptionReport> = emptyList(),
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
        val problems = allNodes.filter { it.consecutiveFailures > 0 || it.isCoolingDown() }
        return buildString {
            appendLine("SingFlow 诊断报告")
            appendLine("时间：$time")
            appendLine("VPN：${vpnState.status} · ${vpnState.engineMode}")
            appendLine("当前节点：${selectedNode?.displayName ?: "未选择"}")
            appendLine("Clash API：${if (available) "可用" else "不可用"}")
            appendLine("节点：${allNodes.size} 个（异常 ${problems.size} 个）")
            appendLine("订阅：${subscriptions.size} 个")
            subscriptions.take(5).forEach { subscription ->
                appendLine("  - ${subscription.name}：${formatSubscription(subscription)}")
            }
            appendLine("代理组：${groups.size} 个")
            if (groups.isNotEmpty()) {
                groups.take(5).forEach { group ->
                    appendLine("  - ${group.tag}（${group.type}，选中 ${group.now.ifBlank { "无" }}）")
                }
            }
            appendLine("规则命中：")
            if (ruleHits.isEmpty()) {
                appendLine("  - 无活跃连接")
            } else {
                ruleHits.take(5).forEach { hit -> appendLine("  - ${hit.label}: ${hit.count}") }
            }
            appendLine("出站链：")
            if (chainHits.isEmpty()) {
                appendLine("  - 无活跃连接")
            } else {
                chainHits.take(5).forEach { hit -> appendLine("  - ${hit.label}: ${hit.count}") }
            }
            val success = latencyHistory.filter { it.isSuccess }
            if (success.isNotEmpty()) {
                appendLine(
                    "延迟历史：${latencyHistory.size} 次采样，失败 ${latencyHistory.size - success.size} 次，" +
                        "最低 ${success.minOf { it.latencyMs }} ms，最高 ${success.maxOf { it.latencyMs }} ms"
                )
            }
            if (problems.isNotEmpty()) {
                appendLine("异常节点：")
                problems.take(5).forEach { node ->
                    appendLine(
                        "  - ${node.displayName}：连续失败 ${node.consecutiveFailures} 次，" +
                            "健康 ${node.healthScore} 分"
                    )
                }
            }
        }.trimEnd()
    }

    private fun formatSubscription(subscription: SubscriptionReport): String {
        val parts = mutableListOf<String>()
        if (subscription.total > 0) {
            val used = subscription.upload + subscription.download
            val remaining = (subscription.total - used).coerceAtLeast(0)
            parts += "剩余 ${formatGb(remaining)} / ${formatGb(subscription.total)}"
        }
        if (subscription.expireAt > 0) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(subscription.expireAt))
            parts += "到期 $date"
        }
        return if (parts.isEmpty()) "无流量信息" else parts.joinToString(" · ")
    }

    private fun formatGb(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.US, "%.1f GB", gb)
    }
}
