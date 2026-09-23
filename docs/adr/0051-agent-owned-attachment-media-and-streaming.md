---
status: accepted
date: 2026-09-23
supersedes:
  - ADR-0018
---

# 将附件格式和处理大小交由 Agent 决定

Android 与 Gateway 不按附件字节数上限或 MIME allowlist 拒绝用户明确选择的附件；两端通过账号隔离的加密暂存和有界内存流式传输，把媒体类型作为描述信息透传给 Agent，由 Agent 与模型决定是否处理及其自身资源策略。`sizeBytes`、`Content-Length` 和 SHA-256 只用于传输完整性核验，不作为大小上限；磁盘、网络、代理或 Agent 策略导致的失败必须有明确状态，不能静默退化为空文本。

附件暂存保持 TTL、认证、账号隔离和 ACK 生命周期。实现必须在流完整写入且长度/摘要匹配后才标记 `verified`，并以 versioned authenticated-encryption chunks 加密落盘；不承诺突破 Android、文件系统、HTTP 代理或 Agent 自身的实际资源能力。
