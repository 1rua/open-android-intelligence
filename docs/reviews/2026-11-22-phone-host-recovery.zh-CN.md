# 安卓虚拟主机离线排查

关联 #220。基线 `d11e8f29`。官方浏览器回跳和自部署密码登录均进入 `RemoteAccountRuntime.loginAuthorized`，用当前账号授权分别注册 Client 和 Host。本机设备过滤发生在 `listHosts()` 返回手机界面之前。扫码配对仅授权 Client。

基线受控测试让两个角色完成 `hello.ack`，再由测试服务以 WebSocket 1012 关闭 Host 连接。保持 Client 在线并触发前台刷新后，服务端累计收到一次 Host `hello`，断言期望两次。`startHost()` 在收到关闭通知后只发布 `FAILED`，前台和网络恢复入口仅重启 Client。这个缺陷足以使曾经上线的手机持续离线。

本机电脑 Host 日志中缺少对应手机的连接记录，排查时 ADB 设备列表为空。实际账号首次注册、真机待机时长及通知、屏幕和触控仍需设备端证据。

## 定位与修复

- `RemoteHostControl` 按当前账号与 Host 角色维护控制连接，以 `hello.ack` 作为上线依据。服务重启、网络中断或心跳超时后，保持任务重新取 Host 设备票据并建立新的控制连接。设备撤销或被替代时保留终态，登录界面与诊断页分别报告账号注册和角色故障。
- 账号注册携带安装级 Host ID、已保存设备名、`role=host`、`platform=android`，以及 Remote `0.6.5`、Harness `0.2.2-rc.1` 兼容声明。新安装默认读取系统设备名，缺省时取厂商与型号。
- 手机门面在固定上游桌面客户端发送带 `clientTimeZone` 的 `session/prompt` 时曾返回 `CONTROL_PARAMETERS_INVALID`。现按上游格式接收时区，业务授权与请求去重继续使用原来的账号、会话与 `request_id`。
- 日志可按 `OAI-Virtual-Host` 过滤。`REGISTERED` 表示已取得 Host 票据，`ONLINE` 表示服务器已确认控制连接，`HEARTBEAT_OBSERVED` 表示本次连接已交换心跳，`PEER_AUTHENTICATED` 表示来访设备已完成加密握手。故障记录阶段和固定错误码。

## 控机验证入口

电脑客户端带同账号 Client 凭据读取 `/api/v1/devices` 与 `/presence`，从在线的 Android Host 进入 `harness/phone-capabilities`。`control-bus` 提供能力目录与回执，`remote_create_session` 为多步操作创建独立任务。`remote_send_message` 将请求送入手机的会话门面和 `PhoneControlBus`。通知读取经本机授权、签名 WASM 插件与系统通知提供者，读屏与触控经原生屏幕内核。执行结果按原请求 ID 从会话记录读取。
