# OAI Core 0.2 独立部署与 Hermes 安全切换

本次依赖方向为 `Hermes Session Adapter → OAI Core`。Android 设备协议、认证、配对、
授权版本、请求排队与 Claim/Result/ACK 由 `oai_core.gateway` 提供唯一实现。
Hermes 是可选的原生会话宿主。DSH、Codex 与纯设备 CLI/API/MCP 无需安装 Hermes。
OpenClaw 沿用现有部署，本阶段不更新。

## 独立安装 Core

使用 Python 3.11 或更高版本。从仓库根目录执行：

```sh
python -m venv .venv
.venv/bin/python -m pip install ./oai-core
.venv/bin/python -m pip check
```

也可先构建再将 wheel 安装到不含仓库源码的机器：

```sh
python -m pip install build
python -m build oai-core --outdir ./dist
python -m venv .venv-clean
.venv-clean/bin/python -m pip install ./dist/open_android_intelligence_core-0.2.0-py3-none-any.whl
.venv-clean/bin/python -m pip check
```

wheel 与源码分发包中的协议资产均由构建过程从仓根 `gateway-contract` 生成。
不依赖网络下载旧 Gateway 包，也不维护另一份可独立修改的协议源码。
`jsonschema`、`cryptography`、`aiohttp` 与 `mcp` 是 Core 直接声明的运行依赖。
`open-android-intelligence-hermes-gateway` 和 Hermes SDK 均不在 Core 的依赖图中。

在部署目录创建 `core.json`：

```json
{
  "storageRoot": "runtime/accounts",
  "masterKeyFile": "runtime/secrets/core-master-key",
  "listen": {"host": "127.0.0.1", "port": 8045},
  "agents": []
}
```

路径相对于配置文件。`contractRoot` 可省略，使用包内契约；显式配置时仍严格验证
真实 Schema Hash。主密钥必须在账号数据目录之外，首次部署由 Core 安全创建。

```sh
.venv/bin/oai account create --config core.json --username alice
.venv/bin/oai serve --config core.json
```

密码交互输入。`agents: []` 表示尚未连接会话宿主；设备服务仍可协商、登录、配对、
发布能力和执行已授权请求。Android 使用原 Gateway Protocol v2，外部设备 Schema
和签名字节规则不随包命名空间调整而改变。

默认仅绑定本机。手机远程接入时设置监听地址并配置 TLS 证书及私钥；反向代理终止
TLS 时设置与客户端实际证书一致的 `tlsSpkiSha256`。配置为远程监听且无 TLS 时，
Core 会拒绝启动；受控开发环境须显式设置 `allowInsecureHttp: true`。

## 安装与配置向导

```sh
./scripts/install.sh
./scripts/setup.sh
```

`install.sh` 安装 Core 和其直接依赖，不会因为探测到 Hermes 就安装它。
两个向导只配置选择的会话入口，不停用旧插件、不重启服务、不修改原生 Hermes
配置或账号目录。Hermes 接入默认为关闭，选择接入时要求输入对应的既有 Hermes 账号。

配置文件保存为 `0600`；更新前备份到 `runtime/configuration-backups`。
已有 `storageRoot`、`masterKeyFile`、TLS 和其他配置字段、账号密码、主密钥、
配对数据及原有 Agent 实例均予以保留。已存在账号不会因重新运行向导重设密码。
移除旧 Agent 须显式编辑 `agents` 数组。

会话桥凭据写入 `runtime/secrets/adapter-tokens.env`（`0600`，父目录 `0700`）。
凭据由 Python 安全随机源生成；重复配置优先保留已有值，不自动旋转。
旧安装器写入启动脚本的固定 Token 格式会被安全提取到私密文件，迁移过程不执行
旧脚本。无法安全恢复既有值时，要求管理员在环境中提供原 Token，禁止静默替换。
`start-core.sh`（`0700`）只引用私密文件，不包含任何 Token 或账号密码的字面值，
并在读取前拒绝不属于当前用户、指向符号链接或对其他用户可读写的凭据文件。
不要把生成的运行目录、配置或凭据提交到仓库。

当需要将同一个桥 Token 提供给另一个服务身份时，由管理员通过权限受限的配置
管理方式分别提供给 Core 与宿主。不要把整个 Core 环境传给共享 Agent，不要把
令牌放入命令行参数、公开启动脚本、Shell 历史或文档。
桥 Token 只能认证会话接口，不能登录 Core 或授予设备操作权限。

## 安装可选 Hermes Session Adapter

先在 Core 的独立部署中完成设备服务验证，再准备 Hermes 切换。
在 Hermes 自己的 Python 环境中安装配套版本；下面命令从 OAI 仓库根目录执行：

```sh
python -m pip install ./oai-core ./integrations/hermes-session-adapter
```

适配器包约束 Core 为 `>=0.2,<0.3`，只调用 Core 的公共存储库与会话接口。
它不构造 `GatewayCore`、设备请求队列或设备认证服务，不注册 Android 设备工具。
Agent 设备工具应显式调用 Core CLI/MCP/API，并使用当前账号授予的受限设备凭据。

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

映射由管理员显式配置，必须是一对一的 `Core Account → Hermes Account`。
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
