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

## 后续测试修正

CI 失败后保留原始失败结果，未重跑相同提交。超时用例继续要求 1.5 秒内结束、正文未完成、只报告一次 I/O 失败；不要求连接断开后由特定 JVM 实现抛出某个 I/O 异常子类。取消用例在观察 EOF 前改为批量读取已排队数据并恢复正常 TCP 接收窗口，避免把逐字节排空速度当成连接未关闭；读取超时不能作为关闭证据。修正后的三个用例仍须由新提交 CI 执行，未在本地编译。
