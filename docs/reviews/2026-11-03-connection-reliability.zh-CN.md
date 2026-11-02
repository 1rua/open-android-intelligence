# OAI 连接稳定性与 WS/SSE 可靠性修复记录

基线：`dev@77f8136b4ffd6a71ec86452f05eaecc0dfdcaa0d`。修复在独立 worktree 和 `feature/connection-reliability-20261103` 分支进行。

## 证据与问题

| 问题 | 触发与代码事实 | 错误传播与影响 | 修复与回归风险 |
| --- | --- | --- | --- |
| WS 空闲时没有 LIVE | GatewayHttpClient 仅在 WS 业务事件到达后报告 LIVE，Ping/Pong 只更新续签活动 | 有效连接仍可能显示 CONNECTING | 增加认证连接与活动回调；心跳不得强制重建 Native |
| SSE 健康时不能主动恢复 WS | WS 偏好只在 SSE 结束后重置 | WS 恢复后仍停留 SSE | 无订阅探测后串行交接 |
| Native 六次失败后停止 | startEventSync 在第六次暂时错误退出 | 依赖外部唤醒 | 保留 synchronizer、pending ACK，持续有界退避 |
| 恢复通知取消健康读取 | connectionRestored 传 force=true | 长轮询被取消，重新补历史，多状态源覆盖健康 | 合并唤醒，按 Native 所有者投影状态 |
| 设备事件查询阻塞消费 | handlePlatformEvent 等待 driver.accept 的十秒 HTTP 查询 | 请求、取消和撤权排队 | requestId 先加密入账，再交现有执行器；未入账不得推进游标 |

Core 已有 WS receive loop、在线引用计数和溢出断链后持久重放。Native JSON 长轮询 waitMs=25000、HTTP read timeout=30000，不走 Platform SSE 的 20000ms 预算。这些保护不按假设重写。源码缺陷与现场根因分别记录；竞态和历史覆盖问题先用针对性回归确认。

## 实施检查点

- [x] 基线与远端核对，建立独立 feature worktree。
- [x] Android 传输状态、失活检测、持续重连和探测；最终门禁见下方。
- [x] Core 探测认证和生命周期清理。
- [x] Native 自愈、健康和会话隔离。
- [x] 网络生命周期和设备请求可靠接收。
- [x] 聚焦回归、test:fast 和 test:pr 全部通过。
- [x] 真机及部署可用性核对；真机场景未完成，见独立验收记录。
- [ ] 中文提交、dev PR、CI 触发记录。

## 验证记录

规划阶段基线：Android 传输 53 项、Core 重放/溢出/重启恢复 5 项通过，不代表修复验收。Native 测试曾被 Gradle 项目缓存读取错误阻断。实施阶段使用本 worktree 固定项目缓存，并复用用户依赖缓存、JDK 21 和已有 SDK。

规划阶段实际执行 adb devices -l，没有设备。实施阶段再次核对；无设备或部署访问时明确未验证，不把模拟写为真机通过。

## 边界与已选方案

健康 SSE 期间进行同等认证、无业务订阅的 WS 探测，成功后串行交接。故障注入选择现有真实部署，记录加载版本和恢复步骤，限定影响窗口，保留账号、密钥、绑定和历史；尚未执行部署变更。

不修改 Hermes/DSH 私有协议或插件实现。离线恢复遵守既有 TTL、风险分类、本地授权和执行检查点；结果只补传，结果未知不重新执行动作。

## 环境性阻塞与修复

首次 test:fast 的 Core/安装卸载门禁为 676 passed、10 failed、1 skipped。十项失败均为既有 `/tmp/open-android-intelligence-trash` 权限为 0755，而卸载脚本要求 0700；没有修改卸载实现或断言。将该用户自有回收目录收紧为 0700 后重新执行门禁。首次 App 设备回归缺本 worktree 的已编译 WASM，已使用仓库既有 Rust 1.88 工具链离线生成，不修改插件源码。

## 复核后补齐的恢复边界

- WS 已工作后自然过期不能与撤销混为 1008：Core 分别使用 4001 与 1008，客户端分别进入既有受限续签和终止。
- WS 探测成功后正式握手 503 回到 SSE；快照重建和续签的临时网络失败留在原退避循环。
- 快速连续切网的等待链使用 UNDISPATCHED 启动和 NonCancellable join 保留真实前任，取消待接管任务不能跳过仍在关闭的 Socket。
- 关闭连接只取消仍在运行的设备任务，已完成结果日志保留用于补传。结果主体只按真实取消/授权栅栏处理，不能把连接关闭当作动作取消。
- Native 取消按完整 Agent/会话/generation 路由；当前 Agent 无匹配时只采用唯一已记录原宿主，歧义不猜测。

当前 Native 定向失败中的 ACK 竞态、生命周期事件缺失 errorCode:null 属测试时序/fixture 修正，未放宽生产解码或保存后 ACK 的断言。

## 代码入口

以下均相对仓库根目录：

- Platform：`apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/http/GatewayHttpClient.kt` 的 `events`、`collectSseAttempt`、`rebuildCursor`，以及 `ws/GatewayWebSocketTransport.kt` 的 typed `events`、`probe`、连接期限和关闭码分类。
- Core：`oai-core/src/oai_core/gateway/http.py` 的 `authenticate_event_stream`，`transport.py` 的 `_handle_ws_probe`、`_handle_ws_stream`、`_finish_websocket`，`core.py` 的 `event_session_failure`；路由与关停由 `oai-core/src/oai_core/server.py` 接入。
- 生命周期：`apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt` 的网络唤醒、Platform 所有者接管与 HTTP 健康投影，`GatewayNetworkMonitor.kt` 的默认网络身份、能力和路径回调。
- Native：同 app 目录下 `conversations/CoreAgentConversationRepository.kt` 的 `connectionRestored`、`startEventSync`、generation 路由；`NativeEventSynchronizer.kt` 的保存、正文修订覆盖和 ACK；`NativeReadFailure.kt` 的故障分类。
- 设备：同 app 目录下 `plugins/DeviceExecutionDriver.kt` 的 `enqueue`、`lookup`、`recoverPending`、`cancelAll` 与补传；gateway-client 的 `device/DeviceRequestClient.kt` 的并发收据和绑定检查。

运行策略见[连接与恢复职责](../architecture/2026-11-03-platform-connection-reliability.zh-CN.md)，设备条件与未完成场景见[验收记录](2026-11-03-connection-device-acceptance.zh-CN.md)。

Android 全量 App 测试还需要既有历史 Hermes HTTP 互通 fixture。隔离 worktree 未默认配置该外部目录，附件测试报 HERMES_FIXTURE_MISSING；后续统一验证显式指向主仓已有 `.toolchains/legacy-gateway-ebe527da`，仅用于历史兼容测试，不修改或部署插件。新 Core 设备能力测试继续使用当前 worktree 的 Core。

## 最终门禁进度

完整环境的 test:fast 已完整通过：Core/安装卸载/门禁 686 passed、1 skipped、6 subtests；Hermes 143 passed；DSH 37 passed；通信测量器 25 passed。Android XML 当前记录 gateway-client 364、control-ports 38、capability-ports 51、screen-control 28、device-primitives 55，均 0 failure/0 error/0 skip。App 全量 1399 项、platform-kernel 83 项、conversation-domain 34 项、conversation-ui 684 项均 0 failure/0 error/0 skip。Gradle BUILD SUCCESSFUL（9m51s）；test:pr 的最终结果见下方。

首次旧快照测试因期待网络 IOException 退出而在持续自愈循环中等待；线程栈定位到 PlatformSnapshotRecoveryTest 后取消该次已失败运行。测试改为在观察到安排重试后主动取消，仍断言未完成 journal 的旧 cursor 不变。

最后补充的凭据栅栏按共享 sessionId 版本生效：同版本的认证拒绝不能靠重开会话绕过，显式续签为新版本后恢复；eventSync 和 generationRecovery 两种观察路径均覆盖。TLS 层明确包装的 EOF/Socket 中断按普通网络恢复，证书、指纹和身份拒绝仍优先终止，不因底层断网 cause 掩盖身份错误。

Python 门禁唯一跳过项已单独核实为 `test_dangerous_or_unrecognized_directories_are_rejected[symlink]`：当前工作区文件系统不能创建符号链接（tests/test_uninstall_script.py:258），该环境路径由 Linux CI 复核。连接、认证、设备和 Native 新增用例未因这个原因跳过。

## 最终准入结果

`npm run test:fast` 完整通过，Gradle BUILD SUCCESSFUL（9m51s）。`npm run test:pr` 在最终源码上完整通过，Gradle BUILD SUCCESSFUL（8m11s）。两者均使用仓库原门禁，未移除用例或降低断言。

PR 门禁 Python：Core/安装卸载/门禁 686 passed、1 skipped、6 subtests；Hermes 143 passed；通信测量器 25 passed。DSH 37 passed。唯一跳过项为上文说明的环境符号链接场景。

| Android 模块 | 测试数 | failures/errors/skipped |
| --- | ---: | --- |
| app | 1399 | 0/0/0 |
| gateway-client | 365 | 0/0/0 |
| control-ports | 38 | 0/0/0 |
| capability-ports | 51 | 0/0/0 |
| screen-control | 28 | 0/0/0 |
| device-primitives | 55 | 0/0/0 |
| platform-kernel | 83 | 0/0/0 |
| conversation-domain | 34 | 0/0/0 |
| conversation-ui | 684 | 0/0/0 |

所有 Android 数字来自最终 JUnit XML；构建输出包含已有弃用/资源关闭警告，最终 Gradle 退出码为 0。自动化、模拟器预检、真实设备及线上部署是不同证据层级；当前没有登录后的真机或模拟器应用验收结论。未部署到运行中的 Core，未执行 Core 重启或代理故障注入。

提交与远端 CI 状态以对应 PR 页面和最终交付报告为准，本文件记录本地准入验证。
