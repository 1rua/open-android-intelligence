# 原生用户消息身份与受理状态边界

延续 PR #64，本步不改变旧 Gateway 的默认兼容行为。

## 已复现的三项失败

唯一 Android 构建 owner 运行 `TimelineTurnDedupTest` 与 `WorkbenchDuplicateAndDeliveryTest`，实际 **29 tests / 3 failures**。两项身份失败分别为：不同真实用户 ID、相同正文和接近时间被合并；真实用户 ID 恰好带 `local_` / `stream_` 前缀，即使隔着回答也被全局去重合并。第三项失败为 `MessageAccepted(agentReceived=false)` 仅确认服务端受理，Workbench 仍提前推进 DELIVERED。

失败来自真实 Workbench 历史渲染与事件消费路径，日志在已忽略的工具目录 `pr64-native-acceptance-identity-ui-red.log`。

## 修改

原生 `authoritativeMessageIds` 模式下，用户消息去重只使用真实消息 ID 或明确的本地占位和客户端关联，不再使用正文相同、时间接近或去掉字符串前缀猜身份。相邻去重和跨回答的全局去重都受此约束。多个不同原生用户 ID 即使共享客户端关联，也必须显示为不同记录，不能用 UI 合并掩盖宿主实际写入；只有明确标记的本地占位可以根据同一客户端身份与唯一真实消息合并。旧 Gateway 的无权威 ID 兼容分支保持原行为。

`MessageAccepted` 新增可选 `agentReceived`，默认 true 保持旧 Gateway 契约。原生 Core 可以明确返回 false，Workbench 仍据真实回执绑定用户消息身份，但两处 QUEUED → DELIVERED 推进均必须确认 `agentReceived=true`。后续实际 Agent 接收通过权威 DELIVERED 状态事件推进，服务端受理本身不能伪称 Agent 已收到。

保留三项红测，另补显式本地占位仍可合并、共享客户端关联的两个真实写入不能隐藏这两项回归。源码通过 `git diff --check`；唯一构建 owner 的联合验证已完成：客户端 32 项、UI 91 项、仓储和所有者 34 项，共 157 项通过、0 失败、0 跳过，`BUILD SUCCESSFUL in 2m 12s`。该轮包括 queued/running 生命周期解析与 server-only acceptance，不把后续尚未验证的 done 生命周期版本修复混入本步。
