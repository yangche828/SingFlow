# SingFlow — Claude / Antigravity 全局执行规则

本文件与 `AGENTS.md` 同步，作为 Antigravity / Claude Code 的上下文入口。若两者冲突，以 `AGENTS.md` 为准。

## 最高优先级

1. 全程无人值守执行 Phase 0 → Phase 6，不等待人工粘贴提示词。
2. 每个 Phase 结束必须运行自检门禁，失败则自动修复重跑，禁止带病进入下一阶段。
3. 禁止任何 `TODO` / 占位符 / 省略代码。
4. 只有在物理硬件或首次系统级授权弹窗时才输出 `[🔔 用户操作提醒]`。

## 技术基线

- 包名 `com.singflow.android`；minSdk 33；compileSdk 动态探测最高已安装平台（37/36/35）。
- AGP 8.7.2 + Gradle 8.10.2 + Kotlin 2.0.21 + KSP + Hilt 2.52 + Room 2.6.1 + Compose BOM 2024.10.01。
- 16KB Page Size：`useLegacyPackaging = false`。
- FGS：`systemExempted|specialUse`，`ServiceCompat.startForeground` 显式传类型。
- Edge-to-Edge：`enableEdgeToEdge()`，禁止方向锁定。

## sing-box 1.14+ 六条红线

1. DNS server 强类型（`https` / `udp` / `local`），禁止 `address` 字符串写法。
2. 禁止 `outbound: "any"` 与 `domain_strategy`，使用 `default_domain_resolver` / `domain_resolver`。
3. 禁止 `block` / `dns` outbound，使用 `hijack-dns` / `reject` 规则动作。
4. 禁止 inbound `sniff`，首条路由规则为 `{"action":"sniff","timeout":"300ms"}`。
5. TUN 使用 `address` 数组，禁止 `inet4_address`。
6. 禁止 `store_rdrc`，使用 `store_dns`；分流用 `ip_is_private` + 远程 `.srs`。

## 分层与扩展点

- `core/parser`：`NodeLinkParser` 职责链，新增协议实现接口并注册。
- `core/config`：`ConfigInterceptor` 拦截器链，新增能力实现接口并注册。
- `core/engine`：`LibboxBridge` 反射双模（有 `libbox.aar` 用原生，无则真实 TUN 诊断回退）。
- `ui/`：Compose Material 3，四大页面 Dashboard / Nodes / Logs / Settings。

## 自检命令

```bash
./gradlew :app:kspDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
scripts/e2e_adb_verify.sh
```

## 禁止

- 在 JVM 单测路径使用 `android.util.Base64`。
- 把 `libbox.aar` 作为强编译依赖。
- 写死 `android:screenOrientation="portrait"`。
- 在 `outbounds` 中生成 `block` / `dns`。
