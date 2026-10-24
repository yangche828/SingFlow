# 03. 已完结功能交付总台账 (Completed Ledger)

| 完结日期 | 编号 | 功能/页面名称 | 核心产出/修改源码文件 | 归档详情索引 |
| :--- | :--- | :--- | :--- | :--- |
| 2026-10-24 | `FEAT-03` | ECH 导入链路审查修复（订阅刷新从不解析 ECH + 空 PEM 覆盖 + 缓存并发） | `SubscriptionManager.kt`（刷新补 ECH）、`UniversalImportManager.kt`（负缓存 + `resolveEchConfigs` + `ImportResult.saved`）、`EchConfigResolver.kt`（端点归一化/超时）、`NodeMerge.kt` / `NodeRepository.kt`（`preserveEch`）、`LibboxBridge.kt`（`lastStartError` 生命周期）、`MainViewModel.kt`（导入提示）、测试 `EchConfigEndpointTest.kt` 等 | `archive/FEAT-03_ech-pipeline-review-fix.md` |
| 2026-10-24 | `FEAT-02` | 真机可达性修复（cache_file 不兼容字段 + WS 叠加 h2mux） | `ExperimentalInterceptor.kt`（移除 store_selected）、`ProxyOutboundFactory.kt`（仅 TCP 传输启用 mux）、`AntiBlockingConfigTest.kt`（+2 回归用例） | `archive/FEAT-02_runtime-config-mux-fix.md` |
| 2026-10-24 | `FEAT-01` | libbox 启动失败自动重试 + 诊断文案真实化 | `LibboxBridge.kt`（分级重试 + lastStartError）、`SingFlowVpnService.kt`（诊断分支带真实原因）、`DashboardScreen.kt` / `DiagnosticsScreen.kt`（动态横幅）、`VpnState.kt`（KDoc） | `archive/FEAT-01_libbox-start-retry.md` |
| 2026-10-24 | `FEAT-00` | 开发环境基建 | `local.properties`、`ai-hq/*`（JDK21/SDK/ADB 探测、账本初始化、构建验证） | （无独立档案，见 01 快照） |
