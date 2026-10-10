# 退出登录、Hermes 回复、消息保留与附件上传修复记录

日期：2026-10-11。应用基线为 `72a9be3967362c997a1c911ab3f2a86a0c2c790f`，独立 Hermes 插件基线为 `a41e692dc640d6ef94b8083bf220f260936234dd`。批准范围见 [实施计划](../superpowers/plans/2026-10-10-conversation-bugfix.md)。

## 缺陷与修复

### 侧边栏退出

生产 Activity 原先没有向工作台传入真实登出回调，按钮只能关闭抽屉。现在生产与回归测试复用 `GatewayWorkbenchScreen`，明确调用 `runtime.logout(revokeRefresh = true)`；工作台和抽屉均要求调用方传入非空退出回调。

工作台显示运行层已有的操作提示。离线时保留账号凭据并说明需要先重连；远端拒绝或网络失败不清除刷新凭据。远端确认成功后，独立尝试清理当前账号刷新凭据和自动恢复标记，单项存储失败不跳过另一项，清理失败保持可见。取消继续正常传播。普通登出保留本地资料、配对、其他账号凭据及加密历史。

### 批次、宿主回复与发送记录

独立插件存在两个串联断点：重命名分支对外层 `now` 的遮蔽使其他请求入口抛出异常；路径提取函数不接受 `message-batches`，成功受理的批次也不能进入 Agent。插件将前者改为 `rename_now`，明确允许批次路径，并按真实 Hermes 的契约使用异步完成回调。修复源码提交为 `9f6878b42e8ef73a6a6d8f0e9a41d48a8230c514`，含独立审查报告的提交为 `d7ec5fbfae81cf9228fdd9d53c86e4c99d682b80`。

应用批次客户端使用标准错误信封解析，保留 `INTERNAL_ERROR` 等稳定错误码，严格检查全部成员的远端身份。HTTP 成功表示已受理，Agent 状态由事件推进；已完成、失败或取消的生成不会被迟到回执、旧排队事件或旧快照重新激活。

原工作台检查点只保存活动会话，回执也可能写到切换后的会话。现有加密镜像新增按会话保存的发送记录：提交前先保存客户端身份、正文及附件元数据、原始时间；回执和事件始终更新原会话。空远端快照、切换会话和重启均通过明确身份合并恢复。正文相同的两次明确提交保留各自身份；同一消息回放不重复显示。

发送记录分别保存本地提交状态、Agent 状态修订、内容修订和删除屏障。远端明确删除会清除正文副本并隐藏对应消息；迟到回执和空快照不能撤销删除。未知结果保留身份和内容；进程重启不会自动重发。用户确认恢复时先按原身份查询，已受理则恢复回执；查询失败保持未知结果，只有明确查询不存在才允许以原身份重试。

### 附件加密暂存

旧暂存写入器为受随机化约束的 Android Keystore 密钥自行生成并传入 GCM IV，上传请求尚未开始就抛出 `InvalidAlgorithmParameterException`。修复对每个数据块和文件尾新建 `Cipher`，以 `init(ENCRYPT_MODE, key)` 初始化并保存系统生成的 12 字节 IV。解密仍读取信封中的 IV。

分块格式、AAD、认证标签、长度及 SHA-256 检查均保持不变，账号密钥别名不变，旧附件可继续读取。调用方式遵守 [Android 官方随机化加密要求](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setRandomizedEncryptionRequired(boolean))。

## 复现证据

新增回归先验证旧行为失败，再验证修复。以下是各阶段实际日志，不把中途失败或历史缓存当作最终通过：

证据文本仅归一化行尾空白及文件尾空行，测试结果、错误与诊断内容保持原样。

| 边界 | 修复前失败证据 |
| --- | --- |
| 真实退出回调、离线、取消与凭据清理 | [logout-red.txt](evidence/2026-10-10-conversation-bugfix/logout-red.txt)、[logout-cleanup-red.txt](evidence/2026-10-10-conversation-bugfix/logout-cleanup-red.txt) |
| 发送记录、按会话保存、迁移与删除 | [messages-red.txt](evidence/2026-10-10-conversation-bugfix/messages-red.txt)、[messages-boundaries-red.txt](evidence/2026-10-10-conversation-bugfix/messages-boundaries-red.txt) |
| 错误信封 | [batch-error-red.txt](evidence/2026-10-10-conversation-bugfix/batch-error-red.txt) |
| 终态与迟到生成回执 | [generation-receipt-red.txt](evidence/2026-10-10-conversation-bugfix/generation-receipt-red.txt) |
| 远端显式空内容编辑与客户端身份解码 | [remote-edit-red.txt](evidence/2026-10-10-conversation-bugfix/remote-edit-red.txt) |
| 普通消息恢复查询与同正文不同身份 | [ordinary-identity-red.txt](evidence/2026-10-10-conversation-bugfix/ordinary-identity-red.txt) |
| 独立预审发现的恢复身份隔离、取消终态、批次写盘与删除重放 | [pre-review-boundaries-red.txt](evidence/2026-10-10-conversation-bugfix/pre-review-boundaries-red.txt)：工作台 19 项中 6 项失败，镜像 11 项中 3 项失败 |

附件新增回归采用不可导出、拒绝调用方加密 IV 的密钥与 Provider，覆盖单块、多块、空文件、独立 V1 解密、旧 V1 读取、账号范围、篡改、截断及认证尾部校验。该证据证明平台约束和格式兼容，真实手机 Keystore 与完整上传由手动验收补齐。

## 验证方法与边界

本机只执行改动对应的最小测试，使用独立插件源码和 Python 3.11，关闭 Gradle 构建缓存。`HermesHttpInteropTest` 经过生产 Android HTTP 客户端、真实签名及登录、真实插件 HTTP/SSE 与宿主后台回合；确定性回答只替换模型边界，验证两个成员身份、一次聚合输入、即时回复、幂等重放和后续普通消息。附件 HTTP 回归覆盖创建、上传、校验、发送与元数据。

完整 Gradle `check`、Node/契约门禁与 FullDebug APK 交给 CI。应用 CI 两处插件 checkout 都固定为 `d7ec5fbfae81cf9228fdd9d53c86e4c99d682b80`。核心 Schema 没有变化，摘要保持 `sha256:99309b87dec79f9c737b7d875a6e949ceffb6bb18b468d7ce98ca37d77dd3d50`，继续使用 Gateway Protocol 2.1。

最终本机定向结果为 **215 项通过，0 失败、0 错误、0 跳过**：

| 模块 | 实际覆盖 | 结果 |
| --- | --- | --- |
| 工作台 UI | 发送、恢复、前台重连、布局、命令权威、审批、身份去重 | 112 通过 |
| 应用 | 真实点击及登出 HTTP、凭据清理、附件密钥约束、加密镜像与迁移 | 45 通过 |
| 对话数据 | 事件、生成竞争、附件恢复、生产客户端 HTTP/SSE | 47 通过 |
| 附件加密存储 | 分块暂存、内容、异常及流读取 | 8 通过 |
| 批次客户端 | 受理证据与稳定错误信封 | 3 通过 |

逐类测试计数及实际时间见 [android-suite-counts.txt](evidence/2026-10-10-conversation-bugfix/android-suite-counts.txt)。第一轮统一执行的非工作台模块全部通过，唯一失败是未封批次同时进入普通恢复入口，已修正为按持久批次归属恢复；保留完整 [第一轮日志](evidence/2026-10-10-conversation-bugfix/android-first-green-attempt.txt)。最终修改后重新执行受影响的工作台和应用，见 [最终通过日志](evidence/2026-10-10-conversation-bugfix/android-final-green.txt)，`BUILD SUCCESSFUL`。这些测试任务均关闭构建缓存，未将 `FROM-CACHE` 当作新证据。

随后追加运行同两个 HTTP 互通用例，显式加载本机真实 Hermes 宿主 `75b083e9399115b12513385252811f09c3503c90` 和独立插件 `d7ec5fbfae81cf9228fdd9d53c86e4c99d682b80`：**2 项通过，0 失败**。echo 夹具诊断确认为 `host=native`，两个实际宿主回合分别来自原批次与后续普通消息，重放没有额外回合。该验证经过原生异步完成 hook；仍以确定性 handler 替换模型边界。证据为 [android-native-http-green.txt](evidence/2026-10-10-conversation-bugfix/android-native-http-green.txt) 和 [native-http-boundary.txt](evidence/2026-10-10-conversation-bugfix/native-http-boundary.txt)。两用例属于前述 215 项，追加执行不重复累加测试数量。

退出测试使用 Robolectric 原生图形模式执行真实触摸。Legacy 图形模式的相同坐标点击只关闭抽屉遮罩，不能命中按钮；原生模式确实调用按钮并发送 `DELETE`，三项退出界面测试均通过。临时诊断探针已从源码移除。

插件独立测试为 336 通过、1 个原生环境专用跳过，真实 Hermes 回归另有 8 项通过；两路独立审查均无剩余发现。插件修复及含审查报告提交的 CI 均通过：[源码修复 CI](https://github.com/1rua/hermes-gateway-plugin/actions/runs/38070753720)、[审查报告提交 CI](https://github.com/1rua/hermes-gateway-plugin/actions/runs/38084443175)。应用完整 CI、部署与手机验收状态单独记录，不以源码或隔离回答代替生产验收。

## 数据迁移与手机验收

迁移细节见 [发送记录迁移说明](../superpowers/plans/2026-10-11-conversation-send-ledger-migration.md)。两处部署只更新现有插件程序及必要依赖，再重启现有 Gateway 服务；保留账号目录、主密钥、配对与宿主绑定。

手机使用与应用修复提交对应、经固定 Debug 签名校验的 APK 覆盖安装。用户分别在本机 Gateway 和 `47.251.8.196` 完成普通消息真实回复、回复前切换会话、清理后台后恢复、附件发送及 Agent 接收、侧边栏退出及重启不恢复已退出账号。用户尚未反馈通过前，整体闭环状态保持待手动验收。
