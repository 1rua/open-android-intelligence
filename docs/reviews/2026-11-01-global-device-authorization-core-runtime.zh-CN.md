# 隔离 Core A 接收端与 Core B 临时授权运行验证

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

后续 A 与新 B 曾续期，最后一次已记录的截止时间分别为北京时间 12:29:38.188 和 12:30:01.645，续期时两条 route 的 generation 均保持为 1。截至用户报告的 2026-11-01 16 时左右，这两份已记录租约均已过期；本次文档整理没有实时访问 Core。后续推送测试须另行限定范围续租、核对完整 route 和代次，并由手机侧明确重新选择；不能把先前选择或历史投递当作新租约的验收结果。

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

## 第一事件逐端 ACK 与未选中端点

仅针对观察到的第一事件，分别通过两个端点各自的真实 CLI 凭据提交 ACK；回执的事件、投递及完整 route 均与观察记录一致：

| 接收端 | `state` | `acknowledgedAt`（UTC） | 证据 |
| --- | --- | --- | --- |
| A | `acknowledged` | 2026-11-01T03:10:17.680Z | [A 回执](2026-11-01-global-device-authorization-core-runtime-ack-a-evidence.txt) |
| 新 B | `acknowledged` | 2026-11-01T03:14:00.170Z | [B 回执](2026-11-01-global-device-authorization-core-runtime-ack-b-evidence.txt) |

按后续授权，两端对第一事件各重复一次 ACK，完整回执和确认时间均与首次一致。针对这两条指定投递的 Core 存储元数据只读检查显示正文为空、长度为 0；没有读取正文。[重复 ACK 证据](2026-11-01-global-device-authorization-repeat-ack-evidence.txt)。主 Agent 报告真实手机 UI 的队列在 A ACK 后由 `2 events / 4 deliveries` 变为 `2 / 3`，B ACK 后变为 `1 / 2`；这是主 Agent 的 UI 观察，不是本执行者自行操作手机所得。

未选中旧 B 的原代次租期过期后，使用其原凭据读取返回 `409 ENDPOINT_UNAVAILABLE`，不能当作零投递证据。随后按明确授权使用原登记键续期，端点 ID 不变、generation 从 1 变为 2；发现新 route 后，拥有者 GET 返回 `200`、数量 0。[旧 B 证据](2026-11-01-global-device-authorization-old-b-unselected-evidence.txt)。该结果只说明新代次检查时为空，不能证明旧代次历史上无投递，也不能证明未选中时对后续新事件的行为。未选中状态由主 Agent 报告，本执行者没有检查手机 UI。

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

## Core B 临时授权到期观察：5/30/60 分钟

本节记录后台观察已完成的 5、30 和 60 分钟结果。Core B 使用现有测试 CLI 凭据和明确绑定的设备，没有更换令牌或创建新绑定。用户报告三个授权均由原用户 10 的真实 App 界面产生，App 未重启、授权版本未更改；本执行者未操作或独立核验手机界面。

- 授权记录包含相同通话查询能力、分别为 `limit=1/2/3` 的原始参数，且 `receivedAfter=2099-01-01T00:00:00Z`。三次点击的前后时间界经过结构和一致性检查；到期时刻使用各次**点击后上界 + 时长 + 5 秒**，不把点击前时间误当作上界。
- 文件中的单调时间与主机单调时钟的时基不同，未直接混用；按点击后 UTC 上界换算至主机单调时钟。该脚本在后台按真实时间等待，没有调整系统时钟；启动时记录 `PID=2465473`、总截止 4200 秒，并当时核实进程存活。主 Agent 保存的完成证据显示最终 `finished / PASS`，实际运行 2848.13 秒。[私密运行日志](../plans/2026-11-01-global-device-authorization/runtime/temporary-approval-run-20261101T041916Z-a18a9a64/observer.jsonl)包含请求轨迹，不应公开凭据或未经脱敏的输入数据。
- **5 分钟：延迟复验 PASS。** 计划到期后检查时刻为 12:06:21.530，实际发起前检查点为 12:19:17.152，记录约晚 775.6 秒。Core B 请求 `devreq_f2d65fb3-bc4e-42ba-adcf-e1ad61c8a4dd` 经 `pending` 到达 `claimed / localAuthorizationState=awaiting_confirmation`，随后 CLI 取消到终态 `cancelled`，日志没有记录 `succeeded`。这是到期后的延迟复验，**不证明恰好到期后 5 秒**的行为。
- **30 分钟：自然到期观察 PASS。** 计划检查时刻为 UTC `2026-11-01T04:33:39.142Z`，即北京时间 `12:33:39.142`；发起前检查点为 UTC `04:33:39.253Z`，脚本记录晚 0.112 秒。使用 `limit=2` 和未来时间参数的新请求 `devreq_3d65079a-0b10-47a2-810f-1a82ccc83609` 经 `pending` 到达 `claimed / localAuthorizationState=awaiting_confirmation`，随后取消到终态 `cancelled`；观察期间日志未记录 `succeeded`。
- **60 分钟：自然到期观察 PASS。** 计划检查 UTC `2026-11-01T05:06:31.758Z`，即北京时间 `13:06:31.758`。请求 `devreq_db56f068-6553-41fd-884b-d5e0b3235109` 的完成证据记录了 `claimed / localAuthorizationState=awaiting_confirmation`，随后 CLI 取消至 `cancelled`，该项 `case_finished / PASS`。

`due` 的 `actualUTC` 是 CLI 发起请求之前的调度检查点，不是服务端提交时间；日志中的 `submitted`、等待确认和取消记录均未附带各自时间戳，不能推断精确时刻。[5/30 分钟脱敏日志摘录](2026-11-01-global-device-authorization-ttl-5-30-evidence.txt)和[主 Agent 保存的完整结果证据](2026-11-01-global-device-authorization-ttl-complete-evidence.txt)保留实际记录顺序。计划时刻不是测试结果；设备离线、`GRANT_STALE`、未观察到等待确认或取消未到终态均不能记作通过。

## Core B 最终终态进度字段复测

Terra 仅将 Core B 在无非终态请求时有界重启到 `97f601f7` 生产源码，保留原账号、令牌、设备配对、主密钥与 TLS 状态，旧源码快照留存。Core A 未重启。Core B 的 `core.py` SHA-256 为 `026a6119fb16545a1250c454a55073fc7d4aaec7950849dd56929759847398f7`。

使用原 B 凭据发现 schema 后提交未来范围空通话请求 `devreq_a7ef8a7a-559c-4ee3-b73b-3af3722a2084`，先观察 claimed/awaiting_confirmation，再取消；cancel_requested 与最终 cancelled 都不包含 localAuthorizationState，证明终态不继续显示等待/执行。见[脱敏证据](2026-11-01-global-device-authorization-core-b-97f601f7-cancel-evidence.txt)。此结论仅适用 B 运行快照，A 尚未更新，不能笼统称两个运行 Core 均为最新。

## 5556 被动短信 KEYWORDS：真实 CLI/MCP 三请求

2026-11-01，工作树 HEAD 为 `1f338778ecd6c39fc8eb0bd11134867d6c238568`。Hubble 报告新包已安装，真实 UI 已保存并重新进入确认：被动短信为 READ_ONLY、KEYWORDS=`OAI-SAMPLE-SMS-5556-20261101`、正文关闭；号码草稿仍保留 phone22，主动推送范围独立。本执行者未操作手机或读取原 user0 设备。

通过 5556 的现有专用 CLI 凭据发现 `org.openandroidintelligence.sms.query@1.0.0` 为 READY；官方 MCP stdio 子进程返回的 schema 与 CLI 一致。实际 schema 只允许 `senders`、`receivedAfter`、`limit`、`query`，没有远端 `includeContent` 开关。本次仅发出以下三条请求，收到各自终态后继续下一条，没有人工批准或结果 ACK：

| 场景 | 入口 | requestId（脱敏） | 实际结果 |
| --- | --- | --- | --- |
| phone22 元数据，不带 query | CLI | `devreq_afcbe5cd...c244cba2` | `succeeded`，1 条；正文内容为空或缺失的检查通过 |
| phone22 加固定标记的正文搜索 | 真实 MCP | `devreq_0d78310d...c4c31fad` | `denied / LOCAL_DATA_SCOPE_DENIED`，由 Android 本地范围拒绝 |
| phone44 元数据，不带 query | CLI | `devreq_90f6ed58...c3de6745` | `succeeded`，0 条 |

三项均限定 `receivedAfter=2026-11-01T05:02:22Z` 和单个测试发送方；元数据两项 `limit=5`，正文搜索 `limit=1`。没有扫描其他号码或输出短信正文。元数据结果没有远端正文，因此不能据此直接复验样本文字；其与本地关键词的关系还依赖 Hubble 的真实配置及专用样本记录。[公开脱敏请求与接口记录](2026-11-01-global-device-authorization-sms-keywords-evidence.txt)，完整请求 ID 保留在忽略的 `runtime/sms-emulator/passive-keywords-1f338778/run-20261101T102937Z-5a2b7bde/`。

## 5556 主动短信 KEYWORDS：新有效未选端点对照

先通过现有凭据发现原 `TerraSmsReceiver`：generation 1、租期截止北京时间 18:53:08.905，完整 route 与历史记录一致。随后使用相同的既有 5556 凭据、独立登记键注册 `TerraSmsUnselected`，租期 3600 秒，generation 1，截止北京时间 19:33:02.493；新端点的脱敏 ID 为 `endpoint_d9a2937c...b275b418`。发现结果完整匹配新 route，原已选端点的 route、代次和租期未变。两端点使用同一账号和设备凭据，不能把此对照称为独立令牌隔离测试。本执行者没有在手机选择新端点；未选状态由 Hubble 报告。[第二端点脱敏记录](2026-11-01-global-device-authorization-sms-unselected-evidence.txt)。

Hubble 随后报告主动短信范围已独立保存为 KEYWORDS=`OAI-SAMPLE-SMS-KEYWORD-PUSH-20261101`、正文关闭、保留期 1 小时，仅原接收端仍选中，被动设置不变。后台观察器于约 18:38:09 启动，最长 300 秒；Hubble 报告在主机北京时间 18:39:43.369978741 至 18:39:43.387304266 之间唯一一次从 OS 注入 sender55 的专用样本，退出码 0。本执行者没有注入、操作手机或读取短信正文。

- 观察器在 97.727 秒后正常 `finished / PASS`：已选端点接收到 sender=`15550105505`、`eventType=sms.received`、启动后时间及完整 route 匹配的新事件；`body` 和 `text` 键均不存在。
- 事件为 `push_d962b57e...d6a93169`，投递为 `delivery_0a66aed6...35d71e31`。同轮已选端点为 1、非目标数量 0；新有效未选端点真实 owned GET 返回 0，所有已记录轮次均为 0。两端租约均覆盖这次观察。
- 该对照覆盖了一次新受控 SMS 事件，与此前旧 B 过期后换代才得到空列表的证据不同。正文未外发，接收端不能独立检查正文标记；事件与固定标记的关联依据 sender、时间、route 及 Hubble 的唯一一次受控注入报告，不把元数据当作正文读取证据。
- 观察器始终 `ackSubmitted=false`，事件保留未由本执行者确认，供后续退出、离线或进程场景观察。当前手机队列、后续到期或清理尚未由本执行者验证；本轮没有续租、改变代次或为 sender66 启动新观察器。

[公开脱敏元数据推送记录](2026-11-01-global-device-authorization-sms-keyword-push-evidence.txt)。完整事件、投递及 route 位于忽略的 `runtime/sms-emulator/unselected-receiver/keyword-push-20261101T103809Z-6be92d56.receipt.json`，权限 `0600`。

## 5556 真正断开 Core 传输后的同请求恢复

本场景使用 secondary 测试账号中已经存在的当前 d097 设备绑定，generation 为 1；官方 CLI 登录仅为该既有配对签发独立客户端令牌，没有新建设备或配对，也没有替换旧 c16 客户端。后续请求使用当前绑定的专用配置，不使用旧设备或旧接收端 route。

Hubble/root 报告：2026-11-01 北京时间 20:39:00，仅移除 5556 的 `tcp:19841` ADB reverse mapping，其他端口及系统网络未改；20:46:25 确认恢复 `tcp:19841 -> tcp:19841`，OAI 回前台，期间未更改账号、授权策略或权限。本执行者没有操作或独立检查手机、ADB 或网络，以下 Core 结果只与该外部报告关联。

通过当前 d097 凭据仅提交一次 `sms.query@1.0.0`，参数为 `senders=["15550102202"]`、`receivedAfter=2026-11-01T05:02:22Z`、`limit=1`，不含远端 `query`。断开期立即查询一次，恢复确认后再次查询同一 ID 一次：

| 阶段 | requestId（脱敏） | 实际结果 |
| --- | --- | --- |
| 断开期提交与状态检查 | `devreq_d1416263...e399be8e` | `pending`，`DEVICE_OFFLINE`，`queued=true`，无结果 |
| 恢复后同请求状态检查 | `devreq_d1416263...e399be8e` | `succeeded / result.outcome=succeeded`，1 条元数据，`text=null`，无 `body` 字段 |

三份已有回执中的 requestId、d097 deviceId、pairingGeneration、能力和请求期限均一致。元数据记录匹配专用测试发送方和日期下界，没有输出短信正文。任务只提交一次，未重提、未人工批准、未取消该请求，也未发送结果 ACK；恢复执行不需要新的设备请求或重新选择手机范围。[公开脱敏恢复证据](2026-11-01-global-device-authorization-sms-offline-recovery-evidence.txt)。

Core 实际记录 `createdAt=2026-11-01T12:41:18.683Z`、`expiresAt=2026-11-02T12:41:18.683Z`，两者相差 **86400 秒，即 24 小时请求排队寿命**；对应北京时间 11 月 1 日 20:41:18.683 至 11 月 2 日 20:41:18.683。该期限与 **5 分钟本机确认等待**独立，不能互换；本请求按 READ_ONLY 执行，没有等待或点击人工批准卡片。

断开期本地授权页面可读和网络 mapping 动作来自手机 Agent 报告；草稿取消因工具超时未完成，**不记为 PASS**。本场景的 PASS 仅覆盖 Core 离线排队后同一请求恢复成功，不代表断开期全部本地 UI 流程通过。
