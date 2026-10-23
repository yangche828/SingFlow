# SingFlow 功能增强 v1 — 交接报告

> 生成时间：2026-10-23（Asia/Shanghai）
> 仓库：`D:\Workspace\Github\SingFlow`
> 分支 / HEAD：`main` / `1e4ac59`（`docs(v2-r48): 最终完成审计`）
> 需求来源：`singflow_android_function_enhancement_v1.md`（v2.0）
> 用途：给下一个接手的模型/开发者提供一份**只依赖仓库事实**的现状说明。

---

## 0. 先读什么、先做什么

接手顺序建议：

1. `AGENTS.md`：Android 13–17、16KB page、前台服务与 sing-box 1.14 六条红线。
2. `TASK_PROGRESS.md`：R1–R48 的逐轮实现、修复与验收记录，含大量运行时证据。
3. 本报告：当前完成度、未完成项、代码入口、测试环境与风险。
4. `HANDOFF.md`：**已过期**（仍写着 31 tests / v1.0.1），不要按它的数字判断现状；以本报告和 `git log` 为准。

先跑三条命令确认环境：

```powershell
$env:JAVA_HOME = 'D:\Apps\JDK\jdk-21'
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleRelease
```

期望结果（2026-10-23 已实际验证）：

- 141 个单元测试，0 failures。
- release 构建成功，`app/build/outputs/apk/release/app-release.apk` = 84,951,708 bytes。
- SHA-256：`DD69F3E53A5072A4A4F2EB8D248204B6E0D2D8DB6E4BB24E6540DA9835150589`。

---

## 1. 当前状态快照

| 项 | 值 |
| :--- | :--- |
| 包名 | `com.singflow.android` |
| versionCode / versionName | `2` / `2.0.0` |
| minSdk / targetSdk | 33 / 36（compileSdk 36；AGENTS 要求动态取本机最高平台） |
| ABI | 仅 `arm64-v8a` |
| locale | `--_--`、`zh-CN`（仅中文） |
| 16KB page | `zipalign -c -P 16 -v 4` 通过 |
| 测试 | 141 tests / 0 failures（`testDebugUnitTest`） |
| 主源码 | 111 个 `.kt`，27 个测试 `.kt` |
| 工作区 | 生成时源码无未提交改动；需求 PDF `SingFlow功能迭代增强V1.pdf` 未跟踪（输入资料，不建议提交） |
| 提交总数 | 58（含 v1 历史）；R1–R48 功能提交 48 个 |

> 注：release APK 是 R47 构建产物，R48 只增加文档与审计，未重新打包；代码与 APK 对应同一 HEAD 的源码。

---

## 2. 需求实现矩阵

### 2.1 已完成（核心）

| 领域 | 已实现内容 | 主要代码 / 证据 |
| :--- | :--- | :--- |
| 数据层 | Room 节点/订阅/应用规则表，DataStore 设置，Hilt 注入 | `core/database/`；R1 |
| 导入解析 | 7 个链接解析器（VLESS/VMess/Trojan/SS/Hysteria2/TUIC/WireGuard）+ CUSTOM_JSON outbound 支持、名称 URL 解码清洗、批量导入、通用导入 | `core/parser/`；R1 |
| 节点探活 | 并发探活（12 并发信号量）、真实延迟、失败原因、批量结果写回 | `core/probe/`；R2 |
| 健康评分 | 延迟/失败/抖动综合评分、四级中文标签、慢速节点判定 | `core/scorer/`；R1/R5 |
| GeoIP | 逐节点切换 Selector + Cloudflare Trace 实测出口国家/地区，写回国家与验证标记 | `MainViewModel.scanGeoIp()`；R15（`acceptance/r15_*`） |
| 配置编译 | `SingBoxConfigPipeline` 拦截器链，1.14 六条红线；TUN `address` 数组、typed DNS、`store_dns`、`reject`/`hijack-dns`、本地 `.srs` | `core/config/`；R1/R4 |
| 抗封锁 | TLS ClientHello 分片、uTLS 指纹（chrome 等）、mux（h2mux/smux/yamux）+ padding | `AntiBlockingConfig`、`TlsConfigInterceptor`、`ProxyOutboundFactory`；R9（`acceptance/r9_*`） |
| 安全 DNS | 远程 DoH/DoT、直连 DNS、FakeIP 防逃逸、`default_domain_resolver` | `DnsConfigInterceptor`；R7/R14 |
| 应用级国家分流 | 按包名选择国家/收藏/直连/断网；同国分组、`auto-{CC}`、`group-{CC}`、`group-FAVORITES`、`group-GLOBAL`、`proxy` | `AppCountryRouteCompiler`、`RoutingRuleInterceptor`；R4（`acceptance/r4_*`） |
| 故障切换 | 四大预设（同国锁定健康优先/收藏池优先/全域低延迟/稳健防抖）、七项参数、Clash delay 探测、Selector 热切换、连续失败冷却、本机直连防误杀 | `core/failover/`；R2/R3/R5（`acceptance/r3_*`、`r5_failover_advanced.png`） |
| 稳定性 | `UnderlyingNetworkTracker` 物理网络重绑、`protect(fd)`、内核启动/重载 Mutex 串行 | `core/engine/`；R4 |
| 三级省电 | L1/L2/L3 状态机，息屏/分屏/低电量广播，后台探活与 UI 刷新降频 | `core/power/PowerStateController`；R5（`acceptance/r5_power_runtime.log`） |
| 订阅 | 下载/解析/导入、`Subscription-Userinfo` 流量元数据、差异更新（新增/保留/删除）、WorkManager 自动更新、失败指数退避 | `core/subscription/`；R6/R10/R14/R20/R26（`acceptance/r6_*`、`r14_*`） |
| 备份 | 本地 SAF JSON、口令加密（PBKDF2 + AES-256-GCM）、WebDAV 上传/下载、WebDAV 密码 Keystore 加密、Gzip `.json.gz`（自动识别恢复） | `core/backup/`、`ui/screens/ExportBackupScreen.kt`；R8/R12/R13/R31 |
| 导出 | 节点二维码/分享链接、精选收藏/高分节点 Base64 订阅、精选节点 sing-box 1.14 最小配置 JSON | `core/export/SelectedNodeExporter`；R8/R30/R37 |
| 三屏 UI | 主页/节点/日志/设置（底部四入口，规格称三屏）、全中文、Material3、320dp 适配 | `ui/screens/`；R2–R5 |
| 节点页 | 收藏置顶、国家分组、搜索过滤（名称/服务器/国家）、分组折叠、分组顺序（拖拽+上下按钮）、批量选择/删除/探活/GeoIP、节点卡片健康标签 | `NodesScreen.kt`；R11/R19/R33/R34/R35 |
| 节点详情 | 健康分/探活/EMA/出口 IP/当前节点、失败与冷却、延迟历史曲线（失败空洞）、相关连接列表、二维码/分享/设为当前/删除 | `NodeDetailScreen.kt`、`NodeDetailViewModel`；R18/R21–R24 |
| 诊断 | Clash 2s 轮询、规则命中/出站链聚合、异常/熔断节点、连接详情（目标/网络/规则/链/流量/应用进程）、延迟历史、清空历史、报告复制/分享/导出文件 | `DiagnosticsScreen.kt`、`DiagnosticsViewModel`、`DiagnosticsReportBuilder`；R16/R17/R25/R27–R29/R32/R36/R43–R45 |
| 受信任 Wi-Fi | SSID 列表（当前/手动/删除）、连接受信任网络时跳过代理、通知提示、开机跳过、磁贴提示；需定位权限读取 SSID | `core/wifi/TrustedWifi`、`SingFlowVpnService`、`BootReceiver`、`QuickTileService`；R38–R42/R46 |
| 域名直连 | 设置页维护 `directDomains`，配置生成 `domain_suffix → direct`；节点详情连接弹窗可一键加入 | `RoutingRuleInterceptor`、`SettingsScreen`、`NodeDetailScreen`；R47 |
| 快捷磁贴 | 连接状态、副标题（节点名/受信任 Wi-Fi 已跳过）、点击连接/断开 | `QuickTileService`；R46 |
| 日志 | 分级日志、内存环形缓冲、诊断模式回退、日志页过滤/清空/分享 | `LogsScreen`、`VpnStateRepository`；R2/R5 |

### 2.2 部分完成 / 有明确限制

| 项 | 现状 | 限制 |
| :--- | :--- | :--- |
| 受信任 Wi-Fi 自动化 | 连上受信任 SSID 时**跳过代理**；开机/磁贴联动；发送通知 | **离开受信任网络后不会自动恢复连接**。Android 12+ 后台启动 FGS 受限，当前只提示用户打开 App/点通知手动重连。 |
| SSID 读取 | 已接 `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE`，首次添加请求定位权限 | 未授权/定位关闭/系统脱敏时返回 `null`，功能静默不可用；无“权限被永久拒绝”引导页。 |
| 域名白名单 | 后缀匹配、设置页增删、连接详情一键加入 | 未做通配符/正则、IP/CIDR、重复提示；配置生成只有 `domain_suffix`。 |
| 诊断订阅卡片 | 展示名称/剩余流量/到期日，可点击进入订阅页 | 未做长按刷新/删除；订阅页本身已有按钮。 |
| 连接诊断 | 展示应用进程名（`metadata.process`），可复制域名、加入直连 | 未实现规格中的“长按连接 → 将该应用改为走其他国家”；应用分流需到独立「分应用代理」页操作。 |
| 磁贴 | 受信任 Wi-Fi 显示“已跳过”并阻止连接 | 点击只 Toast，不打开 App 说明页。 |
| 备份 | 节点/设置/订阅/应用规则；Gzip；加密；WebDAV | 不包含延迟历史、日志、当前 VPN 会话；恢复为**合并模式**，不覆盖/清空现有数据；WebDAV 无 MKCOL/冲突版本管理。 |
| Anti-blocking mux | 配置支持 h2mux/smux/yamux + padding | 与本地回环测试服务端不完全兼容（开启 mux 时回环 VLESS 可能失败）；默认关闭，真实节点支持度取决于服务端。 |

### 2.3 未完成（明确没做）

1. **离开受信任 Wi-Fi 后自动重连**：只有通知/手动路径。
2. **订阅 Worker 的 Room + MockWebServer 全流程集成测试**：当前只有下载器 MockWebServer 测试、差异计算/合并策略单测；Worker 本体依赖 Hilt EntryPoint，未做端到端测试。
3. **50+ 节点批量导入实机压力测试**：解析器/批量探活有单测，但未在真机导入 50+ 做完整 UI/内核压力验证。
4. **当前 release 包的真机 Android 13–17 矩阵复验**：旧版 80.5MB release 有真机 arm64 记录（`acceptance/e2e_realdevice_release_arm64.log`），但当前 84.95MB 新包未重新做真机安装/冒烟；模拟器主要为 API 33，另有 API 36 历史日志。
5. **R33–R47 的部分 UI 交互未逐个跑运行截图**：搜索、折叠、拖拽、域名白名单、受信任 Wi-Fi 设置、订阅卡片跳转、磁贴提示等有编译/单测/代码验证，但没有全部留存 ADB 截图；其中拖拽排序仅编译验证。
6. **连接长按的“应用改国家”快捷操作**：未做；现有应用分流页可手动完成。
7. **WebDAV 高级能力**：目录创建、条件请求、冲突合并、多版本备份未做。
8. **受信任 Wi-Fi 设置项的运行端到端验收**：R38 尝试在模拟器打开开关时受滚动/坐标影响未完成切换，服务端逻辑与单测已验证，但 UI 全链路未截图。

---

## 3. 代码地图（接手入口）

```
app/src/main/java/com/singflow/android/
  MainActivity.kt                  edge-to-edge、通知权限、导入 Intent
  SingFlowApp.kt                   Hilt Application
  core/
    model/                         ProtocolType / ProxyNode / AppSettings / VpnState / failover 配置
    database/                      Room entities/DAO、NodeRepository、PreferencesManager、NodeMerge
    parser/                        NodeLinkParser + impl/ + UniversalImportManager
    config/                       SingBoxConfigPipeline + interceptors/
      SingBoxConfigPipeline.kt
      interceptors/DnsConfigInterceptor.kt
      interceptors/RoutingRuleInterceptor.kt
      interceptors/TunConfigInterceptor.kt
      interceptors/ProxyOutboundFactory.kt
      ...
    engine/
      SingFlowVpnService.kt        前台服务、TUN、内核启动、网络重绑、受信任 Wi-Fi 短路
      LibboxBridge.kt              libbox.aar 反射双模
      VpnControl.kt                连接/断开/上次会话持久化
      UnderlyingNetworkTracker.kt  物理网络重绑
      QuickTileService.kt          快捷磁贴
      BootReceiver.kt              开机/升级自动连接
      NodeLatencyTester.kt
      TrafficAndLogMonitor.kt
    failover/                      FailoverController、ClashApiOutboundSwitcher、预设/参数
    probe/                         BatchProbeEngine、ClashApiModels、LatencyHistoryStore、ClashStats
    power/                         PowerStateController
    subscription/                  SubscriptionManager、SubscriptionHttpClient、Diff、UpdateWorker
    backup/                        BackupManager、BackupCipher、BackupCompression、WebDavClient、WebDavCrypto
    export/                        SelectedNodeExporter
    wifi/                          TrustedWifi
  ui/
    SingFlowRoot.kt                NavHost、底部导航、页面组装
    MainViewModel.kt               节点/订阅/探活/GeoIP/设置/连接
    DiagnosticsViewModel.kt
    NodeDetailViewModel.kt
    BackupViewModel.kt
    DiagnosticsReportBuilder.kt
    screens/                       Dashboard / Nodes / Logs / Settings / Subscriptions /
                                   AppRouting / NodeDetail / Diagnostics / ExportBackup
    components/                    NodeItemCard、LatencySparkline 等
app/src/test/java/...              27 个测试文件，141 tests
app/libs/libbox.aar                sing-box 1.14.3（已入库）
```

### 配置编译的关键顺序

`SingBoxConfigPipeline` 的拦截器顺序决定红线是否满足。`RoutingRuleInterceptor` 当前规则顺序：

1. `sniff`（`action: sniff`, 300ms）
2. `hijack-dns`
3. `ip_is_private → direct`
4. `directDomains`（`domain_suffix → direct`）
5. 应用级国家分流（`package_name → auto-{CC}` 等）
6. 大陆分流（BYPASS_LAN_CN，`geosite-cn`/`geoip-cn`）
7. GLOBAL 模式兜底
8. `final = proxyTag`

修改这里时要同步跑 `SingBoxConfigPipelineTest` 与 `CountryRoutingConfigTest`，并检查六条红线。

---

## 4. 关键设计约束（不要破坏）

来自 `AGENTS.md`，接手后必须继续遵守：

1. **SDK**：minSdk 33；compile/target 动态取本机最高平台（37→36→35）。
2. **16KB page**：`useLegacyPackaging = false`，release 需通过 `zipalign -P 16`。
3. **前台服务**：`FOREGROUND_SERVICE_SYSTEM_EXEMPTED` + `SPECIAL_USE`，`ServiceCompat.startForeground` 显式类型。
4. **通知**：未授权 `POST_NOTIFICATIONS` 不得阻塞 VPN 启动。
5. **分应用**：始终排除自身，`protect(fd)` 防回环。
6. **sing-box 1.14 六条红线**：
   - DNS servers 强类型（`https`/`udp`/`local`/`fakeip`），禁止 `address` 字符串；
   - 禁止 `outbound: any` 与 `domain_strategy`，用 `default_domain_resolver`/`domain_resolver`；
   - 禁止 `block`/`dns` outbound，用 `reject`/`hijack-dns`；
   - inbound 禁止 `sniff`，首条路由规则 `{"action":"sniff","timeout":"300ms"}`；
   - TUN 用 `address: []`，禁止 `inet4_address`；
   - 禁止 `store_rdrc`，用 `store_dns`；分流用 `ip_is_private` + 远程 `.srs`。
7. **禁止**：`TODO`/`FIXME`/`...省略`、main 源码 `android.util.Base64`、`libbox.aar` 作为编译期硬依赖、在 `SingFlowVpnService` 之外调用 `establishTun`。

---

## 5. 构建、测试与静态门禁

### 5.1 本机环境

| 项 | 路径/值 |
| :--- | :--- |
| OS | Windows |
| JDK | `D:\Apps\JDK\jdk-21` |
| Android SDK | `D:\Apps\Android\Sdk` |
| Node | `D:\Apps\nodejs\node.exe` |
| AVD | `SingFlow_Test_AVD`，320×640，API 33 |
| 模拟器启动 | `D:\Apps\Android\Sdk\emulator\emulator.exe -avd SingFlow_Test_AVD -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect` |

### 5.2 命令

```powershell
$env:JAVA_HOME = 'D:\Apps\JDK\jdk-21'
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:assembleRelease --console=plain
```

安装/启动：

```powershell
$adb = 'D:\Apps\Android\Sdk\platform-tools\adb.exe'
& $adb install -r app/build/outputs/apk/debug/app-debug.apk
& $adb shell am force-stop com.singflow.android
& $adb shell am start -n com.singflow.android/.MainActivity
```

### 5.3 发布包检查

```powershell
$aapt = Get-ChildItem 'D:\Apps\Android\Sdk\build-tools' -Recurse -Filter aapt2.exe | Select-Object -Last 1 -ExpandProperty FullName
& $aapt dump badging app/build/outputs/apk/release/app-release.apk | Select-String 'package:|sdkVersion|targetSdk|locales'

$zip = Get-ChildItem 'D:\Apps\Android\Sdk\build-tools' -Recurse -Filter zipalign.exe | Select-Object -Last 1 -ExpandProperty FullName
& $zip -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk
```

最近结果：

- ABI 只有 `arm64-v8a`；
- locales 只有 `--_--` / `zh-CN`；
- `zipalign` 通过；
- `TODO/FIXME/...省略`（大小写敏感）：0；
- main 源码 `android.util.Base64`：无实际使用，仅 `Base64Utils.kt` 注释声明“刻意不使用”。

### 5.4 单元测试覆盖的重点

141 个测试覆盖（以测试文件实际内容为准）：

- 解析器/导入清洗、协议字段；
- 配置 pipeline 六条红线、国家分流、DNS/FakeIP、AntiBlocking；
- 健康评分、失败切换、探活结果；
- 订阅差异/合并、HTTP 下载器（MockWebServer）；
- 备份加密/Gzip、精选导出；
- 延迟历史编解码、诊断报告、节点分组/搜索/受信任 Wi-Fi 匹配。

测试结果目录：`app/build/test-results/testDebugUnitTest/*.xml`。

---

## 6. 运行时验收环境（复现指南）

### 6.1 本地测试服务

`scripts/dev/`：

| 脚本 | 用途 | 常用端口 |
| :--- | :--- | :--- |
| `vless-loopback-server.js` | 回环 VLESS WS 节点，供模拟器直连测试 | 8445 / 8446（历史上两个实例） |
| `subscription-server.js` | 本地订阅 HTTP 服务 | 8088 |
| `webdav-server.js` | 本地 WebDAV PUT/GET（Basic Auth） | 8089 |
| `probe-vless-ws.js` | 探测回环 VLESS 是否可用 | 按脚本参数 |

运行时还需要：

- ADB reverse（把模拟器端口映射到主机）：通常 `adb reverse tcp:8445 tcp:8445` 等；具体端口按当次脚本参数。
- mixed 入站：`127.0.0.1:7890`。
- Clash API：`127.0.0.1:9090`（诊断/切换用）。

### 6.2 AVD 当前测试状态（可能被后续运行改变）

- 节点：约 6 个（本地 VLESS + 订阅节点），含收藏节点。
- 订阅：1 个本地订阅（8088）。
- 应用规则：1 条。
- 设置：`countryRoutingEnabled=true`、`fakeIpEnabled=true`、`countryGroupOrder=["US","CN","UN"]`、mixed 端口 7890；failover 预设曾设为 `COUNTRY_LOCKED_HEALTH` 且 failover enabled=false。
- WebDAV：`http://127.0.0.1:8089/backup.json`，测试账号 `test/secret`。
- 定位权限：R38 曾在 AVD 执行 `pm grant com.singflow.android android.permission.ACCESS_FINE_LOCATION`；受信任 Wi-Fi 开关未确认成功打开。
- 延迟历史：R29 曾清空；后续是否重新探活取决于当次运行。

> AVD 的 DataStore/Room 数据会保留。接手后若行为与文档不一致，先清应用数据或重建 AVD，再按 `TASK_PROGRESS.md` 的测试步骤复现。

### 6.3 320×640 ADB 坐标（底部导航）

| 页面 | 坐标 |
| :--- | :--- |
| 主页 | `37,616` |
| 节点 | `119,616` |
| 日志 | `201,616` |
| 设置 | `283,616` |
| 主页连接按钮 | `160,318` |
| 节点详情信息图标（主页卡片下滑一次后） | `236,464` |

UI 自动化建议：每次 `uiautomator dump` 后按文本/bounds 点击，不要依赖固定坐标；R38 就因滚动状态导致开关未点中。

### 6.4 验收资产

- `acceptance/`：90 个文件，R2–R31 的截图与运行日志，含：
  - `r15_geoip_scan.png`、`r16_diagnostics_stats.png`、`r17_latency_history.png`；
  - `r21_node_connections.png`、`r24_connection_detail.png`、`r25_problem_nodes.png`；
  - `r30_selected_export.png`、`r31_gzip_backup.png`；
  - 更早的 `e2e_api33*.log`、`e2e_api36*.log`、`e2e_realdevice_release_arm64.log`、`realdevice_release_home.png`。
- 根目录构建日志：`build_test.log`、`build_release_slim.log`、`build_lint.log` 等（历史，可能不是最新）。
- 逐轮细节：`TASK_PROGRESS.md`。

---

## 7. 未完成工作清单（按建议优先级）

### P0 — 接手先确认

1. 用当前 HEAD 重跑 `testDebugUnitTest` 和 `assembleRelease`，确认 141 tests 与 APK 产物一致。
2. 在真机 Android 13/14/15/16/17 中至少选 2 个版本安装当前 release，做启动、授权、连接、断开、息屏冒烟。
3. 确认 `libbox.aar` 与 16KB page 在真机可用（模拟器不能完全代表）。

### P1 — 规格收尾

4. **受信任 Wi-Fi 离开后自动恢复**：可选方案是通知 Action 直接触发已授权 `VpnService` 启动（避免后台 FGS 限制），或前台服务常驻监视。需要重新评估 Android 12+ 限制，不要直接 `startForegroundService` 冒险。
5. **订阅 Worker 全流程测试**：用 Robolectric + Hilt Test/EntryPoint 或把 Worker 逻辑抽成纯函数，覆盖 Room 写入、失败重试、删除旧节点。
6. **50+ 节点导入压力测试**：验证 UI 不卡顿、批量探活并发、GeoIP 逐节点切换耗时。
7. **连接长按“应用改国家”**：把 `metadata.process` 映射到 `AppRouteRule`，或跳转应用分流页并预选包名。

### P2 — 体验与健壮性

8. 域名白名单：通配符/CIDR、重复提示、非法域名校验、导入导出。
9. 订阅卡片长按刷新/删除（或明确只保留订阅页操作）。
10. 磁贴受信任状态点击打开 App 说明页。
11. WebDAV：MKCOL、条件请求、冲突提示、多版本；备份加入延迟历史/日志（注意隐私与体积）。
12. 受信任 Wi-Fi 权限被永久拒绝时的设置引导。
13. 把 `HANDOFF.md` 更新或标注过期，避免下一个模型按 31 tests 判断。

---

## 8. 已知风险与坑

1. **模拟器 vs 真机**：本地回环 VLESS、ADB reverse、模拟器网络栈与真机差异大；`egress_timeout`、DNS 逃逸曾在真机/API36 出现，修复记录见 R3/R4 与 `TASK_PROGRESS.md`。
2. **Anti-blocking mux**：开启后回环服务端可能握手失败；默认关闭。改默认值前先验证真实节点兼容性。
3. **FakeIP**：只对域名生效；IP 直连/部分应用可能绕过，验证 DNS 逃逸时要同时看 `dns` 日志与 `curl` 结果。
4. **受信任 Wi-Fi**：SSID 读取受定位权限/系统脱敏影响；`<unknown ssid>` 会被当作不可用，不会误判为受信任。
5. **备份恢复**：合并模式，节点按 configHash 去重；不会删除现有节点。恢复前提醒用户。
6. **WebDAV 密码**：用 Android Keystore 加密保存，换设备/清除 Keystore 后旧密文不可解密；跨设备恢复只应依赖备份口令，不应依赖 WebDAV 密码。
7. **精选节点导出**：sing-box JSON 含节点凭据，UI 已提示；不要提交到公开仓库或日志。
8. **AVD 坐标**：底部导航在 320×640 的 y=616；不同分辨率/手势导航会变，自动化务必用文本定位。
9. **旧文档**：`HANDOFF.md` 的测试数、APK 大小、tag 已过期。

---

## 9. 提交记录（R1–R48）

```text
95bacc3 feat(v2): 数据层/导入清洗/健康度评分/国家分组配置编译
e01cb99 feat(v2-r2): 探活/故障切换/省电状态机/节点国家分组 UI
a3af62a fix(ui): 320dp 窄屏节点卡片改为三行布局并刷新验收截图
f8da3f8 feat(v2-r3): 服务内自动切换与三个运行时缺陷修复
f5f1b68 feat(v2-r4): 应用级国家分流 UI、物理网络重绑与内核任务串行化
27926b9 feat(v2-r5): 三级省电接入服务、七项参数面板与慢速节点判定修复
055ec2f feat(v2-r6): 订阅管理、流量元数据与 WorkManager 自动更新
8da20b0 feat(v2-r7): 连接诊断面板与 Clash 数据解析
eee8048 feat(v2-r8): 节点二维码导出与本地备份恢复
59af45e feat(v2-r9): TLS 分片、uTLS 指纹与多路复用配置
e9599eb feat(v2-r10): 订阅节点差异计算与更新提示细化
7f9bdab feat(v2-r11): 节点批量管理
d267555 feat(v2-r12): WebDAV 云备份与密码加密
4c4ed1c feat(v2-r13): 口令加密备份
f046f4e feat(v2-r14): Worker 指数退避与 FakeIP 开关
cf564c0 feat(v2-r15): 节点真实出口 GeoIP 批量实测
96c1da2 feat(v2-r16): 诊断规则命中与出站链统计
b03cead feat(v2-r17): 节点延迟历史与诊断曲线
a8ae485 feat(v2-r18): 节点详情页
3b30463 feat(v2-r19): 节点分组自定义排序
9ac686e test(v2-r20): 订阅刷新节点合并策略抽离与测试
b3af465 feat(v2-r21): 节点详情相关连接
c86be23 feat(v2-r22): 延迟历史持久化
80e10ea feat(v2-r23): 延迟断线空洞标记
6e11c6a feat(v2-r24): 连接详情弹窗与熔断状态
032f42e feat(v2-r25): 诊断异常/熔断节点列表
6afcc58 test(v2-r26): 订阅 HTTP 下载器抽离与 MockWebServer 测试
3e0d012 feat(v2-r27): 异常节点跳转与连接域名复制
d208a66 feat(v2-r28): 诊断报告一键复制
746dc59 feat(v2-r29): 清空延迟历史与诊断报告分享
9f1ed16 feat(v2-r30): 精选节点池导出
e9d0bd6 feat(v2-r31): Gzip 压缩备份
b1f64ca feat(v2-r32): 连接诊断显示应用进程
ab806a5 feat(v2-r33): 节点搜索过滤
39c7211 feat(v2-r34): 节点分组折叠展开
c7909a4 feat(v2-r35): 分组拖拽排序手势
cfccb5d feat(v2-r36): 诊断报告导出为文件
69353c6 feat(v2-r37): 精选节点导出 sing-box JSON
6715d95 feat(v2-r38): 受信任 Wi-Fi 跳过代理
ed8d193 feat(v2-r39): 受信任 Wi-Fi 跳过提示通知
a0d0e6a feat(v2-r40): 受信任 Wi-Fi 单条删除
45c03ec feat(v2-r41): 手动添加受信任 Wi-Fi
92c6541 feat(v2-r42): 开机联动与诊断订阅数
63be85e feat(v2-r43): 诊断报告订阅到期与流量余量
059e778 feat(v2-r44): 诊断页订阅卡片
01a7618 feat(v2-r45): 订阅卡片跳转订阅管理
84b6497 feat(v2-r46): 快捷磁贴受信任 Wi-Fi 联动
448a36e feat(v2-r47): 域名直连白名单
1e4ac59 docs(v2-r48): 最终完成审计
```

---

## 10. 一句话总结

**核心功能与四阶段里程碑已完成，测试/构建/发布包检查通过；剩余工作主要是受信任 Wi-Fi 的“离开后自动恢复”、少量交互的运行验收、订阅 Worker 集成测试与真机矩阵复验。** 接手者应先按 P0 重跑构建与真机冒烟，再决定是否补 P1/P2。
