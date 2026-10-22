package com.singflow.android.core.parser

import com.singflow.android.core.model.NodeFingerprint
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点名称清洗、指纹与国家预判单元测试。
 */
class NodeParserUtilsTest {

    @Test
    fun `clean name decodes chinese emoji and preserves plus`() {
        val raw = "%E6%97%A5%E6%9C%AC%20%F0%9F%87%AF%F0%9F%87%B5%20A%2BB"
        val cleaned = NodeParserUtils.cleanNodeDisplayName(raw, "example.com", 443)
        assertEquals("日本 🇯🇵 A+B", cleaned)
    }

    @Test
    fun `clean name removes control and zero width characters`() {
        val raw = "  日本\u200B\u0007节点\u0000  "
        val cleaned = NodeParserUtils.cleanNodeDisplayName(raw, "example.com", 443)
        assertEquals("日本节点", cleaned)
    }

    @Test
    fun `blank fragment falls back to host and port`() {
        assertEquals("1.2.3.4:8443", NodeParserUtils.cleanNodeDisplayName(null, "1.2.3.4", 8443))
        assertEquals("1.2.3.4:8443", NodeParserUtils.cleanNodeDisplayName("  ", "1.2.3.4", 8443))
        assertEquals("1.2.3.4:8443", NodeParserUtils.cleanNodeDisplayName("\u200B", "1.2.3.4", 8443))
    }

    @Test
    fun `long names are truncated to max length`() {
        val cleaned = NodeParserUtils.cleanNodeDisplayName("a".repeat(200), "h", 1)
        assertEquals(NodeParserUtils.MAX_NAME_LENGTH, cleaned.length)
    }

    @Test
    fun `country is guessed from flag chinese and airport code`() {
        assertEquals("US", NodeParserUtils.guessCountryFromName("🇺🇸 洛杉矶 IPLC").code)
        assertEquals("日本", NodeParserUtils.guessCountryFromName("🇯🇵 东京 01").nameCn)
        assertEquals("JP", NodeParserUtils.guessCountryFromName("Tokyo Premium").code)
        assertEquals("SG", NodeParserUtils.guessCountryFromName("新加坡 · 狮城").code)
        assertEquals("HK", NodeParserUtils.guessCountryFromName("HKG 中转").code)
    }

    @Test
    fun `unknown country returns fallback`() {
        val match = NodeParserUtils.guessCountryFromName("神秘节点 01")
        assertEquals("UN", match.code)
        assertEquals("其他地区", match.nameCn)
    }

    @Test
    fun `fingerprint is stable and case insensitive for host and sni`() {
        val first = NodeFingerprint.of(
            protocol = "VLESS",
            host = "Example.COM",
            port = 443,
            credential = "11111111-2222-3333-4444-555555555555",
            transport = "ws:cdn.example.com:/ws:",
            sni = "CDN.Example.com"
        )
        val second = NodeFingerprint.of(
            protocol = "vless",
            host = "example.com",
            port = 443,
            credential = "11111111-2222-3333-4444-555555555555",
            transport = "ws:cdn.example.com:/ws:",
            sni = "cdn.example.com"
        )
        assertEquals(first, second)
        assertEquals(32, first.length)
    }

    @Test
    fun `fingerprint differs when credential or port differs`() {
        val base = ProxyNode(
            protocol = ProtocolType.VLESS,
            server = "example.com",
            port = 443,
            uuid = "uuid-a"
        )
        assertNotEquals(base.configHash, base.copy(uuid = "uuid-b").configHash)
        assertNotEquals(base.configHash, base.copy(port = 8443).configHash)
    }

    @Test
    fun `normalize cleans name and fills country for new node`() {
        val raw = ProxyNode(
            name = "%E6%97%A5%E6%9C%AC%20%F0%9F%87%AF%F0%9F%87%B5",
            server = "jp.example.com",
            port = 443
        )
        val normalized = NodeParserUtils.normalize(raw, rawShareUri = "vless://demo")

        assertEquals("日本 🇯🇵", normalized.name)
        assertEquals("JP", normalized.countryCode)
        assertEquals("日本", normalized.countryNameCn)
        assertEquals("vless://demo", normalized.rawShareUri)
    }

    @Test
    fun `normalize keeps verified region`() {
        val verified = ProxyNode(
            name = "美国节点",
            server = "us.example.com",
            port = 443,
            countryCode = "JP",
            countryNameCn = "日本",
            isRegionVerified = true
        )
        val normalized = NodeParserUtils.normalize(verified)
        assertEquals("JP", normalized.countryCode)
        assertEquals("日本", normalized.countryNameCn)
        assertTrue(normalized.isRegionVerified)
    }
}
