# 设备通知请求长时间等待：诊断、修复与验证

## 证据范围

- Android 初始复现基线：`f6d4349e9a3600caef12d8a80fdddacbf3bd15a6`；交付前同步远程 `main` 至 `552fb2c8655126d564b78bf400f55f033ab97267`，保留同期发布与应用更新改动。
- Hermes 基线：`1rua/hermes-android-gateway` 的 `f09951f1de80c294619d76001f9a7cf5eb8ebd36`。
- 用户确认故障发生时手机亮屏且 App 在前台。截图显示工具等待十多分钟，但没有该次请求的 `requestId` 或完整链路日志。因此不能把后台挂起、通知权限缺失或某一具体网络故障认定为现场根因。
- 当前没有连接的 ADB 设备。本机旧 Hermes 安装记录与截图目标部署无法关联，不能用其版本或空请求表代替目标部署证据。

## 已复现的代码缺陷

| 环节 | 修复前的实际证据 | 修改 |
| --- | --- | --- |
| Agent 等待 | 未领取和已领取但不回传的两个测试均超过短期限；工具直接按 read 队列最长 24 小时的期限轮询 | Hermes 使用独立单调时钟，默认最多等待 60 秒；工具创建的请求最多存活 90 秒，同时遵守风险原有更短上限 |
| Android Claim | 丢回包、拒绝、挂起三个测试失败；Claim 位于执行异常捕获之外 | 将 Claim 纳入捕获及时间预算，网络错误使用相同幂等键有限重试；未取得且核对服务端收据时不得执行或伪造结果 |
| Android 恢复 | 某一条离线日志会抛错并阻断后续条目 | 逐条隔离恢复错误并记录阶段，保留原有每 30 秒恢复入口 |
| SSE | 正常 EOF 无帧会绕过失败上限；已有事件的流关闭后退避期间仍显示 LIVE | EOF 记为连接失败，进入已有有限重试，显示 RECONNECTING，耗尽后明确失败 |
| Gateway 过期恢复 | 扫描候选后可能用旧状态覆盖并发完成结果 | 在事务内重读最新状态并执行合法状态转换，终止请求时释放请求事件正文 |

结果回传失败后先保存本地结果、恢复时只补传的机制原本存在，旧实现对应保护测试已经通过；本次补充上传总时间预算、有限重试及可见日志，不能把既有机制说成完全缺失。SSE 游标原本也在处理成功后才保存，新增测试确认接收失败会保留游标并重放。

## 时间、终态与授权

Android 请求查询预算为 10 秒，Claim 与执行预算为 30 秒，并受请求剩余期限约束；结果上传总预算为 10 秒，耗尽后保存已完成结果，由恢复入口补传。所有预算是上限，不保证网络断开时能向 Gateway 成功上传。

Hermes 的 60 秒工具预算与队列保留期分离。未领取请求在超时时取消并返回 `DEVICE_OFFLINE` 或 `DEVICE_TIMEOUT`，附 `requestId` 和 `claim_wait`。已领取请求发出取消意图并返回 `outcome_unknown`、`DEVICE_TIMEOUT`、`result_wait`，保留剩余有限时间供真实结果回传。90 秒是拒绝过期 Claim 与结果的边界，数据库终态在后续请求访问或定期维护时按原状态机落盘；不能把已开始的副作用报告为“未执行”。默认 read 最大排队期限的协议语义不变，短上限仅用于这类交互工具请求。

配对代次、授权修订、能力发布、设备身份、服务器收据、前台确认和本机通知访问检查继续生效。幂等 Claim 和结果重试使用相同操作键；执行前持久化检查点；丢回包、重连和进程恢复均不得重新调用已开始的插件。

## requestId 追踪与部署核实

Gateway 日志使用 `[open_android] device_request requestId=… stage=…`，Android Logcat 使用 `OaiDeviceRequest`。仅记录请求编号、阶段、状态或受控错误码，不记录通知正文、参数、令牌及任意异常正文。

在实际 Hermes 部署目录运行配套 PR 中的只读工具：

```bash
python scripts/trace_device_request.py --database <目标账号的gateway.sqlite> --request-id <devreq编号>
```

它只输出请求状态、创建/到期/领取时间、是否存有结果、配对和授权修订是否仍匹配，以及现有审计时间线；不会解密设备内容，也不会创建或修改数据库。`awaiting_claim` 表示尚无领取证据，不能单凭它断言手机离线；`awaiting_result` 表示已有领取证据；`terminal` 表示状态已终结。需要把它与同一编号的两端日志对齐：

1. Gateway `enqueued`，Android 无 `lookup`：核对实际 APK/插件提交、SSE 连接及事件重放。
2. Android `lookup` 后无 Claim 成功：核对 HTTP、配对代次、授权修订及服务端 Claim 拒绝码。
3. Claim 成功后无 `completed`：检查能力发布、插件启用、通知监听器连接及系统通知访问许可。
4. `completed` 后无 `result_accepted`：检查上传重试与本地恢复；不得重复插件执行。
5. Gateway 已接收结果而工具仍等待：关联工具日志、实际宿主加载位置、提交版本及结果采用/确认。

## 自动化验证

本机只运行聚焦 Android 测试，完整构建与完整矩阵由 GitHub Actions 执行。物理设备验收仍需在目标部署上完成。

- SSE：修复前新增两例失败；修复后 `GatewayEventStreamTest` 24、`SseParserTest` 16、`EventStreamAuthenticationTest` 1，共 41 例通过。
- 设备执行器：修复前 6 例中 4 例失败，分别为 Claim 回包丢失、Claim 挂起、Claim 拒绝的未捕获异常、单条离线日志阻断后续恢复；结果补传与授权栅栏两例原本通过。首轮修复后 6 例全部通过。
- 最终 App 聚焦组：**163 例通过，失败、错误与跳过均为 0**。Gradle 完整结束并输出 `BUILD SUCCESSFUL in 2m 17s`；此轮包含最新的 8 项执行器恢复测试，测试任务实际执行而非直接使用测试结果缓存。
- 协议：共享 Hermes 向量 70/70、状态机/Schema 注册/请求签名 110 例通过；两仓契约内容比对通过。
- 契约门禁测试：8 例通过；修正了测试仍引用旧常量名、混入 pytest 命令行及未清除环境变量的问题，门禁生产行为未改。
- Hermes 超时、撤权、恢复、HTTP、原生宿主及只读诊断的最终矩阵记录在配套仓库报告中。

最终 App 结果按测试类核对如下：

| 测试类 | 通过数 | 主要证据 |
| --- | ---: | --- |
| `DeviceExecutionRecoveryTest` | 8 | Claim 失败/挂起/错账号收据、恢复隔离、已领取请求不重复执行、上传耗尽后原样补传、上传挂起在 10 秒预算内结束 |
| `DeviceGrantFenceTest` | 1 | 旧授权修订与 Gateway 拒绝仍然阻断执行 |
| `NotificationCapabilityHttpTest` | 1 | 实际签名 HTTP、真实签名通知包与通知监听器组合；服务端接受后丢失 Claim 和结果回包仍可恢复；后台已授权 read 成功；权限撤销与重连恢复 |
| `ProductionPluginHostTest` | 20 | 插件宿主及授权边界；后台 write 拒绝、前台确认、执行前撤权、结果幂等重传 |
| `PlatformSnapshotRecoveryTest` | 2 | 失效事件游标的快照恢复与失败时保留基线 |
| `GatewayNetworkMonitorTest` | 3 | 前台网络监听注册与网络切换唤醒 |
| `AgentSessionKeepAliveTest` | 72 | API 31–36 的后台授权、会话与保活状态分支 |
| `AgentSessionForegroundServiceTest` | 56 | API 31–36 的前台服务启动、通知与系统限制分支 |

以上后台证据来自 JVM/Robolectric，并未验证目标 Samsung 设备的省电策略、进程被杀或物理网络行为。用户现场亮屏且 App 前台这一事实不因此改变。

本次本地工具链使用 JDK 17 启动 Gradle 8.12，单独把测试进程切换到已有 JDK 25，以满足 Robolectric API 36 要求 Java 21 以上的条件。没有修改项目的生产编译版本。测试启动脚本内容为：

```groovy
allprojects {
    tasks.withType(Test).configureEach {
        executable = new File(System.getenv('OAI_TEST_JAVA_HOME'), 'bin/java').path
    }
}
```

从 `apps/android` 运行以下聚焦命令；`OAI_TEST_JVM_INIT` 指向保存上述内容的本机文件，`JAVA_HOME`、`OAI_TEST_JAVA_HOME`、`ANDROID_HOME`、`GRADLE_USER_HOME` 分别指定 JDK 17、JDK 25、Android SDK 与现有 Gradle 缓存，`HERMES_PLUGIN_ROOT` 指向配套修复工作树，`OPEN_ANDROID_GATEWAY_CONTRACT_ROOT` 指向本仓 `gateway-contract`。`OAI_HERMES_PYTHON` 必须指向安装完整测试依赖的虚拟环境内 `bin/python`，不得对 Python 可执行文件本身运行 `realpath`；本次成功轮使用既有 `local-history/python` 虚拟环境。

```bash
./gradlew --offline --no-daemon --max-workers=2 --console=plain \
  --init-script "$OAI_TEST_JVM_INIT" :app:testFullDebugUnitTest \
  --tests '*DeviceExecutionRecoveryTest' --tests '*DeviceGrantFenceTest' \
  --tests '*NotificationCapabilityHttpTest' --tests '*ProductionPluginHostTest' \
  --tests '*PlatformSnapshotRecoveryTest' --tests '*AgentSessionKeepAliveTest' \
  --tests '*AgentSessionForegroundServiceTest' --tests '*GatewayNetworkMonitorTest'
```

前置通知和 SMS WASM 包由本工作树源码使用 Rust 1.88.0、`wasm32-unknown-unknown` 目标聚焦构建通过。最初混用 JDK 17 运行 API 36 测试、以及把虚拟环境解释器解析成裸 Python 的两次配置失败均已在最终成功轮纠正，不能把这些失败解释为产品根因。

## 主线同步后的证据复核

同步 `552fb2c8655126d564b78bf400f55f033ab97267` 后，追加的 163 项扩展复跑在未修改的会话保活测试中长时间没有新增执行记录，已主动中断；随后 30 项直接相关用例的重复复跑在当前受管环境配置阶段耗时过长，也已中断。两次中断均不作为通过证据，没有把旧 XML 当作本轮新结果。

提交前将所有本次修改的生产源码和回归测试与同步前保存的成功测试版本逐字节比对，全部一致；同期主线只改发布、应用更新及文档，不涉及这些源码或测试。163 项成功 XML 已保存在忽略的本机证据目录，成功日志亦保留。本次提交使用前述实际通过的 163 / 41 项证据；同步后的契约门禁 8 项、70 个共享向量、工作流解析和两处 Hermes 固定提交检查另已通过。完整合并结果由 PR 的 GitHub Actions 复核。

## 配套提交、独立审查与远程验证

Hermes 配套 [PR #6](https://github.com/1rua/hermes-android-gateway/pull/6) 固定提交为 `ebe527da2426dd25877dd335786e80cf85d1cdde`，主仓两处 CI 检出均锁定此提交。独立 Standards 与 Spec 审查均在推送后进行，分别为 0 个阻断问题；审查为只读源码检查，没有把审查误报为测试重跑。

Hermes [CI 40050139586](https://github.com/1rua/hermes-android-gateway/actions/runs/40050139586) 已成功：完整独立矩阵 448 项通过，固定原生宿主矩阵 92 项通过。该结果对应上述精确提交，覆盖最后补充的截止边界用例。Android 提交后的独立审查和完整 CI 将继续记录。

## 目标设备验收入口

安装经 CI 验证的配套 APK 与 Hermes 提交后，在保留原账号和配对的情况下分别验证：前台正常读取、已授权读取在后台、网络断开及恢复、通知监听器访问关闭、Claim 拒绝、结果上传中断。每次记录 `requestId`，失败应在有限时间内返回明确代码；后台强杀和 Samsung 省电策略需要设备日志证明，JVM 测试不能代替这些证据。
