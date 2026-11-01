# 通知与短信范围界面保真修复

日期：2026-11-01。状态：本次UI保真补充的规则、实现、自动化、APK构建与Terra模拟器分项已完成；1f338778远端检查全成功。原全局授权完整运行矩阵仍未闭合，实体手机验收BLOCKED。
需求见[全局规格 UI-01 至 UI-06](../superpowers/specs/2026-11-01-global-device-authorization.md#2026-11-01-用户补充选择界面与既有功能保真)。

## 用户新增要求：无应用数量上限、禁止自行设计

用户明确要求取消通知推送256应用上限。先前上限及“超限后修正保存”的实现和测试是未经用户要求自行加入的设计，现已被UI-07/08取代；保留下方历史记录便于审计，不能把它当成当前产品需求。

本次移除界面保存、通知范围模型和加密读取的应用数量限制，不用其他数量替代。回归改为1025个应用全选保存、搜索/反选、取消与重开，并验证完整持久化/重启恢复和实际事件范围。用户另外要求移除每插件下方“恢复上一版本”按钮，以及主动推送菜单进入退出与其他设置菜单一致，分别按UI-09/10执行。

## 已确认的回归

`97f601f7` 中新增 `PushSettingsContent` 要求手填通知包名、短信发件人地址；`SmsPassiveScopeSettings` 也用地址文本代替用户要求的全部/关键词/电话号码。与此同时，原有 `NotificationAppPicker` 仍提供搜索、逐项选择、反选及草稿保存，未被移除。问题是新增页面没有复用已有交互，而不是系统无法提供应用列表。

原被动通知范围仍通过 `NotificationPolicyPort` 保存并携带 `expectedRevision`。复用选择器不意味着共用被动/主动授权，推送范围必须保存在自己的安装实例配置中。

## 规则与文案边界

- 已写入 `AGENTS.md` 的通用规则：功能或底层重构不擅自降级既有 UI，需求未涉及的文案不任意改写。
- 通知继续使用原有“选择应用”“保存”“反选”等文字与行为；显示应用名称/图标/包名，包名只作内部身份。搜索、全选/清空针对当前列表，未安装或不可见的已选项仍可保留或移除。
- 短信新增范围选项仅使用用户指定的“全部”“关键词”“电话号码”。关键词在本机内容上判定，正文开关控制外发正文；选条件模式而清单为空时不得扩大为全部。
- 主动推送总开关、接收端点、正文许可、队列设置及被动范围继续独立，升级保留用户已有选择与未确认数据。

## 实施入口和审查重点

| 层 | 入口 | 必须验证的行为 |
| --- | --- | --- |
| 通知 UI | `NotificationAppPicker`、`NotificationPushScopeSettings` | 搜索、正选/反选、草稿取消、不可见项保留、查询重试和保存失败；只成功保存后关闭 |
| 短信 UI | `SmsScopeSelector`、`SmsPassiveScopeSettings`、`PushSettingsContent` | 默认全部、关键词/号码选择、空条件提示、回调保存真实模型；其余文案不改 |
| 权威保存 | `DevicePushRuntime.configure`、`SharedPreferencesPairingGrantStore` | 原子检查编辑时配置，冲突不覆盖；本地模型版本屏障防止旧程序忽略关键词后扩大许可 |
| 被动读取 | `ProductionPluginHost.mediate`、`SmsReadPrimitive`、`AndroidSmsInboxReader` | 远端参数只可缩小本机范围；正文关闭不外发正文；执行与返回前重新检查 |
| 主动采集/队列 | `DevicePushComposition`、`SmsPushSource`、`DevicePushRuntime`、`PushStateCodec` | 原生事件真实过滤、非匹配行游标可推进、重投复核；v1/v2/v3旧队列与收据无损读取 |

## 自动化与运行证据

- 文档相对链接、代码块、阶段任务 JSON 与 `git diff --check` 已通过；仅证明文档与补丁格式，不证明功能。
- UI 定向62项全部通过、0 fail/error/skip：PushSettingsScreen 9、NotificationPushScopeSettings 9、SmsPassiveScopeSettings 11、原 NotificationCollectionScreen 17、原 InstalledNotificationApps 16；退出码0；其后新增超限回归及真实过滤/迁移/并发保存定向通过见下节，不用UI通过代替完整运行验收。
- 本轮 Fast、PR 门禁均完整退出0；精确数量及环境skip见下节。
- Terra 新 APK覆盖升级与1小时自然到期通过；通知选择及短信新模式实际调用正在验证。旧短信样本链路不单独证明新模式。
- 实体手机：未连接，物理真机验收仍 BLOCKED，不能以模拟器替代。

## Terra 有界复测顺序与待闭合项

1. 新 APK 覆盖安装前后核对现有账号、短信被动号码范围/正文关闭、主动号码范围/接收端点、1 小时保留期和未 ACK 事件；不清空数据、不重建凭据。
2. 先只编辑/取消通知应用草稿，验证搜索、正选、反选和重新打开仍为原保存状态；被动与主动分别核对。
3. 只切换短信模式草稿，验证全部/关键词/电话号码、空条件不可保存，退出后原范围保留。旧推送配置不应因新默认全部而扩张。
4. 5556 专用 SMS 事件 17:09:20 注入且未 ACK；至少 18:09:20 后刷新本机队列，核对真实过期计数及远端清理。此前不改已保存范围、正文开关或时钟，避免用撤销冒充到期。
5. 旧事件到期核对后，再保存本机关键词与电话号码范围，用专用固定合成样本经真实查询/OS 收件箱检查命中、不命中、正文关闭与被动/主动独立。只读接收端不得提前 ACK 冒充完整生命周期。

这些是测试计划而非通过证据；实际步骤、requestId、事件 ID、版本/哈希和截图须完成后追加。

## 独立复核：超限草稿恢复保存

复核确认 P2：主动通知选择超过 256 项时使用与并发冲突相同的 rejection，选择器禁用保存；随后取消一项到 256 项，旧 rejection 仍不清除，用户只能重载并丢掉草稿。修复须将可编辑纠正的本地数量错误与必须重载的保存/并发拒绝分开，不能用任意勾选变化清掉所有错误。已添加 257→256 的行为回归，先复现再修改，最终已通过并与原冲突/异步拒绝断言共同保留。

冻结后的短信 runtime/codec/范围 standalone 49 项通过，覆盖 v1/v2/v3、非空撤回账本、收据/到期保留与并发保存；模块首轮28项通过。最新 composition、持久迁移与分页新增测试已在最终定向构建和 Fast/PR 中通过，不将早一轮编译冒充最终源码通过。

## 最终定向构建与回归

- 超限草稿先以真实 Compose 回归复现：257 项拒绝后减到 256 项，保存仍 Disabled，测试失败；拆分本地 validationError 与必须重载的 rejection 后，同测试及原并发/异步拒绝测试通过，未清除冲突保护。
- 最新源码 `assembleFullDebug`、`assembleFullDebugAndroidTest` 与定向 60 项同一次 Gradle 完整退出0（1分23秒）：通知选择器10、持久迁移9、推送composition3、SMS推送scope9、SMS source5、SMS原生reader10、读取原语11、内核scope3，均0 fail/error/skip。
- App APK SHA-256：`1ec8841c6fc7957c2fd6cf0e0fa0f77dec3d63f2ccfa1936b8d204ad89a81e5a`；测试 APK：`d5ee749ae541b9c88d1f03a05dca1411065824c29e4dc21171657fc4fa31ef6e`。两包 apksigner verify 通过，使用已有同一 debug 证书，未更换签名身份。
- 此包来自 `97f601f7` 加本次未提交生产补丁，20份改动生产源码摘要已保存于忽略的运行目录，不冒充原提交的APK；最终提交后需核对生产摘要保持一致。
- 安全与迁移独立静态审查未发现新增确切P1/P2。v3→v4仅变模型、不增加revision的迁移保留旧scope语义；已完成缓存结果仍可能按原fence恢复交付，不能简单以进程重启推断全部缓存已清除。收紧范围会更新revision，动态跨版本缓存压力场景仍未验证。
- Fast/PR 门禁已通过，Terra新APK界面/实际过滤复测仍在执行；不将定向测试扩大成整套验收完成。

## 本轮 Fast 与 PR 门禁

本次 UI/范围/迁移及CI同步补丁冻结后，`npm run test:fast` 与 `npm run test:pr` 均完整退出0。Core 634 passed、1 skipped、6 subtests；Hermes native143、DSH37、通信测量25通过。Android App1347、gateway-client320、device-primitives55、platform-kernel83、conversation-ui684均0 fail/error/skip；PR复用增量结果，未重编译无变化模块。Core skip仍为托管文件系统无法创建符号链接的危险卸载路径环境限制，不是授权断言被跳过。

Terra新包覆盖安装后18:09:17仍见短信队列1/1、expired0；18:12:43一次真实刷新变为0/0、expired1、待远端清理0，未ACK/改scope/改时钟。本机1小时自然过期PASS，不凭Core60秒buffer空结果推断手机到期。

## 提交与远端检查

修复已提交并推至 PR #71：`aed310ab` 为审批/会话测试同步；`1f338778ecd6c39fc8eb0bd11134867d6c238568` 为通知/SMS范围与界面规则。已核对生产补丁摘要与Terra测试APK一致。

新head已触发 OAI Core and Session Adapters Run `41296937078` 与 Android APK Artifact Run `41296932216`，记录时均 in_progress；不watch或循环等待，不将触发视为通过。PR仍目标dev、保持Review，不合并。

Terra实际拍摄的[新包短信队列自然到期界面](evidence/2026-11-01-global-device-authorization/terra-sms-queue-natural-expiry.png)已由主Agent检查为仅设置与计数、不含账号凭据或短信正文后复制公开；主Agent复制与目检不另算设备执行证据。

## Play 变体保真验证

考虑Play包可见性与Full不同，另外执行了同一新增UI及原通知回归：PushSettingsScreen9、NotificationPushScopeSettings10（含超限修正）、SmsPassiveScopeSettings11、原NotificationCollectionScreen17、原InstalledNotificationApps16，共63项，0 fail/error/skip，Gradle退出0、1分29秒。Play不增加QUERY_ALL_PACKAGES；沿用原列表可见性说明，不将Robolectric列举结果当成真实手机全量应用证明。

Terra已实际拍摄并验证通知名称搜索、单项正选/取消、反选及过滤列表全选；[搜索截图](evidence/2026-11-01-global-device-authorization/terra-notification-picker-name-search.png)和[反选截图](evidence/2026-11-01-global-device-authorization/terra-notification-picker-invert.png)已公开，取消/重开与同值保存尚待确认，不能仅凭截图标全部PASS。

## 短信关键词真实执行验证

Terra将5556被动query保存为关键词OAI-SAMPLE-SMS-5556-20261101、READ_ONLY、正文关闭后，复用合法CLI/MCP实测三项PASS：

| 场景 | requestId | 实际结果 |
| --- | --- | --- |
| sender22专用样本命中本机关键词，CLI查询 | devreq_afcbe5cd-ab1d-447e-b059-8ccfc244cba2 | succeeded，1条元数据，无正文 |
| 正文关闭时MCP追加远端query搜索 | devreq_0d78310d-90fa-4f7b-b607-adb6c4c31fad | denied / LOCAL_DATA_SCOPE_DENIED |
| sender44另一合成样本不含本机关键词，CLI查询 | devreq_90f6ed58-acd4-4b44-b451-6788c3de6745 | succeeded，0条 |

[脱敏证据](2026-11-01-global-device-authorization-sms-keywords-evidence.txt)。这证明新关键词控件连接实际过滤与正文披露边界，不只验证配置或内存模型；主动关键词与未选端点对照仍待新OS样本。

[短信关键词、电话号码、全部三模式及正文关闭的实际界面](evidence/2026-11-01-global-device-authorization/terra-sms-passive-keywords.png)由Terra在新包拍摄，主Agent确认只包含本次固定合成标记后复制公开。主动范围的草稿取消、空关键词禁存和重入持久值已由Terra核对；随后仅将主动scope保存为另一个专用关键词、正文关闭，不改被动范围，准备新OS样本验证独立性。

## 主动关键词与有效未选端点对照

Terra在真实UI将主动范围设为另一个独立关键词OAI-SAMPLE-SMS-KEYWORD-PUSH-20261101、正文关闭，保留原已选接收端点第1代及1小时保留期；被动关键词不变。在接收观察器确认两端有效且初始0条后，仅一次通过Android OS注入sender15550105505/固定合成正文，上海时间18:39:43.369978741至18:39:43.387304266，返回OK。

新事件push_d962b57eaed998860e5b70797cf8a381c9540aeec67b5c4539226472d6a93169、投递delivery_0a66aed671d646228c170c3e35d71e31：已选端点收到sms.received，sender正确，body字段缺失；同一时间有效但未选入白名单的另一个端点返回0条。观察97.727秒PASS，未ACK。正文标记由本机配置和唯一合成注入关联，不通过Core负载中的正文推断。该新事件对照补足旧事件/失效端点不能证明有效未选端点隔离的证据缺口。

## 本次 UI 补充完成核对

Terra在5554/user10实测通知名称/包名搜索、正选/反选、过滤列表全选、0项草稿取消后重开仍保留原OAI一项；以完整包名搜索后同值保存，选择器关闭、无错误，原主动开关/正文/A与新B端点选择均保留，未发送新通知。

5556短信主动新关键词的非匹配对照：18:42:07队列1/1；18:43:02仅一次OS注入sender15550106606/body为固定不匹配合成样本，返回OK；18:43:48刷新仍1/1、累计过期1、待清理0，未ACK/改scope。该证据与匹配样本metadata投递共同证明选择连接实际采集过滤。

2026-11-01 18:43:50只读核对PR #71，head=1f338778，Core3.11、Core3.12、Hermes native、DSH、Android、APK及插件签名7项全部SUCCESS。没有watch或阻塞等待CI，PR仍OPEN、目标dev、自动合并关闭。后续证据文档提交不替换本节的源码head归属。

本次用户纠正对应task-010可标完成。原task-008与整体goal继续active：物理设备、完整屏幕允许/撤销/竞争和生命周期矩阵的剩余部分没有因此变为PASS。

完整来源记录见[三条CLI/MCP查询](2026-11-01-global-device-authorization-sms-keywords-evidence.txt)、[有效未选端点注册与租约](2026-11-01-global-device-authorization-sms-unselected-evidence.txt)及[主动关键词元数据投递](2026-11-01-global-device-authorization-sms-keyword-push-evidence.txt)。仅公开脱敏回执，运行凭据保持在忽略目录。

## UI-07/08取消应用数量上限的当前验证

已移除PushSettingsScreen数量校验和超限文案、NotificationPushScope的应用数量检查，以及codec通用strings(count256)对通知范围的隐藏限制；通知列表改按当前二进制实际剩余字节读入，不设替代应用数量常量，v1–v4布局保持。

先将UI回归改为1025项全选保存，旧代码稳定失败；删除上限后，同测试及搜索反选/取消/重开、保存冲突/失败回归通过。最新App定向27项0 fail/error/skip：选择器10、真实加密保存恢复2、codec迁移5、推送页面9、审批审计诊断1。standalone53项通过，包含257/1025/2049项真实加密文件重启、最后一项实际入队、未选拒绝、旧v1–v3各1025项无损读取及损坏格式拒绝。

原上限与修正上限错误后的保存测试已被用户明确取消；不把那段历史实现继续算产品需求。UI-09/10插件按钮及菜单容器转场补丁另行验证，不调整自定动画时长。

## UI-09/10插件入口与菜单容器定向验证

每插件“恢复上一版本”按钮已移除，保留启用/停用、系统权限、安装、卸载及底层历史版本。DEVICE_PUSH路由与同级菜单继续使用NavHost原四项AppTransitions，生产和不可用状态现在都复用SettingsPageScaffold，统一背景、系统边距、标题栏返回，不新增时长、曲线或自行改写正文。

新增7项定向全部通过：多个插件无恢复按钮、其余操作有效；SCREEN/BOTTOM_SHEET各自背景覆盖、唯一返回；普通和减少动态两模式进入/返回沿用既有位移/无位移语义。最初两个取图测试因重绘回调超时失败，仅改测试取图方式后通过，原像素/控件/返回断言保留，未改生产加载行为。新APK与综合门禁、实际转场观测仍在执行。

最新用户UI补丁同次APK构建与App58项定向回归全部通过（Settings11、Plugin20、通知选择10、持久化2、codec5、推送页面9、审计诊断1），0 fail/error/skip。APK SHA-256为67d7d5330d2b6ebbb3e227f88cffe19a2b8a55c251e0e913e1a7d26e4124fa68，签名仍为原debug身份；此包包含工作树中未提交screen补丁，其真实screen失败另列，UI截图不证明screen控制成功。

[实际主动推送统一页面容器上半屏](evidence/2026-11-01-global-device-authorization/terra-device-push-unified-page-top.png)由Terra在5554/user10拍摄，主Agent裁剪去除下方路由标识后公开；仅证明页面背景/标题栏/内容布局，动态转场需另看实际录屏与自动化进退断言。

## 最新补丁综合验证与实际页面观测

最新Fast完整退出0：Core634/1符号链接环境skip、Hermes143、DSH37、通信25；Android App1361、screen-control44、device-primitives61全部0 fail/error/skip。PR门禁进行中。审批审计测试此前一次初始化NPE原因尚未证实，保留初始化/审计就绪明确断言与原15秒上限，定向及本轮完整Fast均通过，不将诊断assert误写成生产根因修复。

Terra在67d7d5包实际从授权管理进入主动推送并点击一次顶栏返回：两页完整背景/顶栏保持，返回授权管理，无重复返回；通知原1个应用及推送/正文开关不变。前/中/后截图仅证明布局和返回，尚未录屏，动态观感未单独实测，不能把截图标为motion PASS。插件按钮抽查待完成，原正常/减少动态进退已由自动化时钟断言。

最终PR门禁也完整退出0，复用无变更增量结果；最新APK之后所有生产源码摘要未变。用户最新三项UI修改具备本地Fast/PR、58项综合定向、53项standalone证据；待实际插件按钮抽查与菜单录屏归档后更新分项完成状态。
