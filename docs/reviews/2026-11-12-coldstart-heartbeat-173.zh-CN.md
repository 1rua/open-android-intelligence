# ColdStart 十秒恢复测试的心跳节奏夹具

日期：2026-11-12。关联 #173、PR182。受控诊断基线为 `83fbef075a494f0d4899cc22c08b4864c9edb3cd`，正式最小修复基线为 `4e7aeef85dbe824223ce3a23eeb0a4fe125a04a4`。本轮不修改生产恢复逻辑、十秒预算、账号、会话、设备或真实服务。

## 已确认的测试缺口与历史边界

原 FullRelease 失败在 `ColdStartConnectionTest.returningToForegroundReplacesASilentPlatformConnectionWithoutChangingTheConversation` 的联合等待：十秒内真实 SSE 连接数增加且状态恢复 LIVE。失败位置不是后面的独立耗时预算；原 CI 日志没有采集两条件终值或在途帧，不能据此认定当次失败的唯一来源。

已确认 `LoopbackGatewayStub.ControlledEventStream` 没有声明当前 Core 的心跳节奏头。若心跳字节已从 socket 读入、但到实际前台检查后才交给解析器，它会满足一次显式检查；没有节奏头时，客户端按既有兼容策略使用六十秒持续失活预算，十秒内不会再次替换。这能稳定产生原联合断言的精确症状。

当前 Core 的实际契约在 `oai-core/src/oai_core/gateway/transport.py:397`：响应 prepare 前声明 `X-Open-Android-Intelligence-Event-Heartbeat-Ms: 1000`；prepare 且授权通过后立即写出注释 `: ping`，之后每次事件队列一秒空闲超时再写出心跳。头不是初帧之后才声明。已有 `oai-core/tests/test_core_transport.py:677` 验证该头。桩使用 `: heartbeat`，两者都是有效 SSE 注释，本次不改变无关文本或其快速测试节奏。

## 受控反馈环

所有结果单独记录在[白名单摘要](2026-11-12-coldstart-heartbeat-173-artifacts/controlled-public-summary.json)。不纳管完整日志、原始账号、配置或会话正文。

| 反馈环 | 结果 | 范围与信号 |
| --- | --- | --- |
| 真实 GatewayHttpClient、合成传输、虚拟时间三对照 | 3 项：2通过、1失败、0错误、0跳过，0.186秒 | 无节奏头且放行迟到完整心跳：10秒连接数1、状态LIVE，原同义联合条件失败；同样迟到心跳加真实Core节奏头：连接数3、LIVE；无迟到心跳即使无头：连接数2、LIVE。 |
| 原 FullRelease 方法、真实 Android HTTP 与回环 TCP，持有已到达但尚未交给解析器的心跳字节 | 1项失败，14.832秒 | 保留原十秒联合断言；在注入后第208行（原197行）以相同断言文本失败。门闩并非只暂停服务器写出，已先调用真实输入流 read 得到字节。 |
| 同一真实 TCP 受控方法，唯一变量为增加1000ms响应头 | 1项通过，0失败/错误/跳过，7.976秒 | 恢复实测4000ms；原十秒预算、工作台对象、会话和草稿断言全部通过。 |

受控测试没有捕获历史 CI 当次帧；结论是“夹具遗漏当前契约可以稳定触发同一症状”，不是“已经证明历史 CI 的唯一根因”。没有证据要求修改生产的旧 Core 兼容策略、放宽超时或增加无条件重试。

## 正式最小改动

只在 `apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/LoopbackGatewayStub.kt` 的 ControlledEventStream 响应声明1000ms心跳节奏头。该控制流当前只有上述 ColdStart 方法使用；保留普通旧服务端/无节奏头测试的行为。原 `ColdStartConnectionTest` 方法不修改，临时输入流门闩、包装器与诊断打印不提交。

实际诊断入口均为现有 Gradle；工作目录为仓库根，使用既有 JDK21、SDK36、可写 Gradle 缓存及离线 Robolectric JAR 配置：

```sh
./apps/android/gradlew -p apps/android --offline --no-configuration-cache --max-workers=2 \
  :gateway-client:testDebugUnitTest \
  --tests com.openandroidintelligence.gateway.http.ForegroundLateHeartbeatProbeTest
./apps/android/gradlew -p apps/android --offline --no-configuration-cache --max-workers=2 \
  -I <既有离线测试配置> :app:testFullReleaseUnitTest \
  --tests com.openandroidintelligence.mobile.ColdStartConnectionTest.returningToForegroundReplacesASilentPlatformConnectionWithoutChangingTheConversation
```

第一条和受控门闩测试只存在隔离诊断检出中；正式最小补丁已在固定当前基线执行原 FullDebug/FullRelease 方法，分别1项通过、恢复4032ms与3941ms，实际方法耗时8.253秒与7.165秒；同一 Gradle 调用成功，3分52秒包含当前基线首次Release编译，862任务中342执行、520来自缓存，见[正式结果](2026-11-12-coldstart-heartbeat-173-artifacts/formal-public-summary.json)。同一最终源的原类双变体聚焦回归随后完成：FullDebug 19项通过、FullRelease 19项通过，各0失败/错误/跳过，JUnit分别19.596秒和19.905秒；单次 Gradle 成功1分40秒，862任务中28执行、834最新，复用前次编译产物。初次诊断命令因临时测试文件路径重复而无匹配测试，未执行任何用例，保留为工具失败，不计入受控红环。

## 关闭条件

最小补丁的原 FullDebug/FullRelease 方法和原测试类均已通过；独立 Standards/Spec 和对应 PR 门禁仍须完成。随后固定最终合并提交执行 Full 并确认相关用例实际成功，才按 #173 范围关闭；历史失败、未采集当次帧和所有其他层级的证据分别保留，不以单方法通过冒充完整发布验收。
