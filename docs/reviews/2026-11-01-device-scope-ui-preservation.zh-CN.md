# 通知与短信范围界面保真修复

日期：2026-11-01。状态：文档已落盘，代码与回归实施中；未宣称完整运行验收通过。
需求见[全局规格 UI-01 至 UI-06](../superpowers/specs/2026-11-01-global-device-authorization.md#2026-11-01-用户补充选择界面与既有功能保真)。

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
- UI 定向62项全部通过、0 fail/error/skip：PushSettingsScreen 9、NotificationPushScopeSettings 9、SmsPassiveScopeSettings 11、原 NotificationCollectionScreen 17、原 InstalledNotificationApps 16；退出码0。真实过滤、迁移、并发保存相关测试仍在收尾，不用UI通过代替。
- Fast、PR 门禁：本次新增补丁尚未运行完成，不能复用旧提交的通过结果。
- Terra 新 APK UI 与真实调用复测：待构建后执行。旧短信样本已在真实系统收件箱触发主动链路，尚不能证明新模式。
- 实体手机：未连接，物理真机验收仍 BLOCKED，不能以模拟器替代。

## Terra 有界复测顺序（待执行）

1. 新 APK 覆盖安装前后核对现有账号、短信被动号码范围/正文关闭、主动号码范围/接收端点、1 小时保留期和未 ACK 事件；不清空数据、不重建凭据。
2. 先只编辑/取消通知应用草稿，验证搜索、正选、反选和重新打开仍为原保存状态；被动与主动分别核对。
3. 只切换短信模式草稿，验证全部/关键词/电话号码、空条件不可保存，退出后原范围保留。旧推送配置不应因新默认全部而扩张。
4. 5556 专用 SMS 事件 17:09:20 注入且未 ACK；至少 18:09:20 后刷新本机队列，核对真实过期计数及远端清理。此前不改已保存范围、正文开关或时钟，避免用撤销冒充到期。
5. 旧事件到期核对后，再保存本机关键词与电话号码范围，用专用固定合成样本经真实查询/OS 收件箱检查命中、不命中、正文关闭与被动/主动独立。只读接收端不得提前 ACK 冒充完整生命周期。

这些是测试计划而非通过证据；实际步骤、requestId、事件 ID、版本/哈希和截图须完成后追加。

## 独立复核：超限草稿恢复保存

复核确认 P2：主动通知选择超过 256 项时使用与并发冲突相同的 rejection，选择器禁用保存；随后取消一项到 256 项，旧 rejection 仍不清除，用户只能重载并丢掉草稿。修复须将可编辑纠正的本地数量错误与必须重载的保存/并发拒绝分开，不能用任意勾选变化清掉所有错误。已添加 257→256 的行为回归，先复现再修改，最终结果待追加。

冻结后的短信 runtime/codec/范围 standalone 49 项通过，覆盖 v1/v2/v3、非空撤回账本、收据/到期保留与并发保存；模块首轮28项通过。最新 composition、持久迁移与分页新增测试纳入最终 Fast/PR，不将早一轮编译冒充最终源码通过。

## 最终定向构建与回归

- 超限草稿先以真实 Compose 回归复现：257 项拒绝后减到 256 项，保存仍 Disabled，测试失败；拆分本地 validationError 与必须重载的 rejection 后，同测试及原并发/异步拒绝测试通过，未清除冲突保护。
- 最新源码 `assembleFullDebug`、`assembleFullDebugAndroidTest` 与定向 60 项同一次 Gradle 完整退出0（1分23秒）：通知选择器10、持久迁移9、推送composition3、SMS推送scope9、SMS source5、SMS原生reader10、读取原语11、内核scope3，均0 fail/error/skip。
- App APK SHA-256：`1ec8841c6fc7957c2fd6cf0e0fa0f77dec3d63f2ccfa1936b8d204ad89a81e5a`；测试 APK：`d5ee749ae541b9c88d1f03a05dca1411065824c29e4dc21171657fc4fa31ef6e`。两包 apksigner verify 通过，使用已有同一 debug 证书，未更换签名身份。
- 此包来自 `97f601f7` 加本次未提交生产补丁，20份改动生产源码摘要已保存于忽略的运行目录，不冒充原提交的APK；最终提交后需核对生产摘要保持一致。
- 安全与迁移独立静态审查未发现新增确切P1/P2。v3→v4仅变模型、不增加revision的迁移保留旧scope语义；已完成缓存结果仍可能按原fence恢复交付，不能简单以进程重启推断全部缓存已清除。收紧范围会更新revision，动态跨版本缓存压力场景仍未验证。
- Fast/PR 门禁与 Terra 新 APK 复测正在执行；本节不将定向测试扩大成整套验收完成。

## 本轮 Fast 与 PR 门禁

本次 UI/范围/迁移及CI同步补丁冻结后，`npm run test:fast` 与 `npm run test:pr` 均完整退出0。Core 634 passed、1 skipped、6 subtests；Hermes native143、DSH37、通信测量25通过。Android App1347、gateway-client320、device-primitives55、platform-kernel83、conversation-ui684均0 fail/error/skip；PR复用增量结果，未重编译无变化模块。Core skip仍为托管文件系统无法创建符号链接的危险卸载路径环境限制，不是授权断言被跳过。

Terra新包覆盖安装后18:09:17仍见短信队列1/1、expired0；18:12:43一次真实刷新变为0/0、expired1、待远端清理0，未ACK/改scope/改时钟。本机1小时自然过期PASS，不凭Core60秒buffer空结果推断手机到期。
