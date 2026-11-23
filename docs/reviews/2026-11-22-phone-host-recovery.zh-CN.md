# 安卓虚拟主机离线排查

关联 #220。基线 `d11e8f29`。官方浏览器回跳和自部署密码登录均进入 `RemoteAccountRuntime.loginAuthorized`，用当前账号授权分别注册 Client 和 Host。本机设备过滤发生在 `listHosts()` 返回手机界面之前。扫码配对仅授权 Client。

基线受控测试让两个角色完成 `hello.ack`，再由测试服务以 WebSocket 1012 关闭 Host 连接。保持 Client 在线并触发前台刷新后，服务端累计收到一次 Host `hello`，断言期望两次。`startHost()` 在收到关闭通知后只发布 `FAILED`，前台和网络恢复入口仅重启 Client。这个缺陷足以使曾经上线的手机持续离线。

最初排查时没有连接物理手机。本次运行使用自部署测试账号、独立 Linux Client 和隔离 API 35 模拟器。账号注册层测试分别覆盖官方浏览器回跳交来的账号票据和自部署密码登录。官方账号的实际授权回跳与厂商真机长时间休眠仍需实测。

## 定位与修复

- `RemoteHostControl` 按当前账号与 Host 角色维护控制连接，以 `hello.ack` 作为上线依据。服务重启、网络中断或心跳超时后，保持任务重新取 Host 设备票据并建立新的控制连接。设备撤销、凭据重复使用或连接被替代时，手机把终态按本次登录代次保存在加密凭据仓库，直到显式重新登录。账号错误沿原登录通道返回，角色错误进入脱敏日志。
- 账号注册携带安装级 Host ID、已保存设备名、`role=host`、`platform=android`，以及 Remote `0.6.5`、Harness `0.2.2-rc.1` 兼容声明。新安装默认读取系统设备名，缺省时取厂商与型号。
- Host 已在线时，握手还要经 HTTP 查询来访设备的注册身份。固定服务关闭空闲 HTTP 连接后，用项目的 OkHttp 4.12.0 可复现下一次 GET 失败。模拟器也在 `PEER_AUTHORIZATION CONNECTION_FAILED` 同一时间记录了 HTTP GET 的 I/O 错误。本次只为 GET 启用 OkHttp 自带的连接恢复，写入保持单次提交。
- 手机门面在固定上游桌面客户端发送带 `clientTimeZone` 的 `session/prompt` 时曾返回 `CONTROL_PARAMETERS_INVALID`。现按上游格式接收时区，业务授权与请求去重继续使用原来的账号、会话与 `request_id`。
- 用户在可见 App 发起账号登录时，手机可以提前启动带系统常驻通知的待命服务。注册完成后，服务核对当前账号与 Host 票据，再保持出站信令。普通退后台继续待命，系统重建服务、开机解锁或覆盖安装本应用时重新核对加密授权。登录失败、退出或 Host 授权终止时，服务结束。
- 在受控回归中，服务端撤销 Host 并关闭连接的同时发送心跳，原实现将撤销报成普通断线并尝试重连。现在同一连接的读取、发送和心跳共用首次关闭原因。两个角色各自保存授权终态，显式重登后开始新代次。
- API 35 上，本机确认浮层的窗口状态事件会在窗口已从系统列表移除之后到达。同一次更新也可能重复发出没有包名的窗口事件。现在按当前或刚卸下的本机浮层 ID 与快照目标判定，真实目标窗口的内容、焦点或几何变化仍使旧快照失效。
- 日志可按 `OAI-Virtual-Host` 过滤。`REGISTERED` 表示已取得 Host 票据，`ONLINE` 表示服务器已确认控制连接，`HEARTBEAT_OBSERVED` 表示本次连接已交换心跳，`PEER_AUTHENTICATED` 表示来访设备已完成加密握手。故障记录阶段和固定错误码。

## 控机验证入口

电脑客户端带同账号 Client 凭据读取 `/api/v1/devices` 与 `/presence`，从在线的 Android Host 进入 `harness/phone-capabilities`。`control-bus` 提供能力目录与回执，`remote_create_session` 为多步操作创建独立任务。`remote_send_message` 将请求送入手机的会话门面和 `PhoneControlBus`。通知读取经本机授权、签名 WASM 插件与系统通知提供者，读屏与触控经原生屏幕内核。执行结果按原请求 ID 从会话记录读取。

固定桌面客户端 `0.6.5` 的 `withToolRemote` 在桌面已选中目标手机时复用当前加密连接。需要本机确认的多步控制应先在电脑 Remote 选中这台手机，再发起任务。未选中时，工具在单次调用返回后关闭临时连接。在隔离模拟器上，桌面发出的读屏请求先返回 `accepted`，关闭连接后用 `control-bus` 查询原 `request_id` 得到 `cancelled`、`not_started` 和 `REMOTE_CONNECTION_CLOSED`。过去 `remote_read_session` 仍显示运行中。修复后手机以账本的最小回执关闭原轮次，桌面可以查询取消或结果未知的原因。同一请求 ID 仍受原有防重规则约束。

## 设备验收记录

2026-11-22，独立 Linux Client 通过固定 `ds-harness-remote 0.6.5` 从自部署服务发现 `sdk_gphone64_x86_64`，取到 `backend=harness` 的 `phone-capabilities` 与 `control-bus`，并通过 `remote_create_session` 创建独立任务。覆盖安装后 Host ID 保持一致。

在 API 35 模拟器上，Android 报告服务为前台，通知渠道 `remote-host-standby`。熄屏 141 秒，桌面每约 20 秒查询一次，8 次均为在线。正常重启模拟器后，在桌面启动器保持前台的情况下，系统以新进程恢复了同一 Host 和前台服务，桌面重新创建控机会话并收到 Android 的读屏结果。实际离线时长不以设备目录的缓存时间推算。

2026-11-23，独立 Linux Client 用 `plugin.invoke` 读取通知。桌面得到 `completed/succeeded`，其中包含测试应用 `local.oai222.notifier` 的来源和标题「OAI222 验收通知」。正文为 Android 返回的 `Sensitive notification content hidden`。未允许通知来源时，手机在同一条链路返回 `PACKAGE_NOT_ALLOWED`。

同日，原生 Linux Client 在同一 `harness/phone-capabilities` 任务中执行读树、点击、再次读树。测试应用的按钮读数从「点击计数 0」变为「点击计数 1」。本机确认后，`ui.tap` 请求 `566f048e-001e-4010-85b1-f86ecb994ec4` 得到 `completed`、`succeeded`、`performed=true`。较长的完整回执经 `remote_download_file` 读回，并与手机声明的长度及 SHA-256 一致。单独执行的 `ui.capture_som` 也经本机确认，桌面下载并解码了 1080×1920 的 WebP，41,172 字节，SHA-256 为 `be4af8b14c45119ccaaa9eccd1bc289d09c67249b056397770a6f264607b560d`。测试期间遇到系统栏内容实际变化时，旧快照返回 `SNAPSHOT_STALE`，执行状态为 `not_started`。最终候选还覆盖了本机批准后执行浮层正在布局的时序，屏幕端等本机窗口保护范围就绪后复核目标。第二次实测同一条官方链路返回 `performed=true`，计数由 1 变为 2，请求 ID 为 `fc05d883-a800-4a4c-b1e6-9c3846d2e11c`。

在后台覆盖安装的单独验证中，旧包能显示在线，但系统服务列表中缺少待命服务。修复后，在熄屏状态覆盖安装，Android 通过 `MY_PACKAGE_REPLACED` 恢复 `RemoteHostStandbyService`，系统记录 `isForeground=true`、通知 ID `2402`。此恢复入口只核对现有 Host 授权。此后 161 秒内的 9 次独立采样，桌面均报告同一 Host 在线，安卓同时报告熄屏和前台待命服务运行。
