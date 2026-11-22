# 安卓虚拟主机离线排查

关联 #220。基线 `d11e8f29`。官方浏览器回跳和自部署密码登录均进入 `RemoteAccountRuntime.loginAuthorized`，用当前账号授权分别注册 Client 和 Host。本机设备过滤发生在 `listHosts()` 返回手机界面之前。扫码配对仅授权 Client。

基线受控测试让两个角色完成 `hello.ack`，再由测试服务以 WebSocket 1012 关闭 Host 连接。保持 Client 在线并触发前台刷新后，服务端累计收到一次 Host `hello`，断言期望两次。`startHost()` 在收到关闭通知后只发布 `FAILED`，前台和网络恢复入口仅重启 Client。这个缺陷足以使曾经上线的手机持续离线。

本机电脑 Host 日志中缺少对应手机的连接记录，排查时 ADB 设备列表为空。实际账号首次注册、真机待机时长及通知、屏幕和触控仍需设备端证据。
