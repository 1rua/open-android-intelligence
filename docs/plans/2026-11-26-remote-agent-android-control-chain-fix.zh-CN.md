# 修复远程 Agent 控制 Android 的后端链路

跟踪 issue #248。核验基线为 `b6cce9c16cd1047fa812d7b5b7ad307660209022`，上游协议固定于 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`，桌面安装验收用 `v0.6.5`。

## 复现与范围

JVM 门面的 `workspace/follow` 能返回 `phone-capabilities`，`session/create` 能创建独立任务。固定桌面 `workspace.open` 调用 `workspace/create`，后者在基线上返回 `METHOD_NOT_FOUND`。Host 首次注册暂时失败、握手重复帧与流首帧顺序、历史回执截断、外部桥安装与本机鉴权分别验证。真实部署停在哪一步仍需匹配同一连接、流与原请求回执。

Android 后端保持独立 Client/Host 身份。扫码配对只导入 Client，配对受限身份不能补注册 Host。控机从同账号授权的桌面入口打开指定工作区，保持连接后运行原生 `remote_*` 与 Stdio `android_*`。

## 实施批次

1. 独立实现手机虚拟工作区幂等打开、授权注册接口、角色凭据原子提交、握手重复帧处理、首帧订阅顺序和历史回执预算。每项用定向 Gateway 单测约束；回执溢出经同任务私有产物提供完整 JSON。
2. 在注册接口与凭据提交就绪后补 Host 首次注册恢复。明确账号登录、同一代次、仍有效的 Client、暂态 Host 失败是补偿前提。冷启动待命保留已有 Host 恢复语义。
3. 外部桥按本机 HTTP 准入、包安装、连接与发现、回执查询推进。OAI 专用回环路由（`/oai-android-mcp/v1/*`）由桥自身校验回环地址与有效端口（含默认端口 `80`）、精确 `Host`、拒收浏览器跨站头及签发时即绑定当前登录身份代次的桥专属 bearer，桌面通用路由的 Cookie/API Peer 鉴权仍完全由 DSH `Connection.requestRejection` 负责（两类路由鉴权分工已同步至[控制契约](../contracts/oai-control-rpc-v1.md)第 6 节与[架构基线](../architecture/2026-11-13-dsh-remote-baseline.zh-CN.md)第 4 节）。注销、换服、卸载必须让旧桥授权立即永久失效。历史缺失按原请求回查 `control-bus`，保留手机终态、权限与不重放语义。
4. 用固定客户端跨进程联测契约，然后在任务分支触发 `Android APK Artifact` 产出同一候选提交的 Full Debug APK，由用户安装到真机完成端到端验收。用户在真机手动登录后，分别验收 Host 注册在线、工作区幂等打开、独立任务、同请求终态不重放和回执产物文件字节校验。

每批完成后执行定向回归与差异核对，发布就绪前运行 `npm run test:fast`、`npm run test:pr`、固定上游跨进程联测及独立双轴代码审查，并在任务分支触发 `Android APK Artifact` 产出 Full Debug APK，按候选提交记录 CI 与真机端到端验收状态。
