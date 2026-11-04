# dev 相对 origin/main 的代码审查

后续修复与测试结果见[修复记录](2026-11-04-review-fixes.zh-CN.md)。本报告保留原审查时点的结论与证据。

## 审查范围

- 审查日期：2026-11-04，Asia/Shanghai。
- 比较基点：`d88fffed134f57c5cf6ca4733e65b61f6e13120d`，即本次请求指定的 `origin/main` 合并基点。
- 最终审查提交：`dev@9e7ab2244b40c14e89cda60cd774e4f7ee8577e9`。
- 首轮检查固定在 `e38edc0994f300f1fdf9d381317017d48ad9d133`；审查期间合入设置布局提交后，补查了 `e38edc09 → 9e7ab224` 的七个文件。下文行号按最终审查提交定位。
- 审查结束时，工作区另有尚未提交的 `WorkbenchScreen.kt` 修改；这些修改不属于本报告的提交范围。报告不判定后续提交或工作区修改是否已修复下列问题。
- 本次工作为代码审查及证据归档，没有实施产品修复，也没有执行提交、推送、发布或部署。

可在仓库根目录重新查看固定范围：

```bash
git diff d88fffed134f57c5cf6ca4733e65b61f6e13120d 9e7ab2244b40c14e89cda60cd774e4f7ee8577e9
```

## 结论与证据分级

共确认 **12 项问题：P1 两项，P2 十项**。P1 涉及错误 Agent 接收消息及 Hermes 压缩后无法继续发送，应优先处理。

- **已复现**：在隔离的合成数据、真实 HTTP 测试服务、固定宿主 SDK 或协议子进程中观察到错误行为；不代表真机或生产部署复现。
- **源码确认**：调用链、状态更新或序列化路径能够确认缺陷，尚未执行对应界面、并发时序或设备验收。
- 下列修复方向与回归场景均为建议，**不是已完成的修复或已通过的回归测试**。

| 编号 | 优先级 | 问题 | 证据 | 状态 |
| --- | --- | --- | --- | --- |
| R01 | P1 | 切换 Agent 时，旧工作台可能向新 Agent 提交消息 | 源码确认 | 待修复 |
| R02 | P1 | Hermes 合法压缩后继被绑定检查拒绝 | 已复现 | 待修复 |
| R03 | P2 | 失败推送目标反复占满批次，阻塞在线目标 | 已复现 | 待修复 |
| R04 | P2 | 一个端点撤回失败阻断其他 Core 的推送 | 源码确认 | 待修复 |
| R05 | P2 | Codex RPC 超时未覆盖写锁及管道写入 | 已复现 | 待修复 |
| R06 | P2 | 消息临时身份标记未持久化，恢复后产生重复行 | 源码确认 | 待修复 |
| R07 | P2 | 注销端点后注册名额不释放 | 已复现 | 待修复 |
| R08 | P2 | 截图结果在取消交接时缺少缓冲区释放 | 源码确认 | 待修复 |
| R09 | P2 | 平板设置误用全屏模式，失去侧栏输入法避让 | 源码确认 | 待修复 |
| R10 | P2 | 设置页面跨窗口断点时丢失导航及临时状态 | 源码确认 | 待修复 |
| R11 | P2 | 无可用终端时密码输入循环无法结束 | 源码确认及无终端探针 | 待修复 |
| R12 | P2 | 虚拟环境创建没有复用已检测的 Python | 源码确认 | 待修复 |

## 问题详情

### R01 / P1：将工作台发送绑定到固定 Agent

**位置：** [GatewayRuntime.kt:1882](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L1882)。

A 工作台发送消息、等待本地保存时，用户可切换到 B。旧工作台的关闭也要等待同一持久化锁；旧发送先完成保存后，`submitMessage()` 通过共享的 `currentAgent` 闭包读取到 B，却仍携带 A 的 `conversationId`。若两端会话 ID 相同，会错投消息；否则发送失败。

依据为 [WorkbenchController 的退役路径:1440](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L1440)、[保存后提交路径:3218](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L3218)及 [CoreAgentConversationRepository.submitMessage():1056](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt#L1056)。现有[生命周期方案:245–252](android-auth-session-lifecycle-fix-plan.md#L245)要求交接所有者一致，并按 Agent 隔离本地范围。

**修复方向：** 每个工作台绑定不可变的 Agent；切换时先冻结旧工作台的新远端操作，保留已受理工作的原所有者。

**回归场景：** 暂停 A 的持久化提交，切换到 B，再恢复提交；分别覆盖会话 ID 相同和不同的情形，断言消息不会进入 B。

### R02 / P1：接受 Hermes 会话的合法压缩后继

**位置：** [runtime.py:1139](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py#L1139)。

Hermes 压缩后，同一 `sessionKey` 会指向压缩子会话，而账号绑定仍保存父会话 ID。`_dispatch_agent_inbound → _ensure_agent_session → _remember_agent_source` 的严格 ID 比较将合法后继判为 `HOST_INCOMPATIBLE`。

使用固定 Hermes SDK `75b083e9399115b12513385252811f09c3503c90`，通过真实 `publish_compression_child()` 和 `SessionStore.get_or_create_session()` 推进宿主路由后，再调用 `adapter.send()`，观察到 `queued → AGENT_FAILED`；原生处理器调用列表为空，持久 generation 为 `failed`。见[复现输出](evidence/2026-11-04-dev-vs-main/hermes-compression-reproduction.txt)。

**修复方向：** 在账号、来源和会话键仍精确匹配的条件下，验证当前会话属于原绑定的合法压缩链；继续拒绝独立重置或其他会话。

**回归场景：** 压缩后继续发送成功；独立重置、其他账号和不匹配来源仍被拒绝。

### R03 / P2：避免失败目标持续占满推送批次

**位置：** [DevicePushRuntime.kt:225](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntime.kt#L225)。

每轮固定从队首取 `maxDeliveries` 个目标，而 `RETRY` 或 `UNAVAILABLE` 项不会移除或轮转。失败目标积压后，后面的在线目标无法被处理，事件可能一直等到过期。

以现有独立推送运行时及测试夹具复现：65 条短信分别投递给 A、B，A 持续重试，B 正常确认；10 轮后 B 仅收到 63 条，短信 64、65 未收到，第 7–10 轮均无新增确认，剩余 67 个投递目标。见[逐轮输出](evidence/2026-11-04-dev-vs-main/push-fairness-reproduction.txt)。

**修复方向：** 使用能持续推进的遍历位置或公平调度，保留失败项重试而不垄断后续批次。

**回归场景：** 一个端点长期失败时，另一个端点仍能收到全部有效事件；重启和批次边界不得造成遗漏或重复确认。

### R04 / P2：按端点隔离撤回失败

**位置：** [DevicePushComposition.kt:179](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/DevicePushComposition.kt#L179)。

用户移除 Core A 的接收端点后，若 A 不可达且存在待撤回记录，`revocationsReady` 被设为 `false`，整个周期提前返回。在线 Core B 的通知采集、短信采集和队列投递因此全部跳过，后续周期继续受阻。

**修复方向：** 保留 A 的撤回重试，只阻断受撤回影响的端点和事件；其他仍有本地授权的目标继续处理。

**回归场景：** A 离线且撤回失败、B 在线并被用户选中时，B 的采集与投递继续推进，A 的撤回记录保留并可恢复重试。

### R05 / P2：让 Codex RPC 超时覆盖管道写入

**位置：** [codex.py:226](../../oai-core/src/oai_core/adapters/codex.py#L226)。

`_rpc_raw()` 先等待 `_write()`，随后才对响应计时。宿主停止读取标准输入时，大消息会阻塞在 `stdin.drain()` 并持有写锁，后续普通 RPC 等待写锁也不受 `request_timeout` 限制。

使用完成初始化后暂停读取输入的合成协议子进程，发送 512 KiB 文本。配置超时 100 ms 时，发送返回 `AGENT_TIMEOUT`，但写锁仍占用；随后 `status()` 超过外层 600 ms 截止时间。见[复现输出](evidence/2026-11-04-dev-vs-main/codex-pipe-reproduction.txt)。清理阶段的未消费 future 异常是本次复现的诊断输出，不另计问题。

**修复方向：** 对普通 RPC 的写锁等待和管道写入一并限时；保留后台 `turn/start` 的晚到受理跟踪，避免超时后自动重发。

**回归场景：** 子进程不读输入时，普通 RPC 在预算内失败；晚到的生成受理仍可关联和取消，不会重复提交。

### R06 / P2：持久化消息的临时身份标记

**位置：** [EncryptedConversationMirror.kt:184–195](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt#L184)。

`message()` 与 `decodeMessage()` 均遗漏 `localPlaceholder`。原值为 `true` 的消息保存后恢复成默认 `false`，使未取得真实宿主 ID 的 `stream_*` 回复被当作真实消息。历史补回真实 ID 后，[原生身份区分逻辑:4989](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L4989)拒绝合并两条不同身份的回复，重复行会持续保留。

**修复方向：** 编码和解码均保存、恢复该字段；兼容旧存储时不得仅凭文本或 ID 前缀冒认真实宿主身份。

**回归场景：** 临时回复保存、进程重建、真实历史补回后只显示一条回复；两条真实宿主消息即使正文相同，也仍保留各自身份。

### R07 / P2：注销端点后释放注册名额

**位置：** [endpoints.py:155–157](../../oai-core/src/oai_core/endpoints.py#L155)。

容量统计包含该账号所有历史端点，而 `unregister()` 只写入 `retired=1`，过期端点也没有回收名额。通过真实 HTTP 请求依次注册并注销 64 个不同端点后，当前可用端点列表为空，但下一次注册仍返回 HTTP 429、`ENDPOINT_CAPACITY_EXCEEDED`。见[复现输出](evidence/2026-11-04-dev-vs-main/endpoint-capacity-reproduction.txt)。

**修复方向：** 回收已退役或失效端点占用的注册名额，同时保留必要的历史去重及撤回事实。

**回归场景：** 连续注销超过容量上限数量的历史端点后，仍能注册有效新端点；活动端点的容量保护仍生效。

### R08 / P2：补齐截图缓冲区的取消交接清理

**位置：** [AndroidAccessibilityScreenshotRuntime.kt:111](../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/AndroidAccessibilityScreenshotRuntime.kt#L111)。

`continuation.isActive` 的检查与 `resume()` 之间，以及恢复执行前，都可能发生取消。这里没有成功结果被取消时的清理回调，`FrameHolder` 可能被丢弃：既不进入前面的关闭分支，也不会执行 `encodeScreenshot()` 的 `finally`。整屏硬件缓冲区只能等待 GC，多次取消可能积累图形内存占用。

**修复方向：** 为成功结果移交和后续调度边界补齐取消释放，明确每个阶段的缓冲区所有者，避免遗漏或重复关闭。

**回归场景：** 分别在成功回调前、检查后恢复前、切换编码线程前取消请求，断言缓冲区恰好关闭一次。该时序尚未进行真机验证。

### R09 / P2：使用宿主窗口宽度决定设置呈现模式

**位置：** [MainActivity.kt:692](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/MainActivity.kt#L692)。

宽屏设置插槽已被 `WorkbenchScreen` 限为 320dp，此处读取的局部 `maxWidth` 永远达不到 840dp，平板也会选择 `SettingsPresentation.SCREEN`，关闭 [SettingsScreen.kt:256](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsScreen.kt#L256) 为侧栏提供的 `imePadding()`。主动推送子页没有其他输入法避让，编辑底部参数时会被键盘覆盖。

**规则依据：** [AGENTS.md:26](../../AGENTS.md#L26)要求保留既有交互能力；[设置规格:23–25](../superpowers/specs/2026-11-03-android-settings-sidebar-and-tablet-panel.md#L23)要求 320dp 侧栏内控件可用、不被遮挡。

**修复方向：** 从宿主传入已计算的宽屏状态，为平板保留 `SIDEBAR` 模式。

**回归场景：** 宽屏 320dp 设置面板中聚焦底部输入框，键盘显示后输入框及相关操作仍可见、可滚动到达；手机继续采用全屏设置。

### R10 / P2：跨窗口断点时复用设置页面状态

**位置：** [PlatformSettingsBottomSheet.kt:123–127](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PlatformSettingsBottomSheet.kt#L123)。

从登录页打开设置子页后，调整窗口跨越 840dp，两个分支分别创建 `SettingsScreen`。原 `NavController` 随分支销毁，新页面回到设置总览，原页面的临时状态也可能丢失。

**规则依据：** [设置规格:26](../superpowers/specs/2026-11-03-android-settings-sidebar-and-tablet-panel.md#L26)明确要求跨断点保留面板及相关状态；[AGENTS.md:26](../../AGENTS.md#L26)要求保留既有能力。

**修复方向：** 将同一份设置内容及其状态提升到分支外复用，随窗口移动呈现位置。

**回归场景：** 未登录时进入设置子页并编辑尚未保存的内容，双向跨越 840dp，当前子页、草稿和滚动位置均保留。

### R11 / P2：无可用终端时退出密码输入循环

**位置：** [install.sh:439–443](../../scripts/install.sh#L439)。

没有可打开的控制终端且标准输入不是 TTY 时，`prompt_password()` 持续返回空值。密码循环不断输出警告并重试，安装无法结束。审查运行的无终端探针确认了控制终端不可打开；未执行整套真实安装流程，该结论还依赖源码中的空值重试路径。

**修复方向：** 进入交互配置前确认有可用终端，否则明确失败退出；若提供非交互凭据入口，应单独校验输入。

**回归场景：** 无控制终端、关闭标准输入时，安装应在限定时间内返回明确错误；具有可用终端的管道安装仍可正常输入密码。

### R12 / P2：创建虚拟环境时复用已检测的 Python

**位置：** [install.sh:223](../../scripts/install.sh#L223)。

前置检查在没有 `python3` 时接受有效的 `python`，但创建虚拟环境固定调用 `python3`。仅提供 `python` 命令的合格环境会先通过检查，再在创建环境时失败。

**修复方向：** 保存并复用前置检查选定的解释器。

**回归场景：** 仅提供合格 `python`、仅提供合格 `python3`、存在不合格解释器等路径均得到一致的检查与安装行为。

## 已执行验证及边界

下表是本次审查过程中已经执行的验证记录。归档文本保留原输出，不将“复现器退出 0”解释成产品修复通过。

| 验证范围 | 结果 | 记录与限制 |
| --- | --- | --- |
| Core 定向测试 | 86 passed，20.95 秒 | [原输出](evidence/2026-11-04-dev-vs-main/core-tests.txt)；覆盖传输、推送端点、原生操作恢复及设备授权协商 |
| 安装与配置事务测试 | 95 passed，17.32 秒 | [原输出](evidence/2026-11-04-dev-vs-main/install-tests.txt)；不等同于无终端真实安装验收 |
| 独立推送 JVM 测试 | 53 tests，0.534 秒 | [原输出](evidence/2026-11-04-dev-vs-main/push-tests.txt)及[运行器记录](evidence/2026-11-04-dev-vs-main/push-test-runner.json)；使用 Kotlin CLI 与 JUnit，没有运行 Gradle 或 Android 设备 |
| Python Agent 适配器相关测试 | 分批成功用例合计 219 | 183 + 13 + 1 + 22；不是一次统一环境的全绿运行，完整原始输出当时未落盘 |
| DSH 适配器测试 | 37 passed，3 个文件 | 会话执行记录；完整原始输出当时未落盘 |
| R02、R03、R05、R07 故障复现 | 四项均观察到报告中的错误行为 | 归档输出见各问题；均使用合成数据或测试进程 |

适配器测试的环境问题如实保留：首批 13 个审批用例因缺少 `dotenv` 失败，换用已有环境后 13 项通过；另批 22 个原生来源用例因缺少 `requests` 在夹具初始化时报错，换用已有环境后 22 项通过。没有将这些环境失败直接认定为业务缺陷，也没有声称曾运行过一条覆盖全部适配器用例的统一全绿命令。

Core 定向测试选择如下，命令省略运行目录、输出重定向和测试临时目录参数：

```bash
PYTHONPATH=oai-core/src .toolchains/pr64-core-only-python/bin/python -m pytest -q \
  oai-core/tests/test_core_transport.py \
  oai-core/tests/test_push_endpoints.py \
  oai-core/tests/test_native_operation_recovery.py \
  oai-core/tests/test_device_authorization_negotiation.py
```

安装配置测试与独立推送测试入口：

```bash
PYTHONPATH=oai-core/src .toolchains/pr64-core-only-python/bin/python -m pytest -q \
  tests/test_install_script.py tests/test_core_setup_transaction.py
python3 apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/run_push_unit_tests.py
```

本次没有执行完整 Android 编译、界面自动化验收、真机截图取消时序或生产部署验证，也没有为审查触发远端 CI。R09、R10 所在的设置布局增量仅做源码和规格核对；其他已执行测试所覆盖的源码在两次审查提交之间没有变化。

## 证据归档

证据位于 [evidence/2026-11-04-dev-vs-main](evidence/2026-11-04-dev-vs-main)。[manifest.json](evidence/2026-11-04-dev-vs-main/manifest.json)记录每份归档的来源、SHA-256 及对应问题。文件使用 `.txt` 保存审查证据，不保存账号凭据、真实设备数据、构建产物或测试数据库。

按既有 [.gitignore](../../.gitignore#L61) 规则，原始证据目录仅在本地保留，不随报告自动进入版本控制。以上证据链接用于当前工作区核对；报告正文已记录关键观察结果和验证边界，可独立阅读。

## 修复顺序建议

1. 先处理 R01、R02，并加入原所有者不变、合法压缩后继及非法来源拒绝的回归测试。
2. 处理 R03、R04、R05、R07，分别验证投递公平性、撤回隔离、超时预算和历史端点名额回收。
3. 处理 R06、R08，补齐持久化往返和资源取消交接测试。
4. 处理 R09、R10、R11、R12，补齐窗口切换、输入法、无终端及解释器选择的行为验证。

所有问题仍按审查提交标记为待修复。后续修复应以实际修改提交、对应回归测试和必要的设备验收更新状态，不能仅凭本报告把问题视为已关闭。
