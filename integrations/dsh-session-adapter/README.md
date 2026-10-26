# DSH 原生会话适配器

通过 DSH `plugin_manager action=install_bundle` 安装本目录并重启目标 profile。该 Cordis bundle 只调用宿主真实 `sessionQuery` 与 `sessionController`，默认绑定 `127.0.0.1:8791`，通过至少 32 字符的 loopback token 接口供 Core 访问；不包含手机连接、账号库或设备调度器。该令牌仅用于 Core 到宿主的内部会话认证，不能作为 Core 账号或设备凭据。

列表覆盖目标宿主已配置 persistence backend 与 live sessions，所有已认证 OAI 账号共享该部署范围。能力依据实际宿主方法启用（当前不宣告 delete 与 archive），未接入的其他系统实例不在发现范围。请求严格受小于 1 MiB 的紧凑 UTF-8 JSON 预算约束。

架构细节与代码出处见[架构事实基线](../../docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md)，完整配置、实际 API 证据、设备凭据隔离、测试与回滚步骤见[原生会话适配器部署文档](../../docs/plans/2026-10-25-native-session-adapters.zh-CN.md)。
