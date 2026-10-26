# 统一 OAI Core 验证记录

实现基于 `dev` 的 `157e7e5b`，位于独立 `feature/unified-oai-core` worktree。
提交前已同步最新 `dev` 的 `00642ad1`，保留其契约门禁修复；同一事件流降级修复已由上游包含，
Android 最终源码与已通过验收的版本一致。
迁移、数据兼容及回滚步骤见[迁移方案](../plans/2026-10-25-unified-core-migration.zh-CN.md)。
宿主真实接口及对应版本的复核见[原生会话宿主审查](2026-10-25-native-session-host-review.zh-CN.md)。

## 自动化验证

在仓库根目录执行：

```sh
HERMES_SDK_ROOT=.toolchains/hermes-native-sdk PYTHONPATH=oai-core/src python -m pytest oai-core/tests -q
npx vitest run integrations/dsh-session-adapter/adapter.test.js
python -m pip check
python -m pip wheel --no-deps --wheel-dir ./tmp/wheels ./oai-core
git diff --check
```

Core Python 51 项通过，无跳过。覆盖真实 HTTP 登录、Ed25519 签名、持久 nonce、防重放、
账号及设备授权隔离、幂等冲突、SSE/WS 设备事件、手机 claim/result/ack、取消和重启后的未知结果。
CLI 与官方 MCP SDK stdio 客户端握手及工具调用通过；设备调用不接受账号或设备身份覆盖。
固定 Hermes 原生 SDK 在临时 Home 中验证真实插件注册和 SessionStore 生命周期，未调用模型。
Python 仅有 aiohttp 请求存储字符串键的建议性警告。

DSH 会话桥 11 项通过，包括与公开原生接口一致的消息形状、终端会话发现、后续会话刷新、
turn 关联、流式回复、认证拒绝及非成功终态。
wheel 构建与独立目录安装导入成功，CLI 入口可运行，`pip check` 无依赖冲突。

Android 验证使用 JDK 21、Gradle 8.12、Android SDK 35/36，在 `apps/android` 执行：

```sh
./gradlew --no-daemon --max-workers=2 \
  :gateway-client:testDebugUnitTest :app:compileFullDebugKotlin \
  :app:testFullDebugUnitTest \
  --tests '*NativeAgentsControllerTest' --tests '*GatewayTutorialsTest' \
  --tests '*GatewaySessionRefreshTest' --tests '*GatewayWorkbenchLogoutTest' \
  --tests '*DeviceExecutionRecoveryTest' --tests '*PlatformSnapshotRecoveryTest'
```

Android 最终结果：`gateway-client` 279 项通过，选中的 App 回归 77 项通过，全部无失败、错误或跳过；
`compileFullDebugKotlin` 成功。包括会话续期 45 项、退出工作台 6 项、原生 Agent 控制器 3 项、
部署教程 12 项、平台快照恢复 3 项、设备执行恢复 8 项。

## 独立审查修复

- 新扩展路由原先只复用验签，未消费 nonce：统一增加持久防重放账本，并验证重启后仍拒绝回放。
- 原生写操作增加回执摘要；同一签名 request ID 换 nonce 不会重复创建或发送，未知结果不自动重试。
- 独立 Core 不宣告已关闭的旧 `/conversations` 可选能力；原生功能以 `/agents` 能力为准。
- 成功协商用 `X-OAI-Agent-Sessions: 1` 明确选择原生会话界面，旧宿主不接收未知接口探测。
- Core 仅复用验证后的 HTTP/SSE/WS 处理器，不构造 Hermes 平台；真实 SDK 已安装但平台未注册时也能独立启动。
- Hermes 原生标题、配对代次取消、宿主审批拒绝，以及 DSH 原生事件形状均以实际接口补测修复。
- Codex 解除归档调用真实 `thread/unarchive`；隔离说明明确共享 Agent 不能持有个人设备凭据。
- Codex 活跃 turn 关闭时只向存活进程发送取消，不重启 app-server；回归验证无遗留子进程。
- CLI `--wait` 只结束本地等待，取消通过明确命令进行；安装环境、客户端凭据路径和能力 ID 示例已统一。
- Android 管理动作遇到已连接但仍在收尾的初始化时等待完成，再复核相同会话及客户端所有者，避免账号切换或解除配对被静默丢弃。
- 发送流 I/O 中断明确显示结果未知，不自动重发；Core 的设备快照恢复不依赖旧聊天列表。

## 仍需部署验收的范围

自动化使用隔离的原生会话存储、协议进程及合成模型回调，未访问真实用户会话或实体 Android。
实际部署仍需验证 Hermes/DSH/Codex 版本、网络/TLS、模型回复、App/终端交叉操作及敏感设备操作的手机确认。
共享会话进程必须与 Core 私有数据和个人 CLI 凭据隔离，不能把共享会话当作设备授权来源。
旧 App 不能使用新 Core 的原生聊天 API；回滚时同时恢复原 Gateway endpoint 和切换前备份。
