# TSNET Android transport decision

> [!WARNING]
> 本文已于 2026-08-24 被 [Open Android Intelligence 模块化插件架构规格](./2026-08-24-modular-plugin-architecture.md) 取代，仅保留历史背景。Tailscale 现为默认不安装、不启用的 Companion 连接插件，直接 HTTPS 是核心默认路径。

状态：已确认（2026-08-11）

## 决策

Android 端采用项目自构建的最小 `tsnet-android` AAR：以固定的
Tailscale Go userspace 源码为基础，通过 gomobile/NDK 生成 AAR，并由 Kotlin
通过窄接口调用。依赖锁定前不得填入版本、commit 或 SHA-256 占位值。

该 AAR 只提供：

- 启动/停止应用内 Tailnet userspace 节点；
- 恢复和清理应用私有 no-backup 状态；
- 查询连接状态（`DIRECT`、`RELAY`、`OFFLINE`）；
- 连接唯一的 enrollment-ticket 绑定 Bridge TCP 443 端点；
- 返回受控 HTTP/TCP 会话给 transport 层。

## 明确禁止

实现不得声明或调用 `VpnService`、`BIND_VPN_SERVICE`、TUN、系统路由/DNS
修改、全局代理、监听器、Funnel、LocalAPI 或通用 Tailnet `Dial`/`Listen`。
Kotlin 层不得接收 host、port、URL、socket、route 或 DNS 参数；这些值只能
来自已验证的 pairing binding。

## 直连语义

`DIRECT` 是首选路径。若 Tailscale 选择 DERP/relay，transport 必须显式报告
`RELAY`；若当前产品策略要求严格 P2P，则 fail closed，不得静默降级为公网
HTTPS 或伪装成直连。

## 首个验证包

生产实现从一个 arm64-v8a 验证包开始，完成以下证据后才关闭
`MVP-DEP-TSNET`：

1. 固定 Go/Tailscale/gomobile/NDK/Android ABI 版本、源码 commit、AAR SHA-256
   和可复现构建命令；
2. API 34+ 真机启动、配对、重启恢复和断网重连；
3. 证明 `DIRECT`/`RELAY`/`OFFLINE` 状态可区分；
4. 合并 manifest、`dumpsys vpn`、路由/DNS 快照证明没有系统 VPN、TUN 或
   全局流量接管；
5. 在另一系统 VPN 运行时仍能完成或明确拒绝本应用连接；
6. 证明 auth key 在 enrollment 后清理，node state 留在加密 no-backup 存储，
   且日志不包含 auth key、signed envelope 或数据正文。

未完成以上证据前，SDK-free 测试可以通过，但 release gate 必须保持阻塞。
