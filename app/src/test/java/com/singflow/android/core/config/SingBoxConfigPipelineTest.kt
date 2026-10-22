package com.singflow.android.core.config

import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.RoutingMode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sing-box 1.14+ 配置编译流水线测试：结构断言 + 反废弃字段红线断言。
 */
class SingBoxConfigPipelineTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }
    private val pipeline = SingBoxConfigPipeline(json)

    private val vmessNode = ProxyNode(
        id = "vmess-1",
        name = "VMess WS TLS",
        protocol = ProtocolType.VMESS,
        server = "vmess.example.com",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        alterId = 0,
        securityCipher = "auto",
        transport = TransportConfig(type = "ws", host = "cdn.example.com", path = "/ws"),
        tls = TlsConfig.tls(serverName = "cdn.example.com", alpn = listOf("h2", "http/1.1"))
    )

    private val vlessRealityNode = ProxyNode(
        id = "vless-1",
        name = "VLESS Reality",
        protocol = ProtocolType.VLESS,
        server = "1.1.1.1",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        flow = "xtls-rprx-vision",
        tls = TlsConfig(
            enabled = true,
            serverName = "www.microsoft.com",
            realityEnabled = true,
            realityPublicKey = "mB5_7x6Z8q9w0e1r2t3y4u5i6o7p8a9s0d1f2g3h4j5",
            realityShortId = "abcd1234",
            utlsFingerprint = "chrome"
        )
    )

    private val trojanNode = ProxyNode(
        id = "trojan-1",
        name = "Trojan gRPC",
        protocol = ProtocolType.TROJAN,
        server = "trojan.example.com",
        port = 443,
        password = "password",
        transport = TransportConfig(type = "grpc", serviceName = "SingFlowGRPC"),
        tls = TlsConfig.tls(serverName = "trojan.example.com")
    )

    private val ssNode = ProxyNode(
        id = "ss-1",
        name = "SS 2022",
        protocol = ProtocolType.SHADOWSOCKS,
        server = "9.9.9.9",
        port = 8388,
        method = "2022-blake3-aes-256-gcm",
        password = "AAAA:BBBB:CCCC=",
        securityCipher = "2022-blake3-aes-256-gcm"
    )

    private val hysteria2Node = ProxyNode(
        id = "hy2-1",
        name = "Hysteria2",
        protocol = ProtocolType.HYSTERIA2,
        server = "hy.example.com",
        port = 8443,
        password = "hy-pass",
        upMbps = 100,
        downMbps = 500,
        obfsType = "salamander",
        obfsPassword = "obfs-pass",
        tls = TlsConfig.tls(serverName = "hy.example.com", alpn = listOf("h3"))
    )

    private val wireGuardNode = ProxyNode(
        id = "wg-1",
        name = "WireGuard",
        protocol = ProtocolType.WIREGUARD,
        server = "wg.example.com",
        port = 51820,
        wgPrivateKey = "cHJpdmF0ZUtleQ==",
        wgPeerPublicKey = "cHVibGljS2V5",
        wgLocalAddress = "10.0.0.2/32",
        wgReserved = listOf(1, 2, 3),
        wgMtu = 1408
    )

    @Test
    fun `vmess config contains no deprecated fields`() {
        assertNoDeprecatedFields(vmessNode, AppSettings.DEFAULT)
        val root = pipeline.compile(vmessNode, AppSettings.DEFAULT)
        val outbound = outbounds(root).first().jsonObject
        assertEquals("vmess", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", outbound["tag"]!!.jsonPrimitive.content)
        assertEquals("auto", outbound["security"]!!.jsonPrimitive.content)
        assertEquals(0, outbound["alter_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("ws", outbound["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("cdn.example.com", outbound["transport"]!!.jsonObject["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
        assertTrue(outbound["tls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `vless reality config compiles reality and utls`() {
        assertNoDeprecatedFields(vlessRealityNode, AppSettings.DEFAULT)
        val root = pipeline.compile(vlessRealityNode, AppSettings.DEFAULT)
        val outbound = outbounds(root).first().jsonObject
        assertEquals("vless", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("xtls-rprx-vision", outbound["flow"]!!.jsonPrimitive.content)
        val tls = outbound["tls"]!!.jsonObject
        assertEquals("www.microsoft.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertEquals(true, tls["utls"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("abcd1234", tls["reality"]!!.jsonObject["short_id"]!!.jsonPrimitive.content)
        assertTrue(tls["reality"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        val resolver = outbound["domain_resolver"]!!.jsonObject
        assertEquals("dns-direct", resolver["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan grpc and shadowsocks compile correctly`() {
        assertNoDeprecatedFields(trojanNode, AppSettings.DEFAULT)
        assertNoDeprecatedFields(ssNode, AppSettings.DEFAULT)

        val trojanOutbound = outbounds(pipeline.compile(trojanNode, AppSettings.DEFAULT)).first().jsonObject
        assertEquals("grpc", trojanOutbound["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("SingFlowGRPC", trojanOutbound["transport"]!!.jsonObject["service_name"]!!.jsonPrimitive.content)

        val ssOutbound = outbounds(pipeline.compile(ssNode, AppSettings.DEFAULT)).first().jsonObject
        assertEquals("2022-blake3-aes-256-gcm", ssOutbound["method"]!!.jsonPrimitive.content)
        assertEquals("AAAA:BBBB:CCCC=", ssOutbound["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hysteria2 config compiles obfs and bandwidth`() {
        assertNoDeprecatedFields(hysteria2Node, AppSettings.DEFAULT)
        val outbound = outbounds(pipeline.compile(hysteria2Node, AppSettings.DEFAULT)).first().jsonObject
        assertEquals("hysteria2", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("salamander", outbound["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(100, outbound["up_mbps"]!!.jsonPrimitive.content.toInt())
        assertEquals(500, outbound["down_mbps"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `route rules order and dns server types are correct`() {
        val root = pipeline.compile(vlessRealityNode, AppSettings.DEFAULT)
        val route = root["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray

        assertEquals("sniff", rules[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("300ms", rules[0].jsonObject["timeout"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", rules[1].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("dns", rules[1].jsonObject["protocol"]!!.jsonPrimitive.content)
        assertEquals("direct", rules[2].jsonObject["outbound"]!!.jsonPrimitive.content)
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean)

        val resolver = route["default_domain_resolver"]!!.jsonObject
        assertEquals("dns-direct", resolver["server"]!!.jsonPrimitive.content)
        assertEquals("ipv4_only", resolver["strategy"]!!.jsonPrimitive.content)

        val servers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertEquals("https", servers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[0].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("proxy", servers[0].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("udp", servers[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("local", servers[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("dns-remote", root["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content)

        val inbounds = root["inbounds"]!!.jsonArray
        assertEquals("tun", inbounds[0].jsonObject["type"]!!.jsonPrimitive.content)
        val tun = inbounds[0].jsonObject
        assertEquals("172.19.0.1/30", tun["address"]!!.jsonArray[0].jsonPrimitive.content)
        // Android 无 NAT 权限，必须显式使用 gVisor 栈，否则 TCP 流量无法被接管。
        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
        assertNull(tun["sniff"])
        assertNull(tun["inet4_address"])
        assertNull(tun["endpoint_independent_nat"])
    }

    @Test
    fun `bypass lan cn injects srs rule sets and direct outbound`() {
        val settings = AppSettings.DEFAULT.copy(routingMode = RoutingMode.BYPASS_LAN_CN)
        val root = pipeline.compile(vmessNode, settings)
        val route = root["route"]!!.jsonObject
        val ruleSets = route["rule_set"]!!.jsonArray
        assertEquals(2, ruleSets.size)
        assertTrue(ruleSets[0].jsonObject["url"]!!.jsonPrimitive.content.contains("geosite-cn.srs"))
        assertTrue(ruleSets[1].jsonObject["url"]!!.jsonPrimitive.content.contains("geoip-cn.srs"))
        assertEquals("direct", ruleSets[0].jsonObject["download_detour"]!!.jsonPrimitive.content)

        val cnRule = route["rules"]!!.jsonArray[3].jsonObject
        assertEquals(2, (cnRule["rule_set"] as JsonArray).size)
        assertEquals("direct", cnRule["outbound"]!!.jsonPrimitive.content)

        val outboundList = outbounds(root)
        assertEquals(2, outboundList.size)
        assertEquals("direct", outboundList[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `global mode has no cn rule set`() {
        val settings = AppSettings.DEFAULT.copy(routingMode = RoutingMode.GLOBAL)
        val root = pipeline.compile(vmessNode, settings)
        val route = root["route"]!!.jsonObject
        assertNull(route["rule_set"])
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        val rules = route["rules"]!!.jsonArray
        assertEquals(4, rules.size)
        assertEquals("proxy", rules.last().jsonObject["outbound"]!!.jsonPrimitive.content)
    }

    @Test
    fun `direct domains compile to direct route rule`() {
        val settings = AppSettings.DEFAULT.copy(directDomains = listOf("example.com", "intra.local"))
        val root = pipeline.compile(vmessNode, settings)
        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray
        val domainRule = rules.map { it.jsonObject }.firstOrNull { it["domain_suffix"] != null }
        assertNotNull(domainRule)
        assertEquals("direct", domainRule!!["outbound"]!!.jsonPrimitive.content)
        assertEquals(2, domainRule["domain_suffix"]!!.jsonArray.size)
    }

    @Test
    fun `wireguard uses endpoints and never creates block or dns outbound`() {
        assertNoDeprecatedFields(wireGuardNode, AppSettings.DEFAULT)
        val root = pipeline.compile(wireGuardNode, AppSettings.DEFAULT)
        val endpoints = root["endpoints"]!!.jsonArray
        assertEquals(1, endpoints.size)
        assertEquals("wireguard", endpoints[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("cHJpdmF0ZUtleQ==", endpoints[0].jsonObject["private_key"]!!.jsonPrimitive.content)
        assertEquals(3, (endpoints[0].jsonObject["reserved"] as JsonArray).size)
        val outboundList = outbounds(root)
        assertEquals(1, outboundList.size)
        assertEquals("direct", outboundList[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ipv6 settings extend tun address and resolver strategy`() {
        val settings = AppSettings.DEFAULT.copy(enableIpv6 = true)
        val root = pipeline.compile(vmessNode, settings)
        val tun = root["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals(2, (tun["address"] as JsonArray).size)
        assertEquals("fdfe:dcba:9876::1/126", (tun["address"] as JsonArray)[1].jsonPrimitive.content)
        assertEquals("prefer_ipv4", root["route"]!!.jsonObject["default_domain_resolver"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
        assertEquals("prefer_ipv4", root["dns"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain ip remote dns falls back to udp server`() {
        val settings = AppSettings.DEFAULT.copy(remoteDns = "8.8.8.8", directDns = "1.1.1.1")
        val root = pipeline.compile(vmessNode, settings)
        val servers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertEquals("udp", servers[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("8.8.8.8", servers[0].jsonObject["server"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", servers[1].jsonObject["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `experimental uses store dns and clash api`() {
        val root = pipeline.compile(vmessNode, AppSettings.DEFAULT)
        val experimental = root["experimental"]!!.jsonObject
        val cacheFile = experimental["cache_file"]!!.jsonObject
        assertEquals(true, cacheFile["store_dns"]!!.jsonPrimitive.boolean)
        assertNull(cacheFile["store_rdrc"])
        assertEquals(
            "127.0.0.1:9090",
            experimental["clash_api"]!!.jsonObject["external_controller"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `fake ip adds fakeip server and cache flag`() {
        val settings = AppSettings.DEFAULT.copy(fakeIpEnabled = true)
        val root = pipeline.compile(vmessNode, settings)
        val servers = root["dns"]!!.jsonObject["servers"]!!.jsonArray
        assertEquals("fakeip", servers.last().jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(true, root["experimental"]!!.jsonObject["cache_file"]!!.jsonObject["store_fakeip"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pretty json is parseable and includes all top level sections`() {
        val pretty = pipeline.compilePretty(vmessNode, AppSettings.DEFAULT)
        val root = json.parseToJsonElement(pretty).jsonObject
        listOf("log", "dns", "inbounds", "outbounds", "route", "experimental").forEach { key ->
            assertNotNull("缺少顶层段落 $key", root[key])
        }
    }

    @Test
    fun `custom json outbound is merged and keeps tag`() {
        val custom = ProxyNode(
            id = "custom-1",
            name = "Custom",
            protocol = ProtocolType.CUSTOM_JSON,
            server = "custom.example.com",
            port = 1080,
            rawOutboundJson = """{"type":"socks","server":"custom.example.com","server_port":1080}"""
        )
        val outbound = outbounds(pipeline.compile(custom, AppSettings.DEFAULT)).first().jsonObject
        assertEquals("socks", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("proxy", outbound["tag"]!!.jsonPrimitive.content)
        assertNotNull(outbound["domain_resolver"])
    }

    private fun outbounds(root: JsonObject): JsonArray = root["outbounds"]!!.jsonArray

    private fun assertNoDeprecatedFields(node: ProxyNode, settings: AppSettings) {
        val compact = pipeline.compileCompact(node, settings)
        listOf(
            "\"inet4_address\"",
            "\"store_rdrc\"",
            "\"domain_strategy\"",
            "\"type\":\"block\"",
            "\"type\":\"dns\"",
            "\"outbound\":\"any\""
        ).forEach { forbidden ->
            assertFalse("配置中出现废弃字段 $forbidden: $compact", compact.contains(forbidden))
        }
    }
}
