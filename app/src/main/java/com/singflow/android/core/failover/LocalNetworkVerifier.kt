package com.singflow.android.core.failover

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 本机底层网络可用性判定。
 *
 * 用于在代理节点探测失败时先区分「手机自己断网」与「节点故障」，
 * 避免电梯/地下室场景误扣节点健康分并乱切。
 */
interface LocalNetworkVerifier {
    /** 当前底层网络是否具备可用互联网连接。 */
    fun isLocalNetworkUsable(): Boolean
}

/**
 * 基于 [ConnectivityManager] 的实现。
 *
 * 判定标准刻意不强制 `NET_CAPABILITY_VALIDATED`：
 * 模拟器 / 部分运营商网络在 VPN 抢占默认网络后不会返回 VALIDATED，
 * 但底层 TCP 仍然可用。这里以 `INTERNET 且非 Captive Portal` 为准，
 * 否则会把「节点故障」误判成「本机断网」而拒绝切换。
 *
 * @param context 应用上下文
 */
class AndroidLocalNetworkVerifier(context: Context) : LocalNetworkVerifier {

    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isLocalNetworkUsable(): Boolean {
        val manager = connectivityManager ?: return true
        val network = runCatching { manager.activeNetwork }.getOrNull() ?: return false
        val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            ?: return false

        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return false
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            return false
        }
        // VPN 承载网络由 SingFlow 自己维护，只看其是否具备 INTERNET。
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
