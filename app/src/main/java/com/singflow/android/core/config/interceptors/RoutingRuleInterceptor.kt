package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.model.RoutingMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale

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
            // 3.1 应用级国家分流（多节点编译时生效，必须排在大陆分流之前）
            if (context.nodes.size > 1) {
                buildAppRouteRules(context).forEach { add(it) }
            }
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
     * 将应用级国家分流表编译为路由规则。
     *
     * 同一模式的多个应用会合并为一条规则，减少内核逐条匹配开销；
     * 指定的国家没有可用节点时自动回退到默认国家组，避免该应用断网。
     */
    private fun buildAppRouteRules(context: SingBoxConfigContext): List<JsonObject> {
        val rules = context.appRouteRules
        if (rules.isEmpty()) return emptyList()
        val result = mutableListOf<JsonObject>()

        fun route(packages: List<String>, outbound: String) {
            if (packages.isEmpty()) return
            result += buildJsonObject {
                putJsonArray("package_name") { packages.forEach { add(it) } }
                put("action", "route")
                put("outbound", outbound)
            }
        }

        route(
            rules.filter { it.routeMode == AppRouteRuleEntity.MODE_DIRECT }.map { it.packageName },
            "direct"
        )

        val blocked = rules.filter { it.routeMode == AppRouteRuleEntity.MODE_BLOCK }.map { it.packageName }
        if (blocked.isNotEmpty()) {
            result += buildJsonObject {
                putJsonArray("package_name") { blocked.forEach { add(it) } }
                put("action", "reject")
            }
        }

        context.favoritesGroupTag?.let { favoritesTag ->
            route(
                rules.filter { it.routeMode == AppRouteRuleEntity.MODE_FAVORITES }.map { it.packageName },
                favoritesTag
            )
        }

        val countryRules = rules
            .filter { it.routeMode == AppRouteRuleEntity.MODE_COUNTRY && it.targetCountryCode.isNotBlank() }
            .groupBy { it.targetCountryCode.trim().uppercase(Locale.US) }
        for ((country, appRules) in countryRules) {
            val outbound = if (country in context.availableCountryCodes) {
                "group-$country"
            } else {
                context.defaultCountryGroupTag
            }
            route(appRules.map { it.packageName }, outbound)
        }

        return result
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
