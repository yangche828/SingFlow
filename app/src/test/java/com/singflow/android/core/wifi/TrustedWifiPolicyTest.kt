package com.singflow.android.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TrustedWifiPolicy] 决策矩阵单元测试：
 * 覆盖「进入受信任网络自动暂停」与「离开受信任网络自动恢复」两条核心路径，
 * 以及隧道维持、挂起维持与生命周期中间态三个边界。
 */
class TrustedWifiPolicyTest {

    @Test
    fun `隧道运行中进入受信任网络应挂起`() {
        assertEquals(
            TrustedWifiAction.PARK,
            TrustedWifiPolicy.decide(tunnelActive = true, parked = false, trustedNow = true)
        )
    }

    @Test
    fun `挂起中离开受信任网络应自动恢复`() {
        assertEquals(
            TrustedWifiAction.RESUME,
            TrustedWifiPolicy.decide(tunnelActive = false, parked = true, trustedNow = false)
        )
    }

    @Test
    fun `隧道运行中且非受信任网络应维持转发`() {
        assertEquals(
            TrustedWifiAction.KEEP_TUNNEL,
            TrustedWifiPolicy.decide(tunnelActive = true, parked = false, trustedNow = false)
        )
    }

    @Test
    fun `挂起中且仍为受信任网络应维持挂起`() {
        assertEquals(
            TrustedWifiAction.KEEP_PARKED,
            TrustedWifiPolicy.decide(tunnelActive = false, parked = true, trustedNow = true)
        )
    }

    @Test
    fun `隧道与挂起均为否时处于生命周期中间态应忽略`() {
        assertEquals(
            TrustedWifiAction.IGNORE,
            TrustedWifiPolicy.decide(tunnelActive = false, parked = false, trustedNow = true)
        )
        assertEquals(
            TrustedWifiAction.IGNORE,
            TrustedWifiPolicy.decide(tunnelActive = false, parked = false, trustedNow = false)
        )
    }
}
