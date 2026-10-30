<div align="center">

<a href="#快速开始"><img src="assets/readme/hero-banner.svg" alt="Open Android Intelligence：一个统一的安卓客户端，用于连接多种主流 Agent" width="100%"></a>

# Open Android Intelligence

<p>
  <strong>一个统一的安卓客户端，用于连接多种主流 Agent。</strong><br>
  客户端仅提供设备能力，决策全由 Agent 负责。
</p>

<h3>
  <a href="https://github.com/1rua/open-android-intelligence/releases/latest/download/app-full-release.apk">一键安装 APK</a> · 连接多种主流 Agent · 隐私安全可控
</h3>

<p>
  <sub>
    零中转服务器 · 数据直连自托管网关 · 无需 Root 权限 · 生产级权限隔离<br>
    简体中文 · <a href="README.en.md">English</a>
  </sub>
</p>

<p>
  <a href="https://github.com/1rua/open-android-intelligence/releases/latest"><img src="https://img.shields.io/github/v/release/1rua/open-android-intelligence?style=flat&label=Release&color=34D399" alt="Latest Release"></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/Android-12%2B%20(minSdk%2031)-10B981?style=flat&logo=android&logoColor=white" alt="Android 12+"></a>
  <a href="#怎么工作"><img src="https://img.shields.io/badge/网关-直连多种主流Agent-38BDF8?style=flat" alt="直连主流Agent"></a>
  <a href="#当前状态"><img src="https://img.shields.io/badge/契约-70%20条共享用例-34D399?style=flat" alt="Hermes 与 OpenClaw 共享 70 条契约用例"></a>
  <a href="#安全边界"><img src="https://img.shields.io/badge/安全-不%20Root%20%C2%B7%20默认拒绝-F59E0B?style=flat" alt="安全边界"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/许可证-MIT-94A3B8?style=flat" alt="MIT License"></a>
</p>

<p>
  <a href="#30-秒看懂">30 秒看懂</a> ·
  <a href="#主要功能">主要功能</a> ·
  <a href="#怎么工作">怎么工作</a> ·
  <a href="#安全边界">安全边界</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#当前状态">当前状态</a> ·
  <a href="#文档导航">文档导航</a>
</p>

</div>

---

## 30 秒看懂

> **2026-10-28 授权规则**：Android 客户端是设备能力授权的唯一权威。已安装且启用的设备插件，其能力默认开启；实际执行仍受手机本地显式开关、Android 系统权限、数据范围设置和必要的敏感操作确认约束。OAI Core 负责身份认证、设备绑定、能力发现、协议校验与请求路由；Hermes、DSH 等 Agent 插件负责接入与桥接，两者均不另设账号级设备能力授权。规则已在 `feature/android-device-authority-20261028` 分支实施，待合入 `dev` 和真机验收。实现与当前验证见[实施记录](docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)；旧 `dev` 基线证据见[修复计划](docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)。

> [!TIP]
> ### Open Android Intelligence（OAI）是**一个统一的安卓客户端，用于连接多种主流 Agent**（如 Hermes、DSH、Codex、OpenClaw 等）。
>
> 并将手机硬件能力安全、受控地开放给 AI Agent，让多种远程 Agent 共享手机控制能力，而无需在手机部署独立 Agent。

项目核心构成：

| 核心组件 | 定位与说明 | 代码位置 / 仓库 |
| --- | --- | --- |
| **Android App** | 连接 Core 或 Gateway、管理账号与原生会话，发送用户选定的多模态附件，承载设备能力沙箱 | [`apps/android`](apps/android) |
| **OAI Core** | 单一 Python 服务进程，统一承载连接认证、设备绑定、能力发现与请求路由，并提供 CLI/MCP 与会话适配 | [`oai-core`](oai-core) |
| **Gateway Protocol** | 定义手机与服务端之间的身份认证、设备事件流和全字段封闭数据协议 | [`gateway-contract`](gateway-contract) |
| **会话适配器** | 宿主侧轻量 Session Adapter，直通 DSH 与 Hermes 原生会话，不维护第二份聊天历史 | [`integrations`](integrations) |
| **三方宿主插件** | 面向既有独立网关部署的适配插件（Hermes / OpenClaw / DSH） | [Hermes 插件](https://github.com/1rua/hermes-gateway-plugin) / [OpenClaw 插件](https://github.com/1rua/openclaw-gateway-plugin) / [DSH 插件](https://github.com/1rua/dsh-android-gateway) |

> [!NOTE]
> **无中转架构**：Android App 直接与用户自托管的 OAI Core 服务端通信，项目本身不提供任何公网中转服务器。<br>
> **手机统一授权（当前 feature 规则）**：设备插件安装并启用后普通能力默认开启，Android 决定能否执行；系统权限、独立后台许可和必要敏感操作确认仍各自生效。


---

## 主要功能

<table>
  <tr>
    <td width="50%" valign="top">
      <h3>多会话原生聊天 <img src="https://img.shields.io/badge/已就绪-10B981?style=flat-square" alt="已就绪"></h3>
      <p>在手机上与你连接的主流 Agent 流畅对话。支持多会话独立管理、会话归档、目录整理与灵活排序；全面支持系统相册选图、文件选择器、剪贴板图片粘贴以及系统级多文件分享附件。</p>
    </td>
    <td width="50%" valign="top">
      <h3>多宿主网关直连 <img src="https://img.shields.io/badge/已支持-38BDF8?style=flat-square" alt="已支持"></h3>
      <p>通过同一套 Gateway Protocol v2 协议，无缝接入 <strong>Hermes</strong>、<strong>OpenClaw</strong> 以及 <strong>DSH (DeepSeek Harness)</strong>。直连你自己的服务器，自主掌控全部模型调用与历史记录，拒绝云端数据劫持。</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>WebAssembly 插件沙箱 <img src="https://img.shields.io/badge/架构就绪-34D399?style=flat-square" alt="架构就绪"></h3>
      <p>万物皆插件。受保护的设备插件打包为标准 <code>.alp</code> 包，运行于高性能 WASM 沙箱中，受到 CPU 时间、内存上限（硬配额）、存储容量及并发连接的严密限制，杜绝越权滥用。</p>
    </td>
    <td width="50%" valign="top">
      <h3>零信任安全与屏幕确认 <img src="https://img.shields.io/badge/安全第一-F59E0B?style=flat-square" alt="安全第一"></h3>
      <p>设备能力统一在 Android 客户端管理。插件安装并启用后能力默认开启，本地显式关闭、缺少系统权限或未完成必要确认时不能执行。连接身份认证和设备配对持续生效；目标设计不要求网关账号另行授予设备能力。</p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <h3>封闭契约与防重放校验 <img src="https://img.shields.io/badge/70组用例-10B981?style=flat-square" alt="70组用例"></h3>
      <p>基于 JSON Schema 2020-12 的全字段严格契约验证，多余未知字段整条丢弃；关键指令搭载 Ed25519 签名、时间戳与一次性随机数防重放校验，保障网络抖动下的幂等执行。</p>
    </td>
    <td width="50%" valign="top">
      <h3>原生系统通知与动态跟进 <img src="https://img.shields.io/badge/Android%2012--16-8B5CF6?style=flat-square" alt="Android 12-16"></h3>
      <p>深度融合 Android 原生生态：支持 Android 12–15 的会话通知（MessagingStyle）与悬浮气泡（Bubbles），并率先接入 Android 16 原生 Live Update，任务进度实时感知。</p>
    </td>
  </tr>
</table>

---

## 怎么工作

<div align="center">
  <img src="assets/readme/architecture.svg" alt="架构：Android 客户端通过 Gateway Protocol 连接 Agent 宿主" width="100%">
</div>

整个系统由清晰的三层结构协同运转：

1. **Android 原生宿主端**（客户端）
   - **交互与会话**：提供网关连接配置、多会话管理、系统级附件选发（通过系统 SAF 选择器与分享接口）。
   - **平台内核（Platform Kernel）**：基于 Ed25519 密钥管理设备身份、插件生命周期与权限裁决。它只行使守门仲裁权，不私自预埋业务后门。
   - **安全插件沙箱**：通过 WebAssembly 运行时隔离执行设备扩展插件，严格限制 CPU、内存、本地存储与网络并发资源。
2. **OAI Core 服务端与协议契约层**（连接桥梁与调度中心）
   - **单一服务监听**：`oai serve` 同时暴露 `/v2` 设备协议、`core/v1` 设备调用面及 `/v2/agents` 原生会话面。
   - **设备安全运行时**：由 `oai_core.gateway` 唯一实现排队、超时、防重放、取消与结果确认；独立安装和启动不需要 Hermes。
   - **严格契约守卫**：全字段封闭校验，关键请求搭载 Ed25519 签名、时间戳与 Nonce 防重放，写操作绑定幂等键。
3. **Agent 宿主与原生会话端**（后端大脑）
   - **轻量会话桥（Session Adapter）**：Hermes 与 DSH 通过 loopback 会话桥接入；Codex 走原生 `app-server` 进程。
   - **直读真实会话**：所有操作直通宿主原生存储，不建立影子会话数据库；未接入实例严格隔离，配置即发现边界。
   - **凭据与授权隔离**：共享 Agent 会话仅共享会话文本可见性，绝不继承手机设备凭据；设备操作必须通过受限凭据独立调用。

---

## 安全边界

<div align="center">
  <img src="assets/readme/security-model.svg" alt="安全模型：Agent 提请求，内核查权限，你在屏幕上确认" width="100%">
</div>

当前 feature 分支中的设备能力流程是：**在手机安装并启用插件 → Android 检查本地开关、系统权限与必要确认 → 执行允许的请求**。安装后尚未启用的插件不能执行；已安装且启用的插件无需再为 Gateway 账号勾选能力授权。Core 验证调用凭据、目标设备绑定和协议，Android 作出设备能力的最终决定。

连接认证回答“谁在调用”，设备配对回答“可路由到哪台设备”，设备能力授权回答“手机当前允许做什么”，三者属于不同安全层级。取消旧 Gateway 能力开关不会开放匿名调用、跨账号调用或未配对设备调用。上方图片保留既有版本；当前 feature 分支授权规则以本段和已接受的 ADR-0059 为准，旧 `dev` 基线证据见[修复计划](docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)，实现与验证见[实施记录](docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

### 设备权限管控矩阵

| 敏感能力 | 严格限制与允许边界 |
| --- | --- |
| **通话记录** | **仅允许只读基础元数据**（联系人/号码、时间戳、通话时长）；严禁任何形式的录音、代接、挂断或自动拨号。 |
| **短信记录** | 仅支持受控读取收发记录；**发短信等变更操作必须在手机屏幕上手动逐条点击确认**，绝不允许后台代发。 |
| **应用通知** | 插件安装并启用后能力默认开启；通知访问权限、手机上的应用包名范围与字段设置继续限制实际数据读取，未选中的应用不得外发，不落明文日志。 |

---

## 快速开始

### 0. 准备环境

| 运行端 | 最低要求 | 推荐配置 |
| --- | --- | --- |
| **Android 手机** | Android 12+ (`minSdk 31`) | Android 14+，推荐 8GB 内存 |
| **Agent 宿主环境** | 运行 Hermes、OpenClaw 或 DSH (DeepSeek Harness) | 具备公网或 Tailscale 局域网可达性 |
| **开发者编译工具链** *(仅参与源码开发时需要)* | Node.js `24.18.0` + npm `11.16.0`、JDK 21、Android SDK (`compileSdk 36` / `targetSdk 35`) | Rust 1.80+ (带 `wasm32-unknown-unknown` 目标) |

---

### 1. 手机端安装 App

前往项目 [Releases 页面](https://github.com/1rua/open-android-intelligence/releases) 下载 APK 并安装：

| 版本类别 | 适用群体 | 安装说明 |
| --- | --- | --- |
| **开发预览测试版** | 尝鲜体验者、开发者 | 使用仓库内置统一 Debug 签名，可覆盖安装，CI 每日构建直接下载 |
| **正式稳定版** | 普通日常使用 | 使用官方私有生产密钥签名，严禁与开发预览版交叉覆盖安装（详见 [Android 签名配置](apps/android/README.md#release-signing)） |


---

### 2. 服务端部署与 Agent 接入

**推荐方案（全新部署）**：部署 [OAI Core](oai-core/README.md)，一个服务地址搞定手机连接与多 Agent 会话直通。

#### 🚀 极速一键安装 / 更新（推荐）

通过 `curl` 运行自动化安装向导，脚本将自动检测系统依赖、安装或更新 OAI Core、探测本机 Agent（如 Hermes、DSH、Codex），提示是否一同安装或接入所选 Agent，再引导完成账号与端口配置。接受后，Hermes 插件会尝试自动安装并启用；DSH 会在工具可用时自动注册 bundle，否则显示手动命令；Codex 配置写入 Core 配置。拒绝 Agent 接入时仍可只安装 OAI Core。已有账号不会被向导重置：

```bash
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/install.sh | bash
```

若后续需要**重新配置**端口、初始化新账号或调整 Agent 接入，可随时运行独立配置脚本：

```bash
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/setup.sh | bash
```

> **提示**：
> - 若已克隆本仓库，可在根目录直接运行 `./scripts/install.sh` 进行就地安装与交互式配置。
> - 在本地仓库中亦可随时运行 `./scripts/setup.sh` 进行独立配置。
> - 向导安装完成后，会自动将 `~/.local/bin` 加入环境变量 PATH，并在其中部署 `oai` 命令：**直接输入 `oai` 即可一键启动 Core 服务**；带参数时（如 `oai devices`、`oai mcp`）作为 CLI 客户端管理工具。

<details open>
<summary><b>手动安装 OAI Core 服务端（点击收起）</b></summary>

需 Python 3.11+。在仓库根目录安装与启动：

```bash
# 1. 创建虚拟环境并安装 oai-core
python3 -m venv .venv
source .venv/bin/activate
pip install -e ./oai-core

# 2. 服务端本地创建账号（交互式输入密码）
oai account create --config oai-core/examples/core.json --username alice

# 3. 启动 Core 统一服务监听（默认 127.0.0.1:8045）
oai serve --config oai-core/examples/core.json
```

服务就绪后，在 `oai-core/examples/core.json` 的 `agents` 数组中按需接入目标 Agent：
- **DSH**：安装轻量 bundle [`integrations/dsh-session-adapter`](integrations/dsh-session-adapter/README.md)（默认端口 8791）；
- **Hermes**：安装轻量插件 [`integrations/hermes-session-adapter`](integrations/hermes-session-adapter/README.md)（默认端口 8792，需账号一对一映射）；
- **Codex**：配置 `codex app-server` 与专属 `codexHome`，通过 `envFrom` 映射模型凭据。

手机端与服务端配对后，可在操作人的独立环境配置 `oai` 或 MCP 调用凭据。能力可用性仍须由 Android 检查；本分支的授权实现与验证见[实施记录](docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)，e5 `dev` 基线排查见[修复计划](docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)：
```bash
oai login --url http://127.0.0.1:8045 --username alice --device <DEVICE_ID>
oai capabilities
oai mcp  # 启动标准 FastMCP stdio 接口
```
</details>

#### 卸载 OAI Core

卸载脚本支持 Linux/macOS，使用系统 Python 3.11+ 标准库，可直接通过 `curl | bash` 运行。两种模式都将待移除内容移动到 `/tmp/open-android-intelligence-trash/<uid>/<批次>/`，不做安全擦除：

- **保留配置（默认，`--keep-config`）**：移走 `.venv`、`start-core.sh`，以及指向本次安装的 `oai` 快捷命令和开机项；保留源代码、`core.json` 和 `runtime` 全部数据。再次对同一目录运行安装器即可原地重装。
- **整体回收（`--purge`）**：将整个安装目录移入回收目录。位于安装目录之外的 `storageRoot`、`masterKeyFile`、`codexHome` 以及 Hermes、DSH、Codex 宿主本身均保留，脚本会提示外置数据位置，不自动清理共享数据。

```bash
# 默认保留配置、数据和源代码
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/uninstall.sh | bash

# 整体回收安装目录
curl -fsSL https://raw.githubusercontent.com/1rua/open-android-intelligence/dev/scripts/uninstall.sh | bash -s -- --purge
```

默认处理 `~/.oai-core`，可用 `--dir PATH` 或 `OAI_DIR` 指定安装目录，不会自动推断当前仓库。先追加 `--dry-run` 可预览操作；在仓库中例如运行 `bash scripts/uninstall.sh --purge --dry-run --dir "$HOME/.oai-core"`。`--yes` 跳过确认，`--help` 查看参数。回收目录可能含配置、账号数据和密钥，请自行复核并处理。

<details>
<summary><b>既有部署：为 Agent 宿主安装独立网关插件（点击展开）</b></summary>

以下为固定旧版本的独立网关部署记录，不代表这些外部插件已适配 2026-10-28 的目标授权设计。它们不得用于给新 Core 增加设备能力授权；新接入使用上文 Core 与 Session Adapter。既有部署需单独评估兼容性。Hermes 和 OpenClaw 的旧部署仍需保留原账号主密钥（Master Key）：

#### 配置 Hermes 宿主
```bash
hermes plugins install 1rua/hermes-gateway-plugin --ref 190441415652ae34fbd1cb8cc485f6208fff944b --enable
hermes open-android-intelligence account create -u my_user -p <设置密码> --confirm-local
```

#### 配置 OpenClaw 宿主
```bash
export OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE=/private/path/gateway-master-key.bin
openclaw plugins install git:github.com/1rua/openclaw-gateway-plugin@c7c087b9fe28adcc658a1cbdb71689a377b65214
openclaw plugins inspect open-android-intelligence-gateway --runtime --json
```

#### 配置 DSH (DeepSeek Harness) 宿主
```bash
git clone https://github.com/1rua/dsh-android-gateway.git
cd dsh-android-gateway && npm ci && npm run build
# 在 DSH 会话中安装：plugin_manager action=install_bundle target=/absolute/path/to/dsh-android-gateway
```
</details>

---

### 3. 手机端连接网关

1. 打开手机 App，输入你的网关访问地址（支持 HTTP/HTTPS，推荐内网或域名反代地址）。
2. 输入刚才创建的账号与密码，点击「连接配对」。
3. 手机端成功完成握手后，证书信息与凭据将安全保存在本机 Android Keystore 中，后续启动无需重复输密。

<details>
<summary><b>HTTPS 证书公钥指纹（SPKI SHA-256）配置指引</b></summary>

在启用 HTTPS 时，为杜绝中间人攻击，需将服务端 TLS 证书的 SPKI SHA-256 摘要配置在 Gateway 插件中：

```bash
# 计算服务器证书公钥的 SHA-256 指纹
openssl x509 -in gateway.crt -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256
```

将计算出的公钥指纹写入网关环境变量：
```bash
OPEN_ANDROID_INTELLIGENCE_GATEWAY_TLS_SPKI_SHA256=sha256:<64位十六进制字符>
```
未配置指纹时握手会返回 `null`，客户端将拒绝提交密码凭据。明文 HTTP 虽可降级连接，但 App 界面会持续显示橙色未加密警告。
</details>

---

### 4. 设备能力按需启用

当前 feature 分支规则下，在手机的插件管理中安装并启用设备插件后，其普通设备能力默认开启。用户可在 Android 本地明确关闭能力；系统权限、通知应用范围、后台许可和必要的单次敏感操作确认继续生效。Agent 使用已认证且绑定目标设备的 Core CLI/MCP/API，先读取 `oai capabilities` 的实际声明，再按 Schema 调用。

旧 `dev` 基线可能显示“当前 Gateway 账号未启用；请通过 Gateway 能力配置开启”。这是旧授权判断与新架构不一致的表现，不能把回到 Hermes 配置能力开关作为目标操作流程。Android 唯一授权修复已在 `feature/android-device-authority-20261028` 分支实施，待合入 `dev` 并完成真机验收；实现和验证状态见[实施记录](docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

> [!WARNING]
> **开发中状态说明**：设备插件打包、Ed25519 签名校验、WASM 沙箱引擎及配额约束已有自动化覆盖；Android 本地授权、能力发布和设备请求执行栅栏已在 `feature/android-device-authority-20261028` 分支接通。完整 App Fast 套件仍在验证，真机全链路尚未验收。授权实现及证据见[实施记录](docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)，其他能力同步进展见[通知能力同步记录](docs/reviews/2026-10-20-agent-notification-capabilities.zh-CN.md)。

---

### 5. 源码编译与调试（开发者）

<details>
<summary><b>契约一致性测试（Hermes / OpenClaw 70 组用例）</b></summary>

```bash
npm ci

# 检出固定版本的 OpenClaw 插件源码并安装依赖
git clone https://github.com/1rua/openclaw-gateway-plugin.git .openclaw-gateway-plugin
git -C .openclaw-gateway-plugin checkout "$(python3 -c 'import json; print(json.load(open("openclaw-plugin-pin.json"))["revision"])')"
npm ci --prefix .openclaw-gateway-plugin --ignore-scripts

# 运行 TypeScript 与 Python 两端一致性比对测试
OPENCLAW_PLUGIN_ROOT="$PWD/.openclaw-gateway-plugin" npm run gateway:v2:conformance
```
</details>

<details>
<summary><b>构建官方设备插件包（.alp）</b></summary>

```bash
# 编译 Rust/WASM 产物
cargo build --locked --offline --target wasm32-unknown-unknown --release --manifest-path plugins/Cargo.toml
cargo test --locked --offline --manifest-path plugins/Cargo.toml

# 使用发行密钥进行确定性封装签名
npm ci --prefix plugin-tooling
OPEN_ANDROID_INTELLIGENCE_PLUGIN_SIGNING_KEY_FILE=/private/path/plugin-seed.bin \
  npm run build:references --prefix plugin-tooling
```
产物输出在 `plugins/dist/` 目录：包含 `notifications`、`sms`、`call-log` 等官方参考插件。
</details>

<details>
<summary><b>编译 Android 客户端</b></summary>

```bash
cd apps/android
./gradlew --no-daemon check :app:assembleFullDebug
```

所有 APK 模块统一采用仓库预置的 `debug.keystore` 签名，确保本地开发、CI 构建与测试包指纹完全一致，彻底避免 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

验证调试包签名指纹：
```bash
apps/android/tools/verify-debug-signing.sh apps/android/app/build/outputs/apk/full/debug/app-full-debug.apk
```
</details>

---

## 当前状态

本表真实记录各项能力的自动化测试、宿主集成和物理真机验证状态。我们严格区分测试替身（Test Doubles）与真实验证，不拿模拟通过粉饰真机能力。

| 业务领域与核心能力 | 当前验证依据与范围 | 真实完备度与状态 |
| --- | --- | --- |
| **OAI Core 服务端与会话桥** | OAI Core 209 项测试通过；DSH 会话桥 16 项测试通过 | ✅ 代码与协议测试通过；物理真机与生产模型端到端联调待闭环 |
| **Hermes / OpenClaw 契约一致性** | 两端各自 70 组 TS/Python 共享契约用例逐条对齐比对 | ✅ 覆盖两端网关适配器协议一致性，不代表终端执行全链路 |
| **Hermes 网关适配器** | pytest 单元测试 + 真实 aiohttp SSE/WS 握手、签名与凭据撤销测试 | ✅ 协议层已覆盖；宿主上下文历史与工具执行结果回传待完善 |
| **OpenClaw 适配器插件** | 独立仓固定版本、宿主真实运行时动态载入、70 组共享测试用例 | ✅ 安装与通讯协议通过验证；Android 真机端对端待闭环 |
| **DSH (DeepSeek Harness) 插件** | 版本 0.2.0；201 项自动化测试；[DSH 与 Android 模拟器集成记录](https://github.com/1rua/dsh-android-gateway/blob/main/docs/reviews/2026-10-21-feature-completion.md) | ✅ 会话核心主流程已跑通；设备能力闭环待手机端插件与真机验证 |
| **设备插件打包与 WASM 运行时** | 确定性打包签名、WASM ABI 规范对接、内存存储预算配额及恢复测试 | ✅ 核心底层库有自动化覆盖；本分支已接通 Android 本地授权、能力发布与执行栅栏；App Fast 及真机执行验收仍待完成 |
| **官方参考插件业务** | 通知读取、短信、通话记录经平台内核权限拦截审查 | ⚠️ 需验证手机统一授权规则、系统权限与实际执行；统一授权修复已在 feature 分支实施，待合入和真机验收 |
| **Android 客户端界面与账号** | Gradle 编译、Robolectric 测试与模块自动化测试 | ✅ 账号管理、身份认证、事件总线与离线加密存储已接入；物理真机测试待开展 |
| **Companion 私网通道** | tsnet pump 传输管线与可信 IPC 令牌签发未就绪 | ⛔ 入口已切断关闭，暂不签发私网通道 |
| **物理真机端到端体验** | Android Keystore 硬件加解密、证书静默轮换、SAF 动态授权、真实宿主互联 | ⏳ 待真机验收测试闭环 |

---

## 文档导航

所有项目规范与实施记录均完整归档在仓库内。请根据需要查阅对应文档：

### 用户指南与配置

| 文档名称 | 核心内容 | 快速入口 |
| --- | --- | --- |
| **设备权限与读取配置** | 手机端通知、通话记录等设备读取权限开启与白名单配置 | [查看指南](docs/mvp/device-reads-and-reply-notifications.md) |
| **Android 端开发指南** | Android App 架构划分、签名配置与多变体构建说明 | [查看指南](apps/android/README.md) |
| **应用更新机制说明** | 正式版与预览版双通道更新策略与版本比对逻辑 | [查看说明](docs/reviews/2026-10-21-app-update-check.md) |
| **系统通知兼容性报告** | Android 12–16 各版本通知、气泡及厂商定制 ROM 限制边界 | [查看报告](docs/reviews/2026-10-18-session-notifications-compatibility.zh-CN.md) |

### 开发者与架构规范

| 文档名称 | 核心内容 | 快速入口 |
| --- | --- | --- |
| **设备能力授权修复计划** | e5 `dev` 基线的历史排查、目标职责、兼容迁移与验收清单 | [查看计划](docs/reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md) |
| **Android 唯一授权权威（ADR-0059）** | 已接受的设备授权决策；分支实现与当前验证见[实施记录](docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md) | [查看 ADR](docs/adr/0059-android-is-the-sole-device-capability-authority.md) |
| **领域术语与概念边界** | 统一术语定义、核心实体交互与安全责任边界 | [`CONTEXT.md`](CONTEXT.md) |
| **OAI Core 架构事实基线** | 每条架构结论的代码出处与实测依据 | [查看基线](docs/architecture/2026-10-26-oai-core-architecture-baseline.zh-CN.md) |
| **OAI Core 统一服务说明** | 单一服务监听、CLI/MCP 使用与原生会话接入 | [`oai-core/README.md`](oai-core/README.md) |
| **统一 Core 迁移方案** | 协议增量、分阶段交付、数据兼容与回滚 | [查看方案](docs/plans/2026-10-25-unified-core-migration.zh-CN.md) |
| **原生会话适配器部署** | DSH、Hermes 与 Codex 宿主接入、鉴权与验证 | [查看方案](docs/plans/2026-10-25-native-session-adapters.zh-CN.md) |
| **第三方 Agent 适配器开发指南** | 统一 Agent 插件接入协议、能力声明与独立适配器开发模板 | [查看开发指南](docs/guides/agent-adapter-development-guide.zh-CN.md) |
| **模块化插件架构规格** | 插件系统总体架构设计规格书 | [查看规格](docs/superpowers/specs/2026-08-24-modular-plugin-architecture.md) |
| **Gateway Protocol v2 规范** | 手机与 Agent 网关通信契约与 JSON Schema 封闭规范 | [查看契约](docs/contracts/gateway-protocol-v2.md) |
| **设备插件包 v1 规范** | `.alp` 插件包元数据定义、Ed25519 签名及沙箱约束 | [查看规范](docs/contracts/device-plugin-package-v1.md) |
| **DSH 宿主网关插件** | 为 DeepSeek Harness 打造的官方接入插件与用例集 | [访问仓库](https://github.com/1rua/dsh-android-gateway) |
| **网关密钥管理 (ADR 0023)** | SQLite 与明晰 Gateway Secret 管理决策记录 | [查看 ADR](docs/adr/0023-use-sqlite-with-explicit-gateway-secret-management.md) |
| **明文配对安全警告 (ADR 0047)** | 允许内网明文配对但持续显示醒目安全警告决策 | [查看 ADR](docs/adr/0047-allow-plaintext-gateway-pairing-with-explicit-warning.md) |
| **视觉资产与设计规范** | README 静态矢量图的色值 Token 与字号红线规则 | [设计规范](assets/readme/README.md) |
| **Agent 行为准则** | 开发与贡献者规范、测试验证红线与 Git 提交要求 | [`AGENTS.md`](AGENTS.md) |

---

## 项目目录

<details>
<summary><b>点击展开完整代码仓库结构</b></summary>

```text
open-android-intelligence/
├── apps/android/                  # Android 原生宿主应用（AGP 8.9.2、SDK 36、Kotlin 2.1.20）
│   ├── app/                       # 主入口 APK：多会话聊天、账号凭据、系统设置、附件管理
│   ├── platform-kernel/           # 平台内核：权限裁决引擎、插件生命周期、安全加密原语
│   ├── plugin-package/            # .alp 插件包解析、Ed25519 验签与安装管理器
│   ├── plugin-runtime-wasm/       # WebAssembly 高性能沙箱运行时
│   ├── conversation-ui/           # Material 3 会话界面交互组件库
│   ├── assistant-holder/          # 系统全局助手角色与按键手势触发器
│   ├── tailscale-companion/       # 可选用户态 Tailscale 连接插件（Companion APK）
│   └── ...                        # 领域模型、Room 数据存储与契约端口模块
├── gateway-contract/              # Gateway Protocol v2 契约与 Hermes / OpenClaw 一致性测试套件
│   ├── schemas/                   # JSON Schema 2020-12 严格封闭式定义
│   ├── vectors/                   # 跨语言通用测试向量（70 组标准用例）
│   ├── src/                       # TypeScript 契约实现参考
│   └── tools/                     # Hermes / OpenClaw 一致性自动化测试执行工具
├── oai-core/                      # OAI Core 统一服务、CLI、MCP 及会话适配器（Python 3.11+）
│   ├── src/oai_core/              # 服务监听、鉴权、设备调度与各 Agent 会话适配器实现
│   ├── examples/                  # 部署配置示例（core.json）
│   └── tests/                     # Core 协议、设备事件、防重放与原生 SDK 契约回归
├── plugins/                       # 官方参考设备插件源码（Rust/WASM + .alp 构建产物）
├── plugin-tooling/                # 插件打包与确定性签名工具链（Packaging CLI）
├── integrations/                  # 共享集成环境与轻量原生会话适配器
│   ├── dsh-session-adapter/       # DSH 宿主侧 Cordis Bundle（只读写原生会话，端口 8791）
│   └── hermes-session-adapter/    # Hermes 宿主侧仅会话插件（按账号隔离会话，端口 8792）
├── docs/                          # 产品规格书、ADR 架构决策记录与技术评审文档
├── scripts/                       # 自动化安装向导与运维脚本（install.sh）
├── assets/readme/                 # README 所需的精美矢量 SVG 资产与设计 Token 规范
├── legacy/                        # 已冻结归档的历史版本 Bridge 与过渡组件
├── openclaw-plugin-pin.json       # 锁定 OpenClaw 插件的版本、标签与 Git Commit SHA
└── CONTEXT.md                     # 统一领域术语与设计边界说明书
```
</details>


## 许可证
MIT

