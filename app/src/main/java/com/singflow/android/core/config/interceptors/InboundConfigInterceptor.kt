package com.singflow.android.core.config.interceptors

import com.singflow.android.core.config.ConfigInterceptor
import com.singflow.android.core.config.SingBoxConfigContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 入站拦截器（红线 4 / 红线 5）。
 *
 * TUN 使用 `address` 数组，绝不生成 `inet4_address` 与 `sniff` 字段。
 */
class InboundConfigInterceptor : ConfigInterceptor {

    override val name: String = "InboundConfigInterceptor"

    override fun intercept(context: SingBoxConfigContext) {
        val settings = context.settings
        val addresses = buildJsonArray {
            add("172.19.0.1/30")
            if (settings.enableIpv6) {
                add("fdfe:dcba:9876::1/126")
            }
        }

        val tun = buildJsonObject {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", addresses)
            put("mtu", settings.tunMtu)
            put("auto_route", true)
            put("strict_route", true)
            // Android 没有权限做 NAT，sing-box 的默认 mixed 栈使用 system TCP，会导致 TCP 流量无法被接管。
            put("stack", "gvisor")
        }

        val mixed = buildJsonObject {
            put("type", "mixed")
            put("tag", "mixed-in")
            put("listen", "127.0.0.1")
            put("listen_port", settings.mixedPort)
        }

        context.inbounds = buildJsonArray {
            add(tun)
            add(mixed)
        }
        context.diagnostics += "Inbound: tun + mixed(127.0.0.1:${settings.mixedPort})"
    }
}
