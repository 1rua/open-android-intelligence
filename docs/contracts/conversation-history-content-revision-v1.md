# 对话历史可选单条正文修订

此兼容响应约定用于 Gateway Protocol 2.1 的历史读取与按原客户端身份查询，不改变核心 Schema 摘要或认证协商。旧客户端忽略新增字段；未提供该字段的宿主继续使用原有兼容合并路径。

`GET /open-android-intelligence/v2/conversations/{conversationId}/messages` 的单条消息可提供非负整数 `contentRevision`，表示该消息 canonical 正文及附件引用的版本。它不表示 Agent 投递状态，不可从时间、整页 `snapshotRevision`、游标或哈希推导。来源必须保存实际单条版本并在正文变化时递增，Android 只允许明确更高的版本更新本地原稿，较旧页不得覆盖较新事件。

按 `clientMessageId` 查询同一消息也透传该字段。Android 在加密镜像与发送记录中保存已应用版本、正文和附件；空正文且更高版本表示明确清空正文，不代表消息删除。普通空页、字段缺省或未知结果不能抹去本地发送内容。

Hermes 使用原生 `SessionDB` 的 namespaced 元数据保存展示记录和版本，并在已有纯文本 `conversation.message.delta/completed` 事件中提供相同的实际版本。不得新增未分派的事件类型；附件结构变化通过完整历史响应传播，不冒充只允许文本的核心事件载荷。

明确的无修订 `DELETED` 快照沿用消息身份的持久删除屏障，不声称支持撤销该快照删除。现有带修订的事件墓碑仍按原规则允许更高内容事件恢复，二者不可混淆。聚合输入的任意宿主编辑没有逐成员拆分契约，适配器不能猜测新的成员正文与身份。
