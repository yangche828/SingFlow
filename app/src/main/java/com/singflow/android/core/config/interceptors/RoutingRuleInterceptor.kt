package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import com.singflow.android.core.model.RoutingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 路由规则拦截器（红线 2 / 红线 3 / 红线 4 / 红线 6）。
 *
 * 规则顺序固定为：sniff → hijack-dns → 私有地址直连 → 大陆分流（可选）。
 */
class RoutingRuleInterceptor : ConfigInterceptor {

    override val name: String = "RoutingRuleInterceptor"

    override fun intercept(context: SingBoxConfigContext) {
        val settings = context.settings
        val strategy = if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only"

        val rules = buildJsonArray {
            // 1. 协议嗅探（替代旧版 inbound.sniff）
            add(
                buildJsonObject {
                    put("action", "sniff")
                    put("timeout", "300ms")
                }
            )
            // 2. DNS 劫持（替代旧版 dns outbound）
            add(
                buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                }
            )
            // 3. 私有地址直连
            add(
                buildJsonObject {
                    put("ip_is_private", true)
                    put("action", "route")
                    put("outbound", "direct")
                }
            )
            // 4. 大陆分流（BYPASS_LAN_CN）
            if (settings.routingMode == RoutingMode.BYPASS_LAN_CN) {
                add(
                    buildJsonObject {
                        put("rule_set", buildJsonArray {
                            add("geosite-cn")
                            add("geoip-cn")
                        })
                        put("action", "route")
                        put("outbound", "direct")
                    }
                )
            }
            // 5. 全局模式：其余全部走代理；显式规则便于审计
            if (settings.routingMode == RoutingMode.GLOBAL) {
                add(
                    buildJsonObject {
                        put("action", "route")
                        put("outbound", context.proxyTag)
                    }
                )
            }
        }

        val ruleSets = buildJsonArray {
            if (settings.routingMode == RoutingMode.BYPASS_LAN_CN) {
                add(ruleSet(context, "geosite-cn", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"))
                add(ruleSet(context, "geoip-cn", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs"))
            }
        }

        context.route = buildJsonObject {
            put("auto_detect_interface", true)
            putJsonObject("default_domain_resolver") {
                put("server", "dns-direct")
                put("strategy", strategy)
            }
            put("rules", rules)
            if (ruleSets.isNotEmpty()) {
                put("rule_set", ruleSets)
            }
            put("final", context.proxyTag)
        }
        context.diagnostics += "Route: mode=${settings.routingMode.name}, final=${context.proxyTag}"
    }

    /**
     * 规则集定义：优先使用随 APK 分发的本地 `.srs`，缺失时回退远程下载。
     */
    private fun ruleSet(context: SingBoxConfigContext, tag: String, url: String): JsonObject {
        val directory = context.ruleSetDir
        if (!directory.isNullOrBlank()) {
            val file = File(directory, "$tag.srs")
            if (file.isFile) {
                return buildJsonObject {
                    put("type", "local")
                    put("tag", tag)
                    put("format", "binary")
                    put("path", file.absolutePath)
                }
            }
        }
        return buildJsonObject {
            put("type", "remote")
            put("tag", tag)
            put("format", "binary")
            put("url", url)
            put("download_detour", "direct")
            put("update_interval", "1d")
        }
    }
}
