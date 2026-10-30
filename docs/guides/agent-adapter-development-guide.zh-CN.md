# OAI Core 第三方 Agent 适配器开发指南

本指南面向希望将自己的 AI Agent（或已有商业/开源 Agent 系统）接入 Open Android Intelligence (OAI) 体系的开发者。

> **2026-10-28 设备授权规则**：Android 是设备能力授权的唯一权威；已安装且启用的普通前台能力默认开启，受本地显式开关、系统权限、数据范围和必要敏感确认约束。后台同步保留为 Android 本地独立许可。Core 与 Agent Gateway 均不追加账号级设备能力授权。规则已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`；当前测试和真机验收边界见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

通过实现标准的 **OAI Core Session Adapter**，您的 Agent 可以直接被 OAI Core 统一纳管，并通过 Android 手机客户端进行双向原生会话与设备能力互通，**不需要修改 OAI Core 核心代码，也不需要在 Android 端编写专属适配分支**。

---

## 1. 架构总览与职责边界

OAI 遵循「设备控制中心与原生会话分离」的设计哲学：

```
       +-------------------------------------------------------------+
       |                     Android App (OAI Client)                |
       +-------------------------------------------------------------+
                                      |
                     HTTPS + SSE (统一会话与设备协议)
                                      v
       +-------------------------------------------------------------+
       |                          OAI Core                           |
       |  - 连接身份认证与设备绑定 (Token / 请求签名)                     |
       |  - 统一设备管理与调用面 (CLI / REST API / MCP Stdio)          |
       |  - 原生会话路由与幂等台账 (native-operations.sqlite)          |
       |  - AgentRegistry (显式配置驱动的发现与能力仲裁)                |
       +-------------------------------------------------------------+
              |                        |                      |
              v (Remote/Local)         v                      v
     +-----------------+      +-----------------+    +-----------------+
     | 您的第三方 Agent |      |  DSH 部署实例    |    |  Hermes 实例    |
     | (SessionAdapter)|      |                 |    |                 |
     +-----------------+      +-----------------+    +-----------------+
```

### 各组件职责划分

| 组件 | 核心职责 | 绝对禁止事项 |
| --- | --- | --- |
| **Android 客户端** | 设备能力授权的唯一权威；插件安装且启用后能力默认开启，执行时检查本地显式开关、系统权限、数据范围与必要敏感确认。 | 不接受 Agent 或 Core 参数扩大本地权限。 |
| **OAI Core** | 连接认证、设备绑定、Token 签发、能力发现、协议验证、请求路由与幂等校验；裁剪宿主未实现的会话操作。 | 不追加账号级设备能力授权；不维护 Agent 私有长对话数据库；不硬编码特定 Agent 品牌类型。 |
| **Agent 适配器 (Adapter)** | 将 Core 的标准化会话请求转发给 Agent 真实宿主进程或远程服务，并返回真实流式事件。 | 不提供额外设备能力授权设置；不自行实现设备连接或调度，不绕过 Core 直接向手机发送设备请求。 |
| **Agent 宿主** | 拥有对话会话的完整事实、历史存储、上下文执行和模型调用。 | 不应当感知 OAI 底层复杂的设备签名与加密细节。 |

---

## 2. 插件接入形态与发现机制

根据 Agent 宿主的运行环境，适配器通常有两种形态：

1. **远程 HTTP 会话桥（推荐）**：Agent 作为一个独立服务（本地或内网），适配器继承 `RemoteSessionAdapter` 通过 HTTP/SSE 与其通信。
2. **本地子进程模式**：适配器直接管理 Agent 宿主子进程的标准输入输出（如 Codex 模式）。

### 显式配置边界（无环境遍历）

OAI Core 不会随意扫描当前系统进程或文件系统。所有可被 Core 发现的 Agent 必须由管理员在配置文件的 `agents` 数组中显式配置：

```json
{
  "storageRoot": "../runtime/accounts",
  "masterKeyFile": "../runtime/secrets/core-master-key",
  "agents": [
    {
      "type": "remote",
      "id": "my-assistant",
      "name": "我的智能助理",
      "url": "http://127.0.0.1:9000",
      "tokenEnv": "MY_AGENT_BRIDGE_TOKEN",
      "sessionScope": "deployment"
    }
  ]
}
```

---

## 3. 统一会话接口协议规范

### 3.1 SessionAdapter 核心接口

每个适配器在 Python 中实现 `SessionAdapter` 协议（或基于 `RemoteSessionAdapter` 派生）：

```python
class SessionAdapter(Protocol):
    def descriptor(self) -> dict[str, Any]:
        """返回 Agent 元数据与能力声明字典"""
        ...

    async def status(self) -> bool:
        """探测 Agent 真实在线状态 (Core 设有 5 秒超时限制)"""
        ...

    async def list_sessions(self, principal: Principal) -> list[dict[str, Any]]:
        """获取会话列表: [{"id": str, "title": str?, "updatedAt": str?, "archived": bool}]"""
        ...

    async def history(self, principal: Principal, session_id: str) -> list[dict[str, Any]]:
        """获取消息历史: [{"id": str, "role": "user"|"assistant", "text": str, "createdAt": str?}]"""
        ...

    async def create(self, principal: Principal, title: str | None) -> dict[str, Any]:
        """创建新会话: 返回 {"id": str, "title": str?, ...}"""
        ...

    def send(self, principal: Principal, session_id: str, text: str) -> AsyncIterator[dict[str, Any]]:
        """发送消息并产生异步流: 产出 {"type": "delta", "text": str} 或 {"type": "done"}"""
        ...

    async def manage(self, principal: Principal, session_id: str, action: str, value: Any = None) -> dict[str, Any]:
        """会话管理: action 可为 rename, archive, delete, cancel"""
        ...

    def commands(self, language_code: str = "en") -> list[dict[str, Any]]:
        """获取 Agent 支持的斜杠快捷命令目录，返回符合 Gateway Protocol 的命令条目列表"""
        ...
```

### 3.2 能力声明与裁剪机制

以下声明描述宿主实际支持的**会话操作**，不是 Android 设备能力授权开关。`deviceTools` 也只是适配器能否安全接入设备工具的能力声明；它不能授权设备操作、替代调用凭据或扩大手机权限。Core 规定了标准会话能力（布尔值）：

- `list`：支持查询会话列表
- `history`：支持查询历史记录
- `create`：支持创建新会话
- `send`：支持向会话发送消息
- `stream`：支持流式输出响应
- `rename`：支持修改会话标题
- `archive`：支持归档/取消归档会话
- `delete`：支持永久删除会话
- `cancel`：支持中断当前正在生成的回答
- `commands`：支持提供 Agent 自身所支持的斜杠快捷命令目录

> **注意**：如果适配器未实现或禁用了某项能力（声明为 `false`），Core 统一在服务端拦截对应路由请求并返回 `405 UNSUPPORTED_CAPABILITY`，手机端 UI 也会自动置灰对应操作按钮。不要为了应付协议返回伪造的空实现。

### 3.3 HTTP 会话桥标准 API 规范

如果您构建一个独立的远程 HTTP 会话桥服务，该服务建议监听于回环地址（如 `127.0.0.1`），鉴权请求头为 `Authorization: Bearer <TOKEN>`（Token 长度不少于 32 字符）。路由契约如下：

| 方法 | 路径 | 说明 | 请求体规范 |
| --- | --- | --- | --- |
| `GET` | `/status` | 报告在线状态与动态能力 | 无 |
| `GET` | `/commands` | 获取 Agent 支持的斜杠指令列表（支持 `?languageCode=zh-CN`） | 无 |
| `GET` | `/sessions` | 会话列表 | 无 |
| `POST` | `/sessions` | 创建会话 | `{"title": "可选标题"}` |
| `GET` | `/sessions/{id}/history` | 获取历史 | 无 |
| `POST` | `/sessions/{id}/messages` | 发送消息（返回 NDJSON / SSE 流） | `{"text": "用户消息正文"}` |
| `POST` | `/sessions/{id}/rename` | 重命名 | `{"value": "新标题"}` |
| `POST` | `/sessions/{id}/archive` | 归档 / 解除归档 | `{"value": true/false}` |
| `POST` | `/sessions/{id}/delete` | 删除会话 | 无 |
| `POST` | `/sessions/{id}/cancel` | 取消生成 | 无 |

#### 流式事件格式
流式响应采用 `application/x-ndjson`，每一行是一个独立 JSON：
- 增量字符：`{"type": "delta", "text": "部分回复"}`
- 完成：`{"type": "done"}`
- 异常：`{"type": "error", "code": "AGENT_ERROR_CODE"}`

---

## 4. 账号隔离与安全规范

### 4.1 会话范围策略：`sessionScope`

适配器必须显式声明其会话范围：
- **`deployment`（部署共享模式）**：适用于团队共享的大脑或单机专用 Agent（如 DSH、Codex）。所有通过 OAI Core 认证的用户看到统一的真实会话流。
- **`account`（多账号隔离模式）**：适用于具有用户概念的 Agent。适配器必须提供 Core 账号到宿主账号的显式映射，不同用户只能访问各自的私有会话。

### 4.2 严禁泄漏与混淆设备凭据

1. **共享会话绝不共享设备凭据**：在 `deployment` 共享模式下，适配器和 Agent 进程严禁自动继承任何账号的 OAI 客户端配置文件（`~/.config/oai/client.json`）或设备主密钥。
2. **设备能力调用标准途径**：任何 Agent 或第三方 Gateway 插件需要读取通知、短信、屏幕或控制手机时，都使用 OAI CLI、MCP stdio 或 Core 的 `/open-android-intelligence/core/v1` REST 接口。调用凭据必须由 Core 认证并绑定目标账号、已配对设备与有效配对代次；能力 ID、版本和参数按 Core 返回的实际声明构造，不让模型提供或覆盖账号、设备、风险级别与授权版本。
3. **Android 作最终决定**：插件安装且启用后能力默认开启；本地显式关闭、系统权限不足或必要确认未完成时，Android 仍拒绝执行。Core 记录或转发手机的能力状态，不把账号策略、会话桥账号清单或旧 capability grant 当作第二套设备授权。
4. **区分两条链路**：原生会话走 `Android → Core → Session Adapter → Agent`；设备工具走 `Agent → Core CLI/MCP/API → Android → 设备插件`，会话桥不必经过每次设备调用。第三方接入复用同一规则，不增加专属授权开关。桥 Token、Hermes 宿主账号 allowlist 和一对一账号映射仍保护会话访问，不能混同为应移除的设备 capability allowlist。
5. **兼容性据实声明**：`dev` 尚待本分支 PR 合入；外部旧 Hermes/OpenClaw/DSH 独立 Gateway 不会因本次实现自动完成适配。混合版本的能力状态及撤销语义按[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)和目标部署版本单独验收。

### 4.3 紧凑 UTF-8 1 MiB 请求体预算限制

Core 与所有会话桥强制执行 `SESSION_REQUEST_MAX_BYTES = 1048576`（1 MiB）。超出限制的请求在进入持久化台账前即被截断并返回 413，避免大文件攻击或状态不确定。

---

## 5. 开发步骤与独立插件打包发布

### 步骤 1：基于 `RemoteSessionAdapter` 或实现 `SessionAdapter`

可直接在配置中指定 `type: "remote"`（开箱即用，无需编写 Python 代码，只需实现 HTTP 服务），或者编写 Python 包导出工厂函数。

### 步骤 2：使用 entry_points 导出插件（可选 Python 插件包）

在您的插件包 `pyproject.toml` 中声明 entry point：

```toml
[project.entry-points."open_android_intelligence.agent_adapters"]
my_agent = "my_package.adapter:create_adapter"
```

工厂函数签名：
```python
def create_adapter(entry: dict[str, Any], storage_root: Path, core: Any) -> SessionAdapter:
    return MyCustomAdapter(...)
```

安装到 OAI Core 所在的虚拟环境后，Core 在启动时会自动注册 `my_agent` 插件类型！

---

## 6. 常见错误码与排障速查

| 错误码 | HTTP 状态码 | 产生原因与排查建议 |
| --- | --- | --- |
| `AGENT_NOT_FOUND` | 404 | 访问了配置文件中不存在的 `agent_id`。检查客户端请求 URL。 |
| `UNSUPPORTED_CAPABILITY` | 405 | 适配器 descriptor 未开启此项能力。 |
| `AGENT_UNAVAILABLE` / `AGENT_OFFLINE` | 503 | 适配器连接失败或宿主进程崩溃，或握手超时（超过 5 秒）。 |
| `AGENT_STREAM_INTERRUPTED` | 502 / SSE | 流式通信中途断开，或者流未以 `done` 正常终结。 |
| `MESSAGE_TOO_LARGE` | 413 | 发送的内容编码后超过 1 MiB。 |
| `IDEMPOTENCY_KEY_REQUIRED` | 400 | 未传递 `Idempotency-Key` 请求头（针对非 HMAC 签名的直接请求）。 |

旧 `dev` 基线的能力目录若返回 `GATEWAY_DISABLED`，表示旧实现检查了账号设备能力策略；应记录 Core 版本、设备与请求关联信息，并按[历史修复计划](../reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)定位。该判断已在 `feature/android-device-authority-20261028` 分支修复，分支验证和兼容边界见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。不要要求用户回到 Agent Gateway 的旧能力 `setup` 授权。


## 通信任务与观察连接

消息可靠受理后，执行由宿主持有。HTTP/SSE/NDJSON 的关闭、写入超时或慢观察者溢出只结束观察，不能代替绑定真实 generation 的显式取消。先持久受理再登记分派的路径必须由同一宿主任务持有，避免取消等待者后只提交了输入却没有安排执行。

同会话按已提交顺序调度未领取的 queued；running/unknown 不能因断线而自动重投。同步数据库、历史锁、附件解密和文件同步写入不得阻塞事件循环。观察输出使用有界缓冲，Node 写入返回 false 时遵守 drain；超过观察预算时释放订阅并保留宿主事实。

`traceparent` 和 Core 转发的请求 ID 仅用于脱敏阶段关联，不能替代账号、设备、配对或会话认证。generationRecovery/eventSync 继续按实际能力声明，不能因 Core 提供 operationRecovery 就假定宿主也具备恢复能力。具体端点和生命周期见[通信说明](../architecture/2026-10-30-communication-lifecycle.zh-CN.md)。
