package com.singflow.android.core.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落地信息解析测试。
 */
class EgressTraceParserTest {

    @Test
    fun `cloudflare trace parses ip and country`() {
        val text = """
            fl=123f
            ip=104.28.192.11
            ts=1710000000.123
            loc=JP
            tls=TLSv1.3
        """.trimIndent()

        val info = EgressTraceParser.parseCloudflareTrace(text)

        assertEquals("104.28.192.11", info?.ip)
        assertEquals("JP", info?.countryCode)
        assertEquals("日本", info?.countryNameCn)
    }

    @Test
    fun `cloudflare trace without loc keeps ip and unknown country`() {
        val info = EgressTraceParser.parseCloudflareTrace("ip=1.2.3.4\nh=www.cloudflare.com")

        assertEquals("1.2.3.4", info?.ip)
        assertNull(info?.countryCode)
        assertEquals("其他地区", info?.countryNameCn)
    }

    @Test
    fun `cloudflare trace ignores invalid ip and short loc`() {
        val info = EgressTraceParser.parseCloudflareTrace("ip=not-an-ip\nloc=JPN")
        assertNull(info)
    }

    @Test
    fun `blank trace returns null`() {
        assertNull(EgressTraceParser.parseCloudflareTrace(""))
        assertNull(EgressTraceParser.parseCloudflareTrace("   \n  "))
    }

    @Test
    fun `geoip json parses country code and ip`() {
        val raw = """
            {"ip":"203.0.113.9","country_code":"SG","country":"Singapore","asn":123}
        """.trimIndent()

        val info = EgressTraceParser.parseGeoIpJson(raw)

        assertEquals("203.0.113.9", info?.ip)
        assertEquals("SG", info?.countryCode)
        assertEquals("新加坡", info?.countryNameCn)
    }

    @Test
    fun `geoip json malformed returns null`() {
        assertNull(EgressTraceParser.parseGeoIpJson("not-json"))
        assertNull(EgressTraceParser.parseGeoIpJson("{}"))
    }

    @Test
    fun `ip literal validation does not accept hostnames`() {
        assertTrue(EgressTraceParser.isIpLiteral("1.2.3.4"))
        assertTrue(EgressTraceParser.isIpLiteral("2606:4700::1111"))
        assertTrue(EgressTraceParser.isIpLiteral("::1"))
        assertFalse(EgressTraceParser.isIpLiteral("example.com"))
        assertFalse(EgressTraceParser.isIpLiteral("999.1.1.1"))
        assertFalse(EgressTraceParser.isIpLiteral(""))
    }
}
