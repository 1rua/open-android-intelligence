# Hermes 真实回调与审批卡片修复

日期：2026-10-28

基线：`dev@be651108d2d17911ceb1159ef8051b218d57c9c7`

审批卡 UI 参照：`main@d88fffed134f57c5cf6ca4733e65b61f6e13120d`

## 现场证据与边界

用户提供的 [开发版构建](https://github.com/1rua/open-android-intelligence/actions/runs/40593809618/job/122504789268)
检出了上述基线，产物版本为 `2.2.1-dev`、版本码 `5771`。因此不能把截图归因为
“手机仍在使用此前版本”。APK 构建成功不证明 Hermes 服务进程也加载了同一提交。

截图中的“审批卡不可用，命令尚未执行”由 App 收到 `UNSUPPORTED_APPROVAL` 后显示。
当前适配器仅在内部收到 `core.approval.unsupported` 时发送此错误并结束回复流；
当前生产源码中已没有该内部事件的生产者。没有同一时间窗口的服务日志与进程重载
证据，不能确认是哪一个部署组件产生了截图错误。

本机磁盘安装的 Core、Session Adapter 分别指向仓库里的源码；安装的 Hermes SDK
提交为 `75b083e9399115b12513385252811f09c3503c90`，与固定测试 SDK 相同。
磁盘插件入口也已转交给 `oai_hermes_adapter.register`，不能凭安装目录的旧名称判断
它仍在使用旧 Gateway。当前环境无法连接用户服务管理器，未确认运行中服务的启动
时间；现有日志没有匹配到截图同窗的审批失败事件，窗口覆盖也不完整。没有重启或
变更现有部署、账号、配对或历史数据库。

## 已复现的源码缺陷

### 宿主脱敏后无法恢复对应审批请求

真实 Hermes 的调用顺序为：

```text
TurnRunner._approval_notify_sync
  → 脱敏待显示命令，不传 request_id
  → BasePlatformAdapter.send_exec_approval
  → NativeApprovalMixin._send_exec_approval_prompt
  → 从宿主待审批队列恢复 request_id
```

队列保存原始命令，回调携带的是脱敏后的显示命令。原实现直接比较这两种文本，
当同一宿主会话有多个等待项时，可能得到零个匹配，退回普通文本提示。
新回归直接调用固定 SDK 的上述链路，修复前得到 `2 failed, 2 passed`，
失败分支为 `2 pending, 0 command matches` 与 `NATIVE_APPROVAL_UNAVAILABLE`。

现在使用 Hermes 自己的 `redact_sensitive_text(..., force=True)` 比较。原文相等和
脱敏后相等的项目放入同一候选集合，只有唯一命中才能恢复句柄。不同原始命令脱敏后
相同、或另一条命令的原文恰好等于脱敏显示文字，都保留为歧义，继续使用宿主文本提示。
手机收到的命令仍是宿主已经脱敏的显示文字，不会把队列里的敏感原文转发给手机。

测试只把命令作为显示字符串，不运行模型或命令。普通 `systemctl` 显示字符串也覆盖
真实回调、卡片落库及决定回传。此缺陷尚不能认定为截图的唯一原因。

### 保留 main 审批卡 UI，按宿主内容与选项展示

按用户最终要求，以 main 中已有的审批卡为视觉基准，保留卡片、命令区域、按钮布局、
倒计时与提交反馈。dev 既有的不可提交文本兜底继续保留，不影响正常结构化卡片。

命令从宿主回调经 Core、App 数据层传给原卡片，不做客户端改写，也不从宿主原始队列
重新拼接。Hermes 自己已经处理过的显示内容保持原样。操作的标签、样式与顺序来自
宿主实际返回的 `options`，不会固定补齐四种授权范围：

| 宿主 choice | 对应操作 |
| --- | --- |
| `once` | 允许一次 |
| `deny` | 拒绝 |
| `session` | 本次会话允许 |
| `always` | 添加到白名单 |

宿主提供的标签优先展示；缺少标签时使用上述中文文案。状态机提交前再次检查操作
是否属于该卡片的 `options`。宿主只给 `once/deny`，卡片就只有这两项；未返回白名单
选项时不会出现白名单按钮。

真实 SDK 回归额外覆盖三个受限选项组合。当前固定 SDK 在 `allow_session=False` 时
也不提供 `always`，即使 `allow_permanent=True`，客户端仍必须按宿主实际结果展示。
UI 测试检查各按钮分别回传原 `choice`、缺项不补齐，以及命令文本原样保留。

## 验证

以下测试覆盖真实 SDK 的回调、队列与 resolver，只控制消息出口和测试账号数据：

```sh
HERMES_SDK_ROOT=.toolchains/hermes-native-sdk python3 -m pytest \
  oai-core/tests/test_hermes_approval_prompts.py \
  oai-core/tests/test_hermes_interactions.py \
  oai-core/tests/test_interaction_pipeline.py \
  oai-core/tests/test_native_interactions_core.py -q
```

验证包含 `once/session/always/deny`、宿主受限操作集合、重复决定幂等、只唤醒选中的
等待项、脱敏碰撞、无法关联时纯文本降级，且降级不会自动批准或拒绝。

本次新增的真实 SDK 回调场景：`13 passed`，逐字段检查命令与选项是否等于宿主实际
发送的 prompt。已有 Hermes 原生会话合同回归：`19 passed`。

最终本地验证：

| 验证范围 | 结果 |
| --- | --- |
| `npm run test:fast` | 通过：依赖锁 8 项、Core 281 项、会话模型 32 项、会话 UI 626 项、无 VPN 表面检查 |
| `npm run test:pr` | 通过；Android 复用已通过的同一源码增量结果 |
| 真实 SDK 回调、审批交互、HTTP 桥与 Core 审批集成 | 42 项通过，包含新增的 13 个场景 |
| Hermes 原生会话与平台合同 | 19 项通过 |
| 审批卡组件与状态机 | 26 项通过，包含在上述 UI 测试中 |
| UI 源码基线比对 | 对任务基线仅有两处白名单文案调整，普通卡布局与 main 一致 |
| `git diff --check` | 通过 |

新回归明确加入 Hermes CI，并从只安装 Core 的独立测试阶段排除，保持可选宿主边界。
本次不改变协议 Schema、账号身份、密钥或数据库格式。

最终本地门禁结果与远端 CI 链接记录在提交对应的 PR；真实 Samsung 设备、实际模型
服务及运行中部署的完整验收仍须在使用新 App 和新适配器的环境中完成。
