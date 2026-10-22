package com.singflow.android.core.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.singflow.android.core.model.AppSettings

/**
 * 受信任 Wi-Fi 识别。
 *
 * Android 13 起读取 SSID 需要 `ACCESS_FINE_LOCATION` 且定位开关开启；
 * 无法读取时返回 null，调用方应提示用户授权，而不是误判为「不受信任」。
 */
object TrustedWifi {

    private const val UNKNOWN_SSID = "<unknown ssid>"

    /**
     * 读取当前 Wi-Fi SSID；非 Wi-Fi、无权限或系统脱敏时返回 null。
     */
    fun currentSsid(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(WifiManager::class.java) ?: return null
        val raw = runCatching { manager.connectionInfo?.ssid }.getOrNull() ?: return null
        val ssid = raw.trim().trim('"')
        if (ssid.isBlank() || ssid == UNKNOWN_SSID || ssid == "0x") return null
        return ssid
    }

    /**
     * 当前网络是否命中所配置的受信任 SSID。
     */
    fun isTrusted(context: Context, settings: AppSettings): Boolean {
        if (!settings.trustedWifiEnabled || settings.trustedWifiSsids.isEmpty()) return false
        return matches(settings.trustedWifiSsids, currentSsid(context))
    }

    /**
     * 判断当前 SSID 是否命中受信任列表（大小写不敏感）。
     */
    fun matches(configured: List<String>, current: String?): Boolean {
        if (current.isNullOrBlank()) return false
        return configured.any { it.equals(current, ignoreCase = true) }
    }
}
