# DSH 空闲连接状态修复计划

关联问题：#146。基线：`dev@24172841f9a564c53416f7d066f3450d4feb2bfe`。

## 问题与目标

Android 已连接 Core，且 Core 全局实时通道为 LIVE 时，DSH 单次回复完成后会把自身请求观察状态恢复为 IDLE。工作台当前在全局 LIVE 时回退读取原生状态，因此顶部误显示“实时通道未建立”。

本次只调整顶部展示状态的合成，沿用已有文案。Core 的连接、重连和失败事实，以及原生会话真正的连接、重连和失败，仍需正常显示；保留 Hermes 长期事件流、离线镜像和发送、设备授权门禁的既有行为。

## 变更范围

- Android 工作台连接展示状态的合成入口。
- 直接覆盖该入口的聚焦单元测试。
- 本任务计划与验证证据。

不涉及 DSH 桥处理器、Core 配置、原生历史、账户或设备数据，不在本任务构建、安装 APK 或部署服务。

## 验收与验证

- Core 全局 LIVE、原生 IDLE 时，顶部显示现有“已连接”状态。
- DSH 运行、正常完成后和切换空闲会话时，显示事实准确。
- 原生 CONNECTING、RECONNECTING、FAILED 不被全局 LIVE 覆盖。
- Core 连接、重连或失败时，不被旧原生 LIVE 覆盖。
- Hermes 长期事件流和离线镜像维持原行为。
- 先增加可复现缺陷的测试并确认失败，再修复并执行受影响的 Android 测试类。
- 独立审查及最终 APK 集成、隔离模拟器验收由主任务统一安排。

## 实施结果

`MainActivity.workbenchConnectionHealth` 现在明确向工作台提供 Core 全局 LIVE 的连接事实。`WorkbenchScreen` 的抽屉、普通顶栏和大字号副标题共用一个展示合成结果：Core 为 LIVE 且原生为 IDLE 时显示 LIVE，原生其余状态继续可见；Core 正在连接、重连或失败时继续沿用原有优先级；未提供 Core 状态的其他入口继续读取原生状态。

只调整展示值，没有写回 `controller.state.streamHealth`，不会把按请求的原生流变成持续流，也不会改变其历史恢复或重试行为。现有状态枚举没有独立 STALLED 值，既有重连、失败及具体失败原因的通路保持不变。

## 红绿验证

本地使用 JDK 17、主仓共享 SDK、原 Gradle 缓存和已有 Robolectric 离线依赖。独立工作树的 `apps/android/local.properties` 使用相对 SDK 路径，测试辅助配置位于已忽略的 `.gradle`，均未提交。

| 测试类 | 修复前 | 修复后 |
| --- | --- | --- |
| `WorkbenchConnectionHealthTest` | 3 项，新增连接事实断言失败：期望 LIVE、实际 null | 3 项通过 |
| `WorkbenchLayoutRegressionTest` | 16 项，新增普通字号与大字号两项失败，旧 14 项通过 | 16 项通过 |
| `AgentDiscoveryConnectionRecoveryTest` | 已有 Hermes 持续流回归 | 1 项通过 |

聚焦绿阶段共 20 项通过。App 任务耗时 4 秒、界面任务耗时 9 秒。两个任务分开运行，避免 Gradle 将 `--tests` 仅应用到最后一个任务：

```bash
./gradlew :app:testFullDebugUnitTest \
  --tests com.openandroidintelligence.mobile.WorkbenchConnectionHealthTest \
  --tests com.openandroidintelligence.mobile.AgentDiscoveryConnectionRecoveryTest \
  -I .gradle/connection-status-tests.init.gradle

./gradlew :conversation-ui:testDebugUnitTest \
  --tests com.openandroidintelligence.conversation.workbench.WorkbenchLayoutRegressionTest \
  -I .gradle/connection-status-tests.init.gradle
```

界面测试直接经过真实控制器与 `WorkbenchScreen`，覆盖原生 IDLE → CONNECTING → LIVE → RECONNECTING → FAILED → LIVE → IDLE、Core 连接/重连/失败优先级、无 Core 状态时的旧回退、普通/大字号副标题及抽屉。额外断言确认展示“已连接”时，原生流真值仍为 IDLE。`git diff --check` 通过。

首次 CI 的 Fast Gate 检出新 App 测试类尚未登记 PR 回归清单；已将 `WorkbenchConnectionHealthTest` 加入 `apps/android/ci/app-regression.txt` 的 core 分节，确保 PR 门禁实际执行本回归。补充执行 `python3 -m pytest -q tools/test_ci_planning.py`，78 项通过；Android 源码没有因此再次变化，无需重复已通过的 20 项 Android 回归。

执行中曾因组合 Gradle 命令的过滤范围意外扩大 App 测试，发现后立即中断；该次运行不作为回归结果。上述 20 项来自随后分开执行、明确过滤且任务成功的结果。

## 证据边界

本分支只完成源码与聚焦单元回归。没有在本任务运行全量编译、构建或安装 APK、修改服务或操作用户数据。独立审查、整合后的 Fast/PR 门禁及隔离模拟器验收仍由主任务完成，不能用本单测结果宣称完整端到端验收通过。
