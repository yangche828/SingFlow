# SingFlow — Agent 全局规则与架构契约

> 本文件由 Phase 0 自动生成，面向 OpenCode / Codex 等 Agent。任何修改必须遵守以下铁律。

## 1. 项目定位

SingFlow 是一个基于 Jetpack Compose + sing-box 1.14+ 的 Android VPN 客户端。
包名：`com.singflow.android`。支持 Android 13 (API 33) ~ Android 17 (API 37)。

## 2. Android 13~17 兼容红线

| 维度 | 强制要求 |
| :--- | :--- |
| SDK | `minSdk = 33`；`compileSdk` / `targetSdk` 动态取本机已安装最高平台（37 → 36 → 35） |
| 16KB Page Size | `packaging.jniLibs.useLegacyPackaging = false`；AGP 8.7.2+；原生库需 `-Wl,-z,max-page-size=16384` |
| 前台服务 | Manifest 声明 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` 与 `FOREGROUND_SERVICE_SPECIAL_USE`；`foregroundServiceType="systemExempted|specialUse"`；`ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)` |
| Edge-to-Edge | `MainActivity.enableEdgeToEdge()`；禁止写死 `screenOrientation` / `resizableActivity="false"`；Compose 使用 `Scaffold` + `WindowInsets.safeDrawing` |
| 通知 | 动态申请 `POST_NOTIFICATIONS`，未授权不阻塞 VPN 核心启动 |
| 广播 | 动态注册必须 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` |
| 分应用 | 始终 `addDisallowedApplication(packageName)` 排除自身，并 `protect(fd)` 防回环 |

## 3. sing-box 1.14+ / 1.15+ 六大语法红线（禁止出现旧写法）

1. `dns.servers` 必须强类型：`https` / `udp` / `local` / `fakeip`，禁止 `address: "https://..."` 或 `address: "local"`。
2. 禁止 `dns.rules` 的 `outbound: "any"` 与节点 `domain_strategy`；必须使用 `route.default_domain_resolver` 与节点 `domain_resolver`。
3. 禁止 `outbounds` 中出现 `type: "block"` / `type: "dns"`；使用规则动作 `hijack-dns` / `reject`。
4. 禁止 inbound 内写 `sniff` / `sniff_override_destination`；必须在 `route.rules[0]` 写 `{"action":"sniff","timeout":"300ms"}`。
5. TUN 必须使用 `address: ["172.19.0.1/30", ...]`，禁止 `inet4_address`；不硬编码 gvisor stack。
6. 禁止 `store_rdrc`；使用 `store_dns`；分流使用 `ip_is_private` 与远程 `.srs` `rule_set`，禁止 `geoip: "cn"` 内联写法。

## 4. 禁止项

- 严禁 `TODO`、`FIXME`、`...省略`、未实现方法体、占位字符串。
- 严禁在 `main` 源码中耦合 `android.util.Base64` 的解析逻辑（单测需在 JVM 运行，使用 `java.util.Base64`）。
- 严禁在 `SingFlowVpnService` 之外调用 `establishTun`。
- 严禁把 `libbox.aar` 作为编译期硬依赖；必须通过 `LibboxBridge` 反射双模。

## 5. 架构分层（零重构扩展点）

```
core/
  model/     领域模型（ProtocolType / ProxyNode / TransportConfig / TlsConfig / AppSettings / VpnState）
  database/  Room + DataStore + Hilt Module
  parser/    NodeLinkParser 职责链 + impl/ 七大协议 + UniversalImportManager
  config/    SingBoxConfigPipeline + ConfigInterceptor 拦截器链
  engine/    LibboxBridge / SingFlowVpnService / TrafficAndLogMonitor / NodeLatencyTester / QuickTileService
ui/
  theme/ components/ screens/
```

扩展新协议：实现 `NodeLinkParser` 并注册到 `UniversalImportManager.defaultParsers()`。
扩展新配置能力：实现 `ConfigInterceptor` 并注册到 `SingBoxConfigPipeline.defaultInterceptors()`。
扩展新页面：在 `ui/screens/` 新增 Composable，并在 `SingFlowNavHost` 注册路由。

## 6. 自检门禁

| Phase | 命令 | 通过标准 |
| :--- | :--- | :--- |
| 1 | `./gradlew :app:kspDebugKotlin` | KSP 生成成功、无编译错误 |
| 2 | `./gradlew :app:testDebugUnitTest --tests "*UniversalParserTest"` | 全绿 |
| 3 | `./gradlew :app:testDebugUnitTest` | 全绿 + 无废弃字段 |
| 4 | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` | 全绿 |
| 5 | `./gradlew :app:assembleDebug` | APK > 5MB |
| 6 | `scripts/e2e_adb_verify` | 安装成功、零 FATAL EXCEPTION |

## 7. 编码风格

- Kotlin 2.0.21，JVM Target 17，coroutines 结构化并发。
- 所有网络/磁盘操作必须在 `Dispatchers.IO` 或注入的 IO dispatcher 上执行。
- Compose 状态使用 `StateFlow` + `collectAsStateWithLifecycle()`。
- 新增公开类必须带 KDoc，注释使用中文。
