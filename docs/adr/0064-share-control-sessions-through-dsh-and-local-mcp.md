---
status: accepted
date: 2026-11-13
implementation_status: not_started
acceptance_status: pending
issue: 185
---

# 原生 DSH 与本地 MCP 共用手机确定性控制会话

手机 Virtual Host 提供 phone-capabilities 虚拟工作区、常驻 control-bus 和真实创建的任务会话，
以兼容最小 Session V3 的方式暴露确定性操作，不运行本地 LLM。
原生 DSH 经 remote_* 工具访问；外部 Agent 经电脑 Stdio MCP 和 DSH 进程内桥访问相同入口。
两条路径共用手机的请求解析、平台内核、授权、幂等回执与执行端口，不能复制第二套控机实现。
具体请求和文件约束见[控制契约](../contracts/oai-control-rpc-v1.md)。

原生 remote_send_message 最终调用 session/prompt，因此手机须实现真实工作区、会话目录、
状态、历史及文件读取接口；不能只拦截一个并不存在的同名网络 RPC。
session/create 返回独立任务会话，不能每次将固定 control-bus 当作新会话返回。
control-bus 同时支持按可信拥有者/request_id 隔离的单步调用，多步工具调用显式复用同一
taskSessionId/snapshotId。首次 MCP 读树或截图可以真实建任务，动作不得缺少任务时暗中新建。

## 取舍

为手机再运行一个 Agent 会增加模型依赖、推理不确定性与内存开销；确定性 Host 可直接落实权限和回执。
只做独立 MCP 网络客户端则需要另一套客户端身份和连接，偏离复用本机通道的目标。
选择 DSH 进程内桥后，Stdio 层保持无独立 Remote 身份与任务存储，代价是外部 Agent 控机时电脑须运行
DSH 和桥插件，并通过其原生配置挂载 MCP。这个本机桥不是被废弃的服务端会话适配器。

现有公开通道接口不能无副作用地对任意 Codex/ACP 目标后台选路。
用户接受全部后端委派串行切换电脑 DSH 当前工作区，因此采用公开选择接口，不依赖 private hooks 或上游分叉。
Bridge 串行管理委派目标及观察，报告选择副作用；用户手动改选或连接失效后不自动抢回、不重放写操作。
审批仍由真实拥有者处理，不能把 MCP 连接、同账号关系或会话 idle 当成批准或任务成功。

## 后果

电脑桥复用现有 WebServer 的本机受限路由并限制操作、调用来源和认证，不新建 HTTP/WS 监听；
不向外部 Agent 分发设备私钥或 Remote token。
手机控制任务的消息、树、截图和结果正文仅内存保留；重启不恢复、不回放或续做。持久层仅
保存 owner/request/task/op/参数摘要/execution/state/取消关联与旧任务失效标记。
当前 control-bus 的只读 request.status 可查询原最小回执，正文未保留时明确返回
resultAvailable:false，不恢复旧任务或伪造正文。临时 artifact 同样不在重启后恢复。
正常聊天镜像、主动推送队列和定时任务仍按各自功能加密持久。
手机控制任务需要最小持久防重回执、私有文件作用域和取消/未知结果语义；“薄壳”仅描述 Stdio 转接职责，
不表示整个通道、身份、审批与执行状态都无状态。实现和验收要求见[总规格](../specs/2026-11-13-dsh-remote-architecture-refactor.md)。
