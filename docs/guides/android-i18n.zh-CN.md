# Android 国际化 (i18n) 开发指南

- 架构：`:ui-resources` 模块集中管理资源
- 命名：`<domain>_<submodule>_<element>_<state>`
- 调用：Compose 使用 `stringResource`，业务层映射 `ErrorCode` 到 `@StringRes`