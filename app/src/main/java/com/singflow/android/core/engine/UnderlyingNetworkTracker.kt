package com.singflow.android.core.engine

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 物理网络切换监听器。
 *
 * Wi-Fi ↔ 5G 切换时系统会新建/销毁物理网络，若 VPN 仍绑定旧网络，
 * 内核出站会卡在失效的 socket 上（典型症状 `egress_timeout`）。
 * 本类在 `onAvailable` / `onCapabilitiesChanged` 时调用
 * [VpnService.setUnderlyingNetworks] 重新绑定，并 250ms 防抖后通知上层。
 *
 * @param vpnService VPN 服务实例
 * @param scope 与服务同生命周期的协程作用域
 * @param onPhysicalNetworkChanged 物理网络变化回调（防抖后）
 */
class UnderlyingNetworkTracker(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val onPhysicalNetworkChanged: suspend (Network?) -> Unit = {}
) {

    private val connectivityManager =
        vpnService.getSystemService(ConnectivityManager::class.java)

    private var debounceJob: Job? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            rebind(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                rebind(network)
            }
        }

        override fun onLost(network: Network) {
            // 不立即清空底层网络：系统可能马上送上新的默认网络，
            // 清空会让内核短暂没有任何可用出站接口。
            Log.i(TAG, "physical network lost: $network")
            debounce { onPhysicalNetworkChanged(null) }
        }
    }

    /** 开始监听。 */
    fun register() {
        if (registered) return
        val manager = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, callback)
            registered = true
            Log.i(TAG, "network tracker registered")
        }.onFailure { Log.w(TAG, "registerNetworkCallback failed", it) }
    }

    /** 停止监听。 */
    fun unregister() {
        debounceJob?.cancel()
        debounceJob = null
        if (!registered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
            .onFailure { Log.w(TAG, "unregisterNetworkCallback failed", it) }
        registered = false
    }

    private fun rebind(network: Network) {
        runCatching { vpnService.setUnderlyingNetworks(arrayOf(network)) }
            .onFailure { Log.w(TAG, "setUnderlyingNetworks failed", it) }
        Log.i(TAG, "underlying network -> $network")
        debounce { onPhysicalNetworkChanged(network) }
    }

    private fun debounce(action: suspend () -> Unit) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { action() }
                .onFailure { Log.w(TAG, "physical network callback failed", it) }
        }
    }

    private companion object {
        private const val TAG = "UnderlyingNetworkTracker"
        private const val DEBOUNCE_MS = 250L
    }
}
