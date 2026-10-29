# Android 本地授权与能力状态发布

日期：2026-10-30。本约定细化 [ADR-0059](../adr/0059-android-is-the-sole-device-capability-authority.md) 的授权与同步职责；不新增 Core 账号级设备能力许可，也不让 Hermes/DSH Adapter 成为授权入口。

## 权威与可执行状态

Android 决定插件启用、当前提供者、本地能力开关、用户明确关闭、系统权限、数据范围、后台许可、屏幕会话和敏感确认。Core 保留账号/设备认证、配对、签名、Schema/版本/提供者、期限、请求关联和路由检查。Adapter 只负责宿主会话接入与调用转发。

`本机允许` 表达用户的本地决定，`SYNCED` 表达特定本地快照已被 Core 接收并读取验证，`READY` 表达某个具体提供者的当前可执行状态。三者分别展示；网络失败不撤销本地允许，发布成功也不能替代缺失的系统权限或敏感确认。授权模型未精确协商到 android-v1 时保持聊天连接，但设备发布与执行继续拒绝。

## 一致的绑定快照

PairingGrantStateHolder 将 binding 与对应授权 state 作为一个不可变、可见性受保证的快照发布。切换期间旧快照先失效，加载完成后核对状态自己的 pairingId，再发布新快照。查询新绑定时不能读到旧账号的授权。持久化失败保留原有效状态；旧格式继续走既有迁移复核，用户明确关闭的能力、同作者更新后的拒绝状态和独立后台许可均不重置。

## 发布、回读与失败恢复

发布器记录当前连接所有者、配对、本地授权 revision 与插件宿主 revision。每次实际传输前及响应回来后重新核对这些身份和版本；取消中的协程、旧连接或过时快照不能写成当前 SYNCED。

1. 读取 `/v2/pairings/current` 的真实 grantRevision/digest、协议能力和目录。
2. 用当前本地授权和插件库存生成 publication，保存加密 pending 记录。
3. 向 `/v2/pairings/current/capabilities` 发送带 expectedGrantRevision 的 publication。
4. 检查 ACK 的 digest 与 revision，再保存已确认回执。
5. 重新读取 pairing，确认同一 digest/revision 的能力目录；此后才一起发布 SYNCED、版本和目录。

ACK 已成功而回读暂时失败时，保留确认事实。下一次 GET 与当前快照一致即可收敛，不重复提交同一 publication。连接或本地快照已变化时，旧结果不能覆盖新状态；失败显示受限制的协议错误码，不输出任意网络响应正文、Token 或凭据。网络恢复沿既有周期协调重新同步当前授权。

## 提供者身份与界面

状态以 `(pluginId, capabilityId, capabilityVersion)` 为键。相同能力的两个提供者分别展示 READY、PROVIDER_NOT_SELECTED 或其他真实原因，不能因列表顺序或仅按 capabilityId 建表而互相覆盖。发布状态、版本、目录和错误构成同一条观察记录，避免成功状态配上旧目录。

| 状态或原因 | 用户含义 | 是否需要 Hermes 再次授权 |
| --- | --- | --- |
| 本机已关闭 / 旧授权待复核 | 在 Android 插件管理中处理，保留明确拒绝 | 否 |
| 系统权限缺失 | 由用户在本机授予相应系统权限 | 否 |
| 插件停用 / 提供者未选择 | 在 Android 启用插件或选择提供者 | 否 |
| SYNCING / SYNC_RETRYING | 当前 Android 快照正在发布；重试保留具体原因 | 否 |
| SYNC_FAILED | 发布或回读未完成，显示真实协议错误或网络状态 | 否 |
| AUTHORIZATION_MODEL_UNSUPPORTED | Core/客户端授权模型不兼容，设备能力保持不可执行 | 否 |
| SYNCED + READY | 当前具体提供者已同步且可路由；执行时仍复核安全门 | 否 |

不得把状态同步要求改写成“插件 setup 中开启设备许可”，也不得删除同步检查或自动授予系统权限来消除提示。设备请求在真正执行前继续复核绑定、授权 revision、系统权限、期限与必要的本机确认。只读/同步类请求沿已有稳定 requestId 与期限等待；高权限短暂请求离线时不延期自动执行；已超期或已撤销请求不复活。

修复证据、测试范围和未闭合的真机验收见[专项报告](../reviews/2026-10-30-dev-reliability.zh-CN.md)。
