# HANDOFF — SingFlow 原生内核联调收尾

> 生成时间：2026-10-21
> 更新时间：2026-10-22（追加：release 精简包 80.5 MB，真机 Android 17 / arm64 验收 18/18 全绿）
> 来源：OpenCode 会话「SingFlow开发」（`ses_eab443d30ffdXWf6WBPExEkRPl`，累计 2.5M input tokens，经历 7 次上下文压缩）
> 用途：跨工具/跨模型交接。接手者**先读完本文件 + `AGENTS.md` + `TASK_PROGRESS.md`**，再动代码。

---

## 0. 零上下文接手（5 分钟）

> 给下一个 Agent / 模型：读完本节即可动手，细节在后续章节。

### 0.1 仓库与版本快照

| 项 | 值 |
| :--- | :--- |
| 仓库 | `https://github.com/yangche828/SingFlow`（**私有**，默认分支 `main`） |
| 当前 `main` | 以远端为准（本节首次提交为 `55aa807`；用 `git log -1` 取实际值） |
| 最新 tag | `v1.0.1` = `d4cf7e2`（release 构建 + 真机验收文档）；`v1.0.0` 指向更早的 `0cbfcd1`，仅作历史 |
| Release | `v1.0.1` 已附 `SingFlow-1.0.1-arm64.apk`（80.5 MB，SHA256 `7f641f022d06…`)；`v1.0.0` 无资产 |
| 已构建产物（本机） | `app/build/outputs/apk/release/app-release.apk`（80.5 MB）、`app/build/outputs/apk/debug/app-debug.apk`（219.7 MB）、桌面副本 `C:\Users\yangche\Desktop\SingFlow-1.0.0-arm64.apk` |
| 内核 | `app/libs/libbox.aar` 已入库（56 MB），clone 后直接构建，无需额外下载 |

### 0.2 三条命令验证现状

```bash
git clone https://github.com/yangche828/SingFlow.git && cd SingFlow
echo "sdk.dir=D:/Apps/Android/Sdk" > local.properties   # 换成你的 SDK 路径
./gradlew :app:testDebugUnitTest       # 期望 31 tests / 0 failures
./gradlew :app:assembleDebug           # 期望 BUILD SUCCESSFUL，产物 219.7 MB
```

端到端验收（不需要公网节点，回环服务端即可 18/18）：

```powershell
node scripts\dev\vless-loopback-server.js --port 8443          # 另开一个终端
pwsh -File scripts\e2e_adb_verify.ps1 -ProxyLog <日志路径> -RemoteDns https://doh.pub/dns-query
```

### 0.3 环境前置（本机实测值）

- JDK 21（`D:\Apps\JDK\jdk-21`）、Android SDK（`D:\Apps\Android\Sdk`，platforms 33/35/36）、Node（`D:\Apps\nodejs\node.exe`）。
- Gradle 8.10.2（wrapper）/ AGP 8.7.2 / Kotlin 2.0.21；`compileSdk`、`targetSdk` 取本机最高平台（当前 36）。
- 依赖下载走 `127.0.0.1:10808`（已写入 `C:\Users\yangche\.gradle\gradle.properties`）。
- Shell 必须用 `pwsh`；PowerShell 5.1 解析 E2E 脚本会报 `MissingEndCurlyBrace`。

### 0.4 设备与网络（最容易卡住的地方）

- 真机：HONOR **BKQ-AN10**，Android **17（API 37）**，`arm64-v8a`，页大小 4096，adb 序列号 `AMRF025A21005896`。
- **adb 看不到手机**（设备管理器里有 `ADB Interface`，但 `adb devices` 为空）：HONOR/HUAWEI 常把该接口 GUID 绑成厂商值，
  改回 adb 的 `{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}` 再 `pnputil /restart-device <InstanceId>`，具体命令见 §7。
- 真机跑验收前先 `adb shell svc power stayon true`：屏幕熄灭时 `uiautomator dump` 得到空快照，「已保护 / SingFlow 要素」会误报失败。
- release 包 `debuggable=false` → `run-as` 不可用，「节点入库」断言只能 WARN（不是缺陷）。
- 推送 GitHub：Schannel 在该代理下握手失败，必须带
  `git -c http.sslBackend=openssl -c http.version=HTTP/1.1 -c http.proxy=http://127.0.0.1:10808 -c https.proxy=http://127.0.0.1:10808 push origin main`。
  若代理节点失效（端口在监听但全部超时，`git push` 报 `Empty reply from server` / `Connection was reset`），
  可改走 GitHub API：`git credential fill` 取 token，`POST /git/blobs` → `/git/trees` → `/git/commits` → `PATCH /git/refs/heads/main`。
  注意 PowerShell 发 JSON 必须用 `[Text.Encoding]::UTF8.GetBytes(...)`，否则中文提交信息会被二次编码成乱码。

### 0.5 需要人提供、仓库里没有的东西

| 项 | 说明 |
| :--- | :--- |
| GitHub 凭据 | 账号 `yangche828`，token 存在 Windows 凭据管理器（`git:https://github.com`）；本机**没有 `gh` CLI** |
| 真实节点 | 仓库里的真实节点 URI 已脱敏（`203.0.113.10` / `node.example.com` / UUID 全 `0`），**无法复现「真实公网节点 17/17」**；要复现需重新提供节点，跑法见 §7 |
| 手机 | 上述 HONOR 真机，App 仍安装并保留一个回环 VPN 配置（已 force-stop，不影响手机网络） |

### 0.6 先别动的地方

- 别删 `SingFlowVpnService.fallbackTunDnsServers()`：删了客户端 DNS 会再次绕过隧道（见 §4 踩坑表）。
- 别把 `InboundConfigInterceptor` 的 `tun.stack = "gvisor"` 改回默认：会导致 TUN TCP 卡死。
- sing-box 语法红线与 Android 13~17 兼容红线见 `AGENTS.md`；改配置生成逻辑必须同步单测断言。
- 已知未验证项：**真 16 KB 页设备的运行时证据**（现有真机是 4 KB 页，`zipalign -c -P 16` 只是静态校验）。

## 1. 任务是什么

按 `singflow_architecture_and_ai_prompts.md` 完成 SingFlow（Android VPN 客户端，Kotlin + Compose + sing-box 1.14.3）Phase 0~6，并把 App 从「TUN 诊断模式」升级为**真正转发流量**，交付可用 APK。

## 2. 验收标准（全部已达成）

- [x] `./gradlew :app:testDebugUnitTest` 全绿（31/31）
- [x] `:app:assembleDebug` 成功，`app-debug.apk` 219.7 MB（> 5MB）
- [x] API 33 与 API 36 模拟器各自跑通：安装 → 导入节点 → 连接 → `tun0` 在线 → **VPN 内应用真实出网**（Chrome 渲染 `example.com` + VLESS 服务端实际收到 TCP / UDP-XUDP 流量）→ 无 `FATAL EXCEPTION` / `panic:`
- [x] 全程遵守 `AGENTS.md` 的 sing-box 1.14+ 六大语法红线与 Android 13~17 兼容红线（APK 通过 `zipalign -c -P 16` 16KB 对齐校验）
- [x] **用户提供的真实公网节点（VLESS + TLS + WS + Cloudflare）连通并可访问谷歌**：`tun0` 在线、`TUN DNS` 已下发、
  Chrome 渲染 `google.com`、Clash API 显示 `www.google.com → 142.251.x.x:443 chains=proxy`（单次页面下行 794,699 B）
- [x] **release 精简包在真机（HONOR BKQ-AN10 / Android 17 / arm64-v8a）18/18 PASS**：
  80.5 MB（R8 + 资源压缩 + 仅 arm64），`tun0`/MTU 9000/前台服务 `systemExempted`/TUN DNS/UI「已保护」/浏览器真实出网全部通过，零崩溃

证据：`acceptance/e2e_api33.log`（`.ps1` 18/18）、`acceptance/e2e_api36.log`（`.ps1` 19/19）、
`acceptance/e2e_api36_sh.log`（`.sh` 19/19）、`acceptance/e2e_api33_sh.log`（`.sh` 17/17）、
`acceptance/vless-loopback.log`（代理侧原始日志）、`acceptance/e2e_api36_attempt1_egress_timeout.log`（沙箱出口抖动留档）、
`acceptance/e2e_api36_realnode.log`（真实节点 `.ps1` 17/17）、`acceptance/e2e_api36_realnode_sh.log`（真实节点 `.sh` 17/17）、
`acceptance/google_api36_realnode.png`（Chrome 中的 google.com）、`acceptance/realnode_connections.json`（Clash API 快照）、
`acceptance/e2e_realdevice_release_arm64.log`（真机 release 18/18）、`acceptance/realdevice_release_home.png`（真机首页）。

## 3. 现在到哪一步了

### 已完成（Phase 0~6 全部通过，可交付）

- Phase 0~5 全部门禁通过：`kspDebugKotlin`、`testDebugUnitTest` 31/31、`compileDebugKotlin`、`assembleDebug`、`lintDebug`。
- 云端构建出 `libbox.aar`（sing-box v1.14.3，ABI arm64-v8a + x86_64，58,712,166 B，16KB 对齐断言通过），已落地 `app/libs/libbox.aar`。仓库 `yangche828/singflow-libbox`，Release tag `libbox-v1.14.3`。
- `LibboxBridge.kt` 完成 1.14.3 反射桥（**全部经类名字符串反射，无编译期依赖**）：`Libbox.setup(SetupOptions)` + `newCommandServer(...)` + `CommandServer.startOrReloadService(config, OverrideOptions)`；`PlatformInterface.openTun(TunOptions): Int` 返回 `pfd.fd` 且**不 detach**。
- **真实转发验收在 API 33 与 API 36 上均通过**：安装 → 导入节点 → 连接 → `tun0`（`172.19.0.1/30`，MTU 9000）→ 前台服务 `isForeground=true` + `systemExempted` → 节点入库 → UI 显示「已保护」（非诊断模式）→ **Chrome（VPN 内应用）渲染出 `example.com` 的 “documentation examples”** → VLESS 服务端日志新增 TCP `-> :443` 与 UDP `(xudp)` 记录 → 全程零 `FATAL EXCEPTION` / tombstone。
- 验收工具链已作为仓库交付物：`scripts/dev/vless-loopback-server.js`（本机 VLESS TCP+UDP/XUDP 服务端）+ 重写的 `scripts/e2e_adb_verify.ps1` / `.sh`（无设备时自动拉起 AVD、UI 自动化改远程 DNS、真实转发断言、逐行写日志）。
- **真实公网节点验收已完成**（用户提供 `vless://…@203.0.113.10:8443?security=tls&sni=node.example.com&type=ws&path=/`，Cloudflare 边缘 + WSS）：
  - PC 侧先跑独立探针 `node scripts/dev/probe-vless-ws.js --host 203.0.113.10 --port 8443 --sni node.example.com --path / --proxy http://127.0.0.1:10808`
    → `TLS 握手成功 ALPN=http/1.1`、证书 `CN=node.example.com`、`HTTP/1.1 101 Switching Protocols`（先把「节点本身不通」和「App 有问题」分开）。
  - 模拟器带 `-http-proxy http://127.0.0.1:10808` 启动（本机直连 `203.0.113.10:8443` 被 reset），**节点 URI 未做任何改写**，TLS/WS/VLESS 端到端。
  - 结果：`.ps1` **17/17 PASS**、`.sh` **17/17 PASS**；Chrome 渲染 `google.com`；隧道内实测 `http://www.google.com/generate_204` → 204、`https://www.google.com/` → 200/87,187 B；
    Clash API `www.google.com → 142.251.150.119:443 chains=proxy`（页面加载下行 794,699 B）。
- **本轮修掉一个真实缺陷（客户端 DNS 逃出隧道）**：VPN 网络 `DnsAddresses` 为空 → Android 退回底层网络解析 → 拿到 GFW 污染答案 →
  Chrome 把污染 IP 交给隧道，TLS 失败（`ERR_SSL_VERSION_OR_CIPHER_MISMATCH`）。
  修法：`SingFlowVpnService.establishTunFromOptions` 在内核未下发 DNS 时兜底 `1.1.1.1`（+ IPv6），并打印 `TUN DNS: 内核下发=…, 实际下发=…`；修复后 `DnsAddresses=[1.1.1.1]`。

### 收尾（已完成）

- 已停掉回环 Node 进程（`vless-loopback-server.js`）与遗留的 `http-e2e.js`，已关闭测试模拟器，已删除 `SingFlow-E2E-8443` 防火墙规则，已清理过期 acceptance 临时文件。
- 唯一遗留的非阻塞改进：`AppSettings.remoteDns` 的代码默认值仍是 `https://1.1.1.1/dns-query`，**已实测该地址在真实节点上被 TLS alert 拒绝**
  （sing-box 报 `remote error: tls: handshake failure`，`https://doh.pub/dns-query` 与 `https://dns.google/resolve` 均正常），建议改为 `https://doh.pub/dns-query`；
  验收脚本已能自动改这个设置（`-RemoteDns` / `REMOTE_DNS`），但产品默认值未动（需用户确认）。
- release 构建策略已定（`app/build.gradle.kts`）：R8 + `shrinkResources` + `abiFilters arm64-v8a` + 复用 debug 签名，
  APK 219.7 MB → **80.5 MB**；真机 Android 17 / arm64 验收 **18/18 PASS**。

## 4. 已经踩过的坑（**不要重走**）

| 现象 | 根因 | 修法（已提交在代码里） |
| :--- | :--- | :--- |
| `no available network interface` | `getInterfaces()` 返回空迭代器 | 返回真实网卡列表（`io.nekohasekai.libbox.NetworkInterface` + 网关/DNS/flags/type/metered） |
| 内核启动失败，卡在下载 `.srs` | 启动阶段同步拉 GitHub 规则集被 reset | 随 APK 内置 `.srs`（`assets/ruleset/` → `files/singflow/ruleset/`），配置用 `type: local`；远程回退保留 `download_detour: direct` |
| 选路阻塞 / 默认网卡为空 | 把 `tun` 当作默认网卡推送 | `resolvePhysicalInterface()` 跳过 `tun*` 与 `TRANSPORT_VPN`；`onLost` 重新解析而非清空 |
| **TUN 内 TCP 流量不进 router**（只有 UDP DNS 有 flow） | `tun.stack` 默认 `mixed`＝系统 TCP + gVisor UDP，Android 无 NAT 权限 | `InboundConfigInterceptor` 显式 `put("stack","gvisor")`，删除已失效的 `endpoint_independent_nat` |
| 连接后 SIGSEGV / `panic: invalid memory address` | Kotlin `findConnectionOwner` 返回 `null`，Go 侧 `platformInterfaceWrapper.FindConnectionOwner` 直接解引用 | 改为 `newConnectionOwner(uid, packages)`，**始终返回非空** `ConnectionOwner`（查不到时 `userId = -1`） |
| 出站探测失败 | `download_detour` 指向 `proxy` 造成自引用 | 改 `direct` |
| 服务端收到 `atyp 0` 帧，看起来像坏包 | 未配 `packet_encoding` 时 sing-box 默认 XUDP（CommandMux + XUDP 帧） | **无害**：Xray / sing-box 服务端均支持，无需改 App；验收脚本按 TCP + UDP(xudp) 双栈断言 |
| PowerShell 脚本里节点 URI 的 `?encryption` 被吃掉（变成 `:=none&...`） | PowerShell 双引号串中 `?` 是合法变量名字符，`$ProxyPort?encryption` 被当成变量名 | 写成 `"${ProxyPort}?encryption=..."` |
| Git Bash 下 `adb install` 报 `failed to stat /d/...apk` | 脚本用 `MSYS_NO_PATHCONV=1` 关掉了自动路径转换，host 侧路径原样传给 `adb.exe` | host 侧路径显式转换：`to_win_path()`（`cygpath -w`） |
| 验收脚本跑一半报 `syntax error near unexpected token 'fi'` / `en: command not found` | **bash 边读边执行脚本**，运行期间编辑文件会读到半截内容 | bash 验收脚本运行期间禁止编辑（`.ps1` 在启动时整体解析，不受影响） |
| `uiautomator dump` 失败时读到上一次的界面快照 | 设备端 `/sdcard/singflow_ui.xml` 是旧文件 | dump 前先 `rm -f`，并校验 XML 含 `<hierarchy`，失败自动重试 3 次 |
| 冷启动后 UI 自动化找不到元素（如「设置」Tab / DNS 输入框） | 界面未就绪时 dump 取不到 idle 状态，或点击未生效 | 抓取重试 + 等待循环；DNS 输入框先按文本 `://` 匹配，退化时要求页面出现 `DoH` 标签才取第一个 `EditText` |
| **浏览器访问 google 报 `ERR_SSL_VERSION_OR_CIPHER_MISMATCH`，但经 App 的 `mixed` 入站（7890）访问却 200** | VPN 网络 `DnsAddresses: [ ]`（内核未下发 DNS）→ Android 回退底层网络解析 → GFW 污染（`www.google.com` → `157.240.7.20` / `31.13.92.37`，Meta IP）→ Chrome 把污染 IP 交给隧道 | `establishTunFromOptions` 在 DNS 列表为空时兜底 `1.1.1.1`（+IPv6），日志打印 `TUN DNS: 内核下发=无, 实际下发=1.1.1.1`；验收脚本新增断言 `DnsAddresses` 非空 |
| 验收脚本「测试节点已入库」误判失败（回环节点永远通过） | ① URI fragment 是 percent-encoded 的中文名，脚本拿原始串比对数据库里的解码名；② `adb` 输出按非 UTF-8 控制台编码解码成乱码 | 两个脚本都做 percent-decode；`.ps1` 设 `[Console]::OutputEncoding = UTF8` 并改用 `select hex(name)` 做编码无关比较 |
| 模拟器上 UI 断言读到 `System UI isn't responding` / `Wait` | 低配 + 软件 GPU 下 SystemUI 偶发 ANR，弹窗盖住真实界面 | 脚本新增 `Clear-AnrDialog` / `ui_clear_anr`：检测到 `isn't responding` 自动点 `Wait`，浏览器场景重新发起导航 |
| 诊断「DNS 到底走了哪条链路」很费时 | 内核日志 level=info 不含 DNS 细节 | 直接问 Clash API：`curl http://127.0.0.1:9090/dns/query?name=…&type=A`（走的是内核 DNS 模块，返回哪个服务器/哪个 IP 一目了然；失败会返回 `remote error: tls: handshake failure` 这类原文） |

## 5. 关键文件

| 路径 | 作用 | 注意 |
| :--- | :--- | :--- |
| `app/src/main/java/com/singflow/android/core/engine/LibboxBridge.kt` | 1.14.3 反射桥、`resolvePhysicalInterface`、`buildNetworkInterfaces`、`newConnectionOwner` | 核心风险点，改这里必须回归 API 33/36 |
| `app/src/main/java/com/singflow/android/core/config/interceptors/InboundConfigInterceptor.kt` | TUN inbound 生成 | `stack=gvisor` 就在这里，别被"还原成默认" |
| `app/src/main/java/com/singflow/android/core/engine/SingFlowVpnService.kt` | `establishTunFromOptions`、异步启动、诊断回退、**TUN DNS 兜底** | 只允许在这里调 `establish()`；`fallbackTunDnsServers()` 不能删，删了客户端 DNS 会绕过隧道 |
| `app/src/main/java/com/singflow/android/core/config/interceptors/RoutingRuleInterceptor.kt` | 规则顺序：sniff → hijack-dns → 私网直连 → CN 分流 | |
| `app/src/main/java/com/singflow/android/core/config/interceptors/DnsConfigInterceptor.kt` | `dns-remote` / `dns-direct` | |
| `app/src/main/java/com/singflow/android/SingFlowApp.kt` | `installRuleSets()`、`ruleSetDir` | 按 `BuildConfig.VERSION_CODE` 打 `.version` 标记 |
| `app/src/test/java/com/singflow/android/core/config/SingBoxConfigPipelineTest.kt` | 31 个单测（含 `gvisor` / `direct` 断言） | 改拦截器后同步断言 |
| `app/src/main/java/com/singflow/android/ui/screens/SettingsScreen.kt` | DNS/MTU/Mixed 端口编辑 | 模拟器改 remoteDns 走这里 |
| `app/libs/libbox.aar` | 原生库（7 entries，56 MB） | 已入库；仓库公开时须履行 GPLv3 源码义务 |
| `app/build/outputs/apk/debug/app-debug.apk` | 最新调试 APK（约 219.7 MB） | 含双 ABI `.so`；模拟器验收用这个 |
| `app/build/outputs/apk/release/app-release.apk` | 发布包（**80.5 MB**） | R8 + 资源压缩 + 仅 `arm64-v8a` + debug 签名；x86_64 模拟器跑不了 |
| `acceptance/e2e_realdevice_release_arm64.log` / `realdevice_release_home.png` | 真机 release 验收 18/18 + 首页截图（Android 17 / arm64） | 仅 arm64，需 `-RemoteDns https://doh.pub/dns-query` |
| `scripts/dev/vless-loopback-server.js` | 本地 VLESS 回环测试服务端（TCP + UDP/XUDP，默认 8443） | 可复现验收的必备件；`node scripts/dev/vless-loopback-server.js --port 8443` |
| `scripts/e2e_adb_verify.ps1` | 主验收脚本（Windows/pwsh） | 设备自启、UI 自动化、真实转发断言；运行中可安全编辑 |
| `scripts/e2e_adb_verify.sh` | 验收脚本（Linux/macOS/Git Bash） | **运行期间禁止编辑该文件**（bash 边读边执行） |
| `acceptance/e2e_api33.log`、`acceptance/e2e_api36.log` | 主脚本（`.ps1`）两个 API 版本的验收原始日志（18/18、19/19） | 由验收脚本自动写入 |
| `acceptance/e2e_api36_sh.log`、`acceptance/e2e_api33_sh.log` | 姊妹脚本（`.sh`）的验收原始日志（19/19、17/17） | 同一套断言，用于跨平台复现 |
| `acceptance/vless-loopback.log` | 本机 VLESS 服务端原始流量日志 | 断言按「新增行数」计算，可跨轮累计 |
| `scripts/dev/probe-vless-ws.js` | PC 侧独立 VLESS-WS 连通性探针（CONNECT 代理 → TLS(SNI) → WS 101），不依赖 App | 跑模拟器 E2E 前先跑它，可区分「节点不通」与「App 有问题」 |
| `acceptance/e2e_api36_realnode.log` / `_sh.log` | 真实公网节点验收 17/17 PASS（`.ps1` / `.sh`） | 需 `-RemoteDns https://doh.pub/dns-query` |
| `acceptance/google_api36_realnode.png` / `app_log_api36_realnode.png` / `app_home_api36_realnode.png` | 真实节点下 Chrome 渲染 google.com / App 日志（`TUN DNS: 内核下发=无, 实际下发=1.1.1.1`）/ 首页已保护 + 实时速率 | 视觉证据 |
| `acceptance/realnode_configuration.json` / `realnode_connections.json` / `realnode_singbox_logs.jsonl` | 设备上实际送入内核的配置 / Clash API `/connections` 与 `/logs` 快照 | 排查分流与 DNS 的首选证据 |

## 6. 交付物与后续建议

### 交付物

1. `app/build/outputs/apk/debug/app-debug.apk`（219.7 MB，arm64-v8a + x86_64，含 `libbox.aar`）。
   真机自用请用 `app/build/outputs/apk/release/app-release.apk`（**80.5 MB**，R8 + 资源压缩 + 仅 arm64-v8a，复用 debug 签名）。
2. 源码：`app/src/main/...`（core: model/database/parser/config/engine，ui: theme/components/screens）。
3. 验收工具链：`scripts/dev/vless-loopback-server.js`、`scripts/e2e_adb_verify.ps1`、`scripts/e2e_adb_verify.sh`。
4. 验收证据：`acceptance/e2e_api33.log`（18/18）、`acceptance/e2e_api36.log`（19/19）、`acceptance/e2e_api36_sh.log`（19/19）、`acceptance/e2e_api33_sh.log`（17/17）、`acceptance/vless-loopback.log`、
   以及真实公网节点证据 `acceptance/e2e_api36_realnode.log`（17/17）、`acceptance/e2e_api36_realnode_sh.log`（17/17）、`acceptance/google_api36_realnode.png`、`acceptance/realnode_*.json*`。
5. 文档：`AGENTS.md`（架构契约 + 红线）、`TASK_PROGRESS.md`（门禁与验收记录）、本文件（交接 + 踩坑）。

### 后续建议（按优先级）

1. 把 `AppSettings.remoteDns` 默认值改成 `https://doh.pub/dns-query`（当前 `1.1.1.1` 在国内不可达）。
2. 对外发布前替换 release 签名为正式 keystore（当前复用 debug keystore，仅为个人自用方便安装）；
   R8 与 `shrinkResources` 已开启（APK 219.7 MB → 80.5 MB），如需恢复 x86_64 模拟器支持，删掉 `ndk { abiFilters }` 即可。
3. 若要对外分发 APK，注意 GPLv3 义务（`libbox.aar` 源自 sing-box）。
4. 若接入真实机场节点，用 `-NodeUri` 参数跑一遍验收脚本即可复用全部断言：

   ```powershell
   pwsh -File scripts\e2e_adb_verify.ps1 -NodeUri "vless://...@host:port?...#MyNode" `
     -RemoteDns "https://doh.pub/dns-query" -TestUrl "https://www.google.com/" -ExpectText "Google"
   ```
5. 支持节点 URI 的 `ech=` 参数（`tls.ech.config` 需要 base64 ECHConfig，从 URI 的 `ech=域名+DoH地址` 现取现算）；当前忽略，
   实测不影响连通性，但缺少 ECH 会降低抗封锁性。
6. 复核内置 `geosite-cn.srs` 是否应包含 `gstatic.com`：该域名命中大陆分流后会走 `dns-direct`（被污染 IP）+ `direct` 出站，
   Google 页面子资源因此绕开隧道（主域 `www.google.com` 不受影响）。

## 7. 环境与命令

- 本机：JDK 21 `D:\Apps\JDK\jdk-21`；Android SDK `D:\Apps\Android\Sdk`；Node `D:\Apps\nodejs\node.exe`；**无 `gh` CLI**；
  仓库 `https://github.com/yangche828/SingFlow`（私有，默认 `main`；`libbox.aar` 已入库，56 MB）。
- 推送必须带代理参数（Schannel 在该代理下握手失败）：`git -c http.sslBackend=openssl -c http.version=HTTP/1.1 -c http.proxy=http://127.0.0.1:10808 -c https.proxy=http://127.0.0.1:10808 push origin main`。
- 真机 adb 识别不到（`adb devices` 为空但设备管理器里有 `ADB Interface`）：Windows 把该接口的
  `DeviceInterfaceGUID` 绑成了厂商值，改回 adb 的 `{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}`（`HKLM\SYSTEM\CurrentControlSet\Enum\USB\VID_xxxx&PID_xxxx&MI_02\...\Device Parameters`）
  再 `pnputil /restart-device <InstanceId>` 即可；HONOR/HUAWEI 常见。
- 代理：`127.0.0.1:10808` 已写入 `C:\Users\yangche\.gradle\gradle.properties`，Gradle 下载依赖依赖它。
- Shell：**必须用 `pwsh`**，PowerShell 5.1 解析 E2E 脚本会报 `MissingEndCurlyBrace`。
- 模拟器启动：`-memory 4096 -no-window -no-audio -gpu swiftshader_indirect -no-snapshot-load -no-boot-anim`；AVD `SingFlow_Test_AVD`(API33) / `SingFlow_Test_AVD_36`(API36)。
- **本机直连被墙但本机代理可用时**（例如连真实公网节点）：给模拟器加 `-http-proxy http://127.0.0.1:10808`，
  guest 的 TCP 会经 CONNECT 走本机代理，**节点 URI 无需改写**（TLS/WS/VLESS 仍端到端）：
  ```powershell
  & "D:\Apps\Android\Sdk\emulator\emulator.exe" -avd SingFlow_Test_AVD_36 -no-window -no-audio -no-boot-anim `
    -no-snapshot-save -gpu swiftshader_indirect -http-proxy http://127.0.0.1:10808
  ```
  验证 guest 能到节点：`adb shell 'timeout 10 nc -z 203.0.113.10 8443; echo exit=$?'`。
- 诊断：`printf 'GET /logs?level=debug HTTP/1.0\r\n\r\n' | timeout 12 nc -w 10 127.0.0.1 9090`（guest 内）；Clash API `/connections`、`/proxies/proxy/delay?url=...`。
- 从 host 直验隧道（不依赖浏览器/UI）：`adb forward tcp:7890 tcp:7890` 后
  `curl -x http://127.0.0.1:7890 http://www.google.com/generate_204 -o NUL -w "%{http_code}"` → 期望 `204`；
  同法 `adb forward tcp:9090 tcp:9090` 查 `/connections`（`www.google.com` 应显示 `chains=proxy`）。
- 查内核 DNS 模块到底用了哪个上游：`curl "http://127.0.0.1:9090/dns/query?name=www.google.com&type=A"`（`:9090` 需先 `adb forward`）。
- 本地 E2E：节点 `127.0.0.1:8443` + `adb reverse tcp:8443 tcp:8443`（模拟器连 `10.0.2.2` 和宿主 LAN IP 均不通，ICMP 例外）。
- **一键验收（Windows / pwsh）**：
  ```powershell
  # 1) 起本地 VLESS 回环服务端（TCP + UDP/XUDP）
  Start-Process -FilePath "D:\Apps\nodejs\node.exe" -ArgumentList "scripts\dev\vless-loopback-server.js","--port","8443" `
    -WorkingDirectory "D:\Workspace\Github\SingFlow" -WindowStyle Hidden `
    -RedirectStandardOutput "D:\Workspace\Github\SingFlow\acceptance\vless-loopback.log"
  # 2) 跑验收（自动装包、导节点、连隧道、断真实转发）
  pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\e2e_adb_verify.ps1 `
    -RemoteDns "https://doh.pub/dns-query" -ProxyLog "D:\Workspace\Github\SingFlow\acceptance\vless-loopback.log"
  ```
- **一键验收（Linux / macOS / Git Bash）**：
  ```bash
  node scripts/dev/vless-loopback-server.js --port 8443 &
  PROXY_LOG=acceptance/vless-loopback.log REMOTE_DNS=https://doh.pub/dns-query ./scripts/e2e_adb_verify.sh
  ```
  脚本内部已处理 Git Bash 的 `MSYS_NO_PATHCONV` 与 host 路径转换（`cygpath -w`）；**运行期间不要编辑该 .sh**。
- UI 坐标（320x640）：连接按钮 `(160,318)`；底部 Tab 首页 `(37,616)`、节点 `(119,616)`、日志 `(201,616)`、设置 `(283,616)`。

## 8. 已知限制（不是 bug）

- 沙箱 host 无法稳定直连外网源站（代理侧常见 `upstream error connect ETIMEDOUT <公网IP>:443`），因此「浏览器渲染」断言偶发失败：隧道侧流量始终正常到达服务端，重跑即过（留档 `acceptance/e2e_api36_attempt1_egress_timeout.log`）。
- **默认远程 DoH `https://1.1.1.1/dns-query` 在用户真实节点上不可用**：经隧道访问 `https://1.1.1.1/` 被 TLS alert 拒绝（内核报 `remote error: tls: handshake failure`），
  而 `https://doh.pub/dns-query`、`https://dns.google/resolve` 正常返回真实 Google IP。真实节点验收统一显式传 `-RemoteDns https://doh.pub/dns-query`；**产品默认值未改**。
- **AliDNS（223.5.5.5）对 `www.google.com` 返回污染答案**（`157.240.7.20`，Meta IP），即使经隧道查询 DoH 版本也一样；因此不能把它当远程解析器，只能用于 `dns-direct`（大陆域名）。
- 内置 `geosite-cn` 含 `gstatic.com`：命中大陆分流 → `dns-direct` 拿到 CN 劫持 IP（`120.253.253.162`）→ `chains=direct`；主域 `www.google.com` 不在表内，走 `proxy`，页面可正常渲染。
- 节点 URI 的 `ech=` 参数未参与解析（见第 6 节建议 5）；实测不影响连通性（Cloudflare 对 `sni=node.example.com` 返回合法证书）。
- host 内存约 15.8 GB，同时只起一个 AVD（4 GB）；起模拟器前先 `./gradlew --stop`，否则 guest 易被 LMK 杀。低配 + 软件 GPU 下 SystemUI 偶发 ANR（脚本已自动点掉）。
- **带 `libbox.aar` 的 APK 分发受 GPLv3 约束**（自用不受限）。
- **16 KB 页大小只做了静态校验**：`zipalign -c -P 16` 通过、`.so` 以 `-Wl,-z,max-page-size=16384` 构建，
  但真机（HONOR BKQ-AN10）页大小是 4096，**尚无真 16 KB 页设备的运行时证据**。
- **release 包仅含 arm64**：x86_64 模拟器加载会 SIGILL（ARM 翻译层限制），模拟器验收必须用 `debug` 包。
- 代理服务端日志只记录解析后的 `IP:port`（细分域名需看客户端解析），因此回环验收的流量断言按「新增行数 + 协议类型（TCP / UDP-xudp）」判定；
  真实节点没有服务端日志，改用 Clash API `/connections` + 浏览器渲染作为转发证据。

## 9. 硬约束（违反即返工）

- 禁止 `TODO` / `FIXME` / `...省略` / 空方法体。
- 禁止在 `main` 源码里用 `android.util.Base64`（单测要在 JVM 跑，用 `java.util.Base64`）。
- 禁止在 `SingFlowVpnService` 之外调用 `establish()`。
- `libbox` 只能走 `LibboxBridge` 反射双模，不得作为编译期硬依赖。
- sing-box 语法红线见 `AGENTS.md` 第 3 节。

## 10. 接手后的第一句话建议

> 项目已交付：Phase 0~6 全绿，API 33 / API 36 双版本真实转发验收通过，真实公网节点（VLESS+TLS+WS）验证可访问谷歌，
> 且 release 精简包（80.5 MB / arm64）已在 Android 17 真机跑出 18/18。先读 `AGENTS.md`、`TASK_PROGRESS.md` 与
> 本文件第 4/5 节，再跑 `./gradlew :app:testDebugUnitTest`、`scripts/e2e_adb_verify.ps1` 复现现状；**先不要改代码**（尤其别删 `fallbackTunDnsServers()`，否则客户端 DNS 会再次绕过隧道）。
