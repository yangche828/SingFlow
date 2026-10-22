package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 实验特性拦截器（红线 6）。
 *
 * 使用 `store_dns`，禁止 `store_rdrc`；并开启 Clash API 供流量/日志监控使用。
 */
class ExperimentalInterceptor : ConfigInterceptor {

    override val name: String = "ExperimentalInterceptor"

    override fun intercept(context: SingBoxConfigContext) {
        context.experimental = buildJsonObject {
            putJsonObject("cache_file") {
                put("enabled", true)
                put("path", "cache.db")
                put("store_dns", true)
                if (context.settings.fakeIpEnabled) {
                    put("store_fakeip", true)
                }
            }
            putJsonObject("clash_api") {
                put("external_controller", "127.0.0.1:9090")
                put("default_mode", "rule")
            }
        }
        context.diagnostics += "Experimental: cache_file(store_dns=true) + clash_api(127.0.0.1:9090)"
    }
}
