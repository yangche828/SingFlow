package com.singflow.android.core.power

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 功耗档位。
 */
enum class PowerLevel {
    /** L1：前台可见且屏幕点亮。 */
    L1_FOREGROUND,

    /** L2：后台运行但屏幕点亮。 */
    L2_BACKGROUND_SCREEN_ON,

    /** L3：息屏深度休眠。 */
    L3_SCREEN_OFF
}

/**
 * 某一功耗档位下的采样策略。
 *
 * @property level 档位
 * @property trafficSampleIntervalMs 速率采样间隔；0 表示完全挂起
 * @property activeProbeIntervalSec 主动巡检周期（秒）；0 表示停止主动巡检
 * @property notificationIntervalMs 通知栏刷新间隔；0 表示停止刷新
 * @property logStreamEnabled 是否挂载内核日志流
 */
data class PowerProfile(
    val level: PowerLevel,
    val trafficSampleIntervalMs: Long,
    val activeProbeIntervalSec: Int,
    val notificationIntervalMs: Long,
    val logStreamEnabled: Boolean
) {
    companion object {
        /** L1：前台活跃态。 */
        fun l1(probeIntervalSec: Int = 180): PowerProfile = PowerProfile(
            level = PowerLevel.L1_FOREGROUND,
            trafficSampleIntervalMs = 1_000L,
            activeProbeIntervalSec = probeIntervalSec,
            notificationIntervalMs = 2_000L,
            logStreamEnabled = true
        )

        /** L2：后台亮屏态。 */
        fun l2(): PowerProfile = PowerProfile(
            level = PowerLevel.L2_BACKGROUND_SCREEN_ON,
            trafficSampleIntervalMs = 0L,
            activeProbeIntervalSec = 0,
            notificationIntervalMs = 3_000L,
            logStreamEnabled = false
        )

        /** L3：息屏深度休眠态。 */
        fun l3(): PowerProfile = PowerProfile(
            level = PowerLevel.L3_SCREEN_OFF,
            trafficSampleIntervalMs = 0L,
            activeProbeIntervalSec = 0,
            notificationIntervalMs = 0L,
            logStreamEnabled = false
        )
    }
}

/**
 * 屏幕/前后台/省电模式感知的三级功耗状态机。
 *
 * 设计要点：
 * - 息屏后先进入 L2，满 [screenOffDelayMs] 才进入 L3，避免短暂锁屏就停止巡检；
 * - 进入 L3 后停止速率采样、主动巡检与通知刷新，让基带进入休眠；
 * - 亮屏或回到前台会立即恢复，并取消待执行的 L3 定时任务。
 *
 * @param scope 生命周期与宿主一致的协程作用域
 * @param onProfileChanged 档位变化回调
 * @param screenOffDelayMs 息屏进入 L3 的延迟（默认 15 秒）
 * @param idleProbeIntervalSec L1 档位下用户配置的巡检周期
 */
class PowerStateController(
    private val scope: CoroutineScope,
    private val onProfileChanged: (PowerProfile) -> Unit = {},
    private val screenOffDelayMs: Long = DEFAULT_SCREEN_OFF_DELAY_MS,
    private val idleProbeIntervalSec: Int = 180
) {

    private val _profile = MutableStateFlow(PowerProfile.l1(idleProbeIntervalSec))

    /** 当前功耗档位。 */
    val profile: StateFlow<PowerProfile> = _profile.asStateFlow()

    private var screenOn = true
    private var foreground = true
    private var powerSaveMode = false
    private var screenOffJob: Job? = null

    /** App 回到前台。 */
    fun onForeground() {
        foreground = true
        recompute()
    }

    /** App 退到后台。 */
    fun onBackground() {
        foreground = false
        recompute()
    }

    /** 屏幕点亮。 */
    fun onScreenOn() {
        screenOn = true
        screenOffJob?.cancel()
        screenOffJob = null
        recompute()
    }

    /** 屏幕熄灭：延迟进入 L3。 */
    fun onScreenOff() {
        screenOn = false
        recompute()
        screenOffJob?.cancel()
        screenOffJob = scope.launch {
            delay(screenOffDelayMs)
            if (!screenOn) {
                emit(PowerProfile.l3())
            }
        }
    }

    /** 系统省电模式变化。 */
    fun onPowerSaveModeChanged(enabled: Boolean) {
        powerSaveMode = enabled
        reduceForPowerSave(enabled)
        recompute()
    }

    private fun reduceForPowerSave(enabled: Boolean) {
        if (!enabled) return
        if (!screenOn) {
            screenOffJob?.cancel()
            screenOffJob = null
            emit(PowerProfile.l3())
        }
    }

    private fun recompute() {
        if (!screenOn && powerSaveMode) {
            emit(PowerProfile.l3())
            return
        }
        if (!screenOn) {
            // 息屏 15 秒内保持 L2，避免短暂锁屏就停掉巡检。
            if (_profile.value.level != PowerLevel.L3_SCREEN_OFF) {
                emit(PowerProfile.l2())
            }
            return
        }
        if (foreground) {
            emit(PowerProfile.l1(idleProbeIntervalSec))
        } else {
            emit(PowerProfile.l2())
        }
    }

    private fun emit(next: PowerProfile) {
        if (_profile.value == next) return
        _profile.value = next
        onProfileChanged(next)
    }

    companion object {
        /** 息屏进入 L3 的默认延迟。 */
        const val DEFAULT_SCREEN_OFF_DELAY_MS = 15_000L
    }
}
