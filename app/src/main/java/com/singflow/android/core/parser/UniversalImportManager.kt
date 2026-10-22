package com.singflow.android.core.parser

import com.singflow.android.core.database.NodeRepository
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig
import com.singflow.android.core.parser.impl.Hysteria2Parser
import com.singflow.android.core.parser.impl.ShadowsocksParser
import com.singflow.android.core.parser.impl.TrojanParser
import com.singflow.android.core.parser.impl.TuicParser
import com.singflow.android.core.parser.impl.VlessParser
import com.singflow.android.core.parser.impl.VmessParser
import com.singflow.android.core.parser.impl.WireGuardParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * 一次导入的结果。
 *
 * @property nodes 成功解析的节点（已去重）
 * @property errors 解析失败的行与原因
 * @property sourceType 识别出的来源类型：TEXT / BASE64_SUBSCRIPTION / SING_BOX_JSON
 */
data class ImportResult(
    val nodes: List<ProxyNode> = emptyList(),
    val errors: List<String> = emptyList(),
    val sourceType: String = "TEXT"
) {
    val success: Boolean get() = nodes.isNotEmpty()
    val isEmpty: Boolean get() = nodes.isEmpty()
}

/**
 * 通用导入管理器（职责链 + 订阅/JSON 自适应）。
 *
 * 支持：
 * 1. 单条链接；
 * 2. 多行混合链接；
 * 3. 整段 Base64 编码的机场订阅；
 * 4. 原生 sing-box JSON 配置中的 outbounds / endpoints。
 *
 * @param json JSON 编解码器
 * @param nodeRepository 可选节点仓库；提供时 [importFromText] 会直接入库
 * @param parsers 解析器链
 */
class UniversalImportManager(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val nodeRepository: NodeRepository? = null,
    private val parsers: List<NodeLinkParser> = defaultParsers()
) {

    /**
     * 只解析不入库。
     */
    fun parseText(text: String, groupId: String = "manual"): ImportResult {
        val trimmed = text.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return ImportResult(errors = listOf("输入内容为空"))

        // 1) sing-box JSON
        if (looksLikeSingBoxJson(trimmed)) {
            val nodes = parseSingBoxConfig(trimmed, groupId)
            return if (nodes.isEmpty()) {
                ImportResult(errors = listOf("sing-box JSON 中未找到可导入的 outbound"), sourceType = "SING_BOX_JSON")
            } else {
                ImportResult(nodes = nodes, sourceType = "SING_BOX_JSON")
            }
        }

        // 2) 整段 Base64 订阅
        if (!trimmed.contains("://") && Base64Utils.looksLikeBase64(trimmed)) {
            val decoded = Base64Utils.safeBase64DecodeOrNull(trimmed)
            if (decoded != null && decoded.contains("://")) {
                val inner = parseLines(decoded, groupId)
                return inner.copy(
                    nodes = inner.nodes.distinctBy { it.dedupKey() },
                    sourceType = "BASE64_SUBSCRIPTION"
                )
            }
        }

        // 3) 逐行解析
        val result = parseLines(trimmed, groupId)
        return result.copy(nodes = result.nodes.distinctBy { it.configHash })
    }

    /**
     * 解析并入库。
     *
     * @return 入库结果（节点已去重；无仓库时仅返回解析结果）
     */
    suspend fun importFromText(text: String, groupId: String = "manual"): ImportResult {
        val result = parseText(text, groupId)
        if (result.nodes.isEmpty() || nodeRepository == null) return result

        val existing = nodeRepository.getAll().map { it.configHash }.toMutableSet()
        val toSave = result.nodes.filter { existing.add(it.configHash) }
        if (toSave.isNotEmpty()) {
            nodeRepository.saveAllPreservingStats(toSave)
        }
        return result.copy(nodes = toSave)
    }

    /**
     * 逐行解析文本，跳过无法识别的行。
     */
    private fun parseLines(text: String, groupId: String): ImportResult {
        val nodes = mutableListOf<ProxyNode>()
        val errors = mutableListOf<String>()
        val lines = text.split('\n', '\r')
        for (line in lines) {
            val candidate = line.trim()
            if (candidate.isEmpty()) continue
            if (candidate.startsWith("#") || candidate.startsWith("//")) continue
            val parser = parsers.firstOrNull { it.canParse(candidate) }
            if (parser == null) {
                if (candidate.contains("://")) {
                    errors += "不支持的链接: ${candidate.take(48)}"
                }
                continue
            }
            val node = runCatching { parser.parse(candidate) }.getOrNull()
            if (node == null) {
                errors += "${parser.name} 解析失败: ${candidate.take(48)}"
            } else {
                val withGroup = node.copy(groupId = groupId)
                nodes += NodeParserUtils.normalize(withGroup, rawShareUri = candidate)
            }
        }
        return ImportResult(nodes = nodes, errors = errors)
    }

    /**
     * 解析 sing-box JSON 的 outbounds / endpoints。
     */
    fun parseSingBoxConfig(raw: String, groupId: String = "manual"): List<ProxyNode> {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        val nodes = mutableListOf<ProxyNode>()

        (root["outbounds"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val type = obj.stringOrEmpty("type").lowercase(Locale.US)
            if (type in IGNORED_TYPES) return@forEach
            parseOutbound(obj, groupId)?.let { nodes += NodeParserUtils.normalize(it) }
        }
        (root["endpoints"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            if (obj.stringOrEmpty("type") != "wireguard") return@forEach
            parseOutbound(obj, groupId)?.let { nodes += NodeParserUtils.normalize(it) }
        }
        return nodes.distinctBy { it.configHash }
    }

    /**
     * 单个 sing-box outbound 转 ProxyNode。
     */
    private fun parseOutbound(obj: JsonObject, groupId: String): ProxyNode? {
        val protocol = ProtocolType.fromRaw(obj.stringOrEmpty("type")) ?: return null
        val server = obj.stringOrEmpty("server")
        val port = obj.intOrDefault("server_port", 0)
        if (server.isBlank() && protocol != ProtocolType.WIREGUARD) return null
        if (port !in 1..65535 && protocol != ProtocolType.WIREGUARD) return null

        val tlsObj = obj["tls"] as? JsonObject
        val transportObj = obj["transport"] as? JsonObject
        val tls = parseTls(tlsObj)
        val transport = parseTransport(transportObj)

        return ProxyNode(
            groupId = groupId,
            name = obj.stringOrEmpty("tag").ifBlank { "$server:$port" },
            protocol = protocol,
            server = server,
            port = if (port in 1..65535) port else 1,
            uuid = obj.stringOrEmpty("uuid"),
            alterId = obj.intOrDefault("alter_id", 0),
            securityCipher = obj.stringOrEmpty("security").ifBlank { "auto" },
            flow = obj.stringOrEmpty("flow"),
            password = obj.stringOrEmpty("password"),
            method = obj.stringOrEmpty("method"),
            plugin = obj.stringOrEmpty("plugin"),
            pluginOpts = obj.stringOrEmpty("plugin_opts"),
            upMbps = obj.intOrDefault("up_mbps", 0),
            downMbps = obj.intOrDefault("down_mbps", 0),
            obfsType = ((obj["obfs"] as? JsonObject)?.stringOrEmpty("type")).orEmpty(),
            obfsPassword = ((obj["obfs"] as? JsonObject)?.stringOrEmpty("password")).orEmpty(),
            congestionControl = obj.stringOrEmpty("congestion_control").ifBlank { "bbr" },
            udpRelayMode = obj.stringOrEmpty("udp_relay_mode").ifBlank { "native" },
            wgPrivateKey = obj.stringOrEmpty("private_key"),
            wgPeerPublicKey = obj.stringOrEmpty("peer_public_key"),
            wgPreSharedKey = obj.stringOrEmpty("pre_shared_key"),
            wgLocalAddress = (obj["address"] as? JsonArray)?.firstOrNull()?.let { it as? JsonPrimitive }?.content
                ?: "172.16.0.2/32",
            wgReserved = (obj["reserved"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                ?: emptyList(),
            wgMtu = obj.intOrDefault("mtu", 1420),
            rawOutboundJson = if (protocol == ProtocolType.CUSTOM_JSON) obj.toString() else "",
            transport = transport,
            tls = tls
        )
    }

    private fun parseTls(obj: JsonObject?): TlsConfig {
        if (obj == null) return TlsConfig.DEFAULT
        val reality = obj["reality"] as? JsonObject
        val utls = obj["utls"] as? JsonObject
        return TlsConfig(
            enabled = obj.booleanOrDefault("enabled", true),
            serverName = obj.stringOrEmpty("server_name"),
            insecure = obj.booleanOrDefault("insecure", false),
            alpn = obj.stringList("alpn"),
            utlsFingerprint = utls?.stringOrEmpty("fingerprint")?.ifBlank { "chrome" } ?: "chrome",
            realityEnabled = reality?.booleanOrDefault("enabled", false) ?: false,
            realityPublicKey = reality?.stringOrEmpty("public_key").orEmpty(),
            realityShortId = reality?.stringOrEmpty("short_id").orEmpty()
        )
    }

    private fun parseTransport(obj: JsonObject?): TransportConfig {
        if (obj == null) return TransportConfig.DEFAULT
        val type = obj.stringOrEmpty("type").ifBlank { "tcp" }
        val headersObj = obj["headers"] as? JsonObject
        val headers = headersObj?.entries?.associate { (key, value) ->
            key to ((value as? JsonPrimitive)?.content ?: "")
        } ?: emptyMap()
        return TransportConfig(
            type = type,
            host = obj.stringOrEmpty("host").ifBlank { headers["Host"].orEmpty() },
            path = obj.stringOrEmpty("path"),
            headers = headers,
            serviceName = obj.stringOrEmpty("service_name"),
            maxEarlyData = obj.intOrDefault("max_early_data", 0),
            earlyDataHeaderName = obj.stringOrEmpty("early_data_header_name")
        )
    }

    private fun looksLikeSingBoxJson(text: String): Boolean {
        if (!text.startsWith("{")) return false
        return text.contains("\"outbounds\"") || text.contains("\"endpoints\"")
    }

    companion object {
        private val IGNORED_TYPES = setOf(
            "direct", "block", "dns", "selector", "urltest", "socks", "http", "custom"
        )

        /**
         * 内置解析器链。新增协议时在此注册即可。
         */
        fun defaultParsers(): List<NodeLinkParser> = listOf(
            VmessParser(),
            VlessParser(),
            TrojanParser(),
            ShadowsocksParser(),
            Hysteria2Parser(),
            TuicParser(),
            WireGuardParser()
        )

        /**
         * 生成 5 个全协议示例节点，便于首次安装即可体验测速与配置编译。
         */
        fun sampleNodes(): List<ProxyNode> {
            val now = System.currentTimeMillis()
            return listOf(
                ProxyNode(
                    id = "sample-vless-reality",
                    groupId = "sample",
                    name = "示例 · VLESS Reality",
                    protocol = ProtocolType.VLESS,
                    server = "1.1.1.1",
                    port = 443,
                    uuid = "00000000-0000-0000-0000-000000000001",
                    flow = "xtls-rprx-vision",
                    transport = TransportConfig(type = "tcp"),
                    tls = TlsConfig(
                        enabled = true,
                        serverName = "www.microsoft.com",
                        alpn = listOf("h2", "http/1.1"),
                        utlsFingerprint = "chrome",
                        realityEnabled = true,
                        realityPublicKey = "mB5_7x6Z8q9w0e1r2t3y4u5i6o7p8a9s0d1f2g3h4j5",
                        realityShortId = "abcd1234"
                    ),
                    createdAt = now
                ),
                ProxyNode(
                    id = "sample-vmess-ws-tls",
                    groupId = "sample",
                    name = "示例 · VMess WS TLS",
                    protocol = ProtocolType.VMESS,
                    server = "8.8.8.8",
                    port = 443,
                    uuid = "00000000-0000-0000-0000-000000000002",
                    alterId = 0,
                    securityCipher = "auto",
                    transport = TransportConfig(type = "ws", host = "cdn.example.com", path = "/singflow"),
                    tls = TlsConfig.tls(serverName = "cdn.example.com", alpn = listOf("h2", "http/1.1")),
                    createdAt = now + 1
                ),
                ProxyNode(
                    id = "sample-trojan-grpc",
                    groupId = "sample",
                    name = "示例 · Trojan gRPC",
                    protocol = ProtocolType.TROJAN,
                    server = "1.0.0.1",
                    port = 443,
                    password = "singflow-demo-password",
                    transport = TransportConfig(type = "grpc", serviceName = "SingFlowGRPC"),
                    tls = TlsConfig.tls(serverName = "trojan.example.com"),
                    createdAt = now + 2
                ),
                ProxyNode(
                    id = "sample-ss-2022",
                    groupId = "sample",
                    name = "示例 · Shadowsocks 2022",
                    protocol = ProtocolType.SHADOWSOCKS,
                    server = "9.9.9.9",
                    port = 8388,
                    method = "2022-blake3-aes-256-gcm",
                    password = "singflow:demo:key:2022",
                    securityCipher = "2022-blake3-aes-256-gcm",
                    createdAt = now + 3
                ),
                ProxyNode(
                    id = "sample-hysteria2",
                    groupId = "sample",
                    name = "示例 · Hysteria2",
                    protocol = ProtocolType.HYSTERIA2,
                    server = "1.1.1.1",
                    port = 8443,
                    password = "singflow-demo-password",
                    upMbps = 100,
                    downMbps = 500,
                    obfsType = "salamander",
                    obfsPassword = "singflow-obfs",
                    tls = TlsConfig.tls(serverName = "hy2.example.com", alpn = listOf("h3")),
                    createdAt = now + 4
                )
            ).map { NodeParserUtils.normalize(it) }
        }
    }
}
