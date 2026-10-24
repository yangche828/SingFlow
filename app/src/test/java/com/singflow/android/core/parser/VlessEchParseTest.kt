package com.singflow.android.core.parser

import com.singflow.android.core.parser.impl.VlessParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VLESS ech 参数解析单测（对抗 SNI 阻断的 ECH 链路入口）。
 */
class VlessEchParseTest {

    private val echLink =
        "vless://ed0b524e-8507-41f8-8223-d08a00b3c325@172.66.138.182:443" +
            "?encryption=none&security=tls&sni=yche.ccwu.cc&fp=chrome" +
            "&ech=cloudflare-ech.com%2Bhttps%3A%2F%2Fdns.alidns.com%2Fdns-query" +
            "&type=ws&host=yche.ccwu.cc&path=%2F#ECH-SG"

    @Test
    fun `ech 参数解析为来源域名与 DoH`() {
        val node = VlessParser().parse(echLink)!!
        assertTrue("echEnabled 应为 true", node.tls.echEnabled)
        assertEquals("cloudflare-ech.com", node.tls.echConfigSource)
        assertEquals("https://dns.alidns.com/dns-query", node.tls.echDohUrl)
        assertEquals("yche.ccwu.cc", node.tls.serverName)
    }

    @Test
    fun `无 ech 参数时保持关闭`() {
        val plain =
            "vless://ed0b524e-8507-41f8-8223-d08a00b3c325@1.2.3.4:443" +
                "?encryption=none&security=tls&sni=example.com&type=ws&path=%2F#plain"
        val node = VlessParser().parse(plain)!!
        assertFalse(node.tls.echEnabled)
        assertEquals("", node.tls.echConfigSource)
    }
}
