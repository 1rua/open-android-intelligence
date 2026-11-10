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
  -e class com.openandroidintelligence.mobile.BackgroundConnectionRecoveryInstrumentedTest \
  -e oaiCoreUrl http://127.0.0.1:<corePort> \
  -e oaiControlUrl http://127.0.0.1:<controlPort> \
  -e oaiEventTransport ws \
  com.openandroidintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

用 `adb -s emulator-5562 logcat -d -v brief -s OaiConnectionE2E:I '*:S'` 只提取本测试结果，记录 `recoveryMillis`、事件数量和结论；不能用界面“已连接”替代真实事件到达证据。完成后停止本任务夹具，撤销其两个 adb reverse 映射；测试账号及密钥留在独立状态目录供复核，不执行永久删除。Android 测试密钥也仅位于独立安装标识的 `connection-e2e/` 子目录，不影响既有账号。

## 已执行证据

- 夹具宿主烟测：同端口、同认证会话、停机事件持久补发通过；实际登录次数 1、订阅次数 2。它主动打开补发流，只证明夹具具备可用反馈边界，不证明 Android 自动重连。
- 仪器测试 APK：`assembleFullDebug` 与 `assembleFullDebugAndroidTest` 在复用 JDK 17、SDK 与 Gradle 缓存的条件下通过，`BUILD SUCCESSFUL in 31s`，484 项任务中 241 项来自缓存。
- Android 模拟器：本任务独立 `emulator-5562`，Android 15 / API 35，KVM，`boot_completed=1`，默认 Wi-Fi 为 VALIDATED，深浅 Doze 均为 ACTIVE。未使用真实手机。

| 实际传输 | 仪器测试 | 恢复耗时 | 事件、身份与订阅 |
| --- | --- | --- | --- |
| WebSocket | `OK (1 test)`，1.094 秒 | 848 毫秒 | 三事件按序各一次，重复 0；登录 1，订阅 2，Core 重建 1 |
| SSE | `OK (1 test)`，2.194 秒 | 1928 毫秒 | 三事件按序各一次，重复 0；登录 1，订阅 2，Core 重建 1 |

恢复计时采用 Android `SystemClock.elapsedRealtime()`，从向隔离控制器发起停机前开始，到停机事件实际到达；含夹具 0.8 秒停机，未作跨机器时间相减。每项只执行一次样本，不能据此保证所有网络、机型和系统状态下均小于十秒。

脱敏原始证据保存在根工作区忽略目录 `.toolchains/background-connection-e2e/` 的 `instrumentation-ws.txt`、`instrumentation-sse.txt`、`result-ws.log`、`result-sse.log`、`status-ws.json`、`status-sse.json`。两个夹具均已停止，对应 adb reverse 映射已撤销；账号/密钥及日志保留，专用模拟器交由主任务继续验收。
