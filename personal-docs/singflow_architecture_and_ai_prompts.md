# SingFlow — 全自动 AI Agent 端到端开发、编译、真机/虚拟机联调与自检执行蓝图

> **[🤖 致 AI 编程 Agent（Antigravity / OpenCode / Codex / Claude Code）的最高优先级执行指令]**
>
> **当你读取到本文档时，意味着人类开发者已将整个《SingFlow 全自动开发蓝图》交给你全权执行。请立即进入「全程无人值守全自动开发模式 (Autonomous End-to-End Execution Mode)」，并严格遵守以下铁律：**
>
> 1. **禁止等待人工分段粘贴提示词**：本文档本身就是你的完整执行剧本。你必须从 **【Phase 0】** 开始，自动、连续地推进到 **【Phase 6】**，直至在连接的 Android 13～17 真机或 Android 模拟器上成功安装运行 `SingFlow` 并通过所有自动化测试。
> 2. **自动写入全局规则与自我安装技能/依赖**：在 Phase 0，你必须自己主动在项目根目录创建 `AGENTS.md`（供 OpenCode / Codex 识别）、`CLAUDE.md`（供 Antigravity / Claude 识别）和 `.cursorrules`，并主动通过终端检查、安装所需的 SDK（Android SDK 33～37、NDK r28+、Go、gomobile、ADB 工具链）或 Agent Skills，**全程不要让用户手动配置环境或复制规则文件**。
> 3. **严酷的「自检门禁 (Self-Check Gate)」机制**：每一个 Phase 结束时都设有强制自检门禁。你必须在终端执行门禁中指定的编译、单测、Lint、Schema 校验或 `adb` 自动化验证命令。**只有当自检门禁 100% 通过（Exit Code = 0 且无异常日志）时，才允许进入下一阶段**；若自检失败，你必须自动分析报错日志、修改代码并重新执行自检，直到通过为止。
> 4. **精准的「用户提醒机制 (`[🔔 用户操作提醒]`)」**：人类开发者全程不介入代码编写。你只在每个 Phase 完成自检后输出规定格式的进度简报；仅在涉及**物理硬件连接**（如提示用户可插上 USB 手机，若未插则你自动拉起模拟器）或**真机首次系统级弹窗确认**时，才向用户发出清晰的 `[🔔 用户操作提醒]`。
> 5. **严禁任何占位符与 TODO**：所有生成的 Kotlin、XML、Gradle、Shell 脚本必须 100% 完整可编译，严禁出现 `// TODO`、`// ... 省略其余代码` 或未实现的方法体。

## 一、 全局技术底座与 Android 13 ~ Android 17 + sing-box 1.14+ 铁律规范

在编写任何代码之前，AI Agent 必须将以下 **系统兼容性要求** 与 **内核配置红线** 刻入全局上下文：

### 1. Android 13 (API 33) 至 Android 17 (API 37) 跨代际兼容规范

本项目明确要求完整支持并适配 **Android 13 (API 33, Tiramisu)** 直至最新的 **Android 17 (API 37, Cinnamon Bun)**（同时向下兼容 Android 14 API 34、Android 15 API 35、Android 16 API 36）：

| 适配维度 | Android 13 (API 33) 要求 | Android 14 ~ 16 (API 34~36) 要求 | Android 17 (API 37) 最新强制要求 | Agent 代码实现强制方案 |
| :--- | :--- | :--- | :--- | :--- |
| **SDK 版本配置** | `minSdk = 33`（直接以 Android 13 为基线，抛弃历史包袱，充分利用现代 API） | `compileSdk` / `targetSdk` 动态检测最高可用版本（36/37） | [Android 17 (API 37)](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Fabout%2Fversions%2F17%2Fsetup-sdk) 移除旧版兼容性豁免开关 | 在 `app/build.gradle.kts` 中设置 `minSdk = 33`，并通过脚本自动通过 `sdkmanager` 安装 `platforms;android-33` 与最新 `platforms;android-37`（若远程仓库为预览版 `android-Baklava`/`36` 则自动回退 `36` 保证 100% 编译通过）。 |
| **16 KB 内存页对齐 (16 KB Page Size)** | 传统 4 KB 页大小 | [Android 15+ 强制支持 16 KB 页大小](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Fguide%2Fpractices%2Fpage-sizes)，未对齐的 `.so` 在 16KB 设备/模拟器上直接安装失败 (`INSTALL_FAILED_INVALID_APK`) 或崩溃 | Android 17 全面默认启用 16 KB 页面检查 | 1. `app/build.gradle.kts` 必须配置 `packaging { jniLibs { useLegacyPackaging = false } }`；<br>2. 使用 AGP `8.7.2+` 自动执行 `zipalign -P 16`；<br>3. 编译 `libbox.aar` 时使用 **NDK r28+**（或传递 `-Wl,-z,max-page-size=16384`），并用 `llvm-objdump -p` 验证 `LOAD` 段对齐为 `2**14`。 |
| **前台服务类型 (FGS Type)** | 仅需声明 `FOREGROUND_SERVICE` 权限 | [Android 14+ 强制声明 FGS 类型](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Fabout%2Fversions%2F14%2Fchanges%2Ffgs-types-required)，否则调用 `startForeground` 立即抛出 `MissingForegroundServiceTypeException` | Android 17 严格审计后台服务生命周期与超时 | 在 `AndroidManifest.xml` 同时声明 `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` 与 `FOREGROUND_SERVICE_SPECIAL_USE` 权限，为 `SingFlowVpnService` 设置 `android:foregroundServiceType="systemExempted|specialUse"`，并在 `ServiceCompat.startForeground()` 中显式传入 `ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED`。 |
| **大屏自适应与 Edge-to-Edge** | 可选沉浸式状态栏 | Android 15/16 默认强制 `enableEdgeToEdge()` | [Android 17 彻底移除大屏方向锁定与缩放豁免](https://www.google.com/search?q=https%3A%2F%2Fdeveloper.android.com%2Fabout%2Fversions%2F17%2Fbehavior-changes-17) | 1. **严禁**在 `AndroidManifest.xml` 中写死 `android:screenOrientation="portrait"` 或 `resizableActivity="false"`；<br>2. `MainActivity` 必须调用 `enableEdgeToEdge()`，所有 Compose 页面统一使用 `Scaffold` 的 `innerPadding` 与 `WindowInsets.safeDrawing`。 |
| **通知与广播安全** | 必须动态申请 `POST_NOTIFICATIONS` 权限才能显示 VPN 状态通知 | `Context.registerReceiver()` 必须显式指定 `RECEIVER_NOT_EXPORTED` 或 `RECEIVER_EXPORTED` | 后台启动 Activity 与隐式 Intent 拦截更严格 | 启动 VPN 前检查 `POST_NOTIFICATIONS`（未授权也不阻塞 VPN 核心启动），测试阶段通过 `adb shell pm grant` 自动授权；所有动态广播一律使用 `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`。 |

### 2. 最新版 sing-box (v1.14.3+ / v1.15+) 六大配置语法红线

由于大模型预训练语料中包含大量 `sing-box 1.8 ~ 1.10` 的过时教程，而根据 [sing-box 官方迁移规范 (Migration Guide)](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fmigration%2F) 与 [废弃特性清单 (Deprecated Feature List)](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fdeprecated%2F)，旧写法在 **1.14.x / 1.15.x** 中已被彻底移除，一旦生成会导致内核启动直接报错退出。Agent 必须严格遵守以下映射表：

| 序号 | 严禁出现的旧版废弃写法（会导致 1.14+ 启动直接崩溃） | 必须生成的 sing-box 1.14+ / 1.15+ 标准写法 | 官方规范链接 |
| :--- | :--- | :--- | :--- |
| **红线 1** | `dns.servers` 中写 `{"address": "https://1.1.1.1/dns-query"}` 或 `{"address": "local"}` | 必须使用强类型 DNS Server：`{"type": "https", "tag": "dns-remote", "server": "1.1.1.1", "server_port": 443, "path": "/dns-query", "detour": "proxy"}`、`{"type": "udp", "tag": "dns-direct", "server": "223.5.5.5", "server_port": 53}`、`{"type": "local", "tag": "dns-local"}` | [DNS Server 规范](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fconfiguration%2Fdns%2F) |
| **红线 2** | `dns.rules` 中写 `{"outbound": "any", "server": "dns-direct"}` 或在节点写 `"domain_strategy"` | **严禁使用 `"outbound": "any"`**！必须在 `route` 顶层配置 `"default_domain_resolver": {"server": "dns-direct", "strategy": "prefer_ipv4"}`，并在代理节点 outbound 中配置 `"domain_resolver": {"server": "dns-direct", "strategy": "prefer_ipv4"}` | [1.12+ 域名解析器迁移](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fmigration%2F) |
| **红线 3** | `outbounds` 中定义 `{"type": "block", "tag": "block"}` 或 `{"type": "dns", "tag": "dns"}` | **严禁在 `outbounds` 中定义 `block` 和 `dns`**！必须在 `route.rules` 中使用动作：`{"protocol": "dns", "action": "hijack-dns"}` 与 `{"action": "reject"}` | [Rule Action 规范](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fconfiguration%2Froute%2Frule_action%2F) |
| **红线 4** | `inbounds` (tun/mixed) 中写 `"sniff": true, "sniff_override_destination": true` | **严禁在 `inbounds` 中写 `sniff`**！必须在 `route.rules` 首条写入 `{"action": "sniff", "timeout": "300ms"}` | [Route Rule 规范](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fconfiguration%2Froute%2Frule%2F) |
| **红线 5** | `tun` 入站中写 `"inet4_address": "172.19.0.1/30"` | 必须使用合并数组：`"address": ["172.19.0.1/30", "fdfe:dcba:9876::1/126"]`，且不硬编码旧版 `gvisor` stack | [TUN 入站规范](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fmigration%2F) |
| **红线 6** | `experimental.cache_file` 中写 `"store_rdrc": true` 或路由中写 `"geoip": "cn"` | 必须使用 `"store_dns": true`；分流规则统一使用 `"ip_is_private": true` 与远程二进制规则集 `.srs` (`rule_set`) | [1.14 Cache 迁移规范](https://www.google.com/search?q=https%3A%2F%2Fsing-box.sagernet.org%2Fmigration%2F) |

## 二、 SingFlow 面向未来的插件化可扩展架构蓝图

为了确保第一版直接跑通，且未来扩展几十项新功能时**零重构**，Agent 必须按照以下目录与分层创建项目（包名统一为 `com.singflow.android`）：

```text
SingFlow/
├── AGENTS.md                                # [Phase 0 自动生成] OpenCode / Codex 全局规则与架构契约
├── CLAUDE.md                                # [Phase 0 自动生成] Antigravity / Claude Code 全局规则
├── .cursorrules                             # [Phase 0 自动生成] Cursor / Windsurf 全局规则
├── TASK_PROGRESS.md                         # [Phase 0 自动生成并实时更新] 全自动开发与自检状态追踪表
├── scripts/
│   ├── setup_env_and_sdk.sh                 # [Phase 0 自动生成并执行] 自动检查/安装 Android SDK 33~37、NDK、Go、ADB
│   ├── fetch_or_build_libbox.sh             # [Phase 4 自动生成并执行] 自动拉取或使用 gomobile 编译 16KB 对齐的 libbox.aar
│   └── e2e_adb_verify.sh                    # [Phase 6 自动生成并执行] 真机/模拟器全自动安装、授权、注入节点与连接自检脚本
├── gradle/libs.versions.toml                # Version Catalog 依赖版本锁定
├── build.gradle.kts
├── settings.gradle.kts
└── app/
    ├── libs/                                # 存放 libbox.aar（即使暂未放入，通过 LibboxBridge 双模引擎也能 100% 编译运行）
    ├── build.gradle.kts                     # 含 Android 13~17 适配、16KB Page Size 对齐、KSP、Hilt、Compose、Serialization
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml          # 声明 VPN、FGS systemExempted|specialUse、POST_NOTIFICATIONS、TileService
        │   └── java/com/singflow/android/
        │       ├── SingFlowApp.kt           # @HiltAndroidApp，初始化工作目录与全局未捕获异常日志记录
        │       ├── MainActivity.kt          # @AndroidEntryPoint，开启 enableEdgeToEdge()，承载 SingFlowNavHost
        │       ├── core/
        │       │   ├── model/               # 领域模型层：ProtocolType, ProxyNode, TransportConfig, TlsConfig, AppSettings, VpnState
        │       │   ├── database/            # 持久化层：SingFlowDatabase, NodeEntity, NodeDao, SubscriptionEntity(预留), PreferencesManager
        │       │   ├── parser/              # 【扩展点 1：职责链解析引擎】
        │       │   │   ├── NodeLinkParser.kt
        │       │   │   ├── Base64Utils.kt
        │       │   │   ├── UniversalImportManager.kt
        │       │   │   └── impl/            # VmessParser, VlessParser, TrojanParser, ShadowsocksParser, Hysteria2Parser, TuicParser, WireGuardParser
        │       │   ├── config/              # 【扩展点 2：拦截器配置编译流水线】
        │       │   │   ├── ConfigInterceptor.kt
        │       │   │   ├── SingBoxConfigPipeline.kt
        │       │   │   └── interceptors/    # DnsConfigInterceptor, InboundConfigInterceptor, OutboundNodeInterceptor, RoutingRuleInterceptor, ExperimentalInterceptor
        │       │   └── engine/              # 【核心引擎层：libbox + Android VpnService + Socket/TUN 双模桥接】
        │       │       ├── LibboxBridge.kt          # 自动检测 io.nekohasekai.libbox.Libbox，支持原生 JNI 与内置本地回退引擎无缝切换
        │       │       ├── SingFlowVpnService.kt    # VpnService 实现：openTun、protect(fd) 防回环、Android 10~17 UID 归属查询、热重载
        │       │       ├── TrafficAndLogMonitor.kt  # Clash API WebSocket (127.0.0.1:9090) + TrafficStats 实时速率与内核日志流
        │       │       ├── NodeLatencyTester.kt     # 协程 Semaphore 并发 TCP 握手测速 + 代理后 HTTP 204 真连接测速
        │       │       └── QuickTileService.kt      # 下拉通知栏磁贴快捷开关
        │       └── ui/                      # Jetpack Compose Material 3 现代流体界面
        │           ├── theme/               # 动态取色 + 极光青蓝深浅色主题 + 协议专属色彩系统
        │           ├── components/          # HeroConnectButton, TrafficWaveCard, NodeItemCard, ImportBottomSheet
        │           └── screens/             # DashboardScreen, NodesScreen, LogsScreen (含 JSON 实时预览复制), SettingsScreen (含分应用代理)
        └── test/java/com/singflow/android/  # 【强制自检单测层】
            ├── core/parser/UniversalParserTest.kt          # 验证 V2Ray 全协议奇葩格式 100% 解析成功
            └── core/config/SingBoxConfigPipelineTest.kt    # 验证生成的 JSON 100% 符合 sing-box 1.14+ 规范且无任何废弃字段
```

## 三、 全自动分步执行流水线（Agent 必须从 Phase 0 连续执行至 Phase 6）

> **⚠️ Agent 执行协议**：请按顺序逐个执行以下 Phase。每进入一个 Phase，先完成所有文件创建与脚本执行，然后运行该 Phase 的 **`[✅ 模型自检门禁]`**。自检通过后，更新 `TASK_PROGRESS.md`，并在终端打印该 Phase 的 **`[🔔 用户操作提醒]`**，紧接着**自动继续下一 Phase**（除非遇到无法自动绕过的硬件阻塞）。

### Phase 0：环境自检、全局规则自写入与调试设备（真机/模拟器）探测

#### 1. Agent 自动执行动作

1. **自动创建规则与进度文件**：
   - 将本文档「第一章」中的 **Android 13~17 适配规范**、**sing-box 1.14+ 六大语法红线** 以及 **禁止 TODO/占位符原则**，自动提炼写入项目根目录的 `AGENTS.md`、`CLAUDE.md` 和 `.cursorrules` 三个文件中，确保你自己在后续所有步骤中自动加载这些规则。
   - 创建 `TASK_PROGRESS.md`，记录 Phase 0 ～ Phase 6 的勾选状态。
2. **自动检查并安装所需 Skills / MCP / 工具链**：
   - 如果你运行在支持 Skills 或插件的 Agent 环境（如 Antigravity / OpenCode / Codex），自动检查当前可用的 bash / android / adb 能力。
   - 编写并执行 `scripts/setup_env_and_sdk.sh`：
     - 检查 `java -version`（需 JDK 17 或 21，若未配置 `JAVA_HOME`，自动搜索系统中的 Android Studio Embedded JDK `/Applications/Android Studio.app/Contents/jbr` 或 `/usr/lib/jvm` 并导出）。
     - 检查 `ANDROID_HOME` / `ANDROID_SDK_ROOT`（自动探测 `~/Library/Android/sdk`、`~/Android/Sdk` 或 `/opt/android-sdk`）。
     - 若存在 `sdkmanager`，自动静默接受许可（`yes | sdkmanager --licenses`）并确保安装 `platform-tools`、`build-tools;35.0.0`（或最新 36/37）、`platforms;android-33`（Android 13）以及最高可用的 `platforms;android-37` / `platforms;android-36` / `platforms;android-35`。
     - 检查 `adb devices`：判断当前是否有状态为 `device` 的 Android 13 (API 33) ～ Android 17 (API 37) 真机或已启动的模拟器。

#### 2. `[✅ Phase 0 模型自检门禁 (Self-Check Gate)]`

Agent 必须在终端执行以下检查并确认通过：
```bash
test -f AGENTS.md && test -f CLAUDE.md && test -f .cursorrules && test -f TASK_PROGRESS.md
java -version
adb version
```
- **通过标准**：4 个规则与追踪文件均已生成且非空；`java` 与 `adb` 命令均可正常输出版本号。

#### 3. `[🔔 Phase 0 到达时向用户输出的提醒]`

自检通过后，向用户打印以下提示，然后**不要停顿，立即开始执行 Phase 1**：
> **[🔔 SingFlow 阶段 0 完成 — 环境与全局规则已自举]**
> - 我已自动在项目根目录写入 `AGENTS.md`、`CLAUDE.md` 与 `.cursorrules`，并完成了 JDK 与 Android SDK/ADB 工具链自检。
> - **【给您的提醒】**：如果您希望一会儿直接在您的 **Android 13 ～ Android 17 手机**上真机运行测试，可以现在用数据线将手机连上电脑并打开「开发者选项 -> USB 调试」（手机弹窗点「允许调试」）；如果您不想连手机也没关系，我在代码编写完成后会**自动检测并拉起 Android 虚拟机**完成全部安装与测试，您无需任何操作。我现在立即开始 **Phase 1：构建工程脚手架与数据层**。

### Phase 1：工程脚手架、Android 13~17 构建配置与核心数据模型层

#### 1. Agent 自动执行动作

请完整创建以下所有文件（严禁省略任何一行 import 或属性）：

1. **Gradle Wrapper 与全局配置**：
   - 生成标准 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.properties`（使用 `gradle-8.10.2-bin.zip`）与 `gradle.properties`（开启 `org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`）。
   - 生成 `settings.gradle.kts`：配置 `pluginManagement` 与 `dependencyResolutionManagement`，包含 `google()`, `mavenCentral()`, 以及 `flatDir { dirs("app/libs") }`。
   - 生成 `gradle/libs.versions.toml`：
     - `agp = "8.7.2"`（原生支持 Android 15~17 的 16KB Page Size `zipalign`）
     - `kotlin = "2.0.21"`, `ksp = "2.0.21-1.0.27"`, `hilt = "2.52"`
     - `composeBom = "2024.10.01"`, `navigationCompose = "2.8.3"`, `lifecycle = "2.8.7"`, `activityCompose = "1.9.3"`
     - `room = "2.6.1"`, `datastore = "1.1.1"`, `serializationJson = "1.7.3"`, `coroutines = "1.9.0"`, `okhttp = "4.12.0"`
     - 测试依赖：`junit = "4.13.2"`, `coroutinesTest = "1.9.0"`, `json = "20240303"`（供 JVM 单元测试使用的纯 Java JSON 或直接使用 `kotlinx-serialization-json`）。
2. **`app/build.gradle.kts`（核心跨版本与 16KB 对齐配置）**：
   - 动态探测本地 SDK 目录：若本地已安装 `android-37`（Android 17）则 `compileSdk = 37`，否则自动使用已安装的最高版本（`36` 或 `35`），`minSdk = 33`（Android 13），`targetSdk = 37`（或与 `compileSdk` 一致）。
   - 必须包含 16 KB Page Size 打包配置：
     ```kotlin
     packaging {
         jniLibs {
             useLegacyPackaging = false
         }
         resources {
             excludes += "/META-INF/{AL2.0,LGPL2.1}"
         }
     }
     ```
   - 依赖中添加 `implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))`，确保当 `app/libs/libbox.aar` 存在时自动打包进 APK，不存在时也不报错。
3. **`app/src/main/AndroidManifest.xml`（Android 13 ~ 17 严格合规）**：
   - 声明权限：
     ```xml
     <uses-permission android:name="android.permission.INTERNET" />
     <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED" />
     <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
     <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
     <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
     <uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"
         tools:ignore="QueryAllPackagesPermission" />
     ```
   - 注册 `SingFlowVpnService`：
     ```xml
     <service
         android:name=".core.engine.SingFlowVpnService"
         android:enabled="true"
         android:exported="false"
         android:permission="android.permission.BIND_VPN_SERVICE"
         android:foregroundServiceType="systemExempted|specialUse">
         <intent-filter>
             <action android:name="android.net.VpnService" />
         </intent-filter>
         <property
             android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
             android:value="vpn_proxy_tunnel" />
     </service>
     ```
   - 注册 `MainActivity`：**不要锁死 `screenOrientation`**，以完全符合 Android 17 (API 37) 大屏自适应规范。
4. **领域模型层 (`com.singflow.android.core.model`)**：
   - `ProtocolType.kt`：包含 `VMESS`, `VLESS`, `TROJAN`, `SHADOWSOCKS`, `HYSTERIA2`, `TUIC`, `WIREGUARD`, `SOCKS`, `HTTP`, `CUSTOM_JSON`，带 `displayName` 与 `badgeColorHex`。
   - `TransportConfig.kt` (`@Serializable`)：`type: String = "tcp"` (`tcp`, `ws`, `grpc`, `http`, `httpupgrade`, `quic`, `xhttp`), `host: String = ""`, `path: String = ""`, `headers: Map<String, String> = emptyMap()`, `serviceName: String = ""`, `maxEarlyData: Int = 0`, `earlyDataHeaderName: String = ""`。
   - `TlsConfig.kt` (`@Serializable`)：`enabled: Boolean = false`, `serverName: String = ""`, `insecure: Boolean = false`, `alpn: List<String> = emptyList()`, `utlsFingerprint: String = "chrome"`, `realityEnabled: Boolean = false`, `realityPublicKey: String = ""`, `realityShortId: String = ""`, `realitySpiderX: String = ""`。
   - `ProxyNode.kt` (`@Serializable`)：包含 `id`, `groupId`, `name`, `protocol`, `server`, `port`, `uuid`, `alterId`, `securityCipher`, `flow`, `password`, `method`, `plugin`, `pluginOpts`, `upMbps`, `downMbps`, `obfsType`, `obfsPassword`, `congestionControl`, `udpRelayMode`, `wgPrivateKey`, `wgPeerPublicKey`, `wgPreSharedKey`, `wgLocalAddress`, `wgReserved`, `wgMtu`, `rawOutboundJson`, `transport`, `tls`, `latencyMs`, `createdAt`。
   - `AppSettings.kt`、`RoutingMode.kt` (`GLOBAL`, `BYPASS_LAN`, `BYPASS_LAN_CN`) 与 `VpnState.kt` (`IDLE`, `CONNECTING`, `CONNECTED`, `STOPPING`, `ERROR`，含实时上下行速率、累计流量、日志列表)。
5. **持久化与 DI 层 (`com.singflow.android.core.database`)**：
   - `NodeEntity.kt`、`NodeConverters.kt`、`NodeDao.kt`、`SingFlowDatabase.kt`、`PreferencesManager.kt`（基于 `DataStore<Preferences>`）及 Hilt `DatabaseModule.kt`。

#### 2. `[✅ Phase 1 模型自检门禁 (Self-Check Gate)]`

Agent 必须在终端执行：
```bash
./gradlew :app:kspDebugKotlin
```
- **通过标准**：Room 与 Hilt 的 KSP 注解处理器成功生成代码，无任何语法或依赖解析错误。若报错，立即定位并修复后再继续。

#### 3. `[🔔 Phase 1 到达时向用户输出的提醒]`

> **[🔔 SingFlow 阶段 1 完成 — Android 13~17 工程底座与数据库层已通过编译自检]**
> - 已完成 Gradle 8.10 + AGP 8.7（启用 16KB 内存页对齐）、Android 13~17 权限声明以及 Room 数据库与 DataStore 构建，并通过了 `:app:kspDebugKotlin` 编译自检。
> - **【给您的提醒】**：您无需任何操作，我现在自动进入 **Phase 2：开发 V2Ray 全协议导入引擎并执行 JUnit 自动化测试**。

### Phase 2：V2Ray / 全协议节点解析引擎 (`core/parser`) 与单元测试门禁

#### 1. Agent 自动执行动作

在 `com.singflow.android.core.parser` 下编写完整、零妥协的全协议解析器（支持直接导入 v2rayNG / v2rayN / NekoBox 导出的所有节点与订阅原文）：

1. **`Base64Utils.kt`**：
   - 使用纯 Kotlin / `java.util.Base64` 实现（不要耦合 `android.util.Base64`，以便在 JVM 单元测试中直接运行！）。
   - `safeBase64Decode(input: String): String`：自动清理空白字符、`-\r\n`，将 URL-Safe 的 `-` 转为 `+`、`_` 转为 `/`，自动按 `length % 4` 补齐 `=`，解码为 UTF-8 字符串。
2. **`NodeLinkParser.kt` 及 7 大协议解析器实现 (`impl/`)**：
   - `VmessParser.kt`：
     - 支持 `vmess://` + Base64 JSON（v2rayN 格式）：必须使用自定义辅助函数 `JsonObject.stringOrEmpty(key)` 和 `JsonObject.intOrDefault(key, default)`（通过 `jsonElement.jsonPrimitive.content.toIntOrNull()` 读取），**完美兼容 `port`、`aid` 是数字 `"port": 443` 或字符串 `"port": "443"` 的情况**！
     - 完整映射 `ps` (备注)、`add` (地址)、`port`、`id` (UUID)、`aid`、`scy` (默认 `auto`)、`net` (`tcp`/`ws`/`grpc`/`httpupgrade`/`quic`/`h2`)、`host`、`path`、`tls` (`"tls"` 则 `tls.enabled = true`)、`sni`、`alpn`、`fp`。
     - 同时兼容 `vmess://uuid@host:port?...#remark` 标准 URI 格式。
   - `VlessParser.kt`：
     - 解析 `vless://uuid@host:port?query#remark`。
     - **防崩解析技巧**：先取 `#` 之后的部分做 `URLDecoder.decode(remark, "UTF-8")`（防止备注中含有空格、中文或 Emoji 导致 `java.net.URI` 抛 `URISyntaxException`），再解析 `uuid@host:port?query`（正确处理 IPv6 地址如 `[2606:4700::1]:443`）。
     - 完整提取：`security` (`none`/`tls`/`reality`)、`flow` (`xtls-rprx-vision`)、`type` (`tcp`/`ws`/`grpc`/`httpupgrade`/`xhttp`)、`sni`、`fp`、`alpn`、`allowInsecure`/`insecure` (`1` 或 `true`)、`pbk` (REALITY Public Key)、`sid` (REALITY ShortId)、`spx` (SpiderX)、`serviceName` (gRPC)、`path`、`host`。
   - `TrojanParser.kt`：
     - 解析 `trojan://password@host:port?query#remark`，默认开启 `tls.enabled = true`，提取 `sni`/`peer`、`allowInsecure`、`fp`、`alpn`、`type` (`tcp`/`ws`/`grpc`)、`host`、`path`、`serviceName`。
   - `ShadowsocksParser.kt`：
     - 同时兼容 3 种格式：
       1. SIP002：`ss://BASE64(method:password)@host:port?plugin=...#remark`
       2. 明文 URI：`ss://method:password@host:port#remark`
       3. Legacy 全 Base64：`ss://BASE64(method:password@host:port)#remark`
     - **SS-2022 关键细节**：使用 `decodedUserInfo.substringBefore(':')` 获取 `method`，使用 `decodedUserInfo.substringAfter(':')` 获取 `password`（绝不能用 `split(":")` 取第二个元素，因为 `2022-blake3-aes-256-gcm` 的多段密钥中含有 `:`！）。
   - `Hysteria2Parser.kt`：支持 `hysteria2://` 与 `hy2://`，提取 `auth` (password)、`sni`、`insecure`、`obfs` (`salamander`)、`obfs-password`、`upmbps`、`downmbps`。
   - `TuicParser.kt`：支持 `tuic://uuid:password@host:port?query#remark`，提取 `uuid`、`password`、`congestion_control`、`udp_relay_mode`、`alpn`、`sni`、`allow_insecure`。
   - `WireGuardParser.kt`：支持 `wireguard://privateKey@host:port?publicKey=...&address=...&mtu=...#remark`。
3. **`UniversalImportManager.kt`**：
   - 支持单条链接、多行混合链接、整段 Base64 编码的机场订阅响应内容、以及原生 `sing-box` JSON 配置中的 `outbounds` 节点提取。
4. **编写单元测试 `app/src/test/java/com/singflow/android/core/parser/UniversalParserTest.kt`**：
   - 编写至少 8 个测试方法，覆盖：
     - v2rayN 导出的 `vmess://` Base64 JSON（分别测试 `"port": "443"` 字符串与 `"port": 443` 整数、带 `ws` + `tls`）；
     - v2rayNG 导出的 `vless://` + `xtls-rprx-vision` + `security=reality` (`pbk`, `sid`, `fp=chrome`) + 中文及 Emoji 备注；
     - `trojan://` + gRPC 传输层；
     - `ss://` SIP002 格式、Legacy Base64 格式以及 `2022-blake3-aes-256-gcm` 带冒号密钥；
     - `hy2://` 与 `tuic://` 链接；
     - 包含 5 个不同协议节点的多行文本整体 Base64 编码后的订阅内容一键导入。

#### 2. `[✅ Phase 2 模型自检门禁 (Self-Check Gate)]`

Agent 必须在终端运行单元测试：
```bash
./gradlew :app:testDebugUnitTest --tests "com.singflow.android.core.parser.UniversalParserTest"
```
- **通过标准**：所有测试用例 `PASSED`（0 failures, 0 errors）。如果有任何一个协议解析失败，立即修复对应 Parser 并重跑单测直至全绿。

#### 3. `[🔔 Phase 2 到达时向用户输出的提醒]`

> **[🔔 SingFlow 阶段 2 完成 — V2Ray 全协议导入引擎已通过 JUnit 自动化测试]**
> - 已完成 VMess、VLESS (Vision/REALITY)、Trojan、Shadowsocks (SIP002/Legacy/SS-2022)、Hysteria2、TUIC、WireGuard 及 Base64 订阅解析器，并通过了全部单元测试校验。
> - **【给您的提醒】**：无需任何操作，我现在自动进入 **Phase 3：构建 sing-box 1.14+ 拦截器配置编译器并执行 Schema 合规自检**。

### Phase 3：sing-box 1.14+ 配置编译流水线 (`core/config`) 与 Schema 自检门禁

#### 1. Agent 自动执行动作

在 `com.singflow.android.core.config` 下采用 **Interceptor Pipeline（拦截器链）** 模式实现配置编译器：

1. **`ConfigInterceptor.kt` 与 `SingBoxConfigContext.kt`**：
   - 定义 `interface ConfigInterceptor { fun intercept(context: SingBoxConfigContext) }`，使得未来新增任何路由、DNS 或实验特性只需注册新的 `ConfigInterceptor`。
2. **实现 5 个核心标准拦截器 (`interceptors/`)**：
   - `DnsConfigInterceptor.kt`：
     - 严格按照 **红线 1** 生成强类型 `dns.servers`：
       - `"dns-remote"`：自动解析 `settings.remoteDns`（默认 `https://1.1.1.1/dns-query` $\rightarrow$ `{"type": "https", "tag": "dns-remote", "server": "1.1.1.1", "server_port": 443, "path": "/dns-query", "detour": "proxy"}`）。
       - `"dns-direct"`：`{"type": "udp", "tag": "dns-direct", "server": "223.5.5.5", "server_port": 53}`。
       - `"dns-local"`：`{"type": "local", "tag": "dns-local"}`。
     - 当 `routingMode == BYPASS_LAN_CN` 时，在 `dns.rules` 中添加 `{"rule_set": "geosite-cn", "action": "route", "server": "dns-direct"}`。
     - 设置 `"final": "dns-remote"`, `"strategy": if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only"`。
   - `InboundConfigInterceptor.kt`：
     - 严格按照 **红线 4 & 红线 5** 生成：
       - `tun` 入站：`{"type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"]`（开启 IPv6 时追加 `"fdfe:dcba:9876::1/126"`），`"mtu": settings.tunMtu, "auto_route": true, "strict_route": true}`（**绝不包含 `sniff` 或 `inet4_address`**）。
       - `mixed` 入站：`{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": settings.mixedPort}`。
   - `OutboundNodeInterceptor.kt`：
     - 将传入的 `ProxyNode` 精准编译为对应的 `sing-box 1.14+` outbound 对象（`"tag": "proxy"`），并严格按照 **红线 2** 注入：
       `"domain_resolver": {"server": "dns-direct", "strategy": if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only"}`。
     - 完整生成 `tls` 对象（`enabled`, `server_name`, `insecure`, `alpn`, `utls: {"enabled": true, "fingerprint": node.tls.utlsFingerprint}`, 以及当 `realityEnabled` 为真时的 `reality: {"enabled": true, "public_key": node.tls.realityPublicKey, "short_id": node.tls.realityShortId}`）。
     - 完整生成 `transport` 对象（当 `transport.type != "tcp"` 时，生成 `ws`, `grpc`, `http`, `httpupgrade`, `quic` 配置；注意 VMess 的 `alter_id` 与 `security`）。
     - 若协议为 `WIREGUARD`，按照 `sing-box 1.11+` 规范写入顶层 `"endpoints"` 数组；其余写入 `"outbounds"` 数组。
     - 在 `"outbounds"` 末尾追加 `{"type": "direct", "tag": "direct"}`（**严格遵守红线 3，绝不生成 `block` 和 `dns` outbound**）。
   - `RoutingRuleInterceptor.kt`：
     - 设置 `"auto_detect_interface": true`。
     - 设置 `"default_domain_resolver": {"server": "dns-direct", "strategy": if (settings.enableIpv6) "prefer_ipv4" else "ipv4_only"}`。
     - 在 `route.rules` 中按顺序注入：
       1. `{"action": "sniff", "timeout": "300ms"}`
       2. `{"protocol": "dns", "action": "hijack-dns"}`
       3. `{"ip_is_private": true, "action": "route", "outbound": "direct"}`
       4. 若为 `BYPASS_LAN_CN` 模式，注入 `{"rule_set": ["geosite-cn", "geoip-cn"], "action": "route", "outbound": "direct"}` 并在 `route.rule_set` 注入对应的远程 `.srs` 二进制规则集定义（`download_detour: "proxy"`）。
     - 设置 `"final": "proxy"`。
   - `ExperimentalInterceptor.kt`：
     - 严格按照 **红线 6** 注入 `cache_file: {"enabled": true, "path": "cache.db", "store_dns": true}` 与 `clash_api: {"external_controller": "127.0.0.1:9090"}`。
3. **编写单元测试 `app/src/test/java/com/singflow/android/core/config/SingBoxConfigPipelineTest.kt`**：
   - 将 Phase 2 解析出的 VMess、VLESS-REALITY、Trojan、SS、Hysteria2 节点分别编译为 JSON，并执行**反废弃字段红线断言**：
     - 断言 JSON 字符串中 **不包含** `"inet4_address"`、**不包含** `"store_rdrc"`、**不包含** `"domain_strategy"`、**不包含** `"type":"block"`、**不包含** `"type":"dns"`、**不包含** `"outbound":"any"`。
     - 断言 JSON 解析后的 `route.rules[0].action == "sniff"`、`route.rules[1].action == "hijack-dns"`、`route.default_domain_resolver` 存在、`dns.servers[0].type == "https"`。

#### 2. `[✅ Phase 3 模型自检门禁 (Self-Check Gate)]`

Agent 必须在终端执行：
```bash
./gradlew :app:testDebugUnitTest
```
- **通过标准**：`UniversalParserTest` 与 `SingBoxConfigPipelineTest` 全部 100% 通过！如果宿主环境安装了 `sing-box` 命令行工具，额外把生成的 JSON 写入临时文件执行 `sing-box check -c /tmp/test_config.json` 验证 0 警告 0 错误。

#### 3. `[🔔 Phase 3 到达时向用户输出的提醒]`

> **[🔔 SingFlow 阶段 3 完成 — sing-box 1.14+ 配置编译流水线已通过红线合规自检]**
> - 已完成基于拦截器链的 `SingBoxConfigPipeline`，生成的 JSON 配置 100% 符合 `sing-box 1.14+ / 1.15+` 最新规范，并通过了自动化反废弃字段断言测试。
> - **【给您的提醒】**：无需任何操作，我现在自动进入 **Phase 4：构建 `libbox` 双模引擎桥接、`SingFlowVpnService` 与 16KB 对齐支持**。

### Phase 4：`libbox.aar` 自动获取/编译与 `SingFlowVpnService` 核心引擎实现 (`core/engine`)

#### 1. Agent 自动执行动作

1. **编写并执行 `scripts/fetch_or_build_libbox.sh`**：
   - 自动创建 `app/libs/` 目录。
   - 脚本逻辑：
     1. 检查 `app/libs/libbox.aar` 是否已存在；
     2. 若不存在，尝试通过 `curl -L` 从 GitHub 上拉取预编译的 `libbox.aar`（或检查本地是否有 Go + `gomobile` + Android NDK 环境，若有则自动克隆 `https://github.com/SagerNet/sing-box` 最新 release tag 并使用 `-ldflags="-extldflags=-Wl,-z,max-page-size=16384"` 编译 16KB 对齐的 `libbox.aar`）；
     3. 同时生成 `.github/workflows/build-libbox.yml`，支持云端一键构建 16KB Page Size 对齐的 `libbox.aar`。
2. **实现双模安全引擎桥接 `LibboxBridge.kt`**：
   - **设计核心**：使用编译期安全的反射/动态代理（`java.lang.reflect.Proxy`）对接 `io.nekohasekai.libbox.Libbox` 与 `io.nekohasekai.libbox.PlatformInterface`，同时内置 **Direct TUN / Local Engine 回退与诊断模式**！
   - 当 `app/libs/libbox.aar` 存在时：
     - 自动调用 `Libbox.setup(baseDir, workingDir, tempDir, false)`；
     - 自动创建 `PlatformInterface` 动态代理实例，将 `openTun`、`autoDetectInterfaceControl`、`usePlatformAutoDetectInterfaceControl`、`findConnectionOwner`、`packageNameByUid`、`uidByPackageName`、`writeLog` 全部桥接到 `SingFlowVpnService`；
     - 调用 `Libbox.newService(configJson, platformProxy)` 并执行 `boxService.start()`！
   - 当 `app/libs/libbox.aar` 暂未放入时：
     - **绝不崩溃，也绝不报编译错误**！仍然真实调用 `SingFlowVpnService.establishTun()` 建立系统级 Android VPN TUN 虚拟网卡接口（触发真实的系统 VPN 授权弹窗、状态栏 VPN 钥匙图标与前台实时通知），并在日志流中打印当前编译好的完整 `sing-box 1.14+` JSON 配置与内核状态说明，让整套 VPN 生命周期、测速、流量监控与 UI 100% 真实运转！
3. **实现 `SingFlowVpnService.kt`（严格适配 Android 13 ~ 17）**：
   - 启动前台服务时使用：
     ```kotlin
     ServiceCompat.startForeground(
         this,
         NOTIFICATION_ID,
         buildForegroundNotification(nodeName, 0L, 0L),
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
             ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
         } else {
             0
         }
     )
     ```
   - `establishTun(settings: AppSettings): ParcelFileDescriptor?`：
     - 配置 `VpnService.Builder()`：`setSession("SingFlow")`、`setMtu(settings.tunMtu)`、`addAddress("172.19.0.1", 30)`、`addRoute("0.0.0.0", 0)`、`addDnsServer("1.1.1.1")`、`setMetered(false)`。
     - **防回环保护**：如果启用了分应用代理（`settings.perAppProxyEnabled`），根据 `perAppProxyMode` 调用 `addAllowedApplication` 或 `addDisallowedApplication`；**无论何时，必须确保 `addDisallowedApplication(packageName)` 将 SingFlow 自身排除在 TUN 路由之外**，并配合 `protect(fd)` 彻底杜绝代理回环死锁！
   - 实现 Android 10 ~ 17 的 `findConnectionOwner`：通过 `ConnectivityManager.getConnectionOwnerUid(ipProtocol, InetSocketAddress(srcIp, srcPort), InetSocketAddress(dstIp, dstPort))` 获取连接所属 App UID。
   - 支持 **节点热切换 (`ACTION_RELOAD`)**：用户在已连接状态下点击列表中的其他节点时，直接重新生成 `configJson` 并平滑重启内核服务，不断开系统 VPN 准备状态。
4. **实现 `NodeLatencyTester.kt` 与 `TrafficAndLogMonitor.kt` 及 `QuickTileService.kt`**：
   - `NodeLatencyTester`：使用 `Semaphore(10)` 并发对节点 `server:port` 发起真实 TCP Socket 连接握手测速（超时 3000ms）及 HTTP 204 URLTest 测速，实时更新 Room 数据库中的 `latencyMs`。
   - `TrafficAndLogMonitor`：优先连接 `ws://127.0.0.1:9090/traffic` 与 `/logs`（Clash API），并自动结合 `TrafficStats.getUidTxBytes(Process.myUid())` 实时计算每秒上下行网速 `uploadSpeedBytes` / `downloadSpeedBytes` 与累计流量，每秒刷新 `VpnStateRepository` 与前台通知栏。

#### 2. `[✅ Phase 4 模型自检门禁 (Self-Check Gate)]`

Agent 必须在终端执行：
```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```
- **通过标准**：引擎层与服务层全部编译通过，且原有单元测试无回归错误。

#### 3. `[🔔 Phase 4 到达时向用户输出的提醒]`

> **[🔔 SingFlow 阶段 4 完成 — `SingFlowVpnService` 与 `libbox` 双模引擎已就绪]**
> - 已完成适配 Android 13~17 的 VPN 前台服务、TUN 虚拟网卡建立、防回环保护、并发延迟测速器与实时网速监控。
> - **【给您的提醒】**：无需任何操作，我现在自动进入 **Phase 5：构建 Jetpack Compose Material 3 流体界面并打包 APK**。

### Phase 5：Jetpack Compose Material 3 现代流体界面 (`ui/`) 与 APK 打包门禁

#### 1. Agent 自动执行动作

打造高颜值、丝滑流畅的 Jetpack Compose 界面（全面适配 Android 13～17 动态色彩与 Edge-to-Edge 安全边距）：

1. **主题与色彩系统 (`ui/theme/`)**：
   - 支持 Android 12+ `dynamicDarkColorScheme` / `dynamicLightColorScheme`，并内置高对比度 **Aurora Cyan（极光青蓝）** 默认配色。
   - 定义协议专属标签配色函数 `ProtocolType.badgeColor()`（VLESS 电光紫、VMess 科技蓝、Trojan 琥珀橙、Hysteria2 极光粉、SS 翡翠绿、TUIC/WG 青色）。
2. **四大核心页面与交互组件 (`ui/components/` & `ui/screens/`)**：
   - **`HeroConnectButton.kt`**：带双层 `infiniteRepeatable` 脉冲水波纹扩散动效的中央连接大按钮，清晰展示「未连接 (Tap to Connect)」、「连接中 (Connecting...)」、「已保护 (Connected · 实时时长)」三种状态。
   - **`TrafficWaveCard.kt`**：实时展示 `↑ 上传速率`、`↓ 下载速率`、`累计上传/下载流量`，底部使用 Compose `Canvas` 绘制最近 30 个采样点的平滑三次贝塞尔曲线动态网速波形图。
   - **`DashboardScreen.kt`**：顶部品牌栏 + 路由模式快捷切换胶囊（`全局代理` / `绕过局域网与大陆` / `仅绕过局域网`）+ `HeroConnectButton` + `TrafficWaveCard` + 当前激活节点卡片（点击可快速切换节点）。
   - **`NodesScreen.kt` + `NodeItemCard.kt` + `ImportBottomSheet.kt`**：
     - 顶部提供「⚡ 一键并发测速」、「按延迟排序」、「➕ 导入节点」按钮。
     - `ImportBottomSheet` 提供：
       1. **📋 从剪贴板一键导入**（读取系统剪贴板文本自动解析入库）；
       2. **📝 多行 V2Ray 链接 / Base64 订阅原文粘贴导入框**（方便模拟器或 ADB 直接输入测试）；
       3. **🧪 一键生成 5 个全协议示例节点**（包含 VLESS-REALITY、VMess-WS-TLS、Trojan-gRPC、SS-2022、Hysteria2，默认预置公网可测速 IP 如 `1.1.1.1:443` / `8.8.8.8:443`，确保用户打开 App 第一秒就能测试延迟测速、配置编译与 VPN 开关！）。
   - **`LogsScreen.kt`**：
     - 顶部 SegmentedButton 切换 **「实时内核日志」** 与 **「sing-box 1.14+ JSON 配置预览」**。
     - 在 JSON 预览页高亮展示当前节点实时编译出的完整 `sing-box` JSON，并提供「一键复制 JSON」按钮。
   - **`SettingsScreen.kt`**：
     - 支持修改远程 DoH DNS、直连 DNS、TUN MTU、本地 Mixed 端口、IPv6 开关，以及 **「分应用代理 (Per-App Proxy)」选择器弹窗**（支持搜索本机应用并勾选绕过或仅代理）。
3. **支持 ADB 自动化测试的 Intent 入口 (`MainActivity.kt`)**：
   - 在 `MainActivity.onCreate` / `onNewIntent` 中额外支持接收调试 Intent Extra：
     - `intent.getStringExtra("EXTRA_IMPORT_URI")`：当收到该参数时，自动调用 `UniversalImportManager.importFromText(uri)` 入库并选中该节点；
     - `intent.getBooleanExtra("EXTRA_AUTO_CONNECT", false)`：当为 `true` 且已有 VPN 权限时，自动触发启动 `SingFlowVpnService`！
     - **设计意图**：让 Agent 在 Phase 6 能够通过一条 `adb shell am start` 命令全自动完成「导入 V2Ray 节点 + 启动 VPN 连接」的端到端闭环验证！

#### 2. `[✅ Phase 5 模型自检门禁 (Self-Check Gate)]`

Agent 必须在终端依次执行：
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
ls -lh app/build/outputs/apk/debug/app-debug.apk
```
- **通过标准**：
  1. `./gradlew :app:assembleDebug` 构建成功（`BUILD SUCCESSFUL`）；
  2. `app/build/outputs/apk/debug/app-debug.apk` 文件存在且大小大于 5MB；
  3. 如果存在任何 Kotlin 编译错误、Compose 版本冲突或 Manifest 合并错误，Agent 必须自动修复并重新打包，直到生成 `app-debug.apk`。

#### 3. `[🔔 Phase 5 到达时向用户输出的提醒]`

> **[🔔 SingFlow 阶段 5 完成 — Debug APK 已成功打包生成！]**
> - 已完成全套 Jetpack Compose Material 3 界面开发，并成功编译生成 `app/build/outputs/apk/debug/app-debug.apk`。
> - **【给您的提醒】**：我现在立即进入 **Phase 6：真机/模拟器自动化安装、节点导入、VPN 启动与端到端联调自检**。如果您连接了真机，请保持手机屏幕解锁；如果没有连接真机，我将自动为您启动 Android 模拟器进行测试。

### Phase 6：真机 / 模拟器 (Android 13 ~ 17) 全自动安装、联调与自我检查闭环

#### 1. Agent 自动执行动作

编写并执行 `scripts/e2e_adb_verify.sh`，由 Agent 自动完成真机或虚拟机上的端到端验收：

1. **设备自动发现与模拟器自启动**：
   - 执行 `adb devices | grep -w "device"`。
   - **情况 A（检测到用户已连接真机或已运行的模拟器）**：直接锁定该设备 ID，通过 `adb shell getprop ro.build.version.release` 和 `ro.build.version.sdk` 打印设备的 Android 版本（如 Android 13 / 14 / 15 / 16 / 17）。
   - **情况 B（未检测到任何在线设备）**：
     - 自动查询 `emulator -list-avds`；
     - 若已有 AVD，使用 `nohup emulator -avd <avd_name> -no-snapshot-load -gpu swiftshader_indirect >/dev/null 2>&1 &` 在后台拉起模拟器；
     - 若尚无 AVD 且存在 `avdmanager`，自动使用已安装的 system-image（优先 Android 17 API 37 / Android 16 API 36 / Android 13 API 33）创建名为 `SingFlow_Test_AVD` 的虚拟设备并拉起；
     - 循环执行 `adb wait-for-device` 并轮询 `adb shell getprop sys.boot_completed` 直至返回 `1`。
2. **自动安装 APK 与静默授予系统权限**：
   ```bash
   # 1. 安装 APK
   adb install -r app/build/outputs/apk/debug/app-debug.apk

   # 2. 自动授予 Android 13~17 通知权限
   adb shell pm grant com.singflow.android android.permission.POST_NOTIFICATIONS || true

   # 3. 关键黑科技：通过 appops 直接授予 Android VpnService 激活权限（免除人工点击 VPN 弹窗！）
   adb shell cmd appops set com.singflow.android ACTIVATE_VPN allow || true
   ```
3. **自动注入 V2Ray 测试节点并触发连接与 UI 树自检**：
   ```bash
   # 清空旧日志
   adb logcat -c

   # 启动 SingFlow，通过 EXTRA_IMPORT_URI 自动导入一条标准 VLESS-REALITY 节点并自动触发连接
   TEST_VLESS="vless://00000000-0000-0000-0000-000000000000@1.1.1.1:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=mB5_7x6Z8q9w0e1r2t3y4u5i6o7p8a9s0d1f2g3h4j5&sid=abcd1234&type=tcp#SingFlow-AutoTest-Node"
   adb shell am start -n com.singflow.android/.MainActivity \
       --es EXTRA_IMPORT_URI "$TEST_VLESS" \
       --ez EXTRA_AUTO_CONNECT true

   sleep 4

   # 若是部分定制 ROM 真机拦截了 appops ACTIVATE_VPN 并弹出了系统 VpnDialogs，尝试用 uiautomator 自动点击“确定/OK”或提醒用户点击
   CURRENT_FOCUS=$(adb shell dumpsys window | grep -E "mCurrentFocus" || true)
   if echo "$CURRENT_FOCUS" | grep -q "VpnDialogs"; then
       echo "[🔔 用户操作提醒] 检测到您的真机系统弹出了首次 VPN 授权确认框，请在手机屏幕上点击「确定/允许」！"
       adb shell input keyevent KEYCODE_TAB
       adb shell input keyevent KEYCODE_ENTER
       sleep 3
   fi
   ```
4. **全方位运行时健康度自检（Crash 检查 + UI 渲染检查 + TUN 网卡检查）**：
   - 检查进程是否存活：`adb shell pidof com.singflow.android` 必须返回有效 PID。
   - 检查是否有崩溃日志：`adb logcat -d *:E | grep -E "FATAL EXCEPTION|AndroidRuntime.*com.singflow.android"` 必须为空！
   - 导出当前页面 UI 树：`adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`，验证页面上已正常渲染出 `"SingFlow"` 与导入的节点要素。
   - 检查 VPN 服务与网卡状态：`adb shell dumpsys vpn_management` 或 `adb shell ip addr show` 检查 TUN 服务启动状态。

#### 2. `[✅ Phase 6 模型自检门禁 (Self-Check Gate)]`

- **通过标准**：
  1. `app-debug.apk` 成功安装到目标 Android 设备（真机或模拟器）；
  2. `adb shell pidof com.singflow.android` 非空，且 `logcat` 中 **零 `FATAL EXCEPTION`**；
  3. 通过 Intent 注入的 V2Ray 节点已成功入库并编译为 `sing-box 1.14+` 配置，VPN 服务正常启动。
  4. **自动修复闭环**：如果在真机/模拟器启动或点击连接时发现任何 Crash（例如 ForegroundServiceType 异常、Room Schema 异常、Compose 布局异常），Agent 必须立即从 `adb logcat` 抓取堆栈、修改源码、重新运行 `./gradlew installDebug` 并重跑 `e2e_adb_verify.sh`，直到零报错通过！

#### 3. `[🔔 Phase 6 到达时向用户输出的最终交付报告]`

当 Phase 6 自检全部通过后，更新 `TASK_PROGRESS.md` 为全部 `[x]`，并向用户输出最终交付报告：
> **[🎉 SingFlow v1.0 全自动开发、编译与真机/模拟器联调全部通过！]**
> - **目标设备验证**：已在连接的设备（Android API 级别及型号）上完成自动安装、V2Ray 节点注入、配置编译与 VPN 启动验证，运行期间 **0 Crash / 0 Error**。
> - **您现在可以直接在手机/模拟器上体验**：
>   1. **一键导入 V2Ray 节点**：从 v2rayNG / v2rayN 复制任何节点链接，打开 SingFlow 进入「节点库」点击「从剪贴板导入」（或点击「生成示例节点」）；
>   2. **并发延迟测速**：点击「⚡ 一键测速」查看真实毫秒延迟；
>   3. **审查最新 sing-box 配置**：在「实时日志 -> JSON 预览」中查看或复制生成的 `sing-box 1.14+` 标准配置；
>   4. **开启代理**：点击首页中央脉冲大按钮即可开启或热切换节点。

## 四、 后期功能无人值守扩展协议（Phase 7 按需自动演进）

当用户后续对你说「帮我加上订阅自动更新」、「帮我加上二维码扫描」或「帮我加上 FakeIP 与规则集」时，你（AI Agent）只需直接按照以下预设的模块化规约自动实现并重跑 `./gradlew testDebugUnitTest assembleDebug` 与 `adb install -r`：

| 扩展模块 | 接入位置（零侵入原有代码） | 实现与自检规范 |
| :--- | :--- | :--- |
| **1. 机场订阅定时更新 (`SubscriptionUpdater`)** | 新增 `core/database/SubscriptionEntity.kt` + `core/parser/SubscriptionUpdater.kt` + `WorkManager` | 请求时携带 `User-Agent: SingFlow/1.0 sing-box/1.14.3`；自动解析响应头 `Subscription-Userinfo: upload=...; download=...; total=...; expire=...` 展示流量进度条与到期时间；响应体直接复用 `UniversalImportManager.importFromText(body, groupId)`。 |
| **2. 二维码扫描导入与生成分享 (`QrCodeHelper`)** | 扩展 `ui/components/ImportBottomSheet.kt` 与 `NodeItemCard.kt` | 引入 `com.google.mlkit:barcode-scanning:17.3.0` 与 `com.google.zxing:core:3.5.3`；实现 `NodeUriSerializer.toShareUri(node: ProxyNode): String` 将节点逆向导出为标准 `vless://` / `vmess://` / `hy2://` 链接并生成二维码 Bitmap。 |
| **3. FakeIP 极速模式与 `.srs` 规则集路由 (`FakeIpInterceptor`)** | 在 `core/config/interceptors/` 下新增 `FakeIpInterceptor.kt` 并注册到 `SingBoxConfigPipeline` | 严格遵循 `sing-box 1.12 / 1.14+` 新版 FakeIP 语法：在 `dns.servers` 注入 `{"type": "fakeip", "tag": "dns-fakeip", "inet4_range": "198.18.0.0/15", "inet6_range": "fc00::/18"}`，在 `dns.rules` 将 `query_type: ["A", "AAAA"]` 路由至 `dns-fakeip`，并在 `experimental.cache_file` 开启 `"store_fakeip": true`。 |
