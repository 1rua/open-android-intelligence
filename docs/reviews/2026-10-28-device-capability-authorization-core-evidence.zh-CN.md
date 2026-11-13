# 设备能力授权收敛：OAI Core 代码证据

> **历史阶段说明（2026-10-28 后续更新）**：本文固定记录 `dev@e5c7f42a59262f404888858045aa975830dafb61` 的 Core 源码证据，不描述后续 feature 分支实现。下文“当前源码”“未实现”均指 e5 基线。分支实现、当前测试与待完成验收见[实施记录](2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

日期：2026-10-28。基线：`dev`，`e5c7f42a59262f404888858045aa975830dafb61`。

本记录只说明 e5 基线源码中的判断条件和后续修复约束，不表示该基线当时已实现修复。e5 阶段仅阅读源码、测试及文档，没有读取运行中的账号数据库、操作设备、调用能力或执行数据迁移。部署是否与此基线一致、特定账号是否存在旧关闭记录，仍需以脱敏运行证据确认；feature 分支实现及验证见[实施记录](2026-10-28-device-capability-authorization-implementation.zh-CN.md)。

## 1. 已确认结论

Core 中仍存在独立于 Android 的账号级设备能力开关。这个开关并非缺失时默认关闭：`DeviceCapabilityPolicy.snapshot()` 在没有持久化记录时返回空映射，`allows()` 用 `enabled.get(capability_id) is not False` 判断，只有显式 `false` 才拒绝。相同的判断用于能力目录，因此不能把用户报告直接归因为“迁移后未执行 setup，所有新账号默认拒绝”。

持久化位置是各账号 `account_metadata` 表的 `device-capability-policy:v1`，结构为 `{revision, enabled}`。关闭决定属于账号，未包含设备 ID、插件作者或插件版本。目录代码会把 Android 上报状态覆盖为 `GATEWAY_DISABLED`；调用代码还会过滤绑定，并在请求入队、Claim、结果提交、重放与读取结果时再次检查。只删除 UI 文案或只改默认值都不能移除这层权威。

证据：[capability_policy.py](../../oai-core/src/oai_core/gateway/capability_policy.py)，`DeviceCapabilityPolicy.snapshot/allows/set`，第 14–55 行；`capability_catalog`，第 58–95 行；[capability_bindings.py](../../oai-core/src/oai_core/gateway/capability_bindings.py)，`CapabilityBindings.available/validate`，第 120–132 行；[core.py](../../oai-core/src/oai_core/gateway/core.py)，`DeviceRequestStore.enqueue`，第 2761–2772 行，`_assert_capability_enabled`，第 3040–3046 行。

## 2. 证据索引

| 编号 | 当前代码与关键函数 | 已确认行为 | 修复含义 |
| --- | --- | --- | --- |
| C01 | [capability_policy.py](../../oai-core/src/oai_core/gateway/capability_policy.py)，`snapshot/allows/set`，14–55 | 无记录默认允许；显式 `false` 拒绝；不合法 JSON 或结构报 `CAPABILITY_POLICY_INVALID` | 移除新请求对旧策略的读取与写入入口；迁移不得把配置损坏解释为 Android 已授权 |
| C02 | 同文件，`capability_catalog`，58–95 | 从当前设备 publication 与 inventory 构建目录，再用账号开关覆盖 `status`；输出 `gatewayEnabled`、`androidStatus`；目录还可能有诊断用 hint | 目录以 Android 当前声明及可用性为准，hint、inventory 和旧 `true` 均不得生成可调用绑定 |
| C03 | [capability_bindings.py](../../oai-core/src/oai_core/gateway/capability_bindings.py)，`register/validate_inventory/validate_publication`，14–69 | 按设备保存 generation/revision/bindings/inventory；校验插件身份、作者键摘要、风险、Schema 摘要；`READY` 集合必须等于绑定集合 | 保留来源、结构和身份校验；不能简单让所有 capability ID 通过 |
| C04 | 同文件，`publication/list/available/validate`，112–132 | 过期 generation/revision 返回空绑定；`available` 额外按账号 policy 过滤；`validate` 再校验 provider、风险和参数 Schema | 删除账号 policy 过滤，保留发布新鲜度与请求校验 |
| C05 | [device_service.py](../../oai-core/src/oai_core/device_service.py)，`account/capabilities/submit`，22–76 | 每次确认 principal 当前有效；发现使用目录；提交从 `available` 选绑定；绑定身份来自 Core 当前设备发布，调用参数不能切换账号和设备 | 第三方 Agent 继续走统一、带认证的服务；不存在“任意 Agent 能直接调用”的目标 |
| C06 | [core.py](../../oai-core/src/oai_core/gateway/core.py)，`DeviceRequestStore.enqueue/claim/submit_result`，2718–2877 | 入队两处检查旧 policy；Claim 与结果提交检查绑定后再次检查 policy；结果封存并发布终态 | 取消额外 policy 的同时保留原子 Claim、绑定、超时、幂等与结果状态机 |
| C07 | 同文件，`validate_claim_replay/read_result/validate_result_replay`，2879–2937；`_assert_capability_enabled`，3040–3046 | 重放、读结果也被当前账号 policy 和永久请求撤销记录阻断 | 清理不能只覆盖新提交；历史撤销记录必须保留终态隔离 |
| C08 | [gateway/admin.py](../../oai-core/src/oai_core/gateway/admin.py)，`AdminService.capabilities`，290–336 | `capabilities.set` 写 policy；关闭时对匹配能力的历史请求写 `device-request-policy-revoked:{requestId}`，清参数/结果、取消或请求取消并发通知/审计 | 需要迁移永久撤销语义，避免删除 policy 后旧请求或结果复活 |
| C09 | [cli.py](../../oai-core/src/oai_core/cli.py)，`_parser`，27–35，`_local_admin`，84–110 | `oai policy set` 直接调用 `DeviceCapabilityPolicy.set`，未经过上述 AdminService 的撤销及清理流程 | 两个旧入口语义不同，迁移不能假定所有旧关闭决定都带撤销标记 |
| C10 | [core.py](../../oai-core/src/oai_core/gateway/core.py)，`GatewayCore.handle` 的配对相关分支，5493–5521 | `GET /v2/pairings/current` 返回 policy 与目录；能力发布实际为 `POST /v2/pairings/current/capabilities`；验证 `expectedGrantRevision`、本地 revision、bindings/inventory 后计算摘要，变更时 bump revision | 保留 Android 认证发布与摘要去重；后续协议字段退场需兼容评审，不是把 POST 改成 PUT |
| C11 | [auth.py](../../oai-core/src/oai_core/auth.py)，`_issue_login/authenticate/assert_current`，128–216 | Core token 与账号、设备、generation、revision 绑定；revision 变化使旧 token `GRANT_STALE`；签名客户端还需活跃会话与协商绑定 | token 身份及失效不属于旧 capability 开关，必须保留并清晰提示重新认证 |
| C12 | [gateway/verifier.py](../../oai-core/src/oai_core/gateway/verifier.py)，`GatewayRequestVerifier._verify`，271–345；[auth.py](../../oai-core/src/oai_core/auth.py)，`ReplayProtectedVerifier/consume_nonce`，35–88 | 校验协议、单例头、账号/设备/会话、时间、nonce、规范目标、真实请求体 Ed25519 签名，并消费 nonce 防重放 | 取消账号设备能力授权不放松认证或防重放 |
| C13 | [core.py](../../oai-core/src/oai_core/gateway/core.py)，`GatewayAccount.bump_grant_revision`，3732–3774，`revoke_pairing`，3674–3728 | revision 变化记录事件/审计；解除配对撤销密钥、凭据、会话、在途请求等 | 保留单调版本、配对撤销和事件；去除额外能力授权不等于删除所有含 grant 的字段 |

`C08` 的写入口仍存在于通用 Core 包内，不证明新的 Hermes 会话适配器实际注册或暴露了旧 setup 面板。该点必须结合适配器入口单独核对。

## 3. 当前 Core 调用链

### 3.1 Agent 设备工具入口

1. 官方 MCP `oai_capabilities/oai_invoke/oai_request_status/oai_cancel_request/oai_ack_result` 调用同一个 `CoreClient`；CLI 也使用同一客户端。
2. `CoreClient` 通过 `/open-android-intelligence/core/v1` 调用服务；token 登录先验证账号密码和已配对设备，随后 token 绑定账号/设备/generation/revision。
3. `server.create_app` 的 capability/request 路由先 `Authentication.authenticate()`；`DeviceService.account()` 再 `assert_current()`，进入当前账号独立存储。
4. `DeviceService.submit()` 验证封闭参数集合和幂等键，从对应设备、配对代次、授权 revision 的 `CapabilityBindings.available()` 选已发布能力。旧账号开关在此已过滤绑定，因此入口有时只返回一般 `CAPABILITY_DENIED`，未必附带 `GATEWAY_DISABLED` reason。
5. `DeviceRequestStore.enqueue()` 再校验绑定、provider、风险和参数 Schema，并在事务内重新检查旧开关，然后写队列、发 `device.requested` 与审计事件。

证据：[mcp.py](../../oai-core/src/oai_core/mcp.py)，`create_mcp`，13–49；[client.py](../../oai-core/src/oai_core/client.py)，`capabilities/invoke/status/cancel/acknowledge`，163–188；[server.py](../../oai-core/src/oai_core/server.py)，`DEVICE_API`，28，`create_app` 路由，145–196；以及 C04–C06、C11。

### 3.2 Android 状态同步及执行往返

1. Android 使用已配对设备的签名会话读取 `GET /open-android-intelligence/v2/pairings/current`，获得当前 generation/revision、摘要与能力目录；当前目录仍含旧账号状态。
2. Android 通过 `POST /open-android-intelligence/v2/pairings/current/capabilities` 上报 bindings、本地 revision 和可选 inventory。Core 验证集合一致性和 Schema，计算 publication 摘要。同摘要且 publication 当前有效时不重复推进 revision；否则更新 revision，保存声明和摘要。
3. Android 接收请求后通过 `/v2/device-requests/{requestId}/claim` 原子认领；Core 同时验证设备/generation/revision、账号 policy、期限与已有认领回执。
4. Android 本地实际授权和插件执行之后，通过 `/result` 提交带 `claimId` 和 `grantRevision` 的结果；Core 再验证绑定、policy、有效期、回执和状态迁移。
5. Agent 在 Core API 读取请求/结果后 ACK；Core 清理临时结果。旧 policy 或永久撤销标记也会阻断结果读取，而非只影响新调用。

证据：C06–C10；[core.py](../../oai-core/src/oai_core/gateway/core.py)，`GatewayCore.handle` Claim/Result 分支，5668–5689，重放校验，5701–5712；[device_service.py](../../oai-core/src/oai_core/device_service.py)，`_record/get/cancel/acknowledge`，78–109。

## 4. 不能混淆的三种状态

| 状态 | 当前来源 | 后续要求 |
| --- | --- | --- |
| 旧账号能力开关 | `device-capability-policy:v1`，以及 UI 中 `gatewayEnabled/GATEWAY_DISABLED` | 不再参与新设备能力调用授权，停止写入，按正式迁移计划退场 |
| Android 能力声明及本地授权版本 | `device-capabilities:{deviceId}`、`device-grant-digest:{deviceId}`、`device_keys.grant_revision` | 保留为 Android 授权结果的镜像、协议兼容与撤销版本栅栏；Core 不能自行创造允许绑定 |
| 连接及设备身份 | 账号密码摘要、设备键、access/refresh 会话、Core token、generation、nonce | 保留、继续校验；跨账号、旧代次、撤销会话、重放均拒绝 |

源码内的 “grant” 不全是要删除的旧开关。例如 `grantRevision` 防止授权变化后旧请求继续 Claim，`grantDigest` 标识 Android 发布内容，二者均不能因名字含 grant 而直接去除。历史请求永久撤销标记也不能当作无用缓存清掉。

## 5. 待验证假设及所需证据

| 假设 | 当前能确认到哪里 | 后续最小验证方式 |
| --- | --- | --- |
| 用户账号保存了早期显式 `false` | 源码确认这会生成目录中的 `GATEWAY_DISABLED` 并阻断调用；未查看该账号存储 | 只读取目标部署版本和目标账号此单一 metadata key，输出能力 ID/布尔值/策略 revision；不导出密码、token 或数据正文 |
| Android 保留了断线前的旧账号目录 | 当前 Core 在配对状态响应中传播旧目录；Android 缓存与失效策略需其代码证据 | 关联一次连接与状态刷新，记录 publication 摘要、revision、catalog 状态；不靠清空 App 数据试错 |
| 部署仍运行旧 Gateway 或不同 Core 版本 | 此基线包含独立 Core 服务，不等于运行中的进程使用它 | 核对运行包版本、提交、配置指向和单进程所有权；不停止服务或替换部署 |
| Android 的本地授权/系统权限其实也缺失 | 账号状态覆盖会隐藏底层 Android 状态，去除 policy 不保证 READY | 对同一 plugin/capability/version 比较 `androidStatus`、inventory 与本地权限实际状态 |
| policy 配置损坏导致整个目录失败 | `snapshot()` 明确返回拒绝；不是 `GATEWAY_DISABLED` 文案的同一判断 | 读取限定 metadata key 并验证结构；不能将损坏配置改为空以假定允许 |

## 6. 后续实现需要覆盖的 Core 修改点

以下是未来任务清单，本次未执行。

| 文件/函数 | 具体修改方向 | 保留条件 |
| --- | --- | --- |
| `gateway/capability_policy.py:DeviceCapabilityPolicy` | 停止作为新请求授权来源；旧结构仅供受控迁移读取；移除或弃用写入入口 | 读取旧状态用于迁移审计，不把旧 `true` 当作本地授权 |
| `gateway/capability_policy.py:capability_catalog` | 以当前 Android publication、inventory 和连接状态产生可用性；不再用账号开关覆盖；兼容字段的过渡规则单独评审 | 缺失 publication、过期 revision、没有真实 binding 不得显示可执行 |
| `gateway/capability_bindings.py:available/validate` | 移除 `DeviceCapabilityPolicy` 过滤；继续从 `list()` 获取匹配版本绑定 | 身份、Schema、provider、风险、当前代次与 revision 校验完整保留 |
| `gateway/core.py:DeviceRequestStore.enqueue/_assert_capability_enabled` | 移除当前账号开关检查，将历史永久撤销与当前绑定检查拆清楚 | 旧 request tombstone、已取消/已结束/超期状态不可复活 |
| 同文件 `claim/submit_result/read_result/validate_*_replay` 与 v2 GET request | 审核每一个 `_assert_capability_enabled` 调用，调整为保留必要绑定和历史撤销语义的校验 | Claim/Result/replay/读结果行为一致，不因入口不同绕过隔离 |
| 同文件 `GET /v2/pairings/current` | 不再提供有权威作用的账号开关；兼容期字段只表达退场信息，不能回写或驱动 Android 本地授权 | Android 真正上报的 `grantRevision/grantDigest` 与发布握手仍有效 |
| `gateway/admin.py:AdminService.capabilities/execute`、旧解析与注册函数 | 关闭旧 `capabilities.set` 写入口，明确弃用/迁移提示；只保留需要的连接身份管理与诊断 | 账号、配对、会话管理不能一并删除 |
| `cli.py:_parser/_local_admin` | 退场 `oai policy set`，避免第三方宿主或管理员继续创造旧拒绝条件 | 不做“自动代用户开启”；不把设备工具 token 和宿主桥 token 互换 |
| `device_service.py:capabilities/submit/_record` | 校验各路由都只依赖当前 principal 与 Android 当前绑定，错误区分缺授权、未配对、版本过期、不可用 | 各入口继续共享唯一请求状态机和幂等账本 |
| `auth.py`、`gateway/verifier.py` | 原则上保留认证语义，仅补适配新能力状态所必需的文档及回归 | token 的账号、设备、generation/revision 绑定不可削弱 |

## 7. 历史状态与混合版本迁移约束

1. 实施前对账号存储及原主密钥做可恢复备份，明确唯一写入者；不重建账号、配对、历史或密钥。本次未操作任何运行数据。
2. 停止两个旧写入口，避免旧宿主或旧 CLI 在升级期间再写 capability policy。仅把缺省判断改成允许仍会被旧 `false` 阻断。
3. 保存旧 `{revision, enabled}` 的来源和迁移结果，按设备收集重新认证的 Android 完整声明。旧 `true/false` 均不应直接翻译成一份 Core 允许表；尤其不能把账号 `false` 自动覆盖为 Android 用户显式关闭或开启。
4. 新调用能否执行只由当前 Android 绑定和本地最终检查决定。离线、未同步、缺失 publication 的设备保持待同步或不可用；不凭静态 capability hint、历史绑定或缓存放行。
5. 在启用新语义前先处理旧在途请求：已标记 `device-request-policy-revoked:*` 的请求永久不复活；由 CLI 直接关闭而没有 tombstone 的历史在途请求也应按经评审的取消/未知结果策略收敛，不能在取消 policy 后自动继续执行。已 Claim 的副作用不可伪造为“未发生”。
6. 首个迁移边界必须阻止旧 publication 和旧请求跨越；具体采用发布 revision 失效或显式迁移栅栏，由实施 Review 确定并写自动化测试。不得降低、归零或把旧 token 升权复用。revision 变更后旧 Core token 按现有规则过期，需要重新认证。
7. 兼容窗口内，旧客户端看到 `gatewayEnabled` 之类字段时不能要求用户去旧 setup；新 Core 对旧写命令应返回明确退场结果，不能假成功；不支持安全协商的组合保持明确不可用。字段删除、状态枚举与 Schema Hash 更新需成套协议评审，本文不改协议生成物。
8. 回滚必须恢复匹配版本的数据和原密钥，不能仅换旧二进制读取已经改写的存储。不能借回滚重新排队或重放已取消、超期、已撤销、未知结果的请求。

## 8. 后续验收依据与当前验证范围

已有测试源码表明当前测试仍把额外账号级开关当作预期行为：

- [test_screen_capabilities.py](../../oai-core/tests/test_screen_capabilities.py)，`test_screen_capability_disabled_explicitly_blocks_new_requests`，192–210：设置 policy 为 `false`，期待新屏幕请求被拒绝。
- [test_cli_mcp.py](../../oai-core/tests/test_cli_mcp.py)，`test_local_account_and_policy_commands_use_real_isolated_core_stores`，176–206：校验无 policy 的账号默认允许、另一个账号显式关闭后拒绝。

这些是静态测试证据，形成本证据记录时未运行这些测试，也未改写其断言；主任务后来执行的 Fast 门禁及环境失败见[修复计划第 10 节](2026-10-28-device-capability-authorization-repair-plan.zh-CN.md#10-本次文档交付的自动验证与完整清单)。后续实现应替换“Core policy 决定设备可用性”的断言，同时增加以下必要用例：

- 缺失/旧 `true`/旧 `false`/损坏旧 policy 均不会产生额外设备授权权威；仅当前 Android 真实发布的绑定可被发现和调用。
- 本地关闭、系统权限撤销、provider 缺失或未选择、插件禁用、卸载、重新安装及新版本发布，均按本地真实状态变化。
- 未登录、伪造设备、其他账号、旧 generation/revision、旧 token、伪造 Schema/provider/风险、重放 nonce、错误幂等键均拒绝。
- 能力状态变化发生在 discover→enqueue、enqueue→Claim、Claim→本地执行、执行→结果、结果→读取/ACK 各窗口时，阻止新副作用并保留准确终态，不泄漏旧结果。
- `capabilities.set` 与 `oai policy set` 产生的两类旧数据、永久撤销记录、已 Claim 请求、进程重启、断线重连、混合版本和回滚均有独立覆盖。
- 用同一个 `requestId` 验证 Agent 提交、Core 入队、Android Claim/执行、Core 结果与 ACK。单元测试不替代受控设备上的系统权限和敏感确认验收。

本记录的当前完成条件为：源码引用存在、基线固定、事实与待验证项分开、未修改实现。它不构成设备功能通过或生产迁移通过的证明。
