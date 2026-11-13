# OAI Core 0.2 独立部署与 Hermes 安全切换

**部署说明更新**：独立部署保留原账号、配对、主密钥和宿主历史；管理变更需要 Core 管理员认证。设备能力按 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md) 由 Android 唯一授权，本地策略、系统权限和必要确认仍然生效。本说明包括历史 Hermes 切换方法，不表示生产服务、自动接入或 Android 真机已完成验收。

本次依赖方向为 `Hermes Session Adapter → OAI Core`。Android 设备协议、认证、配对、
授权版本、请求排队与 Claim/Result/ACK 由 `oai_core.gateway` 提供唯一实现。
Hermes 是可选的原生会话宿主。DSH、Codex 与纯设备 CLI/API/MCP 无需安装 Hermes。
OpenClaw 沿用现有部署，本阶段不更新。

## 独立安装 Core

使用 Python 3.11 或更高版本。从仓库根目录执行：

下列 pip 命令仅用于新环境的软件包检查，不会初始化管理员，也不是已有部署的升级入口。已有部署请先使用经过认证的安装流程。

```sh
python -m venv .venv
.venv/bin/python -m pip install ./oai-core
.venv/bin/python -m pip check
```

也可先构建再将 wheel 安装到不含仓库源码的新环境：

```sh
python -m pip install build
python -m build oai-core --outdir ./dist
python -m venv .venv-clean
.venv-clean/bin/python -m pip install ./dist/open_android_intelligence_core-0.2.0-py3-none-any.whl
.venv-clean/bin/python -m pip check
```

wheel 与源码分发包中的协议资产均由构建过程从仓根 `gateway-contract` 生成。
不依赖网络下载旧 Gateway 包，也不维护另一份可独立修改的协议源码。
`jsonschema`、`cryptography`、`aiohttp`、`mcp` 与 `yarl` 是 Core 直接声明的运行依赖。同一 Hermes 会话桥的多入口配置必须保持全局一对一账号映射；服务启动会按与实际 HTTP 客户端相同的 URL/IDNA 规则核验，冲突配置拒绝注册。
`open-android-intelligence-hermes-gateway` 和 Hermes SDK 均不在 Core 的依赖图中。

新部署应先在可信源码检出中运行 `./scripts/install.sh`，按提示创建首个管理员并保存本机恢复码；不能在尚无身份库时直接用 `account create` 创建首个账号。wheel 只完成软件安装，不等于已初始化管理员、配置服务或完成验收。

管理员在服务器本机建立日常账号；多实例时可加 `--config`：

```sh
oai account create alice
oai setup
# 从安装目录启动，或使用安装时配置的服务
./start-core.sh
```

输入管理员的 Core 账号密码，然后设置新账号密码；新增账号默认是普通用户。Android 使用相同的账号和可达的 Core 地址登录、配对。CLI 登录无需设备 ID：`oai login --url https://core.example`，然后用 `oai devices` 和 `oai capabilities` 查看本人设备与能力；可按需以 `--device` 指定目标。

配置路径相对于配置文件，`contractRoot` 可省略并使用包内契约；主密钥必须在账号数据目录之外。手机远程接入时配置可达的监听地址和 TLS 证书、私钥；反向代理终止 TLS 时配置与手机实际看到的证书一致的 `tlsSpkiSha256`。仅在受控开发环境显式启用 `allowInsecureHttp`。

## 安装与配置向导

```sh
./scripts/install.sh
./scripts/setup.sh
```

新安装按提示创建管理员；已有部署在安装、配置、服务管理和卸载前须验证现有 Core 管理员账号及其 Core 登录密码。旧部署迁移先指定原账号、验证原密码、停止旧写入者并完成一致备份；指定账号成为管理员，其他旧账号保持普通用户。切勿先更新旧环境或下载不受信任的 helper 再交出密码。具体限制及失败恢复见[安装说明](../../scripts/README.md)。远端一键安装和自动依赖处理仍在完善，不能宣称全流程已验收。

`oai setup` 认证后可管理网络、账号、Agent 与服务；候选 Agent 必须验证身份并真实握手、确认注册，没有候选时显示未连接。配置及事务备份应受限访问；保留已有 `storageRoot`、`masterKeyFile`、TLS、账号密码、配对和宿主历史，不静默替换主密钥或重设密码。查看、退出菜单不应改写配置。

会话桥凭据写入 `runtime/secrets/adapter-tokens.env`（`0600`，父目录 `0700`）。
凭据由 Python 安全随机源生成；重复配置优先保留已有值，不自动旋转。
迁移时应保留已有桥凭据；无法安全恢复时停止并由管理员核对受保护的原始凭据，禁止静默替换。
`start-core.sh`（`0700`）只引用私密文件，不包含任何 Token 或账号密码的字面值，
并在读取前拒绝不属于当前用户、指向符号链接或对其他用户可读写的凭据文件。
不要把生成的运行目录、配置或凭据提交到仓库。

当需要将同一个桥 Token 提供给另一个服务身份时，由管理员通过权限受限的配置
管理方式分别提供给 Core 与宿主。不要把整个 Core 环境传给共享 Agent，不要把
令牌放入命令行参数、公开启动脚本、Shell 历史或文档。
桥 Token 只能认证会话接口，不能登录 Core 或授予设备操作权限。

## 二次验证、账号与恢复

服务端 2FA 默认关闭；管理员在 `oai setup` 输入 `2fa`，先绑定验证器并安全保存账号备用码和独立管理员恢复码，才能确认开启。开启后 CLI、Android 和管理操作按提示提供验证码，正常会话续期不重复要求。关闭策略不删除已有绑定。

删除账号或修改密码前核对目标和会话撤销影响；删除或降权最后一个可用管理员应被拒绝。忘记管理员密码时，仅在服务端本机执行 `oai recover ADMIN_USERNAME`，交互输入预先登记的独立管理员恢复码及新密码，再确认目标；恢复会撤销旧会话，但不自动关闭 2FA。不要把密码、验证码或恢复码放进命令参数、普通环境变量或日志。

## 安装可选 Hermes Session Adapter

目标是由可信适配器按稳定 Core 账号准备专属原生空间，已有身份关联需确认，不复制密码或继承他人历史；自动专属空间仍在集成，尚未验证通过。下列显式映射是旧部署兼容与切换记录，不是新用户的必做步骤。

先在 Core 的独立部署中完成设备服务验证，再准备 Hermes 切换。
在 Hermes 自己的 Python 环境中安装配套版本；下面命令从 OAI 仓库根目录执行：

```sh
python -m pip install ./oai-core ./integrations/hermes-session-adapter
```

适配器包约束 Core 为 `>=0.2,<0.3`，只调用 Core 的公共存储库与会话接口。
它不构造 `GatewayCore`、设备请求队列或设备认证服务，不注册 Android 设备工具。
Agent 设备工具应通过 Core CLI/MCP/API 使用个人账号认证，目标设备在请求时按规则选择；共享宿主不继承个人设备凭据。

宿主所需配置：

| 配置 | 含义与限制 |
| --- | --- |
| `OAI_HERMES_GATEWAY_ROOT` | 原 Gateway 的既有账号目录，必须存在，不指向 Core 新设备数据库 |
| `OAI_HERMES_ACCOUNTS` | 唯一且明确的 JSON 账号清单，例如 `["legacy-alice","legacy-bob"]` |
| `OAI_HERMES_SESSION_TOKEN` | 至少 32 字符的随机会话桥 Token，须与 Core 对应 `tokenEnv` 相同 |
| `OAI_HERMES_SESSION_PORT` | 默认 `8792`，Adapter 仅监听 `127.0.0.1` |
| `OPEN_ANDROID_INTELLIGENCE_GATEWAY_MASTER_KEY_FILE` | 原主密钥文件；保留原安全访问方式、所有者与 `0600` 权限，不换钥 |

不要在共享启动脚本中直接写入这些配置中的私密值。使用服务管理器的受限环境
配置或仅服务身份可读的配置文件；本文不提供带真实值的启动脚本。

配置 Core 的 `agents` 条目：

```json
{
  "type": "hermes",
  "id": "hermes",
  "name": "Hermes",
  "url": "http://127.0.0.1:8792",
  "tokenEnv": "OAI_HERMES_SESSION_TOKEN",
  "accounts": {"alice": "legacy-alice", "bob": "legacy-bob"}
}
```

已有部署沿用的显式映射必须是一对一的 `Core Account → Hermes Account`。
App 传入的会话 ID、账号名或桥 Token 无权改变该映射。
跨账号的会话、历史、管理和发送请求均须拒绝。

## 原数据备份、切换与回滚

1. 记录现有插件提交、Hermes 版本、配置、账号目录与主密钥位置。保留原安装和
   原生 SessionStore/SessionDB；先在隔离的合成账号环境验证新包。
2. 停止旧 Gateway 写入后，做一致备份：原 Gateway 每个账号数据库及 SQLite
   `-wal`/`-shm` 文件、原生 SessionStore/SessionDB 和压缩链、原配置均保留。
   主密钥单独备份到权限受限的位置，不混入普通账号备份。
3. Core 设备数据库使用独立目录。禁止自动合并旧 Hermes 账号库和 Core 设备库，
   禁止让新设备服务与旧会话后端指向同一个账号目录。
4. 给 Hermes 进程配置上述变量，使用原生插件加载器安装入口：

   ```sh
   hermes plugins install ./integrations/hermes-session-adapter --enable
   ```

   旧 Gateway 必须保持停止状态，再启动新 Session Adapter。Adapter 对每个旧
   账号库持有持久独占锁；锁不可用时拒绝启动。不能靠不同端口让新旧版本同时写库。
5. 先确认原历史、原生会话 ID、标题和压缩链均可读取，再检查创建、消息、流式
   回复、重命名、归档、删除、取消和终端与 App 的一致性。通过后才移除旧服务
   的自启动设置；仍保留原包与备份，以便回滚。

本次迁移复用原数据格式，不执行批量改写历史或账号数据库，不改变原主密钥。
包与配置版本升级为 Core 0.2，未引入外部设备协议版本变更。部署人员应把升级前
备份、包版本、验证结果和回滚命令保存在自己的受限运维记录中。

回滚时先停新 Adapter，确认它已释放账号库独占锁，再恢复旧插件与原配置、
原目录、原密钥并启动。不要在仍有写进程时覆盖数据库。
优先保留切换后的兼容数据；若必须恢复备份，先备份当前状态，并明确恢复备份
会放弃该备份之后新增的会话和管理操作。Core 设备库不参与 Hermes 会话回滚。

## 自动化验证与实际验收的区别

CI 的 `oai-core.yml` 分别检查：

- Core wheel/sdist 构建、干净环境只安装 Core、依赖审查、无 Hermes 的真实启动；
- `gateway_runtime` 原协议向量、签名、防重放、跨账号、授权撤销、Claim/Result/ACK、
  幂等、超时、取消、崩溃恢复，以及 CLI/MCP 和 DSH/Codex 客户端回归；
- 独立可选 Adapter 与固定 Hermes SDK 的原生 SessionStore、历史和生命周期契约；
- DSH 桥回归，以及 Android 连接和本地状态恢复测试。

主 `ci.yml` 中固定旧 Gateway 提交仅作为历史 HTTP 兼容夹具。它在单独的 Python
虚拟环境运行，不进入 Core 独立性验证的环境；旧夹具通过也不代表新 Core 的
真实跨端部署已经验收。

本地可执行：

```sh
python -m pytest oai-core/tests tests/test_install_script.py -q \
  --ignore=oai-core/tests/test_hermes.py --ignore=oai-core/tests/test_hermes_native.py
```

安装可选包并提供固定 Hermes SDK 后，再执行对应原生测试。未安装 SDK 而跳过的
测试不算宿主验证通过。合成账号和协议自动化不能替代生产宿主、真实模型输出、
Android 真机的签名连接与敏感操作确认。本次代码交付不自动重启用户的现有服务、
不读取实际用户历史，也不自动完成真机验收。
