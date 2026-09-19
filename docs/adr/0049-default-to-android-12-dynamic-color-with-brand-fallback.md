---
status: accepted
date: 2026-09-20
---

# 默认启用 Android 12+ 系统动态取色，品牌色令牌降级为兜底方案

App 的主题现在是「动态取色优先」：Android 12（API 31）及以上默认使用 `dynamicLightColorScheme/dynamicDarkColorScheme`（Monet），只有在系统不提供动态取色、或用户在「外观与动效」里显式关闭时，才回落到 `BrandLightColorScheme / BrandDarkColorScheme`。用户手动关闭后偏好会持久化到本机 SharedPreferences，下次启动仍然生效。

这取代了 UI 规格 `2026-08-29-android-conversation-assistant-ui-design.md` §4.2 中的决定——原文要求 V1 只使用固定品牌令牌、不得使用系统动态取色，理由是担心破坏产品身份与状态辨识，并写明"动态取色若以后加入，必须作为独立设计任务重新验证语义色和品牌一致性"。本 ADR 就是那次独立的取舍决策，理由是：Monet 是 Material 3 在 Android 上的原生形态，官方应用与系统设置都以它为默认，跟随系统壁纸的 App 与 Android 的视觉语言一致性带来的收益大于固定配色的品牌一致性；而用户完全可以在设置里一键关回品牌配色。

品牌一致性通过"语义角色绑定"而不是"固定色值"保留：

- 所有界面只能通过 `MaterialTheme.colorScheme.*` 的角色取色，`AppColors` 被降为 `internal` 且仅供 `Theme.kt` 构造降级方案，任何组件都不得直接引用其中的色值；`conversation-ui` 的 `ThemeContrastTest.uiComposablesNeverHardcodeColors` 与 `app` 的 `MaterialTokenGuardTest.appScreensNeverHardcodeColors` 在单测层持续守卫这条边界。
- 状态语义仍然只由"语义 + 结构"承载，不依赖具体色相：成功/失败/选中分别对应 `primaryContainer` / `errorContainer` / `secondaryContainer` 角色，降级的容器层级（surface → surfaceContainerLowest → Low → Container → High → Highest）必须逐级不同，并由 `ThemeContrastTest.fallbackSchemeCoversEveryContainerRoleDistinctly` 校验。
- 唯一的视觉签名「信号缝线」的结构（一条主色短线 + 一个 tertiary 小圆点）保持不变，只是在动态取色下圆点颜色跟随系统调色，而不是固定的茶金 `#B8874A`。

降级方案自身必须是完整的 M3 角色集：缺任何一个色角色（尤其是 5 级 surface container）都会逼着业务组件回到写死颜色的老路上，因此在 `Theme.kt` 里一次性定义全部角色（含 `scrim`、`inverse*` 与所有 tertiary 角色），并由 `ThemeContrastTest` 对正文/辅助文字与所有容器的组合校验 ≥ 4.5:1 对比度。

代价与约束：动态取色下 App 的主色不再可控，不同壁纸会让截图与品牌物料看起来不一致；因此在营销物料、产品官网截图等需要固定品牌色的场合，应关闭动态取色后再取图。此外 minSdk 为 34，实际所有可安装设备都支持动态取色，降级路径主要是给用户"关闭"选项与未来 minSdk 下探时使用。
