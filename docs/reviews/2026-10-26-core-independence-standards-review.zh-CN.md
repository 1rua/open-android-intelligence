# Core 独立化 Standards 审查

审查提交：`eb6798dabbe4acbe5a45bbfe1dce83131b3513cd`；基线：
`f2c4a4eab081aa48fc0307c51f0f3c8754a5891d`，区间仅含一个重构提交。
依据用户及根 `AGENTS.md`，并以 Fowler 启发式辅助判断。重点为本人未编写的
Core 公共运行时、可选 Hermes 会话后端、历史隔离、打包和薄入口；未重复审查本人编写的安装器。

## 原审查发现：1 项 P1，后续修复已独立复验

`integrations/hermes-session-adapter/src/oai_hermes_adapter/backend.py:43` 的
`SessionStore` 继承 `AccountStore`，只包装初始连接和事务。
`oai-core/src/oai_core/gateway/core.py:1433` 的 `_reopen_after_unknown()` 在
提交结果未知时关闭旧连接，创建普通 `AccountStore`，把未包装的连接赋回。
这会丢失 `LockedDatabase` 与 SQLite 独占模式，违反用户“不允许旧 Gateway 与新
Adapter 同时写入原有账号数据库”的明确要求。

已在合成账号真实 SQLite 库复现：设置 `store.fail_next_commit = True` 后提交，
收到 `TransactionOutcomeUnknown`；连接类型由 `LockedDatabase` 变成 `Connection`；
另一连接随即成功写入同一账号库。随后 `backend.close()` 在该文件第 159 行因
`Connection` 无 `.connection` 抛出 `AttributeError`。现有正常独占测试未覆盖此分支。

建议由会话存储负责重建连接，持续保留独占与线程锁；无法重新取得独占时应断开
后端并拒绝后续请求。增加提交失败后的竞争写入拒绝、后续事务及关闭成功回归。
这也体现继承恢复行为与子类连接所有权不一致的风险，非单纯命名或格式问题。

## 已核查的范围及证据

- Core 无旧包运行导入；设备状态机集中在 Core，薄入口仅转发兼容命名空间。
- 会话历史从账号自己的持久绑定定位，不枚举其他账号；现有映射、跨账号拒绝、
  正常独占及释放回滚测试合计 `8 passed`，附一个 aiohttp 类型化请求键建议警告。
- 核对本地 Core wheel：21 个契约资产与仓根来源逐字节相同，元数据无 Hermes 依赖。
- 本轮只读源码并运行合成测试，未修改实现、提交或推送；未运行真实模型或 Android
  真机。以上结论针对审查提交，后续修复需重新验证。

## P1 修复复验：通过，关闭本项发现

后续工作树的 `SessionStore._reopen_after_unknown()` 保留原独占连接，回滚并复核
独占状态；无法恢复时将 `LockedDatabase.failed` 设为失败，拒绝旧、新账号租约，
不再替换为普通连接。关闭统一经过包装器。

独立重跑原故障注入：可恢复分支保持同一包装器和连接，确认失败写入已回滚，
旧写进程仍被锁拒绝，后续事务及重复关闭均成功；关闭后可由旧版本独占恢复。
不可恢复分支的旧、新租约均报 `HOST_INCOMPATIBLE`，新 Adapter 无法继续写入，
关闭不再抛异常。固定 SDK 下两份 Hermes 测试为 `11 passed, 1 warning`，包含
新增两项故障回归。上述修复复验针对后续工作树；最终提交仍须绑定 SHA 检查 CI。
