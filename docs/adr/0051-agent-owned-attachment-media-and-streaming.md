---
status: accepted
date: 2026-09-23
supersedes:
  - ADR-0018
---

# 将附件格式和处理大小交由 Agent 决定

Android 与 Gateway 不按附件字节数上限或 MIME allowlist 拒绝用户明确选择的附件；两端通过账号隔离的加密暂存和有界内存流式传输，把媒体类型作为描述信息透传给 Agent，由 Agent 与模型决定是否处理及其自身资源策略。`sizeBytes`、`Content-Length` 和 SHA-256 只用于传输完整性核验，不作为大小上限；磁盘、网络、代理或 Agent 策略导致的失败必须有明确状态，不能静默退化为空文本。

附件暂存保持 TTL、认证、账号隔离和 ACK 生命周期。实现必须在流完整写入且长度/摘要匹配后才标记 `verified`，并以 versioned authenticated-encryption chunks 加密落盘；不承诺突破 Android、文件系统、HTTP 代理或 Agent 自身的实际资源能力。

## 存储兼容与配对隔离

Gateway 在内部附件记录中保存创建附件的 `deviceId` 与 `pairingGeneration`，解除配对只移除该配对创建且未被确认的附件。升级前已存在、没有设备归属字段的附件不猜测归属，保留原 TTL 清理，避免解除一台设备时误删同账号另一台设备的数据。字段、格式标记及数据库触发器在同一个 SQLite 事务中迁移；事务提交后，旧宿主缺少配对归属的 INSERT、未递增存储修订号的 UPDATE，以及未经新宿主授权的 DELETE 均会失败。

Hermes 旧版 `aead-v1` 文件把整份密文作为一个 AEAD 值，没有有界内存的解密接口。为保持流式内存边界，升级时事务性地将其置为明确的 `failed` 并请求客户端重新上传；状态与审计事务提交失败时保留旧记录和文件，提交成功后再清理旧字节。OpenClaw 旧版纯明文暂存也采用升级前排空：先在事务内记审计并撤销旧附件记录，事务提交后再清理原文件；失败则旧记录和原文件仍可恢复。之后客户端可复用 `clientAttachmentId` 创建新的流式附件。两种排空只影响升级前已有暂存，不构成新的附件大小限制。新格式文件继续由长度和 SHA-256 校验，旧宿主无法把密文校验为明文附件。

Protocol 2.1 core Schema hash 为 `sha256:b84d7ee1efacf538e12af5fca7dd55f3123fba42c98f4ffddd20a5483fe46dba`。Android、Hermes 与 OpenClaw 必须使用同一版本发布；旧 core hash 继续由严格协商拒绝。
