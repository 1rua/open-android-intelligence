# 手机虚拟 Host 与官方桌面互通

关联 issue #195、PR #190。本轮处理手机设备列表错误离线、Client 控制面未常驻、重复安装身份及官方桌面反复重连。本文区分已复现的协议问题、隔离环境验证和待实机确认的问题；迁移总体范围仍以[验收索引](2026-11-14-dsh-remote-acceptance-index.zh-CN.md)为准。

## 固定兼容边界

- 手机生产协议 pin 仍为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，该提交的插件 distribution 为 `0.6.5`。
- Remote `v0.6.5` release 固定为 `eef232d2c43b0c9d04998a18db98cbc9e673e488`。互通探针在 pin 基础上逐字节加载 release 的七份差异源码；原插件 `ClientSecureTransport`、能力探测、`RemoteTypertGateway`、`RemoteSessionTools` 与 pin 相同。生产 pin 未升级，不维护上游分叉。
- 官方桌面消费者锁为 npm `@deepseek-ai/dsh-client-connection`、`@deepseek-ai/dsh-api-gateway`、`@deepseek-ai/dsh-api-session-controller` 的 `0.2.2-rc.1`，运行原始连接、事件和 SessionControl 消费者，不复制其解析逻辑。来源清单记录 npm tarball integrity、逐文件 SHA256 和官方仓库 `deepseek-ai/deepseek-harness@39163dd30f703a79079e2d2446545cff3c453738` 的 MIT 许可。
- pin、release 差异和官方桌面消费者来源分别见 `tools/remote-interop/fixtures/source.json`、`tools/remote-interop/release-fixtures/source.json`、`tools/remote-interop/harness-fixtures/source.json`；门禁核对原文及许可完整性。
- `clientVersion=0.6.5` 表示本项目实现的 Remote 协议兼容基线。`harnessVersion=0.2.2-rc.1` 是**本项目定义的虚拟 Host Typert 协议兼容声明**，不同于上游字段原本的已安装 Harness distribution 含义，也不表示手机安装或运行 Harness 引擎。`harness.transport.describe.implementation` 同时公开 `kind=virtual`、`harnessVersionSource=compatibility`、独立 OAI 实现版本和固定上游 SHA。
- 手机只公布真实实现的 `harness.remote.v3` 和手机工作区；未声明模型、终端、任意文件、Codex/ACP、审批 waterfall 或上传能力。

## 复现与修复

### 目录与账号控制面

固定上游 `packages/plugin/src/client-runtime.ts` 的 `devices()` 单独查询 `/api/v1/devices/{deviceId}/presence`；`server-api.ts` 允许目录项省略 `online`。原手机只读目录 `online` 并默认 `false`，因此上游测试中不含该字段的真实 `host(0)` 目录会被误判离线。现按上游方式以八并发查询独立 presence；单项失败只影响对应 Host，并记录固定错误码。离线是保守可达性结果，不能据此认定账号授权失效。

登录、恢复和配对导入后即建立 Client 控制连接，收到真实 `hello.ack` 才显示已连接；保持任务按服务器 `heartbeatIntervalMs` 发心跳并刷新 presence，回到前台或网络恢复时另触发刷新。上游没有设备 presence 推送帧，本次未新增线协议。工作区选择和业务隧道独立于账号控制面，关闭工作区不会关闭已认证 Client。断线重建认证与传输，不重放旧业务写入。

`connect.accepted` 不含 Host 身份，取消尚未完成的选择须重建控制连接，拒绝旧回包借用新目标。每条隧道固定持有所属连接结果，旧连接清理与迟到认证不能结束新选择；目录结果发布也核对活动账号及授权代际。退出后停止保持任务、关闭两角色连接并清空在线投影，不因后台重连重新登录。

### 安装身份、名称与本机过滤

Client 和 Virtual Host 的独立 deviceId、静态密钥及永久设备名保存在安装级加密存储，重启和退出再登录保持稳定。账号 token、对端 pin、活动授权仍按服务 origin、账号和角色隔离，退出时清理，不能随安装身份跨账号继承。

升级仅接纳当前已有、可读取的两角色身份；退出先保住原安装密钥，再撤销账号。密文、Keystore 或初始化记录损坏时失败关闭，不生成新身份掩盖损坏，也不删除唯一旧密钥。设备名默认来自系统名称，回退到厂商及型号；改名在下次设备注册生效。列表只按安装 Host 和当前账号 Host 的精确 ID 过滤本机，其他同名手机仍可见。已经丢失私钥的旧匿名 deviceId 无法安全认领或自动合并。

### 官方桌面事件与会话投影

原始手机 Host 经 Control、Relay、Noise 与 Session 工具可通过探针，但该路径没有执行桌面 `$events`。官方连接控制器将 `$events` 的首项作为连接代际来源；原手机缺少该流，原消费者打印 `connection lost, retry #1`。在同一原消费者链上单变量补上事件源后通过，因此工具层通过不能替代桌面连接入口验收。

事件源首项严格为 `ready/clientId/host.home`，后续会话新增、状态和移除来自真实手机执行总线。取消订阅或关闭连接会移除归属；旧代际、另一设备不能回传结果。`$events/result` 只校验所属订阅及官方结果格式，未知事件按上游返回幂等空结果，不能授予手机动作权限，本机敏感确认仍由手机权限门处理。

`session/control` 原先返回会话数组，官方解析器要求以 sessionId 为键的投影映射，现返回真实 `asOfSeq/values`。`inbox.next-step` 明确为空：手机执行总线支持任务排队，不提供 steering 步输入队列，不用假状态宣称支持。

### WebRTC 回调与失败边界

固定依赖为 Maven Central 的 `io.github.webrtc-sdk:android:150.7871.04`，静态来源与许可见[原生依赖说明](2026-11-14-dsh-remote-native-dependencies.zh-CN.md)。官方 `PeerConnection.dispose()` 要求避免在 observer 回调内同步调用。原 ICE FAILED/CLOSED 路径同步 dispose，信令回调还可能把控制连接关闭异常抛出 JNI 边界。

现将原生资源释放排队到单独清理执行器，生命周期门使 DataChannel 接收、发布和关闭互斥，清理仅执行一次。同步创建失败保留原异常；等待者立即失败，关闭后拒绝继续创建或发布可用载体，不吞异常伪造连接成功。这些回归验证回调边界，不能证明所有 ABI、实机 WebRTC 或用户进程崩溃已修复。

## 验证与限制

最终本轮生产源码为 `8a297ab9f9ca0d9d32ccad2c4c6cbb32df89c038`，两项组合门禁均使用 `--since 7215a9d9e5aa281e4182cc5e0ad9b5b91d0e125b`。来源分支的定向批次存在重叠，不相加为唯一总数；历史 `$events` 单变量红绿只用于证明缺口及修复因果，以下以组合源码实际执行结果为准。

| 验证层级 | 状态及证据 |
| --- | --- |
| `npm run test:fast -- --since 7215a9d9e5aa281e4182cc5e0ad9b5b91d0e125b` | VERIFIED：`8a297ab9` 完整退出 0 |
| `npm run test:pr -- --since 7215a9d9e5aa281e4182cc5e0ad9b5b91d0e125b` | VERIFIED：同源码完整退出 0；Gateway 35 类/307 项全部通过，App Full 131 类/1453 项、1450 通过、0 失败/错误、3 项渠道条件跳过 |
| 关键 Gateway 回归 | VERIFIED：同批实际执行 `OriginalPluginHostInteropTest` 1 项、`RemoteRtcCallbackBoundaryTest` 5 项、`PhoneRemoteEventSourceTest` 2 项、安装身份 5 项、退出含冷启动 6 项、Client 生命周期 2 项；不是读取旧缓存 XML |
| 原消费者互通范围 | VERIFIED：经固定 Server、官方 Remote Noise initiator、Android responder、真实门面与执行总线，以及官方连接/事件/SessionControl 消费者；系统采集器隔离，测试释放入口不进入生产 Host |
| 来源与 CI 路由 | VERIFIED：Host 来源分支固定原文/许可回归 9/9，精确 CI 规划 107/107；组合 Fast/PR 也完整退出 0，不将来源分支结果冒充同 SHA 独立重跑 |
| Play 必要补验 | VERIFIED：同源码定向 5 类/26 项，18 通过、0 失败/错误、8 项渠道条件跳过；设备名 2/2、顶栏 5/5，Full 的三项受限渠道负向用例在 Play 实际执行通过 |
| 远端 CI、APK 与真实环境 | UNVERIFIED：本轮新的远端 CI/APK、真实账号、电脑 Host、LAN/P2P/TURN、物理设备及原生库运行另验；旧构建不替代本轮结果 |

Full 的三项条件跳过来自 `BuiltInCapabilitiesTest`、`SmsSendOutcomeHostBusTest`、`SmsSendPluginHostTest` 的受限渠道负向路径，不计作通过；对应三项在 Play 已实际通过。Play 的八项需 `ALLOW_RUNTIME_PLUGINS`，该渠道不装配插件运行时，按条件跳过。Play 命令为 `./gradlew :app:testPlayDebugUnitTest`，用 `--tests` 定向 `*RemoteDeviceNameSettingTest`、`*RemoteRootInteractionTest` 及上述三个类。两渠道重复用例不相加为唯一总数。完整原始日志保留临时环境，不入库。

用户还报告连接时 App 闪退，服务端显示 `dotnet-experimental`。受控复现证明桌面重试原因和官方禁止的原生清理路径，**不能证明用户进程崩溃根因或真实闪退已解决**。该 .NET 服务的公开原实现尚未找到，也没有 Android fatal 堆栈。本轮未启动模拟器、ADB、真实账户或变更电脑 Host；真实服务与设备链路仍待独立验收。PR 合并、worktree 清理和 issue 收尾由用户操作。
