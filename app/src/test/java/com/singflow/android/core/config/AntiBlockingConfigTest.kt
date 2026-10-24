package com.singflow.android.core.config

import com.singflow.android.core.config.interceptors.ProxyOutboundFactory
import com.singflow.android.core.model.AntiBlockingConfig
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抗封锁配置生成测试。
 */
class AntiBlockingConfigTest {

    private val resolver = buildJsonObject { put("server", "dns-direct") }

    private fun vlessNode() = ProxyNode(
        id = "n1",
        name = "anti",
        protocol = ProtocolType.VLESS,
        server = "example.com",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        tls = TlsConfig(enabled = true, serverName = "example.com")
    )

    @Test
    fun `emits tls fragment utls and multiplex`() {
        val anti = AntiBlockingConfig(
            tlsFragment = true,
            utlsFingerprint = "chrome",
            multiplexEnabled = true,
            multiplexProtocol = "smux",
            multiplexPadding = true,
            multiplexMaxStreams = 16
        )
        val outbound = ProxyOutboundFactory.buildProxyOutbound(vlessNode(), "node-n1", resolver, anti)
        val tls = outbound["tls"]!!.jsonObject
        assertTrue(tls["fragment"]!!.jsonPrimitive.boolean)
        assertTrue(tls["record_fragment"]!!.jsonPrimitive.boolean)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)

        val mux = outbound["multiplex"]!!.jsonObject
        assertTrue(mux["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("smux", mux["protocol"]!!.jsonPrimitive.content)
        assertTrue(mux["padding"]!!.jsonPrimitive.boolean)
        assertEquals("16", mux["max_streams"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default anti blocking emits no fragment or multiplex`() {
        val outbound = ProxyOutboundFactory.buildProxyOutbound(vlessNode(), "node-n1", resolver)
        val tls = outbound["tls"]!!.jsonObject
        assertFalse(tls.containsKey("fragment"))
        // 节点自身默认 chrome 指纹仍应保留；全局配置为空时不覆盖。
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        assertFalse(outbound.containsKey("multiplex"))
    }

    @Test
    fun `multiplex is skipped for quic protocols`() {
        val node = ProxyNode(
            id = "h1",
            protocol = ProtocolType.HYSTERIA2,
            server = "example.com",
            port = 443,
            password = "pwd",
            tls = TlsConfig(enabled = true)
        )
        val outbound = ProxyOutboundFactory.buildProxyOutbound(
            node,
            "node-h1",
            resolver,
            AntiBlockingConfig(multiplexEnabled = true)
        )
        assertFalse(outbound.containsKey("multiplex"))
    }

    @Test
    fun `multiplex is skipped for websocket transport`() {
        // WS 本身已在单条连接上复用；叠加 sing-box 私有 h2mux 会被
        // Cloudflare Worker / Xray 服务端直接关闭（http2 ClientConn.Close）。
        val node = ProxyNode(
            id = "w1",
            protocol = ProtocolType.VLESS,
            server = "ws.example.com",
            port = 443,
            uuid = "11111111-1111-1111-1111-111111111111",
            tls = TlsConfig(enabled = true, serverName = "ws.example.com"),
            transport = TransportConfig(type = "ws", host = "ws.example.com", path = "/")
        )
        val outbound = ProxyOutboundFactory.buildProxyOutbound(
            node,
            "node-w1",
            resolver,
            AntiBlockingConfig(multiplexEnabled = true, multiplexProtocol = "h2mux")
        )
        assertFalse(outbound.containsKey("multiplex"))
    }

    @Test
    fun `ech skips tls fragment to keep client hello intact`() {
        val node = vlessNode().copy(
            tls = TlsConfig(
                enabled = true,
                serverName = "ech.example.com",
                echEnabled = true,
                echConfigPem = "-----BEGIN ECH CONFIGS-----\nAA==\n-----END ECH CONFIGS-----"
            )
        )
        val outbound = ProxyOutboundFactory.buildProxyOutbound(
            node,
            "node-e1",
            resolver,
            AntiBlockingConfig(tlsFragment = true)
        )
        val tls = outbound["tls"]!!.jsonObject
        assertFalse(tls.containsKey("fragment"))
        assertTrue(tls["ech"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }
}
