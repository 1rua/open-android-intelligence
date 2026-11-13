# 缺少刷新凭据通知的初始化时序回归

日期：2026-11-12。关联 Issue #174、PR #176。业务基线固定为 `83fbef075a494f0d4899cc22c08b4864c9edb3cd`，本地原方法和受控红测的 HEAD 为仅新增实施计划的 `905468deae802cecb4e251fd501adaa233f0fc33`。

## 问题与结论

最终 Full run `43129391533` attempt 2 的 PlayDebug 变体在 `GatewaySessionRefreshTest.withoutARefreshCredentialThePathSaysSoInsteadOfPretending:959` 失败，1855 测试中 1 失败、94 跳过。第 959 行立即读取 `operationNotice` 并要求非空。第一轮的 ColdStart 第 197 行失败是另一条问题，本修正不宣称关闭 #173。

受控复现确认：`Connected` 发布后，连接初始化任务仍可能持有操作门禁。此时调用 `refreshSession()` 会按既有逻辑延迟到同一初始化任务结束后执行。即时通知为 `null`，但释放初始化门禁后会发布原缺凭据说明，凭据状态变为 `UNAVAILABLE`，且没有发送 HTTP 续期请求。因此，即时非空断言会把尚未完成的合法操作误判为静默丢失。

本修正只让原方法等待实际通知发布，再继续执行原来的“非空、包含刷新凭据说明、零续期请求”断言。没有修改生产源码、UI 文案、账号或设备授权逻辑、防重入策略，也没有增加任意睡眠、扩大既有十秒等待预算或重新运行远端 CI。

## 可控红环

新测试在既有安全日志 `stage=CONNECTED state=ready` 处暂停初始化。此时生产代码已设置 `ConnectionPhase.Connected`、当前工作台和会话归属，但初始化任务尚未返回。测试只通过 `GatewayLog.sink` 设置闩锁，不增加生产测试入口，不操作模拟器、系统服务或模型。

红测先捕获调用返回时的即时通知，再释放初始化闩锁；确认最终通知、状态、零 HTTP 请求和保留语义后，重放原来的即时非空断言。实际输出如下：

```text
刷新时序诊断：initialNotice=null finalNotice=本机没有为当前账号保存刷新凭据，请重新登录以获取自动登录凭据。 refreshRequests=0
java.lang.AssertionError: 没有刷新凭据时必须给出可读说明，而不是静默
```

红测只有原即时非空假设失败。释放闩锁后，精确的原缺凭据文案、`UNAVAILABLE`、零续期请求、同一工作台、当前账号、设备授权绑定、本机授权决定及设备密钥保留断言均已通过。这能稳定解释原断言为何会失败；没有捕获 CI 的实际线程调度记录，因此不声称还原了 CI 当次每一个调度点。

固定门禁的最终回归检查：门禁内刷新仍在排队且零续期 HTTP；释放后必须在原等待预算内收到精确原通知及 `UNAVAILABLE`；完成后仍零续期 HTTP，并保留上述账号、工作台、授权和密钥事实。这样可以独立捕获“延迟操作确实丢失”的回归，不能仅靠增加等待让它通过。

## 相关源码

- `apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt` 的 `deferUntilSessionEstablished`：确认同一初始化任务、HTTP 客户端与会话所有者后，等待初始化任务完成再执行原操作。
- 同文件 `refreshSession`：首先使用上述延迟入口；实际执行时，缺少刷新凭据只发布 `UNAVAILABLE` 与既有说明，不发续期 HTTP。
- 同文件 `establish`：先发布 `Connected`，再完成通知绑定、后台工作状态和平台事件流初始化。
- `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/GatewaySessionRefreshTest.kt`：原 `awaitConnected` 只观测 `Connected` 阶段，不能作为所有初始化操作已结束的信号。本修正局限于该通知断言，未全局改变其他用例的连接等待语义。

## 执行证据

原始证据位于 [2026-11-12-refresh-notice-174-evidence](2026-11-12-refresh-notice-174-evidence/)。

| 阶段 | 实际范围 | 结果 |
| --- | --- | --- |
| 未改原方法 | PlayDebug 原方法，生产和既有测试均为业务基线 | 1 通过、0 失败、0 错误、0 跳过；方法 3.421 秒，Gradle 1 分 38 秒。单次通过不排除时序失败。 |
| 固定初始化的红测 | 新方法；在捕获即时值和验证最终语义后重放原非空假设 | 1 测试、1 失败、0 错误、0 跳过；方法 3.289 秒，Gradle 52 秒。失败为同文案的即时非空断言。 |
| 修正后聚焦与相关回归 | 原方法 + 门禁回归；整个 `GatewaySessionRefreshTest` 类 | 聚焦 2 测试全绿；整类 51 测试、0 失败、0 错误、0 跳过。整类 JUnit 46.139 秒，Gradle 57 秒。 |

复跑使用既有 JDK 21、Gradle 8.12、Android SDK 与 Robolectric jar 缓存；保留 daemon。环境变量分别指向已有工具链及离线 jar，注入脚本只设置离线依赖与完整测试输出，不改变用例逻辑。核心命令从 `apps/android` 执行：

```bash
./gradlew --offline --no-configuration-cache --max-workers=2 \
  -I "$REFRESH_EVIDENCE/offline.init.gradle" \
  :app:testPlayDebugUnitTest \
  --tests com.openandroidintelligence.mobile.GatewaySessionRefreshTest \
  --console=plain
```

本轮本地聚焦与相关类结果将与远端 CI、最终 Full 和真实设备验收分别记录，不能互相替代。独立评审与最终门禁由主任务整合后安排；PR 当前仍为草稿。

## 受控红绿结果补充

修正后的 PlayDebug 聚焦同时执行原方法与门禁回归：2 测试、0 失败、0 错误、0 跳过；Gradle 1 分 19 秒。随后将原方法的等待条件收紧为必须出现包含“刷新凭据”的最终通知，再次执行同两例仍为 2 测试全绿，Gradle 1 分 18 秒。门禁回归确认初始化期间操作未提前发布结果，释放后准确发布缺凭据说明并保持零续期请求及账号、工作台、授权和密钥事实。
