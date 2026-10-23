package com.singflow.android.core.wifi

/**
 * 受信任 Wi-Fi 情境下对 VPN 引擎应采取的动作。
 */
enum class TrustedWifiAction {

    /** 关闭隧道引擎，服务进入前台挂起状态。 */
    PARK,

    /** 重建隧道，恢复代理转发。 */
    RESUME,

    /** 隧道正常运行且网络非受信任，无需干预。 */
    KEEP_TUNNEL,

    /** 已处于挂起状态且当前仍为受信任网络，维持现状。 */
    KEEP_PARKED,

    /** 生命周期中间态（连接中/断开中），暂不干预。 */
    IGNORE
}

/**
 * 受信任 Wi-Fi 与隧道状态之间的决策器（纯函数，无 Android 依赖，可直接 JUnit 验证）。
 *
 * 状态 输入约定：
 * - [tunnelActive]：隧道引擎当前在位（CONNECTED / CONNECTING）；
 * - [parked]：服务正处于受信任挂起状态；
 * - [trustedNow]：当前网络命中受信任 SSID。
 *
 * `tunnelActive` 与 `parked` 同时为 false 表示生命周期中间态（启动中或停止中），
 * 此时返回 [TrustedWifiAction.IGNORE]，避免与进行中的启停流程竞争。
 */
object TrustedWifiPolicy {

    /**
     * 依据隧道与挂起状态、当前网络是否受信任，返回应执行的动作。
     */
    fun decide(
        tunnelActive: Boolean,
        parked: Boolean,
        trustedNow: Boolean
    ): TrustedWifiAction = when {
        parked && trustedNow -> TrustedWifiAction.KEEP_PARKED
        parked && !trustedNow -> TrustedWifiAction.RESUME
        tunnelActive && trustedNow -> TrustedWifiAction.PARK
        tunnelActive && !trustedNow -> TrustedWifiAction.KEEP_TUNNEL
        else -> TrustedWifiAction.IGNORE
    }
}
