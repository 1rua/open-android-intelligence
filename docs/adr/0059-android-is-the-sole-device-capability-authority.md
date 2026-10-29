---
status: accepted
date: 2026-10-28
---

# Android 客户端统一决定设备能力授权

本 ADR 修订 ADR-0054 中“设备权威”可能包含服务端设备能力许可的表述，并取代既有文档中要求
Gateway 账号再开启设备能力的规则。此决策已在
`feature/android-device-authority-20261028` 分支实施，待 PR 合入 `dev` 和真实手机验收；
分支实现与当前证据见[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)。
[只读修复计划](../reviews/2026-10-28-device-capability-authorization-repair-plan.zh-CN.md)和
[Core 基线证据](../reviews/2026-10-28-device-capability-authorization-core-evidence.zh-CN.md)
固定记录 e5 阶段的源码事实和测试结果，不是本分支实施报告。逐项需求见
[授权需求修订](../superpowers/specs/2026-10-28-device-capability-authorization.md)。

## 决策

Android 客户端是设备能力授权的唯一权威。用户安装并启用设备插件后，该插件声明的设备能力
默认开启，不再要求为 Gateway 账号单独开能力开关，也不再要求为正常使用重复勾选一份配对
能力 grant。只安装而未启用的插件不能执行；用户已经明确设置的手机本地关闭状态必须保留。

“默认开启”只决定本地能力开关的默认值。执行仍由 Android 检查插件与作者身份、有效提供者、
当前账号和配对上下文、系统权限、本地数据范围、资源与期限，以及所需的敏感操作确认。
短信发送、屏幕控制等所需的逐次确认不能由插件启用、远端配置或默认值替代；通知采集及
应用白名单、屏幕本地许可和 MediaProjection 等系统授权仍独立生效。缺少可靠本地授权入口时，
相关敏感操作继续不可用，远端不能代为批准。

OAI Core 负责连接认证、设备绑定、能力发现、协议与 Schema 验证、请求路由及可靠交付。
它可以根据 Android 发布的当前状态拒绝路由，也必须拒绝身份、配对、签名、版本、期限或
请求关联错误；它不能维护另一份账号级设备能力许可来覆盖 Android 的决定。

Hermes、DSH 及第三方 Agent Gateway/Session Adapter 只负责协议接入、宿主身份映射与会话
桥接，不提供额外的设备能力授权设置。设备调用使用同一个 Core CLI/API/MCP 入口及明确
绑定调用账号和设备的凭据。会话桥 Token、宿主账号映射和会话可见范围都不能充当设备凭据。
只实现会话桥的插件无需成为设备请求链的必经节点。

## 分开的安全层级

| 层级 | 权威与用途 | 本次决定 |
| --- | --- | --- |
| 连接身份认证 | Core 验证账号、登录或受限调用凭据、TLS 和签名 | 保留；未认证调用不得发现或操作他人设备 |
| 设备配对与隔离 | Core 与 Android 绑定账号、安装实例、设备、公钥及配对代次 | 保留；不能跨账号、跨设备或跨配对复用上下文 |
| 设备能力授权 | Android 本地插件启用、能力开关、系统权限与安全确认 | Android 唯一裁决；已安装且启用插件的能力默认开启 |
| 协议与执行一致性 | Core 和 Android 校验 Schema、提供者、版本、幂等、期限及撤销 | 保留；拒绝过期或无法确认的状态，不构成额外许可 |
| 宿主接入与审计 | 桥 Token、宿主账号 allowlist、会话能力声明、最小关联审计 | 保留其各自边界；不得扩展为设备授权开关 |

## 迁移约束

移除的是服务端 `device-capability-policy:v1` 等账号级设备许可及其否决作用，不是批量移除
所有 `allowlist`、所有拒绝或所有 `grantRevision`。配对代次、Android 本地状态版本、撤销、
请求 Claim/Result/ACK 绑定继续生效。Android 与 Core 通过可选单例头
`X-Open-Android-Intelligence-Device-Authorization: android-v1` 协商此模型；该头不扩展封闭
JSON Schema，不改变 `SchemaContractHash` 或 Gateway Protocol v2 版本。

历史服务端关闭值不得转换成 Android 本地关闭；用户已经明确拒绝的本地权限和开关也不得
因升级被清空或重置。新默认值应在 Android 本地按可验证来源完成迁移并重新发布状态，
Core 不能凭数据库里旧 grant 缺失来猜测许可，更不能伪造 Android READY。
未确认的旧快照、在途请求、历史撤销标记和混合版本必须按修复计划处理，已撤销或结果未知的
操作不得因为取消远端开关而复活或重放。

账号、主密钥、设备与配对 ID、原生会话和历史均不重建、不合并；回滚须恢复一致的数据版本。
本分支实现已有 Core 209 项与授权协商 4 项通过记录；Android 模块测试已有阶段结果，App 完整
Fast 套件仍在主 Agent 验证中，真机验收仍待完成，`dev` 合入须通过 PR Review。当前状态和证据以
[实施记录](../reviews/2026-10-28-device-capability-authorization-implementation.zh-CN.md)为准。
