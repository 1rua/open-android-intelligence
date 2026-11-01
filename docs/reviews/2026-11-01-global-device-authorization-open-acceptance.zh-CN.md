# 全局设备授权：剩余明确要求与验收证据复核
> 收尾更新：本文主体为本轮较早的独立复核快照。其后[临时许可A/B/C](2026-11-01-global-device-authorization-temporary-lifecycle.zh-CN.md)已证明有效期内主动撤销及受控突然进程死亡/重启失效；[Accessibility撤销](2026-11-01-global-device-authorization-system-revoke.zh-CN.md)已证明系统OFF拒绝及恢复READY。R2其他权限/策略复核与R3关联取消/交付边界仍待测。`ff720cde`已在线核对7checks成功；screen仍FAIL，补丁仅本地`c71735b0`。下文后续顺序是未执行计划，用户要求收尾后没有继续安装或操作设备。最新结论以[收尾报告](2026-11-01-global-device-authorization-closeout.zh-CN.md)为准。

复核日期：2026-11-01，Asia/Shanghai。范围：当前工作树的规格、源码入口、测试及运行存证；唯一写入本文。未修改 screen 或其他源码，未运行设备、Gradle、业务测试、Core 请求或远端 PR/CI 查询；不关闭原全局任务。

结论：UI-01 至 UI-10 已有分层完成证据，不重新列为待实现；全局验收还剩下文八组。最近保存的 screen 真实允许仍失败；系统权限撤销、有效临时许可跨重启失效、通知被动读取、主动队列撤销/真正离线恢复、息屏/Doze、真实旧授权样本迁移及物理验收均未闭合。以下是原始要求的缺口，不新增产品限制、功能或调度设计。

## 一、基线与证据口径

- 原始依据为[完整规格][SPEC]的 GLOBAL-01 至 GLOBAL-13、UI-01 至 UI-10、完整用户任务及屏幕手动确认补充。交叉核对[coverage 索引][COVERAGE]、Terra、SMS、CoreRuntime 的后续存证，较新且对应同一对象的回执优先于早期阶段描述。原审计基线为 `dev@3c9b82d1ef18062fc7dd09546efe6136803079cc`。
- 本次本地 HEAD 和本地 `origin/feature/global-device-authorization-20261101` 引用均为 `ff720cde6b2057725925d24ea95403bb54a5f22c`，其父提交为 UI 修正 `c0779957e8a8881883c3435f6a2d3f23e39d165c`。用户已说明两者推至 PR；本地引用相同不等于本轮在线核对过远端。
- 工作树原有未提交 screen task-011、测试及相关文档改动全部保留。提交、包含未提交补丁的 APK、当前源码分别判断。
- **source** 的“已证实”仅表示核对到相应实现入口；**test** 的“已证实”表示对应版本有已保存通过记录；二者均不代替 **emulator** 的真实请求与系统交互。**physical** 独立列示。
- **未完成**包括未运行、缺可对应的回执或已知 FAIL；后者明确写出错误。**阻塞**只用于缺物理手机、缺真实旧状态样本、未实际发生系统回收等具体条件。不能把来源不同的成功拼成同一条完整链路。

### 当前必须区分的版本

| 对象 | 已保存事实 | 本次可作的结论 |
| --- | --- | --- |
| UI 提交 `c0779957`、证据提交 `ff720cde` | 通知不限应用数量、插件按钮及统一菜单页面已提交；[UI 专项最终段][UI]和[Terra 菜单段][TERRA]已归档 | UI 补充按已有证据完成；不覆盖 screen task-011 |
| UI APK `67d7d533…124fa68` | 真正覆盖安装、正常进退录屏与单卡按钮抽查；包内同时含未提交 screen 补丁 | 页面与正常动画可记 PASS；不能称 PR 提交本身已包含 screen 修复 |
| screen APK `3b32b5a197dfe29e83be0b35a37d729b2fce76c4692cfcc675f66a88e696748b` | 设备端哈希匹配；reader 成功；CONTROL 卡真实批准后最终 `failed / SCREEN_STALE_REFERENCE` | 是本次找到的最近一次 screen 控制运行结果，仍 FAIL，详见下节 |
| 已保存本地门禁 | `ui-followups-fast.exit=0`、`ui-followups-pr.exit=0`；对应日志均以“所有阶段验证通过”结束 | 证明当次测试工作树通过，不是任意后续改动通过 |
| 当前 screen 诊断增量 | root 21:49 仅追加 `application_equivalence` 布尔/数字诊断，放行条件未改；[新源码清单][EQUIV-MANIFEST]的 10 个生产文件均匹配；[诊断日志][EQUIV-LOG] exit0、`BUILD SUCCESSFUL in 51s`，44 项 screen 测试零失败，并构建 App/test APK | **source/test 已证实**；没有重复全量测试，真实诊断尚未执行，不能写控制允许 PASS |

本次只读核对新清单 **10/10 production matches**。当前诊断 App SHA-256 为 `2ad99dae1f55092aa51e73763a8bc4b452a6a155a054ecf59c354b98311e8e75`，test APK 为 `db709b77ee888368ef12a5f55cd39bc6cd2aeac288cc924c19552e59449d03c3`。[Fast 日志][FAST-LOG]、[PR 日志][PR-LOG]继续证明此前完整门禁；当前仅诊断增量由本次定向测试和构建存证覆盖，无需据旧清单差异判成未验证。

### 最近一次 screen 终态，不能被提交阶段 PENDING 覆盖

只读取既有 `3b32b5` 私密记录的必要元数据，未复制正文、节点、凭据或密钥：

| 环节 | 对应记录 | 事实 |
| --- | --- | --- |
| 安装来源 | [设备安装摘要][SCREEN-INSTALL] | App 与 test APK 的设备端哈希均匹配交付产物 |
| reader | [链路阶段记录][SCREEN-CHAIN] | `devreq_374f6470-a29b-444e-bdf9-7c70677fcb26` 已 `succeeded` |
| control 提交 | 同一阶段记录 | `devreq_d4d1a275-ac35-4e07-96f4-fd364ae8cd9d` 的 `controlState=pending` 属于 `phase=control_submitted`，不是最终状态 |
| 真正批准 | [批准记录][SCREEN-APPROVAL]、[采集元数据][SCREEN-META] | `allow_once`；2026-11-01 20:46:45.527934 至 20:46:45.555677 点击，ADB exit 0；控制卡出现及批准点击均有记录 |
| 最终结果 | [control 终态回执][SCREEN-FINAL] | 同一 requestId，`state=failed`、`result.outcome=failed`、`result.data.code=SCREEN_STALE_REFERENCE`；无控制成功依据 |

上述链接指向本工作树已忽略的 runtime，属于本地可复核材料，未作为公开原始日志提交。主记录中 `416196…` 包“批准后 stale”和 `774620…` 包“prepare 阶段、尚未弹卡即 stale”仍是各自版本的事实；不能混写阶段，也不能替代本次找到的更新终态。新诊断包 `2ad99d…` 已通过匹配源码的定向测试/构建，真实诊断未执行；5554 因多用户共享 APK，在安装前停止，改由 5556 合成数据环境在临时许可阶段结束后独占 UI 验收，见[screen 接续记录][SCREEN-CONTINUATION]。不将新包构建改写为设备运行 PASS。

## 二、已有完成证据，排除重复验收

这些分项不再按早期 coverage、Terra 预检或主记录中的 PENDING 重新开工；其版本及范围仍按原证据保留，物理层仍未通过。

| 原要求 | 已证实的分项与边界 | 较新证据 |
| --- | --- | --- |
| GLOBAL-01、11：跨 Core 与统一入口 | A→B→A 的安装实例总开关/手动模式和系统权限保持；两 Core 合法请求、拒绝/取消链路已到达 Android | [Terra 双 Core 段][TERRA]、[主记录][MAIN]；不能再写“两 Core 合法请求均待测” |
| GLOBAL-04、05：手动确认与计时 | 通话手动拒绝、确认超时；SMS 仅本次批准成功；5/30/60 分钟真实授予，30/60 跨 Core、不同 Agent 声明即时共享；未批准 limit4 重新等待；5 到期延迟复验及 30/60 自然到期重新确认均 PASS | [CoreRuntime 临时授权段][CORE]、[完整计时摘录][TTL]；5 分钟不是精确到期后 5 秒边界实测，不要求重等 30/60 分钟 |
| GLOBAL-06、UI-01..06：短信与选择器 | SMS MANUAL/READ_ONLY 专用样本、电话号码越界拒绝、关键词命中/不命中、正文关闭及真实 MCP 正文搜索拒绝；主动号码正文与关键词元数据投递、非匹配不入队；通知名称/包名搜索、正反选、取消重开和同值保存已完成 | [UI 专项][UI]、[SMS 报告][SMS]、[CoreRuntime][CORE]；元数据 `text=null` 不等于完全没有该字段 |
| GLOBAL-07、11：接收端及认证 | CLI 注册/发现、真实 MCP stdio 发现、未认证 401、A/新 B 独立凭据双向 403；新且有效的未选 SMS 端点在同轮新事件观察中为 0 条 | [CoreRuntime][CORE]；旧 B 过期后换代为空不作原代次零泄漏证明，端点 403 也不替代设备绑定失败用例 |
| GLOBAL-08：通知 ACK 与自然到期 | 同一 OS 通知向同一 Core/账号下 A、独立 B 双端投递；逐端 ACK、重复 ACK 幂等、第一事件正文清理及手机 2/4→2/3→1/2；后台第二事件恢复及双方 ACK 有记录；SMS 新包保留未 ACK 队列、真实 1 小时自然到期已通过 | [CoreRuntime 双端与 ACK 段][CORE]、[SMS 到期终点][SMS]；第二通知恢复与早先队列事件的对应有上下文推断成分，不能扩大为真正断网下同 ID 重投全闭环 |
| GLOBAL-09：有限生命周期 | debug 同 UID 突然进程死亡后身份、策略、插件和队列保留；全部退出后匹配 SMS 不新增队列；离线本地范围草稿取消和保留期 1→2→1 小时保存已通过；当前 d097 绑定经官方设备密钥入口 d097→d097 恢复 | [SMS 生命周期最终汇总][SMS]；普通 `am kill` 未回收不能报系统回收 PASS，普通密码路径 c16→d097 不能报旧 c16 恢复 |
| GLOBAL-01、09、11：真正断 Core 传输 | 仅断 5556 的 Core A reverse 映射后，`devreq_d1416263…e399be8e` 为 `DEVICE_OFFLINE / queued=true`；恢复后同一请求成功返回元数据，没有重新提交 | [CoreRuntime 最后段][CORE]、[脱敏离线回执][OFFLINE]；此处是被动请求恢复，24 小时排队寿命与本机 5 分钟确认等待不同，不是主动推送补发证明 |
| GLOBAL-11：Core 终态字段 | B 在 `97f601f7`、A 在 `1f338778` 分别完成真实等待后取消，终态不再携带 `localAuthorizationState`；A 更新保留账号、密钥及账本 | [B 回执][CORE-B]、[A 回执][CORE-A]、[主记录后续段][MAIN]；不能继续把 A 旧快照残留 executing 当成新服务现状 |
| UI-07..10：最新补充 | 1025 项选择器行为、2049 项真实加密文件恢复及旧 codec 读取；移除共用插件恢复按钮；正常/减少动态自动化通过；正常菜单进退录屏由 root 离线取帧核验，Call Log 单卡实际按钮抽查通过 | [UI 最终交付段][UI]、[Terra 最后段][TERRA]；未实际安装上千假应用、未逐插件实机遍历、减少动态未另录视频，不据此重开已完成 UI 子任务 |

## 三、八组剩余明确要求

表中“已证实（存证）”始终限于已有测试版本。每行同时列出 source / test / emulator / physical；不是把单测通过改称整项验收通过。

### R1．屏幕真正允许、点击/滑动及独占 FIFO

需求对应：GLOBAL-03、04、10；规格屏幕手动确认补充、§4、§8、§10.2。“真实允许不得写 CONTROL PASS”的约束继续有效。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| fresh reader → 匹配确认卡 → 仅允许本次 → 原动作实际发生；普通手动 READ 保持可用，目标真实变化仍拒绝 | **已证实（当前源码）**：task-011 后端/驱动补丁未提交，新 manifest 的 10 个生产文件均匹配，诊断未改放行条件 | **已证实（当前增量）**：44 项 screen 测试零失败、App/test APK 构建通过；既有手动确认、reader 退场及 A→B→A 回归保留 | **未完成**：新 `2ad99d` 诊断尚未执行；此前最近 `3b32b5` 真批准仍 stale，控制允许仍无 PASS | **阻塞**：无已授权物理手机 |
| 真实点击与滑动；屏幕读/截图/控制各自三级策略，READ_ONLY 控制仍需确认，AUTO 不替代系统批准 | **已证实**：[RemoteScreenRuntime][S-SCREEN]、后端和独立能力入口存在，当前控制实现正确性未闭合 | **已证实（存证）**：[runtime 测试][T-SCREEN]、[后端测试][T-BACKEND]含隔离和最终副作用检查 | **未完成**：未见实际 click/swipe 成功；back 成功本身也不能替代这两项 | **阻塞**：同上 |
| 多个真实控制请求互斥、FIFO；30 秒租约、显式心跳、累计最长 5 分钟；完成/取消/连接超时释放；过期和排队后复核、只读并发、用户紧急停止 | **已证实**：[租约管理器][S-LEASE]和按请求接线存在 | **已证实（存证）**：[租约测试][T-LEASE]及 runtime 的 FIFO、排队撤销、心跳、紧停与连接丢失断言；未证明实际调度 | **未完成**：无真实并发控制/租约链路回执；允许动作失败是本组后续运行的前置阻塞 | **阻塞**：同上 |

R1 的当前短板包括真实执行和当前源码验证，不是再做一遍只读成功或拒绝卡片。后续仍须保留原确认 UI、revision/目标变化检查；本文不提出新的窗口忽略规则或修复设计。

### R2．系统权限撤销、剩余三级模式及调用错误语义

需求对应：GLOBAL-03、06、11、12；规格 §2.1、§4.2/4.3、§9、§10.2。系统“初次拒绝/随后允许”与“已获准后撤销”分别验收。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| 已允许后撤销 READ_CALL_LOG、READ_SMS、Notification Listener；App/能力状态反映缺权，停止相应执行/采集，敏感结果交付前复核；AUTO 不绕过 | **已证实**：[生产 Host][S-HOST]、[Driver][S-DRIVER]及[推送 runtime][S-PUSH]存在执行/交付检查 | **已证实（存证）**：[客户端授权测试][T-CLIENT]的 `automaticModeStillChecksCurrentSystemPermissionAndLocalRevocation`、[推送测试][T-PUSH]的采集/投递再检查 | **未完成**：通话初次拒绝与允许、SMS 获权、监听开启已做；没有对应三类权限获准后撤销的完整运行链 | **阻塞**：无物理手机 |
| 通过真实系统入口撤销 Accessibility / MediaProjection，阻断新读/控制及尚未交付的敏感结果，策略配置不被改成另一模式 | **已证实**：screen 生命周期、projection 撤回及最终原语检查入口存在；当前 R1 修复另列 | **已证实（存证）**：runtime 的 projection 撤销清字节、capture 中撤销禁止发布、后端权限丢失测试 | **未完成**：系统无障碍启用、单 App MediaProjection 同意和截图 PASS，不是系统撤销 PASS | **阻塞**：同上 |
| 通话专用样本读取与三级模式；SMS 剩余 AUTO；通知被动模式见 R4，screen 见 R1 | **已证实**：独立总开关和三模式本机裁决存在 | **已证实（存证）**：[授权控制器测试][T-AUTH]及客户端测试覆盖模式、总开关与系统门槛 | **未完成**：通话成功使用未来空范围，未闭合专用非空样本/完整三级；SMS MANUAL/READ_ONLY 已过，不重复列缺口 | **阻塞**：同上 |
| 剩余能力缺失/未实现、版本/协议不兼容和可信设备绑定失败的明确调用错误，不以 READY 或网络错误掩盖 | **已证实**：[Core 设备服务][S-CORE]及契约诊断入口存在 | **已证实（存证）**：[诊断测试][T-DIAG]及 Core 契约/认证测试覆盖分类与绑定；来源见主记录 | **未完成**：已读运行索引未见这些剩余分支可逐条对应的 CLI/MCP 回执；已有 401、端点 403、拒绝/超时/取消及真实离线结果继续有效 | **阻塞**：Android 侧物理复核缺设备；Core 侧不因此受阻 |

### R3．临时授权的撤销、取消关联与重启失效

需求对应：GLOBAL-04、05；规格 §4.1、§10.3。本组不重复自然到期及 30/60 分钟跨 Core 共享。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| 持有尚未到期的临时许可时重启客户端：保存的总开关/模式/范围不变，旧临时许可不能复活或延长，后续请求重新确认 | **已证实**：[控制器][S-AUTH]的票据为进程内状态；[持久化][S-MIGRATION]保存独立长期策略 | **已证实（存证）**：控制器单次重启失效、策略重启恢复及撤销栅栏断言；不作为限时许可真实重启证明 | **未完成**：5556 突然进程死亡时没有配套未过期临时许可的前后请求；两项不能拼成 PASS | **阻塞**：无物理手机 |
| 用户撤销/总开关关闭、关联请求取消、批准后参数或操作越权时，在执行前和敏感交付前均不沿用原批准；单次不共享成限时许可 | **已证实**：控制器的 cancel/revoke/current、Host 与 Driver 再检查入口存在 | **已证实（存证）**：`policyChangeCancellationAndRevokeFenceApprovals`、[客户端测试][T-CLIENT]、[请求 fence 测试][T-FENCE] | **未完成**：普通等待请求取消、未批准 limit4 重新等待已过；尚缺“有效许可已建立后”的撤销/关联取消与交付边界运行证据 | **阻塞**：同上 |
| 请求待确认时同时提供 App 卡片与系统通知，通知展示对应请求并随结束清理 | **已证实**：[Driver][S-DRIVER]的 `showConfirmationNotification` 和请求结束清理存在 | **未完成**：卡片按钮/选项有通过存证，未见后台系统通知的独立验证记录 | **未完成**：卡片及批准/拒绝/超时已过，缺后台待确认系统通知的独立运行回执，可并入一次新的待确认请求补证 | **阻塞**：同上 |

本组沿用既有确认 UI 和选项，不另造交互。

### R4．通知被动真实数据链与独立范围

需求对应：GLOBAL-03、06；UI-01、02、04、06；规格 §5.1/5.2、§10.2。选择器 UI 的完成不等于通知数据查询已验收。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| 用专用 OS 通知通过真实 CLI/MCP 被动查询，证明已选应用命中、未选应用/空范围不越权，标题/正文/元数据按本机范围交付，在已支持查询契约内正文关闭不被可用搜索字段绕过 | **已证实**：[通知查询入口][S-NOTIFICATION]与 Host/本机范围接线存在 | **已证实（存证）**：[通知 HTTP 测试][T-NOTIFICATION]、collection/query 测试；picker 行为和保存冲突另已通过 | **未完成**：已存双端通知正文是主动推送结果；缺本行被动 requestId、真实范围及正文边界回执 | **阻塞**：无物理手机 |
| 通知每次手动/只读/自动执行与拒绝；被动范围与主动范围分别保存、分别生效 | **已证实**：被动策略与主动配置分开；[主动源][S-NOTIFY-PUSH]不通过被动确认 | **已证实（存证）**：模式、范围和独立推送测试 | **未完成**：历史“被动手动设置下主动推送”及通知选择器已通过；剩余为真实被动允许/拒绝及两份不同范围在数据链上的证明，不能要求每条主动推送再确认 | **阻塞**：同上 |

通知/SMS 的推送关闭、缩小范围及移除端点后的停止/清理统一归 R5；系统监听撤销统一归 R2，避免重复建项。参数以执行时发现的现有 schema 为准，不要求新增尚未实现的过滤字段。

### R5．主动队列真正离线、跨路由、撤销及登录补发闭环

需求对应：GLOBAL-06、07、08、09；UI-04、05；规格 §5.3、§6、§7、§10.3。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| 未 ACK 主动事件真正断 Core 传输→恢复：同 eventId、正确 route/代次、逐端状态及去重；重新登录后核验端点/连接再补发有效队列；容量与溢出按现实现有设置处理 | **已证实**：[加密队列/runtime][S-PUSH]、transport 与 [composition][S-COMPOSITION]存在 | **已证实（存证）**：真实加密文件重启、容量拒绝、稳定事件/收据、logout 取消与 login 复核、Core buffer 到期重填及 ACK 幂等 | **未完成**：真正断传输的被动查询已过；通知后台恢复和 SMS 队列保留/过期已过；缺“主动同事件真正断网重投”和“重登后有效事件补发”回执，不能用已过期事件补写成功 | **阻塞**：无物理手机 |
| 手机移除端点、关闭通知/SMS 推送或收窄范围后立即停止相应采集/外发，删除不再允许保留的本地待发数据；在途撤销与恢复重投前复核，精确远端撤回不误清另一类有效事件 | **已证实**：`configure/authorized/maintain`、精确撤回与 Core 撤销账本存在 | **已证实（存证）**：[推送测试][T-PUSH]的收窄/移除、发送前撤销；[Core 端点测试][T-ENDPOINTS]的精确撤回、迟到投递和另一账号隔离 | **未完成**：无对应手机撤销→本地清理→远端结果的完整运行链。5556 最后 20:11:49 为队列 0/0、待远端清理 2；那是敏感内容已自然到期，不是用户撤销成功，也不是远端清理完成 | **阻塞**：物理缺设备；旧不可用 route 的清理应按实际返回存证，不能把旧事件换到新代次 |
| 同一手机多选分属不同 Core/账号的稳定端点，实际事件只去所选完整路由，ACK 与恢复不串账号/代次 | **已证实**：[端点服务][S-ENDPOINTS]、完整 route 与 Android 白名单保存存在 | **已证实（存证）**：ownership、accountScope/generation、旧事件不转新路由及 Android 逐端状态测试 | **未完成**：现有通知 A/B 是同一 Core/账号下独立凭据，SMS 已选/有效未选对照也非跨 Core 多选；不能合称跨 Core/账号推送实测已过 | **阻塞**：无物理手机 |

历史租约大多已到期，最后 SMS 队列已自然清空；后续需要新的专用事件和执行当时合法、已被手机选中的完整 route。不能靠历史 endpointId、过期后新代次空列表或续长旧事件 TTL 补足证据。72 小时默认、1 小时至 7 天可配、容量及时钟/codec 保护已有 source/test；不新增另一套产品上限，也不要求重等已完成的 1 小时到期。

### R6．息屏、Doze、系统回收及全部退出后的执行暂停

需求对应：GLOBAL-01、09、11；规格 §7、§10.2/10.3。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| 息屏、Doze/后台网络限制下原生采集/任务调度、队列保留与恢复，不能依赖永久 WS/SSE | **已证实**：[composition/JobService][S-COMPOSITION]、原生源与本地队列接线存在 | **已证实（存证）**：调度、logout 取消、恢复只补报一次；不是系统限制实测 | **未完成**：未见息屏或 Doze 的步骤、系统状态及恢复回执 | **阻塞**：无物理手机，厂商后台行为未测 |
| 系统实际回收后的恢复；区分用户强行停止与显式重新打开，不承诺强停后自动运行 | **已证实**：持久状态及恢复入口存在 | **已证实（存证）**：加密文件/策略重建及恢复测试 | **阻塞**：单次 `am kill` exit0 但旧 PID 未消失；debug 同 UID 突然死亡已过，仍不等于系统回收。用户强行停止及显式重开边界未完成 | **阻塞**：同上 |
| 全部退出时停止远程能力处理及在途敏感执行/外发，保留配置、权限、有效队列并继续到期清理 | **已证实**：登录门槛、授权投影撤销及推送取消存在 | **已证实（存证）**：控制器 logout fence、通知 provider 退出拒绝、推送在途取消 | **未完成**：退出 UI、保留状态、匹配 SMS 不入队及随后自然到期已过；还缺退出态真实远程请求不执行/在途停止与出站停止的对应回执。重登补发归 R5 | **阻塞**：同上 |

原 c16 绑定未恢复；d097 官方密钥恢复已完成。前者不能由后者回填，也不能仅据普通密码产生新 device 的观察认定所有重登入口失败；本轮不据此新增登录流程设计。

### R7．真实旧版本授权样本迁移及身份/业务数据保留

需求对应：GLOBAL-01、02、03；UI-05；规格 §3、§10.3、§12 的兼容与无回归要求。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| 真正含旧 Core/账号/配对 grant 和迁移标记的旧安装覆盖升级：只清废弃能力状态，首次手动/推送关闭，幂等、失败可恢复，旧敏感请求不能沿旧许可执行，无迁移死循环 | **已证实**：[持久化迁移][S-MIGRATION]同事务清理及旧请求 fence 存在 | **已证实（存证）**：[持久化测试][T-MIGRATION]的原子、失败恢复、旧写入和默认值；不是旧安装真实样本 | **阻塞**：当前记录只有本轮 v3→v4 范围/队列保留；未找到带旧按账号 grant 的真实升级验收样本。需先准备可追溯的旧版本测试安装状态 | **阻塞**：无物理手机 |
| 同轮升级保留登录、账号、设备密钥/身份、会话、附件、插件、系统权限和明确范围；旧限制不被新默认全部扩大 | **已证实**：只移除旧能力键，保留其他数据；范围/队列 codec 向后读取及版本屏障存在 | **已证实（存证）**：无关键保留、策略/旧范围/加密队列迁移；业务回归见主门禁 | **未完成**：本轮覆盖安装和 d097 恢复已证明部分保留；尚无上述真实旧样本的完整前后对照，原 c16 也未被本次恢复证明 | **阻塞**：同上 |

这里缺的是旧版本真实写出的状态与升级链，不是再手写一份旧 JSON 就可替代。可在隔离测试用户用真实旧版产生样本再覆盖升级；保留原数据及身份，不能清数据、新建密钥或重建配对来消除失败。

### R8．物理运行矩阵与最终可复核交付

需求对应：GLOBAL-12、13；规格 §10.1—10.4、§11、§12。

| 尚待闭合的原要求 | source | test | emulator | physical |
| --- | --- | --- | --- | --- |
| Terra 依原矩阵验收，并保留版本、实际命令/步骤、requestId、截图、日志及失败复测；分开自动化、模拟器与物理结论 | **已证实**：规格与报告已采用分层口径 | **已证实（存证）**：自动化按各自版本保存；当前诊断增量的 44 项测试、构建及 10/10 源码匹配另已核对 | **未完成**：仅 R1—R7 所列剩余分项，不把已经完成的分项整行重测 | **阻塞**：既有设备清单没有已授权实体手机；通话、SMS、通知、屏幕、系统撤销、后台等物理矩阵均未闭合 |
| 文档与实际行为一致，完成要求的门禁后交付 dev PR 并保持 Review | **已证实**：规格/ADR/指南/协议及 PR 材料存在；UI 已按补充更新 | **未完成**：完整任务最终收口待剩余验收；当前仅诊断增量已有匹配定向测试/构建，不要求重复全量或无变动模块 | **未完成**：完整验收报告须待缺口闭合；本文提供真实剩余状态 | **阻塞**：物理证据仍缺；PR Review 状态本身已有存证，见下一节 |

不把物理阻塞扩散成“模拟器也不能继续”；也不要求通过安装上千个物理应用、另造调度 UI 或新增权限限制来验收原任务。

## 四、PR 与 CI：仅按已存证据核对

| 对象 | 现有存证 | 本轮结论 |
| --- | --- | --- |
| PR #71 | [UI 最终交付段][UI]、[进度记录][PROGRESS]及[PR 文本][PR-BODY]记载：OPEN、base=`dev`、`autoMergeRequest=null`；用户确认 c0779957/ff720cde 已推 | **已证实（存证）**：按既定 dev Review 交付，不合并、不启用自动合并。本轮未访问远端，不能声称刚刚实时复核 |
| `1f338778` | 18:43:50 的七项 checks 全部 SUCCESS，含 Core 3.11/3.12、Hermes、DSH、Android、APK、插件签名 | **已证实（该 SHA）**：不能覆盖 c0779957、ff720cde 或当前未提交 screen；旧 97f601f7 的失败也不能当当前 CI 状态 |
| `c0779957` | Run `41311820490`（Core/Adapters）、`41311815681`（APK），最后保存为已触发 / in_progress | **未完成（最终结果未存证）**：不知道此刻是否已结束；不将触发算通过，不轮询、不 watch |
| `ff720cde` | root 提供 2026-11-01 21:54 只读快照：6 项 SUCCESS，Android 仍运行；本次据用户转交记录，未自行远端查询 | **部分已证实**：该 SHA 的六项成功有效，Android 最终结果仍未存证；不轮询、不移用旧全绿 |
| 本地 Fast / PR 与 screen 诊断 | UI 补充完整门禁 exit0；当前仅诊断增量另有 44 项 screen 零失败、App/test 构建 exit0 和新 manifest 10/10 匹配 | **已证实（对应范围）**：完整门禁与当前定向证据分开；未重跑全量不是当前增量未验证，真实诊断仍待执行 |

完成本次复核不意味着完成原全局 goal，也不改变 PR 的 Review 边界。本次不提交、不推送、不修改任务状态或已有 PR 文本。

## 五、不需要物理手机的最短后续验收顺序

以下交由主 Agent 按 R1—R8 推进，**本轮未执行设备/构建步骤**。新诊断包已就绪；5554 因共享 APK 停止安装，screen 改到 5556，在其临时许可阶段结束后独占 UI 验收，不与其他测试同时驱动界面。

1. **复用本次版本与证据清单，确定可测对象。** 后续执行时只核对当次 App/Core/设备绑定和完整 route；历史租约不得视为仍有效。已有 UI、自然到期、401/端点 403、两 Core 取消字段和被动离线同请求恢复直接引用，不再重做。screen 复用已匹配的新 manifest、44 项测试及诊断 APK，剩余为 5556 真实运行。
2. **先补被动能力与模式，再在同批专用样本上撤销系统权限。** 一轮覆盖 R4 通知应用/正文边界、R2 通话样本/三级及 SMS 剩余 AUTO；对获准状态做真实系统撤销，保存 requestId、设备缺权和 Core 终态。仍待证明的调用错误按已支持契约逐项存证；不把端点拒绝当作设备绑定拒绝。
3. **用新的有效未 ACK 主动事件合并 R5 与 R6 的退出部分。** 核实当时有效且已选中的不同 Core/账号 route；保留逐事件/逐端标识，验证真正断传输后的同 ID 重投和去重；在有有效事件时退出并确认停止执行/出站，再从既有合法绑定登录补发。用仍有效的独立对照事件验证关闭推送、缩小范围、移除端点及精确撤回；已撤销事件不能拿来证明补发成功。容量依据现有实现与既有自动化，不自行制定新上限。
4. **补一次“许可仍有效时重启”，并复用恢复窗口测后台。** 分别存证本机撤销/关联取消，然后在真正持有未到期临时许可时重启客户端，验证旧批准不再有效而长期策略保留；不重等 30/60 分钟自然到期。接着补息屏/Doze、系统实际回收及强停/显式重开边界。旧 PID 未消失就继续标“未实际回收”，不能靠重复普通 am kill 或已有 debug 死亡记录改判。
5. **独立准备 R7 真实旧样本，再覆盖升级。** 可与前述非屏幕验收的准备工作并行，但使用隔离测试用户和真实旧版写出的数据，记录旧版来源及身份/业务/权限/队列前后对照。缺样本时保留具体阻塞，不把本轮 v3→v4 或手写夹具当旧授权升级 PASS。
6. **在 5556 执行已就绪诊断包：先真允许，再控制动作，最后排队。** 同一受控屏幕先 fresh reader→手动本次→原动作成功，并保留真实目标变化拒绝；成功后依次补 click/swipe、独立模式、FIFO/独占/心跳/到期/排队复核与紧停。Accessibility/MediaProjection 系统撤销可并入这一窗口。出现失败即保存该次链路，不通过切 AUTO、换 revision 或循环重试掩盖。不要与其他 UI/通知操作同时驱动该屏幕。
7. **按完成回执收口。** 将 R1—R7 的新结果按 source/test/emulator/physical 分层关联到同一版本；仅有新的生产行为变更时补相应增量验证，不为已验证的诊断重复全量。物理矩阵继续 BLOCKED，PR 继续 dev Review；远端 CI 只使用明确 SHA 的完成存证，不以前台等待或轮询补结果。

旧样本仍缺可追溯状态；screen 缺的是新包真实诊断，已有匹配的 source/test 证明。通知被动、系统撤销、临时重启及后台矩阵可在模拟器先行，不需要等待实体手机。新的执行证据必须由原任务指定的 Terra 流程实际产生，不能把本文复核本身算一次运行验收。

## 六、本次文档验证范围

本次只对本文执行本地自动检查：相对链接及目标、Markdown 表格/代码围栏、需求编号覆盖、最近 screen 终态/门禁与源码摘要一致性，并核对原有未提交文件的内容摘要未被本次写入改变。该检查不等于重新执行 Fast/PR，不产生设备、Gradle 或 CI 通过结论。

[SPEC]: ../superpowers/specs/2026-11-01-global-device-authorization.md
[COVERAGE]: 2026-11-01-global-device-authorization-coverage.zh-CN.md
[MAIN]: 2026-11-01-global-device-authorization.zh-CN.md
[TERRA]: 2026-11-01-global-device-authorization-terra.zh-CN.md
[SMS]: 2026-11-01-global-device-authorization-sms-emulator.zh-CN.md
[CORE]: 2026-11-01-global-device-authorization-core-runtime.zh-CN.md
[UI]: 2026-11-01-device-scope-ui-preservation.zh-CN.md
[TTL]: 2026-11-01-global-device-authorization-ttl-complete-evidence.txt
[OFFLINE]: 2026-11-01-global-device-authorization-sms-offline-recovery-evidence.txt
[CORE-A]: 2026-11-01-global-device-authorization-core-a-1f338778-evidence.txt
[CORE-B]: 2026-11-01-global-device-authorization-core-b-97f601f7-cancel-evidence.txt
[PROGRESS]: ../plans/2026-11-01-global-device-authorization/harness-progress.txt
[PR-BODY]: ../plans/2026-11-01-global-device-authorization/pr-body.md
[EQUIV-MANIFEST]: ../plans/2026-11-01-global-device-authorization/runtime/screen-equivalence-diagnostic-source-manifest.json
[EQUIV-LOG]: ../plans/2026-11-01-global-device-authorization/runtime/screen-equivalence-diagnostics.log
[SCREEN-CONTINUATION]: 2026-11-01-global-device-authorization-screen-continuation.zh-CN.md
[FAST-LOG]: ../plans/2026-11-01-global-device-authorization/runtime/ui-followups-fast.log
[PR-LOG]: ../plans/2026-11-01-global-device-authorization/runtime/ui-followups-pr.log
[SCREEN-INSTALL]: ../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-screen-task011-3b32b5-installation-private.json
[SCREEN-CHAIN]: ../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-screen-task011-3b32b5-chain-state-private.json
[SCREEN-APPROVAL]: ../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-screen-task011-3b32b5-chain-control-approval-private.json
[SCREEN-META]: ../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-screen-task011-3b32b5-chain-log-metadata-private.json
[SCREEN-FINAL]: ../plans/2026-11-01-global-device-authorization/runtime/evidence/terra-screen-task011-3b32b5-chain-control-status-private.json
[S-AUTH]: ../../apps/android/platform-kernel/src/main/kotlin/com/openandroidintelligence/kernel/CapabilityAuthorization.kt
[S-MIGRATION]: ../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/PairingGrantPersistence.kt
[S-HOST]: ../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/ProductionPluginHost.kt
[S-DRIVER]: ../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/plugins/DeviceExecutionDriver.kt
[S-SCREEN]: ../../apps/android/device-primitives/src/main/kotlin/com/openandroidintelligence/device/primitives/RemoteScreenRuntime.kt
[S-BACKEND]: ../../apps/android/screen-control/src/main/kotlin/com/openandroidintelligence/screen/AndroidScreenBackend.kt
[S-LEASE]: ../../apps/android/control-ports/src/main/kotlin/com/openandroidintelligence/control/ScreenControlLease.kt
[S-PUSH]: ../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntime.kt
[S-COMPOSITION]: ../../apps/android/app/src/main/kotlin/com/openandroidintelligence/mobile/DevicePushComposition.kt
[S-NOTIFICATION]: ../../apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationAgentQueryGateway.kt
[S-NOTIFY-PUSH]: ../../apps/android/notification-collector/src/main/kotlin/com/openandroidintelligence/notifications/NotificationPushSource.kt
[S-CORE]: ../../oai-core/src/oai_core/device_service.py
[S-ENDPOINTS]: ../../oai-core/src/oai_core/endpoints.py
[T-AUTH]: ../../apps/android/platform-kernel/src/test/kotlin/com/openandroidintelligence/kernel/CapabilityAuthorizationTest.kt
[T-MIGRATION]: ../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/PairingGrantPersistenceTest.kt
[T-CLIENT]: ../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/ClientCapabilityAuthorizationTest.kt
[T-FENCE]: ../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/DeviceGrantFenceTest.kt
[T-SCREEN]: ../../apps/android/device-primitives/src/test/kotlin/com/openandroidintelligence/device/primitives/RemoteScreenRuntimeTest.kt
[T-BACKEND]: ../../apps/android/screen-control/src/test/kotlin/com/openandroidintelligence/screen/ScreenBackendTest.kt
[T-LEASE]: ../../apps/android/control-ports/src/test/kotlin/com/openandroidintelligence/control/ScreenControlLeaseTest.kt
[T-PUSH]: ../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/push/DevicePushRuntimeTest.kt
[T-NOTIFICATION]: ../../apps/android/app/src/test/kotlin/com/openandroidintelligence/mobile/plugins/NotificationCapabilityHttpTest.kt
[T-DIAG]: ../../oai-core/tests/test_device_diagnostics.py
[T-ENDPOINTS]: ../../oai-core/tests/test_push_endpoints.py
