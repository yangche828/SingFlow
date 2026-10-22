# SingFlow Android 客户端详细工程设计说明书（直接落地开发版）

> **文档版本**：v2.0（工程级全细节交付版）  
> **目标平台**：Android API 33 $\sim$ 36+（兼容 Android 13 $\sim$ 16+）  
> **核心内核**：`sing-box` (`libbox.aar`)  
> **语言支持**：简体中文（单语言极限瘦身配置）  
> **核心目标**：高强度抗封锁与防 DNS 逃逸、$7 \times 24$ 小时零断流高稳定性、批量节点导入/探活/落地地区检测、节点健康度数学评分与自动打标、应用级按国家自定义分流、分层可配置故障自动切换、息屏零功耗省电优化、安装包（APK）极限瘦身。

---
## 工程现状
项目：SingFlow —— Android 13~17 的 sing-box 客户端（Kotlin + Compose + libbox 1.14.3），已交付 1.0.1。
仓库：https://github.com/yangche828/SingFlow（私有）
依次读：HANDOFF.md 第 0 节（零上下文接手）→ AGENTS.md（红线）→ TASK_PROGRESS.md（验收矩阵）→ README.md
环境：JDK 21 / Android SDK（platform 36）/ Node；libbox.aar 已入库，clone 即可构建。
先跑：./gradlew :app:testDebugUnitTest（应 31/31）、./gradlew :app:assembleDebug。
注意：16 KB 页大小只有静态校验；真实节点已脱敏（要复现真实公网节点验收需我另行提供节点）；
GitHub 推送必须带代理参数（见 HANDOFF §0.4）。
---

## 目录

1. [系统总体架构与代码重构规范](#一-系统总体架构与代码重构规范)
2. [代理抗封锁能力与防 DNS 逃逸详细设计](#二-代理抗封锁能力与防-dns-逃逸详细设计)
3. [数据层详细设计（Room 数据库与 DataStore）](#三-数据层详细设计room-数据库与-datastore)
4. [核心业务引擎详细设计与落地代码](#四-核心业务引擎详细设计与落地代码)
   - 4.1 [批量节点导入、订阅解析与编码修复引擎](#41-批量节点导入订阅解析与编码修复引擎)
   - 4.2 [批量并发探活与批量落地地区检测引擎](#42-批量并发探活与批量落地地区检测引擎)
   - 4.3 [节点健康度评分算法与动态打标签机制](#43-节点健康度评分算法与动态打标签机制)
   - 4.4 [节点按国家分组与应用级国家分流编译器](#44-节点按国家分组与应用级国家分流编译器)
   - 4.5 [节点不可用自动切换方案（预设大方向 + 细节微调）](#45-节点不可用自动切换方案预设大方向--细节微调)
   - 4.6 [补充高实用价值功能模块设计](#46-补充高实用价值功能模块设计)
5. [全方位软件稳定性加固工程（Stability Engineering）](#五-全方位软件稳定性加固工程stability-engineering)
6. [极致耗电优化设计（Power Optimization）](#六-极致耗电优化设计power-optimization)
7. [安装包（APK）极限瘦身与中文单语言构建配置](#七-安装包apk极限瘦身与中文单语言构建配置)
8. [UI 交互细节规范与落地开发里程碑](#八-ui-交互细节规范与落地开发里程碑)

---

## 一、 系统总体架构与代码重构规范

### 1.1 架构分层（Clean Architecture + MVI）
为消除业务逻辑耦合、减小冗余代码体积并提升多线程并发安全性，项目采用 **Clean Architecture + MVI（Model-View-Intent）** 单向数据流架构，包结构严格按以下职责划分：

```text
com.yangche.singflow
├── core/
│   ├── box/                # libbox.aar JNI 桥接、PlatformInterface 实现、内核守护与互斥锁
│   ├── config/             # Sing-box JSON 配置编译器（抗封锁、国家分组、Per-App 路由生成）
│   ├── probe/              # 批量并发探活引擎、GeoIP 落地国家检测器、本机直连甄别器
│   ├── scorer/             # 节点健康度数学评分引擎与动态标签状态机
│   ├── failover/           # 自动切换容灾控制器（四大预设方向 + 七项微调参数执行器）
│   └── power/              # 息屏/亮屏状态机、网络切换监听器、动态采样节流器
├── data/
│   ├── local/              # Room 数据库、DAO、Entity、DataStore 偏好设置存储
│   ├── parser/             # 多协议 URI / Base64 / Clash YAML / Sing-box JSON 批量解析器
│   └── repository/         # 节点仓库、订阅更新 Worker、应用分流规则仓库、SRS 规则热更新器
├── domain/
│   ├── model/              # 纯 Kotlin 领域模型（不可变数据类，标注 @Immutable 供 Compose 优化）
│   └── usecase/            # 业务用例层（聚合多 Repository 的复杂事务）
└── ui/
    ├── main/               # HorizontalPager 三屏主容器（左滑节点、中屏主页、右滑设置）
    ├── home/               # 主页（一键连接、实时速率平滑曲线、累计流量、当前节点卡片）
    ├── nodes/              # 节点页（按国家分组折叠、健康分徽章、收藏置顶、批量测速/测地区）
    └── settings/           # 设置页（应用级国家分流器、自动切换策略面板、订阅管理、连接诊断）
```

### 1.2 核心代码精简与依赖收敛准则
1. **统一序列化与网络栈**：废弃体积庞大的 `Retrofit + OkHttp + Gson/Jackson` 组合，全局统一使用 **Kotlinx Serialization (`kotlinx-serialization-json`)** 处理 JSON 解析与配置树生成；订阅拉取与规则下载直接封装基于 `HttpsURLConnection` 的轻量协程客户端或直接复用 `libbox` 内置网络栈，单此一项即可缩减约 $1.8\text{ MB}$ 第三方字节码。
2. **结构化并发规范**：严禁在任何位置使用 `GlobalScope`。`SingFlowVpnService` 维护独立的 `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`，并在 `onDestroy()` 时安全取消；UI 层状态收集统一使用 `repeatOnLifecycle(Lifecycle.State.STARTED)`，确保退至后台时零 UI 刷新消耗。

---

## 二、 代理抗封锁能力与防 DNS 逃逸详细设计

针对高强度网络审查环境以及连接超时（`egress_timeout`）、DNS 污染/逃逸（`dns_escape`）等痛点，在配置编译器 `SingBoxConfigBuilder` 中实施以下四层抗封锁加固。

### 2.1 抗封锁协议与传输层伪装设计矩阵

| 特性模块 | `sing-box` 配置字段映射 | 默认策略与抗封锁原理 |
| :--- | :--- | :--- |
| **uTLS 客户端指纹伪装** | `outbound.tls.utls.enabled = true`<br>`outbound.tls.utls.fingerprint = "chrome"` | 消除 Go 原生 `crypto/tls` 握手的 JA3/JA4 特征，默认模拟最新版 Chrome 浏览器指纹，支持用户在 `chrome`、`firefox`、`safari`、`edge`、`random` 间切换。 |
| **TLS ClientHello 分片** | `route.rules[].tls_fragment = true`<br>`tls_fragment_fallback_delay = "300ms"` | 将 TLS 握手首包在 TCP 层切分为多个微小片段发送，破坏中间设备（DPI）对明文 SNI 字段的特征匹配与阻断。 |
| **多路复用与长度填充** | `outbound.multiplex.enabled = true`<br>`protocol = "h2mux"`, `padding = true` | 启用连接复用减少高频建连的握手暴露面；强制开启 `padding` 随机填充数据包长度，抵抗基于包长度分布的流量分析识别。 |
| **VLESS + Reality 伪装** | `outbound.tls.reality.enabled = true`<br>`public_key`, `short_id`, `flow = "xtls-rprx-vision"` | 消除服务端自签证书特征，直接借用目标知名大厂站点的真实 TLS 1.3 证书与握手旁路，彻底免疫主动探测。 |
| **QUIC 抗丢包与端口跳跃** | `type = "hysteria2"` / `"tuic"`<br>`server_ports = ["20000:50000"]`, `hop_interval = "30s"` | 针对晚高峰 UDP QoS 限流与高丢包环境，每 $30\text{ s}$ 在指定端口段动态切换通信端口，阻断基于五元组的流控封锁。 |

### 2.2 根治 DNS 逃逸的双栈安全 DNS 架构（Anti-DNS Escape）

为彻底杜绝本地运营商 DNS 抢答、污染以及明文 DNS 泄漏，客户端**全面禁止任何明文 UDP/TCP 53 端口 DNS 请求直接出网**，采用 **FakeIP + 加密远端 DoH + 引导自举解析器 + TUN 层 53 端口强制劫持**：

```json
{
  "dns": {
    "servers": [
      {
        "tag": "dns-remote",
        "address": "https://1.1.1.1/dns-query",
        "address_resolver": "dns-bootstrap",
        "strategy": "prefer_ipv4",
        "detour": "proxy-default"
      },
      {
        "tag": "dns-direct",
        "address": "https://223.5.5.5/dns-query",
        "address_resolver": "dns-bootstrap",
        "strategy": "prefer_ipv4",
        "detour": "direct-out"
      },
      {
        "tag": "dns-bootstrap",
        "address": "223.5.5.5",
        "detour": "direct-out"
      },
      {
        "tag": "dns-fakeip",
        "address": "fakeip"
      }
    ],
    "rules": [
      {
        "outbound": "any",
        "server": "dns-direct"
      },
      {
        "rule_set": ["geosite-cn"],
        "server": "dns-direct"
      },
      {
        "query_type": ["A", "AAAA"],
        "server": "dns-fakeip",
        "rewrite_ttl": 1
      }
    ],
    "fakeip": {
      "enabled": true,
      "inet4_range": "198.18.0.0/15",
      "inet6_range": "fc00::/18"
    },
    "final": "dns-remote",
    "independent_cache": true
  }
}
```

#### 防逃逸核心保障机制：
1. **节点域名自举解析（防死锁超时）**：`"outbound": "any", "server": "dns-direct"` 规则确保代理节点自身的服务器域名（`serverHost`）强制通过国内加密阿里 DoH（`223.5.5.5`）直连解析，彻底消除“代理节点域名需要走代理才能解析”的死锁问题（即 `egress_timeout` 的主因之一）。
2. **硬编码 DNS 强制劫持**：在 `route.rules` 首条配置 `"protocol": "dns", "outbound": "dns-out"` 以及 `"port": 53, "outbound": "dns-out"`，即便第三方 App 绕过 Android 系统接口直接向 `8.8.8.8:53` 或 `114.114.114.114:53` 发送明文请求，也会被 TUN 虚拟网卡无条件拦截并重定向至内部安全 DNS 引擎。
3. **FakeIP 零延迟防泄漏**：对所有非中国大陆域名的 `A/AAAA` 查询，本地立即返回 `198.18.0.0/15` 虚拟网段 IP，应用发起连接时由 `sing-box` 自动还原为真实域名并封装在加密代理隧道内发往远端节点解析，本地网络零明文域名暴露。

---

## 三、 数据层详细设计（Room 数据库与 DataStore）

### 3.1 Room 数据库表结构定义 (`Entities`)

```kotlin
package com.yangche.singflow.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 节点核心实体表：支持幂等去重、国家归类、健康度统计与收藏管理
 */
@Entity(
    tableName = "nodes",
    indices = [
        Index(value = ["configHash"], unique = true),
        Index(value = ["countryCode", "healthScore"]),
        Index(value = ["isFavorite", "healthScore"]),
        Index(value = ["subscriptionId"])
    ]
)
data class NodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configHash: String,              // SHA-256(protocol + server + port + credential + transport)
    val subscriptionId: Long? = null,    // 关联订阅 ID，手动导入为 null
    val rawOutboundJson: String,         // 标准化编译后的单节点 sing-box Outbound JSON 字符串
    val rawShareUri: String,             // 原始分享链接（用于导出/生成二维码）
    val displayName: String,             // 经 URLDecode 与控制字符清洗后的规范名称
    val protocol: String,                // vless, vmess, trojan, hysteria2, tuic, shadowsocks
    val serverHost: String,
    val serverPort: Int,

    // 地区与分组属性
    val countryCode: String = "UN",      // ISO 3166-1 alpha-2 国家代码（如 US, JP, SG, HK, UN=未知）
    val countryNameCn: String = "未知地区",// 中文国家名称（如 美国、日本、新加坡、中国香港）
    val egressIp: String? = null,        // 实测落地出口 IP
    val isRegionVerified: Boolean = false,// true=已通过实测 GeoIP 校准；false=仅通过节点名称正则预判

    // 探活与健康度数学统计指标
    val probeTotalCount: Int = 0,        // 累计探活总次数 N
    val probeSuccessCount: Int = 0,      // 累计探活成功次数 S
    val consecutiveFailures: Int = 0,    // 连续失败次数 F_streak（一旦成功即清零）
    val lastLatencyMs: Int = -1,         // 最近一次实测 RTT 延迟（ms，-1 表示未测或超时）
    val emaLatencyMs: Float = 0f,        // 指数移动平均延迟 L_ema
    val latencyJitterMs: Float = 0f,     // 延迟抖动指数移动平均 J_ema
    val healthScore: Int = 50,           // 综合健康分（0 - 100，新导入节点初始默认 50 分）
    val healthTag: String = "未检测",     // 动态标签：极佳 / 健康 / 一般 / 异常 / 未检测
    val lastProbeTimeMs: Long = 0L,      // 上次探活时间戳
    val cooldownUntilMs: Long = 0L,      // 故障熔断冷却截止时间戳（此时间戳前不参与自动切换）

    // 用户个性化与流量统计
    val isFavorite: Boolean = false,     // 是否收藏置顶
    val cumulativeRxBytes: Long = 0L,    // 该节点历史累计下行字节数
    val cumulativeTxBytes: Long = 0L,    // 该节点历史累计上行字节数
    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * 订阅源管理表
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val userAgent: String = "SingFlow/2.0 (sing-box; Android)",
    val autoUpdateEnabled: Boolean = true,
    val updateIntervalHours: Int = 12,
    val uploadBytes: Long = 0L,          // 解析自响应头 subscription-userinfo
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestampSec: Long = 0L,
    val lastUpdatedMs: Long = 0L,
    val lastUpdateStatus: String = "未更新"
)

/**
 * 应用级国家分流规则表（Per-App Country Routing）
 */
@Entity(
    tableName = "app_route_rules",
    indices = [Index(value = ["packageName"], unique = true)]
)
data class AppRouteRuleEntity(
    @PrimaryKey val packageName: String, // 应用包名，如 "com.openai.chatgpt"
    val appName: String,                 // 应用中文名缓存，供 UI 列表秒开展示
    val routeMode: String,               // "COUNTRY"（指定国家）, "FAVORITES"（收藏组）, "DIRECT"（直连）, "BLOCK"（断网阻断）
    val targetCountryCode: String = "",  // 当 routeMode == "COUNTRY" 时存储国家代码，如 "US", "JP", "SG"
    val updatedAtMs: Long = System.currentTimeMillis()
)
```

### 3.2 核心数据访问接口 (`NodeDao`)

```kotlin
package com.yangche.singflow.data.local.dao

import androidx.room.*
import com.yangche.singflow.data.local.entity.NodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY isFavorite DESC, healthScore DESC, lastLatencyMs ASC")
    fun observeAllNodesSorted(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE countryCode = :country ORDER BY isFavorite DESC, healthScore DESC, lastLatencyMs ASC")
    suspend fun getNodesByCountry(country: String): List<NodeEntity>

    @Query("""
        SELECT * FROM nodes 
        WHERE cooldownUntilMs < :nowMs AND healthScore >= :minScore 
        ORDER BY isFavorite DESC, healthScore DESC, emaLatencyMs ASC
    """)
    suspend fun getHealthyCandidateNodes(nowMs: Long, minScore: Int = 50): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE configHash = :hash LIMIT 1")
    suspend fun findByConfigHash(hash: String): NodeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(node: NodeEntity): Long

    @Update
    suspend fun updateNode(node: NodeEntity)

    /**
     * 订阅同步专用幂等更新：若节点已存在，仅更新配置与名称，完整保留收藏状态、实测国家与历史健康度评分
     */
    @Transaction
    suspend fun upsertPreservingStats(incomingNodes: List<NodeEntity>) {
        for (incoming in incomingNodes) {
            val existing = findByConfigHash(incoming.configHash)
            if (existing == null) {
                insertIgnore(incoming)
            } else {
                updateNode(
                    existing.copy(
                        rawOutboundJson = incoming.rawOutboundJson,
                        rawShareUri = incoming.rawShareUri,
                        displayName = incoming.displayName,
                        subscriptionId = incoming.subscriptionId ?: existing.subscriptionId,
                        countryCode = if (existing.isRegionVerified) existing.countryCode else incoming.countryCode,
                        countryNameCn = if (existing.isRegionVerified) existing.countryNameCn else incoming.countryNameCn
                    )
                )
            }
        }
    }

    @Query("UPDATE nodes SET isFavorite = :favorite WHERE id = :nodeId")
    suspend fun setFavorite(nodeId: Long, favorite: Boolean)

    @Query("DELETE FROM nodes WHERE id IN (:nodeIds)")
    suspend fun deleteNodesByIds(nodeIds: List<Long>)
}
```

---

## 四、 核心业务引擎详细设计与落地代码

### 4.1 批量节点导入、订阅解析与编码修复引擎

针对节点名称特殊字符乱码及多格式兼容问题，`NodeImportParser` 按以下流水线执行：

1. **多源格式自动拆解**：
   * 支持剪贴板一次性粘贴数十条混合协议链接（`vless://`、`vmess://`、`trojan://`、`ss://`、`hysteria2://`、`hy2://`、`tuic://`）。
   * 支持 Base64 / URL-Safe Base64 订阅正文自动识别与 Padding（`=`）自动补齐解码。
   * 支持直接导入 `sing-box` JSON 格式中的 `outbounds` 数组。
2. **节点名称编码深度清洗函数**：
   ```kotlin
   package com.yangche.singflow.data.parser

   import java.net.URLDecoder
   import java.nio.charset.StandardCharsets
   import java.security.MessageDigest

   object NodeParserUtils {
       /**
        * 修复 URL 编码乱码、+ 号转空格误伤以及不可见控制字符导致的渲染/日志异常
        */
       fun cleanNodeDisplayName(rawFragment: String?, fallbackHost: String, port: Int): String {
           if (rawFragment.isNullOrBlank()) return "$fallbackHost:$port"
           return try {
               val preservedPlus = rawFragment.replace("+", "%2B")
               val decoded = URLDecoder.decode(preservedPlus, StandardCharsets.UTF_8.name()).trim()
               // 移除 ASCII 控制字符与零宽字符
               decoded.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), "")
                   .take(64)
                   .ifEmpty { "$fallbackHost:$port" }
           } catch (e: Exception) {
               rawFragment.trim().take(64)
           }
       }

       /**
        * 计算节点核心连接要素 SHA-256 指纹，用于全局幂等去重
        */
       fun computeConfigHash(
           protocol: String,
           host: String,
           port: Int,
           credential: String,
           transport: String,
           sni: String
       ): String {
           val rawKey = "${protocol.lowercase()}|${host.lowercase()}|$port|$credential|$transport|${sni.lowercase()}"
           val bytes = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray(StandardCharsets.UTF_8))
           return bytes.joinToString("") { "%02x".format(it) }.take(32)
       }

       /**
        * 节点入库前的静态国家预识别（保证未测速前也能按国家展示分组）
        */
       private val COUNTRY_RULES = listOf(
           Triple("HK", "中国香港", Regex("(?i)(🇭🇰|香港|\\bHK\\b|Hong\\s*Kong|HKSAR)")),
           Triple("TW", "中国台湾", Regex("(?i)(🇹🇼|台湾|台北|新北|\\bTW\\b|Taiwan|Taipei)")),
           Triple("JP", "日本", Regex("(?i)(🇯🇵|日本|东京|大阪|埼玉|\\bJP\\b|Japan|Tokyo|Osaka)")),
           Triple("SG", "新加坡", Regex("(?i)(🇸🇬|新加坡|狮城|\\bSG\\b|Singapore)")),
           Triple("US", "美国", Regex("(?i)(🇺🇸|美国|洛杉矶|圣何塞|西雅图|纽约|硅谷|\\bUS\\b|United\\s*States|America|LAX|SJC)")),
           Triple("KR", "韩国", Regex("(?i)(🇰🇷|韩国|首尔|釜山|\\bKR\\b|Korea|Seoul)")),
           Triple("GB", "英国", Regex("(?i)(🇬🇧|英国|伦敦|\\bUK\\b|\\bGB\\b|Britain|London)")),
           Triple("DE", "德国", Regex("(?i)(🇩🇪|德国|法兰克福|\\bDE\\b|Germany|Frankfurt)"))
       )

       fun guessCountryFromName(displayName: String): Pair<String, String> {
           for ((code, nameCn, regex) in COUNTRY_RULES) {
               if (regex.containsMatchIn(displayName)) return code to nameCn
           }
           return "UN" to "其他地区"
       }
   }
   ```

---

### 4.2 批量并发探活与批量落地地区检测引擎 (`BatchProbeEngine`)

为防止一次性对上百个节点并发拨号耗尽系统 Socket 句柄或导致家用路由器 NAT 表崩溃，探活引擎采用**有界信号量限流 + 两阶段合一（真实 RTT 探活 + 落地出口 GeoIP 识别）**：

* **并发度控制**：通过 `Semaphore(permits = 12)` 严格限制同一时刻最多 12 个节点处于并发握手状态，单节点硬超时设为 $3500\text{ ms}$。
* **探活与地区检测合一原理**：
  * **仅测速探活模式**：通过代理隧道请求 `https://www.gstatic.com/generate_204`（超时降级至 `http://cp.cloudflare.com/generate_204`），记录从发起代理连接至收到 HTTP `204` 响应头的完整耗时作为真实 RTT 延迟（杜绝虚假 TCP Ping）。
  * **探活 + 批量地区检测模式**：通过被测节点代理请求 `https://www.cloudflare.com/cdn-cgi/trace`（响应体仅约 $150\text{ Bytes}$ 纯文本，极省流量且全球 Anycast 响应极快）。
  * 解析响应文本中的 `ip=` 与 `loc=` 行：
    ```text
    ip=104.28.192.11
    ts=1710000000.123
    loc=JP
    ```
    即可在一次请求中同时获得：**真实代理可用性**、**端到端 RTT 延迟**、**真实落地出口 IP (`egressIp`)** 以及**权威落地国家代码 (`countryCode = "JP"`)**，并将 `isRegionVerified` 置为 `true`。若个别节点屏蔽 Cloudflare，自动回退请求 `https://api.ip.sb/geoip`。

---

### 4.3 节点健康度评分算法与动态打标签机制 (`NodeHealthScorer`)

每次探活（含手动批量探活、后台巡检探活、故障转移探活）结束后，立即通过以下数学模型计算节点的综合健康分（$0 \sim 100$ 分）并赋予中文状态标签。

#### 1. 数学模型与更新公式
设节点更新前的历史总检测次数为 $N$，成功次数为 $S$，连续失败次数为 $F_{\text{streak}}$，历史指数移动平均延迟为 $L_{\text{ema}}$，历史抖动为 $J_{\text{ema}}$。

* **当本次探测结果为【成功】，实测延迟为 $L_{\text{cur}}$（单位：$\text{ms}$）时**：
  $$N' = N + 1, \quad S' = S + 1, \quad F'_{\text{streak}} = 0$$
  $$L'_{\text{ema}} = \begin{cases} L_{\text{cur}}, & \text{若 } L_{\text{ema}} \le 0 \\ 0.7 \times L_{\text{ema}} + 0.3 \times L_{\text{cur}}, & \text{若 } L_{\text{ema}} > 0 \end{cases}$$
  $$J'_{\text{ema}} = 0.7 \times J_{\text{ema}} + 0.3 \times |L_{\text{cur}} - L'_{\text{ema}}|$$

* **当本次探测结果为【失败/超时】时**：
  $$N' = N + 1, \quad S' = S, \quad F'_{\text{streak}} = F_{\text{streak}} + 1$$

* **五维综合健康分 $\text{Score}$ 计算公式**：
  1. **拉普拉斯贝叶斯平滑成功率 $R_{\text{smooth}}$**（防止新节点仅测 $1$ 次成功就拿到满分）：
     $$R_{\text{smooth}} = \frac{S' + 1}{N' + 2}$$
  2. **低延迟得分 $S_{\text{latency}}$**（满分 $30$ 分，$80\text{ ms}$ 以内满分，$\ge 1200\text{ ms}$ 得 $0$ 分）：
     $$S_{\text{latency}} = 30 \times \text{clamp}\left( \frac{1200 - L'_{\text{ema}}}{1200 - 80}, \ 0.0, \ 1.0 \right)$$
  3. **低抖动稳定性得分 $S_{\text{jitter}}$**（满分 $10$ 分）：
     $$S_{\text{jitter}} = 10 \times \text{clamp}\left( 1.0 - \frac{J'_{\text{ema}}}{300}, \ 0.0, \ 1.0 \right)$$
  4. **历史样本置信度奖励分 $S_{\text{conf}}$**（满分 $5$ 分，累计检测满 $20$ 次拿满）：
     $$S_{\text{conf}} = 5 \times \min\left(1.0, \ \frac{\ln(N' + 1)}{\ln(21)}\right)$$
  5. **连续失败重罚项 $P_{\text{fail}}$**（一旦节点刚发生断连，分数立刻断崖下跌以触发容灾切换）：
     $$P_{\text{fail}} = F'_{\text{streak}} \times 18$$
  6. **最终健康分汇总**：
     $$\text{Score} = \text{round}\Big( \text{clamp}\big( 55 \times R_{\text{smooth}} + S_{\text{latency}} + S_{\text{jitter}} + S_{\text{conf}} - P_{\text{fail}}, \ 0, \ 100 \big) \Big)$$

#### 2. 完整 Kotlin 评分引擎实现代码

```kotlin
package com.yangche.singflow.core.scorer

import com.yangche.singflow.data.local.entity.NodeEntity
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

object NodeHealthScorer {

    fun evaluateAfterProbe(
        node: NodeEntity,
        isSuccess: Boolean,
        latencyMs: Int,
        detectedCountryCode: String? = null,
        detectedCountryNameCn: String? = null,
        detectedIp: String? = null,
        cooldownMinutesOnDead: Int = 10
    ): NodeEntity {
        val now = System.currentTimeMillis()
        val newTotal = node.probeTotalCount + 1
        val newSuccess = if (isSuccess) node.probeSuccessCount + 1 else node.probeSuccessCount
        val newStreakFail = if (isSuccess) 0 else node.consecutiveFailures + 1

        val newEmaLatency = if (isSuccess && latencyMs > 0) {
            if (node.emaLatencyMs <= 0f) latencyMs.toFloat()
            else 0.7f * node.emaLatencyMs + 0.3f * latencyMs.toFloat()
        } else {
            node.emaLatencyMs
        }

        val newJitter = if (isSuccess && latencyMs > 0) {
            0.7f * node.latencyJitterMs + 0.3f * abs(latencyMs - newEmaLatency)
        } else {
            node.latencyJitterMs
        }

        // 1. 拉普拉斯平滑成功率 (权重 55)
        val smoothRate = (newSuccess + 1.0) / (newTotal + 2.0)
        val rateScore = 55.0 * smoothRate

        // 2. 延迟得分 (权重 30)
        val effectiveLatency = if (newEmaLatency > 0f) newEmaLatency else 800f
        val latencyRatio = ((1200f - effectiveLatency) / (1200f - 80f)).coerceIn(0f, 1f)
        val latencyScore = 30.0 * latencyRatio

        // 3. 抗抖动得分 (权重 10)
        val jitterRatio = (1f - (newJitter / 300f)).coerceIn(0f, 1f)
        val jitterScore = 10.0 * jitterRatio

        // 4. 样本置信度分 (权重 5)
        val confScore = 5.0 * min(1.0, ln((newTotal + 1).toDouble()) / ln(21.0))

        // 5. 连续失败惩罚
        val penalty = newStreakFail * 18.0

        val finalScore = (rateScore + latencyScore + jitterScore + confScore - penalty)
            .roundToInt()
            .coerceIn(0, 100)

        val tag = when {
            finalScore >= 85 && newStreakFail == 0 -> "极佳"
            finalScore >= 70 && newStreakFail == 0 -> "健康"
            finalScore >= 50 && newStreakFail <= 1 -> "一般"
            else -> "异常"
        }

        // 若连续失败达到 2 次，打入冷却小黑屋
        val cooldownUntil = if (newStreakFail >= 2) {
            now + cooldownMinutesOnDead * 60_000L
        } else if (isSuccess) {
            0L
        } else {
            node.cooldownUntilMs
        }

        return node.copy(
            probeTotalCount = newTotal,
            probeSuccessCount = newSuccess,
            consecutiveFailures = newStreakFail,
            lastLatencyMs = if (isSuccess) latencyMs else -1,
            emaLatencyMs = newEmaLatency,
            latencyJitterMs = newJitter,
            healthScore = finalScore,
            healthTag = tag,
            lastProbeTimeMs = now,
            cooldownUntilMs = cooldownUntil,
            countryCode = detectedCountryCode ?: node.countryCode,
            countryNameCn = detectedCountryNameCn ?: node.countryNameCn,
            egressIp = detectedIp ?: node.egressIp,
            isRegionVerified = if (detectedCountryCode != null) true else node.isRegionVerified
        )
    }
}
```

---

### 4.4 节点按国家分组与应用级国家分流编译器 (`CountryRoutingCompiler`)

本模块完整实现**“节点按国家自动归类；自定义给部分软件走指定国家（如 AI 软件走美国、动漫软件走日本），另一些软件走其他国家，其余未设置的软件统一走默认国家”**。

#### 1. 多国出站组（Outbounds）动态构建逻辑
编译器在生成 `sing-box` 运行配置时，自动扫描数据库中所有可用节点并按 `countryCode` 聚合：
1. 对每个存在的国家代码 `{CC}`（如 `US`、`JP`、`SG`、`HK`），自动生成两个组合出站：
   * `auto-{CC}`：类型为 `urltest`，成员包含该国家下 `healthScore` 排名前 $10$ 且未处于冷却期的节点，负责该国组内的底层自动测速优选。
   * `group-{CC}`：类型为 `selector`，成员列表第 1 项为 `auto-{CC}`，后续为该国所有节点（按收藏置顶 $\rightarrow$ 健康分降序排列）。
2. 生成收藏夹专属出站组 `group-FAVORITES`（汇总所有 `isFavorite == true` 的节点）。
3. 生成默认兜底出站组 `proxy-default`：将其绑定到用户设定的**默认国家组**（例如用户设置默认国家为新加坡，则 `proxy-default` 默认指向 `group-SG`；若用户未限定默认国家，则指向汇总全网高分节点的 `group-GLOBAL`）。

#### 2. 应用级国家分流规则编译代码实现

```kotlin
package com.yangche.singflow.core.config

import com.yangche.singflow.data.local.entity.AppRouteRuleEntity
import kotlinx.serialization.json.*

object AppCountryRouteCompiler {

    /**
     * 将用户的应用级国家分流表编译为 sing-box 的 route.rules 数组
     * @param rules 用户配置的应用分流规则列表
     * @param availableCountryCodes 当前节点库中实际拥有可用节点的国家代码集合
     * @param defaultCountryGroupTag 默认兜底国家组 Tag（如 "group-SG" 或 "proxy-default"）
     */
    fun buildAppRouteRules(
        rules: List<AppRouteRuleEntity>,
        availableCountryCodes: Set<String>,
        defaultCountryGroupTag: String
    ): List<JsonObject> {
        val resultRules = mutableListOf<JsonObject>()

        // 1. 直连应用合并成单条规则（减少内核匹配开销）
        val directPackages = rules.filter { it.routeMode == "DIRECT" }.map { it.packageName }
        if (directPackages.isNotEmpty()) {
            resultRules += buildJsonObject {
                putJsonArray("package_name") { directPackages.forEach { add(it) } }
                put("outbound", "direct-out")
            }
        }

        // 2. 断网阻断应用合并
        val blockPackages = rules.filter { it.routeMode == "BLOCK" }.map { it.packageName }
        if (blockPackages.isNotEmpty()) {
            resultRules += buildJsonObject {
                putJsonArray("package_name") { blockPackages.forEach { add(it) } }
                put("outbound", "block-out")
            }
        }

        // 3. 走收藏节点池的应用合并
        val favPackages = rules.filter { it.routeMode == "FAVORITES" }.map { it.packageName }
        if (favPackages.isNotEmpty()) {
            resultRules += buildJsonObject {
                putJsonArray("package_name") { favPackages.forEach { add(it) } }
                put("outbound", "group-FAVORITES")
            }
        }

        // 4. 按目标国家分组聚合应用包名（例如所有被指定走 US 的 App 合并为一条 rule）
        val countryGrouped = rules
            .filter { it.routeMode == "COUNTRY" && it.targetCountryCode.isNotBlank() }
            .groupBy { it.targetCountryCode.uppercase() }

        for ((countryCode, appList) in countryGrouped) {
            val outboundTag = if (availableCountryCodes.contains(countryCode)) {
                "group-$countryCode"
            } else {
                // 容灾降级保护：若用户指定某 App 走日本，但当前订阅中没有任何日本节点，自动回退走默认国家，防止该 App 断网
                defaultCountryGroupTag
            }
            resultRules += buildJsonObject {
                putJsonArray("package_name") { appList.forEach { add(it.packageName) } }
                put("outbound", outboundTag)
            }
        }

        return resultRules
    }
}
```

---

### 4.5 节点不可用自动切换方案（预设大方向 + 细节微调）

为兼顾普通用户的“开箱即用”与高阶玩家对切换灵敏度的精确把控，自动切换控制器 `FailoverController` 采用**“四大预设方向 + 七项微调参数 + 本机断网前置甄别”**架构。

#### 1. 四大预设切换方向（用户一键单选）

| 预设模式枚举 | 中文名称与定位 | 核心切换与降级方向逻辑 |
| :--- | :--- | :--- |
| `COUNTRY_LOCKED_HEALTH` | **同国锁定 · 健康优先（默认推荐）** | 当节点不可用时，**严格在同一个国家分组内**寻找替代节点，优先切往同国家内 `healthScore` 最高且未冷却的节点；仅当该国所有节点全部不可用时，才按用户设定的“备用国家顺序（如 `SG -> JP -> HK -> US`）”跨国容灾。适合流媒体与 AI 账号防风控。 |
| `FAVORITES_FIRST_FAST` | **收藏池优先 · 极速容灾** | 优先在用户打星标的 `isFavorite == true` 节点池中按健康分与实时低延迟切换；仅当收藏池全部不可用时，才降级扩展至全量节点中 `healthScore >= 75` 的高分池。 |
| `GLOBAL_LOWEST_LATENCY` | **全域极速 · 低延迟竞技** | 打破默认国家边界（已单独绑定特定国家的 App 依然维持其国家分流），在全网所有健康节点中实时追踪 RTT 最低的节点；一旦当前节点延迟超标或丢包，立即无感切往全网最快节点。 |
| `STABLE_HYSTERESIS_ONLY` | **稳健防抖 · 仅断网保底** | 平时锁定当前连接节点，**完全关闭因延迟波动触发的择优切换**；仅当当前节点彻底断连（连续 $K$ 次探测全部超时）时，才执行一次性故障转移，防止频繁更换出口 IP 导致 SSH/金融软件重连。 |

#### 2. 用户可精细把控的七项底层参数（高级折叠面板）

```kotlin
package com.yangche.singflow.core.failover

import kotlinx.serialization.Serializable

enum class FailoverPreset {
    COUNTRY_LOCKED_HEALTH,
    FAVORITES_FIRST_FAST,
    GLOBAL_LOWEST_LATENCY,
    STABLE_HYSTERESIS_ONLY
}

@Serializable
data class FailoverConfig(
    val enabled: Boolean = true,
    val preset: FailoverPreset = FailoverPreset.COUNTRY_LOCKED_HEALTH,

    // 参数 1：亮屏状态下的主动巡检周期（秒），支持 30 / 60 / 180 / 300 / 0(仅被动异常触发)
    val activeCheckIntervalSec: Int = 180,

    // 参数 2：单次探活超时判定阈值（毫秒，范围 1000 ~ 5000）
    val singleProbeTimeoutMs: Int = 3000,

    // 参数 3：连续失败确认次数（范围 1 ~ 5，默认连续 2 次超时才判定节点故障，防偶发丢包误判）
    val failConfirmRetries: Int = 2,

    // 参数 4：高延迟慢速熔断阈值（毫秒，即便没断网，若连续测速高于此值也触发切换，范围 400 ~ 2000）
    val slowLatencyThresholdMs: Int = 850,

    // 参数 5：择优切换迟滞防抖温差（候选节点必须比当前节点健康分高出 ΔScore 且延迟快 ΔMs 才触发非故障择优切换）
    val switchHysteresisScoreDiff: Int = 12,
    val switchLatencyToleranceMs: Int = 80,

    // 参数 6：故障节点冷却隔离时长（分钟，被打入小黑屋的节点在此期间不参与自动切换，范围 1 ~ 60）
    val deadNodeCooldownMinutes: Int = 10,

    // 参数 7：同国节点全灭时的跨国备用顺序与通知开关
    val allowCrossCountryFallback: Boolean = true,
    val fallbackCountryOrder: List<String> = listOf("SG", "JP", "HK", "US", "TW", "KR"),
    val showToastOnSwitch: Boolean = false
)
```

#### 3. 自动切换核心执行闭环（含本机断网防误杀甄别）

```kotlin
package com.yangche.singflow.core.failover

import com.yangche.singflow.core.box.BoxServiceManager
import com.yangche.singflow.core.probe.DirectNetworkVerifier
import com.yangche.singflow.core.probe.SingleNodeProber
import com.yangche.singflow.core.scorer.NodeHealthScorer
import com.yangche.singflow.data.local.dao.NodeDao
import com.yangche.singflow.data.local.entity.NodeEntity
import kotlinx.coroutines.delay

class FailoverController(
    private val nodeDao: NodeDao,
    private val boxManager: BoxServiceManager,
    private val prober: SingleNodeProber
) {
    /**
     * 对指定国家组或默认组的当前活跃节点执行健康校验与按需容灾切换
     */
    suspend fun checkAndFailoverIfNeeded(
        groupTag: String,
        currentNode: NodeEntity,
        config: FailoverConfig
    ): NodeEntity? {
        if (!config.enabled) return null

        // 第 1 步：含重试确认的当前节点探活
        var isCurrentAlive = false
        var measuredLatency = -1
        for (attempt in 1..config.failConfirmRetries) {
            val res = prober.probeNode(currentNode, timeoutMs = config.singleProbeTimeoutMs)
            if (res.isSuccess && res.latencyMs <= config.slowLatencyThresholdMs) {
                isCurrentAlive = true
                measuredLatency = res.latencyMs
                break
            }
            if (attempt < config.failConfirmRetries) delay(400L)
        }

        // 第 2 步：若当前节点探测失败，先执行【本机直连网络甄别】
        if (!isCurrentAlive) {
            val isLocalNetOk = DirectNetworkVerifier.isCaptivePortalReachable(timeoutMs = 1500)
            if (!isLocalNetOk) {
                // 手机自身处于电梯/地下室/断网状态 -> 绝不扣减节点健康分，也不执行乱切！
                return null
            }

            // 坐实代理节点故障：更新健康分并打入冷却期
            val penalizedNode = NodeHealthScorer.evaluateAfterProbe(
                node = currentNode,
                isSuccess = false,
                latencyMs = -1,
                cooldownMinutesOnDead = config.deadNodeCooldownMinutes
            )
            nodeDao.updateNode(penalizedNode)
            return selectAndSwitchToBestCandidate(groupTag, penalizedNode, config, isEmergency = true)
        } else {
            // 当前节点正常：更新成功健康分
            val updatedCurrent = NodeHealthScorer.evaluateAfterProbe(
                node = currentNode,
                isSuccess = true,
                latencyMs = measuredLatency
            )
            nodeDao.updateNode(updatedCurrent)

            // 若处于“稳健防抖模式”，当前节点没死就绝不切换
            if (config.preset == FailoverPreset.STABLE_HYSTERESIS_ONLY) return null

            // 非故障态下的迟滞防抖择优检查
            return selectAndSwitchToBestCandidate(groupTag, updatedCurrent, config, isEmergency = false)
        }
    }

    private suspend fun selectAndSwitchToBestCandidate(
        groupTag: String,
        currentNode: NodeEntity,
        config: FailoverConfig,
        isEmergency: Boolean
    ): NodeEntity? {
        val now = System.currentTimeMillis()
        val allHealthy = nodeDao.getHealthyCandidateNodes(nowMs = now, minScore = 45)
            .filter { it.id != currentNode.id }

        if (allHealthy.isEmpty()) return null

        // 按预设大方向筛选候选池
        val pool: List<NodeEntity> = when (config.preset) {
            FailoverPreset.COUNTRY_LOCKED_HEALTH -> {
                val sameCountry = allHealthy.filter { it.countryCode == currentNode.countryCode }
                if (sameCountry.isNotEmpty() || !config.allowCrossCountryFallback) {
                    sameCountry
                } else {
                    // 同国全灭：按备用国家顺序查找
                    config.fallbackCountryOrder.firstNotNullOfOrNull { fallbackCode ->
                        allHealthy.filter { it.countryCode == fallbackCode }.ifEmpty { null }
                    } ?: allHealthy
                }
            }
            FailoverPreset.FAVORITES_FIRST_FAST -> {
                allHealthy.filter { it.isFavorite }.ifEmpty { allHealthy.filter { it.healthScore >= 75 } }
            }
            FailoverPreset.GLOBAL_LOWEST_LATENCY -> {
                allHealthy.sortedBy { if (it.lastLatencyMs > 0) it.lastLatencyMs else 9999 }
            }
            FailoverPreset.STABLE_HYSTERESIS_ONLY -> {
                allHealthy.filter { it.countryCode == currentNode.countryCode }.ifEmpty { allHealthy }
            }
        }

        // 取排名前 3 的候选节点并发实测，确保切过去的节点 100% 在线
        val topCandidates = pool.take(3)
        for (candidate in topCandidates) {
            val probeRes = prober.probeNode(candidate, timeoutMs = config.singleProbeTimeoutMs)
            if (probeRes.isSuccess) {
                val verifiedCandidate = NodeHealthScorer.evaluateAfterProbe(candidate, true, probeRes.latencyMs)
                nodeDao.updateNode(verifiedCandidate)

                // 若非紧急断网切换，需满足迟滞防抖温差门槛才允许切换
                if (!isEmergency) {
                    val scoreGain = verifiedCandidate.healthScore - currentNode.healthScore
                    val latencyGain = currentNode.lastLatencyMs - probeRes.latencyMs
                    if (scoreGain < config.switchHysteresisScoreDiff || latencyGain < config.switchLatencyToleranceMs) {
                        return null
                    }
                }

                // 调用 libbox Selector 接口在内存态毫秒级无感切换出站，不重建 TUN 网卡
                boxManager.selectOutboundInGroup(groupTag, "node-${verifiedCandidate.id}")
                return verifiedCandidate
            } else {
                nodeDao.updateNode(NodeHealthScorer.evaluateAfterProbe(candidate, false, -1))
            }
        }
        return null
    }
}
```

---

### 4.6 补充高实用价值功能模块设计

除上述指定特性外，为提升软件日常易用性，补充设计以下 5 项高频刚需功能：

1. **实时分流命中与连接诊断面板（Connection Inspector）**
   * **痛点**：用户配置了“某软件走美国、某软件走日本”，但不知道实际上有没有生效。
   * **方案**：在设置页提供“实时连接诊断”入口，展示当前活跃的最近 $50$ 条连接：`[应用图标 + 中文名] -> [目标域名/IP] -> [命中的分流规则：走日本组 group-JP] -> [实际落地节点名 + 实时延迟]`。支持长按任意一条连接弹出菜单：“将该应用改为走其他国家”或“将该域名加入直连白名单”。
2. **网络环境智能自动化（Wi-Fi SSID 联动策略）**
   * 支持配置“受信任 Wi-Fi 列表（如家里已配置旁路由的 Wi-Fi）”：当检测到手机连上指定 SSID 时自动断开 VPN 或切为直连模式；当离开家切入 5G 蜂窝网络或连接公共陌生 Wi-Fi 时自动开启 SingFlow 代理保护。
3. **精选高分节点集一键导出与二维码分享**
   * 在节点页提供“导出精选池”功能：可将当前“所有收藏节点”或“健康分 $\ge 80$ 分的节点”一键导出为通用 Base64 订阅文本、标准 `sing-box` JSON 文件或直接生成屏幕二维码，方便分享给备用机或平板扫码导入。
4. **本地加密备份与 WebDAV 云同步**
   * 将订阅源、收藏标记、积累的节点健康度数据、应用级国家分流规则打包为单个 Gzip 压缩 JSON 文件，支持本地一键导出恢复，以及通过坚果云 / Nextcloud WebDAV 自动云端同步。
5. **Android 下拉状态栏快捷磁贴（`SingFlowTileService`）**
   * 实现系统级 `TileService`：用户无需打开 App，下拉通知栏即可一键开关代理；已连接状态下，磁贴副标题直接显示 `当前默认国家 · 实时延迟`（如 `新加坡 · 38ms`）。

---

## 五、 全方位软件稳定性加固工程（Stability Engineering）

为确保客户端在 Android 13 $\sim$ 16+ 系统上长期挂载不闪退、不断流、不假死，实施以下六大底层稳定性工程。

### 5.1 物理网络切换无缝重绑（根治 Wi-Fi 切 5G 后的 `egress_timeout`）
当手机在 Wi-Fi 与蜂窝移动网络之间切换时，物理网卡接口发生变化，若旧 Socket 仍停留在失效网卡上，会导致所有连接陷入死锁超时。

```kotlin
package com.yangche.singflow.core.box

import android.net.*
import android.net.VpnService
import kotlinx.coroutines.*

class UnderlyingNetworkTracker(
    private val vpnService: VpnService,
    private val scope: CoroutineScope,
    private val onPhysicalNetworkChanged: suspend (Network?) -> Unit
) {
    private val connectivityManager = vpnService.getSystemService(ConnectivityManager::class.java)
    private var debounceJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 1. 立即告知 Android 内核将此物理网络绑定为 VPN 底层承载网络
            vpnService.setUnderlyingNetworks(arrayOf(network))
            // 2. 250ms 防抖后通知 libbox 刷新连接池并立即触发一次活跃节点探活
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(250L)
                onPhysicalNetworkChanged(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                vpnService.setUnderlyingNetworks(arrayOf(network))
            }
        }

        override fun onLost(network: Network) {
            vpnService.setUnderlyingNetworks(null)
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun unregister() {
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }
}
```

### 5.2 严格校验 `VpnService.protect(fd)` 防止回环风暴
在实现 `libbox.PlatformInterface.autoDetectInterfaceControl(fd: Int)` 时，必须强校验返回值：
```kotlin
override fun autoDetectInterfaceControl(fd: Int) {
    val protected = vpnService.protect(fd)
    if (!protected) {
        throw IllegalStateException("VpnService.protect(fd=$fd) 失败，已阻断连接以防止 TUN 回环死锁")
    }
}
```

### 5.3 内核生命周期互斥锁与零重建热切换
* **协程互斥锁 (`Mutex`)**：在 `BoxServiceManager` 中使用全局唯一的 `lifecycleMutex = Mutex()` 包裹 `start()`、`stop()`、`reload()`，彻底杜绝用户快速连点开关导致两个 Go runtime 实例同时争抢同一个 TUN 文件描述符引发 Native 崩溃（`SIGSEGV`）。
* **内存态热切换**：节点自动切换或手动切换时，一律通过 `libbox` 的 `CommandClient` / `Selector` API 在内存中切换出站指针，**严禁断开重建 `VpnService` 的 TUN 网卡**，从而保障微信语音、游戏或下载长连接不断流。

### 5.4 Go Native 内存水位硬限制（防 LMK 杀后台）
Go 语言的垃圾回收器（GC）默认按翻倍策略申请堆内存，在手机内存紧张时极易被 Android LMK（Low Memory Killer）优先强杀。
* **解决方案**：
  1. 在 `BoxApplication.onCreate()` 初始化 `libbox` 时，调用 `Libbox.setMemoryLimit(true)`，将 Go 运行时内存水位严格限制在 **$45\text{ MB}$** 以内。
  2. 在生成的 `sing-box` 配置中将 `udp_timeout` 从默认的 `"5m"` 缩短为 `"90s"`，及时释放高并发 UDP 会话占用的内存与文件描述符（FD）。

### 5.5 适配 Android 14 $\sim$ 16+ 前台服务强制规范
* 在 `AndroidManifest.xml` 中显式声明权限与服务类型：
  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

  <service
      android:name=".core.box.SingFlowVpnService"
      android:exported="false"
      android:permission="android.permission.BIND_VPN_SERVICE"
      android:foregroundServiceType="specialUse">
      <intent-filter>
          <action android:name="android.net.VpnService" />
      </intent-filter>
      <property
          android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
          android:value="vpn_proxy_tunnel" />
  </service>
  ```
* 在 `SingFlowVpnService.onStartCommand()` 入口的第 1 行立即调用 `ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`，杜绝超时引发的 `ForegroundServiceDidNotStartInTimeException`。

### 5.6 配置文件的原子化写入 (`AtomicFile`)
所有运行期配置文件（如 `active_config.json`）及本地缓存写入均使用 `androidx.core.util.AtomicFile`，确保即使写入瞬间进程被杀或手机断电，也不会产生半截损坏的 JSON 导致下次启动失败。

---

## 六、 极致耗电优化设计（Power Optimization）

VPN 软件耗电的三大根源是：**息屏期间的高频定时网络探活（阻止手机 5G/Wi-Fi 基带芯片进入深度休眠态）**、**后台无效的 UI 速率轮询**以及**跨 JNI 边界的高频日志字符串拷贝**。

### 6.1 屏幕与前后台感知的三级功耗状态机 (`PowerStateController`)

通过监听 `Intent.ACTION_SCREEN_ON`、`Intent.ACTION_SCREEN_OFF`、`PowerManager.ACTION_POWER_SAVE_MODE_CHANGED` 以及 `ProcessLifecycleOwner`，动态调节全系统的采样与探活频率：

| 功耗档位 | 触发条件 | 速率采样与主页曲线渲染 | 自动探活与容灾巡检策略 | 通知栏刷新与日志流 |
| :--- | :--- | :--- | :--- | :--- |
| **L1：前台活跃态** | App 处于前台可见且屏幕点亮 | 每 $1000\text{ ms}$ 采样一次上下行字节差值，驱动主页平滑曲线 | 按用户配置的周期（如 $180\text{ s}$）正常巡检 + 被动异常触发 | 正常更新；仅当打开“日志/诊断页”时挂载 JNI 日志流 |
| **L2：后台亮屏态** | 用户切换到其他软件使用手机，SingFlow 在后台 | **立即挂起 UI 速率曲线协程**（零 Compose 重组消耗） | 暂停非活跃节点扫描，仅对当前正在承载流量的节点做被动异常监控 | 若开启通知栏速率显示，更新间隔拉长至 $3000\text{ ms}$ |
| **L3：息屏深度休眠态** | 屏幕关闭（`ACTION_SCREEN_OFF`）满 $15\text{ s}$ 或开启系统省电模式 | **100% 挂起所有速率统计协程** | **100% 停止所有定时主动探活任务**，让手机基带芯片彻底进入休眠态；仅在下次亮屏瞬间执行一次快速唤醒复检 | **完全停止 `NotificationManager.notify()` 调用**，消除 SystemUI 进程被频繁唤醒的耗电 |

### 6.2 生产环境日志熔断与环形内存缓冲
* **默认日志级别下调**：在 `SingBoxConfigBuilder` 中，日常连接的 `"log": { "level": "warn", "disabled": false }` 强制设为 `warn` 级别，禁止每转发一个域名就向磁盘写入一行 `info/debug` 日志。
* **按需订阅日志流**：仅当用户进入设置页的“实时日志排查”界面时，才向 `libbox` 注册日志回调并写入固定容量为 $200$ 行的内存 `ArrayDeque` 环形缓冲区；用户一退出该页面立即注销回调。

---

## 七、 安装包（APK）极限瘦身与中文单语言构建配置

针对**仅需支持中文**的定位，通过 **Go 内核模块裁剪 + 编译符号剥离 + 中文单语言资源过滤 + R8 深度压缩 + Native 库 Deflate 高倍率压缩 + 外部规则按需加载**，可将正式版 APK 体积缩减 **60% 以上**。

### 7.1 改造 `.github/workflows/build-libbox.yml` 裁剪内核体积

在编译 `libbox.aar` 时，剔除服务端及冷门冗余模块（如 `with_dhcp`、`with_tor`、`with_acme`、`with_lwip`），并启用 `-s -w -trimpath` 剥离调试符号：

```yaml
- name: Build size-optimized libbox.aar (arm64-v8a)
  run: |
    # 仅保留客户端抗封锁、现代协议与 TUN 核心必需的 Tag
    BUILD_TAGS="with_gvisor,with_quic,with_utls,with_wireguard,with_ech,with_clash_api"
    
    # -s: 移除符号表; -w: 移除 DWARF 调试信息; -buildid=: 清空构建ID; -trimpath: 移除编译路径字符串
    gomobile bind -v -androidapi 33 -target android/arm64 \
      -tags "$BUILD_TAGS" \
      -trimpath \
      -ldflags="-s -w -buildid=" \
      -o app/libs/libbox.aar \
      ./experimental/libbox
```

### 7.2 改造 `app/build.gradle.kts`（中文单语言 + 极限压缩配置）

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yangche.singflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yangche.singflow"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        // 1. 仅打包主流 64 位真机架构，剔除冗余的 x86/x86_64/armeabi-v7a 库
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 2. 仅保留中文语言资源，彻底剔除 AndroidX / Material3 附带的 80+ 种外国语言字符串
    androidResources {
        localeFilters += listOf("zh", "zh-rCN")
    }

    buildTypes {
        release {
            // 3. 开启 R8 深度代码混淆、无用类移除与无用资源物理裁剪
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs {
            // 4. 核心体积优化：启用 Legacy 压缩打包，对 libgojni.so 进行高倍率压缩
            // 可将最终生成的 APK 下载体积直接缩减 12MB ~ 18MB
            useLegacyPackaging = true
        }
        resources {
            // 5. 移除所有无用的编译期元数据、许可证文本与调试探针
            excludes += setOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }
}
```

### 7.3 改造 `app/proguard-rules.pro` 混淆与瘦身规则

在 `gradle.properties` 中确保开启 `android.enableR8.fullMode=true`，并在 `proguard-rules.pro` 中写入：

```proguard
# 1. 严格保护 libbox.aar 的 JNI 桥接类与 Go 回调接口不被混淆（防 Release 包闪退）
-keep class io.nekohasekai.libbox.** { *; }
-keep class libbox.** { *; }
-keep class go.** { *; }

# 2. 保护 Kotlinx Serialization 与 Room 实体类
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.yangche.singflow.** {
    *** Companion;
}
-keepclasseswithmembers class com.yangche.singflow.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# 3. 在 Release 字节码中彻底抹除所有 Log.d / Log.v 调试调用
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# 4. 合并短方法与重打包以极致压缩 DEX 体积
-repackageclasses 'sf'
-allowaccessmodification
```

### 7.4 外置精简二进制规则集（零 `assets` 体积占用）
* **严禁将传统的 `geoip.db` 和 `geosite.db`（约 $18\text{ MB}$）打包进 `app/src/main/assets`**。
* 采用 `sing-box` 专属的高压缩二进制规则集（Rule-Set `.srs`）：仅需 `geoip-cn.srs`（约 $90\text{ KB}$）和 `geosite-cn.srs`（约 $480\text{ KB}$），合计不足 $600\text{ KB}$，在首次启动或后台静默拉取并缓存在 `context.noBackupFilesDir/rules/` 目录下。

---

## 八、 UI 交互细节规范与落地开发里程碑

### 8.1 全中文三屏极简交互规范（HorizontalPager）
1. **中屏（主页 Home）**：
   * 顶部：应用标题“SingFlow”与当前运行状态胶囊徽章（`未连接` / `已保护 · 新加坡 38ms`）。
   * 中部：超大尺寸圆形呼吸波纹一键连接按钮，底部显示当前承载节点名称、国家国旗与健康度徽章（如 `[极佳 94分]`）。
   * 下部：实时上下行速率平滑贝塞尔面积图（仅在前台活跃态 L1 渲染）+ 本次会话与历史累计上下行流量统计卡片。
2. **左滑（节点管理页 Nodes）**：
   * 顶部操作栏：`[批量导入/订阅]`、`[一键批量探活]`、`[批量检测落地国家]`、搜索过滤框。
   * 分组折叠列表：
     * 第一置顶组：`⭐ 我的收藏 (N)`
     * 后续国家分组：按 `🇭🇰 中国香港 (12)`、`🇸🇬 新加坡 (8)`、`🇯🇵 日本 (15)`、`🇺🇸 美国 (10)` 自动折叠/展开。
   * 节点卡片要素：左侧国家/协议角标、节点名称、右侧显示彩色健康度标签（`极佳 92分` / `健康 78分` / `异常 32分`）、实测 RTT 延迟（`42 ms`）、星标收藏按钮。
3. **右滑（设置与分流页 Settings）**：
   * **卡片 1：默认国家与分流路由**：一键选择全局“默认国家（未单独设置的应用统一走该国家）”，点击进入**“按应用自定义国家分流”**可视化列表（支持搜索应用、按已分流应用置顶，右侧下拉直接给任意 App 选择 `美国 / 日本 / 新加坡 / 香港 / 收藏组 / 直连 / 断网`）。
   * **卡片 2：故障自动切换策略**：顶部单选四大预设模式（`同国锁定·健康优先`、`收藏池优先`、`全域低延迟`、`稳健防抖`），下方点击展开“细节参数微调滑块”（探测周期、超时阈值、连续失败次数、防抖温差、小黑屋冷却时间）。
   * **卡片 3：抗封锁与安全 DNS**：uTLS 浏览器指纹选择、TLS ClientHello 分片开关、多路复用 Padding 开关、FakeIP 防 DNS 逃逸开关。
   * **卡片 4：连接诊断与备份**：实时分流命中监控面板、配置导入导出/WebDAV 同步。

### 8.2 四阶段开发落地与验收清单（Milestones）

| 阶段 | 核心交付模块 | 自动化验收标准 |
| :--- | :--- | :--- |
| **Phase 1：体积瘦身与稳定性底座** | 完成 `.github/workflows/build-libbox.yml`、`app/build.gradle.kts`、`proguard-rules.pro` 改造；完成 `SingFlowVpnService` 的 `NetworkCallback` 物理网络重绑、`protect(fd)` 强校验与 `Mutex` 互斥锁。 | `./gradlew assembleRelease` 产物仅含中文与 `arm64-v8a`；Wi-Fi 与 5G 反复切换 $10$ 次无 `egress_timeout`。 |
| **Phase 2：数据层与导入/探活/评分引擎** | 完成 Room 三张核心表与 DAO；实现 `NodeImportParser`（含名称 URL 解码清洗）、`BatchProbeEngine`（12 并发信号量 + Cloudflare Trace 落地国家识别）与 `NodeHealthScorer` 数学评分模型。 | 批量导入 $50+$ 含特殊字符节点无乱码；一键探活与地区检测后准确写入 `countryCode`、`healthScore` 与四级中文标签。 |
| **Phase 3：应用级国家分流与自动切换** | 完成 `SingBoxConfigBuilder` 与 `AppCountryRouteCompiler`；完成 `FailoverController`（四大预设 + 七项参数 + 本机直连防误杀甄别 + Selector 内存无感切换）。 | 指定 App A 走美国、App B 走日本、其余走默认国家均准确命中；模拟当前节点断连可在 $3\text{ s}$ 内无感切往同国最高健康分节点且不重建 TUN。 |
| **Phase 4：三屏中文 UI 与三级省电状态机** | 完成主页、国家分组节点页、应用国家分流器与自动切换微调面板 UI；挂载 `PowerStateController` 三级省电状态机与快捷磁贴。 | 息屏满 $15\text{ s}$ 后后台定时探活与 UI 刷新 $100\%$ 挂起；Release 包无任何混淆崩溃与 ANR。 |