# 隔离 Core A 接收端运行验证

## 范围和结果

- 当前交接使用 A 与新 B 的两个独立 CLI 凭据；二者绑定同一测试账号、设备和配对代次。实际跨凭据接收请求双向均返回 HTTP 403，详见[独立凭据所有权验证](#独立凭据所有权验证)。后续手机配置使用新 B 的 `receiver-b.route2.json`，不使用下文旧 B。
- 时间：2026-11-01 约 10:02 至 10:07，时区 Asia/Shanghai。
- 目标：`https://127.0.0.1:19841`，使用 `runtime/core-a/tls/v2/ca-cert.pem` 验证服务证书，没有关闭 TLS 校验。
- 客户端源码位于本工作树，`HEAD` 为 `17895223c16fc84dfbe7c889943a608f7fbaa095`；工作树存在其他任务的未提交修改。Core A 为已有隔离服务，其运行快照不能仅凭当前工作树 `HEAD` 推定。
- 两个接收端的真实 CLI 注册、CLI 发现、独立 MCP 进程发现和未认证拒绝均已验证。验证只涉及 Core A，没有操作 ADB、模拟器界面、用户 0 或生产服务，没有停止 Core。
- 早期注册使用已有私密配置 `runtime/core-a/terra-cli-user10.json` 的两份副本。此前 [Terra 验证记录](2026-11-01-global-device-authorization-terra.zh-CN.md)记载，这份配置使用用户 10 真实登录时的设备 ID 创建。配置文件不含设备 ID；该轮未单独通过设备或 UI 重新核实该来源。注册成功不代表手机已选择白名单。
- 早期 A 和旧 B 的配置使用同一凭据，不构成凭据隔离测试；之后的新 B 才使用独立凭据并完成双向拒绝验证。没有创建或修改设备绑定，也没有伪造 Android 能力发布。

## 端点注册与发现

使用真实 `oai --client-config <私密配置> endpoint register --registration-key <私密登记键> --label <名称> --ttl-seconds 3600`，分别注册 `TerraReceiverA` 和 `TerraReceiverB`。命令退出状态均为 0。下表保留全部路由字段名称和代次，标识符只展示脱敏片段；完整路由仅保存在私密文件中。

| 接收端 | coreInstanceId | accountScope | endpointId | generation | 租期截止，UTC |
| --- | --- | --- | --- | --- | --- |
| TerraReceiverA | `core_...a4ea1e54` | `acct_...8d99a222` | `endpoint_...a70a13de` | 1 | 2026-11-01 03:02:32.489 |
| 旧 TerraReceiverB（同 A 凭据） | `core_...a4ea1e54` | `acct_...8d99a222` | `endpoint_...ac9df718` | 1 | 2026-11-01 03:03:07.082 |

两者对应北京时间 11:02:32.489 和 11:03:07.082。CLI `endpoint list` 与 MCP `oai_endpoints` 分别找到两条记录，且都与注册结果的完整四字段逐项相等。该比较不证明服务重启、凭据更换或租期结束后的路由仍不变；过期后应重新发现并核对代次和白名单。

## MCP 和认证拒绝

- 使用已安装的官方 Python MCP 客户端 `mcp 1.30.0`，通过标准输入输出启动真实 `oai mcp` 子进程。初始化返回服务名 `OAI`、协议版本 `2025-11-25`；列出 10 个工具，并实际调用 `oai_endpoints`。没有使用进程内模拟服务。
- 未携带认证信息时，`GET /core/v1/endpoints`、`GET /core/v1/devices`、`GET /core/v1/capabilities`、`GET /v2/push/endpoints` 和 `POST /core/v1/device-requests` 全部返回 HTTP 401，curl `ssl_verify_result` 均为 0（证书校验成功，不表示关闭TLS验证）。提交测试使用空 JSON，认证先拒绝，没有创建有效设备请求。
- 这一轮没有调用接收、确认或投递接口，没有读取投递正文；当时未验证手机签名请求、白名单选择、实际投递、凭据之间的访问隔离或实体手机行为。后续独立凭据验证见本文末节。
- [脱敏自动检查记录](evidence/2026-11-01-global-device-authorization-core-runtime.txt)包含路由比较、MCP 工具清单、认证状态和 TLS 结果。对应的私密原始 CLI/MCP 输出位于下述忽略目录。

## 先前同凭据私密交接

以下路径均相对于仓库根目录，文件内容不应放入公开报告：

| 接收端 | 客户端配置 | 注册结果，含完整 `route` 和 `endpointId` | 登记键 |
| --- | --- | --- | --- |
| A | `docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-a.client.json` | `docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-a.route.json` | `docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-a.registration-key` |
| 旧 B | `docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-b.client.json` | `docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-b.route.json` | `docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-b.registration-key` |

私密目录权限为 `0700`，配置、登记键和路由文件权限为 `0600`，目录受项目规则忽略。本会话没有可用的 `send_input` 工具或对应消息资源，因此没有通过该通道向主 Agent 投递；上表是供主 Agent 和手机测试 Agent 读取的交接位置。

## 独立凭据所有权验证

2026-11-01 约 10:12 至 10:14（Asia/Shanghai），保留 A 和旧 B，不注销旧路由。通过只读检查隔离 Core 的凭据与端点记录，确定 A 的测试账号、设备及配对代次；然后使用同一账号的测试密码和同一设备，通过真实 `oai login` 生成新 B 凭据。新凭据与 A 不同，但账号、设备及配对代次相同；没有创建 Agent 身份或修改设备绑定。

使用新 B 凭据，通过真实 `oai endpoint register` 以登记键 `terra-receiver-b-independent`、名称 `TerraReceiverB`、租期 3600 秒注册新端点。新端点的脱敏路由为 `coreInstanceId=core_...a4ea1e54`、`accountScope=acct_...8d99a222`、`endpointId=endpoint_...afe05d70`、`generation=1`；租期截止 2026-11-01 03:13:38.973 UTC，即北京时间 11:13:38.973。新 B 的 CLI 发现结果与 A、新 B 的注册路由分别完整一致；旧 B 仍保留。

实际 HTTPS 请求前，只读检查两个端点的待投递数量均为零，并确认端点所有者分别对应两个不同的凭据。使用测试 CA 校验证书，直接请求 Core API，不读取响应正文：

| 请求 | 实际 HTTP 状态 |
| --- | --- |
| 新 B 凭据读取 A 的 `/deliveries` | 403 |
| A 凭据读取新 B 的 `/deliveries` | 403 |

这证明当时双向跨凭据请求均被拒绝，不证明有待投递事件时的端到端交付，也没有实际模拟同名或不同名 Agent。端点名称不是本次鉴权输入；不能把上述拒绝当作手机白名单或实体手机验收。未重复未认证测试，上一轮结果见前文。[脱敏状态记录](evidence/2026-11-01-global-device-authorization-ownership.txt)。

后续手机选择应使用新 B 的完整路由，不使用旧 B：

- A 配置：`docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-a.client.json`
- 新 B 配置：`docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-b-independent.client.json`
- 新 B 完整路由及端点 ID：`docs/plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/receiver-b.route2.json`

## 固定样本双端观察

2026-11-01 约 10:56 至 10:58（Asia/Shanghai），只观察脚本通过各自的独立凭据访问 Core A 的 A 和新 B 端点。脚本记录最大运行时间 300 秒、间隔 2 秒，实际在 112.122 秒后以 `PASS` 结束；两端最后一次返回各一条记录。

- A 和新 B 均收到 `notification.posted`，各自完整路由与预期一致，正文均等于固定样本 `OAI-SAMPLE-NOTIFICATION-20261101 complete synthetic body`。
- 两端 `eventId` 相同（脱敏为 `push_7e267b91...fd8e0b79`），`deliveryId` 不同：A 为 `delivery_c822675c...ad5fa666`，B 为 `delivery_13ef41a1...338d885d`。
- 观察脚本的最终记录为 `ack_submitted=false`，没有发送 ACK。Core 接收接口会标记返回事件已读取，因此不应把“未 ACK”描述成“未读取”。手机队列当前状态和后续是否收到逐端 ACK 不在本次观察证明范围内。
- [可提交的脱敏观察日志摘录](2026-11-01-global-device-authorization-core-runtime-observer-evidence.txt)保留 `started`、两端 `sample_observed`、`both_observed` 和 `finished`；完整事件及投递 ID 保留在忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/notification-observe-20261101T025606Z-9f17aec3.receipt.json`，文件权限为 `0600`。

用户报告手机已通过真实系统界面启用通知监听并配置 A、新 B，且已触发操作系统合成样本；本执行者没有操作或独立检查手机界面。上述观察证明 Core 返回的固定样本匹配，不单凭接收日志证明手机侧排队状态或逐端确认完成。

## 未确认样本恢复观察

2026-11-01 约 11:28（Asia/Shanghai），A 与新 B 的一次实际接收各返回 0，因没有正文或事件 ID 无法确认当时手机队列中的第二事件。随后用户报告手机回到 OAI 前台、没有发布新样本；11:43 的 observer2 首轮接收在 A、新 B 各发现一条完整固定样本，`eventType`、正文和各自完整路由均匹配。两端为同一事件 `push_45e447a7...f6345750`，A、B 的投递 ID 不同，脚本在 0.083 秒后以 `PASS` 结束，观察时未发送 ACK。[脱敏原始日志摘录](2026-11-01-global-device-authorization-recovery-observer-evidence.txt)。

observer2 在启动时读取并核验第一事件的两端 ACK 回执；若该事件 ID 再次出现则直接 `FAIL`。实际两端各只返回一条，均为上述不同于第一事件的样本，日志没有记录第一事件重现。结合手机侧仍有一个未确认事件且没有新发布样本的报告，结果与之前未 ACK 的第二事件恢复投递一致；因 11:28 的空接收未提供第二事件 ID，这一对应关系是基于运行时上下文的推断，而不是对先前 ID 的直接比对。此处不单凭 Core 接收日志宣称手机队列已清空。

完整 observer2 日志与匹配回执保留在忽略目录 `../plans/2026-11-01-global-device-authorization/runtime/core-a/terra-receivers-runtime-20261101/notification-observe-20261101T034308Z-f5d2e4c2.receipt.json`，未包含凭据内容。

根据后续明确授权，仅对 observer2 确认的上述固定样本分别通过 A、新 B 的真实 CLI 提交 ACK，并各重复一次：

| 接收端 | 首次 ACK 数 | 重复请求数 | `acknowledgedAt`（UTC） | 重复回执 |
| --- | --- | --- | --- | --- |
| A | 1 | 1 | 2026-11-01T03:50:57.882Z | 与首次完整一致 |
| 新 B | 1 | 1 | 2026-11-01T03:50:58.278Z | 与首次完整一致 |

两端回执均为 `acknowledged`，事件 ID、对应投递 ID 和完整 route 与观察回执一致。没有确认其他事件，也没有再次读取正文。[脱敏 ACK 验证记录](2026-11-01-global-device-authorization-recovery-ack-evidence.txt)。手机队列 `0/0` 尚待主 Agent 通过 UI 检查，不能由 Core ACK 直接推定。
