package com.singflow.android.core.parser

import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.parser.impl.Hysteria2Parser
import com.singflow.android.core.parser.impl.ShadowsocksParser
import com.singflow.android.core.parser.impl.TrojanParser
import com.singflow.android.core.parser.impl.TuicParser
import com.singflow.android.core.parser.impl.VlessParser
import com.singflow.android.core.parser.impl.VmessParser
import com.singflow.android.core.parser.impl.WireGuardParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全协议解析器单元测试（纯 JVM，无 Android 依赖）。
 */
class UniversalParserTest {

    private val vmess = VmessParser()
    private val vless = VlessParser()
    private val trojan = TrojanParser()
    private val shadowsocks = ShadowsocksParser()
    private val hysteria2 = Hysteria2Parser()
    private val tuic = TuicParser()
    private val wireGuard = WireGuardParser()

    /** 断言解析成功并返回非空节点。 */
    private fun ProxyNode?.orFail(): ProxyNode = requireNotNull(this) { "解析结果为空" }

    @Test
    fun `vmess base64 json supports string port and ws tls`() {
        val payload = """
            {"v":"2","ps":"测试节点 🚀","add":"example.com","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","scy":"auto","net":"ws","host":"cdn.example.com","path":"/singflow","tls":"tls","sni":"cdn.example.com","alpn":"h2,http/1.1","fp":"chrome"}
        """.trimIndent()
        val node = vmess.parse("vmess://" + Base64Utils.encode(payload)).orFail()

        assertEquals(ProtocolType.VMESS, node.protocol)
        assertEquals("example.com", node.server)
        assertEquals(443, node.port)
        assertEquals("11111111-2222-3333-4444-555555555555", node.uuid)
        assertEquals("ws", node.transport.type)
        assertEquals("/singflow", node.transport.path)
        assertEquals("cdn.example.com", node.transport.host)
        assertTrue(node.tls.enabled)
        assertEquals("cdn.example.com", node.tls.serverName)
        assertEquals(listOf("h2", "http/1.1"), node.tls.alpn)
        assertTrue(node.name.contains("🚀"))
    }

    @Test
    fun `vmess base64 json supports integer port and grpc service name`() {
        val payload = """
            {"ps":"整数端口","add":"8.8.8.8","port":8443,"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":0,"net":"grpc","path":"SingFlowGRPC","tls":"tls"}
        """.trimIndent()
        val node = vmess.parse("vmess://" + Base64Utils.encode(payload)).orFail()

        assertEquals(8443, node.port)
        assertEquals("grpc", node.transport.type)
        assertEquals("SingFlowGRPC", node.transport.serviceName)
        assertEquals(0, node.alterId)
    }

    @Test
    fun `vmess standard uri format is parsed`() {
        val link = "vmess://11111111-2222-3333-4444-555555555555@1.2.3.4:443?type=ws&security=tls&host=cdn.example.com&path=%2Fws&sni=cdn.example.com&fp=chrome#URI%E8%8A%82%E7%82%B9"
        val node = vmess.parse(link).orFail()

        assertEquals("1.2.3.4", node.server)
        assertEquals(443, node.port)
        assertEquals("ws", node.transport.type)
        assertEquals("/ws", node.transport.path)
        assertEquals("URI节点", node.name)
    }

    @Test
    fun `vless reality with vision supports ipv6 and emoji remark`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@[2606:4700::1]:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com" +
            "&fp=chrome&pbk=mB5_7x6Z8q9w0e1r2t3y4u5i6o7p8a9s0d1f2g3h4j5&sid=abcd1234" +
            "&spx=%2F&type=tcp#REALITY%20%E4%B8%AD%E6%96%87%20%F0%9F%9A%80"
        val node = vless.parse(link).orFail()

        assertEquals(ProtocolType.VLESS, node.protocol)
        assertEquals("2606:4700::1", node.server)
        assertEquals(443, node.port)
        assertEquals("xtls-rprx-vision", node.flow)
        assertTrue(node.tls.enabled)
        assertTrue(node.tls.realityEnabled)
        assertEquals("www.microsoft.com", node.tls.serverName)
        assertEquals("mB5_7x6Z8q9w0e1r2t3y4u5i6o7p8a9s0d1f2g3h4j5", node.tls.realityPublicKey)
        assertEquals("abcd1234", node.tls.realityShortId)
        assertEquals("chrome", node.tls.utlsFingerprint)
        assertTrue(node.name.contains("中文"))
        assertTrue(node.name.contains("🚀"))
    }

    @Test
    fun `trojan grpc transport is parsed`() {
        val link = "trojan://my-password@trojan.example.com:443?type=grpc&serviceName=SingFlowGRPC&sni=trojan.example.com&allowInsecure=1#Trojan%20gRPC"
        val node = trojan.parse(link).orFail()

        assertEquals(ProtocolType.TROJAN, node.protocol)
        assertEquals("my-password", node.password)
        assertEquals("grpc", node.transport.type)
        assertEquals("SingFlowGRPC", node.transport.serviceName)
        assertTrue(node.tls.enabled)
        assertTrue(node.tls.insecure)
        assertEquals("Trojan gRPC", node.name)
    }

    @Test
    fun `shadowsocks sip002 userinfo base64 is parsed`() {
        val userInfo = Base64Utils.encode("aes-256-gcm:pass:word")
        val node = shadowsocks.parse("ss://$userInfo@1.2.3.4:8388?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#SS-SIP002").orFail()

        assertEquals(ProtocolType.SHADOWSOCKS, node.protocol)
        assertEquals("aes-256-gcm", node.method)
        assertEquals("pass:word", node.password)
        assertEquals(8388, node.port)
        assertEquals("obfs-local", node.plugin)
        assertTrue(node.pluginOpts.contains("obfs=http"))
    }

    @Test
    fun `shadowsocks legacy full base64 is parsed`() {
        val legacy = Base64Utils.encode("chacha20-ietf-poly1305:legacy-pass@5.6.7.8:8389")
        val node = shadowsocks.parse("ss://$legacy#Legacy%E8%8A%82%E7%82%B9").orFail()

        assertEquals("chacha20-ietf-poly1305", node.method)
        assertEquals("legacy-pass", node.password)
        assertEquals("5.6.7.8", node.server)
        assertEquals(8389, node.port)
        assertEquals("Legacy节点", node.name)
    }

    @Test
    fun `shadowsocks 2022 key containing colon keeps full password`() {
        val credentials = Base64Utils.encode("2022-blake3-aes-256-gcm:AAAA:BBBB:CCCC=")
        val node = shadowsocks.parse("ss://$credentials@9.9.9.9:8388#SS2022").orFail()

        assertEquals("2022-blake3-aes-256-gcm", node.method)
        assertEquals("AAAA:BBBB:CCCC=", node.password)
    }

    @Test
    fun `plain shadowsocks uri is parsed`() {
        val node = shadowsocks.parse("ss://aes-128-gcm:plain-pass@1.1.1.1:8388#Plain").orFail()

        assertEquals("aes-128-gcm", node.method)
        assertEquals("plain-pass", node.password)
    }

    @Test
    fun `hysteria2 and hy2 aliases are parsed`() {
        val link = "hy2://hy2-password@1.1.1.1:8443?sni=hy2.example.com&insecure=1&obfs=salamander&obfs-password=obfs-pass&upmbps=100&downmbps=500#HY2"
        val node = hysteria2.parse(link).orFail()

        assertEquals(ProtocolType.HYSTERIA2, node.protocol)
        assertEquals("hy2-password", node.password)
        assertEquals("salamander", node.obfsType)
        assertEquals("obfs-pass", node.obfsPassword)
        assertEquals(100, node.upMbps)
        assertEquals(500, node.downMbps)
        assertTrue(node.tls.enabled)
        assertTrue(node.tls.insecure)
        assertEquals(listOf("h3"), node.tls.alpn)
    }

    @Test
    fun `tuic uuid password and congestion control are parsed`() {
        val link = "tuic://11111111-2222-3333-4444-555555555555:tuic-pass@9.9.9.9:443?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=tuic.example.com&allow_insecure=0#TUIC"
        val node = tuic.parse(link).orFail()

        assertEquals(ProtocolType.TUIC, node.protocol)
        assertEquals("11111111-2222-3333-4444-555555555555", node.uuid)
        assertEquals("tuic-pass", node.password)
        assertEquals("bbr", node.congestionControl)
        assertEquals("native", node.udpRelayMode)
        assertEquals("tuic.example.com", node.tls.serverName)
        assertFalse(node.tls.insecure)
    }

    @Test
    fun `wireguard link is parsed`() {
        val link = "wireguard://cHJpdmF0ZUtleUJhc2U2NA==@1.2.3.4:51820?publicKey=cHVibGljS2V5&address=10.0.0.2%2F32&mtu=1408&reserved=1,2,3#WG"
        val node = wireGuard.parse(link).orFail()

        assertEquals(ProtocolType.WIREGUARD, node.protocol)
        assertEquals("1.2.3.4", node.server)
        assertEquals(51820, node.port)
        assertEquals("cHJpdmF0ZUtleUJhc2U2NA==", node.wgPrivateKey)
        assertEquals("cHVibGljS2V5", node.wgPeerPublicKey)
        assertEquals("10.0.0.2/32", node.wgLocalAddress)
        assertEquals(1408, node.wgMtu)
        assertEquals(listOf(1, 2, 3), node.wgReserved)
    }

    @Test
    fun `base64 subscription containing five protocols imports all`() {
        val vmessJson = """
            {"ps":"订阅VMess","add":"vmess.example.com","port":"443","id":"11111111-2222-3333-4444-555555555555","aid":"0","net":"ws","path":"/ws","tls":"tls"}
        """.trimIndent()
        val lines = listOf(
            "vmess://" + Base64Utils.encode(vmessJson),
            "vless://11111111-2222-3333-4444-555555555555@vless.example.com:443?security=tls&type=ws&path=%2Fvl#%E8%AE%A2%E9%98%85VLESS",
            "trojan://pass@trojan.example.com:443?type=grpc&serviceName=svc#%E8%AE%A2%E9%98%85Trojan",
            "ss://" + Base64Utils.encode("aes-256-gcm:ss-pass") + "@ss.example.com:8388#%E8%AE%A2%E9%98%85SS",
            "hy2://hy-pass@hy.example.com:8443?sni=hy.example.com&obfs=salamander&obfs-password=obfs#%E8%AE%A2%E9%98%85HY2"
        )
        val subscription = Base64Utils.encode(lines.joinToString("\n"))
        val manager = UniversalImportManager()
        val result = manager.parseText(subscription)

        assertEquals("BASE64_SUBSCRIPTION", result.sourceType)
        assertEquals(5, result.nodes.size)
        assertEquals(
            setOf(
                ProtocolType.VMESS,
                ProtocolType.VLESS,
                ProtocolType.TROJAN,
                ProtocolType.SHADOWSOCKS,
                ProtocolType.HYSTERIA2
            ),
            result.nodes.map { it.protocol }.toSet()
        )
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `sing box json outbounds are imported`() {
        val raw = """
            {
              "outbounds": [
                {"type":"vless","tag":"JSON-VLESS","server":"json.example.com","server_port":443,"uuid":"11111111-2222-3333-4444-555555555555","flow":"xtls-rprx-vision",
                 "tls":{"enabled":true,"server_name":"json.example.com","utls":{"enabled":true,"fingerprint":"chrome"}},
                 "transport":{"type":"ws","path":"/json","headers":{"Host":"cdn.example.com"}}},
                {"type":"direct","tag":"direct"},
                {"type":"selector","tag":"select","outbounds":["JSON-VLESS"]}
              ]
            }
        """.trimIndent()
        val result = UniversalImportManager().parseText(raw)

        assertEquals("SING_BOX_JSON", result.sourceType)
        assertEquals(1, result.nodes.size)
        val node = result.nodes.first()
        assertEquals("JSON-VLESS", node.name)
        assertEquals(ProtocolType.VLESS, node.protocol)
        assertEquals(443, node.port)
        assertTrue(node.tls.enabled)
        assertEquals("ws", node.transport.type)
        assertEquals("/json", node.transport.path)
    }

    @Test
    fun `multi line text imports and deduplicates identical nodes`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@1.1.1.1:443?security=tls#A"
        val result = UniversalImportManager().parseText("$link\n$link")

        assertEquals(1, result.nodes.size)
    }

    @Test
    fun `invalid input returns null instead of throwing`() {
        assertNull(vless.parse("vless://@:443"))
        assertNull(trojan.parse("trojan://"))
        assertNull(hysteria2.parse("https://example.com"))
        assertNull(tuic.parse("tuic://@1.1.1.1:443"))
    }

    @Test
    fun `base64 utility tolerates url safe and missing padding`() {
        val raw = "SingFlow-测试"
        val urlSafe = Base64Utils.encodeUrlSafe(raw)
        assertEquals(raw, Base64Utils.safeBase64Decode(urlSafe))
        assertTrue(Base64Utils.looksLikeBase64(urlSafe))
        assertFalse(Base64Utils.looksLikeBase64("vless://abc@1.1.1.1:443"))
    }
}
