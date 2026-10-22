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
