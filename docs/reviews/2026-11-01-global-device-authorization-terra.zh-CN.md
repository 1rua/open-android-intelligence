# Terra Android 全局授权运行验收

日期：2026-11-01。状态：待环境预检；尚无本次运行通过结论。
执行模型：`gpt-6.1-terra`；必须以 Android CLI/ADB 与 OAI CLI/MCP 实际调用验证。
矩阵依据[规格第十章](../superpowers/specs/2026-11-01-global-device-authorization.md)。

| 项目 | 状态 | 证据 |
| --- | --- | --- |
| SDK、ADB、设备、App、测试 Core | PENDING | Terra 正在预检 |
| 通话记录、短信、通知系统权限与三级策略 | PENDING | 待样本和新构建 |
| 手动确认、临时授权、撤销、跨 Core/账号/Agent | PENDING | 待实现 |
| 推送白名单、加密离线队列、ACK、去重、到期 | PENDING | 待实现 |
| 屏幕读取、控制、系统同意和 FIFO 租约 | PENDING | 待实现 |
| 后台、息屏、断网、回收、退出和重新登录 | PENDING | 待实现 |

每项补充命令、Android/App/Core 版本、步骤、结果、脱敏截图及日志路径；未具备条件记 BLOCKED。
模拟器通过和物理设备通过分列，禁止以单测替代。
