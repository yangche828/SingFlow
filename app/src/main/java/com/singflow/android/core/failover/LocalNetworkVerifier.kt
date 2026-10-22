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
 * 基于 [ConnectivityManager] 的实现：要求底层网络同时具备 INTERNET 与 VALIDATED。
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
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // VPN 自身就是当前网络时，继续看其底层是否已校验。
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
