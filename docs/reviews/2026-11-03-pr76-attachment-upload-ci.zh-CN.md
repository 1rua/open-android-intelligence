# PR76 附件上传 CI 监测结果

日期：2026-11-03（Asia/Shanghai）

PR：#76，目标分支 `dev`

监测 head：`1e3d6a81908e0c557461ea4734d0db9ab7b7a9a1`

worktree：`.worktrees/android-attachment-upload-20261103`

## 结论

监测脚本正常退出，PR head 与指定 SHA 一致。6 项检查通过，Android 检查失败；因此不能将本次 CI 记为全绿。未修改源码、未本地编译、未重跑任务，也未发送 PR 评论。

| 检查 | 结果 | 运行记录 |
| --- | --- | --- |
| `sign-plugin-packages` | 成功 | [job 125888657463](https://github.com/1rua/open-android-intelligence/actions/runs/41627560370/job/125888657463) |
| `apk` | 成功 | [job 125888984286](https://github.com/1rua/open-android-intelligence/actions/runs/41627560370/job/125888984286) |
| `core (3.11)` | 成功 | [job 125889186820](https://github.com/1rua/open-android-intelligence/actions/runs/41627713786/job/125889186820) |
| `core (3.12)` | 成功 | [job 125889186822](https://github.com/1rua/open-android-intelligence/actions/runs/41627713786/job/125889186822) |
| `hermes-native` | 成功 | [job 125889186777](https://github.com/1rua/open-android-intelligence/actions/runs/41627713786/job/125889186777) |
| `dsh` | 成功 | [job 125889186931](https://github.com/1rua/open-android-intelligence/actions/runs/41627713786/job/125889186931) |
| `android` | 失败 | [job 125889186453](https://github.com/1rua/open-android-intelligence/actions/runs/41627713786/job/125889186453) |

## Android 失败

失败步骤为 `Core connection, Agent sessions, platform recovery and transport regressions`，执行 `:gateway-client:testDebugUnitTest` 时 374 项测试中有 2 项失败：

1. `GatewayTransportWriteTimeoutTest.fixedLengthWriteStopsWhenPeerKeepsHeadersOpenWithoutReadingBody`：CI 得到 `IOException: Error writing request body to server`，而测试要求 `SocketTimeoutException`。断言位置：[GatewayTransportWriteTimeoutTest.kt:267](../../apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/http/GatewayTransportWriteTimeoutTest.kt#L267)，测试入口在第 134 行。
2. `GatewayTransportWriteTimeoutTest.callerCancellationDisconnectsAStalledRealSocketAndKeepsCancellationCause`：真实服务端在测试等待窗口内未观察到客户端 socket 关闭。断言位置：[GatewayTransportWriteTimeoutTest.kt:276](../../apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/http/GatewayTransportWriteTimeoutTest.kt#L276)，测试入口在第 139 行。

**最小根因定位：** 两项失败都落在请求正文写入期间的超时/取消关闭行为。相关实现通过 `withTimeout` 触发 `connection.disconnect()`，并尝试将超时转换为 `SocketTimeoutException`，位置在 [GatewayTransport.kt:201](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayTransport.kt#L201) 到第 219 行。CI 的真实 socket 测试证明当前路径未满足这两项契约：超时返回了普通写入 `IOException`，调用方取消后对端也未及时观察到连接关闭。

**尚未确认：** 现有日志不能单独判定底层原因究竟是 `HttpURLConnection.disconnect()` 与阻塞写入的竞争、运行时实现差异，还是测试对端观测时序。未进行本地复现或重跑；不要据此扩大为附件服务端或代理故障结论。`Attachment upload phases and confirmed-upload recovery` 后续步骤因前置步骤失败而跳过，本次 Android CI 未验证该专项测试。

## APK 产物与评审

Android APK workflow `41627560370` 已在同一 head 完成并成功。只读 artifact 元数据显示 `app-full-debug` 与 `signed-plugins-1` 均未过期；没有下载私人 artifact。可由主 Agent 转告 Terra：`app-full-debug` 已可用，入口为 [Android APK Artifact run 41627560370](https://github.com/1rua/open-android-intelligence/actions/runs/41627560370)。

只读检查了关联该 head 的 inline review comments 与 review summaries，未发现新评审意见；未回复或发送评论。

## 监测记录

监测 JSON 保存在忽略目录 `tmp/attachment-upload/ci-76/`，包括 `status.json` 与 `complete.json`。本报告仅记录本次指定 SHA 的结果，不引用旧 head 的通过状态。

## 新 head 监测：`39a48747de277c4b52d46e7edf0347872f3e25fd`

日期：2026-11-03。后台监测确认 PR head 与本 SHA 一致，脚本以 `CHECK_FAILURE` 退出。6 项检查成功，Android 检查仍失败：

| 检查 | 结果 | 运行记录 |
| --- | --- | --- |
| `sign-plugin-packages` | 成功 | [job 125903684444](https://github.com/1rua/open-android-intelligence/actions/runs/41632007735/job/125903684444) |
| `apk` | 成功 | [job 125903985493](https://github.com/1rua/open-android-intelligence/actions/runs/41632007735/job/125903985493) |
| `core (3.11)` | 成功 | [job 125903710618](https://github.com/1rua/open-android-intelligence/actions/runs/41632015298/job/125903710618) |
| `core (3.12)` | 成功 | [job 125903710282](https://github.com/1rua/open-android-intelligence/actions/runs/41632015298/job/125903710282) |
| `hermes-native` | 成功 | [job 125903710387](https://github.com/1rua/open-android-intelligence/actions/runs/41632015298/job/125903710387) |
| `dsh` | 成功 | [job 125903710162](https://github.com/1rua/open-android-intelligence/actions/runs/41632015298/job/125903710162) |
| `android` | 失败 | [job 125903710437](https://github.com/1rua/open-android-intelligence/actions/runs/41632015298/job/125903710437) |

### 新 head Android 失败分析

失败步骤仍为 `Core connection, Agent sessions, platform recovery and transport regressions`。本次 `:gateway-client:testDebugUnitTest` 共 374 项测试，只有 `fixedLengthWriteStopsWhenPeerKeepsHeadersOpenWithoutReadingBody` 失败。

**最小根因：** 失败不是 bounded `IOException` 的类型检查，也不是异常消息变化。CI 日志显示 `operationFailure` 与 `reportedFailures.single()` 都是 `java.io.IOException: Error writing request body to server`，但第 286 行的 `assertSame` 要求两者必须引用同一个对象，运行结果是两个不同实例。之前的限时完成、I/O 类型、正文未全部写出和单次回调断言均已通过。定位：[GatewayTransportWriteTimeoutTest.kt:286](../../apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/http/GatewayTransportWriteTimeoutTest.kt#L286)。因此当前直接失败点是测试额外要求了异常对象身份；日志没有显示生产 Kotlin/Core 文件变化。

`callerCancellationDisconnectsAStalledRealSocketAndKeepsCancellationCause` 本次通过。固定长度超时用例在第 286 行断言失败后提前退出，后面的 peer-close 断言未执行；不能把该用例的 socket-close 检查记为通过。附件上传专项步骤仍因前置步骤失败而跳过。

### 新 head APK 与交接

Android APK workflow `41632007735` 的 `head_sha` 已通过只读 API 核实为本 SHA，结论成功。`app-full-debug` 与 `signed-plugins-1` artifact 均未过期；未下载产物。可转告 Terra：新 head 的 `app-full-debug` 已可用，入口为 [Android APK Artifact run 41632007735](https://github.com/1rua/open-android-intelligence/actions/runs/41632007735)。

只读检查了关联本 SHA 的 inline review comments 与 review summaries，未发现新意见；未发送评论，也未手动重跑或本地编译。

旧 head 的监测目录已完整复制至忽略目录 `tmp/attachment-upload/ci-76-1e3d6a81/`，并与复制前逐文件比较一致。新 head 的脚本状态保存在 `tmp/attachment-upload/ci-76/`；旧 head 失败记录未改写。

## 后续测试修正

CI 失败后保留原始失败结果，未重跑相同提交。超时用例继续要求 1.5 秒内结束、正文未完成、只报告一次 I/O 失败；不要求连接断开后由特定 JVM 实现抛出某个 I/O 异常子类。取消用例在观察 EOF 前改为批量读取已排队数据并恢复正常 TCP 接收窗口，避免把逐字节排空速度当成连接未关闭；读取超时不能作为关闭证据。修正后的三个用例仍须由新提交 CI 执行，未在本地编译。

## 第二轮后的最小测试补丁

第二轮 CI 的唯一失败是固定长度超时测试对 `operationFailure` 与失败回调异常执行 `assertSame`。两者均为同消息的 `IOException`，但异常对象身份不是公共契约；协程的异常栈恢复可能复制 `Throwable`。本补丁仅移除该身份断言及其 import，并在原位置说明这一点。

第二轮中，调用方取消测试和持续进展测试通过。固定长度超时测试在身份断言处提前退出，因此独立 `System.nanoTime` 时限、未写完与单次失败上报断言均已通过，但其后的真实 EOF/reset peer-close 断言尚未执行，仍须由后续统一 CI 验证。限时、I/O 类型、未完成写入、单次上报和真实 EOF/reset 检查均保留；未弱化关闭证据或延长时限。

本补丁未本地编译或运行测试，也未启动第三轮 CI。待主 Agent 汇总 Terra 的真实 50 MB 上传结果及其他改动后统一推送 CI。

## 第三轮 CI 检查摘要

监测 head：`6c46c8c8f99a29c379eb358feb0000e83d376ef9`。后台 session `35388` 已结束，精确 HEAD 快照匹配，监测结果为 `CHECK_FAILURE`；当前没有该监测进程。六项检查成功，Android job 失败：

| 检查 | 结果 | 运行记录 |
| --- | --- | --- |
| `sign-plugin-packages` | 成功 | [job 125980543909](https://github.com/1rua/open-android-intelligence/actions/runs/41654340747/job/125980543909) |
| `apk` | 成功 | [job 125980983314](https://github.com/1rua/open-android-intelligence/actions/runs/41654340747/job/125980983314) |
| `core (3.11)` | 成功 | [job 125980590594](https://github.com/1rua/open-android-intelligence/actions/runs/41654353020/job/125980590594) |
| `core (3.12)` | 成功 | [job 125980591313](https://github.com/1rua/open-android-intelligence/actions/runs/41654353020/job/125980591313) |
| `hermes-native` | 成功 | [job 125980590219](https://github.com/1rua/open-android-intelligence/actions/runs/41654353020/job/125980590219) |
| `dsh` | 成功 | [job 125980589620](https://github.com/1rua/open-android-intelligence/actions/runs/41654353020/job/125980589620) |
| `android` | 失败 | [job 125980590260](https://github.com/1rua/open-android-intelligence/actions/runs/41654353020/job/125980590260) |

Android job 中 `:gateway-client:testDebugUnitTest` 已通过；job 后续失败，因此附件上传专项步骤未执行。失败日志由主 Agent 单独核对，本节只记录检查状态，不重复根因分析。第三轮状态保存在忽略目录 `tmp/attachment-upload/ci-76/`；第二轮 `39a` 快照保存在 `tmp/attachment-upload/ci-76-39a/`。
