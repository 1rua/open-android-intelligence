# 修复远程 Agent 控制 Android 的后端链路

跟踪 issue #248。核验基线为 `b6cce9c16cd1047fa812d7b5b7ad307660209022`，上游协议固定于 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，桌面安装验收用 `v0.6.5`。

## 复现与范围

JVM 门面的 `workspace/follow` 能返回 `phone-capabilities`，`session/create` 能创建独立任务。固定桌面 `workspace.open` 调用 `workspace/create`，后者在基线上返回 `METHOD_NOT_FOUND`。Host 首次注册暂时失败、握手重复帧与流首帧顺序、历史回执截断、外部桥安装与本机鉴权分别验证。真实部署停在哪一步仍需匹配同一连接、流与原请求回执。

Android 后端保持独立 Client/Host 身份。扫码配对只导入 Client，配对受限身份不能补注册 Host。控机从同账号授权的桌面入口打开指定工作区，保持连接后运行原生 `remote_*` 与 Stdio `android_*`。

## 实施批次

1. 独立实现手机虚拟工作区幂等打开、授权注册接口、角色凭据原子提交、握手重复帧处理、首帧订阅顺序和历史回执预算。每项用定向 Gateway 单测约束；回执溢出经同任务私有产物提供完整 JSON。
2. 在注册接口与凭据提交就绪后补 Host 首次注册恢复。明确账号登录、同一代次、仍有效的 Client、暂态 Host 失败是补偿前提。冷启动待命保留已有 Host 恢复语义。
3. 外部桥按本机 HTTP 准入、包安装、连接与发现、回执查询推进。本机 bearer 限于严格回环路由，桌面通用 Cookie/API Peer 鉴权仍由 DSH 处理。注销、换服、卸载必须让旧桥授权失效。历史缺失按原请求回查 `control-bus`，保留手机终态、权限与不重放语义。
4. 用固定客户端跨进程联测契约，然后用同一候选提交的 Full Debug APK 在专用 API 35 AVD 验收。用户在模拟器上手动登录，App 的本地加密存储保留登录态，复测沿用私有 AVD。分别验收工作区打开、任务、同请求终态和文件字节校验。

每批由 `deepseek-flash` 实施与测试。总控审查差异与可追溯的测试证据，最终由 `6.2 Sol` 独立审查。发布就绪前运行 `npm run test:fast`、`npm run test:pr` 以及固定上游联测，按候选提交记录 CI 与设备验收状态。
