# 原生流认证终帧与凭据版本恢复

日期：2026-11-12。关联 #183、#180、#142；基线 `7c599ab0d8b85cc6f68a7b5fd15c82bf2aea249d`。本子任务只改 Android 客户端及仓储，未操作设备、服务、模型或既有凭据/私人正文；Core 错误码保真由独立子任务处理。

## 已确认缺口

`AgentSessionClient.send` 将已开始的 SSE 内认证错误直接转为 `AgentSessionException`。旧 `CoreAgentConversationRepository` 普通失败分支保留未知屏障，却移除原轮次及恢复所有者；平台恢复后，没有对象可按原 client 继续查询。真实7c599 Core重启现场收到SESSION_REVOKED的事实与此链一致，但本报告不据该码推断当次一定由过期或认证存储故障造成。

另外，旧恢复代码在异常到达时才读取当前 `credentialVersion()`。若平台已经先合法刷新，新凭据会被误记为旧请求拒绝的代次，导致新凭据也无法唤醒查询。仅在发送开始时采样亦不充分：HTTP401可以通过既有授权刷新重放一次，最终 SSE 实际使用的是刷新后的请求代次。

## 最小修复

- `GatewayHttpClient.execute/consumeResponse` 增加默认可选的内部请求代次回调，在实际签名快照通过本地发送围栏之后、传输之前通知该次 `sessionId`。HTTP401的两次实际请求分别报告代次，不改签名、幂等键、授权或既有HTTP刷新策略。
- `AgentSessionException` 可携带实际拒绝代次，供本机围栏使用；异常正文和 `toString()` 仍只有原错误码，不记录代次值或Token。`AgentSessionClient` 将流和GET错误关联到实际尝试，保留原异常为cause。
- 已协商generationRecovery的认证失败保留原client/generation恢复所有者与未知屏障，并暂停同一被拒绝代次；合法新凭据出现后才可读权威generation/history。错误码本身不确立完成/取消/空闲，不自动刷新真实撤销凭据或重新POST输入。
- 若新凭据在旧SSE/GET报错前已经生效，围栏比较的是旧请求实际代次，允许立即用合法新代次重做只读查询；若错误来自刷新后当前真正请求，则保持暂停。`AUTHENTICATION_STORAGE_UNAVAILABLE` 复用暂时故障读取恢复，不伪装为撤销。
- 权威回执仍unknown时，后继继续留队且原输入零重投；未协商能力保持保守行为。原有Agent/会话键和账号绑定保持不变。

## 正式验证

首轮正式 `NativeAuthenticationStreamRecoveryTest` 覆盖12项：同旧凭据零重复读取/提交；错误后合法轮换；错误前合法轮换；HTTP401刷新后的实际代次；认证存储暂不可用；unknown不重投；相同会话跨Agent隔离；缺接纳帧时仅按原client读取；旧权威GET晚到403与新代次竞态；跨账号隔离；关闭释放；未协商不伪造权威完成。新类已登记 `apps/android/ci/app-regression.txt`。

同一正式新类在固定未修复7c599实际编译模块上为 **12项、9失败、3通过**；候选真实仓储/客户端源码为 **12项通过**。初版10项7失败的红环和最终12项红环分别留在隔离证据目录，不把工具探针当真实模型验收。

正式 Gradle 聚焦命令在 `apps/android` 运行，复用已有JDK21/SDK36、Gradle及离线Robolectric缓存：

```sh
./gradlew --offline --max-workers=2 -I <既有离线测试配置> \
  :gateway-client:testDebugUnitTest \
  --tests com.openandroidintelligence.gateway.http.GatewaySessionRecoveryTest \
  --tests com.openandroidintelligence.gateway.agents.AgentSessionClientTest \
  :app:testFullDebugUnitTest \
  --tests com.openandroidintelligence.mobile.conversations.NativeAuthenticationStreamRecoveryTest \
  --tests com.openandroidintelligence.mobile.conversations.NativeCancelledStreamRecoveryTest \
  --tests com.openandroidintelligence.mobile.conversations.NativeConnectionReliabilityTest \
  --tests com.openandroidintelligence.mobile.conversations.NegotiationConnectionRecoveryTest
```

实际 `BUILD SUCCESSFUL in 2m 5s`，448任务中185执行、263缓存。Gateway两类36/29，App四类12/5/41/4，**合计127项通过、0失败、0错误、0跳过**，原始XML摘要与分项计数见[白名单结果](2026-11-12-native-auth-183-android-artifacts/formal-red-green-public-summary.json)。Gateway新增行为断言同时验证签名代次/错误正文不泄漏/本地围栏未执行时不报告假请求。

## 独立评审后的存储失败补充

Spec评审发现prepared SSE的 `AUTHENTICATION_STORAGE_FAILED` 没有HTTP状态时落PROTOCOL，初始发送会丢失owner；HTTP500又会落通用TRANSIENT，绕开存储读取预算。已用同一正式测试固定3401源码重现：最终17项中新增5项失败，原12项仍通过。

最小修复仅将该码优先归为STORAGE，并纳入初始发送的原身份恢复入口。保留既有最多六次读取预算；耗尽后owner和未知屏障仍在，不再后台循环。存储可用后的显式connectionRestored可用同一合法凭据继续只读查询，此故障不是凭据被拒绝，也不要求伪造一次凭据轮换。UNKNOWN和关闭对照均保留零重新POST行为。

补充实际Gradle只跑新类17项、取消5项及连接可靠性41项，**共63项通过、0失败、0错误、0跳过**。`BUILD SUCCESSFUL in 1m 22s`，443任务31执行、412最新；没有重复全量127。精确status0/500分类、预算、owner保留、unknown和关闭结果见[Spec补充白名单](2026-11-12-native-auth-183-android-artifacts/spec-storage-failure-public-summary.json)。首次错误使用根目录gradlew未进入构建，已保留为工具失败；随后正确入口结果单独记录。

## 尚待主任务验收

本地聚焦成功不替代独立Standards/Spec、主分支Fast/PR门禁、真实同提交APK的Core/DSH/TCP重启恢复或Full。仍须验证合法新凭据恢复后的原身份及后继各一次、无保存失败与资源释放；真实撤销/同旧凭据不得被绕过。旧失败和未知屏障继续保留，不据本次代码修复关闭总跟踪。
