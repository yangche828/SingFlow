# SingFlow 全自动开发进度追踪

> 由 Agent 自动维护。状态：`[ ]` 未开始 / `[x]` 已通过自检。

| Phase | 内容 | 状态 | 自检命令 | 结果 |
| :--- | :--- | :--- | :--- | :--- |
| 0 | 环境自检、全局规则、设备探测 | [x] | 规则文件存在 / `java -version` / `adb version` | JDK 21.0.11、ADB 37.0.1、Gradle 8.10.2，`AGENTS.md` / `CLAUDE.md` / `.cursorrules` 齐全 |
| 1 | 工程脚手架、Android 13~17 配置、模型与数据层 | [x] | `./gradlew :app:kspDebugKotlin` | BUILD SUCCESSFUL（`build_ksp.log`） |
| 2 | 全协议解析引擎 + JUnit | [x] | `./gradlew :app:testDebugUnitTest --tests "*UniversalParserTest"` | 17 tests / 0 failures |
| 3 | sing-box 1.14+ 配置编译流水线 + 红线断言 | [x] | `./gradlew :app:testDebugUnitTest` | 31 tests / 0 failures（含六大语法红线断言） |
| 4 | libbox 双模引擎、VpnService、测速与流量监控 | [x] | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` | BUILD SUCCESSFUL（`build_compile.log`） |
| 5 | Compose UI 与 APK 打包 | [x] | `./gradlew :app:assembleDebug` / `:app:assembleRelease` | `app-debug.apk` 219.7 MB（230,368,055 B > 5 MB），16 KB 页对齐校验通过；`app-release.apk` **80.5 MB**（R8 + 资源压缩 + 仅 arm64-v8a） |
| 6 | 真机/模拟器端到端联调（真实转发） | [x] | `scripts/e2e_adb_verify.ps1` / `scripts/e2e_adb_verify.sh` | 回环节点：API 33 **18/18 PASS**、API 36 **19/19 PASS**；用户真实节点（VLESS+TLS+WS）：API 36 **17/17 PASS**（浏览器渲染 `google.com`，`acceptance/e2e_api36_realnode.log`） |

## 环境快照

- OS: Windows 10 (win32)，当前会话具备管理员权限
- JDK: Temurin 21.0.11（`D:\Apps\JDK\jdk-21`）
- Android SDK: `D:\Apps\Android\Sdk`
  - platforms: android-33, android-35, android-36（`compileSdk` / `targetSdk` 动态取本机最高已安装平台 = 36）
  - build-tools: 34.0.0, 37.0.0（含 `aapt2` / `zipalign`）
  - emulator: 37.2.12；system-images: `android-33;google_apis;x86_64`(r17)、`android-36;google_apis;x86_64`(r07)
  - extras: `Android_Emulator_Hypervisor_Driver`（AEHD 2.2，服务 `aehd` RUNNING）
- ADB: 37.0.1；Gradle: 8.10.2 (wrapper)；Node: `D:\Apps\nodejs\node.exe`（回环 VLESS 服务端）
- 依赖下载代理：`127.0.0.1:10808` 已写入 `C:\Users\yangche\.gradle\gradle.properties`
- **libbox.aar：已提供** —— 云端构建 sing-box **v1.14.3**（仓库 `yangche828/singflow-libbox`，Release tag `libbox-v1.14.3`），
  58,712,166 B，ABI `arm64-v8a + x86_64`，`llvm-readelf` 断言 `LOAD` 段 `0x4000` 对齐；构建工作流见 `.github/workflows/build-libbox.yml`

> 模拟器加速说明：主机 `HypervisorPresent=False`、WHPX/VirtualMachinePlatform 均未启用。已安装
> Google AEHD 2.2 内核驱动（`sc query aehd` = RUNNING）作为替代加速方案，`emulator -accel-check`
> 返回 `0 / AEHD (version 2.2) is installed and usable`，因此无需重启主机即可运行 x86_64 镜像。

## Phase 6 验收记录（真实转发）

| 检查项 | 期望 | API 33（Android 13） | API 36（Android 16） |
| :--- | :--- | :--- | :--- |
| 运行环境 | API ≥ 33 | ✅ API 33 | ✅ API 36 |
| `adb install -r` | Success | ✅ | ✅ |
| `EXTRA_IMPORT_URI` 导入 | 节点 `SingFlow-LoopBack\|127.0.0.1\|8443` 入库 | ✅ | ✅ |
| `EXTRA_AUTO_CONNECT` | `tun0` 建立 | ✅ `172.19.0.1/30` | ✅ `172.19.0.1/30` |
| TUN MTU | 9000 | ✅ `mtu=9000` | ✅ `mtu=9000` |
| 应用进程 | `pidof` 非空 | ✅ | ✅ |
| 崩溃 | 无 `FATAL EXCEPTION` / tombstone | ✅ | ✅ |
| 前台服务 | `SingFlowVpnService` 运行 + `isForeground=true` | ✅ | ✅ |
| 前台服务类型（清单） | `foregroundServiceType` 含 systemExempted(0x400) | ✅ `0x40000400` | ✅ `0x40000400` |
| 前台服务类型（运行时） | `types=0x00000400` | 该 API 的 `dumpsys` 不输出该字段（WARN 跳过，已由清单断言覆盖） | ✅ |
| UI 状态 | 显示「已保护」（非 TUN 诊断模式）+ SingFlow 要素 | ✅ | ✅ |
| **真实转发（浏览器）** | VPN 内 Chrome 渲染 `documentation examples` | ✅ | ✅ |
| **真实转发（代理侧）** | VLESS 服务端新增 TCP + UDP(xudp) 记录 | ✅ 61 行 | ✅ 30 行 |
| 缓存排除 | 地址栏含本次随机 `singflow=<token>` | ✅ | ✅ |
| **汇总** | — | **18/18 PASS** | **19/19 PASS** |

证据文件：

| 文件 | 脚本 | 设备 | 结果 |
| :--- | :--- | :--- | :--- |
| `acceptance/e2e_api33.log` | `.ps1`（主脚本） | API 33 | **18/18 PASS** |
| `acceptance/e2e_api36.log` | `.ps1`（主脚本） | API 36 | **19/19 PASS** |
| `acceptance/e2e_api36_sh.log` | `.sh`（姊妹脚本） | API 36 | **19/19 PASS** |
| `acceptance/e2e_api33_sh.log` | `.sh`（姊妹脚本） | API 33 | 17/17 PASS（2 条 WARN：清单断言当时因 Git Bash 未把 APK 转成 Windows 路径而跳过；修复后已在 API 36 复跑全绿） |
| `acceptance/vless-loopback.log` | — | host | VLESS 服务端原始流量日志（跨轮累计，断言按增量计算） |
| `acceptance/e2e_api36_attempt1_egress_timeout.log` | `.ps1` | API 36 | 18/19：沙箱出口抖动导致 Chrome `ERR_TIMED_OUT`（隧道侧 107 行流量正常），重跑 19/19 PASS |

## Phase 6 追加验收：用户真实节点（VLESS + TLS + WS + Cloudflare）

用户提供真实节点 `vless://…@203.0.113.10:8443?security=tls&sni=node.example.com&type=ws&host=node.example.com&path=/`（Cloudflare 边缘 + WSS）。
环境限制：本机 host 被网络策略拦到 `203.0.113.10:8443`（TCP reset、`google.com:443` 亦超时），因此模拟器用
`emulator -http-proxy http://127.0.0.1:10808` 启动，让 guest 的 TCP 经本机代理到达 Cloudflare 边缘 —— **节点 URI 未做任何改写**，
TLS/WS/VLESS 全部端到端（证书校验仍是 `node.example.com`），因此浏览器能出网就等于隧道可用。

| 检查 | 结果 |
| :--- | :--- |
| TLS + WS 层独立探针（不依赖 App） | ✅ `scripts/dev/probe-vless-ws.js`：`ALPN=http/1.1`、证书 `CN=node.example.com`、`HTTP/1.1 101 Switching Protocols` |
| `adb install -r` / `EXTRA_IMPORT_URI` 导入 | ✅ 节点名（含中文）入库，`203.0.113.10:8443` |
| `tun0` / MTU / 进程 | ✅ `172.19.0.1/30`、`mtu=9000`、存活 |
| 崩溃 | ✅ 无 `FATAL EXCEPTION` / tombstone |
| 前台服务 | ✅ `isForeground=true` + 清单 `0x40000400` + 运行时 `types=0x00000400` |
| **TUN DNS 下发** | ✅ `DnsAddresses=[1.1.1.1]`（本次新增断言，见下方缺陷 1） |
| UI | ✅ 已保护 + SingFlow 要素 |
| **真实转发（浏览器）** | ✅ Chrome 渲染 `google.com`（期望文本 `Google` + 本次随机 `singflow=<token>`） |
| **隧道内 Google 实测** | ✅ 经 App 的 `mixed` 入站（`adb forward tcp:7890`）从 host 请求：`http://www.google.com/generate_204` → **204**；`https://www.google.com/` → **200 / 87,187 B** |
| Clash API 活动连接 | ✅ `www.google.com → 142.251.150.119:443 chains=proxy`（单次页面加载下行 794,699 B），另有 `play.googleapis.com` / `android.googleapis.com` / `mtalk.google.com` 等 |
| **汇总** | **17/17 PASS**（`.ps1` 与 `.sh` 各一轮全绿） |

证据文件：

| 文件 | 内容 |
| :--- | :--- |
| `acceptance/e2e_api36_realnode.log` | `.ps1` 真实节点 **17/17 PASS** |
| `acceptance/e2e_api36_realnode_sh.log` | `.sh` 真实节点 **17/17 PASS** |
| `acceptance/google_api36_realnode.png` | Chrome 中 `google.com` 首页完整渲染（含实时 trending 内容） |
| `acceptance/app_home_api36_realnode.png` | App 首页：已保护 + 实时上/下行速率 |
| `acceptance/app_log_api36_realnode.png` | App 日志页：`TUN DNS: 内核下发=无, 实际下发=1.1.1.1` |
| `acceptance/realnode_configuration.json` | 设备上实际送入内核的 `configuration.json` |
| `acceptance/realnode_connections.json` | Clash API `/connections` 快照（Google 域名全部 `chains=proxy`） |
| `acceptance/realnode_singbox_logs.jsonl` | Clash API `/logs` 流（`inbound/tun → outbound/vless[proxy]` 明文记录） |
| `acceptance/e2e_api36_realnode_attempt1_dns_escape.log` | 修复前留档（11/16）：见缺陷 1 |
| `acceptance/e2e_api36_realnode_attempt2_node_name_encoding.log` | 修复节点名编码断言前留档（16/17） |

### 本次真实节点验收发现并修复的缺陷

1. **客户端 DNS 逃出隧道（真实缺陷，已修）** —— 症状：Chrome 打开 `https://www.google.com/` 报
   `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`，而经 App 自身 `mixed` 入站访问 google 却 `200 OK`。
   定位：`dumpsys connectivity` 显示 VPN 网络 `DnsAddresses: [ ]`（内核未下发 DNS），Android 于是退回底层网络解析器，
   拿到 GFW 污染的答案（`www.google.com` → `157.240.7.20` / `31.13.92.37`，Meta IP；`www.example.com` → host 解析器的同一结果），
   随后 Chrome 把污染 IP 交给隧道 → 连到 Meta 机器的 443 且 SNI 是 google.com → TLS 失败。
   修复：`SingFlowVpnService.establishTunFromOptions` 在内核未下发 DNS 时兜底 `1.1.1.1`（+ IPv6 `2606:4700:4700::1111`），
   并在 App 日志打印 `TUN DNS: 内核下发=…, 实际下发=…`；TUN 上的 DNS 地址只是把查询拉进隧道的锚点，真正的解析由 `sniff` + `hijack-dns` 交给内核 DNS 模块。
   修复后：`DnsAddresses=[1.1.1.1]`，Chrome 正常渲染 google.com（`acceptance/google_api36_realnode.png`）。
2. **验收脚本节点名断言误判（测试工具缺陷，已修）** —— URI fragment 是 percent-encoded（中文节点名很常见），
   脚本此前用原始 fragment 去比对数据库里的解码名；同时 `adb` 输出按非 UTF-8 控制台编码解码会乱码。
   修复：两个脚本都做 percent-decode；`.ps1` 额外把 `[Console]::OutputEncoding` 设为 UTF-8，并改用 `select hex(name)` 做编码无关比较。
3. **模拟器 SystemUI 偶发 ANR 会污染 UI 断言（环境抖动，已加固）** —— 弹窗文本被当成页面文本，导致「已保护 / SingFlow / 渲染预期文本」三条误判。
   修复：两个脚本新增 `Clear-AnrDialog` / `ui_clear_anr`，检测到 `isn't responding` 自动点 `Wait`（浏览器断言场景会重新发起导航）。

### 真实节点暴露的环境/配置限制（非 App 缺陷，建议项）

- **默认远程 DoH `https://1.1.1.1/dns-query` 在该节点上不可用**：经隧道访问 `https://1.1.1.1/` 与 1.1.1.1 DoH 均被 TLS alert 拒绝
  （sing-box 报 `remote error: tls: handshake failure`），而 `https://doh.pub/dns-query`、`https://dns.google/resolve` 均正常返回真实 Google IP。
  本轮验收显式传 `-RemoteDns https://doh.pub/dns-query`；**代码默认值未改**（仍为 `1.1.1.1`）。
- **`ech=` 参数未参与解析**：节点 URI 带 `ech=cloudflare-ech.com+https://dns.alidns.com/dns-query`，当前 `VlessParser` 忽略 ECH（sing-box 需 `tls.ech.config` 的 base64 ECHConfig）。
  实测不影响连通性（Cloudflare 对 `sni=node.example.com` 返回合法证书），但缺少 ECH 会降低抗封锁性。
- **内置 `geosite-cn` 含 `gstatic.com` 等 Google CDN 域名**：这些域名按大陆分流走 `dns-direct`（223.5.5.5）→ 被污染的 CN IP → `chains=direct`；
  主域 `www.google.com` 不在该表内，走 `proxy`，页面可正常渲染。属规则集内容问题，不影响本次结论。

## 真机 release 包验收（Android 17 / arm64-v8a）

被测包：`app-release.apk`（80.5 MB，R8 + `shrinkResources` + `abiFilters arm64-v8a` + 复用 debug 签名），
设备：HONOR BKQ-AN10，Android 17（API 37），`arm64-v8a`，页大小 4096，无 root。
运行命令：`pwsh -File scripts\e2e_adb_verify.ps1 -ApkPath <apk> -ProxyLog <回环日志> -RemoteDns https://doh.pub/dns-query -TestUrl https://www.example.com/`。

| 检查项 | 结果 |
| :--- | :--- |
| `adb install -r` | ✅ Success（2.8 s） |
| `tun0` / MTU | ✅ `172.19.0.1/30`、`mtu=9000` |
| 应用进程 / 崩溃 | ✅ 存活；无 `FATAL EXCEPTION`、无 tombstone |
| 前台服务 | ✅ `isForeground=true`；清单 `0x40000400`；运行时 `types=0x00000400` |
| **TUN DNS 下发** | ✅ `DnsAddresses=[1.1.1.1]` |
| UI | ✅ 「已保护」（非诊断模式）+ SingFlow 要素 |
| **真实转发（浏览器）** | ✅ 期望文本命中（`documentation examples`） |
| **真实转发（代理侧）** | ✅ VLESS 服务端新增 39 行（含 TCP / DoH / DoT / UDP-xudp） |
| 节点入库 | ⚠️ WARN —— release `debuggable=false`，`run-as` 不可用读不到 Room 数据库（非缺陷） |
| **汇总** | **18/18 PASS，0 FAIL** |

> 第一轮（未指定 `-RemoteDns`）曾 15/18：屏幕熄灭导致两条 UI 断言读到空快照；隧道内 DNS 走 `1.1.1.1:853`（DoT）
> 而本机出口不可达 → 页面空白。开启屏幕常亮并改用 `https://doh.pub/dns-query` 后 18/18 全绿 —— 均为环境问题，非 App 缺陷。

证据：`acceptance/e2e_realdevice_release_arm64.log`、`acceptance/realdevice_release_home.png`。

## 补充验证

- **16 KB 页对齐**：`zipalign -c -P 16 -v 4 app-debug.apk` → `Verification successful`（`lib/arm64-v8a/libbox.so`、`lib/x86_64/libbox.so` 均 OK）。
- **无设备时自动拉起 AVD**（蓝图 Phase 6 情况 B）：清空 `adb devices` 后运行 `.ps1`，脚本打印「未检测到在线设备…启动 AVD: SingFlow_Test_AVD」，
  以 `-no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect` 拉起并轮询 `sys.boot_completed`，
  随后自动跑完整轮验收（该段输出在控制台，日志文件在设备就绪后才创建）。
- **远程 DoH 写入生效**：UI 自动化改设置后
  `run-as com.singflow.android grep -a -o 'https://[a-zA-Z0-9./-]*' files/datastore/singflow_settings.preferences_pb`
  → `https://doh.pub/dns-query`。
- **前台服务类型静态证据**：`aapt2 dump xmltree app-debug.apk --file AndroidManifest.xml` → `foregroundServiceType(0x01010599)=0x40000400`
  （`SPECIAL_USE | SYSTEM_EXEMPTED`），权限含 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` / `FOREGROUND_SERVICE_SPECIAL_USE`。
- **断开/重连**：点击首页主按钮可断开（`tun0` 消失、UI 回到「未连接」），再次点击可重连（历史运行已验证）。

## 已知限制（环境约束，非 App 缺陷）

- 沙箱 host 无法稳定直连外网源站（代理侧常见 `upstream error connect ETIMEDOUT <公网IP>:443`），因此「浏览器渲染」断言偶发失败；
  隧道侧流量始终正常到达服务端，重跑即通过（`acceptance/e2e_api36_attempt1_egress_timeout.log` 为留档）。
- host 直连用户节点 `203.0.113.10:8443` 被 reset、`google.com:443` 超时，故真实节点验收用 `emulator -http-proxy http://127.0.0.1:10808`
  让 guest 的 TCP 经本机代理到达 Cloudflare 边缘（节点 URI 未改写，TLS/WS/VLESS 端到端）。
- 默认远程 DoH `https://1.1.1.1/dns-query` 在该节点上被 TLS alert 拒绝（内核报 `remote error: tls: handshake failure`），
  真实节点验收显式传 `-RemoteDns https://doh.pub/dns-query`；**代码默认值仍是 `https://1.1.1.1/dns-query`（未改动，建议改）**。
  另：AliDNS（223.5.5.5）对 `www.google.com` 自带污染答案（`157.240.7.20`），只能用于 `dns-direct` 的大陆域名。
- host 内存约 15.8 GB，同时只跑一个 AVD（4 GB）；起模拟器前建议先 `./gradlew --stop`，否则 guest 易被 LMK 杀掉。
  低配 + 软件 GPU 下 SystemUI 偶发 ANR，验收脚本已自动点掉弹窗（`Clear-AnrDialog` / `ui_clear_anr`）。

## Lint 说明（`lintDebug` 通过：3 条 error 级发现均已核实为误报或在代码中已处理）

`lintDebug` 任务 BUILD SUCCESSFUL（`build_lint.log`），但报告里是 **3 errors / 52 warnings**；`app/build.gradle.kts` 配置了 `lint { abortOnError = false }`，所以任务不会因这些 error 失败。逐条核实：

| 位置 | 发现 | 核实结论 |
| :--- | :--- | :--- |
| `SingFlowVpnService.kt:415` | `MissingPermission`：`notify()` 未显式检查 `POST_NOTIFICATIONS` | 第 414 行已用 `runCatching { ... }.onFailure { Log.w(...) }` 显式兜住潜在 `SecurityException`，lint 看不到这层包裹 → **误报** |
| `QuickTileService.kt:59` | `StartActivityAndCollapseDeprecated`：API 34+ 该重载会抛异常 | 代码已做版本分支（`SDK_INT >= 34` 用 `PendingIntent` 重载，仅 API < 34 用旧重载并 `@Suppress("DEPRECATION")`）→ **误报** |
| `AndroidManifest.xml:47` | `ForegroundServicePermission`：`systemExempted` 还要求 exact-alarm 权限 | 清单已声明 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`；API 36 实测 `types=0x00000400` 正常生效，说明运行时并未要求 exact-alarm；而给 VPN 应用加 `SCHEDULE_EXACT_ALARM` 属滥用权限 → **不采纳** |

建议（非阻塞）：把这三处改成 lint baseline 或就地 `@SuppressLint` 并注明理由，然后恢复 `abortOnError = true`，让今后新增的 error 级问题重新具备门禁效力。

## 变更记录

- Phase 0：生成 `AGENTS.md` / `CLAUDE.md` / `.cursorrules`（Android 13~17 兼容红线 + sing-box 1.14+ 六大语法红线 + 禁止 TODO/占位符）与 `TASK_PROGRESS.md`。
- Phase 1~3：Gradle 8.10.2 + AGP 8.7.2 + Kotlin 2.0.21 + KSP + Hilt；动态 `compileSdk/targetSdk`；`packaging.jniLibs.useLegacyPackaging=false`；
  Room + DataStore 数据层；七大协议解析职责链 `NodeLinkParser` + `UniversalImportManager`；配置编译拦截器链 `SingBoxConfigPipeline`（红线全部内建断言，单测 31/31）。
- Phase 4：**libbox 从「缺失回退」升级为真实内核** —— 新增 `.github/workflows/build-libbox.yml`（Go 1.25.5 + JDK 17 + NDK r28 + gomobile，
  构建 sing-box v1.14.3 并内建 16 KB 对齐断言），产物落地 `app/libs/libbox.aar`；`LibboxBridge` 完成 1.14.3 反射桥
  （`Libbox.setup(SetupOptions)` / `newCommandServer` / `CommandServer.startOrReloadService`，`PlatformInterface` 走 `java.lang.reflect.Proxy`，**零编译期依赖**）。
- Phase 5：Compose Material 3 全量页面（首页/节点/日志/设置）+ `SingFlowNavHost`；Edge-to-Edge、禁方向锁定、FGS 声明 `systemExempted|specialUse`。
- 修复真实转发阻塞项：`InboundConfigInterceptor` 显式 `tun.stack = "gvisor"`（此前 TUN TCP 卡死）；`LibboxBridge.findConnectionOwner` 永不返回 null（查不到时 `userId = -1`，修掉原生崩溃）。
- 规则集随 APK 内置：`assets/ruleset/*.srs` → `files/singflow/ruleset/`，配置用 `type: local`；远程回退保留 `download_detour: direct`。
- 新增可复现验收工具链：`scripts/dev/vless-loopback-server.js`（VLESS TCP + UDP/XUDP 回环服务端）+ 重写 `scripts/e2e_adb_verify.ps1` / `.sh`
  （UI 自动化改远程 DNS、真实转发断言、缓存排除断言、清单/运行时前台服务类型断言、无设备自动拉起 AVD、逐行写日志）。
- 验收脚本兼容性修复：PowerShell `"$var?..."` 被当作变量名的坑、Git Bash 需 `MSYS_NO_PATHCONV=1` + `cygpath -w`（`adb install` 与 `aapt2` 读清单都要 Windows 路径）、
  `uiautomator dump` 陈旧快照（dump 前 `rm -f` 并校验 `<hierarchy`）、IME 抢占（先 `keyevent 4`）、bash 脚本运行期间禁止编辑（边读边执行）。
- `scripts/setup_env_and_sdk.ps1` 新增 `-InstallEmulator` 开关（模拟器 + API 33 镜像 + AEHD + `-accel-check`），默认仅只读自检；已验证幂等。
- 验收完成后清理临时产物（根目录 `e2e_result.log`、过期 acceptance 临时日志、测试用 Node 进程与模拟器）。
- **真实公网节点验收（用户提供 VLESS+TLS+WS 节点）**：新增 PC 侧独立探针 `scripts/dev/probe-vless-ws.js`（CONNECT 代理 → TLS(SNI) → WS 101）；
  模拟器加 `-http-proxy` 打通 Cloudflare 边缘；`.ps1` / `.sh` 各跑出 **17/17 PASS**（`acceptance/e2e_api36_realnode.log`、`acceptance/e2e_api36_realnode_sh.log`），
  Chrome 渲染 `google.com`（`acceptance/google_api36_realnode.png`），Clash API 记录 `www.google.com → 142.251.x.x:443 chains=proxy`（下行 794,699 B）。
- **修复真实缺陷：客户端 DNS 逃出隧道** —— VPN 网络 `DnsAddresses` 为空导致 Android 回退底层网络解析、拿到 GFW 污染答案，
  Chrome 由此报 `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`；`SingFlowVpnService.establishTunFromOptions` 新增 `fallbackTunDnsServers(ipv6Enabled)`
  与 `TUN DNS: 内核下发=…, 实际下发=…` 日志，修复后 `DnsAddresses=[1.1.1.1]`。两个验收脚本各新增一条「TUN 已下发 DNS 服务器」断言（`.ps1` / `.sh` 均 17 项）。
- 验收脚本健壮性：节点名 percent-decode（`:ps1` + `.sh`）、`.ps1` `[Console]::OutputEncoding=UTF8` + `select hex(name)` 编码无关比较、
  `Clear-AnrDialog` / `ui_clear_anr` 自动处理模拟器 SystemUI ANR 弹窗。
- 回归门禁复跑：`:app:assembleDebug`（BUILD SUCCESSFUL 52s）、`:app:testDebugUnitTest :app:lintDebug`（BUILD SUCCESSFUL 1m17s，`build_test.log`）。
- **发布体积优化**：release 开启 R8 + `isShrinkResources`，`abiFilters` 限定 `arm64-v8a`，复用 debug 签名（个人自用）；
  APK 从 219.7 MB 压到 **80.5 MB**（dex 57 MB → 2.8 MB，去掉 x86_64 内核 −81.5 MB）。
- **真机 release 验收 18/18 PASS**（HONOR BKQ-AN10 / Android 17 / arm64，`acceptance/e2e_realdevice_release_arm64.log`），
  同时确认模拟器此前连接瞬间 SIGILL 是 x86_64 ARM 翻译层限制，与 App 无关。

## 功能迭代增强 V1（`singflow_android_function_enhancement_v1`）

> 目标文档：`singflow_android_function_enhancement_v1.md`（v2.0 工程设计）。
> 落地策略：保留现有包名 `com.singflow.android` 与已验收的内核链路，按「数据层 → 引擎 → 配置 → UI → 稳定性/省电」增量落地；
> 文档中与 `AGENTS.md` 红线冲突的写法（`useLegacyPackaging = true`、`block-out` 出站、旧版 DNS `address` 字符串）**一律以 `AGENTS.md` 为准**。

### R1：数据层、导入清洗、健康度评分、国家分组编译（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 节点领域模型 | `ProxyNode` 新增连接指纹 `configHash`（由 `NodeFingerprint` 计算）、原始分享链接、国家/落地 IP/实测校准、探活统计、EMA 延迟与抖动、健康分/标签、冷却期、收藏、累计流量 | `NodeEntityMappingTest` 3/3 |
| Room 数据层 | `NodeEntity` 同步新增列 + 索引；数据库升级到 v2（`MIGRATION_1_2` 原地加列，保留既有节点）；新增 `app_route_rules` 表与 `AppRouteRuleDao/Repository`；开启 `exportSchema` 并提交 `app/schemas/.../2.json` | KSP 生成 schema，字段默认值与迁移 SQL 一致 |
| 导入引擎 | `NodeParserUtils`：名称 percent-decode、`+` 保留、控制/零宽字符清洗、64 字截断、SHA-256 连接指纹、23 条国家/地区识别规则（含旗帜、城市、机场三字码）；订阅刷新 `saveAllPreservingStats` 保留收藏/实测国家/健康度 | `NodeParserUtilsTest` 10/10、`UniversalParserTest` 17/17 |
| 健康度评分 | `NodeHealthScorer`：拉普拉斯平滑成功率 55 + 延迟 30 + 抗抖动 10 + 置信度 5 − 连续失败 18/次，连续 2 次失败进入冷却；四级中文标签 | `NodeHealthScorerTest` 8/8 |
| 自动切换策略 | `FailoverPreset` 四大预设、`FailoverConfig` 七项参数、`FailoverPolicy` 候选池/跨国公司回退/迟滞防抖/异常判定纯函数 | `FailoverPolicyTest` 11/11 |
| 国家分组编译 | `CountryGroupInterceptor` 生成 `node-{id}`、`auto-{CC}`（urltest 前 10）、`group-{CC}`、`group-FAVORITES`、`group-GLOBAL`、`proxy` selector；`RoutingRuleInterceptor` 支持应用级 DIRECT/FAVORITES/COUNTRY/`reject` 分流，指定国家无节点时回退默认国家组；`SingBoxConfigPipeline.compile(nodes, selectedNodeId, ...)` 多节点入口 | `CountryRoutingConfigTest` 4/4 |
| 构建瘦身 | `resourceConfigurations = zh/zh-rCN`；剔除 META-INF/kotlin 元数据/调试探针；R8 规则补齐 libbox/Go、Kotlin Serialization、Room、移除 `Log.v/d`；版本升至 2.0.0（versionCode 2） | `:app:testDebugUnitTest` 67/67 全绿 |

**红线核对**：多节点配置仍为强类型 `dns.servers`、无 `domain_strategy`、无 `block`/`dns` 出站（应用阻断用 `reject` 动作）、TUN 使用 `address` 数组、路由首条为 `sniff`、规则集仍用本地 `.srs`；`useLegacyPackaging` 保持 `false`（16 KB 页对齐要求）。

**R1 验证命令与结果**

```text
./gradlew :app:testDebugUnitTest   → BUILD SUCCESSFUL，7 个测试类 67 tests / 0 failures
./gradlew :app:assembleDebug       → BUILD SUCCESSFUL，app-debug.apk 227.6 MB
./gradlew :app:assembleRelease     → BUILD SUCCESSFUL，app-release.apk 80.43 MiB（84,372,264 B）
zipalign -c -P 16                  → Verification successful；资源仅保留 zh-rCN
```

### 后续轮次（进行中）

1. **探活引擎**：基于 Clash API `/proxies/{tag}/delay` + `https://www.cloudflare.com/cdn-cgi/trace` 的批量并发探活与落地国家识别（12 并发信号量、3500 ms 超时、回退 `api.ip.sb/geoip`）。
2. **切换执行器**：`FailoverController` 接入 `NodeHealthScorer` 与 libbox Selector 内存态热切换（不重建 TUN），并加本机断网甄别。
3. **UI**：节点页国家分组折叠、健康分徽章、收藏置顶/批量探活/测地区；设置页默认国家、应用级国家分流器、四大预设与七项参数面板；主页当前国家与健康度。
4. **稳定性/省电**：物理网络切换重绑（`setUnderlyingNetworks`）、`Mutex` 内核生命周期互斥、`PowerStateController` 三级功耗状态机、`AtomicFile` 配置写入。
5. **订阅/导出/备份**：订阅自动更新 Worker、精选节点导出/二维码、本地加密备份与 WebDAV。

### R2：探活引擎、故障切换执行器、三级省电状态机与节点分组 UI（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 落地识别 | `EgressTraceParser` 解析 Cloudflare `cdn-cgi/trace` 与 `api.ip.sb/geoip`；`CountryNames` 覆盖 40+ 国家代码；不触发 DNS 的 IPv4/IPv6 字面量校验 | `EgressTraceParserTest` 7/7 |
| 探活器 | `NodeProber` 接口；`MixedProxyNodeProber` 经本地 mixed 入站做真实 HTTP（顺带落地国家）；`ClashApiNodeProber` 经 `/proxies/{tag}/delay` 在不切换出站的情况下测 RTT | 运行时实测 `delay=209ms` |
| Clash API | `ClashApiClient`：`/version`、`/delay`、`PUT /proxies/{group}` Selector 切换、读取当前成员；统一超时与 JSON 解析 | 运行时 `PUT` 组切换可用 |
| 批量探活 | `BatchProbeEngine`：12 并发信号量、逐节点写回健康度、进度回调、空输入安全 | `BatchProbeEngineTest` 3/3 |
| 故障切换 | `FailoverController`：带重试当前节点探活 → 本机断网甄别 → 扣分/冷却 → 前三候选逐个实测确认 → Selector 内存切换；四大预设与迟滞门槛全部生效 | `FailoverControllerTest` 9/9 |
| 端口抽象 | `NodeStore`/`RepositoryNodeStore`、`OutboundSwitcher`/`ClashApiOutboundSwitcher`、`LocalNetworkVerifier`/`AndroidLocalNetworkVerifier`，便于 JVM 测试 | 编译通过 |
| 省电状态机 | `PowerStateController`：L1 前台 / L2 后台亮屏 / L3 息屏 15 秒后深度休眠；省电模式立即 L3；亮屏取消待执行 L3 | `PowerStateControllerTest` 5/5 |
| UI | 节点页按「收藏 → 国家/地区」自动分组，卡片显示旗帜、健康分徽章与实测延迟，支持收藏置顶；设置页新增「多节点国家分组」开关、四大预设单选与默认国家选择器 | API 33 模拟器实测截图 |
| 集成 | `MainViewModel` 自适应单/多节点配置；多节点 + 已连接时「一键探活」改走 Clash API 真实 RTT；`AppSettings.countryRoutingEnabled` 默认关闭（验证期 opt-in） | 见下方运行时验收 |

**运行时验收（API 33 模拟器 / 4 节点 JP+US+SG+VLESS 回环）**

```text
多节点国家分组开关开启 → 连接成功（UI「已保护」）
tun0: 172.19.0.1/30, mtu 9000
Clash API: proxy(Selector) / group-JP / group-SG / group-US / group-FAVORITES / group-GLOBAL 全部生成
经 mixed(7890) 访问 http://cp.cloudflare.com/generate_204 → HTTP 204（真实转发）
/proxies/node-<loopback>/delay → {"delay":209}
无 FATAL EXCEPTION / panic
```

证据：`acceptance/r2_multinode_runtime.log`、`acceptance/app_home_multinode_connected.png`、
`acceptance/app_nodes_country_grouping.png`、`acceptance/app_nodes_favorite.png`、
`acceptance/app_settings_failover.png`、`acceptance/app_settings_country_picker.png`。

### 下一轮（R3）

1. 将 `FailoverController` 挂到 `SingFlowVpnService`（仅多节点配置 + 已连接时启用），跑 API 33 回环断连自动切换实测。
2. 应用级国家分流 UI（选择应用 → 国家/收藏/直连/阻断）与连接诊断面板。
3. 七项切换参数滑块、`AtomicFile` 配置写入、`setUnderlyingNetworks` 物理网络重绑、内核生命周期 `Mutex`。
4. 主页展示当前国家/健康度，省电状态机接入服务与 UI 采样。

### R3：服务内自动切换 + 三个运行时缺陷修复（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 服务集成 | `SingFlowVpnService` 改为 `@AndroidEntryPoint`，注入 `NodeRepository` / `ClashApiClient` / `LocalNetworkVerifier` / `PreferencesManager`；连接成功 8 秒后启动巡检协程，按巡检周期执行 `FailoverController`；切换后更新 `currentNodeId`、DataStore 选中节点、前台通知并写日志 | API 33 实测 |
| 巡检控制 | 新增「巡检周期」选择器（仅被动 / 30 / 60 / 180 / 300 秒）；关闭多节点或自动切换时明确记录原因；`reload/stop` 会取消旧巡检协程 | 设置页截图 |
| 缺陷 1：本机断网误判 | 模拟器/VPN 抢占默认网络后底层 cellular 只有 `PARTIAL_CONNECTIVITY`、没有 `VALIDATED`，旧实现因此每轮都判 `LOCAL_NETWORK_DOWN`，永不切换；改为 `INTERNET && !CAPTIVE_PORTAL`，VPN/Wi-Fi/蜂窝/以太网均可判定 | 修复前后 logcat 对比 |
| 缺陷 2：陈旧连接掩盖节点死亡 | `MixedProxyNodeProber` 复用共享 OkHttp 连接池，节点端口已断但既有隧道仍存活，探活一直「健康」；改为每次探活新建 `ConnectionPool()` + `retryOnConnectionFailure(false)` | 修复后切断 A 端口，8 秒内切换 |
| 缺陷 3：Clash API 明文被系统拦截 | Android 9+ 默认禁止 cleartext，App 内访问 `http://127.0.0.1:9090` 被拒，批量探活实际退回 TCP；新增 `network_security_config.xml` 仅放行 `127.0.0.1/localhost` | 批量探活后节点健康分从 22 → 66/69 |
| 运行时验收 | 两个 JP 回环节点 A(8445)/B(8446)：A 端口断开后，`proxy` selector 8 秒内从 `node-af215…` 切换到 `node-b090…`，延迟 225ms；切换后 mixed 代理访问 `generate_204` 仍为 **204** | `acceptance/r3_failover_runtime.log` |

运行时关键日志：

```text
17:50:42.431 SingFlowVpnService: failover: action=SWITCHED 已切换至 🇯🇵 日本 B（225ms）
17:50:42.537 SingFlowVpnService: failover switched: af215d95… -> b09051a0…
AFTER_NOW=node-b09051a0-6f46-4888-ada9-5aff297de26b
AFTER_204=204
```

证据：`acceptance/r3_failover_runtime.log`、`acceptance/r3_home_after_failover.png`、
`acceptance/r3_nodes_after_failover.png`、`acceptance/r3_failover_logs.png`。

### 下一轮（R4）

1. 应用级国家分流 UI（选择应用 → 国家/收藏/直连/阻断）与连接诊断面板。
2. 七项切换参数滑块、`AtomicFile` 配置写入、`setUnderlyingNetworks` 物理网络重绑、内核生命周期 `Mutex`。
3. 主页展示当前国家/健康度；省电状态机接入服务采样与通知刷新。
4. 对已修复的三个运行时缺陷补回归测试/验收脚本断言。

### R4：应用级国家分流 UI、物理网络重绑与内核任务串行化（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 应用分流页 | 新增 `AppRoutingScreen`：搜索已安装应用、规则置顶、当前路由标签（跟随全局 / 国家 / 收藏 / 直连 / 阻断）；点击弹出模式选择，支持 4 个可用国家 | API 33 截图 |
| 分流保存 | `MainViewModel.saveAppRouteRule/deleteAppRouteRule`：写入 Room 后，已连接且多节点分组开启时用最新规则编译并 `VpnControl.reload` 热重载；否则提示生效条件 | `last_config` 中确认规则已写入 |
| 设置入口 | 设置页新增「应用与分流」卡片，显示已设置数量，点击进入分流页 | 截图 |
| 物理网络重绑 | 新增 `UnderlyingNetworkTracker`：监听非 VPN 物理网络，`setUnderlyingNetworks` 重绑，250ms 防抖后写日志；Wi-Fi ↔ 5G 切换不再卡旧网络 | 编译 + 运行 |
| 内核任务串行化 | `SingFlowVpnService` 新增 `engineJob`，start/reload 先取消上一内核任务，避免快速连点导致两套 Go 运行时/两个 TUN | 编译 |
| 运行时验收 | `com.android.settings` 指定 🇯🇵 日本：Room 规则 `COUNTRY/JP`，热重载配置包含 `package_name:["com.android.settings"] → group-JP`；TUN 重建为 `tun1 172.19.0.1/30`，mixed 代理 `generate_204=204` | `acceptance/r4_app_routing_runtime.log` |

> 说明：应用/国家规则属于路由段，sing-box Clash API 不支持运行时修改规则，因此保存规则会重建 TUN（接口名可能从 tun0 变为 tun1）；普通的节点切换仍走 Selector，不重建 TUN。

证据：`acceptance/r4_app_routing_list.png`、`acceptance/r4_app_routing_assigned.png`、
`acceptance/r4_after_routing_reload.png`、`acceptance/r4_app_routing_runtime.log`。

### 下一轮（R5）

1. 七项切换参数滑块 + 连接诊断面板（最近分流命中、当前 Selector、延迟历史）。
2. 省电状态机接入服务：息屏 15s 暂停速率采样/通知刷新/主动巡检，亮屏恢复。
3. 主页展示当前国家、健康分与节点名；订阅自动更新 Worker。
4. 为 `UnderlyingNetworkTracker`、应用规则热重载补回归测试/脚本断言。

### R5：三级省电接入服务、七项参数面板与慢速节点判定修复（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 省电接入 | `SingFlowVpnService` 注册屏幕/省电广播（`RECEIVER_NOT_EXPORTED`）与 `ProcessLifecycleOwner` 前后台观察，驱动 `PowerStateController`；L3 暂停速率采样、通知刷新与主动巡检，亮屏恢复 | API 33：L1 → L2 → 15s 后 L3 → 亮屏 L1 |
| 采样暂停 | `TrafficAndLogMonitor.setPaused()`：L3 停止每秒采样，降低基带与 UI 唤醒 | 日志 + 代码 |
| 通知循环 | 修复原先 `while(true)` 通知协程无法取消的问题：改为 `notificationJob`，按功耗档位重启（L2 3s / L3 60s 空转），reload/stop 可取消 | 代码审查 |
| 参数面板 | 设置页新增「细节参数微调」：探活超时、失败确认次数、择优分差、延迟改善、慢速阈值、冷却时长 6 个滑块 + 跨国防灾开关；拖动仅更新本地状态，松手才落盘 | 截图 |
| 主页 | 当前节点卡片新增国家/地区与健康标签/分数（如「日本 B · 日本 · 健康 78分」） | 截图 |
| 缺陷修复 | 探活成功但 RTT 超过慢速阈值时不再判定「节点已死」并反复扣分（此前会把健康分打到 0），慢速只影响择优切换 | `FailoverControllerTest` 更新并通过 |
| 生命周期修复 | `ProcessLifecycleOwner.addObserver` 必须在主线程调用，否则抛 `IllegalStateException`；改为主线程 Handler post | 运行时无该异常 |

**运行时日志**：

```text
18:18:56 power profile -> L1_FOREGROUND
18:19:05 failover: action=HEALTHY
18:20:02 power profile -> L2_BACKGROUND_SCREEN_ON
18:20:17 power profile -> L3_SCREEN_OFF
18:20:22 power profile -> L1_FOREGROUND（唤醒）
```

息屏 L3 期间 35 秒内无任何 `failover:` 探活日志（前一轮验证），满足「息屏停止主动探活」。

证据：`acceptance/r5_power_runtime.log`、`acceptance/r5_failover_advanced.png`、
`acceptance/r5_home_node_health.png`。

### 下一轮（R6）

1. 连接诊断面板：当前 Selector、最近分流命中、延迟历史。
2. 订阅自动更新 Worker（WorkManager 或前台服务）与订阅元数据展示。
3. 节点导出/二维码、配置备份与恢复。
4. 为省电档位切换、监控暂停/恢复补服务级回归测试。

### R6：订阅管理、流量元数据与 WorkManager 自动更新（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 订阅核心 | `SubscriptionManager`：新增/刷新/删除订阅，支持多行链接、整段 Base64、sing-box JSON；5 MiB 内容上限；按 `configHash` 保留节点健康统计并清理订阅内失效节点 | 本地订阅源导入 2 节点 |
| 流量元数据 | `SubscriptionUserInfo` 解析 `Subscription-Userinfo`（upload/download/total/expire），订阅卡展示已用/总量/到期 | 612 MB / 100 GB，到期 2026-01-01 |
| 自动更新 | `SubscriptionUpdateWorker`（12 小时周期、联网约束、唯一任务）+ `SubscriptionWorkScheduler`，失败返回 retry；使用 Hilt `EntryPoint`，不引入 hilt-work | `WorkSpec` interval=43200000，强制运行成功 |
| UI | 新增 `SubscriptionsScreen`：订阅列表、添加对话框、立即更新、删除确认、自动更新开关；设置页新增订阅入口 | 截图 |
| 数据层 | `SubscriptionDao.getAll/getById`、`NodeDao.getByGroup`、`NodeRepository.getByGroup` | 编译 + 运行 |

**运行时验收**：

```text
subscription=[('127.0.0.1','http://127.0.0.1:8088/sub',104857600,536870912,107374182400,1767225600)]
node_groups=[('222cf376-...',2),('adb',4)]
WorkSpec=[('SubscriptionUpdateWorker',43200000,0)]
worker_log=refresh 127.0.0.1: true 已更新 2 个节点
```

证据：`acceptance/r6_subscription_runtime.log`、`acceptance/r6_subscription_list.png`、
`scripts/dev/subscription-server.js`。

### 下一轮（R7）

1. 连接诊断面板：当前 Selector、最近分流命中、延迟历史。
2. 节点导出/二维码、配置备份与恢复。
3. 订阅增量更新测试（节点增删对比）与 Worker 失败重试策略细化。
4. 为省电档位切换、监控暂停/恢复补服务级回归测试。

### R7：连接诊断面板与 Clash 数据解析（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| Clash 解析 | 新增 `ClashProxyGroup` / `ClashConnection` / `ClashTraffic` 与纯函数 `ClashResponseParser`，解析 `/proxies` 代理组、`/connections` 累计流量与活跃连接 | 3 个 JVM 测试 |
| API 扩展 | `ClashApiClient.proxyGroups()` / `traffic()` | 编译 + 运行 |
| 诊断页 | `DiagnosticsScreen`：隧道状态、当前节点/健康分、12 个代理组及当前成员、累计流量、活跃连接（域名、规则、出站链、上下行） | API 33 截图 |
| 轮询 | `DiagnosticsViewModel` 页面可见时每 2 秒刷新，离开页面停止 | 运行 |
| 入口 | 设置页「连接诊断（Selector / 活跃连接）」 | 截图 |
| 缺陷修复 | Clash `/proxies` 响应外层为 `{"proxies":{...}}`，初版按顶层遍历导致「代理组（0）」；兼容外层包装后恢复 12 个组 | 修复前后对比 |

**运行时验收**：

```text
groups=12
connections=19（UI 抓取瞬间 24）
uploadTotal=40658 downloadTotal=17241
UI=代理组（12）/ 活跃连接 / 累计流量
```

证据：`acceptance/r7_diagnostics_runtime.log`、`acceptance/r7_diagnostics.png`、
`acceptance/r7_diagnostics_connections.png`。

### 下一轮（R8）

1. 节点分享链接导出、二维码展示与批量复制。
2. 配置/节点/订阅备份与恢复（本地文件 + WebDAV 可选）。
3. 抗封锁增强：TLS fragment、uTLS 指纹、多路复用 padding 配置项。
4. 订阅增删节点对比测试与 Worker 重试策略细化。

### R8：节点二维码导出与本地备份恢复（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 二维码 | 接入 ZXing `core`，`QrCodeGenerator` 将分享链接编码为 640px 二维码；当前节点展示二维码 | API 33 截图可扫 |
| 分享 | 当前节点/全部节点链接的复制与系统分享（`ACTION_SEND`），批量链接按 `rawShareUri` 去重 | 6 条链接 |
| 备份格式 | `BackupBundle`：版本、导出时间、完整设置、节点（`ProxyNode` 序列化）、订阅、应用分流规则 | JSON 9,548 B |
| 导出/恢复 | 通过 SAF `CreateDocument` / `OpenDocument` 选择本地 JSON；恢复采用合并语义，不清空现有数据，设置整体覆盖 | 恢复提示成功 |
| 入口 | 设置页「导出与备份（节点二维码 / JSON）」 | 截图 |
| 约束 | 非分享链接导入的节点无法生成二维码，界面明确提示 | 运行 |

**运行时验收**：

```text
backup_size=9548 bytes
version=1 nodes=6 subscriptions=1 appRules=1 countryRouting=True
qr=当前节点 JP-SUB-B 二维码生成成功
restore=恢复完成：6 个节点、1 个订阅、1 条应用规则，请重新连接
```

证据：`acceptance/r8_export_qr.png`、`acceptance/r8_backup_restore.png`、
`acceptance/r8_export_backup_runtime.log`。

### 下一轮（R9）

1. 抗封锁增强：TLS fragment、uTLS 指纹、多路复用 padding 配置项与设置页开关。
2. WebDAV 备份（可选）与备份加密。
3. 订阅增删节点对比测试与 Worker 重试策略细化。
4. 节点分组拖拽排序、批量删除与导出选中节点。

### R9：TLS 分片、uTLS 指纹与多路复用（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 数据模型 | `AntiBlockingConfig`：`tlsFragment`、`utlsFingerprint`、`multiplexEnabled`、`multiplexProtocol`、`multiplexPadding`、`multiplexMaxStreams`，并入 `AppSettings` | 序列化持久化 |
| 配置生成 | `ProxyOutboundFactory`：TLS 节点输出 `fragment/record_fragment/fragment_fallback_delay`；全局指纹覆盖节点指纹；VLESS/VMess/Trojan/SS 输出 `multiplex`，QUIC 协议跳过 | 3 个单测 |
| 设置 UI | 「抗封锁增强」卡片：TLS 分片开关、uTLS 指纹选择器、多路复用开关、h2mux/smux/yamux 选择、mux padding 开关 | 截图 |
| 兼容性 | 多路复用需服务端支持；默认关闭，避免普通节点被误伤 | 运行验证 |

**运行时验收**：

```text
persisted=tlsFragment=true,utlsFingerprint=chrome,multiplexEnabled=true,protocol=h2mux
config_multiplex=enabled=true protocol=h2mux max_streams=8 padding=false
mux_enabled_204=000（回环服务器不支持 mux，日志出现 sp.mux.sing-box.arpa:444）
mux_disabled_config=无 multiplex 字段
mux_disabled_204=204
```

说明：回环 VLESS 测试服务器不实现 mux，因此开启 mux 后 204 失败属于预期；关闭后立即恢复 204，证明确实写入并生效。

证据：`acceptance/r9_anti_blocking.png`、`acceptance/r9_anti_blocking_runtime.log`。

### 下一轮（R10）

1. WebDAV 备份与备份加密（可选）。
2. 订阅增删节点对比测试与 Worker 重试策略细化。
3. 节点分组拖拽排序、批量删除与导出选中节点。
4. 节点真实出口 GeoIP 批量探测与自动命名。

### R10：订阅节点差异计算与更新结果细化（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 差异计算 | 新增 `SubscriptionNodeDiffCalculator`：以 `configHash` 为键计算新增 / 保留 / 移除，避免改名或顺序变化导致误删 | 3 个单测 |
| 更新逻辑 | `SubscriptionManager` 改用差异结果：只删除真正消失的节点，保留仍存在节点的健康统计 | 编译 |
| 结果提示 | 刷新提示改为「新增 X、保留 Y、移除 Z」，便于判断订阅是否真的更新 | 代码 |
| 边界 | 空订阅（0 节点）仍按失败处理，不执行删除，避免服务端异常返回空内容时清空节点 | 既有逻辑 |

说明：本轮聚焦数据正确性，未新增页面；订阅页面沿用 R6 的 UI，提示文案随差异结果变化。

### 下一轮（R11）

1. WebDAV 备份与备份加密（可选）。
2. 节点分组拖拽排序、批量删除与导出选中节点。
3. 节点真实出口 GeoIP 批量探测与自动命名。
4. Worker 失败重试策略细化（指数退避、最大重试次数）。

### R11：节点批量管理（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 批量模式 | 节点页新增「批量管理」入口；标题切换为「已选 N 个」，卡片展示复选框并高亮选中 | 截图 |
| 批量操作 | 全选/取消全选、收藏、取消收藏、复制选中节点分享链接、批量删除（二次确认） | 运行验证 |
| 视图适配 | 批量模式下隐藏单卡收藏/删除按钮，避免误触；顶部导入/清空入口隐藏 | 截图 |
| 删除兜底 | 删除当前选中节点时自动选择剩余第一个节点；节点列表变化时自动收敛选择集合 | 代码 |
| ViewModel | 新增 `deleteNodes(Set)` / `setFavorite(Set, Boolean)`，批量写库并提示结果 | 运行验证 |

**运行时验收**：

```text
nodes=6 groups=4 favorites=2
batch_mode=已选 2 个
batch_favorite=已取消收藏 2 个节点 / 已收藏 2 个节点
```

证据：`acceptance/r11_batch_selection.png`、`acceptance/r11_batch_management_runtime.log`。

### 下一轮（R12）

1. WebDAV 备份与备份加密（可选）。
2. 节点分组拖拽排序（收藏组/国家组）。
3. 节点真实出口 GeoIP 批量探测与自动命名。
4. Worker 失败重试策略细化（指数退避、最大重试次数）。

### R12：WebDAV 云备份与密码加密（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 加密存储 | `WebDavCrypto`：AES/GCM/NoPadding + AndroidKeyStore 密钥，DataStore 仅保存 `Base64(IV+密文)` | 运行确认密文 |
| WebDAV 客户端 | `WebDavClient`：PUT 上传 / GET 下载单个 JSON，Basic Auth，60 秒超时、10 MiB 上限 | 运行验证 |
| 设置字段 | `AppSettings` 新增 `webdavUrl`、`webdavUsername`、`webdavPasswordCipher` | 持久化 |
| UI | 导出与备份页新增「WebDAV 云备份」：配置对话框（地址/用户名/密码）、上传备份、下载恢复 | 截图 |
| 安全 | 明文密码不落盘；密码留空表示保留原密码 | 代码 |

**运行时验收**：

```text
webdav_config=url=http://127.0.0.1:8089/backup.json user=test
password_storage=AES-GCM ciphertext in DataStore (AndroidKeyStore)
upload=PUT /backup.json 9860 bytes
download=GET /backup.json 9860 bytes
restore=已从 WebDAV 下载备份；恢复完成：6 个节点、1 个订阅、1 条应用规则
```

证据：`acceptance/r12_webdav_backup.png`、`acceptance/r12_webdav_config.png`、
`acceptance/r12_webdav_runtime.log`、`scripts/dev/webdav-server.js`。

### 下一轮（R13）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 节点真实出口 GeoIP 批量探测与自动命名。
3. Worker 失败重试策略细化（指数退避、最大重试次数）。
4. 备份加密（可选口令加密，防止 WebDAV 服务端读取节点凭据）。

### R13：口令加密备份（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 加密格式 | `EncryptedBackupEnvelope` + `BackupCipher`：PBKDF2-HMAC-SHA256（12 万次）+ AES-256-GCM，信封含 salt/iv/ciphertext | 3 个单测 |
| 导出 | 备份口令非空时输出加密信封；口令仅存在内存，不落盘 | 运行验证 |
| 恢复 | 自动识别加密信封；未填口令提示「该备份已加密，请先填写备份口令」，口令错误提示「口令错误或文件已损坏」 | 运行验证 |
| WebDAV | 上传/下载同样支持加密备份 | 代码 |
| UI | 本地备份区新增口令输入框，说明加密用途 | 截图 |

**运行时验收**：

```text
passphrase=test123（仅内存）
envelope=singflow-encrypted-backup / version=1 / iterations=120000 / ciphertext_len=13168
restore=恢复完成：6 个节点、1 个订阅、1 条应用规则，请重新连接
```

证据：`acceptance/r13_encrypted_backup.png`、`acceptance/r13_encrypted_backup_runtime.log`。

### 下一轮（R14）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 节点真实出口 GeoIP 批量探测与自动命名。
3. Worker 失败重试策略细化（指数退避、最大重试次数）。
4. 订阅更新时保留节点收藏/健康统计的端到端测试。

### R14：Worker 指数退避与 FakeIP 开关（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 重试策略 | 订阅 Worker 最多尝试 5 次；每次失败指数退避（30s 起）；达到上限返回 `failure`，避免无限重试耗电 | WorkSpec 验证 |
| 调度配置 | `PeriodicWorkRequest` 设置 `BackoffPolicy.EXPONENTIAL` + 30 秒初始退避 | `backoff_policy=0` |
| FakeIP | 设置页「网络」新增 FakeIP 开关（配置层此前已支持，缺 UI） | 截图 |
| 运行时 | FakeIP 开启后连接成功，`dns-fakeip` 与 `store_fakeip` 写入，`generate_204=204` | 实测 |

**运行时验收**：

```text
workspec=SubscriptionUpdateWorker interval=43200000 backoff_policy=0(EXPONENTIAL) backoff_delay=30000ms
fakeip_dns=true store_fakeip=true
http204=204
```

证据：`acceptance/r14_worker_fakeip.png`、`acceptance/r14_worker_fakeip_runtime.log`。

### 下一轮（R15）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 节点真实出口 GeoIP 批量探测与自动命名。
3. 订阅更新时保留收藏/健康统计的端到端测试。
4. 连接诊断增加“规则命中统计”和延迟历史曲线。

### R15：节点真实出口 GeoIP 批量实测（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 扫描逻辑 | `MainViewModel.scanGeoIp()`：逐个 `PUT /proxies/proxy` 切换节点 → 等待 350ms → 经 mixed 请求 Cloudflare Trace/GeoIP → 写回真实出口 IP 与国家 | 运行验证 |
| 安全兜底 | 扫描前保存当前 Selector，`finally` 恢复；仅多节点分组 + 已连接可用；跳过 WireGuard；单次最多 20 个节点 | 运行验证 |
| 数据层 | `NodeDao.updateEgress()` / `NodeRepository.updateEgress()` 写 `egressIp/countryCode/countryNameCn/isRegionVerified` | 编译 |
| UI | 节点页新增「实测节点真实出口」，二次确认并提示扫描期间网络短暂中断；扫描中显示进度态 | 截图 |
| 失败策略 | 节点不可用时不覆盖已有国家信息，只计入失败数 | 运行验证 |

**运行时验收**：

```text
nodes=6 (2 live loopback, 4 dead)
egress_8445=120.244.213.111 CN verified=1
egress_8446=120.244.213.111 CN verified=1
dead_nodes=unverified
selector_restored=node-b09051a0-6f46-4888-ada9-5aff297de26b
```

证据：`acceptance/r15_geoip_scan.png`、`acceptance/r15_geoip_scan_runtime.log`。

### 下一轮（R16）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 连接诊断增加规则命中统计与节点延迟历史曲线。
3. 订阅更新保留收藏/健康统计的端到端测试。
4. 节点详情页（单节点配置、分享、延迟趋势）。

### R16：诊断规则命中与出站链统计（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 聚合逻辑 | 新增 `ClashStats`：按规则、出站链首节点聚合活跃连接，空规则归为「未命中规则」 | 3 个单测 |
| ViewModel | `DiagnosticsViewModel` 增加 `ruleHits` / `chainHits`，每 2 秒随连接刷新 | 运行 |
| UI | 诊断页新增「规则命中（活跃连接）」与「出站链分布」两段，各展示前 10 项 | 截图 |
| 展示 | 规则形如 `rule_set=[geosite-cn geoip-cn] => route(direct)`，链形如节点 tag / direct | 截图 |

**运行时验收**：

```text
rule_hits=final 24, rule_set=[geosite-cn geoip-cn]=>route(direct) 1
chain_hits=node-b09051a0... 24, direct 1
active_connections=25
```

证据：`acceptance/r16_diagnostics_rule_stats.png`、`acceptance/r16_diagnostics_stats_runtime.log`。

### 下一轮（R17）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 节点详情页（配置预览、分享、延迟趋势）。
3. 订阅更新保留收藏/健康统计的端到端测试。
4. 节点延迟历史环形缓冲与诊断曲线。

### R17：节点延迟历史与诊断曲线（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 环形缓冲 | `LatencyHistoryStore`：每节点保留最近 40 次成功采样，进程内不落盘 | 3 个单测 |
| 采样来源 | Failover 巡检、批量探活引擎、TCP 测速均写入历史 | 代码 |
| 诊断 UI | 诊断页新增「当前节点延迟趋势」Canvas 折线，显示最近 N 次、最低/最高/最新延迟 | 截图 |
| 绑定节点 | `DiagnosticsViewModel.bindNode()`：切换节点时重绑历史并刷新 | 运行 |

**运行时验收**：

```text
probe=一键探活 x2（Clash API）
history=2 samples min=186ms max=274ms latest=186ms
chart=诊断页当前节点延迟趋势（Canvas 折线）
```

证据：`acceptance/r17_latency_history.png`、`acceptance/r17_latency_history_runtime.log`。

### 下一轮（R18）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 节点详情页（配置预览、分享、延迟趋势复用）。
3. 订阅更新保留收藏/健康统计的端到端测试。
4. 延迟历史落盘（可选）与断线期间的空洞标记。

### R18：节点详情页（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 入口 | 主页当前节点卡片新增信息按钮，进入节点详情 | 截图 |
| 详情信息 | 协议/地址、国家、健康标签/分数、当前延迟、探测成功次数、EMA/抖动、最近探测、实测出口、当前节点标记 | 截图 |
| 延迟趋势 | 复用 R17 环形历史与折线；无历史时提示先探活 | 运行 |
| 分享 | 分享链接二维码、复制、系统分享；非分享链接导入节点给出提示 | 运行 |
| 操作 | 收藏切换、设为当前节点、删除（二次确认） | 代码 |
| 复用 | 抽出 `LatencySparkline` 组件，诊断页与详情页共用 | 编译 |

**运行时验收**：

```text
entry=主页当前节点卡片 info 图标
fields=country/health/latency/probe success/EMA-jitter/last probe/egress IP/current marker
actions=收藏/设为当前/复制/分享/删除
```

证据：`acceptance/r18_node_detail.png`、`acceptance/r18_node_detail_runtime.log`。

### 下一轮（R19）

1. 节点分组拖拽排序（收藏组/国家组）。
2. 订阅更新保留收藏/健康统计的端到端测试。
3. 延迟历史落盘（可选）与断线空洞标记。
4. 节点详情展示该节点的分流规则命中（按域名/规则过滤连接）。

### R19：节点分组自定义排序（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 设置字段 | `AppSettings.countryGroupOrder` 持久化国家组顺序 | DataStore |
| 排序规则 | 收藏组固定置顶；国家组按自定义顺序，未排序列按中文名，未知地区最后 | 2 个单测 |
| UI | 节点页更多菜单 →「分组排序」；对话框中上/下移动并保存 | 截图 |
| 兼容 | 未配置顺序时保持原有按名称排序行为 | 单测 |

**运行时验收**：

```text
initial=CN, US, UN
action=US 上移一位并保存
persisted=countryGroupOrder=["US","CN","UN"]
result=节点页国家组顺序 US → CN → UN（收藏组仍置顶）
```

证据：`acceptance/r19_group_order.png`、`acceptance/r19_group_order_after.png`、
`acceptance/r19_group_order_runtime.log`。

### 下一轮（R20）

1. 订阅更新保留收藏/健康统计的端到端测试。
2. 延迟历史落盘（可选）与断线空洞标记。
3. 节点详情展示该节点相关连接与规则命中。
4. 分组拖拽手势（替代上/下按钮，可选）。

### R20：订阅刷新节点合并策略抽离与测试（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 纯函数 | 抽出 `NodeMerge.preserve(existing, incoming)`，集中定义订阅刷新合并规则 | 2 个单测 |
| 保留字段 | 收藏、健康分、探测次数、延迟、累计统计、已实测国家均保留 | 单测 |
| 更新字段 | 名称、服务器、端口、协议凭据、传输/TLS 配置按订阅刷新 | 单测 |
| 分享链接 | 新订阅缺省分享链接时保留旧链接 | 单测 |
| 国家策略 | 已实测国家不被订阅名称覆盖；未实测时才更新 | 单测 |

说明：本轮聚焦数据正确性，Repository 行为不变，仅将内联合并逻辑抽为可测试纯函数。

### 下一轮（R21）

1. 节点详情展示该节点相关连接与规则命中。
2. 延迟历史落盘（可选）与断线空洞标记。
3. 分组拖拽手势（替代上/下按钮）。
4. 订阅刷新 Worker 的端到端集成测试（MockWebServer）。

### R21：节点详情相关连接（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 观察器 | `NodeDetailViewModel`：每 2 秒拉取 Clash 连接，按 `node-{id}` 出站链过滤 | 运行 |
| 过滤逻辑 | `connectionsForNode()` 纯函数：按流量降序、最多 20 条 | 2 个单测 |
| UI | 详情页新增「该节点连接（N）」，展示域名、规则、↑/↓ 流量 | 截图 |
| 空态 | 未连接或暂无流量时显示对应提示 | 代码 |

**运行时验收**：

```text
connections=该节点连接（20）
rows=host/rule/↑↓bytes（chain=node-b090...）
```

证据：`acceptance/r21_node_connections.png`、`acceptance/r21_node_connections_runtime.log`。

### 下一轮（R22）

1. 延迟历史落盘（可选）与断线空洞标记。
2. 分组拖拽手势（替代上/下按钮）。
3. 订阅刷新 Worker 的端到端集成测试（MockWebServer）。
4. 节点详情页延迟历史与连接列表联动（点击连接查看规则详情）。

### R22：延迟历史持久化（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 持久化 | 历史写入 `files/singflow/latency_history.txt`，每节点最多 40 条；启动时恢复 | 运行 |
| 编码 | 纯文本 `nodeId|ts:lat;ts:lat`，`encodeHistory` / `decodeHistory` 可单测 | 3 个单测 |
| 生命周期 | `SingFlowApp.onCreate()` 调用 `LatencyHistoryStore.attach()` | 代码 |
| 清理 | `clear/clearAll` 同步重写文件；非法行解析时忽略 | 单测 |

**运行时验收**：

```text
file=files/singflow/latency_history.txt
content=b09051a0-...|1792702029423:3;1792702039472:4
restart=force-stop 后重新打开应用
chart=最近 2 次 · 最低 3 ms · 最高 4 ms（未连接状态）
```

证据：`acceptance/r22_latency_persisted.png`、`acceptance/r22_latency_persisted_runtime.log`。

### 下一轮（R23）

1. 断线空洞标记（失败采样在曲线中留空）。
2. 分组拖拽手势（替代上/下按钮）。
3. 订阅刷新 Worker 的端到端集成测试（MockWebServer）。
4. 节点详情连接列表点击查看规则详情。

### R23：延迟断线空洞标记（已完成）

| 模块 | 交付内容 | 验证 |
| :--- | :--- | :--- |
| 失败采样 | `LatencyHistoryStore.recordFailure()` 写入 `latency=0`，保留最近 40 条 | 单测 |
| 采样来源 | Failover 失败、批量探活失败、TCP 测速失败均写入失败采样 | 代码 |
| 曲线 | `LatencySparkline` 在失败点画灰色标记并断开折线，不跨空洞连线 | 截图 |
| 文案 | 诊断/详情显示「失败 N 次」，全失败时显示「全部失败」，最新失败显示「超时」 | 截图 |
| 持久化 | 失败采样同样落盘，重启后保留空洞 | 运行 |

**运行时验收**：

```text
seeded=b09051a0-...|ts:20;ts:0;ts:35
chart=最近 3 次 · 失败 1 次 · 最低 20 ms · 最高 35 ms
gap=失败点灰色标记，折线断开
```

证据：`acceptance/r23_latency_gap.png`、`acceptance/r23_latency_gap_runtime.log`。

### 下一轮（R24）

1. 分组拖拽手势（替代上/下按钮）。
2. 订阅刷新 Worker 的端到端集成测试（MockWebServer）。
3. 节点详情连接列表点击查看规则详情。
4. 失败采样在诊断页标注连续失败次数/熔断状态。
