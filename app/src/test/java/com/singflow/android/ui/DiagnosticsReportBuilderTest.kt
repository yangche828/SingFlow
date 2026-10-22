package com.singflow.android.ui

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.VpnState
import com.singflow.android.core.model.VpnStatus
import com.singflow.android.core.probe.ClashCountedLabel
import com.singflow.android.core.probe.LatencySample
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断报告构建测试。
 */
class DiagnosticsReportBuilderTest {

    private fun node(name: String, failures: Int, health: Int) = ProxyNode(
        id = name,
        name = name,
        protocol = ProtocolType.VLESS,
        server = "example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        consecutiveFailures = failures,
        healthScore = health
    )

    @Test
    fun `report contains key sections and omits credentials`() {
        val report = DiagnosticsReportBuilder.build(
            vpnState = VpnState(status = VpnStatus.CONNECTED, engineMode = "NATIVE"),
            selectedNode = node("当前节点", 0, 80),
            allNodes = listOf(node("当前节点", 0, 80), node("坏节点", 6, 0)),
            available = true,
            groups = emptyList(),
            ruleHits = listOf(ClashCountedLabel("final", 3)),
            chainHits = listOf(ClashCountedLabel("node-1", 3)),
            latencyHistory = listOf(LatencySample(1000L, 20), LatencySample(2000L, 0)),
            subscriptions = listOf(
                SubscriptionReport(
                    name = "测试订阅",
                    upload = 1024L * 1024 * 1024,
                    download = 2L * 1024 * 1024 * 1024,
                    total = 10L * 1024 * 1024 * 1024,
                    expireAt = 1_893_456_000_000L
                )
            ),
            timestampMs = 1_700_000_000_000L
        )

        assertTrue(report.contains("SingFlow 诊断报告"))
        assertTrue(report.contains("节点：2 个（异常 1 个）"))
        assertTrue(report.contains("订阅：1 个"))
        assertTrue(report.contains("测试订阅"))
        assertTrue(report.contains("剩余 7.0 GB"))
        assertTrue(report.contains("final: 3"))
        assertTrue(report.contains("失败 1 次"))
        assertTrue(report.contains("坏节点：连续失败 6 次"))
        assertTrue(!report.contains("11111111-1111"))
    }

    @Test
    fun `empty report explains no connections`() {
        val report = DiagnosticsReportBuilder.build(
            vpnState = VpnState(),
            selectedNode = null,
            allNodes = emptyList(),
            available = false,
            groups = emptyList(),
            ruleHits = emptyList(),
            chainHits = emptyList(),
            latencyHistory = emptyList()
        )
        assertTrue(report.contains("当前节点：未选择"))
        assertTrue(report.contains("无活跃连接"))
    }
}
