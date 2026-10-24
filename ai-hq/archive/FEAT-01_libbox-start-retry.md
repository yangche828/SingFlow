# FEAT-01: libbox 启动失败自动重试加固 + 诊断横幅文案真实化（已完结）

## 完工验证记录（2026-10-24 20:21）
- `./gradlew :app:compileDebugKotlin` + `installDebug` 通过，已装真机 BKQ-AN10。
- 冷启动连接 → NATIVE（logcat `protect fd` ×26，UI 显示"已建立安全隧道 · NATIVE"）。
- Chrome 谷歌搜索"天气" → **真实返回天气结果**（北京 13°C 多云 / 最高18 最低7 / 逐小时 14时-19时 / "晚上8点到9点左右有雨"），浏览期新增 `protect fd` ×14 佐证。
- 激进快速重连（三连点，间隔 0.4~0.5s，比用户复现场景更狠）→ **零降级、零启动失败日志**，服务存活，终态已保护 · NATIVE · 流量计数在走。
- 重试路径未在真机触发（压力下未再出现锁竞争），代码路径经编译与逻辑审查保证。

## 背景与问题
- 2026-10-24 真机两次复现：断开后快速重连时，`LibboxBridge.start()` 抛
  `go.Universe$proxyerror: start or reload service: initialize cache-file: timeout`
  （sing-box `work/cache.db` 被旧实例锁住未释放）→ 返回 false → `engineMode=DIAGNOSTIC`
  → TUN 建立但流量黑洞（Chrome 打不开任何境外站）。
- 横幅文案"TUN 诊断模式：内核未加载（缺少 libbox.aar）"是**写死的误导文案**，
  libbox 从未缺失（dex 类 + .so 均在 APK 内）。
- 教训（2026-10-24 19:36）：诊断模式下 Chrome 长时间转圈黑屏，视觉模型对截图
  误判"已加载"，导致错误宣布"转发正常"。验证转发必须用 logcat `protect fd` +
  页面文字转录双证据。

## 方案（四步法对应：直接小修，走五步微迭代飞轮）
1. `LibboxBridge.start()` 拆出 `startOnce()`，外层分级重试：
   - 首次失败且错误含 `cache-file` / `timeout` → 等 1.5s 重试（等旧实例释放锁）
   - 仍失败 → 删除 `workingDir/cache.db`（仅缓存 selector/fakeip 映射，可重建）→ 第三次
   - 三次失败才降级诊断
2. `LibboxBridge.lastStartError`（public, private set）暴露最终失败原因；
   每次 `start()` 入口先置空，**重试成功时也显式清空**，失败原因截断至 200 字，
   避免诊断横幅展示已恢复的旧错误或超长 decode 报错。
3. `SingFlowVpnService` startTunnel / reloadEngine 诊断分支：setStatus 带上
   `errorMessage = lastStartError`。
4. `DashboardScreen` / `DiagnosticsScreen` 横幅改为动态：
   `TUN 诊断模式：{真实原因}，流量不转发。`（真缺 aar 时才显示"未检测到 libbox.aar"）。

## 修改文件清单
- app/src/main/java/com/singflow/android/core/engine/LibboxBridge.kt
- app/src/main/java/com/singflow/android/core/engine/SingFlowVpnService.kt
- app/src/main/java/com/singflow/android/ui/screens/DashboardScreen.kt
- app/src/main/java/com/singflow/android/ui/screens/DiagnosticsScreen.kt

## 验证标准
- `./gradlew :app:compileDebugKotlin` 通过
- 真机装包后：冷启动连接 → NATIVE 模式（logcat 有 `protect fd`）
- 快速断开→立即重连 ×3 → 不再降级诊断（或重试后自恢复）
- Chrome 打开 google.com：logcat 出现新的 `protect fd` + 截图转录出页面文字
