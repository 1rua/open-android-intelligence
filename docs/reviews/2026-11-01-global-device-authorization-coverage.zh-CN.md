# 全局设备授权需求覆盖与证据索引

日期：2026-11-01。状态：整合验证中；此表是逐项核对入口，不是全部验收通过声明。
完整规格见[全局授权](../superpowers/specs/2026-11-01-global-device-authorization.md)，
执行结果见[主记录](2026-11-01-global-device-authorization.zh-CN.md)，
运行结果见[Terra 报告](2026-11-01-global-device-authorization-terra.zh-CN.md)。

| 需求 | 生产实现入口 | 自动化证据入口 | 当前运行证据 |
| --- | --- | --- | --- |
| GLOBAL-01 全局策略 | `PairingGrantStateHolder`、`SharedPreferencesPairingGrantStore`；策略键不含Core/账号 | `CapabilityAuthorizationTest`、`PairingGrantPersistenceTest` | 模拟器切Core/账号待测 |
| GLOBAL-02 历史清理 | 同一SharedPreferences事务移除旧`pairing_grant_v1.*`和旧scope标记，提升本地版本；Core保留历史fence | 迁移幂等、提交失败恢复、来源不明默认手动、无关键保留测试 | 真实旧安装升级待测 |
| GLOBAL-03 三级模式 | `ProductionPluginHost`、`PluginKernel.authorizeInvocation`、每原语和返回前复核 | `ClientCapabilityAuthorizationTest`、`DeviceGrantFenceTest` | UI和CLI完整链路待测 |
| GLOBAL-04 确认与通知 | `DeviceExecutionDriver.confirm`、`ScreenControlConfirmationDialog`、300秒等待期限 | UI选项、确认进度、取消及拒绝测试 | 系统通知和真实超时待测 |
| GLOBAL-05 临时许可 | `CapabilityAuthorizationController`单调时间内存票据；单次绑连接/请求，限时按范围共享 | 到期/取消/重启、同rawId跨Core单次不共享、timed共享 | 5/30/60分钟设备运行待测 |
| GLOBAL-06 独立推送 | `DevicePushRuntime`、`NotificationPushSource`、`SmsPushSource`；独立配置/来源/正文 | push runtime、collector及source测试 | 真实OS事件与被动MANUAL独立待测 |
| GLOBAL-07 端点白名单 | `endpoints.py`、`GatewayPushTransport`、`PushSettingsScreen` | ownership、route/代次、旧事件不转新代次、缺席保留配置 | CLI/MCP多端点实际消费待测 |
| GLOBAL-08 队列与ACK | `EncryptedPushStateStore`、逐端状态、精确撤销账本；Core60秒密文暂存 | runtime/codec/endpoint/revoke顺序/ACK幂等测试 | 断网/补发/到期/撤销待测 |
| GLOBAL-09 生命周期 | `DevicePushComposition`、`DevicePushJobService`、登录门槛、有界任务与有效期维护 | logout取消、缺权限采集拒绝、恢复只补报一次 | 后台/息屏/回收/全退出待测 |
| GLOBAL-10 屏幕独立与FIFO | `RemoteScreenRuntime`按请求注入、`ScreenControlLeaseManager`独占30秒/累计5分钟 | 读控制隔离、48并发互斥、FIFO/撤销/期限/多连接测试 | Accessibility/MediaProjection系统交互待测 |
| GLOBAL-11 统一设备调用 | `client/cli/mcp/device_service`caller声明、签名pending列表/HTTP短租约、具体错误 | Core主套件、CLI/MCP、账号隔离、pending18项 | 两Core合法请求与未知调用者拒绝待测 |
| GLOBAL-12 Terra验收 | 指定`gpt-6.1-terra`子Agent；user10隔离模拟器、双TLS Core | 测试环境准备与签名包校验 | 无实体手机，物理验收BLOCKED；模拟器矩阵执行中 |
| GLOBAL-13 文档与PR | spec、CONTEXT、ADR、协议、指南、Skill、Android说明及本索引 | 相对链接/代码围栏/格式检查；Fast/PR已退出0，源码提交后记录SHA | 准备创建dev Review PR，验收缺口保持公开 |

## 保留边界

- Android系统权限、通知监听、无障碍、MediaProjection和系统强制同意独立生效，自动模式不代替系统批准。
- 设备访问关系通过可信账号/设备/配对代次与凭据验证；Agent名称和目的不作为身份，Session Adapter不成为授权前置。
- 设备全局策略不授予其他账号私有会话、历史和附件。附加设备连接只取专用pending ID，不加载会话快照。
- 审计写失败先保存拒绝屏障；中断恢复只回到手动模式并保留明确off及数据范围，不回滚旧allow。
- 取消后敏感成功结果在App和Core两端重新核验；Core原子处理避免GET与POST之间的取消竞态。
- Core撤回tombstone保留最长期限，撤回先于迟到投递时也不重新保存正文；手机撤回记录只含事件/路由元数据。
- 长期敏感队列只在Android；Core短暂暂存60秒后等待同ID重投，消费ACK与HTTP接受分开。
- 没有实际运行的项目保持待测；实体手机缺失和系统交互限制不由单测或模拟器代替。
