package com.singflow.android.core.power

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 三级功耗状态机测试。
 */
class PowerStateControllerTest {

    @Test
    fun `foreground screen on is l1 and background is l2`() = runTest {
        val controller = PowerStateController(this, idleProbeIntervalSec = 60)

        assertEquals(PowerLevel.L1_FOREGROUND, controller.profile.value.level)
        assertEquals(60, controller.profile.value.activeProbeIntervalSec)

        controller.onBackground()
        assertEquals(PowerLevel.L2_BACKGROUND_SCREEN_ON, controller.profile.value.level)
        assertEquals(0L, controller.profile.value.trafficSampleIntervalMs)

        controller.onForeground()
        assertEquals(PowerLevel.L1_FOREGROUND, controller.profile.value.level)
    }

    @Test
    fun `screen off enters l3 only after delay`() = runTest {
        val controller = PowerStateController(this, screenOffDelayMs = 15_000L)
        controller.onScreenOff()

        assertEquals(PowerLevel.L2_BACKGROUND_SCREEN_ON, controller.profile.value.level)

        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(PowerLevel.L2_BACKGROUND_SCREEN_ON, controller.profile.value.level)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(PowerLevel.L3_SCREEN_OFF, controller.profile.value.level)
    }

    @Test
    fun `screen on cancels pending l3 and restores l1`() = runTest {
        val controller = PowerStateController(this, screenOffDelayMs = 15_000L)
        controller.onScreenOff()
        advanceTimeBy(10_000L)
        runCurrent()

        controller.onScreenOn()
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(PowerLevel.L1_FOREGROUND, controller.profile.value.level)
    }

    @Test
    fun `power save while screen off enters l3 immediately`() = runTest {
        val controller = PowerStateController(this, screenOffDelayMs = 15_000L)
        controller.onScreenOff()

        controller.onPowerSaveModeChanged(true)

        assertEquals(PowerLevel.L3_SCREEN_OFF, controller.profile.value.level)
        assertEquals(0L, controller.profile.value.notificationIntervalMs)
    }

    @Test
    fun `profile change callback fires once per transition`() = runTest {
        val changes = mutableListOf<PowerLevel>()
        val controller = PowerStateController(
            scope = this,
            onProfileChanged = { changes += it.level },
            screenOffDelayMs = 15_000L
        )

        controller.onBackground()
        controller.onBackground()
        controller.onForeground()
        controller.onScreenOff()
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(
            listOf(PowerLevel.L2_BACKGROUND_SCREEN_ON, PowerLevel.L1_FOREGROUND, PowerLevel.L2_BACKGROUND_SCREEN_ON, PowerLevel.L3_SCREEN_OFF),
            changes
        )
    }
}
