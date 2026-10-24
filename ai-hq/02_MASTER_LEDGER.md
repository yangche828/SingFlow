# 02. 全局研发进度总台账 (Master Ledger)

## 🔥 一、 在研并发功能表 (Active Features)
| 编号 | 功能/页面名称 | 当前阶段 (四步法) | 当前状态 / 阻塞点 | 独立档案路径 |
| :--- | :--- | :--- | :--- | :--- |
| — | （空） | — | FEAT-03 已完工归档，当前无在研功能 | `archive/FEAT-03_ech-pipeline-review-fix.md` |

## 📋 二、 待开发事项池 (Backlog)
- **[发现] 节点健康标签与实际可用性不同步**：2026-10-24 20:21 真机节点显示"新加坡 · 异常 0分"，但该节点实际转发正常（谷歌搜索已验证）。健康分评估机制待复核。FEAT-02 后探活已能成功（同节点 0 → 29 分、连续失败 0），但历史失败残留仍会让健康标签滞后，展示策略需调整。
- **[发现] AnyTLS / SOCKS5 分享链接无 Parser**：订阅文件中 `anytls://` ×6、`socks://` ×1 会被静默跳过。需要时按 `NodeLinkParser` 扩展点新增 `AnytlsParser` / `SocksParser`（sing-box 1.14 原生支持 both）。
- **[建议] ECH PEM 会随 key 轮换失效**：当前实现是导入时一次性查 DoH 并落库（`TlsConfig.echConfigPem`）。ECH key 轮换后旧 PEM 会失效且不会自愈，只能重新导入订阅。可考虑改用 sing-box 1.13+ 原生 `tls.ech.query_server_name`（PEM 留空时内核自行按 DNS 查询），彻底去掉导入期 DoH 依赖——需内核实测后再切换。
- **[建议] `store_selected` 原始诉求未落地**：原本想禁止 cache.db 恢复旧 selector，但内核不识别该字段。当前靠 App 层 selectedNode + 启动后 Clash API 复位，未观察到回退；如需强保证，可在启动后显式 PUT selector 到当前节点。
- **[发现] 节点库与订阅文件不完全对应**：设备库 95 个（94 VLESS + 1 HY2），订阅文件 105 行中 `anytls`×6 / `socks`×1 无 Parser；文件里的 `vmess` / `tuic` 各 1 行可正常解析但库中不存在（该次导入未包含这两行）。
