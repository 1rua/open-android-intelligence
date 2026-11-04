# 2026-11-04 dev 复审修复记录

## 范围与基线

- 输入：[dev 相对 main 的复审](2026-11-04-dev-vs-main-followup-review.zh-CN.md)，本轮处理 F01–F09。
- 基线：`dev@54de1abbcb1312be6f815bcb044f44d62cff18b3`。
- 分支：`feature/followup-review-fixes-20261104`，目标分支 `dev`。
- 原工作区两份未提交的审查文档保持原样；输入报告在本分支按原内容归档。
- 本记录区分代码修复、自动化测试、远端 CI 与设备验收；本轮各项修复已完成自动化验证。

## 修复与回归

| 编号 | 实现边界 | 回归入口 | 当前验证状态 |
| --- | --- | --- | --- |
| F01 | [DSH 入口](../../integrations/dsh-session-adapter/index.js#L100)：宿主缺少已确认的按执行轮次原子取消接口，停止声明 `cancel`；入口返回 409 `UNSUPPORTED_CAPABILITY`，不调用会话级取消 | DSH HTTP 入口测试，覆盖旧轮次、当前轮次、重复及缺失轮次请求 | 已通过 |
| F02 | [隔离日志](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt#L99)：隔离日志移除整个结果对象，保留防重放事实；恢复和事件重放清理旧隔离记录 | `DeviceExecutionRecoveryTest`、`DeviceGrantFenceTest` | 已通过 |
| F03 | [覆盖检查](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventSynchronizer.kt)、[镜像提交](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt#L909)：可信撤回覆盖旧正文事件，先持久化撤回再 ACK；本地镜像不得因缺少正文修订而跳过撤回 | `NativeEventSynchronizerTest` 及真实加密镜像测试 | 已通过 |
| F04 | [内核调用](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PluginKernel.kt#L270)：屏幕控制在插件并发拒绝之前 FIFO 等待；取得名额后重新核验授权与调用身份 | `ScreenControlKernelQueueTest`、屏幕运行时及租约测试 | 已通过 |
| F05 | [附件观察](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt#L2768)：上传状态同步非活动草稿，切回后重新订阅真实协调器；重试迟到失败只更新原会话与发送身份 | `WorkbenchPendingAttachmentSwitchTest` | 已通过 |
| F06 | [本地账号状态](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsViewModel.kt#L49)、[账号切换](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L924)：设置账号操作读取真实本地会话；切换账号发布选择状态；清除活动账号指针失败时保留原工作台 | `SettingsViewModelTest`、`ColdStartConnectionTest`、`AccountSwitchPersistenceFailureTest` | 已通过 |
| F07 | [命令目录装配](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt#L1925)：命令目录固定发现控制器和 Agent ID，每次读取该 ID 的最新状态及能力 | `ColdStartConnectionTest.offlineWorkbenchCommandCatalogUsesItsOwnerLatestDescriptor` | 已通过 |
| F08 | [独立发现](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/IndependentConnectionDiscovery.kt#L13)、[周期组合](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/DevicePushComposition.kt#L151)：各账号独立发现、限时并隔离失败；刷新与投递按账号隔离；临时配对恢复失败保留已认证推送连接，身份与绑定异常仍阻断并隔离缓存连接 | `IndependentConnectionDiscoveryTest`、`DevicePushCycleTest`、`DevicePushRuntimeTest`、`AdditionalDeviceConnectionsTest` | 已通过 |
| F09 | [分级计划](../../tools/smart-test.py#L145)：插件影响标记接入分级计划，全量计划无条件覆盖插件验证 | `tools/test_smart_test.py`、插件测试 | 已通过 |

## 自动化验证

快速门禁 `npm run test:fast` 已通过，退出码 0：

- 配置、安装卸载及测试入口：188 passed、1 skipped。
- DSH：41 passed。
- Android：`platform-kernel` 95、`conversation-domain` 39、`conversation-ui` 748、`app` 1510，合计 2392，失败、错误和跳过均为 0。

独立定向验证：

- `python3 -m pytest tools/test_smart_test.py -q`：48 passed。
- `npm test --prefix plugin-tooling`：86 passed。
- `NativeEventSynchronizerTest`：20 passed；真实加密镜像 `NativeEventDeletionPersistenceTest`：6 passed。
- `ScreenControlKernelQueueTest`：12 passed；现有 `RemoteScreenRuntimeTest`：40 passed。
- 修复前，DSH 新增断言实际观察到 `cancel=true`、取消 HTTP 200、宿主取消被调用；修复后相同回归均通过。
- DSH 的 `--detectAsyncLeaks` 运行报告 41 项断言通过，同时输出 2 条 fetch 测试夹具 Promise 泄漏诊断；不把此运行表述为无泄漏证明。
- 本地复用 Node 24.18.0、JDK 21 与 Vitest 4.1.10 缓存；远端 CI 使用仓库现有的 `npm ci` 固定依赖安装。
- 机器可读的结果和最终源码 SHA-256 清单见[验证记录](2026-11-04-followup-review-verification.json)。

最终 PR 门禁 `npm run test:pr` 已通过，退出码 0。源码 SHA-256 清单与本次运行前的冻结版本一致：

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| platform-kernel | 95 | 0 | 0 | 0 |
| conversation-domain | 39 | 0 | 0 | 0 |
| conversation-ui | 751 | 0 | 0 | 0 |
| app | 1514 | 0 | 0 | 0 |
| 合计 | 2399 | 0 | 0 | 0 |

同轮配置与安装回归为 188 passed、1 skipped，DSH 为 41 passed。首轮 PR 门禁曾因新增配对测试响应缺少 `protocol=2.1` 而失败；已按真实协议修正测试输入，保留严格生产解码器，经过边界定向回归及最终整轮门禁验证。

远端 CI 由 PR 触发；后台检查按固定 head SHA 归档 CI 结果与审查评论，并在完成后唤起 Agent 复核。此处的本地通过结果不代替远端 CI。

## 验收边界

- 合成网络和运行时替身的测试仅证明对应调用及持久化边界，不代表真实宿主或真机验收。
- 本轮不自动合并、发布、部署，不操作用户真实短信、通知或屏幕。
