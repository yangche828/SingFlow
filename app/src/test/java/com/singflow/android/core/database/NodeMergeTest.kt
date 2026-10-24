package com.singflow.android.core.database

import com.singflow.android.core.model.TlsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 订阅刷新节点合并策略测试。
 */
class NodeMergeTest {

    private fun entity(
        id: String,
        name: String,
        server: String,
        countryCode: String,
        countryName: String,
        verified: Boolean,
        favorite: Boolean = false,
        shareUri: String = ""
    ) = NodeEntity(
        id = id,
        configHash = "hash-1",
        groupId = "sub-1",
        name = name,
        protocol = "VLESS",
        server = server,
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        alterId = 0,
        securityCipher = "auto",
        flow = "",
        password = "",
        method = "",
        plugin = "",
        pluginOpts = "",
        upMbps = 0,
        downMbps = 0,
        obfsType = "",
        obfsPassword = "",
        congestionControl = "",
        udpRelayMode = "",
        wgPrivateKey = "",
        wgPeerPublicKey = "",
        wgPreSharedKey = "",
        wgLocalAddress = "",
        wgReserved = "",
        wgMtu = 0,
        rawOutboundJson = "{}",
        transportJson = "{}",
        tlsJson = "{}",
        latencyMs = 120,
        createdAt = 1L,
        rawShareUri = shareUri,
        countryCode = countryCode,
        countryNameCn = countryName,
        isRegionVerified = verified,
        probeTotalCount = 10,
        probeSuccessCount = 9,
        healthScore = 88,
        isFavorite = favorite
    )

    @Test
    fun `refresh keeps favorites health stats and verified country`() {
        val existing = entity(
            id = "node-1",
            name = "旧名称",
            server = "old.example.com",
            countryCode = "JP",
            countryName = "日本",
            verified = true,
            favorite = true,
            shareUri = "vless://old"
        )
        val incoming = entity(
            id = "node-1",
            name = "新名称",
            server = "new.example.com",
            countryCode = "US",
            countryName = "美国",
            verified = false,
            favorite = false,
            shareUri = ""
        )

        val merged = NodeMerge.preserve(existing, incoming)

        assertEquals("新名称", merged.name)
        assertEquals("new.example.com", merged.server)
        assertTrue(merged.isFavorite)
        assertEquals(88, merged.healthScore)
        assertEquals(10, merged.probeTotalCount)
        assertEquals(9, merged.probeSuccessCount)
        assertEquals("JP", merged.countryCode)
        assertEquals("日本", merged.countryNameCn)
        assertEquals("vless://old", merged.rawShareUri)
    }

    @Test
    fun `refresh updates country when not verified`() {
        val existing = entity(
            id = "node-2",
            name = "节点",
            server = "old.example.com",
            countryCode = "JP",
            countryName = "日本",
            verified = false
        )
        val incoming = entity(
            id = "node-2",
            name = "节点",
            server = "new.example.com",
            countryCode = "US",
            countryName = "美国",
            verified = false
        )

        val merged = NodeMerge.preserve(existing, incoming)

        assertEquals("US", merged.countryCode)
        assertEquals("美国", merged.countryNameCn)
    }

    @Test
    fun `refresh keeps existing ech config when this round failed`() {
        // 订阅刷新时 DoH 偶发失败 → 本次 PEM 为空；不能让空值覆盖上一次成功结果。
        val existing = TlsConfig(
            enabled = true,
            serverName = "ech.example.com",
            echEnabled = true,
            echConfigSource = "cloudflare-ech.com",
            echDohUrl = "https://dns.alidns.com/dns-query",
            echConfigPem = "-----BEGIN ECH CONFIGS-----\nAA==\n-----END ECH CONFIGS-----"
        )
        val incoming = TlsConfig(
            enabled = true,
            serverName = "ech.example.com",
            echEnabled = true,
            echConfigSource = "cloudflare-ech.com"
        )

        val merged = NodeMerge.preserveEch(existing, incoming)

        assertEquals(existing.echConfigPem, merged.echConfigPem)
        assertEquals("https://dns.alidns.com/dns-query", merged.echDohUrl)
    }

    @Test
    fun `refresh prefers fresh ech config and respects removal`() {
        val existing = TlsConfig(
            enabled = true,
            echEnabled = true,
            echConfigSource = "cloudflare-ech.com",
            echConfigPem = "-----BEGIN ECH CONFIGS-----\nOLD\n-----END ECH CONFIGS-----"
        )
        val fresh = existing.copy(echConfigPem = "-----BEGIN ECH CONFIGS-----\nNEW\n-----END ECH CONFIGS-----")
        assertEquals(fresh.echConfigPem, NodeMerge.preserveEch(existing, fresh).echConfigPem)

        // 链接已不带 ech 参数：尊重新配置，不复活旧 PEM。
        val removed = TlsConfig(enabled = true, serverName = "plain.example.com")
        assertEquals("", NodeMerge.preserveEch(existing, removed).echConfigPem)
    }
}
