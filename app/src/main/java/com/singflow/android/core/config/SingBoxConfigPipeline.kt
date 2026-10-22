package com.singflow.android.core.config

import com.singflow.android.core.config.interceptors.DnsConfigInterceptor
import com.singflow.android.core.config.interceptors.ExperimentalInterceptor
import com.singflow.android.core.config.interceptors.InboundConfigInterceptor
import com.singflow.android.core.config.interceptors.OutboundNodeInterceptor
import com.singflow.android.core.config.interceptors.RoutingRuleInterceptor
import com.singflow.android.core.model.AppSettings
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
     * 返回编译诊断（DNS / 入站 / 出站 / 路由），用于运行日志。
     */
    fun diagnostics(node: ProxyNode, settings: AppSettings, ruleSetDir: String? = null): List<String> {
        val context = SingBoxConfigContext(node = node, settings = settings, ruleSetDir = ruleSetDir)
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
            RoutingRuleInterceptor(),
            ExperimentalInterceptor()
        )
    }
}
