# 全局设备授权与主动推送操作说明

需求依据 [2026-11-01 规格](../superpowers/specs/2026-11-01-global-device-authorization.md)。
实现尚在验证，本页描述本次提交的目标操作入口；通过状态以[执行记录](../reviews/2026-11-01-global-device-authorization.zh-CN.md)和
[Terra 运行报告](../reviews/2026-11-01-global-device-authorization-terra.zh-CN.md)为准。

## 在手机上配置能力

进入设置 → 授权管理 → 全局设备能力，安装、启用需要的签名插件后，对每一项能力设置总开关与模式。
新能力采用「每次手动」；「允许只读」只让已实现的读取自动执行；「自动允许」仍受到系统权限、本地数据范围和系统强制同意限制。
纯读取能力的后两种模式不会产生额外权限。断开 Core 后仍可查看和修改这些本地设置。

请求到达时，系统通知和 App 内确认卡片显示 Agent 声明名称、目的、能力、操作与参数；声明只供展示和审计。
可拒绝、仅允许本次、允许 5 分钟、30 分钟或 1 小时。确认等待最长 5 分钟；调用者给定更短期限时以更短期限为准。
临时许可按能力、可信操作类型与实际参数摘要精确匹配，无法证明相同数据范围的新参数需要重新确认。
临时许可跨合法 Core/账号/Agent 共享；撤销、取消、到期及进程重启后失效。参数变化不会自动扩大批准范围。

## 主动推送与离线队列

进入设置 → 授权管理 → 短信与通知主动推送，分别开启短信或通知，选择独立内容范围与接收端点。
主动推送默认关闭，配置完成后不逐条请求 OAI 确认；被动查询的「每次手动」不阻止已独立批准的推送。
系统短信读取权限、通知监听特殊访问和插件启用仍需满足。

接收端点由已配置且合法登录的 Core 发现，可跨 Core/账号多选。展示名称不参与信任；端点由 Core 实例、账号路由范围、端点 ID 和代次完整绑定。
撤销推送或移除端点立即停止相应投递，清理不再允许的本地待发送正文，并在连接可用时清理 Core 短期暂存。

手机保存唯一长期敏感内容加密队列，默认保留 72 小时，可设置 1 小时至 7 天。每个端点独立确认，只有接收端消费 ACK 才算完成；Core 接受 HTTP 请求不等于消费。
网络中断后通过 Android 后台任务恢复，同一稳定事件 ID 去重。到期、容量限制或撤销均展示可观察结果，容量不足不静默丢弃。
退出全部账号暂停采集、执行与推送，仍保存配置和有效加密队列；重新登录后重新核验端点和连接再投递。
后台任务受 Android 调度和 Doze 限制，强行停止 App 后须用户重新打开，不保证系统自动运行。

## Agent 调用

所有 Agent 使用同一 `oai` CLI/API/MCP。调用前发现实际能力及参数，携带 Agent 声明名称和操作目的；
可信调用凭据仍必须允许访问目标设备。设备访问关系不授予其他账号的会话、附件或历史。
旧未声明调用返回明确错误，不能编造 Agent 名称或以名称授予权限。
先发现真实 Schema，再提交：

```bash
oai capabilities
oai invoke org.openandroidintelligence.sms.query --version 1.0.0 \
  --parameters '{"limit":5}' --agent-name "测试助手" --purpose "查看指定测试短信" \
  --idempotency-key "sms-test-001"
oai status <REQUEST_ID>
oai ack <REQUEST_ID> --result-digest <RESULT_DIGEST>
```

幂等重试复用同一键、声明和参数；修改任一项视为新请求。`pending/claimed` 是未完成状态，
`localAuthorizationState=awaiting_confirmation` 明确表示等待手机用户确认。提前 ACK 拒绝；重复终态 ACK 返回同一回执。

接收端使用独立的现有合法设备凭据注册，并等待用户选入白名单：

```bash
oai endpoint register --registration-key "test-receiver-001" --label "测试接收器" --ttl-seconds 3600
oai endpoint list
oai endpoint receive <ENDPOINT_ID>
oai endpoint ack <ENDPOINT_ID> <DELIVERY_ID> <EVENT_ID>
```

API 调用 body 携带 `caller:{agentName,purpose}`。MCP `oai_invoke` 使用 `agent_name/purpose`；
接收端工具为 `oai_register_endpoint/oai_endpoints/oai_receive_deliveries/oai_ack_delivery/oai_unregister_endpoint`。
端点租约、设备绑定和持有者凭据继续由 Core 验证；注册只形成路由，不获得敏感数据许可。

## 验收与故障定位

区分本地关闭、等待确认、用户拒绝、确认超时、系统权限缺失、未安装、设备离线、协议不兼容和连接绑定失败。
任何成功结论都需要 Android 实际结果；正常拒绝不能通过自动重试绕过。以 `requestId` 关联 Core 请求、手机日志和 ACK。
自动化、模拟器和物理真机报告分别说明，未具备的系统交互或实体设备条件标记 BLOCKED。
