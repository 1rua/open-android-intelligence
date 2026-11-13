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

**本轮三十分钟没有完成，不能据此宣称息屏三十分钟通过。** 当前证据不足以归因 Android 电源管理、单纯测试控制预算、宿主负载或其他设备活动；也未获得此轮长测结束后的前台恢复断言。用户随后明确确认没有其他任务操作原设备，因此非本任务映射与故障后进程变化的来源仍未闭合，不能归咎外部干扰。后续私有环境的合法恢复样本也不能替代本轮控制超时的根因。两小时、六小时息屏和真实手机电量均未验证。

保留证据位于 `.toolchains/background-connection-e2e/`：`application-screen-30m.txt`、`checkpoints-screen-30m.log`、`system-screen-30m.jsonl`、`system-control-timeout.json`、`connection-failure-screen-30m.log`、`cursor-checkpoint13-boolean.json`、脱敏 `status-screen-30m.json`。只对本任务夹具发送正常停止并撤销本任务 34491/50181 两个映射；其他映射与未知进程、AVD 均不操作。

## 后续设备隔离环境

为避免默认 ADB 服务上的设备被并行任务共用，建立全新 AVD `background-connection-private-api35`，数据目录为根工作区忽略的 `.toolchains/background-connection-private-avd/`，复用已有 Android 15 / API 35 镜像，不移动或复用正在运行的 AVD 数据。配置 2 GiB RAM、2 CPU、KVM、无窗口、无快照；不下载大型依赖。

本机 Emulator 的 `-help-port` 只支持标准端口 5554～5584，因此采用其 `-help-ports` 明确支持的特殊端口对 `-ports 5680,5681`，并以 `ANDROID_ADB_SERVER_PORT=5039` 启动。私有 ADB 服务使用 `adb -P 5039 start-server`，随后显式 `adb -P 5039 connect 127.0.0.1:5681`。

2026 年 11 月 10 日 23:56:45（上海时间）实测：私有设备 `boot_completed=1`，Android 15 / API 35，两个网络具备 VALIDATED，screenOn 为 true，深浅 idle 均为 ACTIVE。`adb -P 5037 devices -l` 没有出现 5680/5681 新设备，私有服务能显式连接 `127.0.0.1:5681`。私有服务也会自动扫描标准端口的其他设备，因此所有后续命令必须明确使用 `-P 5039 -s 127.0.0.1:5681`，不能依靠设备列表唯一性。环境记录为 `.toolchains/background-connection-private-avd/environment.json`；此时尚未安装测试 APK。

所有 instrumentation 后台测试均由活动仪器进程驱动，其系统优先级与普通用户将 App 留在后台不同。它可以验证生命周期调用链、真实 socket、持久游标和恢复逻辑，但不能替代无仪器进程的长期存活、进程被杀后的恢复、实体手机省电策略或真实耗电测试。

## 私有环境长测的断言修正

私有设备首轮采用业务 `ee4f0e83`，在约八分钟时因测试要求“总订阅永远等于一次”而失败。已确认 PID 5321 在后台第 431 秒收到 `NETWORK_CHANGED`，generation 2 在约 71 毫秒后进入 LIVE；第八个分段事件已持久提交（单独布尔核对 true），登录仍为 1。屏幕保持 Asleep、系统允许联网，夹具没有主动关闭 socket。这是后台路由变化后自动恢复的正例，不能把合法重连当作功能失败。

测试提交 `3200868b` 因此仅调整长时断言：默认十秒短窗口仍严格要求销毁 UI 不重建健康订阅；长时运行允许有实际 `NETWORK_CHANGED` 或 `connection.reconnect_started` 诊断的订阅替换，每段继续要求唯一活连接、登录一次、游标按分段持续提交。故障注入前记录当前订阅基线，前台恢复只允许新增一次正式订阅。修正后默认短样本恢复 4023 毫秒、`OK (1 test)`，再使用新隔离目录完整重跑，未把前八分钟合并进新样本。

## 完整三十分钟后台投递段

固定业务 APK 为 `ee4f0e837745bef218275c7ef621953c49ef706d`，测试为 `3200868b`。2026 年 11 月 11 日 00:31:45（上海时间）在私有设备开始息屏，仪器 PID 5914，唯一 Activity 已销毁。第 1～30 个分钟分段事件全部真实接收并持久提交，末次 elapsed 为 **1800035 毫秒**，逐项解析确认 checkpoint 序列精确为 1～30、所有 `cursorCommitted=true`。

后台第十五、三十分钟总订阅分别从 1 增加到 2、3，对应日志均为 WS 关闭码 4001 的正常会话到期与既有凭据续签；续签后自动恢复原游标，登录没有重复，分段检查时始终只有一个正式活连接。采样屏幕 Asleep、`mScreenOn=false`、系统深浅 idle ACTIVE、联网允许，进程一直可执行。故可独立确认：**在本受控 instrumentation/模拟器条件下，完整三十分钟后台事件投递与自然凭据续签恢复通过。**

整条仪器方法仍显示失败：后台 1800043 毫秒后已产生 `foreground_ready`，随后代理工具服务发生长时间 503 空档，未在三十秒内发送外部唤醒键，最终总时长 1833.664 秒在“等待外部屏幕点亮”处超时。不是后台投递断言失败，也没有证据表明测试进程因系统回收而提前停止；**此轮前台终段未完成，不能把整条方法写为 OK**。后来单独 Doze/前台短样本验证了该恢复路径，不能倒填此轮结果。

证据位于 `.toolchains/background-connection-private-e2e/`：`run-30m2.json`、`screen-30m2.txt`、`checkpoints-30m2-final.log`、`connection-30m2-final.log`、`summary-30m2.json`、`system-30m2.jsonl`、脱敏 `status-30m2-final.json`。夹具及其两条私有映射已正常停止/撤销。该结果不代表普通无仪器后台存活、强停恢复、真实功耗、两小时或六小时验收。

## 真实 Android 原生会话独立半开

`NativeConnectionRecoveryInstrumentedTest` 通过真实签名 Core 接口发现 Hermes、读取原会话，再由公开 `CoreAgentConversationRepository.activeConversationChanged()` 启动 Native 同步。使用生产 `NativeConversationMirror`、Android Keystore、本地落盘、原事件游标与 ACK；同时保持单独 Platform WS 健康。测试夹具复用真实 Hermes HTTP 桥、SessionBackend、NativeEventSync 与 ACK 库，只把受控宿主任务和消息来源作为测试输入，不使用手写伪 Adapter。

第一次仪器在故障注入前正确拒绝了测试宿主“追加事件但 history_reader 仍恒空”的不一致事实，报 `NATIVE_HISTORY_BEHIND`；没有修改生产覆盖检查或伪造 ACK。测试宿主修正为先提交可查询消息历史，再追加相同 messageId/revision 的真实事件后，同一方法通过。

APK 业务含关闭保护 `66c85da7`，仪器源码 `4597d8f0`、夹具一致修正 `b0f7a766`。实际结果 `OK (1 test)`、9.626 秒：仅 Native/events 旧 HTTP 连接由代理保持开放并丢弃流量，Platform 正式订阅一直为 1 且 LIVE；不调用 `connectionRestored()`、不切换会话，持续前台独立预算在 **8409 毫秒**恢复新消息并完成镜像保存和 ACK。三消息按序各一次、重复 0、pending ACK 为空、`POST /messages` 次数为 0。Core 观察 Native read 5、ACK 4，代理实际丢弃 884 字节。

证据为 `.toolchains/background-connection-private-e2e/` 的 `native-android2.txt`、`result-native.log`、`status-native.json`；宿主真实半开烟测 `native-host-half-open.json` 只作夹具边界证据。仪器包编译及 `noVpnSurfaceCheck` 均通过，控制 HTTP helper 复用已有精确登记测试文件，没有扩大全目录网络许可。夹具与私有端口映射已正常清理。

## 强制 Doze 状态与解除后恢复

在私有设备使用同一真实 Application/Activity 方法、二十秒后台阶段，按标准测试命令 `dumpsys battery unplug`、息屏键 223、`dumpsys deviceidle force-idle`，实测返回 deep IDLE、light OVERRIDE。期间三个分段事件仍成功提交，因此活动 instrumentation 可能被系统豁免，不能声称已经证明应用经受真实网络冻结。

在 `foreground_ready` 后执行 `deviceidle unforce`、`battery reset`、唤醒键 224，真实 Activity 自动检查并恢复半开连接；从 launch 前到原离线游标实际恢复 **2837 毫秒**，`OK (1 test)`，总 48.511 秒，Application Runtime 身份保持、总订阅 2。没有切换会话、手动 Runtime 生命周期调用或修改产品权限。

收尾实测 `mForceIdle=false`、深浅 idle ACTIVE、屏幕 Awake、AC 供电恢复，夹具与专用映射已停止。证据 `doze-lifecycle.txt`、`result-doze.log`、`doze-state.json`、`doze-restored.json`、脱敏 `status-doze.json`。这验证了强制状态/解除后的调用链，不替代真实机型对 Doze 网络限制及恢复窗口的验收。

## 最终业务提交的同 APK 短复核与资源收尾

在最终业务 `0e391fc3`、精确 checkout `963e94904c04466307d3219265db81f4c98d52f8` 上重新一次增量构建完整调试 APK 和仪器测试 APK，连同 `noVpnSurfaceCheck` 通过（`BUILD SUCCESSFUL in 2m 22s`，485 项任务中 460 项为 up-to-date）。仅在私有 ADB 设备运行两个全新隔离夹具，结果与此前旧 SHA 的样本分开保存：

| 最终同 APK 场景 | 结果 | 实际恢复耗时 |
| --- | --- | --- |
| Native-only 半开，Platform 保持健康，原加密镜像保存后 ACK，不重新提交输入 | `OK (1 test)`，总 10.748 秒；三消息有序各一次、无重复、pending ACK 为空、Platform 订阅 1、prompt POST 0 | **8578 毫秒** |
| 默认十秒真实 Activity 销毁/后台分段投递后重新进入前台，健康短窗口订阅严格保持一次 | `OK (1 test)`，总 22.236 秒；同 Application Runtime、后台游标保存、最终总订阅 2 | **4384 毫秒** |

恢复耗时均为实际半开注入到可用事实或 Activity launch 前到原游标恢复的单调时间，不把整个测试包含初始化与背景等待的总时长当作重连耗时。证据为 `.toolchains/background-connection-private-e2e/` 的 `build-exact-final.log`、`native-exact-final.txt`、`lifecycle-exact-final.txt`、`result-native-exact-final.log`、`result-lifecycle-exact-final.log` 和相应脱敏状态 JSON。此次没有重复三十分钟或 Doze 测试，先前证据继续按各自业务 SHA 标注。

2026 年 11 月 11 日 11:04:56（上海时间）完成正常收尾：所有本任务 Core/Adapter 夹具已停止，专用 reverse 映射撤销；私有模拟器通过官方 `adb -P 5039 -s emulator-5680 emu kill` 正常关闭，私有 ADB 5039 服务停止，5680/5681 与测试端口均关闭。早先本任务 `background-connection-api35` 的 5562 已在核对无活动仪器、前台为系统桌面后，经明确授权正常 `emu kill`；其映射随设备退出自然撤销，没有操作其他设备或默认 ADB 5037 服务。AVD 数据、日志、账号测试目录和未合并 worktree 均保留，未执行永久删除。
