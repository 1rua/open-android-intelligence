# dev 断线恢复、配置保存与设备授权专项修复

日期：2026-10-30（Asia/Shanghai）。本次基线为远端 `dev@c937de9a585adeecd24b2cc75ce5c727c11e148d`，已通过 git ls-remote 核对。主开发目录无脏改，源码修复分别在独立 worktree 中进行，目标为合并到 dev 的 PR。本文将源码事实、合成故障反馈、真实部署证据分开记录。没有读取用户账号数据库、发送真实短信、执行真实屏幕操作或重启共享 Hermes。

## 第一阶段：已验证的根因与边界

### BUG-08：执行连续性与客户端恢复

最新 dev 已包含 PR #64 的独立后台分派、EventStore 缓冲、generationRecovery 和真实消息身份修复。当前故障不能直接归因为“Core 清理 SSE 所以取消 Hermes”。

本次实际执行：`test_hermes.py` 与 `test_remote_generation.py` 共 25 项通过；`test_interaction_pipeline.py -k observer_disconnect` 两项通过。后者分别主动断开手机到 Core、Core 到 Adapter 的真实 HTTP 观察连接，等待观察者解绑，确认宿主任务仍在执行，最终只通过原身份 GET 获取完成回执；用户行和任务分派均为一次。执行本身使用合成处理器，不冒充真实模型。

已确认缺口与反馈：

| 缺陷 | 基线代码路径 | 实际反馈 |
| --- | --- | --- |
| 缺少端到端持久会话事件页与消费 ACK | [Core sessions](../../oai-core/src/oai_core/sessions.py)、[Hermes sessions](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/sessions.py) | 签名请求 GET 原生会话 events 返回 HTTP 404。已有内部 cursor 不代表 Android 已经应用并落盘。 |
| 本地保存失败后恢复仍提前收口 | [CoreAgentConversationRepository](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt)，基线 startRecovery 406–445、emitEvent 109 | 真实仓储与合成传输：正常 finalCommits=1；消费者失败后 finalCommits=0，但状态已 COMPLETED；再次 connectionRestored 后 generation GET 次数不增加。原任务 POST 始终为 1。 |
| 一次写盘异常结束事件消费者 | [WorkbenchController](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt)，基线 observeThreadEvents 3913、4318–4319 | 真实控制器仅一次 flush 失败：eventSubscribers 从 1 变为 0，后续 finalVisible=false。上游 retryWhen 无法捕获下游 collect 的保存异常。 |
| native 权威列表更新未落入离线镜像 | [GatewayRuntime](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt)、[EncryptedConversationMirror](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt) | Robolectric SQLite 连续保存 [old,new]，重开只得到 [old]。通用镜像的旧 checkpoint 保护不能直接取消，需给 native 列表显式更新入口。 |

Android 现有相关基线共 234 项通过。新故障 fixture 连续两轮均为两个对照通过、三个故障断言失败。它们证明存储失败下的恢复缺陷，不证明生产设备已发生磁盘耗尽。

截图中的 Gateway message origin 已定位到固定 Hermes SDK 的 run_busy._steer_text_with_origin。该信息本用于模型的输入来源，必须按真实输入身份与原稿边界修复历史投影，不能按正文关键词删除。相关宿主回归仍在独立子任务中验证。

固定 SDK 的 delivery_ledger 生成 Recovered reply；run.py/run_turn_runner.py 在 resume_pending 恢复流程构造 System note。当前自动化断线回归没有触发宿主中断或额外用户输入。没有对应线上日志，不能认定附件中的所有恢复消息都由同一个入口导致。

### BUG-09：配置保存与结果一致性

已检查 [core_setup.py](../../scripts/core_setup.py)、[setup.sh](../../scripts/setup.sh)、[install.sh](../../scripts/install.sh) 及部署说明。

| 已确认缺陷 | 基线函数与位置 | 实际反馈 |
| --- | --- | --- |
| 校验发生在部分落盘之后 | configure 182–209 | 空安装目录缺新账号密码，真实 setup 保存函数返回 1，但 core.json、Token 文件、主密钥已存在，启动脚本不存在。未注入故障。 |
| 多文件没有统一失败恢复 | configure、private_write | 分别注入 load_config、create_core、启动脚本写入失败，旧 JSON 与 Token 的 SHA-256 已变化，不能恢复整组有效配置。 |
| 同步失败仍被报告成功 | sync_hermes_adapter_env 397、sync_dsh_adapter_env 289；两个 Shell 保存入口 | 注入宿主环境写失败，返回 0，打印“配置与启动脚本已保存”。 |
| 重复配置导致映射漂移 | configure 173、209 | 首次 alice → native-alice，二次空选择保留 Core 映射，却把 Hermes 环境改成 alice。Token、密钥和实例保留，环境与 Core 不一致。 |
| 菜单账号输入未被保存沿用 | configure_agent_integrations 292、save_configuration_and_account 462 | 菜单输入 native-selected-in-menu，保存阶段默认值又取 alice，最终映射成为 alice → alice。 |
| 无法读取旧 DSH 文件却覆盖写入 | sync_dsh_adapter_env 272、update_dsh_credentials_yaml 306 | 真实 EUID=1000 权限 fixture：旧文件不可读，操作未报错但无关环境设置和凭据引用被清除。 |

诊断矩阵为 9 个故障断言失败、1 个正常对照通过。现有配置相关测试 24 项通过、1 项在创建符号链接 fixture 时出错；沙箱外也出现同样创建失败，尚未进入产品拒绝逻辑，不能将该安全用例计为通过。

空 Agent 选择当前意为保留旧接入，与既有部署说明一致；问题在菜单“未启用”与实际保留状态矛盾。本次应保留账号、密码、主密钥、配对、历史和现有 Token，不把空选择自动解释为移除全部实例。

### BUG-10：Android 授权与能力发布

依据 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md)，Android 本地状态是设备能力许可唯一权威。Core 的认证、绑定、Schema/provider/risk 校验与请求路由仍然保留，Adapter 不添加第二套开关。

| 已确认缺陷 | 基线代码 | 实际反馈 |
| --- | --- | --- |
| 成功状态配上旧能力目录 | [CapabilityPublisher](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityPublisher.kt) 24–74 | 关闭后远端 LOCAL_GRANT_REQUIRED，UI 仍 READY；开启后远端 READY，UI 仍 SYNC_REQUIRED。发布器只保存 POST 前 GET 的目录。 |
| 接受已过时的发布 ACK | 同上，67–74 | ACK 等待期间 localRevision 0→1，sync 仍成功返回。完整 Runtime 是否随后出现旧 SYNCED 覆盖，需要另行时序测试。 |
| 多提供者状态按能力 ID 覆盖 | [GatewayRuntime](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt) 1369、[PluginManagementScreen](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt) 499–504 | 已选 a:READY 与未选 z:PROVIDER_NOT_SELECTED 被 toMap 压成后者；缺插件、版本身份。 |
| 绑定与授权状态读取有竞争窗口 | [PairingGrantStateHolder](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt) bind 135、currentKernelGrant 203 | 确定性线程栅栏复现 requested=B returnedA=true。未证明执行层已经越权，后续安全检查仍存在。 |
| 失败原因及引导文案不准确 | GatewayRuntime 1403、PluginManagementScreen 627、PermissionsScreen 130、PermissionCatalog 268 | 异常降为通用 SYNC_FAILED；引导仍要求插件启用后按账号单独授权，属于已确认的源码文案残留。 |

基线 Core 授权/迁移/屏幕能力 39 项通过；现有纯 Kotlin 授权测试 16 项通过。定向复现 5 项失败；下一次 GET 收敛和 ACK 丢失恢复两个对照通过。Kotlin 验证直接编译生产发布器、内核与 JSON 源码，HTTP、插件快照和 journal 使用内存替身，不等同于真实 Core/手机完整链路。

显式关闭、同作者更新保护、旧模型复核、系统权限、通知范围、屏幕与后台许可已有实现。本轮未复现“持久化关闭丢失”，也没有依据恢复 Core 账号级授权。

## 第二阶段：修复设计与当前实现

服务端新增可选 eventSync：复用 EventStore，按当前认证作用域生成加密游标和单调 ACK；普通页严格保留提交顺序，工具通知用真实受理消息关联；过期时先固定高水位，再补原生历史和审批。公开端点与消费顺序见[同步契约](../architecture/2026-10-30-native-event-sync.zh-CN.md)。

服务端首轮新增回归从 5 项失败到 5 项通过；随后扩展到 9 项，覆盖 ACK 重建恢复、Agent 别名隔离、Core 对象与磁盘账号存储重开、长等待期间撤销认证、非法与重复查询，以及跨会话工具通知过滤。关联 Core/远程流/交互/测试选择器联合验证 41 项通过。后续改动的最终计数以下节完整结果为准。

配置、Android 恢复、授权发布、原生输入历史在各自 worktree 中修复；主 Agent 负责整合和交叉回归。该阶段完成前，不把设计目标写作最终产品验收结论。

## 第三阶段：最终验证

待整合后记录针对性测试、Fast、PR 门禁的准确命令、退出码、通过/失败/跳过数量与对应提交。不得把环境 fixture 失败、未执行的真机项目或远端尚未完成的 CI 计为通过。

## 第四阶段：交付与验收限制

目标 PR 基于 dev，不修改或合并 main，不部署共享服务。最终报告将补齐修改文件、最终提交和 PR、远端构建触发信息及未闭合风险。遵守项目约束，不前台轮询 CI。

需要独立验收的边界包括：真实手机熄屏/后台限制与弱网、真实模型及宿主进程中断、物理存储耗尽、现网配置目录所有权与混合版本升级、用户历史数据回滚。自动化合成故障只能证明对应源码路径，不替代这些环境证据。
