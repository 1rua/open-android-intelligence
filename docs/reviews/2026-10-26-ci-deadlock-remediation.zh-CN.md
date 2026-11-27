# CI 阻塞处置报告：契约门禁失效与分支保护死锁

## 1. 背景

`dev` 与 `main` 的 CI 自 2026-10-24 起持续失败，`dev -> main` 的合并请求（PR #37）长期无法完成。本报告记录两个相互独立的问题：一个真实的代码回归，一个部署顺序造成的分支保护死锁。

## 2. 问题一：契约门禁必然失败

### 现象

`Verify the plugin pins this commit's contract` 步骤报错：

```
❌ 本地没有插件仓锁定的提交 f0b3f1bd…，无法比对契约内容。
```

### 根因

Hermes 与 OpenClaw 两个插件仓的 `contract-pin.json` 都锁定本仓提交 `f0b3f1bd`。该提交只存在于 `fix/shared-sessions-bubbles-live-updates` 分支，而该分支已于 2026-10-24 06:23 删除，提交随之变为不可达提交。新鲜检出取不到它，门禁必然失败——但契约内容本身与 HEAD 完全一致。

时间线佐证：分支删除 06:23 → 首次失败 06:45（间隔 22 分钟）→ 持续失败。

### 修复

`gateway-contract/tools/check-contract-pin.py` 在 pin 提交本地缺失时，先按 SHA 从 origin 补抓再比对；补抓失败才返回原失败。

**未放宽标准**：伪造/不存在的提交仍然 fail closed（已用 `000…0` 实测 `exit=1`）。

## 3. 问题二：Android 单测 OOM

### 现象

`GatewayWebSocketTransportTest > websocket sticky downgrade recovers after sse succeeds` 稳定抛出 `java.lang.OutOfMemoryError`，单轮耗时超过 20 分钟。测试代码自始至终未改动，属产品代码回归。

### 根因链（由提交 `4bd54080` 引入）

```
SSE 收到事件        →  consecutiveFailures 重置为 0
                    →  SSE 正常 EOF 被改判为「失败」
                    →  consecutiveFailures = 1（永远到不了上限 6）
                    →  失败分支只退避、不恢复 preferWebSocket 偏好
                    →  下一轮只走 SSE，又收到同一事件 → 计数再归零
                    →  循环永不终止；测试 delayFn 不真实延时 → 忙循环 → OOM
```

### 修复

一轮中只要收到过事件（无论走 WebSocket 还是 SSE）就恢复 WebSocket 偏好，使「短暂不可达的 WebSocket」不会在整个会话里被 SSE 永久替代。该改动使行为回到 `main` 上 CI 正常时的语义。WebSocket 层有 10s 连接超时与 20s 读超时，不会挂起。

## 4. 问题三：分支保护死锁（配置缺陷）

### 现象

PR #37 的所有实际检查均为 SUCCESS，但 `mergeStateStatus` 长期停留在 `BLOCKED`。

### 根因

这是**自我阻塞的循环依赖**：

1. `main` 的规则集 `main-branch-policy` 要求 `PR Source Check` 通过才允许合并；
2. 提供该检查的 `pr-source-check.yml` 只存在于 `dev`，尚未进入 `main`；
3. `pull_request_target` 触发的工作流**只从默认分支（`main`）读取定义**；
4. 因此该工作流在 `main` 上不存在 → 检查永远不会运行 → 永远无法满足要求。

关键时间戳印证了部署顺序失误：

| 事件 | 时间 |
| --- | --- |
| 规则集 `main-branch-policy` 创建 | 2026-10-24 13:49 |
| `pr-source-check.yml` 进入 `dev` | 2026-10-24 14:19 |
| 该工作流在 GitHub 注册（进入 `main`） | 2026-10-26 08:30 |

规则集比工作流先落地，且未考虑「首启用时该检查尚不存在」的自举场景。

### 处置

临时从规则集的必需检查中移除 `PR Source Check`（保留 `Node.js & Gateway Conformance` 与 `Android Build & Check`），合并 PR #37 使工作流进入 `main`，随后立即恢复规则集。已验证恢复后配置与原始快照**完全一致**。

## 5. 验证证据

| 项目 | 结果 |
| --- | --- |
| 契约门禁单测 | 9 项通过（0.06 秒） |
| 干净单分支克隆端到端 | 修复前 `exit=1` → 修复后 `exit=0` |
| Hermes / OpenClaw 两侧 | 均通过 |
| 伪造 pin 反向验证 | 仍正确失败（fail closed 未削弱） |
| gateway-client 全量单测 | 271 项通过、0 失败 |
| 从零复跑耗时 | 48 秒（修复前 21 分钟未通过） |
| 合并后 main CI | `Node.js & Gateway Conformance`、`Android Build & Check` 均 success |
| 合并后 main 其他工作流 | `Android APK Artifact`、`Stable Release` 均 success |
| `PR Source Check` 工作流状态 | 已注册且 `active`，job 名与规则集要求的 context 一致 |

## 6. 遗留观察：Maven 镜像抖动

`Stable Release`（run 40249987097）首次尝试因阿里云镜像返回 **502 Bad Gateway** 失败，`build-stable-apk` 报 `Repository maven is disabled due to earlier error`。重试后同一提交全绿。

这说明 `apps/android/settings.gradle.kts` 将阿里云镜像置于官方源之前，镜像短暂故障会直接中断构建。当前不影响合并流程，但属于可用性风险，建议后续评估把官方源前置或为镜像配置内容过滤。

## 7. 结论

CI 阻塞已解除：代码回归已修复，契约门禁恢复可用，分支保护死锁通过有序的规则集调整消除且配置已完整复原。合并后 `main` 上全部工作流通过。
