# 安卓虚拟主机入站 WebRTC 死锁

2026 年 11 月 28 日，已授权的 Note20 Ultra 能被 DSH `v0.6.5` 发现。默认选路为 LAN 时，桌面发出 Noise IK 第 1 帧，约 10 秒后仍未收到第 2 帧。同一手机走 Relay 时完成握手并返回 `harness / phone-capabilities`。

真机线程栈显示，处理远端 ICE 候选的线程在持有生命周期锁时调用了原生 `addIceCandidate`，同时 WebRTC 的 `onDataChannel` 回调等待同一把锁。手机无法发布数据通道就绪，也就无法继续 Noise 握手。参见 [WebRTC Peer](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/network/RemoteRtcPeer.kt) 和 [Android 连接层](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/remote/network/RemoteSignalService.kt#L372)。

生命周期锁只登记准入和在途操作。接收回调按到达顺序将通道交给应用线程注册观察者，关闭时立即唤醒等待者。每条连接独立排队清理，等在途操作退出并释放数据通道，再释放 PeerConnection 和工厂。回归测试模拟了原生调用等待另一线程回调，并覆盖关闭期间的通道交接。参见 [生命周期回归测试](../../apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/remote/network/RemoteRtcCallbackBoundaryTest.kt)。

首轮候选 APK 在真机完成了数据通道打开，仍缺少手机收到 Noise 首帧的记录。上游固定源码 `packages/plugin/src/server-connection.ts:1087` 会在 Responder 数据通道打开后发送 `transport.selected`。安卓优先通告本机统计确认的路径。统计尚不可用时，通道打开后确认 Client 选定的路径。数据面始终按 Client 的选择绑定。第二轮候选在同一台真机恢复了 Noise 第 2 帧和工作区发现。

真机验收使用与 PR 提交一致的 APK 覆盖更新。用本机 DSH 建立三次独立 LAN 连接及一次 Relay 连接，每次通过 `remote_list_workspaces` 取得 `harness / phone-capabilities`，向 `control-bus` 发送 `phone.capabilities`，按请求 ID 核对 `completed` 和能力列表。验收结果记录在 PR #257。
