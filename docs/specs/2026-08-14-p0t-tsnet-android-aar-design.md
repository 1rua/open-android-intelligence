# P0t Tailscale Android userspace AAR 设计

状态：方案 A 与书面规格已获用户确认（2026-08-14）

## 1. 目的与既有决策

本规格把已经确认的 Android userspace transport 决策收敛为一个可执行的
P0t 可行性门槛：从控制器提供的 `third_party/tailscale` Git checkout 构建项目
自有、最小化、输入锁定的 Android AAR，把它集成进 `tailnet-core`，并让生产
composition 只能装配真实的 `TsnetPairedBridgeTransport`。

本规格继承并细化：

- `docs/superpowers/specs/2026-08-08-agent-bridge-android-design.md` 的
  app-scoped userspace Tailnet、安全配对和不占系统 VPN 槽位约束；
- `docs/superpowers/specs/2026-08-11-tsnet-android-decision.md` 的窄 AAR
  接口、TCP 443、无通用网络 API 和 fail-closed 决策；
- `docs/protocol/v1/enrollment.md` 与 `protocol/schemas/v1/connect.schema.json`
  已冻结的 enrollment 和 `connect_hello`/`connect_welcome` 应用协议；
- `docs/mvp/p0t-mvp-evidence.md` 中仍未完成的 AAR、真机和网络路径证据。

旧决策对 `LocalAPI` 的禁止解释为：产品不得创建 LocalAPI socket、不得把
LocalAPI client 或任意 status/dial 能力暴露给 Kotlin、应用、Agent 或 IPC。
本规格允许 AAR 内部仅调用 `tsnet.Server.LocalClient().Ping` 查询**当前已绑定
Bridge peer** 的实际路径；返回值只归一化为 `DIRECT`、`RELAY` 或
`OFFLINE`。这是为了避免把成功请求或延迟猜测成 direct，不形成通用 LocalAPI
能力。

## 2. P0t 范围

P0t 必须交付：

1. 固定 Tailscale、Go、gomobile、Android SDK、NDK、JDK、ABI 与构建参数；
2. 可重复执行、默认离线、输入不匹配即失败的 AAR 构建与校验脚本；
3. 含 `arm64-v8a` 和 `x86_64` 的 `tsnet-android` AAR、SHA-256、SBOM、
   license inventory 与构建 provenance；
4. AAR 生成 API 到 `tailnet-core` 窄接口的真实 Kotlin adapter；
5. 生产 composition 使用 `TsnetPairedBridgeTransport`，fake 只存在于测试
   fixture；
6. 使用真实 Tailnet、一次性 auth key、已配对 Bridge TCP 443/WSS endpoint
   完成 `connect_hello`/`connect_welcome` 和控制帧往返；
7. direct、DERP、offline、设备待批准、进程恢复、网络切换、Doze、16 KiB
   page size 和另一系统 VPN 共存/阻断证据；
8. merged manifest、`dumpsys vpn`、路由、DNS、egress 与脱敏日志证据，证明
   本产品没有创建系统 VPN、TUN、全局代理或公网降级。

P0t 不交付生产 Bridge listener、OAuth credential issuer、节点生命周期 worker、
完整 policy verifier、生产 pairing UI 或 Hermes/OpenClaw adapter。设备验证使用
专用 P0t Bridge harness；它只验证真实 transport 与已冻结 connect 协议，不得
被记录为生产 Bridge readiness。

## 3. 不可变输入

### 3.1 上游源码

锁定以下 Tailscale stable source：

| 字段 | 固定值 |
| --- | --- |
| upstream | `https://github.com/tailscale/tailscale.git` |
| release | `v1.98.10` |
| annotated tag object | `0ee734d3089846b27bc6ebcddd3d6ee5ec13e04d` |
| source commit | `36550d57f4a4055246ef7412f4e650a012a465f1` |
| `VERSION.txt` | `1.98.10` |
| `go.mod` module | `tailscale.com` |
| `go.mod` Go version | `1.26.5` |
| upstream `go.toolchain.rev` | `63ae404c8203317fd3c82d972e5dc8f0fcb425cb` |

`third_party/tailscale` 是控制器提供的本地 source input，不把完整上游源码
vendoring 到 open-android-intelligence Git history。构建不得切换或修改它的当前工作树：输入
检查先验证 official origin、clean status 和 pinned commit object，再通过
`git archive` 把固定 commit 展开到临时只读 staging。commit object 缺失时构建
失败，并给出显式的 controller fetch 命令；构建脚本本身不联网。

项目在 `.gitignore` 中忽略 `/third_party/tailscale/`，防止误提交完整嵌套
checkout。真正进入版本控制的是 lock、构建脚本、项目 wrapper 源码、规范化
AAR、digest、SBOM 和 provenance。

### 3.2 工具链

| 输入 | 固定值 |
| --- | --- |
| Go | stock Go `1.26.5`；archive SHA-256 写入 lock 并在执行前校验 |
| gomobile / gobind | `golang.org/x/mobile@v0.0.0-20240806205939-81131f6468ab` |
| Android API passed to gomobile | `34` |
| Android compile SDK | `35` |
| Android NDK | `27.2.12479018` (`r27c`) |
| JDK | Temurin `17.0.20+8` |
| AGP / Gradle / Kotlin | `8.9.2` / `8.12` / `2.1.20` |
| ABI | `android/arm64` → `arm64-v8a`; `android/amd64` → `x86_64` |
| page size | native LOAD alignment at least 16,384 bytes |

Go archive、gomobile module、NDK package 和最终 AAR 的真实 digest 只能由校验
脚本从实际字节计算并写入 lock/provenance。缺 digest、digest 不匹配、工具路径
名与 `source.properties` 实际版本不一致，或 lock 被手工填入未验证值时均失败。

构建核心命令固定为等价于：

```text
gomobile bind \
  -target=android/arm64,android/amd64 \
  -androidapi=34 \
  -trimpath \
  -tags=ts_omit_cachenetmap \
  -ldflags="-linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
  -o tsnet-android-1.98.10.aar ./tsnetbridge
```

wrapper module 在临时 staging 中以 `replace tailscale.com => <pinned archive>`
解析源码。构建固定 `SOURCE_DATE_EPOCH`、Go cache、module cache、locale、时区和
zip entry 顺序/时间；两个全新 staging 的规范化 AAR 必须得到相同 SHA-256，
否则不生成 locked artifact。

## 4. AAR 边界

### 4.1 Go package

项目 wrapper 位于 `apps/android/tailnet-core/native/tsnetbridge`。它只导出
gomobile 可绑定的以下概念接口：

```text
Start(bootstrapBytes, restoredStateBytes, StateSink) -> Node
Node.OpenPairedBridge(bindingBytes) -> Channel
Node.Path(bindingBytes) -> DIRECT | RELAY | OFFLINE
Node.Stop()
Channel.Send(canonicalWireBytes)
Channel.Receive() -> canonicalWireBytes
Channel.Close()
```

`StateSink.PersistState(bytes) -> error` 必须在每次 Tailscale state write 时把完整
state 交给 Kotlin，并把持久化失败同步返回 Go。回调返回成功前 Kotlin 必须完成
Keystore-wrapped、app-private、no-backup 持久化；写入失败使节点启动或当前
session fail closed。Go 侧使用项目自有 `ipn.StateStore`，从
`restoredStateBytes` 恢复并在写入时导出；不得使用 filesystem state、Android
Backup 或不可导出的 process-only `mem.Store`。

`Start` 在任何 Tailscale 初始化前调用 `envknob.SetNoLogsNoSupport()`，并将
`Server.Logf` 与 `Server.UserLogf` 接到仅允许固定错误码的本地脱敏 sink。
`RunWebClient=false`、`Ephemeral=false`；AAR 不导出 `Server`、`LocalClient`、
`HTTPClient`、`Dial`、`Listen`、socket、fd、route、DNS、proxy 或 TUN 类型。

### 4.2 Native enrollment bundle

`bootstrapBytes` 是 pairing subsystem 验证签名、ticket、Bridge identity、policy
revision 和 pairing generation 后产生的闭合二进制 `NativeEnrollmentBundleV1`。
transport/UI 不解析它。bundle 只含：

- format version；
- installation-derived node hostname；
- HTTPS control URL；
- 一次性 auth key bytes；
- pinned Bridge Tailnet IPv4/IPv6；
- pinned Bridge MagicDNS/SNI 名；
- Bridge application-key fingerprint；
- enrollment ticket digest；
- device ID 与 pairing generation；
- policy attestation revision/digest 和 expiry。

bundle 不含任意 port、scheme、path、proxy、route 或 DNS 配置。native core 固定
TCP 443、WSS 和 `/v1/control`，禁止 redirect；实际解析结果必须是 pinned
Tailscale IP，SNI 必须是 pinned MagicDNS 名。IPv4 地址不在
`100.64.0.0/10`、IPv6 地址不在 `fd7a:115c:a1e0::/48`、DNS 结果不含 pinned
IP、attestation 过期或 binding identity 不一致时拒绝连接。

auth key 只在首次 enrollment 时允许非空。`Server.Up` 成功、失败或超时后，
Go 清零输入 byte slice、清空 `Server.AuthKey` 的可达引用，Kotlin 在 `finally`
中清除 enrollment credential；节点 state 独立保留。Go string 转换可能在 GC 前
留下不可原地清零的运行时副本，因此证据只声明“凭据不再可达、不会持久化或
记录”，不得宣称进程内 forensic zeroization。重新配对必须使用新 ticket、
新 auth key 和新 node identity。

### 4.3 Bound channel

`OpenPairedBridge` 不接受 endpoint。它只接收从
`VerifiedPairingTransportBinding` 编码的 identity/generation/ticket digest，并
与 Node 启动时保存的 bundle 做常量时间或规范化等值检查。通过后，AAR 使用
tsnet 内部 dialer 建立 pinned WSS session；任何 HTTP redirect、证书/SNI 错误、
非 443 目标或 endpoint 改写都失败。

每条 WSS binary message 恰好承载一条 canonical open-android-intelligence wire envelope。
空帧、text frame、超过协议固定 `max_envelope_bytes = 262,144` bytes、fragment
reassembly 超限或 close 后调用均拒绝。AAR 不解释应用 payload，也不重签、
重试或重排控制 envelope。

真实 path 只能在 WSS 建立后，通过 AAR 内部对当前 pinned peer 的 Tailscale
Ping/peer status 取得：直接 endpoint 映射为 `DIRECT`，DERP region 映射为
`RELAY`，无可验证 peer path 映射为 `OFFLINE`。`APPROVAL_REQUIRED` 只来自明确
backend 状态。请求成功、RTT 或 socket 类型不能推断 path。

## 5. Kotlin 与 Gradle 集成

### 5.1 `tailnet-core`

最终 artifact 固定存放为：

```text
apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar
apps/android/tailnet-core/libs/tsnet-android-1.98.10.aar.sha256
apps/android/tailnet-core/libs/tsnet-android-1.98.10.provenance.json
apps/android/tailnet-core/libs/tsnet-android-1.98.10.sbom.json
```

`tailnet-core/build.gradle.kts` 使用显式 `api(files(...))` 或
`implementation(files(...))` 引用该单一文件，不使用 `flatDir`、动态版本、远程
Maven fallback 或 release/debug 不同 AAR。`preBuild` 和 `check` 依赖 artifact
verifier；文件缺失、digest/ABI/API/page-size/classes 不匹配时，Gradle 在编译前
失败。

`AndroidTsnetBinding` 是唯一 import 生成 `tsnetbridge.*` API 的 Kotlin 文件。
它实现改造后的 `LibTailscaleBinding`，负责：

- native handle 生命周期和并发串行化；
- `ByteArray` defensive copy 与清零；
- native state callback 到 `NoBackupTailnetStateStore`；
- native error code 到 `TransportFailure`/close reason 的封闭映射；
- `NativeChannel` 到 `UserspaceBridgeChannel` 的 byte-only adapter；
- close/stop 幂等及进程重启恢复。

`TsnetLibTailscaleCore.start` 必须在成功和异常路径都清除 enrollment auth key；
返回 session 前 state 已持久化。`TsnetPairedBridgeTransport` 不再接收构造时写死
的 `TransportPath`，而是从 core 查询当前 bound peer 的真实 path。

### 5.2 Production composition 与 test fixture

`FakeUserspaceTransport.kt` 从 `transport/src/main` 移到
`transport/src/testFixtures`。需要 fake 的其他模块通过 Gradle test fixtures
依赖获取它；production APK 的 runtime classpath 和 release AAR 中不得出现该类。

应用侧提供一个 production factory，组合：

- Android Keystore-wrapped no-backup node state；
- 持久 connection-generation store；
- `AndroidTsnetBinding`；
- `TsnetLibTailscaleCore`；
- `PairedBridgeSessionCoordinator`；
- `TsnetPairedBridgeTransport`。

pairing registry 只能安装该 factory 返回的 transport 与已验证 binding。没有
有效 bundle/state 时 registry 保持 unavailable；不得自动创建 fake、普通 HTTPS
transport 或系统 VPN fallback。

## 6. 错误与生命周期

| 原因 | 对外状态/行为 |
| --- | --- |
| ticket/binding/policy 不一致 | `INVALID_BINDING`，启动前拒绝 |
| auth key 失效或节点待批准 | `APPROVAL_REQUIRED` 或封闭 enrollment error；清 key |
| control plane 不可达 | `CONTROL_UNREACHABLE`；不启用公网 fallback |
| UDP 被阻断但 DERP 可用 | `RELAY`；仅当产品当前策略允许 relay 时继续 |
| control、DERP 和 direct 均不可用 | `NETWORK_BLOCKED`/`OFFLINE` |
| state 持久化失败 | 关闭 channel 和 node，保留错误证据，不返回 session |
| generation 过期 | `STALE_GENERATION`，关闭旧 channel |
| network change | 先 fence 旧 session，再以递增 generation 重连 |
| process stop/user revoke | 关闭 channel/node；按撤销语义保留或清除 node state |

所有启动、open、reconnect、close 和 stop 操作由现有 coordinator mutex 串行化。
native close/stop 必须可重复调用；一个 Node 同时至多存在一个 bound Bridge
channel。core 不为保持在线单独创建常驻前台服务、无界 wake lock 或 Android
listener。

## 7. 测试策略

实现严格遵循 red-green-refactor：

1. Go 单元测试先锁定 bundle parser、endpoint pinning、state callback、auth key
   清理、frame 限制、error mapping 和 close 幂等；
2. AAR ABI/provenance verifier 测试先以缺文件、错 commit、错 NDK、错 digest、
   缺 ABI、非 16 KiB alignment 和 forbidden symbols 失败；
3. Kotlin JVM/Robolectric 或 instrumentation contract test 先证明真实 binding
   adapter 的生命周期、state-before-session、异常清 key 和真实 path 映射；
4. production composition test 证明 release runtime 不含 fake，未配对时 fail
   closed；
5. P0t Bridge harness 使用既有 canonical protocol 组件验证签名后的
   `connect_hello`，返回签名 `connect_welcome`，随后做 nonce-bound binary
   control echo；
6. connected tests 在真实 AAR 上完成 cold start、warm state restore、process
   death、network switch、Doze、direct、DERP、offline 和另一 VPN 场景。

Go 测试不得 mock `tsnet.Server` 来证明集成成功；外部 control/Tailnet 只在纯
单元测试中使用窄 fake，P0t 必须运行真实 Tailscale coordination、WireGuard 和
DERP/direct datapath。Kotlin 测试可以 fake generated AAR boundary，但必须另有
instrumentation test 加载真实 `libgojni.so`。

## 8. P0t 设备矩阵与资源预算

### 8.1 必需环境

- `x86_64` emulator 分别覆盖 API 34、35、36、37，每个版本至少执行安装、
  native load、cold/warm start、WSS 往返与 stop smoke test；
- 至少一台运行 API 34–37 之一的 `arm64-v8a` 物理设备，执行本节全部网络、
  生命周期、VPN 共存和资源场景；
- 至少一个 Android 15+ 16 KiB page-size 环境，执行安装、native load、完整 WSS
  往返和进程恢复；
- 一个与手机 deployment-specific policy 匹配的 P0t Bridge；
- 5 分钟、单次、tag-scoped auth key；
- direct 可达网络、强制 DERP 网络、完全离线网络；
- 允许 Tailscale 底层 UDP/HTTPS 的另一系统 VPN，以及阻断 Tailscale 的
  always-on/lockdown 配置。

### 8.2 硬预算

任一硬预算失败均使 P0t 失败并回到设计评审：

| 指标 | 门槛 |
| --- | --- |
| 双 ABI 规范化 AAR | 不超过 80 MiB |
| per-ABI release APK 安装增量 | 不超过 40 MiB |
| warm-state Wi-Fi online p95（10 次） | 不超过 15 秒 |
| 首次 enrollment online p95（10 次，不含人工批准） | 不超过 30 秒 |
| screen-off idle RSS p95（30 分钟） | 不超过 160 MiB |
| screen-off steady CPU（30 分钟平均） | 不超过单核 1% |
| idle wakeups | 不超过 12 次/小时 |
| screen-off idle battery | 4 小时不超过 6 个百分点 |
| direct 64 MiB payload throughput | 不低于 10 Mbit/s，且无内容错误 |
| relay 64 MiB payload | 必须完成且无内容错误；记录吞吐但不设最低值 |

资源测试记录设备型号、OS build、battery health、网络、温度、充电状态和采样
命令。运行时如果 thermal throttling 或系统更新污染样本，该次样本作废并重跑，
不能择优删除有效慢样本。

## 9. 证据包

`docs/mvp/p0t-mvp-evidence.md` 最终必须从 `BLOCKED` 更新为基于实际运行结果的
`PASS` 或 `FAIL`，并链接一个不含秘密的 evidence inventory。证据至少包括：

- source tag/commit、工具链版本和每个输入/输出 digest；
- 两次 clean build 的相同 AAR SHA-256；
- AAR entries、ABI、ELF LOAD alignment、SBOM 和 license review；
- Gradle `:tailnet-core:check`、`:transport:check`、全仓 `check`；
- 真机/emulator 型号、API、ABI、page size 与测试 run ID；
- redacted `connect_hello`/`connect_welcome` digest 和 nonce-bound echo receipt；
- direct/DERP/offline/approval/network-switch/Doze/process-death 状态与时间线；
- 测试前后 `dumpsys vpn`、route、DNS 和另一 VPN owner；
- merged manifests 和 APK/AAR forbidden-surface scan；
- egress destination inventory 与 no-log-upload 证明；
- Logcat/崩溃输出秘密扫描结果；
- AAR/APK、RSS、CPU、wakeups、battery 和 throughput 数值。

auth key、plaintext ticket、node private state、设备私钥、完整 signed envelope、
消息正文和可复用 endpoint credential 不进入证据。证据缺项必须标记 `BLOCKED`
或 `FAIL`，不能用 source-level test、fake、未执行命令或人工描述补成 `PASS`。

## 10. P0t 通过条件

只有同时满足以下条件才关闭 `MVP-DEP-TSNET`：

1. stable source、工具链、AAR、SBOM、license 和 provenance 全部锁定；
2. 两次 clean build 的规范化 AAR digest 相同；
3. `tailnet-core` 与 production composition 使用真实 adapter，release runtime
   不含 fake；
4. 真实设备完成一次性 auth-key enrollment、state 恢复和已配对 Bridge WSS
   `connect_hello`/`connect_welcome`；
5. direct、DERP 和失败状态来自真实 backend 信号；
6. 系统 VPN、route、DNS 和其他 App 流量未被本产品接管；
7. 另一系统 VPN 的允许路径成功，阻断路径真实失败且无公网/系统 VPN fallback；
8. 16 KiB、进程死亡、网络切换、Doze、日志/egress 和所有资源预算通过；
9. `docs/mvp/mvp-dependency-lock.md` 由 controller 记录 review time、expiry、
   digest、verify command 和 `locked` 状态；
10. 全部自动化测试与证据 inventory 无失败、跳过或秘密泄漏。

任何条件失败都暂停后续 P0d 生产 transport，不得静默改用官方 Android
`libtailscale`、`VpnService`、系统 TUN、普通公网 HTTPS 或代理。
