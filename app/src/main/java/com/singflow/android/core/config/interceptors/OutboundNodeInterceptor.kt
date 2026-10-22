package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import com.singflow.android.core.model.ProtocolType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 单节点出站拦截器（红线 2 / 红线 3 / 红线 6）。
 *
 * 将 [com.singflow.android.core.model.ProxyNode] 编译为 sing-box 1.14+ outbound 或 WireGuard endpoint，
 * 并注入 `domain_resolver`，绝不生成 `block` / `dns` outbound。
 *
 * 多节点（按国家分组）编译由 [CountryGroupInterceptor] 接管，本拦截器直接跳过。
 */
class OutboundNodeInterceptor : ConfigInterceptor {

    override val name: String = "OutboundNodeInterceptor"

    override fun intercept(context: SingBoxConfigContext) {
        if (context.nodes.size > 1) return

        val node = context.node
        val settings = context.settings
        val strategy = if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only"
        val resolver = buildJsonObject {
            put("server", "dns-direct")
            put("strategy", strategy)
        }

        if (node.protocol == ProtocolType.WIREGUARD) {
            // WireGuard 以 endpoint 形式参与路由，route.final 仍指向同名 tag。
            context.endpoints = buildJsonArray {
                add(ProxyOutboundFactory.buildWireGuardEndpoint(node, context.proxyTag, resolver))
            }
            context.outbounds = buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    }
                )
            }
            context.diagnostics += "Outbound: WireGuard endpoint -> ${node.server}:${node.port}"
            return
        }

        val proxy = ProxyOutboundFactory.buildProxyOutbound(node, context.proxyTag, resolver)
        context.outbounds = buildJsonArray {
            add(proxy)
            add(
                buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                }
            )
        }
        context.endpoints = JsonArray(emptyList())
        context.diagnostics += "Outbound: ${node.protocol.displayName} -> ${node.server}:${node.port}"
    }
}
