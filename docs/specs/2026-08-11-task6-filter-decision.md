# Task 6 capability filter decision

状态：已确认（2026-08-11）

Task 6 采用“按能力使用封闭过滤器”（`capability_specific_v1`）。每个
capability 必须引用一个版本化、闭合的 schema；未知 capability、缺失或
漂移的 schema 一律 fail closed，不允许模型或 adapter 提供自定义 selector
语言，也不接受通用 `filter_all_v1` 作为通知授权。

通知 capability 使用闭合 schema
`urn:open-android-intelligence:protocol:v1:filter:notifications_v1`：

- `packages`（可选）必须是至少一个合法 Android package name，唯一且按
  Unicode code point 排序；
- `fields`（可选）只能包含 `metadata`、`content`，至少一项、最多两项，
  唯一且按 Unicode code point 排序；
- `packages` 与 `fields` 至少提供一个；`notifications.metadata` 禁止
  `content` 字段；
- 授权、订阅、on-demand 查询和 auto-send 事件使用相同过滤语义；默认只
  返回 metadata，正文必须有独立的 content grant；loss marker 不因包名
  过滤而丢弃。

过滤器摘要固定为 `b64u(SHA-256(RFC8785_UTF8(filter)))`，摘要在授权和
replay admission 前校验。通知记录不携带 agent、workspace、session 或
job 身份，这些身份由已认证的 Bridge binding 注入。
