package com.singflow.android.core.wifi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 受信任 Wi-Fi 匹配测试。
 */
class TrustedWifiTest {

    @Test
    fun `matches ssid case insensitive`() {
        assertTrue(TrustedWifi.matches(listOf("HomeWiFi"), "homewifi"))
        assertTrue(TrustedWifi.matches(listOf("HomeWiFi"), "HomeWiFi"))
    }

    @Test
    fun `empty or null never matches`() {
        assertFalse(TrustedWifi.matches(emptyList(), "HomeWiFi"))
        assertFalse(TrustedWifi.matches(listOf("HomeWiFi"), null))
        assertFalse(TrustedWifi.matches(listOf("HomeWiFi"), ""))
    }
}
