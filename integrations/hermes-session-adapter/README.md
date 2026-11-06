# Hermes 原生会话适配器

`oai-hermes-session-adapter 0.2.0` 是 OAI Core 的可选组件。Core 直接提供 Android 设备协议、连接认证、设备绑定和请求队列；此包只连接 Hermes 的原生 SessionStore、SessionDB、消息 dispatcher 与生命周期。安装或启动 Core 不需要安装此包或 Hermes SDK。

```text
Android App ↔ OAI Core ↔ Hermes Session Adapter ↔ Hermes
                        └ 原有账号绑定 → 原生会话/压缩链
```

Core 端的 `oai_core.adapters.hermes.HermesRemoteSessionAdapter` 只通过内部 HTTP 会话桥访问此适配器。原生宿主实现位于本包 `oai_hermes_adapter`；设备状态机只有 `oai_core.gateway` 一份。适配器不创建 GatewayCore、GatewayAccount、设备请求队列或设备认证服务，也不注册设备工具。

## 设备授权边界

**2026-10-28 设备授权规则**：Android 客户端是设备能力授权唯一权威。插件安装且启用后普通前台能力默认开启，本机显式开关、系统权限、后台许可、数据范围与必要敏感确认仍限制执行。Core 负责认证、绑定、发现、验证与路由，Hermes Session Adapter 仅负责会话接入与桥接，不追加账号级设备能力授权，也不需要旧 Gateway 能力 `setup`。规则已在 `feature/android-device-authority-20261028`，待 PR 合入 `dev`；分支实现见 [ADR-0059](../../docs/adr/0059-android-is-the-sole-device-capability-authority.md) 和[实施记录](../../docs/reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。外部旧版本的兼容性须按其版本单独评估。

会话桥 Token、`OAI_HERMES_ACCOUNTS` 宿主账号清单与 Core 到 Hermes 的一对一映射继续生效。它们保护会话入口与账号隔离，不是设备 capability 授权表。取消设备账号能力开关不会取消这些检查。

## 安装与配置

从已安装的 OAI 仓库运行配置入口，选择「3. Agent 接入」、Hermes 候选，并确认注册：

```bash
./scripts/setup.sh
```

会话桥不可用或版本过旧时，入口会再次询问是否安装或更新本机 Hermes 会话适配器。确认后，在 Hermes 自己的 Python 环境中安装当前仓库的 Core 和 Adapter 两个包，将标准入口放入所选 `HERMES_HOME` 的用户插件目录，再通过 Hermes 官方命令启用。已有旧插件和配置保留备份。宿主环境没有 pip 时优先使用 uv。

`hermes plugins install` 的参数是官方插件清单中的名称、Git URL 或仓库简称，固定 SDK 不支持把本地源码路径直接作为参数；原文中的 `hermes plugins install ./integrations/hermes-session-adapter` 和直接传入 wheel 安装目录的示例不适用。尚未发布到包索引的 Core 与 Adapter 使用同一批次的本地源码或 wheel，不能依赖插件管理器从索引下载 Core。由其他部署工具预装 wheel 时，仍需通过 Hermes 标准用户插件目录提供 `plugin.yaml` 和加载入口，并启用 `oai-hermes-session-adapter`。

安装命令完成后仍须通过真实会话桥握手。若 Hermes 进程尚未重新加载，入口保持未注册并提示重载后重试；不要只凭插件文件存在判断连接成功。

宿主进程通过权限为 `0600` 的环境文件或服务管理器提供以下配置；不要把真实 Token 写进共享启动脚本或提交到仓库：

| 配置 | 含义 |
| --- | --- |
| `OAI_HERMES_GATEWAY_ROOT` | 原 Hermes Gateway 的 `accounts` 目录，必须已存在；不会新建或合并账号库 |
| `OAI_HERMES_ACCOUNTS` | 可访问的既有 Hermes 账号 ID 的 JSON 数组，必须显式列出且不重复 |
| `OAI_HERMES_SESSION_TOKEN` | 至少 32 字符的内部会话桥密钥，Core 远程配置使用同一值 |
| `OAI_HERMES_SESSION_PORT` | 默认为 `8792`；监听地址固定为 `127.0.0.1` |
| `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE` | 原账号库使用的既有主密钥文件；保留原受限路径、所有者和 `0600` 权限 |

Core 的 Hermes Agent 配置须明确提供 `accounts` 对照表，例如 `{"core-alice":"hermes-alice","core-bob":"hermes-bob"}`。不同 Core 账号不能映射同一 Hermes 账号。会话桥 Token 只认证内部桥接请求，不能作为 Core 账号 Token、Android 会话或设备授权凭据。Hermes 的设备工具统一配置 OAI Core CLI/MCP/API，并使用经认证、绑定目标设备的独立 Core 凭据；会话接入本身不授予设备调用身份，也不能改变 Android 的能力决定。

## 迁移与回滚

1. 保留旧 Gateway 版本、配置、原账号目录和主密钥；先停旧 Gateway 写入，再离线备份完整账号目录（包含 SQLite WAL/SHM）及单独的主密钥。密钥备份保持受限权限。
2. Core 设备库与 Hermes 原账号库使用不同目录。此组件不导入、合并或重置账号、配对、设备授权或原生历史数据库。
3. 安装配套新版本，配置原账号目录和原密钥，再启用会话适配器。每个允许账号持有一个 SQLite `EXCLUSIVE` 连接；旧进程仍占用库时激活失败，适配器运行期间竞争写入被数据库拒绝。
4. 先验证原会话 ID、历史、压缩链、账号隔离与消息收发，再切换客户端。此适配器与旧 Gateway 不能同时作为原目录的写入者。
5. 回滚时先停新适配器，释放独占连接，再恢复旧版本及原配置。未改变数据库格式，不需要降级迁移；若需恢复备份，使用整份离线快照和匹配密钥，保留当前目录供复核，避免覆盖唯一数据副本。

原生会话标题、归档、删除和压缩链操作调用 Hermes 的原生存储接口，终端修改与 App 读取使用同一数据。未绑定、来源不明确、跨账号或原生宿主接口不兼容时拒绝操作，不扫描全局历史冒充账号会话。结构化审批无法生成时会保留原命令和待审批状态，改由宿主发送带 `/approve`、`/deny` 说明的文本审批；这不等同于用户拒绝。忙碌会话还支持经宿主原生 `steer()` 注入当前轮次；不支持、generation 过期或带附件时返回明确不可用，不会伪装成 Queue。

## 验证范围

自动化测试覆盖 Core 远程账号映射、内部桥认证、消息体大小、流式回复、旧账号库排他写入，以及固定 Hermes SDK 下的标准插件加载、旧平台会话绑定、真实 SessionStore/SessionDB、压缩链、创建/重命名/归档/删除/取消和终端标题同步。测试使用临时合成账号及消息处理器，不调用真实模型，不读取用户的生产账号。

这些结果证明所测固定 SDK 的存储和宿主接口兼容性；真实 Hermes 模型服务、生产账号部署和 Android 真机连通仍需单独验收。完整迁移清单及证据分层见[Core 独立化迁移文档](../../docs/architecture/2026-10-26-core-independence-migration.zh-CN.md)。
