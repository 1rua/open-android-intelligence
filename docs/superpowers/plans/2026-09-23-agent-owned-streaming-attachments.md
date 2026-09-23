# Agent 自主处理附件的流式透传修复计划

## 目标

移除 Android 与 Gateway 的单文件/单消息产品级字节上限和 MIME allowlist，让 Agent 决定附件格式与处理大小。保留长度/SHA-256 完整性核验，并通过账号隔离的加密暂存与有界内存流式传输避免整文件常驻内存。执行依据为 [ADR-0051](../../adr/0051-agent-owned-attachment-media-and-streaming.md)；原 [ADR-0018](../../adr/0018-use-gateway-attachment-limits-with-device-safety-refusal.md) 仅保留为历史背景。

## 实施顺序

1. 升级 Gateway Protocol 至 2.1：协商移除单文件/单消息最大字节数和 MIME allowlist；附件元数据保留实际长度、摘要和媒体类型。新增有序 `conversation.message.status` 状态事件，携带递增 revision；`errorCode` 必填，非失败为 `null`，失败只允许稳定脱敏枚举；重算 core Schema hash。
2. 建立 Android 和 Gateway 的分块认证加密暂存接口。对附件流边写入边计数和计算摘要；只有 EOF 后实际长度与摘要匹配才转为 `verified`。保持控制面 JSON 请求体限制独立于附件内容流。
3. 将 Android 选图、相机和屏幕选区附件改为 IO dispatcher 上的加密暂存、固定长度签名流式上传、进度/取消/重试和明确 I/O 错误；创建按`clientAttachmentId`元数据幂等，未知上传/提交结果先查`GET /attachments/{attachmentId}`再继续，避免重复附件；时间线改存元数据与采样缩略图。
4. Hermes 以宿主媒体事件传递正文、媒体路径和 MIME；OpenClaw 接通原生 channel inbound media facts。两端在 Agent 确认接收后 ACK 暂存内容，Agent/model拒绝时写入关联消息 ID 的状态事件。
5. 对 Hermes 旧 AEAD 暂存格式及 OpenClaw 旧明文暂存格式执行事务迁移，支持失败回滚并阻止旧版本使用新格式；Protocol 2.1 的 Android、Hermes、OpenClaw 同版本发布。

## 验收

- Schema、协商向量、双宿主共享向量均验证 Protocol 2.1；旧 core hash 返回 `PROTOCOL_INCOMPATIBLE`。
- 超过原 1 MiB、25 MiB、50 MiB 门槛的合成流可到达 Agent 测试端，传输内存与总文件大小无关；任意非空 MIME 都能到达 Agent。
- 长度或摘要不匹配、流中断、磁盘不足、密文篡改和账号错配均失败关闭，不留下 `verified` 或跨账号可读暂存。
- 图片-only、文字+多附件、Agent/model拒绝、重试、ACK、过期、解除配对与迁移回滚通过 Android/Hermes/OpenClaw 集成测试；最后在真实设备和两个宿主完成完整闭环。
