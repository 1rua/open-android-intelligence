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

## SSE 持续前台的最终修复复核

在 `2a2d0f02` 及上述三个测试提交组成的 `f246cf37` 上，Core 通过实际 SSE 响应头声明原有一秒心跳节奏，Android 前台按该节奏检测静默。使用同一个真实半开方法、不发显式健康信号，SSE 从此前十秒超时变为 `OK (1 test)`，实际恢复 4022 毫秒；同 APK 的 WS 周期恢复复核为 9210 毫秒。两项均保持三事件顺序和唯一交付、登录 1、订阅 2、Core 重启 0。证据为 `sse-half-open-periodic-final.txt`、`ws-half-open-periodic-final.txt` 和相应 `result-*.log`、脱敏 `status-*.json`。

## 真实 Application 与 Activity 生命周期的修复前反馈

新增仪器方法 `ApplicationConnectionLifecycleInstrumentedTest.destroyingTheActivityKeepsBackgroundDeliveryAndRelaunchRepairsAHalfOpenConnection`，直接调用真实 `Application.gatewayRuntime.login()`，通过 `ActivityScenario` 启动和彻底关闭唯一 `MainActivity`，等待真实 `ProcessLifecycleOwner` 进入后台。没有手工调用 Runtime 生命周期方法。

在尚未加入 Runtime 层修复的 `f246cf37` 上实际观测：Activity 已销毁后后台等待十秒仍 LIVE，Core 事件通过现有通道推进了公开 `AndroidEventCursorStore` 的持久游标，订阅保持一次。同一旧 socket 半开后重新 launch Activity，最终仍收到补发，同一 Application Runtime 保持、订阅变成两次，但从 launch 前到实际游标恢复耗时 **10398 毫秒，未达到十秒目标**。

初次方法只在 `ActivityScenario.launch()` 返回后设置十秒等待，因漏计 Activity 启动时间而显示 `OK (1 test)`；这不是十秒达标证据。现已补充从 launch 前计时的总耗时硬断言，旧样本按该标准为失败。日志显示恢复由 WS 关闭 1006 后重试触发，尚不是 Runtime 主动健康验证。原始证据 `application-lifecycle-baseline.txt`、`result-application-lifecycle-baseline.log`、`lifecycle-connection-events.txt` 与脱敏 `status-application-lifecycle-baseline.json`；后续必须在 Runtime 修复提交上用同一方法复核，不放宽预算。

该方法使用隔离 Core 的私有测试控制返回值与公开账号/游标 Store 核对事件，不访问 Runtime 私有字段。测试控制值不写日志，状态证据移除原始事件游标；日志只保留耗时、布尔结果和计数。

## 真实 Application 与 Activity 生命周期的修复后复核

把相同严格计时方法线性更新到包含 Runtime 恢复修复的 `2047b65e`，仪器测试提交为 `b731e154`，再次使用全新隔离 Core。结果 `OK (1 test)`，总耗时 18.852 秒：

- 真实 `ActivityScenario.close()` 销毁唯一 Activity 后，ProcessLifecycleOwner 确实进入后台；Application Runtime 保持相同实例。
- 后台等待至少十秒，真实 Core 事件已由原有订阅接收并在 `AndroidEventCursorStore` 持久提交，健康期间订阅次数保持一次。
- 旧事件 socket 由同一用户态代理保持开放并双向丢弃后，再次 launch 真正的 MainActivity；不调用 Runtime 生命周期方法、不切换会话，**从 Activity launch 前到原离线游标实际恢复为 4043 毫秒**，通过十秒总预算。
- 恢复后新事件继续到达；账号登录 1 次、正式订阅总数 2、Core 重启 0，恢复瞬间正式活连接 1。

修复前 10398 毫秒与修复后 4043 毫秒各为一个实际样本，不能推断所有设备或系统调度均等价。该项证明 Activity 销毁与重新进入前台的实际调用链，仍不代表实体手机、系统强停、Doze、2 小时/6 小时息屏或真实电量验收。

证据为忽略目录 `.toolchains/background-connection-e2e/` 的 `build-application-lifecycle-fixed.log`、`application-lifecycle-fixed.txt`、`result-application-lifecycle-fixed.log`、脱敏 `status-application-lifecycle-fixed.json`。夹具已正常停止，两个 adb reverse 映射已撤销；模拟器继续由主任务持有。

## 30 分钟息屏后台长测：未完成

测试参数化提交 `b1303d4e` 增加 `oaiBackgroundDurationMs`（默认十秒）与分段事件检查；正式启动前先执行十秒短样本，三个分段游标均提交，前台恢复 4154 毫秒，`OK (1 test)`。本项业务基线为 `2047b65e`，不包括随后新增的生产修复。

随后以 `oaiBackgroundDurationMs=1800000`、`oaiWaitForScreenWake=true` 后台运行同一真实 Application/Activity 仪器方法。收到真实后台阶段标记后，外部 ADB 对 `emulator-5562` 发 `KEYCODE_SLEEP`（223）。稳定采样显示 `mWakefulness=Asleep`、`mScreenOn=false`、`mNetworkConnected=true`、深浅 deviceidle 均为 ACTIVE，测试 PID 4929 存活；未强制修改 Doze、后台许可、WakeLock 或省电白名单。模拟器为 AC 供电、100% 电量，只能作为测试环境条件，不能推断真实手机功耗。

已确认的运行证据如下：

- 前十二个每分钟分段事件均通过原订阅接收并持久提交，最后成功标记为 checkpoint 12、elapsed 720531 毫秒；当时登录 1、订阅 1、活连接 1。
- 后台约 12 分 48 秒出现 Runtime `NETWORK_CHANGED`，正式连接所有者替换为 2 并到达 LIVE；约 13 分 16 秒又出现 `NETWORK_CHANGED`、所有者 3 开始 CONNECTING。这些日志只证明系统向应用报告了路由变化，不能据此认定变化由谁触发或由什么系统机制造成。
- 仪器测试于总时长 788.13 秒结束，错误是测试控制器 `checkpoint` HTTP 请求的 `getResponseCode()` 读取超时。Core 已追加第十三个测试事件，但在保留现场的布尔检查中，该事件尚未进入此测试 profile 的 Android 持久游标；不能简单视为测试预算过短的假失败。
- 宿主到测试控制器的三秒只读状态查询正常，夹具日志没有报错，Core 重启次数为 0。故障后采样为屏幕 Awake、原仪器进程已结束、存在其他 App 进程；这是故障后的状态，不能替代故障瞬间的系统约束证据。

**三十分钟没有完成，不能宣称息屏三十分钟通过。** 当前证据不足以归因 Android 电源管理、单纯测试控制预算、宿主负载或其他设备活动；也未获得此轮长测结束后的前台恢复断言。两小时、六小时息屏和真实手机电量均未验证。

保留证据位于 `.toolchains/background-connection-e2e/`：`application-screen-30m.txt`、`checkpoints-screen-30m.log`、`system-screen-30m.jsonl`、`system-control-timeout.json`、`connection-failure-screen-30m.log`、`cursor-checkpoint13-boolean.json`、脱敏 `status-screen-30m.json`。只对本任务夹具发送正常停止并撤销本任务 34491/50181 两个映射；其他映射与未知进程、AVD 均不操作。
