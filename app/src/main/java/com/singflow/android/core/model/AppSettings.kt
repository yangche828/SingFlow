package com.singflow.android.core.model

import kotlinx.serialization.Serializable

/**
 * 路由模式。
 */
@Serializable
enum class RoutingMode(val displayName: String, val description: String) {
    /** 全部流量走代理。 */
    GLOBAL("全局代理", "所有流量均通过所选节点转发"),

    /** 绕过局域网与大陆地址。 */
    BYPASS_LAN_CN("绕过局域网与大陆", "局域网与 geoip-cn / geosite-cn 直连"),

    /** 仅绕过局域网。 */
    BYPASS_LAN("仅绕过局域网", "仅私有地址直连，其余流量走代理")
}

/**
 * 分应用代理模式。
 */
@Serializable
enum class PerAppMode(val displayName: String) {
    /** 仅代理勾选的应用（allow list）。 */
    ALLOW_LIST("仅代理所选应用"),

    /** 所选应用绕过代理（disallow list）。 */
    DISALLOW_LIST("所选应用绕过代理")
}

/**
 * 全局应用设置，持久化在 DataStore 中。
 *
 * @property remoteDns 远程 DoH/DoT 地址
 * @property directDns 直连 DNS
 * @property tunMtu TUN MTU
 * @property mixedPort 本地 Mixed 入站端口（同时作为 Clash API 的辅助端口）
 * @property enableIpv6 是否启用 IPv6
 * @property routingMode 路由模式
 * @property perAppProxyEnabled 是否启用分应用代理
 * @property perAppProxyMode 分应用代理模式
 * @property perAppPackages 选中的应用包名
 * @property logLevel 内核日志级别
 * @property autoConnectOnBoot 开机自动连接
 * @property selectedNodeId 当前选中节点 ID
 * @property fakeIpEnabled 是否启用 FakeIP（Phase 7 扩展，默认关闭）
 * @property countryRoutingEnabled 是否启用多节点国家分组编译（需要在连接前开启）
 * @property failover 故障自动切换配置
 */
@Serializable
data class AppSettings(
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "223.5.5.5",
    val tunMtu: Int = 9000,
    val mixedPort: Int = 7890,
    val enableIpv6: Boolean = false,
    val routingMode: RoutingMode = RoutingMode.BYPASS_LAN_CN,
    val perAppProxyEnabled: Boolean = false,
    val perAppProxyMode: PerAppMode = PerAppMode.DISALLOW_LIST,
    val perAppPackages: Set<String> = emptySet(),
    val logLevel: String = "info",
    val autoConnectOnBoot: Boolean = false,
    val selectedNodeId: String = "",
    val fakeIpEnabled: Boolean = false,
    val countryRoutingEnabled: Boolean = false,
    val failover: FailoverConfig = FailoverConfig.DEFAULT,
    val antiBlocking: AntiBlockingConfig = AntiBlockingConfig.DEFAULT,
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPasswordCipher: String = ""
) {
    companion object {
        val DEFAULT = AppSettings()

        /** 允许的 MTU 范围。 */
        val MTU_RANGE = 1280..65535
    }
}
