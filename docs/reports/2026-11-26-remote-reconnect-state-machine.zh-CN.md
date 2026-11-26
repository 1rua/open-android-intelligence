# Remote 断线自动重连状态机实现报告

关联 Issue：#244

## 问题

Server（Relay/Control 服务或远端 Host）断开并重新上线后，Remote Client 可能停留在断连或离线状态，
必须冷启动 App 才能恢复。该问题同时影响 OAI-Remote-Client 的控制面、当前 Agent 的数据面，以及
OAI-Virtual-Host 的在线注册与心跳恢复。

## 实现范围

- 为 Client、Virtual Host 与当前数据面建立独立、可恢复的重连生命周期。
- 使用带抖动的指数退避，并支持网络恢复、前台恢复、Host Presence 上线和手动刷新即时唤醒。
- 每次恢复完整退役旧 WebSocket、Relay/WebRTC、Noise 与 connectionId，再执行全新握手。
- 数据面恢复后保留当前工作台，重新读取目录、工作区与当前会话时间线。

## 验证

实现与验证完成后，在此记录定向测试、Fast/PR 门禁、独立评审结论以及真机或部署验收边界。
