# PR #133 审核意见修复

关联需求：#132。目标分支：`dev`。本轮固定审核提交：`20081a9ac572225d2b37b6aec5679225c78c4475`。

## 已核实的审核意见

### 评论 4533727873：助手可见性遗漏 Agent 身份

原生会话 ID 只在同一 Agent 内唯一。通知发布器把完整通知目标缩减为会话 ID，系统助理显示 Codex 的 `native-session` 时，DSH 的同名会话完成通知被错误抑制。通知完成记录已在发布前持久消费，因此之后离开助手不会补发。

修复要求：沿用原有可见性策略，使用通知目标的 profile、account、Agent 与助手工作区固定身份严格匹配，再比较会话 ID。platform 的 `agentId=null` 仍是独立身份。助手进入会话时，撤回通知也必须使用同一工作区的完整身份。

### 评论 4533727879：工作台忙碌集合遗漏 Agent 身份

主工作台将共享通知集合中的运行记录直接映射为会话 ID，导致 DSH 的运行记录进入 Codex 的忙碌集合，错误阻止后者同名会话的归档、删除。

修复要求：在提取会话 ID 前，按创建控制器时捕获的 profile、account 与 `ownerAgentId` 过滤。不能改用动态选择的 Agent；旧控制器在交接窗口仍属于原身份。同一 Agent 的在途保护继续有效。

## 复现依据

本轮在未修改生产代码的 `20081a9a` 上复跑审核者的实际 Runtime / TCP SSE 探针：3 项中两项在预期行为断言处失败，助手不可见的对照用例通过，0 错误、0 跳过。探针没有直接向通知模块注入事件。

已有未提交审查文档和审核探针保留原状。原审核修复将必要场景纳入既有 `NativeAgentBackgroundWorkTest`，当时仅在新场景显式允许并发连接。后续与最新 `dev` 整合时，测试服务器统一继承上游默认并发处理，保留心跳控制和显式终稿释放两套能力，以便流式连接存续时仍可读取其他会话。

## 验证记录

- 修复前远端：`20081a9a` 的 20 项检查均成功；审核问题另由上述失败探针确认。
- 审核者原探针复跑：3 项，2 失败、1 通过；永久回归修复前在 API 34/36 各执行 6 项，12 项中 8 项失败。失败分别对应跨 Agent 通知误抑制、归档误拦截、删除误拦截、助手无法撤回同身份原生通知；助手不可见和同 Agent 保护对照通过。
- 新增回归：跨 Agent 可见/不可见、跨 Agent 归档与删除、同 Agent 在途保护、切回同 Agent 后的共享运行记录保护、助手只撤回相同身份通知、profile/account/Agent/会话身份变体。
- `npm run test:fast -- --since 20081a9a` 通过。本轮按审核提交限定增量范围，不将此前测试夹具的 Core 全组变更再次视为本轮新增改动。
- 首次修复后 5 类定向回归中，完成通知、删除、撤回、身份匹配及原有通知/气泡测试通过；归档进入既有传输边界时报 `CONVERSATION_MANAGEMENT_FAILED:Invalid HTTP method: PATCH`。这是宿主 JVM 的 HTTP 实现限制，发生在请求发出前，本轮没有修改生产传输。
- 归档新增空闲对照：先完整建立 DSH 主工作台，再切到没有其他任务的 Codex，要求其到达相同 PATCH 限制；跨 Agent 归档用例要求不再返回 `CONVERSATION_BUSY`，后续错误必须与对照精确一致。此证据只证明解除误拦截，不记为归档 HTTP 端到端成功。
- 删除用例要求实际 Codex `DELETE` 请求、无错误及会话列表删除，并确认 DSH 任务仍在运行。
- 同 Agent 共享保护用例切回 DSH 后打开另一空闲会话，明确确认原运行会话不在新控制器的自身在途集合，再核对原会话归档、删除仍被共享运行通知保护。
- 最终验证提交 `a1fdd693`：`npm run test:fast -- --since 20081a9a` 与 `npm run test:pr -- --since 20081a9a` 全部通过。PR 套件覆盖 79 类、1025 项，0 失败、0 错误、0 跳过；`NativeAgentBackgroundWorkTest` 在 API 34/36 共 34 项通过。
- 本轮未重跑模拟器或真实设备，旧设备证据仍归属此前业务提交。归档 HTTP 的宿主 JVM 限制仍按上文单独记录。

## 生产修改与独立复核

生产修改只涉及 `GatewayRuntime.kt`：发布器接收完整通知目标，助手可见性复用 `AssistantConversationWorkspace.matches`；立即撤回使用工作区固定身份；主工作台先按固定 profile/account/`ownerAgentId` 筛选运行通知，再提取会话 ID。

规范轴对 `2b6abc12` 与原审核提交的差异检查，明确规范违规 0 项、判断性代码味道 0 项。需求轴确认两条审核意见均得到对应修复；补核至 `f6f2ae78` 后无阻塞意见，并要求明确区分宿主 JVM 归档限制、真实删除请求和未重跑的设备证据。

本轮没有更改通知文案、UI、回复生成、原生消息通知结构或气泡结构，没有重构其他忙碌状态来源。原工作区的未提交审查文档与设备资料保持原状。

## 提交与审核回执

- 生产修复：`2b6abc12`。测试收口：`4cca2f9f`、`f6f2ae78`、`a1fdd693`。原 PR 分支 `feature/agent-reply-notifications` 已更新，目标仍为 `dev`，保持开放且未合并。
- 评论 `4533727873` 的原线程回复：`4534047114`。已说明完整身份抑制、匹配身份撤回、34 项 Runtime 回归和未重跑设备的边界。
- 评论 `4533727879` 的原线程回复：`4534047221`。已说明固定工作台身份过滤、真实删除请求、归档解除误拦截与 JVM PATCH 对照、同 Agent 保护。
- 两个原审核线程均已标记解决，并回读确认。
- `a1fdd693` 远端检查已触发：[Core、App 与门禁](https://github.com/1rua/open-android-intelligence/actions/runs/42960283260)、[APK](https://github.com/1rua/open-android-intelligence/actions/runs/42960276284)。本轮归档时仍在运行，由后台只读任务跟进；不记为最终 CI 已通过。
- Codex 自动审核另有用量限制提示；该提示不属于源码缺陷。本轮两个有效行内意见均由独立规范轴与需求轴复核，不请求用户为此升级账号。

## 与最新 dev 的冲突整合

用户追加要求解决 PR #133 的合并冲突。本次以最新 `dev@2e1626f5d655b9a4785517817eb4b9b3ca18b10c` 为固定基线，将整合前 `61ed40611cf312b63da8908884c644bbd256a975` 的 16 个任务提交顺序重放，保留原 PR、原 issue 和线性历史。旧提交另外保存在本地备份分支，未修改主工作区的 `dev` 或其他会话资料。

冲突处理：

- `GatewayRuntime.kt` 的主工作台与助手仓库同时保留完成事件通知回调，以及上游前后台判断、关闭时移除、独立读取者登记和恢复参数。气泡仍进入同一读取者恢复集合，助手资源关闭仍关闭对应仓库。
- `LoopbackGatewayStub.kt` 同时保留上游可控心跳流和通知测试的初始／终态受控流。服务器统一沿用上游默认并发处理，删除通知测试中重复的并发开关，避免长连接阻塞其他请求。
- 现有完整身份过滤、重复终稿通知保护继续保留；`CoreAgentConversationRepository.kt` 与 `NativeEventSynchronizer.kt` 相对新基线完全一致。没有新增业务行为、UI 或文案。

验证源码固定在 `e0086f7953783c0755324c235f5b38307f0a3ae5`。增量基准 `ad7e8396` 对应原审核提交 `20081a9a` 在本次重放后的提交；测试根据完整整合后源码执行，公共测试夹具使计划覆盖全部 App 回归分组。

- `npm run test:fast -- --since ad7e8396`：通过。
- `npm run test:pr -- --since ad7e8396`：通过。165 类、1836 项，1835 项通过，0 失败、0 错误、1 跳过。跳过项为 `BuiltInCapabilitiesTest.aChannelWithoutPluginRuntimesReportsEveryCapabilityAsUnavailableWithAReason`，沿用 Full 变体下的原有前提判断；本次没有更改该测试。
- 重点用例：`NativeAgentBackgroundWorkTest` 34 项、`NativeConnectionReliabilityTest` 41 项、`NativeEventSynchronizerTest` 21 项、`NativeConversationNotificationsTest` 156 项、`ColdStartConnectionTest` 19 项，均无失败、错误或跳过。这些已包含在上述 PR 套件内，不重复计数。
- 首次验证使用本机默认 JDK 17，部分 Robolectric 环境初始化失败后中断，原日志保留。最终使用与仓库 CI 相同的 JDK 21 完整重跑并取得上述结果，没有放宽断言或屏蔽失败用例。
- 独立规范轴复核：0 项硬性违规、0 项需要修复的判断性代码气味。独立需求轴复核：无阻塞问题；指出的测试服务器说明过时已修正。
- `git diff --check` 及固定范围检查通过；本次未重跑模拟器或真机，既有设备回执仍只归属原记录提交。新提交的远端 CI 由后台只读任务跟进，不能借用旧提交的成功结论。

上文原审核阶段的提交与测试数保留为历史证据；本次整合后的本地源码验证以本节及结构化回执的“冲突整合”字段为准。
