# FEAT-02: 真机可达性修复（cache_file 不兼容字段 + 多路复用错误叠加）（已完结）

## 完工验证记录（2026-10-24 23:07）
- `installDebug` + `:app:testDebugUnitTest` 全绿。
- 真机 BKQ-AN10（Android 17 / API 37）连接用户订阅节点
  `172.66.138.182:443`（VLESS + WS + TLS + ECH，Cloudflare 前置）：
  - UI：`已保护` / `已建立安全隧道 · NATIVE`，健康分从 0 → 29，连续失败数 0。
  - `adb shell curl https://www.google.com/generate_204` ×3 → 全部 `204`。
  - `curl https://www.cloudflare.com/cdn-cgi/trace` → `ip=45.139.226.157 loc=SG`。
  - Chrome 打开 `google.com/search?q=weather` → 真实渲染天气结果（Hanoi 28°C 等），
    无 FATAL EXCEPTION。
  - 证据截图：`acceptance/r32_google_ech_native.png`。
- 另测自建 `hy2-instance`（35.212.158.154:19308）→ Google 204 ×3、egress `loc=US`，
  证明转发链路与节点本身无关。

## 问题一：内核启动被配置字段拒绝
- 现象：连接后走 TUN 诊断模式（不转发），诊断横幅：
  `proxyerror: start or reload service: decode config:
   experimental.cache_file.store_selected: json: unknown field "store_selected"`
- 根因：未提交改动向 `experimental.cache_file` 写入 `store_selected`，
  仓库内 `libbox.aar`（sing-box 1.14.3 自建）不识别该字段，
  `DisallowUnknownFields` 直接拒绝整份配置，内核未启动。
- 修复：移除该字段；当前节点仍以 DataStore `selectedNode` 为唯一事实源。

## 问题二：h2mux 被服务端关闭，表现为“节点全超时”
- 现象：内核 NATIVE、节点 TLS 已建立，但所有新域名 DNS/网页失败；
  内核日志：
  `outbound/vless[...]: outbound multiplex connection to ...:443`
  `outbound/vless[...]: outbound connection to sp.mux.sing-box.arpa:444`
  `ERROR connection: connection download closed: http2: client connection force closed via ClientConn.Close`
- 根因：用户设置 `antiBlocking.multiplexEnabled=true / h2mux`。
  该订阅节点为 Cloudflare Worker / Xray 系服务端，只支持自身 mux（mux.cool）或
  根本不支持 sing-box 私有 h2mux；WS 传输本身已在单连接上复用，
  叠加 h2mux 会被服务端直接断开。
- 修复：`ProxyOutboundFactory` 新增 `muxCompatible` 条件（本次一并引入，并非原有逻辑）：
  `!realityEnabled && flow.isBlank() && transport.isDefaultTcp`，即
  reality（ClientHello 已加密，分片无对象）、vision 流控（要求独占连接）、
  WS / gRPC / HTTPUpgrade / HTTP 等自带复用的传输一律跳过 mux，仅 TCP 直连才允许。

## 修改文件清单
- app/src/main/java/com/singflow/android/core/config/interceptors/ExperimentalInterceptor.kt
- app/src/main/java/com/singflow/android/core/config/interceptors/ProxyOutboundFactory.kt
- app/src/test/java/com/singflow/android/core/config/AntiBlockingConfigTest.kt（新增 2 个回归用例）
- app/src/test/java/com/singflow/android/core/parser/VlessEchParseTest.kt（随工作区一并验证）

## 回归用例
- `multiplex is skipped for websocket transport`
- `ech skips tls fragment to keep client hello intact`

## 遗留 / 待办
- 节点健康分仍会短时显示“异常”（历史失败残留），下一轮探活成功后回升；
  与真实可用性不同步的展示问题仍在 `02_MASTER_LEDGER.md` Backlog。
- 订阅中的 AnyTLS / SOCKS5 链接当前无对应 Parser，会被跳过（不影响本次验证节点）。
