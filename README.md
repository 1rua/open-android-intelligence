<div align="center">

<img src="assets/readme/hero-banner.svg" alt="Open Android Intelligence：把你的 Android 手机接上自己部署的 AI Agent" width="100%" />

<p>
  <strong>一个 Android App + 一套协议，把你的手机接上你自己部署的 AI Agent。</strong><br />
  手机不是被遥控的终端：每种能力单独装、单独授权，敏感操作每次都要你点头。
</p>

<p>
  <a href="#快速开始"><img src="https://img.shields.io/badge/手机端-Android%2012%2B-10b981?style=flat-square" alt="Android 12+" /></a>
  <a href="#怎么工作"><img src="https://img.shields.io/badge/服务器-自己部署-38bdf8?style=flat-square" alt="自托管" /></a>
  <a href="#当前状态"><img src="https://img.shields.io/badge/契约-70%20组跨宿主用例-34d399?style=flat-square" alt="70 组契约用例" /></a>
  <a href="#安全边界"><img src="https://img.shields.io/badge/安全-不%20Root%20%C2%B7%20默认拒绝-f59e0b?style=flat-square" alt="安全边界" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/许可证-MIT-94a3b8?style=flat-square" alt="MIT" /></a>
</p>

<p>
  <a href="#30-秒看懂">30 秒看懂</a> ·
  <a href="#能做什么和还不能做什么">能做什么</a> ·
  <a href="#怎么工作">怎么工作</a> ·
  <a href="#安全边界">安全边界</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#当前状态">当前状态</a>
</p>

</div>

---

## 30 秒看懂

这个仓库由三部分组成：

| 部分 | 是什么 | 在哪 |
| --- | --- | --- |
| **Android App** | 你和自己的 Agent 聊天、管账号、亲手挑附件的手机端 | `apps/android` |
| **一套协议** | 手机和服务器之间的固定通信规矩，Hermes 与 OpenClaw 共用同一套 | `gateway-contract` |
| **两个 Agent 插件** | 装在你自己的 Agent 上，把消息和设备请求接起来 | [hermes-gateway-plugin](https://github.com/1rua/hermes-gateway-plugin) / [openclaw-gateway-plugin](https://github.com/1rua/openclaw-gateway-plugin) |

它想替换的是「拿微信、QQ、Telegram 的 bot 当 Agent 连接器」这种做法。那种做法有三个绕不开的问题：消息要过第三方服务器、权限给出去就是全有或全无、手机这边没有任何裁决点，出了事也查不清是谁做的。

这里的做法换一下：网关你自己部署，手机和你的账号一对一配对，每种设备能力单独安装、单独授权，敏感操作每次都要在手机上点确认。

一句话概括区别：**手机是裁判，不是被遥控的终端。**

### 几个常见疑问

- **需要 root 吗？** 不需要。App 只做普通 App 能做的事，靠系统权限和你逐次确认来兜底。
- **会偷偷读我的手机吗？** 不会。设备能力是插件，装不装、开不开、授权给哪个网关账号，都是你在手机上决定，随时能收回。
- **数据去哪了？** 只去你自己部署的服务器。手机与网关之间是 HTTPS，每个关键请求都带 Ed25519 签名、时间戳和一次性随机数；网关上的敏感内容用账号主密钥加密保存。
- **现在能当日常助手用吗？** 聊天、会话管理、附件、账号配对、检查更新这些主链路可用；短信/通知/通话这类设备读取能力，插件包和沙箱已经做完并测过，但**还没接进 App 的正式链路**，详细状态见[当前状态](#当前状态)。

---

## 能做什么和还不能做什么

写清楚比写漂亮重要，所以这里直接列出当前的真实状态：

| 能力 | 现在能不能用 |
| --- | --- |
| 手机和自托管 Agent 多会话聊天，支持归档、文件夹、排序 | ✅ 可用 |
| 从手机提交附件（系统文件/相册选择、图片粘贴、单个或多个文件分享） | ✅ 可用 |
| 网关账号登录、配对、凭据轮换与撤销 | ✅ 可用 |
| 设置里检查更新，正式版 / 预览版两个渠道 | ✅ 可用 |
| Android 12–15 的会话通知与气泡，Android 16 的原生 Live Update | ✅ 可用（Samsung Now Bar 等厂商定制有已知限制） |
| Agent 侧插件（Hermes 2.2.0 / OpenClaw 1.1.0） | ✅ 可安装，70 组契约用例两端逐条对齐 |
| 设备插件包的打包、验签、WASM 沙箱与资源配额 | ✅ 库与工具链已就绪 |
| 读短信 / 通知 / 通话记录 | ⚠️ 插件已实现，App 里的执行链路仍在装配，真机闭环未验证 |
| Tailscale 私网通道（Companion APK） | ⛔ 入口关闭，暂不签发通道 |
| 完全「零保留」（宿主历史、ACK 清理） | ⛔ 未完成，暂不承诺 |

---

## 怎么工作

<div align="center">
  <img src="assets/readme/architecture.svg" alt="架构：手机侧裁决、协议层校验、服务器侧思考" width="100%" />
</div>

**手机上（Android 宿主）** 只做三件事：

1. **你来操作**：连网关、多会话聊天、自己挑附件。手机上不跑模型，消息发给你的服务器，模型、工具、记忆都在那边。
2. **平台内核把关**：它是 App 里不可卸载的一层，管设备身份（Ed25519 密钥）、权限裁决、插件的安装/启用/停用/回滚，还有一个一键熔断开关。它不提供任何业务能力，只负责说「可以」或「不行」。
3. **设备插件干活**：短信、通知、通话记录各是一个插件。受保护插件跑在 WebAssembly 沙箱里（可以把沙箱理解成「插件在里面的隔离盒子」），CPU、内存、存储、网络都有硬上限，插件不能自己扩权。

**中间（Gateway Protocol v2）** 是一套封闭契约：

- HTTPS 双向传输 + SSE 单向推流（服务器把事件持续推给手机）；
- JSON Schema 2020-12 全字段封闭校验，多一个未知字段就整条拒绝；
- 每个关键请求带 Ed25519 签名、防重放时间戳和一次性随机数；
- 操作具备幂等状态机与有界重试，网络抖动不会变成重复执行。

**服务器上（你自己的机器）** 跑 Agent：

- Hermes 与 OpenClaw 都以**原生插件**方式加载网关适配器，不再需要单独跑 Docker 或 systemd 的旧 Bridge；
- 一个部署可以托管多个网关账号，配对、会话、记忆、密钥彼此隔离；
- 模型调用、工具执行、长期记忆全在你的机器上，不经过本项目任何服务器。

一句话：**手机裁决、协议校验、服务器思考。**

---

## 安全边界

<div align="center">
  <img src="assets/readme/security-model.svg" alt="安全模型：Agent 提请求，内核查权限，你在屏幕上确认" width="100%" />
</div>

默认是拒绝的，放行要一层层过。每类能力的具体边界：

| 能力 | 允许多少 |
| --- | --- |
| **通话记录** | 只能读元数据（谁、什么时候、通话多久）；不能录音、不能代接或挂断、不能自动拨号 |
| **短信** | 能读收发记录；**发短信必须每条在屏幕上点确认** |
| **通知** | 默认一条都不读；要读得按包名和字段逐项开白名单，加密缓冲，不落明文 |
| **文件与媒体** | 由你通过系统选择器、分享或图片粘贴明确添加，确认发送后提交给 Agent；App 不会在后台扫你的存储 |
| **网络** | Tailscale 跑在用户态（tsnet），不占系统 VPN 槽位；不提供通用代理或旁路出口 |
| **存储配额** | 每个插件有硬上限（9,663,676,416 字节，约 9.66 GB），超了直接拒绝，不会悄悄覆盖旧数据 |

再补几条底线：

- **Agent、网关、插件只能提申请**，最终决定权在手机的平台内核和你本人手上，拒绝会被记录；
- **不提供远程 Shell**，也不读取密码、验证码、支付信息；
- **一键熔断**：怀疑出问题时可以立即让所有能力失效；
- **审计可查**：谁在什么时候申请了什么、结果如何，都有记录。

---

## 快速开始

### 0. 你需要准备

| 项目 | 要求 |
| --- | --- |
| 手机 | Android 12 及以上（`minSdk 31`） |
| 服务器 | 你自己的机器：NAS、小主机或云服务器都行，要能跑 Python 3.12+ 或 Docker |
| 工具链（只在改代码时需要） | Node.js `24.18.0` + npm `11.16.0`、JDK 21、Android SDK（`compileSdk 36` / `targetSdk 35`） |

### 1. 手机装 App

到 [Releases](https://github.com/1rua/open-android-intelligence/releases) 下载最新 `vX.Y.Z-dev.N` **开发预览测试版** APK，直接安装。

读取手机通知还需在「设置 → 插件管理」安装 Full APK 随附的通知读取插件，显式启用并为当前 Gateway 配对授权；再按页面引导开启通知使用权、允许采集并选择应用范围。Hermes 账号需在 `hermes gateway setup` 中开启通知能力。详见[设备读取配置](docs/mvp/device-reads-and-reply-notifications.md)。

> 预览版和正式版用的是不同签名，不能互相覆盖安装。正式版只发布用私有发行密钥签名的非 Debug 包，配置见 [Android README](apps/android/README.md#release-signing)。

### 2. 服务器装 Agent 侧插件

两种宿主选一个。**先准备主密钥**——两个宿主都要用账号主密钥加密消息正文，缺了或不匹配就拒绝读写，不会回退成明文；这把密钥要和数据库分开保管，只恢复数据库是解不开正文的。

- **OpenClaw**：准备一个 32 字节的原始密钥文件（普通文件、非软链接、权限 `0600`），然后设置环境变量：
  `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE=/private/path/gateway-master-key.bin`
- **Hermes**：用宿主自己的 Secret Store；没有的话，按 [ADR 0023：显式 Gateway 密钥管理](docs/adr/0023-use-sqlite-with-explicit-gateway-secret-management.md) 用一个权限受限的专用密钥文件。密钥来源缺失或文件权限不安全时，网关会拒绝启动，而不是退回明文。

<details>
<summary><b>Hermes 安装与日常命令</b></summary>

```bash
# 安装并启用插件（固定提交，与本分支 App 配套）
hermes plugins install 1rua/hermes-gateway-plugin --ref 190441415652ae34fbd1cb8cc485f6208fff944b --enable

# 创建手机连接账号
hermes gateway setup

# 日常管理
hermes open-android-intelligence account list
hermes open-android-intelligence account create -u my_user -p <密码> --confirm-local
hermes open-android-intelligence status
hermes open-android-intelligence contract status
```

当前配套版本 `2.2.0`，核心摘要 `sha256:032f580246eb4a214624bded717819b4d5a76191208207edc9e5a4607f5c4d23`，需要和本分支 APK 一起升级。

如果之前锁过旧提交（`9d642b4`），它会返回 `/models` 这类固定命令、并且漏报取消能力，需要显式覆盖安装后重启宿主：

```bash
hermes plugins install 1rua/hermes-gateway-plugin --force --ref 190441415652ae34fbd1cb8cc485f6208fff944b --enable
hermes gateway restart
```

然后在手机上重新连接网关，让两端重新协商会话管理与停止生成能力；在输入框重新敲 `/` 可以刷新命令目录。
</details>

<details>
<summary><b>OpenClaw 安装与检查</b></summary>

```bash
openclaw plugins install git:github.com/1rua/openclaw-gateway-plugin@c7c087b9fe28adcc658a1cbdb71689a377b65214
openclaw plugins inspect open-android-intelligence-gateway --runtime --json
```

当前固定版本 `1.1.0`、提交 `c7c087b`，协议版本 `2.1.0`，宿主 API 范围 `2026.7.1`。插件包里已经带了固定契约提交生成的运行快照，安装和启动都不联网取契约。
</details>

<details>
<summary><b>没有宿主机器？用 CLI 离线预置账号</b></summary>

Hermes 插件仓库另附了一个等价 CLI，可以和宿主共用同一套管理服务和主密钥来源。在**插件仓库目录**里执行：

```bash
python3 tools/hermes-account.py init-key        # 生成 0600 限制的主密钥文件
python3 tools/hermes-account.py create my_user  # 按提示设置密码；已存在的账号不会被覆盖
python3 tools/hermes-account.py status          # 查看托管状态与配对概况
```
</details>

### 3. 手机连上网关

在 App 里填入网关地址和刚创建的账号密码，手机上确认配对。之后网关地址、证书信任、账号凭据都保存在本机，切换账号不需要重新输密码。

**HTTPS 部署还要多配一步**：把实际 TLS 证书的 SPKI SHA-256 配给两个宿主，否则手机端会拒绝提交凭据。

<details>
<summary><b>证书指纹怎么算、怎么配</b></summary>

```bash
# 计算证书公钥摘要
openssl x509 -in gateway.crt -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256
```

把结果配成环境变量（OpenClaw 也可以写在 `pluginConfig.tlsSpkiSha256`）：

```bash
OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256=sha256:<64 个十六进制字符>
```

没配时协商返回 `null`，HTTPS 客户端会拒绝提交凭据。显式使用明文 HTTP 时按 [ADR 0047：允许明文配对但持续警告](docs/adr/0047-allow-plaintext-gateway-pairing-with-explicit-warning.md) 处理。协议 Schema 摘要变化时，Android、Hermes、OpenClaw 三端必须同步升级。
</details>

### 4. 需要设备能力时再装插件

设备读取能力默认关闭，要按三层依次打开：**手机上安装插件 → 启用插件 → 给当前网关账号授权**。用 `hermes gateway setup` 可以配置，也可以先看清单：

```bash
hermes open-android-intelligence capabilities list <账号>
```

> 注意：这一步目前还没有完整跑通。插件包、验签、WASM 沙箱和配额都已经实现并测过，但 App 里的执行链路仍在装配，真机闭环也还没验证。想跟进进度看[当前状态](#当前状态)与[通知与能力同步修复记录](docs/reviews/2026-10-20-agent-notification-capabilities.zh-CN.md)。

### 5. 从源码构建（开发者）

不想在本地装 Android 工具链，也可以直接取 CI 构建好的调试包：

```bash
tools/download-latest-apk          # 等待当前提交的 CI 构建完成并下载
```

下面三块分别是契约校验、插件打包和 Android 编译。

<details>
<summary><b>契约一致性（两端各 70 组用例）</b></summary>

```bash
npm ci

# 检出固定提交的 OpenClaw 插件，并安装它自己的依赖
git clone https://github.com/1rua/openclaw-gateway-plugin.git .openclaw-gateway-plugin
git -C .openclaw-gateway-plugin checkout "$(python3 -c 'import json; print(json.load(open("openclaw-plugin-pin.json"))["revision"])')"
npm ci --prefix .openclaw-gateway-plugin --ignore-scripts

# Hermes（Python）与 OpenClaw（TypeScript）逐条比对，任一端不一致即失败
OPENCLAW_PLUGIN_ROOT="$PWD/.openclaw-gateway-plugin" npm run gateway:v2:conformance
```

本仓库是契约的唯一真源。`gateway-contract/tools/check-contract-pin.py` 会分别校验两个插件锁定的提交；契约改了但 pin 没跟着改，这里会直接失败。
</details>

<details>
<summary><b>打官方设备插件包（.alp）</b></summary>

先装固定 Rust 工具链编译真实 WASM，再提供发行者自己保管的 32 字节 Ed25519 seed：

```bash
cargo build --locked --offline --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml
cargo test --locked --offline --manifest-path plugins/Cargo.toml
npm ci --prefix plugin-tooling
OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE=/private/path/plugin-seed.bin \
  npm run build:references --prefix plugin-tooling
```

产物在 `plugins/dist/`：`org.openandroidintelligence.notifications-1.0.0.alp`、`…sms-1.0.0.alp`、`…call-log-1.0.0.alp`。

没有私钥或没有真实 WASM 时正式构建会失败；公开 seed 只能用于 `npm run build:fixtures --prefix plugin-tooling`，产物在 `plugins/dist/fixtures/`，不能当发行身份用。
</details>

<details>
<summary><b>编译 Android（调试包签名全机器一致）</b></summary>

```bash
cd apps/android
./gradlew --no-daemon check :app:assembleFullDebug
```

所有 APK 模块（`app`、`assistant-holder`）的调试包统一用仓库内的 `app/keystore/debug.keystore` 签名，不再依赖各机器随机生成的 `~/.android/debug.keystore`。所以本机、CI、预览测试版签名完全一致，可以互相覆盖安装，不会出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

核对任意调试包的签名身份：

```bash
apps/android/tools/verify-debug-signing.sh \
  apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk
```
</details>

---

## 当前状态

这一栏只写已经验证过的东西。历史记录里的 FakeAdapter 只说明测试替身的行为，不算真机能力。

| 领域 | 现在的证据 | 状态 |
| --- | --- | --- |
| Gateway Protocol v2 跨宿主契约 | Hermes 与 OpenClaw 各 70 组 TS/Python 共用用例逐条对齐 | 契约层已对齐；不覆盖整个设备执行链路 |
| Hermes 网关适配器 | pytest + 真实 aiohttp SSE/WS 签名与撤销测试 | 已覆盖；宿主历史与工具结果交付仍不完整 |
| OpenClaw 适配器插件 | 独立仓固定版本、宿主运行时加载、70 组共享用例 | 安装与协议用例已验证；Android 真机闭环未执行 |
| 设备插件打包与运行时 | 确定性签名封装、真实 WASM ABI、预算与安装恢复测试 | 库验证通过；App 执行链路待装配 |
| 官方参考插件业务 | 通知、短信、通话记录经平台内核查询 | 需真机安装并授权，闭环待单独验证 |
| Android 账号与界面 | 编译、Robolectric 与模块测试 | 账号 / 身份 / 事件逻辑已覆盖；加密离线镜像待接通 |
| Companion 私网通道 | tsnet pump 与可信 IPC 令牌签发未完成 | 入口关闭，拒绝创建通道 |
| 物理真机端到端 | Android Keystore、证书轮换、SAF 取消、真实宿主 | 本轮未执行 |

逐项核验与未完成原因见 [2026-10-02 审查修复记录](docs/reviews/2026-10-02-review-remediation.zh-CN.md)。

---

## 项目目录

<details>
<summary><b>展开目录结构</b></summary>

```text
open-android-intelligence/
├── apps/android/                  # Android 原生宿主（AGP 8.9.2、SDK 36、Kotlin 2.1.20）
│   ├── app/                       # 主入口 APK：聊天、账号、设置、附件
│   ├── platform-kernel/           # 平台内核：权限裁决、插件生命周期、安全原语
│   ├── plugin-package/            # .alp 包解析、Ed25519 验签与安装
│   ├── plugin-runtime-wasm/       # WebAssembly 沙箱运行时
│   ├── conversation-ui/           # Material 3 会话界面组件库
│   ├── assistant-holder/          # 系统助手角色与全局触发
│   ├── tailscale-companion/       # 可选 Tailscale 连接插件（Companion APK）
│   └── ...                        # 领域模型、数据存储与契约端口模块
├── gateway-contract/              # Gateway Protocol v2 契约与双宿主一致性套件
│   ├── schemas/                   # 封闭的 JSON Schema 2020-12 定义
│   ├── vectors/                   # 语言无关的黄金用例（70 组）
│   ├── src/                       # TypeScript 契约实现
│   └── tools/                     # 双宿主一致性执行套件
├── plugins/                       # 官方第一方参考插件（Rust/WASM 源码 + .alp 产物）
├── plugin-tooling/                # 插件构建与签名工具链（确定性打包器）
├── integrations/                  # 共享测试夹具与旧 Hermes 夹具
├── docs/                          # 规范、ADR 决策记录与实施评审
├── assets/readme/                 #  README 用的三张静态 SVG 与设计 token 说明
├── legacy/                        # 已冻结归档的旧 Bridge 与历史组件
├── openclaw-plugin-pin.json       # 固定 OpenClaw 插件的版本、标签与提交 SHA
└── CONTEXT.md                     # 领域术语与概念边界
```
</details>

---

## 路线图

- [ ] **真机端到端闭环**：打通从 Agent 请求到手机上执行并回传的完整链路，覆盖 Android Keystore 与证书轮换。
- [ ] **物理助手按键接入**：适配系统助手按键与全面屏长按手势的无缝呼出。
- [ ] **扩充官方 WASM 插件**：联系人只读、日历与闹钟受控写入（逐次确认）、传感器低频采样、无障碍屏幕语义树按需解析。
- [ ] **去中心化插件分发**：可核验 Ed25519 签名链的插件索引，支持从自定义 URL 或 GitHub Release 导入 `.alp`。
- [ ] **端侧双向语音**：WebSocket / WebRTC 低延迟全双工音频，含端侧 VAD 与打断。
- [ ] **多设备漫游**：一台手机在多个自托管网关之间零感知切换。

---

## 文档与贡献

动手改代码前建议先读这几份：

- 领域术语与设计边界：[`CONTEXT.md`](CONTEXT.md)
- 架构总规格：[模块化插件架构](docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md)
- 协议契约：[Gateway Protocol v2](docs/contracts/gateway-protocol-v2.md)
- 插件包规范：[设备插件包 v1](docs/contracts/device-plugin-package-v1.md)
- 行为规范与提交原则：[`AGENTS.md`](AGENTS.md)
- README 配图的配色、字号与改图规则：[`assets/readme/README.md`](assets/readme/README.md)

几份值得先看的实现记录：

- [双宿主插件同步](docs/reviews/2026-10-19-gateway-plugin-sync.zh-CN.md)：两个插件仓的固定提交与验证方式
- [会话通知兼容性与真机验收边界](docs/reviews/2026-10-18-session-notifications-compatibility.zh-CN.md)：各版本 Android 上通知与气泡能用到什么程度
- [应用更新检测](docs/reviews/2026-10-21-app-update-check.md)：正式版与预览版的更新渠道实现

提交前请确认本次改动涉及的测试通过；编译与全量校验由 CI 流水线复核。

---

## 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。
