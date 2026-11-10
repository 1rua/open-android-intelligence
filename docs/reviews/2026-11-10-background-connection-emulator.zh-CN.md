# Android/Core 隔离连接验收入口

关联 issue #130、PR #131。测试基线 `4cbe21b8324e7dc2b5bee44bce0174213c627980`；最终集成版本仍需重新执行。本文件描述测试边界，不表示所有验收项目已经通过。

## 实际覆盖边界

`BackgroundConnectionRecoveryInstrumentedTest` 在真实 Android 运行时使用生产 `GatewayAuthClient`、设备签名与 `GatewayHttpClient.events()`。先收到在线事件，再由独立回环夹具停止旧 Core HTTP 服务、在原事件库追加离线事件、重建 Core 对象并恢复同一端口。Android 全程不调用会话打开、会话切换、发送消息或刷新接口。

断言包括：十秒内自动收到停机事件；前后共三项事件各出现一次并保持顺序；账号登录一次；服务端观察到实际事件订阅重新建立。默认验证 WebSocket，`oaiEventTransport=sse` 可以验证现有 SSE 回落接口，每次执行需要全新夹具。

本项只验证 Android 真实传输与 Core 持久补发；不等同于 Application/Activity 生命周期、真实网络切换、Doze、长时息屏或实体手机电量验收。夹具的 Core 重建发生在同一独立 Python 进程内，OS 进程级 kill/restart 另由既有 `test_communication_process_recovery.py` 验证。

## 隔离与运行方式

夹具 `oai-core/tests/background_connection_fixture.py` 只从测试代码建立账号 `connection-e2e`，使用源码中的测试专用密码；不读取生产配置、环境凭据、现有账号或共享 Core。状态目录必须尚不存在。Core 和测试控制器分别只监听宿主 `127.0.0.1`；后者仅供本测试协调故障，不是生产 Core 管理端点。日志不记录令牌、签名、原始游标、正文或能力结果。

从仓库根目录启动，长时间运行须由工具后台会话托管，日志放在忽略目录：

```bash
python -u oai-core/tests/background_connection_fixture.py \
  --state-dir .toolchains/background-connection-e2e/本次唯一目录
```

读取首行或状态目录的 `ready.json` 得到两个端口。仅在本任务新建的专用模拟器安装 `app-full-debug.apk` 和 `app-full-debug-androidTest.apk`，随后显式指定其序列号：

```bash
adb -s emulator-5562 reverse tcp:<corePort> tcp:<corePort>
adb -s emulator-5562 reverse tcp:<controlPort> tcp:<controlPort>
adb -s emulator-5562 shell am instrument -w -r \
  -e class 'com.openandroidintelligence.mobile.BackgroundConnectionRecoveryInstrumentedTest#aCoreRestartAutomaticallyReplaysEventsWithoutOpeningAConversation' \
  -e oaiCoreUrl http://127.0.0.1:<corePort> \
  -e oaiControlUrl http://127.0.0.1:<controlPort> \
  -e oaiEventTransport ws \
  com.openandroidintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

用 `adb -s emulator-5562 logcat -d -v brief -s OaiConnectionE2E:I '*:S'` 只提取本测试结果，记录 `recoveryMillis`、事件数量和结论；不能用界面“已连接”替代真实事件到达证据。每个仪器方法和参数组合使用一个全新夹具，不能在同一夹具连续执行整个测试类。完成后向测试控制器发送 `POST /stop`，撤销其两个 adb reverse 映射；测试账号及密钥留在独立状态目录供复核，不执行永久删除。Android 测试密钥也仅位于独立安装标识的 `connection-e2e/` 子目录，不影响既有账号。

## 已执行证据

- 夹具宿主烟测：同端口、同认证会话、停机事件持久补发通过；实际登录次数 1、订阅次数 2。它主动打开补发流，只证明夹具具备可用反馈边界，不证明 Android 自动重连。
- 仪器测试 APK：`assembleFullDebug` 与 `assembleFullDebugAndroidTest` 在复用 JDK 17、SDK 与 Gradle 缓存的条件下通过，`BUILD SUCCESSFUL in 31s`，484 项任务中 241 项来自缓存。
- Android 模拟器：本任务独立 `emulator-5562`，Android 15 / API 35，KVM，`boot_completed=1`，默认 Wi-Fi 为 VALIDATED，深浅 Doze 均为 ACTIVE。未使用真实手机。

| 实际传输 | 仪器测试 | 恢复耗时 | 事件、身份与订阅 |
| --- | --- | --- | --- |
| WebSocket | `OK (1 test)`，1.094 秒 | 848 毫秒 | 三事件按序各一次，重复 0；登录 1，订阅 2，Core 重建 1 |
| SSE | `OK (1 test)`，2.194 秒 | 1928 毫秒 | 三事件按序各一次，重复 0；登录 1，订阅 2，Core 重建 1 |

恢复计时采用 Android `SystemClock.elapsedRealtime()`，从向隔离控制器发起停机前开始，到停机事件实际到达；含夹具 0.8 秒停机，未作跨机器时间相减。每项只执行一次样本，不能据此保证所有网络、机型和系统状态下均小于十秒。

脱敏原始证据保存在根工作区忽略目录 `.toolchains/background-connection-e2e/` 的 `instrumentation-ws.txt`、`instrumentation-sse.txt`、`result-ws.log`、`result-sse.log`、`status-ws.json`、`status-sse.json`。两个基线夹具均已停止，对应 adb reverse 映射已撤销；账号/密钥及日志保留，专用模拟器继续用于后续验收。

## SSE 真实半开纵向验证

基于生产连接健康接口提交 `301d5bce`，测试夹具加入用户态 TCP 代理。代理只按 Core 实际收到的事件请求标记连接，不解析或记录认证头；对选中的旧连接双向丢弃字节，保持两端 socket 开放，新连接正常转发。Core 全程运行，事件仍写入原持久库。

仪器方法初次执行时名为 `aHalfOpenSseConnectionIsReplacedAfterAnExplicitForegroundHealthCheck`，后续共用代理扩展 WS 参数后改名为 `aHalfOpenConnectionRecoversWithoutChangingTheEventOwner`。它首先断言注入时唯一事件 socket 仍表面 LIVE、`closedByFixture=0`，再触发 `requestEventHealthCheck("foreground")`。恢复后断言同一事件流所有者仍在、三事件各一次且顺序正确、总登录 1、总订阅 2、当时正式活连接 1，且代理确实丢弃过旧通道数据。

| 对照 | 结果 | 证据含义 |
| --- | --- | --- |
| 相同 APK，参数 `oaiSkipHealthCheck=true` | 10.279 秒结束，`Timed out waiting for 10000 ms`；登录 1、订阅 1、Core 重启 0、旧通道丢弃 316 字节 | 证明真实半开注入能捕捉“表面在线却无法交付”，不能把此对照冒充旧源码 SHA 的测试结果 |
| 全新夹具，启用显式公开健康检查 | `OK (1 test)`，4.09 秒结束；实际恢复 3839 毫秒；三事件按序各一次，重复 0、登录 1、订阅 2、Core 重启 0 | 在不切换会话、不重启 Core 条件下替换失效 SSE，按原游标补发 |

证据为忽略目录下 `half-open-negative.txt`、`half-open-positive.txt`、`result-half-open.log`、`status-half-open-negative.json` 和 `status-half-open-positive.json`。夹具状态在仪器测试清理后读取，此时 `liveEventConnections=0` 属于测试主动取消收集的结果；恢复瞬间活连接为 1 已由仪器断言检查。

## WS 真实半开与前台主动心跳

再加入生产 WS 主动心跳提交 `56f2b302`，保持同一个 TCP 代理和半开仪器方法，只通过参数 `oaiEventTransport=ws` 验证。新旧两端 socket 均未由夹具主动关闭、Core 重启为 0，三事件均按原游标顺序各一次；登录 1、正式订阅总数 2、恢复时唯一正式活连接 1，事件流所有者身份保持。

| 恢复入口 | 实测结果 | 实际恢复时间 |
| --- | --- | --- |
| 持续前台周期心跳，`oaiSkipHealthCheck=true`，无显式前台唤醒 | `OK (1 test)`，9.699 秒结束 | 9196 毫秒 |
| 显式 `requestEventHealthCheck("foreground")` | `OK (1 test)`，4.102 秒结束 | 3871 毫秒 |

前者验证前台保持期间独立运行的主动 Ping，而非等待用户再次进入前台；后者验证明确恢复信号。周期心跳样本已接近十秒目标，单次结果只说明受控环境下通过，不能据此承诺所有机型、网络、调度状态均达标。脱敏证据为 `ws-half-open-periodic.txt`、`ws-half-open-foreground.txt`、`result-ws-half-open-periodic.log`、`result-ws-half-open-foreground.log` 及相应 `status-*.json`。

执行中出现一次测试环境中断：原模拟器宿主 PID 60720 已退出、5562/5563 端口均关闭，ADB 无设备；日志没有产品错误。按原 AVD 保留数据重新启动后继续验证。这是模拟器进程退出，未作为 Android 客户端连接失败或恢复证据。恢复后的模拟器由持续工具会话托管，直到主任务完成后再正常停止。两个 WS 半开夹具与对应端口映射均已清理。
