# DSH Remote 未知提交恢复边界

本文对应规格第 6 节“提交结果未知时查询原回执/文件身份，不重复创建附件或消息”，记录实现边界与回归入口。实际测试结果由当前提交的测试记录给出；本文不代表真实 Host、手机或网络验收已完成。

## 固定公开协议事实

上游固定为 `liguobao/ds-harness-remote@ea1c0925f8be825a5f5afac32ad2d0b2c699f7f7`。公开源码 `packages/plugin/src/workspace-upload.ts` 的 Git blob 为 `b4679a694203a7aa559201f1163ca0ad8c811711`，本次读取的源码已按 Git blob 算法核对一致。

- 公开上传方法只有 `workspace.upload.open/chunk/commit/abort`，没有独立上传回执查询方法。
- 同一认证 peer 中，`commit` 按原 `uploadId` 返回 `completed` 中原结果；文件发布后先记录结果，再清理临时文件。重复原 commit 不创建第二个文件。
- 该结果表属于内存，默认有效期 120 秒，最多 64 项；关闭 peer 会清空。失去结果后返回 `UPLOAD_NOT_FOUND` 不能证明文件没有发布。客户端不能换上传身份重新上传来“恢复”。
- Codex `turn/start` 与 ACP `session/prompt` 的当前公开适配参数没有客户端消息幂等身份，也没有按此身份查询的公开方法。空的本机投影不能作为服务端未执行的证据。

## 消息恢复

Remote 仓储仅在投影确实包含原客户端消息身份时返回消息。未命中时抛出 `REMOTE_MESSAGE_QUERY_UNAVAILABLE`，现有工作台、镜像和默认助理恢复入口由原持久发送原子保留 `OUTCOME_UNKNOWN`，不再把空投影当成可重发许可。其他仓储的权威查询返回空值语义保持原样。

公开通道明确返回 `NOT_SENT` 或 prompt 回执明确 `accepted=false` 时，仓储记录 `REMOTE_SUBMISSION_NOT_SENT` 或 `REMOTE_SUBMISSION_REJECTED`，允许按原消息身份重试。普通失败或未知传输结果不取得此许可。用户创建的新消息仍可正常提交。每次真实重发前，普通消息、Steering 与批次均须先把对应发送原子整体持久化为未知并清除旧的安全重试错误码，等待保存完成后才调用服务端；写盘失败不调用服务端。这样本次执行后崩溃不会在下一次恢复时再次使用上次的未发送许可。

附件内容构建发生在调用 prompt 之前。附件结果未知时，用持久错误码 `REMOTE_ATTACHMENT_MESSAGE_NOT_SENT:*` 同时记录“消息 prompt 尚未调用”与“附件本身仍未知”两个事实。恢复必须先恢复原附件；无法恢复时继续未知，不进入消息提交。

## 附件恢复

复用原 `AttachmentDraftRecoveryStore` 与加密落盘实现，新增原上传身份、阶段、稳定授权作用域、会话、原连接代次、文件大小和摘要；确认后保存 Host 返回的路径与文件名。旧记录没有远端上传字段时仍代表原来的本地已校验草稿，可以正常首次上传。

1. open 之前持久化 `PREPARING` 与原 `uploadId`。open/chunk 不发布文件；只有原上传确认 abort 后才可释放该上传身份。
2. commit 之前持久化 `COMMIT_UNKNOWN`。取消、超时和进程终止均不能触发 abort 或创建新 UUID。
3. 同一协调器且原连接仍有效时，可用同一 uploadId 调用公开幂等 commit 取回原结果。没有结果时仍保持未知。
4. 进程重启或连接替换后，不重放原 peer 内存中的 commit；保留原身份和可诊断未知状态。
5. 确认后持久化 `CONFIRMED` 并复用原路径，不重新 open/chunk/commit。跨会话、工作区、账号代次或认证指纹拒绝复用。晚到结果在写入前检查旧连接归属。

稳定授权作用域包含服务 origin、账号、Host、客户端、backend、workspace、账号 epoch、双方认证指纹与角色；connectionId 与连接 generation 仅用于限制未知回执恢复，不当作稳定端点身份。

## 回归入口与证据层级

- `RemoteUnknownWireRecoveryTest`：真实 Codex/ACP carrier 与公开 call 封装，注入服务端已执行但响应丢失，检查原消息查询不能伪装成未执行，仓储重建不发第二轮。
- `WorkbenchSendPersistenceTest`：真实工作台重试入口、持久发送原子与控制器重建；覆盖不可查询未知请求不重发，以及明确未发送请求可按原身份重试。普通消息、Steering 和批次另覆盖发送入口读取磁盘时旧许可已消费、执行后中断重建不再重发、发送边界写盘失败不调用服务端。
- `RemoteAttachmentMessageRecoveryTest`：真实工作台、远端仓储、附件协调器、加密暂存与发送原子串联，覆盖 commit 两次回执丢失后第三次恢复只创建一次文件、只发送一次 prompt，并在控制器重建后保留原消息未发送事实。
- `RemoteAttachmentCoordinatorCancellationTest`：真实上传协调器与公开上传方法，覆盖 commit 后响应丢失、取消、进程恢复、原 uploadId 查询、确认后复用及跨会话拒绝。服务端注入实现模拟上游按 uploadId 的 completed 语义，不声称已验证运行中的上游服务。
- `EncryptedRemoteAttachmentRecoveryTest`：真实加密存储写入与重新实例化，检查未知阶段、原上传身份、确认文件身份及账号隔离。

当前公开上游能力不能在 peer 结果表丢失后权威找回原消息或原文件身份。因此此场景必须保持未知；新增网关、私有查询接口、上游分叉和猜测文件路径均不属于本次实现。
