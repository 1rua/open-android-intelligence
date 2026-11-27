# 局域网连接反复离线与新建会话失败调查

调查日期：2026-11-09，时间采用 Asia/Shanghai（UTC+8）。固定基线为 `dev@c4191c20e6b7a8933c516d8402e0cab659eeb649`，开始时与远程 dev 一致。

后续用户已连接USB手机并手动复现，新增的真机证据和数据库锁认证反例见[USB现场追踪](2026-11-09-usb-phone-connection-creation-followup.zh-CN.md)。下文“无ADB”和现场尚未对应等描述保留为最初调查时的证据状态，不代表后续采集仍无手机。USB窗口没有捕获协商500，不能把旧进程错误直接当作新现场唯一根因。

本次仅调查、运行定向验证并登记 issue，不修复业务代码，不创建业务 PR，不改变运行服务、账号、主密钥、授权、会话或历史。临时探针在独立临时目录中通过 Gradle init script 注入测试源，仓库测试文件未修改。本报告保存正式调查结论；完整的脱敏复现源码和输出保存于关联 issue。

## 用户现象与交付

用户报告手机在局域网内可以连接 Core，但反复出现「Hermes 离线」「通道已断开」；点击新建会话时，「正在创建对话」卡片立即消失，未进入新会话。用户确认 App、Core、Hermes 均为最新 dev，本机部署，并确认 **05:07 服务重启后仍然出现**。

- [Issue #104：连接状态与恢复失败](https://github.com/1rua/open-android-intelligence/issues/104)。
- [Issue #105：新建会话卡片消失且不进入新会话](https://github.com/1rua/open-android-intelligence/issues/105)。

两项均标记 `bug`，正文包含调用链、可重复反例、现场证据限制和修复验收条件。没有把 issue 登记称为修复完成。

## 证据分层

| 证据 | 已确认 | 不能推出 |
| --- | --- | --- |
| 用户报告 | 最新 dev，本机部署，重启后仍出现 | 手机 APK 的精确提交、每次点击的请求身份 |
| 本机 journal | 旧 Core PID 在协商时发生 16 次配置模块加载异常 | 新进程仍在同一错误状态、这些请求是 Agent 列表请求 |
| 安装源码核对 | 安装的 configuration、sessions、server、scripts_runtime 四文件与固定 dev 字节一致 | 旧进程或新进程的全部内存模块都来自这些文件 |
| 真实 localhost HTTP 反例 | 已加载包指向消失目录会使列表 503、协商 500 | 旧候选目录由谁、何时移动 |
| Android 生产控制器、仓储和 UI 反例 | 下述状态转换、恢复停滞和创建竞争可以稳定触发 | 已抓到手机每次故障的唯一根因 |
| 既有针对性测试 | 相关原行为仍有通过证据，且没有覆盖新增组合失败 | 新缺陷已经修复、手机端到端验收完成 |

本次 `adb devices -l` 没有设备。未取得手机 `OaiConnection`、`GatewayHttpClient`、`OaiNativeEvents`、创建请求 ID 的现场日志，因此无法把重启后的每次症状唯一对应到某一个已复现机制。

## Bug1：三个环节共同造成错误状态和恢复停滞

### 现场旧进程的可选发现异常破坏协商

只读 journal 显示旧 Core PID `2300500` 在 **04:55:11.482517—05:01:58.728586** 发生 16 次相同错误。关键栈为：

```text
server.py:223 dispatch
  registry.discover()
sessions.py:389 discover
  _autodiscover_host_agents()
sessions.py:319
  from .configuration import (...)
ModuleNotFoundError: No module named 'oai_core.configuration'
```

`server.py:223` 属于 `POST /open-android-intelligence/v2/negotiate`。未捕获的导入错误导致协商 500。日志里的模块来自旧任务工作树的 `.toolchains/deployment/candidate/oai_core/`；该工作树和 bootstrap 目前不存在，服务 drop-in 仍引用旧 bootstrap。

真实本地 HTTP 反例保持 `sys.path` 中正确安装源，仅将已导入父包的 `__path__` 指向消失候选目录并清除配置模块缓存：Agent 列表从 200 变为 503 `AGENT_UNAVAILABLE`，协商从 200 变为 500，直接 `discover()` 抛出同名模块不存在。恢复父包路径和缓存后两接口恢复 200。

边界：没有旧 PID 当时内存搜索路径和目录移动审计，不能断言具体清理操作。另用安装虚拟环境和同一残留 `PYTHONPATH` 只导入配置模块，成功解析到正式安装文件，反证“一个不存在的 PYTHONPATH 项足以让新进程继续坏掉”。#102/#103 修复安装器自身进程的包重索引与下载目录归档，不会追溯改变已运行服务的内存路径。

新进程 `2509141` 在 **05:07:16.420997** 记录 Hermes healthy。读取到的后续窗口没有 Hermes 健康转离线记录；DSH 的 05:08—05:10 暂时退化不能移算给 Hermes。用户仍报告异常，因此不能以新进程 healthy 关闭调查。

源码入口：[server.py](../../oai-core/src/oai_core/server.py)、[sessions.py](../../oai-core/src/oai_core/sessions.py)。

### Android 将一次列表失败改成 Hermes 离线

[NativeAgentsController.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/NativeAgentsController.kt) 第 75—92 行在任意 Agent 列表错误时把选中 Agent 的 `online` 改成 false；下一轮成功又恢复。它每 10 秒刷新，绕过了 Core 对已健康 Agent 前两次暂时失败的容忍。

[GatewayRuntime.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt) 第 2837—2848 行进一步合并 Agent 在线和全局 HTTP 可达状态；[WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt) 第 5837 行还混合发送能力，抽屉统一呈现为离线。这些字段不能直接证明 Hermes 进程死亡。

真实控制器和原生仓储共用真实客户端，受控列表依次返回 200、500、200：

```text
Hermes online：true → false → true
Native streamHealth：LIVE → LIVE → LIVE
```

这同时证明单次发现失败会误报离线，但不会单独令健康 Native 同步 FAILED；不能把离线标签与真实通道断开混为一个事实。

### 协商 500 丢失状态后被当成不可恢复错误

`GatewayRuntime.rotateStoredSession()` 第 1665 行在续签前协商。[NegotiationClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/negotiation/NegotiationClient.kt) 第 165—166 行把非 2xx 变成普通 `IllegalStateException("NEGOTIATION_FAILED:500")`，不保留结构化 HTTP 状态。

经 [GatewaySessionRenewal.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/auth/GatewaySessionRenewal.kt) 传播后：

- Platform 的 [GatewayHttpClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt) 第 548—554 行不将这种异常列为可恢复；第 492—496 行报告 FAILED 并退出，Runtime 随后阻断相同凭据的普通恢复。
- Native 的 [NativeReadFailure.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeReadFailure.kt) 提取不到 500，按 `status=0` 的协议失败处理；原生仓储第 458—471 行阻断同步所有者并退出。

真实协商器、共享续签与两种事件循环的最终反例：首次协商返回 500，随后服务准备返回合法 200，Native 重复 5 次 `connectionRestored()`，再分别推进 120 秒虚拟时间：

```text
Native：FAILED，协商次数一直为 1
Platform：FAILED，观察任务已退出，协商次数一直为 1
```

这证实普通恢复信号无法恢复误分类的阻断；显式重新订阅、用户主动重试或凭据变化有独立路径，不能写成“任何操作都永远无法恢复”。虚拟时间不是手机实际等待时间。

既有恢复测试注入 `IOException` 或保留 HTTP 状态的 `GatewayAuthException`，未经过真实协商器的异常转换，因此没有抓住该缺陷。

## Bug2：两种创建失败机制和一处反馈丢失

### 明确未发送也被记录为创建结果未知

新建按钮仅排除创建中状态。Controller 先显示卡片并保存创建 ID；原生仓储在调用客户端前先置 `attempt.attempted=true`。随后客户端发现 Agent 离线或不支持创建，在本机预检抛 `UNSUPPORTED/status=0`，此时没有 POST。

仓储将这个错误送入结果恢复，操作 GET 的 404 又转为 `OUTCOME_UNKNOWN`。Controller 清掉卡片、保留原会话和未确认 ID；在当前 scope 的待确认尝试保留期间，后续点击只恢复原 ID，不再进行第一次真正创建。记录还可写入 checkpoint，但不泛化成所有 scope 切换或重建都永远无法恢复。错误来自“已明确未发送”与“可能已远端受理”状态混淆，不能通过一律重发 POST 来修复。

真实原生仓储与客户端反例：离线时创建，确认 POST=0；将同一 Agent 改成在线，再用原 ID 创建：

```text
首次：OUTCOME_UNKNOWN
恢复在线后重试：OUTCOME_UNKNOWN
创建 POST=0；操作 GET=2
```

路径：[CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt) 第 875—925 行；[AgentSessionClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/agents/AgentSessionClient.kt) 第 107—109 行；Controller 第 1012—1025、1976—2002 行。#104 的误离线快照可以触发此路径；没有手机请求日志时不宣称现场每次点击都已如此关联。

### 初始历史列表抢走创建成功后的导航

前提为 `restoreLastThread=true`、没有活动会话、初始列表尚在等待。用户点击新建后，列表先返回，Controller 第 1779—1782 行自动打开首个旧会话，没有排除创建中。界面创建卡片要求创建源等于当前会话，因此源为 null、当前变为旧会话后卡片立即隐藏，实际创建仍在运行。

之后新会话创建成功，第 1990 行的 epoch/活动会话条件将这次后台自动导航当成用户导航，跳过打开新会话，没有 notice。

```text
初始：active=null
用户点击新建：creating=true，source=null
初始列表返回：active=source，creating=true，source=null
创建成功返回：active=source，creating=false，notice=null
期望 active=created，实际仍是 source
```

部分 App 入口明确关闭历史自动恢复，不能把它泛化为所有冷启动必现。Agent 切换等恢复选择入口可使用此模式。

### 失败说明的显示协程被自身状态清除取消

[WorkbenchScreen.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt) 第 230—235 行的 `LaunchedEffect(state.notice)` 先清空 `notice`，再挂起显示 Snackbar。清空的字段正是 effect 的 key，下一帧重启会取消显示协程，使说明移除。

真实 Compose 界面点击新建、门控仓储返回 `AGENT_OFFLINE`，固定测试时钟，失败后只推进 300ms：创建态已清、当前仍为旧会话、notice=null，界面不存在预期离线说明。固定时钟排除了自动测试时钟把正常显示时长全部推进完的伪失败；这不证明此前每个瞬间都没有显示。

此项解释失败反馈缺失，不是远端会话未创建的独立原因。

## 实际验证结果

以下失败是用于捕获现存缺陷的预期失败，没有实施修复，不将其写成通过。

| 验证 | 结果 | 范围 |
| --- | --- | --- |
| AgentDiscoveryOfflineProbeTest | 1 项预期失败，0.151 秒 | 列表错误误离线，Native 始终 LIVE |
| NegotiationFailureProbeTest | 最终 2 项预期失败，0.243 秒 | 真实协商 500 阻断 Platform/Native，服务恢复后未重新协商 |
| OfflineCreationRetryProbeTest | 1 项预期失败，0.083 秒 | POST=0、恢复后仍只查询 |
| CreationInitialNavigationProbeTest | 1 项预期失败，0.058 秒 | 自动旧会话导航抢占成功创建 |
| CreateFailureVisibilityProbeTest | 固定时钟最终 1 项预期失败，5.256 秒 | 失败 300ms 后没有可读说明 |
| 真实本地 HTTP 模块路径反例 | 对照 200/200；异常 503/500；恢复 200/200 | 未请求真实部署 |
| NativeAgentsControllerTest / NativeConnectionReliabilityTest / GatewayConnectionReliabilityTest | 5 / 18 / 25 项通过 | 原连接行为及已覆盖恢复边界 |
| NewConversationAuthorityTest / WorkbenchOptimisticBootstrapTest | 10 / 1 项通过 | 原创建状态机行为 |
| Core 健康、通信稳定、桥心跳、WS 定向批次 | 91 项通过，1 项既有警告 | 不构成现场故障已修复的证据 |
| 安装器归档与包路径重索引定向回归 | 1 项通过 | 与 #102/#103 区分 |

共形成 6 项互不重复的 Android 缺陷反例；协商测试各次增强不重复计数。未执行不相关的全量构建和 CI 轮询。

服务端实际命令：

```bash
PYTHONPATH=oai-core/src:integrations/hermes-session-adapter/src \
.toolchains/pr64-python/bin/python -m pytest -q -o asyncio_mode=auto --tb=short \
oai-core/tests/test_agent_health_recovery.py \
oai-core/tests/test_remote_communication_stability.py \
oai-core/tests/test_hermes_bridge_heartbeat.py \
oai-core/tests/test_core_ws_reliability.py
```

Android 通过 init script 临时增加测试源。完整源码随 issue 附录保存，复用时可放到仓库忽略的 `tmp/lan-investigation/`：连接探针放 `app-src`，创建仓储探针放 `creation-src`，界面探针放 `ui-src`。从 `apps/android` 运行下列等价命令；临时路径与当时运行位置不同，测试内容和任务相同：

```bash
GRADLE_USER_HOME=../../.toolchains/gradle-user-home \
OAI_BUG1_PROBE_SOURCE=../../tmp/lan-investigation/app-src \
OAI_BUG2_PROBE_SOURCE=../../tmp/lan-investigation/creation-src \
./gradlew --init-script ../../tmp/lan-investigation/app.init.gradle \
  :app:testFullDebugUnitTest \
  --tests '*AgentDiscoveryOfflineProbeTest' \
  --tests '*OfflineCreationRetryProbeTest' \
  --tests '*NegotiationFailureProbeTest'

GRADLE_USER_HOME=../../.toolchains/gradle-user-home \
OAI_BUG2_PROBE_SOURCE=../../tmp/lan-investigation/ui-src \
./gradlew --init-script ../../tmp/lan-investigation/ui.init.gradle \
  :conversation-ui:testDebugUnitTest \
  --tests '*CreationInitialNavigationProbeTest' \
  --tests '*CreateFailureVisibilityProbeTest'
```

## 后续修复的约束

1. 持续服务加载来源必须独立于任务工作树；可选发现异常不能破坏基础协商，且不能伪造健康成功。
2. 发现读取状态、Agent 权威在线状态、独立 HTTP 可达、Platform/Native 同步和发送能力要保留各自含义；不靠隐藏错误解决问题。
3. 暂时协商 5xx 保留结构化状态并可恢复；真正的认证、TLS、协议拒绝不能降为普通可重试网络错误。
4. 创建过程区分明确未发送、可能已发送和权威回执；保持原 ID、持久回执及禁止重复创建保护。
5. 初始列表的后台恢复不能假扮用户导航；真实用户导航仍应受尊重。
6. 错误说明的消费与显示生存期正确交接，保留无关原文案。
7. 后续手机验收按时间、会话身份、`create.clicked`、创建 ID、POST/operation GET、协商状态及通道错误分类对齐。不得以本报告代替重启后手机端到端确认。
