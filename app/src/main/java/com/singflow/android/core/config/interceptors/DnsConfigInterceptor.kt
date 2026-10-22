package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import com.singflow.android.core.model.RoutingMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

/**
 * DNS 拦截器（红线 1 / 红线 2）。
 *
 * 生成强类型 `dns.servers`，禁止 `address` 字符串写法。
 */
class DnsConfigInterceptor : ConfigInterceptor {

    override val name: String = "DnsConfigInterceptor"

    override fun intercept(context: SingBoxConfigContext) {
        val settings = context.settings
        val strategy = if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only"

        val servers = buildJsonArray {
            add(buildRemoteServer(settings.remoteDns))
            add(
                buildJsonObject {
                    put("type", "udp")
                    put("tag", "dns-direct")
                    val parsed = parsePlainDns(settings.directDns, 53)
                    put("server", parsed.first)
                    put("server_port", parsed.second)
                }
            )
            add(
                buildJsonObject {
                    put("type", "local")
                    put("tag", "dns-local")
                }
            )
            if (settings.fakeIpEnabled) {
                add(
                    buildJsonObject {
                        put("type", "fakeip")
                        put("tag", "dns-fakeip")
                        put("inet4_range", "198.18.0.0/15")
                        put("inet6_range", "fc00::/18")
                    }
                )
            }
        }

        val rules = buildJsonArray {
            if (settings.fakeIpEnabled) {
                add(
                    buildJsonObject {
                        putJsonArray("query_type") {
                            add(JsonPrimitive("A"))
                            add(JsonPrimitive("AAAA"))
                        }
                        put("action", "route")
                        put("server", "dns-fakeip")
                    }
                )
            }
            if (settings.routingMode == RoutingMode.BYPASS_LAN_CN) {
                add(
                    buildJsonObject {
                        put("rule_set", "geosite-cn")
                        put("action", "route")
                        put("server", "dns-direct")
                    }
                )
            }
        }

        context.dns = buildJsonObject {
            put("servers", servers)
            if (rules.isNotEmpty()) {
                put("rules", rules)
            }
            put("final", "dns-remote")
            put("strategy", strategy)
            put("disable_cache", false)
            put("independent_cache", true)
        }
        context.diagnostics += "DNS: remote=${settings.remoteDns}, direct=${settings.directDns}, strategy=$strategy"
    }

    /**
     * 解析远程 DNS 地址为强类型 server 对象。
     */
    private fun buildRemoteServer(raw: String): JsonObject {
        val value = raw.trim().ifBlank { "https://1.1.1.1/dns-query" }
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> {
                val uri = runCatching { URI(value) }.getOrNull()
                val host = uri?.host ?: "1.1.1.1"
                val port = if (uri?.port != null && uri.port > 0) uri.port else 443
                val path = uri?.path?.ifBlank { "/dns-query" } ?: "/dns-query"
                buildJsonObject {
                    put("type", "https")
                    put("tag", "dns-remote")
                    put("server", host)
                    put("server_port", port)
                    put("path", path)
                    put("detour", "proxy")
                }
            }
            value.startsWith("tls://") -> {
                val (host, port) = parsePlainDns(value.removePrefix("tls://"), 853)
                buildJsonObject {
                    put("type", "tls")
                    put("tag", "dns-remote")
                    put("server", host)
                    put("server_port", port)
                    put("detour", "proxy")
                }
            }
            value.startsWith("quic://") -> {
                val (host, port) = parsePlainDns(value.removePrefix("quic://"), 853)
                buildJsonObject {
                    put("type", "quic")
                    put("tag", "dns-remote")
                    put("server", host)
                    put("server_port", port)
                    put("detour", "proxy")
                }
            }
            else -> {
                val (host, port) = parsePlainDns(value, 53)
                buildJsonObject {
                    put("type", "udp")
                    put("tag", "dns-remote")
                    put("server", host)
                    put("server_port", port)
                    put("detour", "proxy")
                }
            }
        }
    }

    /**
     * 拆分 `host[:port]`，支持 IPv6 方括号。
     */
    private fun parsePlainDns(raw: String, defaultPort: Int): Pair<String, Int> {
        val value = raw.trim()
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            if (close > 0) {
                val host = value.substring(1, close)
                val port = value.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                return host to port
            }
        }
        val colon = value.lastIndexOf(':')
        if (colon > 0 && value.count { it == ':' } == 1) {
            val host = value.substring(0, colon)
            val port = value.substring(colon + 1).toIntOrNull() ?: defaultPort
            return host to port
        }
        return value to defaultPort
    }

    /** 供测试断言使用：读取 servers 数组。 */
    fun serversOf(dns: JsonObject): JsonArray? = dns["servers"] as? JsonArray
}
