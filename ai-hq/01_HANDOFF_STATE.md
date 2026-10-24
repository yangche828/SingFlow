# 01. 跨模型热切换快照 (Hot-Swap Handoff State)
> ⚠️ **致新接手 AI**：读完本文件与 `02_MASTER_LEDGER.md` 即可 3 秒接管现场，严禁让人类重复交代背景！
- **更新时间**：2026-10-24 23:55 | **执笔 AI**：OpenCode (DeepSeek V4.1)
- **当前焦点功能**：无（FEAT-03 已完工归档）
- **当前所处阶段**：`Step 0/4`（环境基建 ✅ 完结，含热重载）
- **编译与真机状态**：`BUILD SUCCESSFUL` + 单测全绿（169 例）；真机 BKQ-AN10 (Android 17 / API 37) 在线；**订阅节点 VLESS+WS+ECH 真实转发验证通过**（2026-10-24 23:07：Google `generate_204` ×3 = 204，egress `45.139.226.157 / SG`，Chrome 谷歌搜索真实渲染天气页；自建 hy2 节点同样 204 ×3 / US）

## 1. 上一轮刚完成的动作摘要
- **FEAT-03 完工**：审查 `FEAT-01 / FEAT-02` 遗留改动，修掉 5 处真实缺陷（详见 `archive/FEAT-03_ech-pipeline-review-fix.md`）：
  1) **订阅刷新路径从不解析 ECH**（`SubscriptionManager` 走 `parseText` 而非 `importFromText`）→ 订阅用户的 ECH 实际全灭，只有手动粘贴的单条链接生效；
  2) 订阅刷新会用空 PEM 覆盖已成功的 ECH config（`NodeMerge` 直接采用新 `tlsJson`）；
  3) ECH DoH 缓存不缓存失败结果 + `mutableMapOf` 并发不安全 → 94 个同源节点会各发一次请求；
  4) `LibboxBridge.lastStartError` 重试成功后不清空，与 KDoc 不符；
  5) 导入解析器里调 `android.util.Log` + 注释写错兜底 DoH 服务商。
- **FEAT-02**：`experimental.cache_file.store_selected` 被内核拒绝 → 已移除；`h2mux` 叠加 WS 被服务端关闭 → 仅 TCP 直连传输启用 mux。
- **排障方法论复核**：UI“已保护 NATIVE” + logcat `protect fd` 只能证明隧道建立，不能证明可上网；
  必须叠加 `adb shell curl` 状态码或页面文字转录。
- **节点账实核对**（Room `singflow.db` 实测）：库内 95 个 = VLESS 94 + HY2 1；订阅文件 105 行 = VLESS 95（去重 94）/ anytls 6 / hy2 1 / socks 1 / tuic 1 / vmess 1。
  `anytls`/`socks` 无 Parser 被跳过；`vmess`/`tuic` 各 1 行可正常解析但当前库中不存在。
  **重要**：库内仅 1 个节点（`761f8a6f-…`，即本轮验证用的节点）带 ECH PEM，其余 93 个 VLESS 节点无 ECH——原因是它们经订阅/批量导入路径写入，而该路径此前不解析 ECH，FEAT-03 已修复；需重新导入（或刷新订阅）后才会补齐。

## 2. 当前阻塞点 / 待人类决策事项
- 无阻塞。`store_selected` 的原始诉求（防止 cache.db 恢复旧 selector）未实现，
  但当前依赖 App 层 selectedNode + 启动后 Clash API 复位，未观察到回退；如需强保证再单独立项。
- 待决策：是否把 ECH 从“导入期 DoH + 落库 PEM”改为 sing-box 原生 `ech.query_server_name`（见 Backlog）。

## 3. 下一个 AI 接手后的第一要务 (Next Immediate Action)
- 收到需求后：分配新编号 → 创建 `active/<编号>_<slug>.md` → 登记 `02_MASTER_LEDGER.md` 在研表 → 四步穿插法 Step 1。
- 健康分展示与实际可用性不同步（Backlog 首项）可直接接手：先复现探活链路，再决定是重置历史失败计数还是修正评分公式。
