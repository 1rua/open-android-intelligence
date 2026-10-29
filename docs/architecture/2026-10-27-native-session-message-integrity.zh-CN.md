# 原生会话消息身份与附件交付补充约定

日期：2026-10-27。本文补充 [单一 Core 架构决策](../adr/0054-unify-device-authority-and-native-sessions-in-one-oai-core.md)，适用于 `/open-android-intelligence/v2/agents/{agentId}/sessions/{sessionId}` 原生会话入口。设备协议的账号认证、配对、附件上传与请求路由仍由 OAI Core 负责；Android 唯一决定设备能力，普通前台能力默认开启，后台和敏感许可仍独立。此授权修订见 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md)，已在 `feature/android-device-authority-20261028` 实施，待 PR 合入 `dev`。当前验证见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)；原生消息身份与附件边界不变。

## 消息请求与真实身份

`POST /messages` 接受 `text`、可选的 `attachmentIds` 和 `clientMessageId`。至少有非空文本或一个附件；附件 ID 不得重复。客户端 ID 仅用于关联原稿与宿主确认结果，不允许在请求中选择账号或设备。原生接口的单次 JSON 请求仍受既有 UTF-8 字节预算约束，文件字节走独立流式上传。Hermes 在同一事务中核对客户端 ID 并接受消息；已接受的 ID 不能通过新请求改绑设备、配对代次或再次分派。

Hermes 的 `generation` 事件返回真实 `userMessageId`、原始 `clientMessageId` 和 `generationId`。Assistant 的 `delta` 和 `done` 事件返回宿主实际发布的 `messageId`；客户端必须按此 ID 分开保存同一轮中的多条消息。`delta.text` 是该消息的新增文本，`done.text` 是最后一条消息的完整文本。兼容旧宿主时，这些新增身份字段可以缺省，不能用随机 ID 宣称已与远端历史对齐。

Hermes 历史投影保留真实 ID、原始时间值及附件元数据。用户消息返回原始 `clientMessageId`；同一轮有多条 Assistant 消息时，只有最后一条回复返回用户轮次的 `clientMessageId`，其余消息通过各自真实 `id` 对齐。历史中的数字时间来自既有 HistoryPort 的毫秒值。Codex 的流式消息返回原生 item ID，与 `thread/read` 历史身份一致。DSH 从匹配本次请求的原生用户事件及持久化 Assistant 事件取得 ID，在 `done` 中返回 `userMessageId` 与 `messageId`；流式 attempt/chunk 没有稳定消息身份时不作猜测，旧宿主未提供的 ID 继续缺省。

Generic 和 DSH 旧纯文本桥不接受新的请求字段，Core 在调用这些旧桥时保留原有纯文本请求结构；本次未为它们声明附件处理能力。

## 附件跨两个账号存储交付

Android 先通过现有 Gateway 附件端点完成加密暂存、长度和 SHA-256 校验，再把已确认的附件 ID 放入原生消息请求。

Core 在当前认证账号内解析 ID，核对原上传设备与配对代次，并要求附件仍处于可交付状态。跨账号、跨设备和旧配对的附件引用都会被拒绝。Core 向适配器传递只能读取此份已验证文件的对象，按现有加密存储块逐块解密，不把文件编码到消息 JSON，也不接受客户端提供的路径或下载地址。

Hermes bridge 通过受原有独立 bridge token 保护的私有端点接收文件：

- `POST /oai/session-adapter/v1/attachments`：接收原始文件名、MIME、长度和摘要，创建 Hermes 账号内的加密暂存记录。账号映射、设备、配对代次从经过认证的 Core bridge 请求头取得。
- `PUT /oai/session-adapter/v1/attachments/{attachmentId}/content`：以 64 KiB 网络块写入既有加密上传器，重新核对实际长度与摘要，成功后才转为已验证状态。
- `POST /oai/session-adapter/v1/sessions/{sessionId}/messages`：传入 Hermes 账号内的新附件 ID，走原有 `accept_message`、分派、私有文件物化和原生 `MessageEvent` 流程。图片以 `PHOTO`、文档以 `DOCUMENT` 交给 Hermes，保留 MIME 和文件字节。

文件上传完成后，Core 在向原生宿主提交消息前再次检查原认证会话，防止上传期间已退出或已撤销的身份继续发送。

附件的上传幂等标识同时绑定源附件和本次客户端消息，不会把上一次已确认并释放的暂存记录误用于下一轮发送。Core 在本账号加密元数据中记录 Core ID 与 Hermes ID 的映射；重新读取历史时，已由本手机上传的附件继续使用原 Core ID 对齐本地镜像。历史投影的摘要使用既有 `sha256:<hex>` 格式，上传校验器仍接收裸十六进制摘要。此映射只保存元数据，不保存第二份对话或文件正文，也不改变已有暂存 TTL。长期历史媒体取回仍遵守各宿主已有的媒体可用性与下载授权规则。

只有实际具备上述交付路径的 Hermes 适配器声明 `capabilities.attachments=true`。不支持的宿主返回 `UNSUPPORTED_CAPABILITY`，不会丢弃附件后偷偷改发纯文本。

## 错误与验证边界

输入错误保留 `INVALID_MESSAGE`、`INVALID_ATTACHMENT`；权限错误保留 `ATTACHMENT_ACCESS_DENIED`；内容校验错误保留 `ATTACHMENT_DIGEST_MISMATCH`；桥接上传/读取失败分别保留 `ATTACHMENT_UPLOAD_FAILED`、`ATTACHMENT_READ_FAILED`。客户端需要根据这些结果显示具体原因，不应全部解释成 Agent 离线。

自动化验证覆盖两个独立账号存储之间的 PNG 纯附件、docx 与文本混排、真实字节及摘要校验、设备与配对拒绝、原生 ID 与历史对齐，以及固定 Hermes SDK 的真实 `MessageEvent` 文件交付、删除父会话与压缩子会话后列表消失且历史读取返回 404。固定 SDK 测试使用合成处理器返回回复，不调用真实模型；它证明宿主入口收到正确内容，不替代部署后的模型能力和用户真机验收。
