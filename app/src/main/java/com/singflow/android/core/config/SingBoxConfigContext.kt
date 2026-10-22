package com.singflow.android.core.config

import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProxyNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 配置编译上下文：各拦截器只修改自己负责的段落，最终由 [build] 组装为完整 sing-box 配置。
 *
 * @property node 当前选中节点
 * @property settings 全局设置
 * @property proxyTag 代理 outbound 的 tag
 * @property nodes 参与编译的全部节点（多节点时生成国家分组）
 * @property appRouteRules 应用级国家分流规则
 * @property availableCountryCodes 本次编译实际生成国家组的国家代码集合
 */
class SingBoxConfigContext(
    val node: ProxyNode,
    val settings: AppSettings,
    val proxyTag: String = "proxy",
    /** 本地规则集目录；为空时退回远程下载。 */
    val ruleSetDir: String? = null,
    /** 参与编译的全部节点；默认只包含当前节点。 */
    val nodes: List<ProxyNode> = listOf(node),
    /** 应用级国家分流规则。 */
    val appRouteRules: List<AppRouteRuleEntity> = emptyList()
) {
    /** 日志段。 */
    var log: JsonObject = buildJsonObject {
        put("level", settings.logLevel.ifBlank { "info" })
        put("timestamp", true)
    }

    /** DNS 段。 */
    var dns: JsonObject = buildJsonObject { }

    /** 入站段。 */
    var inbounds: JsonArray = JsonArray(emptyList())

    /** 出站段。 */
    var outbounds: JsonArray = JsonArray(emptyList())

    /** endpoint 段（WireGuard）。 */
    var endpoints: JsonArray = JsonArray(emptyList())

    /** 路由段。 */
    var route: JsonObject = buildJsonObject { }

    /** 实验特性段。 */
    var experimental: JsonObject = buildJsonObject { }

    /** 其它顶层段落（供 Phase 7 扩展）。 */
    val extraSections: MutableMap<String, JsonElement> = linkedMapOf()

    /** 编译诊断信息，会写入运行日志。 */
    val diagnostics: MutableList<String> = mutableListOf()

    /** 本次编译实际生成国家组的国家代码集合。 */
    val availableCountryCodes: MutableSet<String> = linkedSetOf()

    /** 未单独指定国家的应用默认使用的出站 tag。 */
    var defaultCountryGroupTag: String = proxyTag

    /** 收藏组 tag；没有收藏节点时为 null。 */
    var favoritesGroupTag: String? = null

    /**
     * 组装完整配置对象。
     */
    fun build(): JsonObject = buildJsonObject {
        put("log", log)
        put("dns", dns)
        put("inbounds", inbounds)
        if (endpoints.isNotEmpty()) {
            put("endpoints", endpoints)
        }
        put("outbounds", outbounds)
        put("route", route)
        put("experimental", experimental)
        extraSections.forEach { (key, value) -> put(key, value) }
    }
}
