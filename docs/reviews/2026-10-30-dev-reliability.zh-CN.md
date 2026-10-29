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

截图中的 Gateway message origin 已通过固定 SDK `75b083e9399115b12513385252811f09c3503c90` 的真实 steer、工具结果插入和 SessionDB flush 路径复现。`run_busy._steer_text_with_origin` 加入模型来源说明，执行中追加输入绕过普通 accept/capture_submission，原生 `display_kind=steer` 行没有 platform_message_id。基线反馈为 nativePlatformMessageId=null、wireClientMessageId=null、wireTextEqualsPersistedInjection=true、originalPreserved=false；普通发送对照的原稿、client/message ID 均保留。该信息本用于模型的输入来源，修复需按真实输入身份与原稿边界关联历史，不能按正文关键词删除。相关合并输入、flush 重试、压缩、取消、宿主重开和合法原生历史的最终回归由独立子任务补齐。

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

服务端首轮新增回归从 5 项失败到 5 项通过；随后扩展到 10 项，覆盖整个 Core HTTP 应用关闭再重开、 ACK 重建恢复、Agent 别名隔离、Core 对象与磁盘账号存储重开、长等待期间撤销认证、非法与重复查询，以及跨会话工具通知过滤。关联 Core/远程流/交互/测试选择器联合验证先后 41、69 项通过。独立审查又复现空库游标无法发现丢失事件、快照依赖的旧尾事件恰好到期两个边界问题；3 个新增故障断言先失败，再改为序号边界与事务内会话清理标记，并增加其他会话过期不干扰的对照。修复后事件同步与现有 EventStore 专项 26 项通过，账号隔离/设备期限/撤销事件回归 48 项通过。后续改动的最终计数以下节完整结果为准。

授权修复已从独立分支 `eae2db0f` 整合到主修复分支 `81d2f675`：绑定与状态原子快照、传输前后所有者及 revision 复核、ACK 后目录回读、多提供者完整身份索引、结构化同步错误与引导说明。授权的额外决定权仍只在 Android；[能力发布约定](../contracts/device-capability-publication-android-v1.md)记录各状态与恢复语义。实际 Kotlin 缺陷/所有者竞争测试 13 项、恢复对照 2 项通过；定向 Android 测试合并证据为 102 项通过、0 失败、0 跳过，其中内核 17 项。首次独立 worktree 缺少真实 WASM fixture 导致 10 项失败，补齐离线编译产物后原失败类 23 项复查通过，保留首轮失败记录，不将它抹去。

配置修复已整合自 `654691d4`（主分支 `ccb2975c`）。保存前复用真实启动的 registry 校验，文件候选与备份先准备，提交逐个原子替换并 fsync；恢复 journal 记录可验证的旧、新摘要，回滚或重跑均拒绝覆盖第三方修改。已有主密钥、账号数据库、密码、历史与凭据不重建；新账号先在隔离目录准备。Shell 入口沿用菜单映射，保留选择语义，环境同步失败不报全成功，可选安装失败返回部分完成。细节和人工恢复步骤见 [脚本说明](../../scripts/README.md)。配置独立分支最终两轮各 58 项通过、1 项链接 fixture 跳过；整合时为两个链接拒绝测试增加现有无害解释器链接回退，并以真实系统 ELOOP 证明拒绝发生在读目标之前，定向两项通过。

原生输入修复已整合自 `876984a`（主分支 `a2075262`）。固定 SDK 暂无公开结构化 steer hook，实例桥接由源码指纹、方法和能力门控约束；生产捕获前保存可信原稿/来源/身份，原生排出与持久化批次携带引用，再投影到原稿，不靠正文匹配。新 13 项原生输入场景覆盖合并输入、flush 重试、压缩、取消、fallback、合法历史与不支持 SDK。旧无关联 metadata 的污染行仍保留，不推测还原。主整合使用瘦 Python 环境时有 13 项 SDK 导入错误（requests 缺失），其他 487 项通过；使用现有完整依赖环境补跑，最终结果将在门禁节记录。

Android 消费修复已整合自 `3f0ce52e`（主分支 `2b00e112`），与能力发布片段正常合并。新增 NativeConversationStore 与同步器固定账号/Agent/会话和设备配对范围；已应用消息、审批、恢复身份与 pending ACK 在加密镜像内提交后再确认游标。ACK 失败重试原位置，终态/历史落盘失败不提前 settled，工作台保存后才标记事件已处理；存储故障有次数上限并公开失败状态，不以无限重试掩盖。native 列表显式保存权威快照，旧 checkpoint 仍不能覆盖新列表。旧或 steering 原稿缺 generation ledger 时保留正文与未知状态，不阻塞其他轮次，也不重复 POST。多轮补偿使用真实页内身份与持久恢复记录，不只取最后一条用户输入。

Android 末轮针对性回归 126 项通过、0 失败/跳过（客户端 37、工作台 32、App 57）。独立分支另有一条 AuthSessionLifecycle 冷启动用例早期超时，已通过同字节码控制实验定位到 Robolectric 首次 native runtime 的字体准备，约 3281ms，超过原 3 秒等待。热态登录 1022ms，提前准备 native runtime 后冷态 273ms、热态 27ms，均未放宽业务等待。旧断言字符串中的 Negotiating 是等待前读取的旧值，不能证明协议协商挂死。本次主 Fast 的全 App 1157 项运行中该用例通过；保留旧失败证据，不据此修改生产登录逻辑。

## 第三阶段：最终验证

整合后配置专项 59 项通过、0 跳过。固定 SDK 缺依赖的 13 项错误，使用已有完整 Python 环境对原生输入/loader/事件同步补跑 31 项通过、0 跳过。链接测试以真实系统 ELOOP 验证，不再把当前文件系统不能创建链接作为成功依据。

Core wheel 在现有独立 Core 环境中安装，在 checkout 外运行真实 oai serve，结果为 distribution=0.2.0、hermes_installed=false、serve=passed，并拒绝错误 Schema Hash；保留原依赖边界。wheel SHA-256 为 `627e6bffb4971719c279fef6052aa1c2db4ec2b79b049e620bdec0de2d29a557`。

首轮 Fast 基于 `2b00e112`，Core 384 项、Hermes 120 项、DSH 18 项通过；Android 2337 项中 2336 项通过、1 项因文件系统不能创建符号链接 fixture 失败，尚未进入卸载实现。随后把路径穿越保护与必须创建链接的保护拆开，定向 13 项通过、1 项明确跳过。这个跳过不代表链接卸载保护已在本机验证；CI 正常文件系统继续执行该用例。最终 Fast/PR 结果以下节补记为准。配置独立复核又确认第三方修改发生在预检之后的回滚覆盖、合法 YAML refs 行被重复追加，以及远程 helper 下载失败留空文件仍报成功，已整合 `3d6c3506`（主分支 `b3cf4e06`）的针对性修复。复核先得到第三方写入竞争 6 项失败、YAML 10 项失败、远程 helper/伪成功 10 项失败；最终配置独立回归 96 项通过、1 项符号链接 fixture 跳过，主分支已有真实链接回退避免该跳过。新逻辑在每次实际替换前复核摘要，YAML 使用安全结构更新并拒绝重复键／危险类型，Shell 核对本次唯一 request_id、完整 committed journal、文件摘要和权限后才报成功。缺少 YAML 解析器给受限错误码与安装补救；其他错误不打印凭据内容。所有写者仍需遵守同一配置锁，不宣称无锁外部写者下的跨文件原子性。第二轮 Fast 使用 `b3cf4e06` 时发现的关闭竞争已在下一节修复并完成最终门禁。不得把环境 fixture 失败、未执行的真机项目或远端尚未完成的 CI 计为通过。

### 整合中补充的工作台关闭竞争

`b3cf4e06` 的 Fast 再次执行时，Core 422、Hermes 120、DSH 18 项通过；Android 2341 项有 1 失败、1 链接 fixture 跳过。失败为 native 账号退出等待 Disconnected 超时。检查发现 Agent 替换先关闭旧工作台、再于 IO 创建新工作台，期间账号退出仍可能读取旧实例；重复 flush 会访问已关闭的 SQLite 镜像。新增两个小回归真实得到 2 项失败，验证重复结束不幂等及原保存错误丢失。

修复用同一持久化生命周期互斥锁协调 flush 与关闭，只关闭一次，并保留真实存储失败；独立复核另指出首个调用方取消不能缓存为后续调用方的取消，已补相应时序测试。Runtime 窗口用例最初在 Robolectric 主线程 runBlocking 引发测试调度等待，线程栈定位后终止隔离测试子进程，改为驱动主 looper 的异步等待，未改生产超时。取消调用方和取消保存 Job 分别有反例回归：首个调用方的取消不缓存成后续取消；保存 Job 的 join 之后仍检查 isCancelled，未完成提交明确报 LOCAL_SAVE_INTERRUPTED。修复后工作台 13 项、App 20 项联合验证通过。已提交 `c1004925`，完整 Fast/PR 已据此通过，准确计数见下一节。

### 最终 Fast 与 PR 门禁

源码冻结于 `c100492541696c8303231f986674bb74301fc7a4`，后续只更新报告、配置说明和 CI 测试依赖。使用已有 Python 环境、固定 Hermes SDK、JDK 21、Gradle 与 Robolectric 缓存；测试 HOME 和临时文件位于仓库忽略目录的隔离位置。

- `npm run test:fast`：退出 0；Core 422、Hermes 120、DSH 18 项通过。Android 2347 项中 **2346 通过、0 失败/错误、1 跳过**，Gradle BUILD SUCCESSFUL。
- `npm run test:pr`：退出 0；相同源码上 Core 422、Hermes 120、DSH 18 项通过，Android 同为 2346 通过、0 失败/错误、1 跳过，Gradle BUILD SUCCESSFUL。
- 跳过项仅为 `PluginInstallStoreTest.removingADamagedPackageCannotFollowItsSymlinks`，当前文件系统不能新建链接；独立路径穿越用例已实际通过。Python 两处链接拒绝通过现有无害解释器链接及系统 ELOOP 实际验证，未跳过。
- Fast 与 PR 完整证据分别保存在 `.toolchains/dev-reliability-20261030/fast-20261029T222026/`、`.toolchains/dev-reliability-20261030/pr-20261029T222618/`，包含源码 SHA、命令、最终退出码、XML 副本及 summary.json。目录名时间为 UTC；本报告使用 Asia/Shanghai 日期。

| Android 模块 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| App Full Debug | 1159 | 0 | 1 |
| conversation-ui | 666 | 0 | 0 |
| gateway-client | 312 | 0 | 0 |
| platform-kernel | 69 | 0 | 0 |
| conversation-domain | 32 | 0 | 0 |
| capability-ports | 51 | 0 | 0 |
| control-ports | 14 | 0 | 0 |
| device-primitives | 22 | 0 | 0 |
| screen-control | 21 | 0 | 0 |

## 第四阶段：交付与验收限制

交付分支为 `feature/dev-reliability-20261030`，PR 目标为 dev，保持打开供审查；源码验证固定在 `c100492541696c8303231f986674bb74301fc7a4`，后续报告与 CI 依赖更新不改变业务实现。不修改或合并 main，不部署共享服务。PR 链接和远端构建触发快照由最终交付消息提供；本地结果不能替代远端 CI，遵守项目约束不前台轮询等待。

需要独立验收的边界包括：真实手机熄屏/后台限制与弱网、真实模型及宿主进程中断、物理存储耗尽、现网配置目录所有权与混合版本升级、用户历史数据回滚。自动化合成故障只能证明对应源码路径，不替代这些环境证据。

## PR #66 的 Android CI 追加修复

旧头提交 `99f4d5a1` 的远端 run `40966830001` / Android job `123773348226` 得到 10 个缺文件失败：CI 测试清单已扩展，但缺少真实 WASM 插件的前置构建。本地已有产物使完整 Fast/PR 未暴露这项工作流缺口。

本地移走而不删除两个 fixture 后，23 项定向测试复现同样 10 个失败，逐项与远端匹配。现已在 Android job 内先安装固定 Rust 工具链、缓存并真实编译 workspace，验证六个 WASM 文件齐备后才执行原测试；测试选择与断言均不变。CI 原命令本地复查 Full 566 项通过，Play 166 项通过、8 项原有产品能力跳过。步骤顺序、缓存工具链、Shell/YAML 与增量 Fast/PR 检查均通过。详细证据及本地 1.88 / CI 1.90 工具链边界见[追加记录](2026-10-30-pr66-android-ci-fixtures.zh-CN.md)。新的远端结果仍由推送后的 CI 复核，不将本地结果记作远端通过。

## 验收标准与证据范围

| 要求 | 自动化证据 | 环境限制 |
| --- | --- | --- |
| 1. 手机失联不取消已受理任务 | 真实手机/Core、Core/Adapter 两条 HTTP 断线测试；关闭整个 Core HTTP 应用后独立 Adapter 任务仍完成；任务分派、用户记录各一次 | 未进行真实厂商熄屏、真实模型运行和宿主进程被终止实验 |
| 2. 保存可信状态并补齐离线事件 | EventStore 稳定身份、140 条消息与审批突发按序重放、隔离 ACK、数据库重开、过期/空库/1001 行清理边界回归 | 保留期外依赖宿主历史及审批快照，不承诺 EventStore 无限保存 |
| 3. 重连不重新提交或伪造恢复输入 | GET 原身份恢复、POST 计数为一；固定 SDK 合法 System note/Recovered reply 保留对照 | 宿主真实重启的原生 resume_pending/delivery ledger 不等同于手机重连，未据无日志症状改写其逻辑 |
| 4. 原会话恢复且不重、不漏、不乱序 | NativeEventSynchronizer、Owner、镜像、generation 修订及工作台保存失败回归，先落盘再 ACK | 旧污染行缺可信原稿时保留原内容；真实手机画面及网络延迟未验收 |
| 5. 配置失败保留原有效数据 | 配置/Token/脚本/宿主文件故障注入摘要对照、进程中断恢复、原密钥与密码核对 | 运行中读者不享有跨文件同时可见；其他写者必须遵守锁 |
| 6. 保存提示符合实际结果 | Shell 本次 request_id、committed journal、完整文件摘要/权限验证；下载空 helper、no-op helper、可选安装失败回归 | 不以保存成功证明宿主已在线或服务已重启 |
| 7. 仅 Android 决定设备许可 | 授权迁移、显式关闭、绑定竞争、插件启用与系统权限回归；无 Adapter 授权设置 | 混合版本设备的实际升级仍需本机复核 |
| 8. Core 保留发现与安全路由 | Core 身份/配对/Schema/提供者/期限/撤销测试；独立 wheel 的真实 serve 与错误 Schema 拒绝 | 未部署共享服务；现网所有权与服务管理器配置未验证 |
| 9. UI 分清授权、插件、系统与同步 | 发布后 GET 同 digest/revision、完整提供者键、所有者竞争、错误码与引导/保存失败文案测试 | 真机权限撤销、敏感确认和系统后台限制待验收 |
| 10. 多 Agent、账号、审批保持隔离 | 既有 Core/Android/DSH 回归、Agent 别名游标拒绝、owner 切换、审批 requested/resolved 和未知结果保护 | 本机无法新建链接的卸载链接用例明确跳过，不列为已验证安全事实 |

## 修改文件

本次基于 dev 共修改 63 个文件。

### 运行实现

- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/GatewayRuntime.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUi.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/CoreAgentConversationRepository.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/EncryptedConversationMirror.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationMirror.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationMirror.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationStore.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationStore.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventSynchronizer.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventSynchronizer.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalog.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/onboarding/screens/PermissionsScreen.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityPublisher.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityPublisher.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilitySyncState.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilitySyncState.kt)
- [apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt](../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreen.kt)
- [apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/StreamHealth.kt](../../apps/android/conversation-domain/src/main/kotlin/com/openandroidintelligence/conversation/model/StreamHealth.kt)
- [apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/components/StateViews.kt)
- [apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/state/WorkbenchController.kt)
- [apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt](../../apps/android/conversation-ui/src/main/kotlin/com/openandroidintelligence/conversation/workbench/WorkbenchScreen.kt)
- [apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/agents/AgentSessionClient.kt](../../apps/android/gateway-client/src/main/kotlin/com/openandroidintelligence/gateway/agents/AgentSessionClient.kt)
- [apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt](../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/PairingGrantState.kt)
- [integrations/hermes-session-adapter/src/oai_hermes_adapter/bridge.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/bridge.py)
- [integrations/hermes-session-adapter/src/oai_hermes_adapter/event_sync.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/event_sync.py)
- [integrations/hermes-session-adapter/src/oai_hermes_adapter/host_history.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/host_history.py)
- [integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/runtime.py)
- [integrations/hermes-session-adapter/src/oai_hermes_adapter/sessions.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/sessions.py)
- [integrations/hermes-session-adapter/src/oai_hermes_adapter/tool_events.py](../../integrations/hermes-session-adapter/src/oai_hermes_adapter/tool_events.py)
- [oai-core/src/oai_core/adapters/remote.py](../../oai-core/src/oai_core/adapters/remote.py)
- [oai-core/src/oai_core/gateway/core.py](../../oai-core/src/oai_core/gateway/core.py)
- [oai-core/src/oai_core/sessions.py](../../oai-core/src/oai_core/sessions.py)
- [scripts/core_setup.py](../../scripts/core_setup.py)
- [scripts/install.sh](../../scripts/install.sh)
- [scripts/setup.sh](../../scripts/setup.sh)

### 自动化测试

- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AuthSessionLifecycleTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/AuthSessionLifecycleTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeAgentBackgroundWorkTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/NativeAgentBackgroundWorkTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUiTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/SettingsCapabilityUiTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationMirrorTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeConversationMirrorTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventOwnerTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventOwnerTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventSynchronizerTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeEventSynchronizerTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeRecoveryCommitTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/conversations/NativeRecoveryCommitTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalogTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/onboarding/PermissionCatalogTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityPublisherTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/CapabilityPublisherTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilitySyncStateTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/DeviceCapabilitySyncStateTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginInstallStoreTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginInstallStoreTest.kt)
- [apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreenTest.kt](../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/PluginManagementScreenTest.kt)
- [apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/components/StateViewsTest.kt](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/components/StateViewsTest.kt)
- [apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchCloseSubmissionTest.kt](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchCloseSubmissionTest.kt)
- [apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchEventCommitRecoveryTest.kt](../../apps/android/conversation-ui/src/test/kotlin/com/openandroidintelligence/conversation/state/WorkbenchEventCommitRecoveryTest.kt)
- [apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/agents/NativeEventSyncClientTest.kt](../../apps/android/gateway-client/src/test/kotlin/com/openandroidintelligence/gateway/agents/NativeEventSyncClientTest.kt)
- [apps/android/platform-kernel/src/test/kotlin/com/openandroidintelligence/kernel/PairingGrantStateTest.kt](../../apps/android/platform-kernel/src/test/kotlin/com/openandroidintelligence/kernel/PairingGrantStateTest.kt)
- [oai-core/tests/native_hermes_contract.py](../../oai-core/tests/native_hermes_contract.py)
- [oai-core/tests/native_hermes_origin_contract.py](../../oai-core/tests/native_hermes_origin_contract.py)
- [oai-core/tests/test_cli_mcp.py](../../oai-core/tests/test_cli_mcp.py)
- [oai-core/tests/test_hermes_native_origin.py](../../oai-core/tests/test_hermes_native_origin.py)
- [oai-core/tests/test_native_event_sync.py](../../oai-core/tests/test_native_event_sync.py)
- [tests/test_core_setup_transaction.py](../../tests/test_core_setup_transaction.py)
- [tests/test_install_script.py](../../tests/test_install_script.py)
- [tools/test_smart_test.py](../../tools/test_smart_test.py)

### 文档与门禁

- [.github/workflows/oai-core.yml](../../.github/workflows/oai-core.yml)
- [docs/architecture/2026-10-29-native-session-recovery.zh-CN.md](../../docs/architecture/2026-10-29-native-session-recovery.zh-CN.md)
- [docs/architecture/2026-10-30-native-event-sync.zh-CN.md](../../docs/architecture/2026-10-30-native-event-sync.zh-CN.md)
- [docs/contracts/device-capability-publication-android-v1.md](../../docs/contracts/device-capability-publication-android-v1.md)
- [docs/reviews/2026-10-30-dev-reliability.zh-CN.md](../../docs/reviews/2026-10-30-dev-reliability.zh-CN.md)
- [docs/reviews/2026-10-30-pr66-android-ci-fixtures.zh-CN.md](../../docs/reviews/2026-10-30-pr66-android-ci-fixtures.zh-CN.md)
- [scripts/README.md](../../scripts/README.md)
- [tools/smart-test.py](../../tools/smart-test.py)
