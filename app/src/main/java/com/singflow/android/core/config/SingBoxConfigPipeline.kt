package com.singflow.android.core.config

import com.singflow.android.core.config.interceptors.CountryGroupInterceptor
import com.singflow.android.core.config.interceptors.DnsConfigInterceptor
import com.singflow.android.core.config.interceptors.ExperimentalInterceptor
import com.singflow.android.core.config.interceptors.InboundConfigInterceptor
import com.singflow.android.core.config.interceptors.OutboundNodeInterceptor
import com.singflow.android.core.config.interceptors.RoutingRuleInterceptor
import com.singflow.android.core.database.AppRouteRuleEntity
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.ProtocolType
import com.singflow.android.core.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * sing-box 1.14+ 配置编译流水线。
 *
 * 采用拦截器链模式：每个 [ConfigInterceptor] 只负责一个段落，
 * 新增能力无需修改本类。
 *
 * @param json JSON 编解码器
 * @param interceptors 拦截器链，按注册顺序执行
 */
class SingBoxConfigPipeline(
    private val json: Json,
    private val interceptors: List<ConfigInterceptor> = defaultInterceptors()
) {

    /**
     * 编译为配置对象。
     */
    fun compile(node: ProxyNode, settings: AppSettings, ruleSetDir: String? = null): JsonObject {
        val context = SingBoxConfigContext(node = node, settings = settings, ruleSetDir = ruleSetDir)
        interceptors.forEach { interceptor -> interceptor.intercept(context) }
        return context.build()
    }

    /**
     * 编译为紧凑 JSON 字符串（供 libbox 使用）。
     */
    fun compileCompact(node: ProxyNode, settings: AppSettings, ruleSetDir: String? = null): String {
        return json.encodeToString(JsonObject.serializer(), compile(node, settings, ruleSetDir))
    }

    /**
     * 编译为带缩进的 JSON 字符串（供 UI 预览 / 日志复制）。
     */
    fun compilePretty(node: ProxyNode, settings: AppSettings, ruleSetDir: String? = null): String {
        val pretty = Json(json) {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
        }
        return pretty.encodeToString(JsonObject.serializer(), compile(node, settings, ruleSetDir))
    }

    /**
     * 多节点编译：按国家生成出站组，并注入应用级国家分流规则。
     *
     * 选中 WireGuard 节点时退回单节点编译（endpoint 不能放入 selector）。
     *
     * @param nodes 全部可用节点
     * @param selectedNodeId 当前选中节点 ID（找不到时取第一个）
     * @param settings 全局设置
     * @param appRouteRules 应用级国家分流规则
     * @param ruleSetDir 本地规则集目录
     */
    fun compile(
        nodes: List<ProxyNode>,
        selectedNodeId: String,
        settings: AppSettings,
        appRouteRules: List<AppRouteRuleEntity> = emptyList(),
        ruleSetDir: String? = null
    ): JsonObject {
        require(nodes.isNotEmpty()) { "至少需要一个节点才能编译配置" }
        val selected = nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.first()
        val effectiveNodes = if (selected.protocol == ProtocolType.WIREGUARD) listOf(selected) else nodes
        val context = SingBoxConfigContext(
            node = selected,
            settings = settings,
            ruleSetDir = ruleSetDir,
            nodes = effectiveNodes,
            appRouteRules = appRouteRules
        )
        interceptors.forEach { interceptor -> interceptor.intercept(context) }
        return context.build()
    }

    /** 多节点紧凑 JSON。 */
    fun compileCompact(
        nodes: List<ProxyNode>,
        selectedNodeId: String,
        settings: AppSettings,
        appRouteRules: List<AppRouteRuleEntity> = emptyList(),
        ruleSetDir: String? = null
    ): String = json.encodeToString(
        JsonObject.serializer(),
        compile(nodes, selectedNodeId, settings, appRouteRules, ruleSetDir)
    )

    /** 多节点带缩进 JSON。 */
    fun compilePretty(
        nodes: List<ProxyNode>,
        selectedNodeId: String,
        settings: AppSettings,
        appRouteRules: List<AppRouteRuleEntity> = emptyList(),
        ruleSetDir: String? = null
    ): String {
        val pretty = Json(json) {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
        }
        return pretty.encodeToString(
            JsonObject.serializer(),
            compile(nodes, selectedNodeId, settings, appRouteRules, ruleSetDir)
        )
    }

    /**
     * 返回编译诊断（DNS / 入站 / 出站 / 路由），用于运行日志。
     */
    fun diagnostics(node: ProxyNode, settings: AppSettings, ruleSetDir: String? = null): List<String> {
        val context = SingBoxConfigContext(node = node, settings = settings, ruleSetDir = ruleSetDir)
        interceptors.forEach { it.intercept(context) }
        return context.diagnostics.toList()
    }

    /**
     * 多节点编译诊断。
     */
    fun diagnostics(
        nodes: List<ProxyNode>,
        selectedNodeId: String,
        settings: AppSettings,
        appRouteRules: List<AppRouteRuleEntity> = emptyList(),
        ruleSetDir: String? = null
    ): List<String> {
        require(nodes.isNotEmpty()) { "至少需要一个节点才能编译配置" }
        val selected = nodes.firstOrNull { it.id == selectedNodeId } ?: nodes.first()
        val effectiveNodes = if (selected.protocol == ProtocolType.WIREGUARD) listOf(selected) else nodes
        val context = SingBoxConfigContext(
            node = selected,
            settings = settings,
            ruleSetDir = ruleSetDir,
            nodes = effectiveNodes,
            appRouteRules = appRouteRules
        )
        interceptors.forEach { it.intercept(context) }
        return context.diagnostics.toList()
    }

    companion object {
        /**
         * 默认拦截器链。扩展新能力时在此追加。
         */
        fun defaultInterceptors(): List<ConfigInterceptor> = listOf(
            DnsConfigInterceptor(),
            InboundConfigInterceptor(),
            OutboundNodeInterceptor(),
            CountryGroupInterceptor(),
            RoutingRuleInterceptor(),
            ExperimentalInterceptor()
        )
    }
}
