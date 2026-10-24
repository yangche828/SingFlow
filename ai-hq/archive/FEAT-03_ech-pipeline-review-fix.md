# FEAT-03: ECH 导入链路审查修复（订阅路径从未生效 / PEM 被覆盖）（已完结）

## 完工验证记录（2026-10-24 23:55）
- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` 通过，169 例单测全绿。
- 新增回归用例：
  - `EchConfigEndpointTest`（5 例：仅来源域名、`udp://` 端点、`/dns-query` → `/resolve`、已归一端点、空端点）
  - `NodeMergeTest`（+2 例：刷新保留旧 PEM、新 PEM 优先且尊重移除）
  - `UniversalParserTest`（+1 断言：`ImportResult.saved`）
- Room 账实核对（真机 `singflow.db` 只读导出 + Python sqlite3）：95 节点中
  **仅 1 个带 ECH PEM**（`761f8a6f-4f1d-4cc4-9d08-59196cd8b97d`，即前两轮验证用的那条），
  其余 93 个 VLESS 全为 `echEnabled=false` 或 PEM 为空 —— 与下述缺陷 1 完全吻合。

## 缺陷 1（最严重）：订阅刷新路径从不解析 ECH
- 现象：手动粘贴单条 `ech=...` 链接时 ECH 生效；走订阅刷新/批量导入则全部失效。
- 根因：`SubscriptionManager.refresh()` 调的是 `importManager.parseText(parsed)`，
  只做语法解析；真正做 DoH 查询并写 PEM 的 `resolveEchConfigs` 只在
  `importFromText()`（手动导入）里被调用。订阅用户等于 100% 没有 ECH。
- 修复：`SubscriptionManager.refresh()` 在 `diff` 与落库前调用
  `importManager.resolveEchConfigs(parsed.nodes)`，与手动导入共用同一套逻辑与同源缓存；
  `imported` 计数改用补齐后的列表。

## 缺陷 2：订阅刷新用空 PEM 覆盖已成功的 ECH config
- 场景：本机 DoH 偶发超时 → 本轮 `echConfigPem` 为空 → `NodeMerge.preserve` 直接采用新
  `tlsJson` → 上一次成功拉到的 PEM 被清空，ECH 被静默关闭、SNI 重新暴露。
- 修复：`NodeMerge.preserveEch(existing, incoming)`（纯函数，可单测）——
  仅当“新配置仍声明 ech + 新 PEM 为空 + 旧 PEM 非空”时沿用旧 PEM 与旧 DoH 地址；
  链接已不再带 `ech=` 参数时尊重新配置，不复活旧 PEM。`NodeRepository.saveAllPreservingStats` 调用它。

## 缺陷 3：ECH 失败不缓存 + 缓存并发不安全
- 现象：订阅里同源节点（`cloudflare-ech.com`）多达 94 个，若 DoH 失败/慢，每次都重发请求。
- 根因：`echCache` 为 `mutableMapOf<String, String?>()`，`getOrPut` 对 null 值不落缓存，
  且导入可能由 UI / 订阅刷新 / 后台 Worker 并发触发。
- 修复：改 `ConcurrentHashMap<String, String>`，失败写空串哨兵（“已查询但失败”），
  读取后 `orEmpty()/isNullOrBlank()` 判定，同源域名整批只打一次 DoH。

## 缺陷 4：`LibboxBridge.lastStartError` 生命周期与 KDoc 不符
- 根因：KDoc 写“成功启动后置空”，代码只在 `start()` 入口置空；`startOnce()` 重试成功后不清空，
  诊断横幅可能继续展示已恢复的旧错误。
- 修复：`startOnce()` 成功分支显式 `lastStartError = null`；失败原因
  `take(MAX_ERROR_LENGTH = 200)` 截断（整份配置 decode 失败会回显超长字段路径）。

## 缺陷 5：导入解析器里的 Android 依赖与错误注释
- `UniversalImportManager` 曾调用 `android.util.Log.d("EchImport", ...)`，与
  “解析逻辑必须可在 JVM 单测运行”的红线相悖，且每次导入刷日志；已删除。
- `VlessParser` 注释写“仅有域名时用 Google DoH 兜底”，实际 `EchConfigResolver.DEFAULT_DOH`
  是阿里 `https://dns.alidns.com/resolve`（Google DoH 在境内不可达）；注释已纠正。
- `EchConfigResolver` 同时加固：`callTimeout(6s)` 收敛整次请求；来源域名 URL 编码；
  非 `http(s)` 端点（订阅里出现过 `udp://1.1.1.1`）直接丢弃并回退默认 DoH；
  非 JSON 响应直接跳过；`jsonEndpoints` 改 `internal` 供单测。

## 缺陷 6：重复导入提示“未识别到有效节点”
- 根因：`importFromText` 返回 `nodes = toSave`（仅新增），纯刷新时为空 → UI 走 `else` 分支。
- 修复：`ImportResult` 新增 `saved` 字段；`nodes` 返回刷新后的全量，`saved` 表示本次真新增数；
  `MainViewModel` 据此区分“成功导入 N 个节点”/“节点已存在，已刷新 N 个存量节点”。

## 修改文件清单
- `core/parser/UniversalImportManager.kt`（ECH 负缓存、`resolveEchConfigs`、`ImportResult.saved`）
- `core/parser/EchConfigResolver.kt`（端点归一化、超时、编码、非 JSON 跳过）
- `core/subscription/SubscriptionManager.kt`（刷新补 ECH —— 核心修复）
- `core/database/NodeRepository.kt` / `core/database/NodeMerge.kt`（`preserveEch`）
- `core/engine/LibboxBridge.kt`（`lastStartError` 生命周期 + 截断）
- `core/parser/impl/VlessParser.kt`（注释纠正）
- `ui/MainViewModel.kt`（导入提示文案）
- 测试：`EchConfigEndpointTest.kt`（新）、`NodeMergeTest.kt`（+2）、`UniversalParserTest.kt`（+1 断言）

## 审查中确认“不是问题”的项
- `gradle.properties` 的 `org.gradle.tooling.parallel=true`：确为 Gradle 9.4+ 属性，
  当前 wrapper 8.10.2 下静默无效但无害，注释（“Gradle 9.4+”）正确 → 不改。
- `settings.gradle.kts` 阿里云镜像：用户网络环境需要，构建已验证可用 → 保留。
- `.zcode/config.json`：仅启用 `computer-use` 插件，无敏感信息 → 未改，仅记录。

## 遗留 / 待办
- 库中 93 个存量节点需重新导入或刷新订阅后才会补齐 ECH PEM（本次修复已保证新路径会补）。
- ECH 方案是否改用内核原生 `tls.ech.query_server_name`（去掉导入期 DoH 与 key 轮换失效问题），
  见 `02_MASTER_LEDGER.md` Backlog，需内核实测后再切换。
