# 00. 顶层开发宪章与架构基线 (Protocol & Architecture)

## 1. 全局锁定环境与技术栈
- **宿主机**：Windows 10 (19044) + Git Bash + JDK 21 (Temurin 21.0.11)
- **Android SDK**：`D:\Apps\Android\Sdk`（platforms 33/35/36；build-tools 34.0.0/37.0.0；emulator 已装）
- **构建**：Gradle 8.10.2 wrapper；`local.properties` 已配置（gitignore，不入库）
- **测试真机**：BKQ-AN10（Android 17 / API 37，序列号 AMRF025A21005896）USB 在线，ADB 37.0.1 可直连（2026-10-24 更新，此前记录的“暂无连接”已失效）
- **核心技术栈**：Kotlin 2.0.21 + Jetpack Compose + Material 3 + Coroutines/StateFlow + MVVM/UDF + Hilt + Room
- **架构铁律**：四步穿插解耦（Contract / Mock UI / 无界面引擎 / 合拢）。自用极简模式（免混淆/免发布配置）。
- **项目契约**：本文件不重复项目红线，一切以根目录 `AGENTS.md` 为准（Android 13~17 兼容红线、sing-box 1.14+ 六大语法红线、架构分层、自检门禁）。

## 2. 页面与五维动效强制基线
- **布局**：三级信息划分（一级核心下半部 1/3 拇指区，二级中区胶囊，三级全屏展开页/BottomSheet）；`enableEdgeToEdge()`；8dp 网格；跳动数字 `Monospace`。
- **色彩**：深色深空灰 `#111318`（禁纯黑），浅色冷白 `#F6FAFB`；禁生硬 `Divider`，用 `surfaceContainerLow/Default/High` 色温差分层。
- **五维动效**：形态融合按钮；`SharedTransitionLayout` 容器变形；`spring()` 物理曲线与异常抖动；25ms 错峰入场 + Shimmer 骨架屏；`HapticFeedbackConstants` 帧同步震动。过渡 200~350ms。

## 3. 项目专属约束与自更新演进日志
- `v1.0` (2026-10-24)：初始化 AI 阵地大本营（阶段 0 环境基建完成）。分支 `feature/v1-enhancements`。
- `v1.1` (2026-10-24)：补记真机在线状态（BKQ-AN10 / API 37）；新增 ECH 导入链路审查修复（FEAT-03，见 `archive/FEAT-03_ech-pipeline-review-fix.md`）。
