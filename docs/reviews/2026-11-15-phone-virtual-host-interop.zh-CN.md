# 手机虚拟 Host 与官方桌面互通

关联 issue #195、PR #190。本文区分已复现的协议问题、受控修复与待实机确认的问题。

## 固定兼容边界

- 手机生产协议 pin 仍为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`。该提交的插件 distribution 为 `0.6.5`。
- 用户实际 Remote `v0.6.5` release 是 `eef232d2c43b0c9d04998a18db98cbc9e673e488`。测试在 pin 基础上逐字节加载该 release 的七份差异源码；原插件 `ClientSecureTransport`、能力探测、`RemoteTypertGateway`、`RemoteSessionTools` 与 pin 相同。未维护上游分叉，未升级生产 pin。
- 官方桌面消费者锁为 npm `@deepseek-ai/dsh-client-connection`、`dsh-api-gateway`、`dsh-api-session-controller` 的 `0.2.2-rc.1`，来源清单保留 npm tarball integrity、文件 SHA256 与 MIT 许可；探针运行原始消费者，不复制其解析逻辑。
- `clientVersion=0.6.5` 表示所实现 Remote 协议兼容基线。`harnessVersion=0.2.2-rc.1` 是**本项目定义的虚拟 Host Typert 协议兼容声明**，并非上游字段原本的已安装 Harness distribution 含义，亦不表示手机安装或运行 Harness 引擎。`harness.transport.describe.implementation` 同时公开 `kind=virtual`、`harnessVersionSource=compatibility`、独立 OAI 实现版本和固定上游 SHA。
- 手机只公布真实实现的 `harness.remote.v3` 和手机工作区；未声明模型、终端、任意文件、Codex/ACP、审批 waterfall 或上传能力。

## 复现与修复

原始 Host 经 Control、Relay、Noise 与 Session 工具可以正常工作，但官方桌面连接控制器会打开 `$events` 作为唯一连接代际来源。原手机没有实现该流，消费者在该阶段打印 `connection lost, retry #1`；单变量补上事件源后同一原消费者通过。

事件源首项严格为 `ready/clientId/host.home`，后续从真实手机执行总线产生会话新增、状态与移除通知。订阅取消和连接关闭移除归属；旧代际及不同设备不可回传结果。手机敏感确认仍由本机权限门处理；`$events/result` 只校验归属和官方结果格式，未知事件按上游行为返回幂等空结果，不能授予手机动作权限。

`session/control` 原先错误返回会话数组，官方结果解析器要求以 sessionId 为键的投影映射。现返回每会话真实 `asOfSeq/values`；`inbox.next-step` 明确为空，因为手机执行总线只支持任务排队，没有 steering 步输入队列。

WebRTC 的 `PeerConnection.dispose()` 官方说明禁止在 observer 回调中同步调用。原 ICE FAILED/CLOSED 路径同步 dispose，信令回调也可能把控制连接关闭异常抛出 JNI 边界。现通过单独清理执行器释放原生资源，并用生命周期门使 DataChannel 接收发布与关闭互斥；错误结束等待者和该载体，不留下可用假状态。

## 验证与限制

`OriginalPluginHostInteropTest` 使用真实固定 Server、官方 Remote `0.6.5` Noise initiator、Android Host responder、真实门面和总线，以及官方 Cordis/连接/事件/SessionControl 消费者。只有系统采集器和测试释放指令隔离；后者不进入生产 Host。

当前需要执行的冻结批：`OriginalPluginHostInteropTest`、`RemoteRtcCallbackBoundaryTest`、`PhoneRemoteEventSourceTest`、来源完整性及 CI 路由回归。执行结果以当前冻结源码和主 Agent 汇总为准。

用户还报告连接时 App 闪退，服务端显示 `dotnet-experimental`。上述受控复现证明桌面重试原因和官方禁止的原生清理路径，不等于已证明用户进程崩溃根因。该 .NET 服务的公开实现尚未找到，也未获 Android fatal 堆栈；未进行真实账户、ADB、模拟器或电脑 Host 变动。P2P/TURN、实机原生库、该 .NET 服务真实链路均待独立验收。
