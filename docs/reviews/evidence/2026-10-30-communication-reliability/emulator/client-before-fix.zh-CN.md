# 模拟器首次 App 输入的阶段证据

原 APK：业务提交 `dda2a4dc8ece02289b3e4f54dab11f8515249707`，SHA-256 `1afbb55747cd0d38e147dd50e3b6efc1fa129762fae1532feffa497729972a99`。Android 15 / API 35、专用 `emulator-5554`。连接真实隔离 Core、Hermes HTTP Adapter、SQLite 和原生历史，执行器为合成宿主。

在原 APK 重开进程后的一次发送操作中，按原始 `clientMessageId` 关联诊断得到：

| 阶段 | 已观察结果 |
| --- | --- |
| App 点击发送 | `send.clicked` 已记录 |
| 客户端界面应用 | `T9.compose_applied` 已记录 |
| 客户端画面提交 | `T10.frame_committed` 已记录 |
| 仓储安排发送 | 未见 `send.scheduled` |
| 客户端进入 HTTP 发送 | 未见 `T1.request_started` |
| Core 消息 POST | 0 次 |
| Adapter 消息 POST | 0 次 |
| 宿主开始执行 | 0 次 |

该样本说明消息的本地展示先于实际网络提交，停滞在客户端路径；不能用发送气泡推测宿主已经接收，也不能将本例解释为 SSE 传输缓慢。仍需通过创建与保存阶段诊断定位具体阻塞点，不能据单个样本解释全部现场症状。

原始日志、截图和完整身份核对 JSON 仅本地留存，按用户要求不提交。未经校准的 Android、Core 和 Adapter 时间戳没有直接相减；本记录不提供生产延迟分位数。
