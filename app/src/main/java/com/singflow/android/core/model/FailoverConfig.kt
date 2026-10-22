package com.singflow.android.core.model

import kotlinx.serialization.Serializable

/**
 * 自动切换预设模式。
 *
 * @property displayName 中文名称
 * @property description 适用场景说明
 */
@Serializable
enum class FailoverPreset(val displayName: String, val description: String) {
    /** 同国锁定 · 健康优先（默认推荐）。 */
    COUNTRY_LOCKED_HEALTH(
        "同国锁定 · 健康优先",
        "优先在同一国家内切换，只有该国全部不可用时才按备用国家顺序跨国防灾"
    ),

    /** 收藏池优先 · 极速容灾。 */
    FAVORITES_FIRST_FAST(
        "收藏池优先 · 极速容灾",
        "优先在收藏节点中切换，收藏池全部不可用时降级到健康分 ≥ 75 的高分节点"
    ),

    /** 全域极速 · 低延迟竞技。 */
    GLOBAL_LOWEST_LATENCY(
        "全域极速 · 低延迟竞技",
        "打破默认国家边界，在全网健康节点中追踪 RTT 最低的节点"
    ),

    /** 稳健防抖 · 仅断网保底。 */
    STABLE_HYSTERESIS_ONLY(
        "稳健防抖 · 仅断网保底",
        "当前节点未断连时绝不因延迟波动切换，避免 SSH/金融软件频繁重连"
    )
}

/**
 * 故障自动切换配置（四大预设 + 七项微调参数）。
 *
 * @property enabled 是否启用自动切换
 * @property preset 预设方向
 * @property activeCheckIntervalSec 亮屏主动巡检周期（秒），0 表示仅被动触发
 * @property singleProbeTimeoutMs 单次探活超时（毫秒）
 * @property failConfirmRetries 连续失败确认次数
 * @property slowLatencyThresholdMs 高延迟慢速熔断阈值（毫秒）
 * @property switchHysteresisScoreDiff 非故障切换所需的最小健康分增益
 * @property switchLatencyToleranceMs 非故障切换所需的最小延迟改善（毫秒）
 * @property deadNodeCooldownMinutes 故障节点冷却隔离时长（分钟）
 * @property allowCrossCountryFallback 同国全灭时是否允许跨国防灾
 * @property fallbackCountryOrder 跨国备用国家顺序
 * @property showToastOnSwitch 切换时是否提示用户
 * @property defaultCountryCode 默认国家（空串表示使用全局高分池）
 */
@Serializable
data class FailoverConfig(
    val enabled: Boolean = true,
    val preset: FailoverPreset = FailoverPreset.COUNTRY_LOCKED_HEALTH,
    val activeCheckIntervalSec: Int = 180,
    val singleProbeTimeoutMs: Int = 3000,
    val failConfirmRetries: Int = 2,
    val slowLatencyThresholdMs: Int = 850,
    val switchHysteresisScoreDiff: Int = 12,
    val switchLatencyToleranceMs: Int = 80,
    val deadNodeCooldownMinutes: Int = 10,
    val allowCrossCountryFallback: Boolean = true,
    val fallbackCountryOrder: List<String> = listOf("SG", "JP", "HK", "US", "TW", "KR"),
    val showToastOnSwitch: Boolean = false,
    val defaultCountryCode: String = ""
) {
    /** 归一化后的备用国家顺序（大写、去空、去重）。 */
    fun normalizedFallbackOrder(): List<String> = fallbackCountryOrder
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .distinct()

    companion object {
        /** 主动巡检周期的允许值。 */
        val ACTIVE_CHECK_INTERVAL_OPTIONS = listOf(0, 30, 60, 180, 300)

        /** 单次探活超时范围（毫秒）。 */
        val PROBE_TIMEOUT_RANGE = 1000..5000

        /** 连续失败确认次数范围。 */
        val FAIL_CONFIRM_RANGE = 1..5

        /** 慢速熔断阈值范围（毫秒）。 */
        val SLOW_LATENCY_RANGE = 400..2000

        /** 冷却时长范围（分钟）。 */
        val COOLDOWN_MINUTES_RANGE = 1..60

        /** 默认值。 */
        val DEFAULT = FailoverConfig()
    }
}
