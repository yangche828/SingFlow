package com.singflow.android.core.parser

import com.singflow.android.core.parser.impl.VlessParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ECH 导入链路测试：VLESS `ech=` 参数形态与 DoH 端点归一化。
 *
 * 端点归一化单独验证，因为它决定「订阅里写 /dns-query 的地址能否真的查到 ECH」。
 */
class EchConfigEndpointTest {

    private val base =
        "vless://ed0b524e-8507-41f8-8223-d08a00b3c325@1.2.3.4:443" +
            "?encryption=none&security=tls&sni=ech.example.com&type=ws&path=%2F"

    @Test
    fun `仅带来源域名时仍启用 ECH 并由默认 DoH 兜底`() {
        val node = VlessParser().parse("$base&ech=cloudflare-ech.com#only-source")!!
        assertTrue(node.tls.echEnabled)
        assertEquals("cloudflare-ech.com", node.tls.echConfigSource)
        assertEquals("", node.tls.echDohUrl)
    }

    @Test
    fun `udp 写法的 DoH 被忽略而不是抛异常`() {
        val node = VlessParser()
            .parse("$base&ech=cloudflare-ech.com%2Budp%3A%2F%2F1.1.1.1#udp-doh")!!
        assertEquals("cloudflare-ech.com", node.tls.echConfigSource)
        assertEquals("udp://1.1.1.1", node.tls.echDohUrl)
        assertTrue("非 http(s) 端点应被丢弃", EchConfigResolver.jsonEndpoints(node.tls.echDohUrl).isEmpty())
    }

    @Test
    fun `RFC8484 的 dns-query 端点同时尝试同源 resolve`() {
        val candidates = EchConfigResolver.jsonEndpoints("https://dns.alidns.com/dns-query")
        assertEquals(
            listOf("https://dns.alidns.com/resolve", "https://dns.alidns.com/dns-query"),
            candidates
        )
    }

    @Test
    fun `已是 JSON API 的端点不重复展开`() {
        assertEquals(
            listOf("https://dns.google/resolve"),
            EchConfigResolver.jsonEndpoints("https://dns.google/resolve/")
        )
    }

    @Test
    fun `空端点不产生候选`() {
        assertTrue(EchConfigResolver.jsonEndpoints("  ").isEmpty())
        assertFalse(EchConfigResolver.jsonEndpoints("ftp://dns.example.com").isNotEmpty())
    }
}
