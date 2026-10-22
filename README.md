# SingFlow

Android 13~17（API 33~37）上的 sing-box 客户端：Jetpack Compose UI + sing-box 1.14+ 原生内核（`libbox`），
支持七大协议导入、大陆分流、远程 DoH、前台服务保活与流量/日志监控。

- 当前版本：**1.0.0**（`versionCode = 1`）
- 包名：`com.singflow.android`
- 内核：sing-box **v1.14.3**（`libbox.aar`，ABI `arm64-v8a` / `x86_64`）

## 功能

| 模块 | 说明 |
| :--- | :--- |
| 协议导入 | `vless://`（TLS / REALITY / WS / gRPC / HTTP）、`vmess://`、`trojan://`、`ss://`、`hysteria2://`、`tuic://`、WireGuard |
| 配置编译 | 拦截器链式生成 sing-box 1.14+ 强类型配置（DNS / 出站 / 入站 / 路由），红线断言内建在单测里 |
| 分流 | `sniff` → `hijack-dns` → 私网直连 → 大陆分流（内置离线 `.srs` 规则集）；三档模式（全局 / 绕局域网与大陆 / 仅代理指定应用） |
| 引擎 | `LibboxBridge` 全反射调用 1.14 内核 API，`libbox.aar` 缺失时自动降级为诊断模式（不崩溃） |
| UI | Compose Material 3 + Edge-to-Edge，不锁定方向；首页实时速率、节点延迟、内核日志、设置 |
| 系统集成 | 前台服务（`systemExempted|specialUse`）、快捷开关磁贴、通知动态授权 |

## 构建

前置条件：

1. JDK 21、Android SDK（`compileSdk` 取本机已安装最高平台）、Node.js（仅验收脚本需要）。
2. **`app/libs/libbox.aar`**：已随仓库提供（56 MB，sing-box v1.14.3，ABI `arm64-v8a` + `x86_64`），clone 后直接构建，无需额外下载。
   如需自行重建，用 `.github/workflows/build-libbox.yml`（Go + gomobile + NDK，含 16 KB 页对齐断言），
   上游产物同源：[`yangche828/singflow-libbox`](https://github.com/yangche828/singflow-libbox) Release `libbox-v1.14.3`。
3. `local.properties` 写入 `sdk.dir=<Android SDK 路径>`。

```bash
./gradlew :app:kspDebugKotlin          # KSP
./gradlew :app:testDebugUnitTest       # 单元测试（含 sing-box 语法红线断言）
./gradlew :app:assembleDebug           # 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 验收

两套等价脚本（`.ps1` 为 Windows 主脚本，`.sh` 为 Linux/macOS/Git Bash 姊妹脚本，断言项一致）：

```powershell
# 真实节点（需自备可用节点）
pwsh -File scripts\e2e_adb_verify.ps1 -NodeUri 'vless://<uuid>@<host>:<port>?...#Node' `
  -RemoteDns https://doh.pub/dns-query -TestUrl "https://www.google.com/" -ExpectText "Google"

# 无公网节点：本机回环服务端
node scripts\dev\vless-loopback-server.js --port 8443
pwsh -File scripts\e2e_adb_verify.ps1 -ProxyLog acceptance\vless-loopback.log
```

脚本会完成：安装 → 导入节点 → 建立 VPN → 断言 `tun0` / MTU / 前台服务类型 / 无崩溃 / 节点入库 / **TUN 已下发 DNS**
→ 在 VPN 内用浏览器真实出网（随机 URL 排除缓存）。PC 侧还可先用 `scripts/dev/probe-vless-ws.js` 独立核对节点的 TLS/WS 层。

验收记录见 `TASK_PROGRESS.md`，交接与踩坑见 `HANDOFF.md`，原始证据在 `acceptance/`。

## 目录结构

```
app/src/main/java/com/singflow/android/
  core/model        领域模型（ProtocolType / ProxyNode / AppSettings / VpnState …）
  core/database     Room + DataStore + Hilt
  core/parser       节点链接解析职责链（七大协议 + 通用导入）
  core/config       sing-box 配置编译流水线（ConfigInterceptor 链）
  core/engine       LibboxBridge / SingFlowVpnService / 测速 / 流量与日志 / 快捷磁贴
  ui/               Compose 主题、组件、页面（首页/节点/日志/设置）
```

扩展点：新增协议实现 `NodeLinkParser` 并注册到 `UniversalImportManager`；新增配置能力实现 `ConfigInterceptor` 并注册到 `SingBoxConfigPipeline`。

## 已知限制

- 默认远程 DoH 为 `https://1.1.1.1/dns-query`；在部分网络/节点上该地址不可达（实测被 TLS alert 拒绝），可改 `https://doh.pub/dns-query`。
- 节点 URI 中的 `ech=` 参数暂未参与解析（需 `tls.ech.config`）；不影响连通性，但缺少 ECH 抗封锁性。
- 内置 `geosite-cn` 含 `gstatic.com` 等 Google CDN 域名，会被判定为大陆域名走直连。

## 许可

本项目依赖 sing-box 的 `libbox`（GPLv3）。**对外分发含 `libbox.aar` 的 APK 时须同时提供对应源码**；
`libbox.aar` 已入库（上游源码即 sing-box，见 https://github.com/SagerNet/sing-box ）；构建产物（`build/`、APK）不入库。
